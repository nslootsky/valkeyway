# Architecture

```
Clients (RESP2 or RESP3, no cluster support needed)
         │
         ▼
┌────────────────────────────────────────────────┐
│  ValkeyWay :6379                               │
│                                                │
│  server: Netty RESP server (epoll/NIO), TLS    │
│    RespDecoder / ReplyEncoder                  │
│    Connection: per-client state; replies in    │
│      order; one backend unit in flight         │
│    CommandTable → commands                     │
│      ├─ CLIENT HELLO INFO SCAN ... (local)     │
│      ├─ MULTI/EXEC/WATCH → transaction         │
│      ├─ blocking commands → blocking           │
│      │    (dedicated clients)                  │
│      ├─ SUBSCRIBE ... → pubsub (shared hub)    │
│      └─ everything else → CatchAllCommand      │
│                                                │
│  backend: Backend (single commands,            │
│    pipelines), Coalescer (batches across       │
│    connections), BackendClients (one Glide     │
│    client per database)                        │
│    routing: slots, key specs, explicit routes  │
│    replies: reply shapes, error codes          │
│                                                │
│  valkey-glide: slot routing, MOVED/ASK,        │
│    multi-slot splitting, topology              │
└────────────────────────────────────────────────┘
         │
         ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│ Valkey   │ │ Valkey   │ │ Valkey   │
│ :7000    │ │ :7001    │ │ :7002    │
└──────────┘ └──────────┘ └──────────┘
```

**Ordering.** Glide keeps order within a batch but not across separate requests. So each connection has at most one backend unit in flight: a single command, or one batch of everything it has queued (a pipeline). That keeps each client's commands in order.

**Connection commands.** Commands that read or change connection state, or that may block, wait for earlier replies and run on a virtual thread. Connection-scoped commands are answered by the proxy rather than forwarded, because a Glide client multiplexes one connection per node for everyone.

**Throughput.** Single commands that arrive on different connections in the same network-thread iteration are sent to Glide as one shared batch. Glide's per-request cost is much higher than its per-command cost inside a batch.

Example measurements on one 24-core machine:
- 200–290k requests/s from 50–200 unpipelined clients;
- up to ~620k requests/s with 16-deep pipelines.

## Packages

| Package | Contents |
|---|---|
| `protocol` | RESP decoder and encoder, the protocol-neutral `Reply` model |
| `server` | Netty server, `Connection`, command table, users, TLS |
| `commands` | Command handlers |
| `transaction` | MULTI/EXEC queueing and WATCH sessions |
| `blocking` | Blocking commands and their client pool |
| `pubsub` | The subscription hub and the subscribe commands |
| `backend` | Glide clients, single commands, batches, coalescing |
| `backend.routing` | Slots, COMMAND key specs, explicit routes |
| `backend.replies` | Glide values to Valkey's reply shapes and errors |
| `ops` | Metrics and health |

## Further reading

- [RESP_LAYER.md](RESP_LAYER.md): the design of the protocol layer, its phases, measurements, and what was learned about Glide on the way.
- [UPSTREAM_ASSUMPTIONS.md](UPSTREAM_ASSUMPTIONS.md): every detail of Glide and Valkey that the proxy relies on but that isn't a stable API, with the code each affects (tagged `[upstream:ID]`) and the test that catches a change. Check it when upgrading either.
