# Valkey's test suite against the proxy

Valkey's own test suite (the Tcl tests in the Valkey repository) has thousands of checks of what a server answers. `scripts/valkey-suite.sh` runs them through the proxy and, for comparison, against a standalone server of the same version, then lists the tests that pass on the server but not through the proxy. Those are the ones that say something about the proxy.

```bash
./mvnw package -DskipTests
scripts/valkey-suite.sh                          # the default areas
scripts/valkey-suite.sh unit/type/list unit/multi
```

## How it runs

The script needs `valkey-server`, `valkey-cli`, `tclsh` 8.6 and git on the PATH. The local `valkey-server`'s version picks which version of the tests to clone.

For each area, the script starts fresh servers: a standalone server, a 3-node cluster and the proxy. A fresh start matters because tests that change a server's settings and then fail leave them changed. Both targets run with `--cluster-mode`, which skips tests marked as incompatible with clusters, and without the tests that need replication streams, DEBUG or persistence (`needs:repl`, `needs:debug`, `needs:save`, ...).

The proxy runs with `node-admin-commands: forward`, because the suite sets server options with CONFIG SET, which Glide sends to every node. Results are written to `target/valkey-suite/out`: one log per area and target, and `proxy-only-<area>.txt` with the tests that pass on the server but not through the proxy.

The tests are written for one server. The interesting failures are bugs in the proxy. Most of the rest come from things a cluster can't do (the same test would fail against a cluster directly), or from tests of a server's internals.

## Results (Valkey 9.1.1, 2026-11-01)

Tests that pass on the standalone server but not through the proxy, before and after the fixes this run found. An area marked † stopped at an uncaught error, so its "before" count includes every test after that point.

| Area | Server passes | Proxy-only failures before | After |
|---|---|---|---|
| type/string | 105 | 14 | 2 |
| type/incr, hash, set | 228 | 0 | 0 |
| type/list | 237 | 38 † | 1 † |
| type/zset | 301 | 2 | 0 |
| type/stream | 73 | 6 | 0 |
| type/stream-cgroups | 56 | 14 | 1 |
| expire | 64 | 6 † | 5 † |
| keyspace, scan, geo, sort, hyperloglog | 184 | 0 | 0 |
| multi | 42 | 24 † | 17 † |
| pubsub | 35 | 29 † | 22 |
| protocol | 28 | 17 † | 3 † |
| bitops | 50 | 4 † | 0 |
| dump | 13 | 1 | 0 |
| info-command | 5 | 5 † | 0 |
| quit | 3 | 0 | 0 |

### Bugs it found, now fixed

- **WATCH with keyless commands:** a transaction of only keyless commands (`MULTI`, `PING`, `EXEC`) ran on a random node, not the one holding the WATCH, so EXEC could succeed after a watched key changed.
- **MSETEX:** routed by its key count (Glide's default for commands it doesn't know), and Valkey 9.1.1 nodes stored the keys even for slots they don't own, so no read found them (catalog entry G15).
- **INFO:** answered six fixed lines, so `blocked_clients`, `expired_keys`, `total_error_replies`, the keyspace, command and error stats, and the other fields that tools and the suite read were missing.
- **Blocking commands:**
  - argument errors used Glide's wording;
  - CLIENT UNBLOCK wasn't supported (it's now passed on to the node, for the backend connection the command waits on);
  - INFO couldn't count blocked clients and keys.
- **Request parsing:** Valkey 9's inline quoting and strict CRLF checks weren't followed.
- **Pub/Sub:**
  - confirmations were dropped under CLIENT REPLY OFF;
  - PUBLISH returned a node's count, which saw the proxy's shared subscription as one subscriber, or none;
  - CLIENT LIST's `watch=` count was wrong too.

## What remains, and why

By area, after the fixes:
- **string (2):** MSETEX with keys in several slots answers CROSSSLOT, as a cluster node does. MSETEX's keyspace notifications arrive in another order (see below).
- **list (1 †):** CLIENT PAUSE is rejected, which stops the area; the one test after it doesn't run.
- **stream-cgroups (1):** a test expects `total_error_replies` to restart from 0 after CONFIG RESETSTAT, but the proxy counts its own replies and doesn't reset.
- **expire (5 †):** Valkey 9's import mode, a server setting the proxy doesn't have.
- **multi (17 †):**
  - **Queue-time errors:** out-of-memory isn't reported at queue time.
  - **Busy scripts:** these tests expect BUSY replies, but the proxy answers PING itself.
  - **SELECT between WATCH and EXEC** aborts the transaction.
  - **Not run:** a BUSY test fails partway through a transaction, which stops the area, so the tests after it don't run.
- **pubsub (22):** 16 tests of keyspace notifications, and tests of PUBLISH counts inside MULTI and scripts, where the count comes from a node. One failure from message order across patterns leaves its client subscribed, which skews the counts in the tests after it.
- **protocol (3 †):** HELLO's `availability_zone`, which stops the area.

In general:

**Things a cluster can't do.** The same tests fail against a cluster directly:
- **Keyspace notifications** (16 tests in pubsub): a node publishes them only for its own keys, so through the proxy they arrive from one node.
- **Message order across channels or patterns:** the proxy's subscriptions can sit on different nodes, so messages for two patterns may arrive in another order than they were published.
- **Keys in different slots:** for example RENAME or XREADGROUP across slots, and scripts that touch undeclared keys.

**A server's internals:**
- **List encoding tests:** they set encoding thresholds and check `OBJECT ENCODING`.
- **Command stats:** commandstats are per node.
- **Busy scripts:** tests that expect BUSY replies while a script runs; the proxy answers PING itself.
- **SAVE or SHUTDOWN inside MULTI.**
- **Import mode:** Valkey 9's `import-mode` setting.
- **Availability zones:** HELLO's `availability_zone`.

**The proxy's design, documented in [COMPATIBILITY.md](COMPATIBILITY.md):**
- **Out-of-memory errors** are reported at EXEC, not at queue time.
- **SELECT between WATCH and EXEC** aborts the transaction.
- **Commands rejected inside MULTI,** such as AUTH and CLIENT REPLY.
- **PUBLISH inside MULTI or a script** returns the node's count.

Each run should be checked against this list. A new proxy-only failure that fits none of these is worth a look.
