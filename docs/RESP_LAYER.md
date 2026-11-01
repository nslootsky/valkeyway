# Client protocol layer: replacing resp-server

Status: **accepted** (decisions at the end)

## Why

### Measured baseline (2026-10-31)

24-core machine, 3-primary Valkey 9.1 cluster on localhost, proxy at commit `5e8a7dc`.
Reproduce with `scripts/bench-throughput.sh` and `scripts/bench-blocking-stall.sh`.

| clients | pipeline | proxy SET/GET rps | proxy p50 | direct to cluster |
|--------:|---------:|------------------:|----------:|------------------:|
| 1   | 1  | 34k / 35k   | 0.031 ms | 150k, p50 0.007 ms |
| 50  | 1  | 145k / 137k | 0.30 ms  | ≥600k |
| 200 | 1  | 151k / 139k | 1.1 ms   | ≥600k |
| 50  | 16 | 152k / 143k | 0.36 ms  | ≥1.2M |
| 200 | 16 | 154k / 147k | 11.6 ms  | ≥1.2M |

(Direct numbers are limited by valkey-benchmark itself; treat them as a floor.)

- Throughput plateaus at ~150k rps no matter the concurrency, and **pipelining gives nothing**.
- **Thread stall:** while one client sits in `BLPOP … 5`, 2 of 96 new connections waited **4.7 s** for a `PING` (direct to the cluster: max 14 ms).
- **Node stall:** while one client sits in `BLPOP`, every other client's commands to **that node** fail with `ERR Request timed out` after 2 s. Other nodes are unaffected.

### Root causes

| # | Cause | Where |
|---|-------|-------|
| 1 | With `parallelExecution()`, resp-server runs each command on the Netty event loop that read it; our handlers then block on `CompletableFuture.get()`. 48 loops ÷ ~0.3 ms RTT ≈ the observed plateau, and a blocking command freezes every connection on its loop. | `RespServerContext.processCommand`, all our handlers |
| 2 | One request in flight per connection; replies are flushed one by one. | `RespServerContext.processResponse` |
| 3 | `ReplayingDecoder` re-parses from the start on every partial frame and copies every argument. | `RedisDecoder` |
| 4 | Blocking commands share Glide's single multiplexed connection per node, so the server stops serving that connection for everyone. | Glide multiplexing + `CatchAllCommand` |
| 5 | Hardcoded 5-minute idle disconnect (Valkey's default is never). | `RespServer.channel` (`IdleStateHandler`) |
| 6 | RESP2 only, 32-bit integers only, no null array, no TLS, no pipeline hooks. | `RedisToken`, `RedisSerializer`, `RespServer.Builder` |

Causes 1–3, 5 and 6 come from resp-server's design: `RespCommand.execute()` returns a reply synchronously, so fixing them would change its core API. ValkeyWay only uses about 450 of its 2,650 lines (bootstrap, decoder, encoder, session), plus RxJava, which we don't need. Cause 4 is ours, whatever the library.

## Goals

- Never block an event loop. Many requests in flight per connection, with replies in request order.
- Single-connection overhead close to one extra network hop; throughput limited by Glide/cluster, not the proxy.
- A blocking command affects only the client that sent it.
- RESP2 and RESP3, 64-bit integers, null arrays, push frames (for pub/sub later).
- TLS and AUTH on the client side.
- Less code than today's resp-server + adapters; no RxJava.

Non-goals for this work: pub/sub, cross-slot emulation, per-client WATCH isolation. The design leaves room for them (see "dedicated leases").

## Architecture

```
Netty (epoll, NIO fallback; io_uring later if benchmarks justify)
 └─ per channel pipeline
     [SslHandler]?  →  RespDecoder  →  ConnectionHandler  →  (writes) ReplyEncoder
                                           │
                                     Connection (per-client state, in-flight queue)
                                           │ dispatch by command name
                         ┌─────────────────┼────────────────────┐
                    ASYNC handler     BLOCKING handler      LOCAL handler
                   (CatchAll: one     (SCAN, EXEC, GETKEYS   (SELECT, MULTI,
                    customCommand →    lookups; runs on a     HELLO, CLIENT
                    future)            virtual thread)        SETNAME …: no I/O)
```

| Component | Responsibility | Est. lines |
|-----------|---------------|-----------:|
| `server.RespServer` | Bootstrap, transport selection, socket options, TLS, lifecycle | 120 |
| `protocol.RespDecoder` | Incremental `ByteToMessageDecoder` state machine → `byte[][] args`; inline commands; limits matching Valkey (`proto-max-bulk-len` 512 MB, 1M args, 64 KB inline) | 180 |
| `protocol.Reply` + `ReplyEncoder` | Sealed reply types; writes straight into the `ByteBuf`, downgrading RESP3 types for RESP2 clients; preallocated `+OK`, `+QUEUED`, `:0`, `:1`, nulls | 200 |
| `protocol.GlideReplies` | Converts Glide result objects (`GlideString`, `Long`, `Double`, `Boolean`, `Map`, `Set`, arrays, exceptions) to `Reply` (replaces `TokenUtils`) | 120 |
| `server.Connection` | Typed per-client state (db, protocol, auth, tx queue/slots, scan cursor, name), in-flight queue, barrier logic, backpressure | 200 |
| `server.CommandTable` | name → handler + mode; replaces `CommandSuite` | 60 |
| Handler changes | Same logic, new signature `CompletionStage<Reply> handle(Connection, byte[][] args)` | (net ~0) |

About 900 lines in total, replacing resp-server (2,650) and RxJava.

## Request lifecycle and ordering

1. The decoder emits one `byte[][]` per command. The connection appends a **slot** to its FIFO and dispatches the command.
2. **ASYNC** handlers submit to Glide *synchronously on the event loop*. `customCommand()` returns immediately, so commands reach Glide in arrival order. Glide keeps per-node order on each node connection (an assumption about Glide's internals; phase 2 starts with a test that proves or disproves it), so every node sees a client's commands in the order the client sent them. Keys on different nodes share no state, so this gives the same observable results as a standalone server.
3. When a future completes (on a Glide thread), the slot is filled and a drain task is scheduled on the channel's event loop (once per batch, not once per reply).
4. The drain writes every completed slot from the head of the queue in order, then flushes once. Replies to a pipeline go out in a few large writes.

**Barriers.** Some commands must not overlap with others from the same client:

| Mode | Commands | Rule |
|------|----------|------|
| ASYNC | Everything routed by `CatchAllCommand` | Runs as soon as it arrives |
| LOCAL | SELECT, MULTI, DISCARD, HELLO, AUTH, CLIENT SETNAME/GETNAME/ID, RESET, QUIT, PROXY CONFIG | Waits until earlier slots are done; later commands wait for it (it changes state they depend on) |
| BLOCKING | SCAN, EXEC, CLUSTER, INFO, multi-step handlers | Same barrier rule; body runs on a virtual thread so it can be written as blocking code |
| DEDICATED | BLPOP, BRPOP, BLMOVE, BLMPOP, BRPOPLPUSH, BZPOPMIN/MAX, BZMPOP, XREAD/XREADGROUP with BLOCK, WAIT, WAITAOF | Barrier, then runs on a leased backend client (below) |

Inside MULTI, commands are only queued locally (no backend call); EXEC is the barrier.

Caveat: during resharding, Glide's MOVED/ASK retries can reorder two in-flight commands to the *same* slot. Pipelines to a standalone server never see this. I consider it acceptable, but it's worth knowing.

## Threading

- Event loops: one per core (no blocking, so no need for 2×).
- Glide completes futures on its own threads; completions only fill a slot and schedule a drain.
- BLOCKING handlers: `Executors.newVirtualThreadPerTaskExecutor()`. Java 25 no longer pins on `synchronized` (JEP 491); Glide's native calls pin only briefly while submitting.
- No platform worker pool.

## Backpressure and limits

- Max in-flight per connection (default 1,000): above it, `setAutoRead(false)`; resume below half.
- Stop reading when the channel is unwritable (client not reading replies); resume on writability change.
- Client idle timeout: `proxy.client-timeout-seconds`, default **0 = never** (Valkey's default).
- Max clients: `proxy.max-clients` (Valkey default 10,000); over the limit → `-ERR max number of clients reached`.

## Blocking commands: dedicated leases

Glide multiplexes, so a blocking command must not travel on the shared client. Options:

1. **Leased pool (chosen).** A pool of extra `GlideClusterClient`s used only for blocking commands. A call leases one, runs, and returns it. Configurable max size (default 64, created lazily, idle ones closed). When the pool is exhausted, the command waits for a free client. Each client holds one connection per primary.
2. **Per-connection dedicated client.** A client connection gets its own backend client on its first blocking command and keeps it. Simple and exact, but costly with many clients.

The same lease mechanism later covers **WATCH** (lease from WATCH until EXEC/DISCARD/UNWATCH, which gives real per-client isolation) and **SUBSCRIBE**.

Ordering note: a leased client is a different connection, so the barrier before a DEDICATED command is what guarantees it sees the client's earlier writes.

## RESP3

- `HELLO 3` switches the connection's protocol. The encoder writes native RESP3 types (`%` map, `~` set, `,` double, `#` boolean, `_` null, `(` big number, `>` push) and downgrades them for RESP2 clients (map→flat array, double→bulk string in Valkey's format, boolean→integer, null→`$-1`/`*-1`).
- Glide already speaks RESP3 to the cluster and returns typed Java values, so passing them through is close to free.
- Unsupported versions get `-NOPROTO unsupported protocol version`.

## TLS and AUTH

- TLS: optional `SslHandler` from PEM cert/key (`proxy.tls.*`), optional client-cert verification, and a separate `tls-port` like Valkey.
- AUTH: `proxy.password` (and later ACL-style users). Until a connection authenticates, only AUTH, HELLO (with AUTH) and QUIT are allowed; anything else gets `-NOAUTH Authentication required.`
- Backend credentials/TLS are separate (Glide config) and don't depend on this work.

## Migration plan

Each phase ends green on the full suite and with a benchmark run.

0. **Fix the node stall now, on the current code:** route blocking commands through a leased pool. It's independent of the protocol layer and fixes a correctness bug. *Done:* a same-slot GET during a BLPOP now answers in ~4 ms instead of failing after 2 s; throughput is unchanged (A/B within ±2%).
1. **New transport, decoder, encoder and `Connection`; resp-server removed.** Existing handlers run via a sync adapter on a per-connection virtual thread (serial, no pipelining yet). This alone removes event-loop blocking (thread stall), fixes the idle disconnect, 64-bit integers and the null array.
2. **Async hot path:** `CatchAllCommand` returns its future, with the ordered pipeline and barriers. This is the throughput phase.
3. RESP3 and `HELLO 3`.
4. Client TLS and AUTH (plus backend credentials/TLS in the same release).

## Success criteria

- `bench-blocking-stall.sh`: zero stalled PINGs; same-node GET answered in milliseconds.
- `bench-throughput.sh`: c=1 p50 within ~2× of direct; c=50+ well past the 150k plateau and scaling with pipelining. If one Glide client becomes the limit, try spreading connections across several Glide clients per DB.
- No regressions in the 67 integration tests; new wire-level tests (`RawRespClient`) for pipelining order, barriers, RESP3 and backpressure.

## Findings since the proposal (2026-10-31)

- **Glide reply integrity.** Unrouted `customCommand` misreads top-level map replies as multi-node results (order lost, binary keys corrupted; valkey-glide #4963), and Glide reshapes some replies by command name. Fixed in `0224d36` (explicit `SlotIdRoute` for map-reply commands) and `e5acdb3` (`Resp2Shapes`). The new encoder must keep both, and `DifferentialRepliesTest` is the check that it does.
- **Glide's routed call path costs ~1.5 µs more CPU per request** (our own slot lookup is ~50 ns). Routing every command cost 4–6% throughput, so only map-reply commands are routed.
- **The ~150k rps plateau may be partly Glide, not just our blocked event loops.** At the plateau the proxy used only ~3 of 24 cores (~24 µs of CPU per request), and extra Glide-side work lowered throughput more than its CPU cost explains, which suggests a serialization point per Glide client. Phase 2 should first measure throughput against the number of Glide clients per DB, before attributing all of the gain to async pipelining.

## Decisions (2026-10-31)

1. **Blocking pool:** when full, a blocking command waits for a free client, still honoring its own timeout. Configurable cap (default 64); log a warning and expose a metric when the pool is full. Pooled entries start as full `GlideClusterClient`s; per-node entries (one connection instead of one per primary) can come later if connection count becomes a problem.
2. **Inline commands:** supported, with Valkey-style quote handling.
3. **Same-slot reordering during resharding:** accepted and documented (same as cluster-aware clients such as Lettuce and Jedis). Phase 2 tests whether Glide actually reorders.
4. **`PROXY` commands:** make them real. STATS and CLIENTINFO come from the connection registry; CONFIG only covers settings that take effect; FLUSHCLIENTS is removed. The registry also lets the proxy answer `CLIENT LIST` / `CLIENT KILL` with real clients.
