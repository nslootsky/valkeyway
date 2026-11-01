<div align="center">
  <img src="images/logo.png" alt="ValkeyWay Logo" width="280"/>
  <h1>ValkeyWay</h1>
  <p>
    <a href="https://github.com/nslootsky/valkeyway/actions/workflows/ci.yml"><img src="https://github.com/nslootsky/valkeyway/actions/workflows/ci.yml/badge.svg" alt="CI"/></a>
    <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"/></a>
  </p>
</div>

A RESP proxy that presents a [Valkey cluster](https://valkey.io/topics/cluster-tutorial/) as a single standalone Valkey instance.

ValkeyWay is a Java 25 + Spring Boot proxy that sits between non-cluster-aware clients and a Valkey/Redis cluster. It uses [valkey-glide](https://github.com/valkey-io/valkey-glide) for cluster routing, MOVED/ASK handling, and multi-slot command splitting, and its own Netty-based RESP server for clients.

Clients connect to ValkeyWay on port 6379 and interact with the cluster as if it were a standalone server.

## Quick Start

```bash
# Docker
docker run -d --name valkeyway \
  -p 6379:6379 -p 6380:6380 \
  nslootsky/valkeyway:latest \
  --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002

# Clients connect to localhost:6379
valkey-cli SET mykey hello
valkey-cli GET mykey
# → "hello"
```

## Features

**Standalone compatibility**
- Works with any RESP2 client, no cluster support needed. Replies match what a standalone Valkey server sends, including:
  - reply shapes that Glide changes;
  - null arrays versus null bulk strings;
  - number formats;
  - error codes.

  More than 70 commands are checked byte for byte against a direct connection to a node.
- Cross-slot commands such as DEL, MGET and UNLINK are split per slot and aggregated.
- Multiple databases: SELECT with per-connection state (Valkey 9+ cluster mode).
- Transactions: MULTI/EXEC and WATCH, atomic when all keys share a slot (use hash tags such as `{user1}`). Queue-time errors and EXECABORT behave as in Valkey, and WATCH is private to each client connection.
- Cluster-wide SCAN, with cursors that work across connections.
- Pipelining and inline commands.
- Standalone-style HELLO and INFO; `CLUSTER INFO` and `CLUSTER NODES` answer like a server without cluster support.

**Performance**
- A client's pipeline goes to the cluster as one ordered batch.
- Single commands that arrive on different connections at the same time are sent to Glide as one shared batch.
- Example measurements on one 24-core machine (see [docs/RESP_LAYER.md](docs/RESP_LAYER.md)):
  - 200–290k requests/s from 50–200 unpipelined clients;
  - up to ~620k requests/s with 16-deep pipelines.
- Blocking commands (BLPOP, XREAD BLOCK, WAIT, ...) run on a pool of dedicated backend clients, so a blocked command never holds up other clients.

**Cluster changes**: slot migrations, new shards, failovers and a re-pointed seed DNS name are handled under live traffic. See [Cluster changes](#cluster-changes) for what clients see in each case.

**Operations**: health checks, metrics and Prometheus through Spring Boot Actuator on port 6380.

## Architecture

```
Clients (RESP2, no cluster support needed)
         │
         ▼
┌────────────────────────────────────────────────┐
│  ValkeyWay :6379                               │
│                                                │
│  server: Netty RESP server (epoll/NIO)         │
│    RespDecoder / ReplyEncoder                  │
│    Connection: per-client state; replies in    │
│      order; one backend unit in flight         │
│    CommandTable → commands                     │
│      ├─ PING ECHO TIME HELLO INFO SCAN ...     │
│      ├─ MULTI/EXEC/WATCH → transaction         │
│      ├─ blocking commands → blocking           │
│      │    (dedicated clients)                  │
│      └─ everything else → CatchAllCommand      │
│                                                │
│  backend: Backend (single commands,            │
│    pipelines), Coalescer (batches across       │
│    connections), BackendClients (one Glide     │
│    client per database)                        │
│    routing: slots, key specs, explicit routes  │
│    replies: RESP2 shapes, error codes          │
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

Each connection has at most one backend unit in flight: a single command, or a batch of everything it has queued. Glide keeps order within a batch but not across separate requests, so this is what keeps each client's commands in order. Commands that change connection state, or may block, wait for earlier replies and run on a virtual thread.

## Configuration

Via `application.yml` or command-line args:

```yaml
proxy:
  host: 0.0.0.0                 # Listen address for clients
  port: 6379                    # Listen port for clients
  cluster-nodes:                # Seed nodes: IPs or DNS names (e.g. a configuration endpoint)
    - "127.0.0.1:7000"
    - "127.0.0.1:7001"
    - "127.0.0.1:7002"
  connect-timeout-ms: 2000      # Timeout per backend request, including Glide's retries (despite the name)
  blocking-pool:                # Dedicated backend clients for BLPOP, XREAD BLOCK, WAIT, ...
    max-size: 64                # Max blocking commands running at once; more wait for a free client
    idle-timeout-seconds: 60    # Close a pooled client after this long unused
  glide:
    inflight-requests-limit: 50000  # Outstanding requests per Glide client (Glide's default is 1000)
    coalesce-requests: true         # Send concurrent clients' commands to Glide in shared batches
    refresh-topology-from-seeds: true  # Check topology via the seeds, re-resolving DNS names (follows a re-pointed endpoint)
    topology-check-seconds: 0       # How often Glide checks for topology changes; 0 = Glide's default (60)
  transactions:
    cross-slot: reject          # EXEC whose keys span slots: reject (CROSSSLOT, like a cluster node) or pipeline (run without atomicity)
    watch-pool:                 # Dedicated backend clients for connections that use WATCH
      max-size: 64              # Connections with keys watched at once; more wait
      idle-timeout-seconds: 60  # Close a pooled client after this long unused
      acquire-timeout-ms: 2000  # How long WATCH waits for a free client before failing
  clients:
    max-clients: 10000          # Connections accepted at once (like Valkey's maxclients)
    timeout-seconds: 0          # Close clients idle this long; 0 = never (like Valkey's timeout)
    io-threads: 2               # Network threads; fewer = bigger backend batches (1 is fastest for small values)

management:
  server:
    port: 6380                    # Separate port for actuator endpoints
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
```

Seed nodes can be IPs or DNS names. With `refresh-topology-from-seeds` (the default), Glide re-resolves and asks the seeds at each topology check, so re-pointing a DNS name at another cluster (a blue/green cutover) moves the proxy there at the next check. Glide refreshes slots at most once every 15–30 s, so a cutover can take that much longer. If the seeds can't be reached, the proxy keeps using the nodes it already knows.

Or via CLI:

```bash
java -jar valkeyway.jar \
  --proxy.host=0.0.0.0 \
  --proxy.port=6379 \
  --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002
```

## Cluster changes

What clients see while the cluster changes shape. `TopologyChangeTest` exercises each case under live traffic.

| Change | Clients see |
|---|---|
| Slot migration (ASK, TRYAGAIN) | Commands succeed. Inside a client pipeline, a multi-key command whose keys are split mid-migration gets `TRYAGAIN` (and likewise `CLUSTERDOWN` and similar errors during a failover). A standalone server's pipeline reports errors per command too. |
| Slot moved, also to a newly added shard | Commands succeed, including inside pipelines. |
| Graceful failover (`CLUSTER FAILOVER`) | No errors. |
| A primary dies | Its shard's commands fail until the replica is promoted (about the cluster's node timeout), then succeed without a proxy restart. |
| A primary hangs (frozen process, network partition) | Its shard's commands time out until the node answers again, even after its replica is promoted (a Glide limitation). |
| A seed DNS name is re-pointed at another cluster | The proxy moves to the new cluster at its next topology check (see [Configuration](#configuration)). |

When the cluster can't serve a command, the error says whether the command ran, so the client can decide whether retrying is safe:

- `ERR backend unavailable: ...; the command did not run`
- `ERR backend unavailable: ...; the command may or may not have run`
- `ERR backend request timed out; the command may or may not have run`

## Building and Running

```bash
# Build
./mvnw package -DskipTests

# Run unit tests (no Docker required)
./mvnw test

# Run integration tests (requires Docker or Podman)
./mvnw verify -Dskip.integration.tests=false

# Run locally (with cluster on ports 7000-7002)
java -jar target/valkeyway-0.1.0.jar

# Test with valkey-cli (no --cluster flag needed)
valkey-cli -h 127.0.0.1 -p 6379 SET mykey hello
valkey-cli -h 127.0.0.1 -p 6379 GET mykey
# → "hello"

# Cross-slot MGET works automatically
valkey-cli -h 127.0.0.1 -p 6379 MGET key1 key2 key3

# Cross-slot DEL works automatically
valkey-cli -h 127.0.0.1 -p 6379 DEL key1 key2 key3

# Cluster-wide SCAN
valkey-cli -h 127.0.0.1 -p 6379 SCAN 0 MATCH "prefix:*" COUNT 100
```

## Docker

```bash
# Build image
docker build -t valkeyway:latest .

# Run with existing cluster
docker run -d --name valkeyway \
  -p 6379:6379 -p 6380:6380 \
  valkeyway:latest \
  --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002

# Local dev with full cluster (docker-compose.dev.yml)
docker compose -f docker-compose.dev.yml up -d
```

## Health and Metrics

Spring Boot Actuator endpoints are exposed on a separate management port (default 6380):

- **Health**: `GET http://localhost:6380/actuator/health`
  - Includes `cluster` health indicator that pings the Valkey cluster
  - Returns UP if reachable, DOWN with error details otherwise

- **Metrics**: `GET http://localhost:6380/actuator/metrics`
  - `proxy.commands.processed` — total commands processed (tagged by command type)
  - `proxy.errors` — total command errors
  - `proxy.blocking.pool.in_use` / `proxy.blocking.pool.clients` — blocking commands running / pooled clients open
  - `proxy.blocking.pool.exhausted_waits` — blocking commands that had to wait because the pool was full
  - `proxy.watch.pool.in_use` / `proxy.watch.pool.clients` / `proxy.watch.pool.exhausted_waits` — the same for connections using WATCH
  - `proxy.commands.latency` — command execution latency histogram

- **Prometheus**: `GET http://localhost:6380/actuator/prometheus`
  - Prometheus-scrapeable metrics format

## Supported Commands

### Dedicated handlers

- SCAN (cluster-wide iteration with cursor persistence)
- SELECT (per-session DB state)
- MULTI/EXEC/DISCARD (transactions, queued by the proxy; atomic within a slot)
- WATCH/UNWATCH (on a dedicated backend client per connection)
- INFO (proxy-specific response with role:master)
- HELLO (standalone-mode response)
- CLUSTER (standalone errors for INFO/NODES; others proxied)
- COMMAND (all subcommands proxied; DOCS format adjusted for redis-cli)
- PING, ECHO, TIME, QUIT (answered by the proxy)
- PROXY (proxy administration; see Limitations)
- Blocking commands (BLPOP, BRPOP, BLMOVE, BZPOPMIN, XREAD/XREADGROUP BLOCK, WAIT, ...) run on dedicated backend clients

### All other commands (via customCommand)

- GET, SET, SETEX, GETSET, DEL, UNLINK, EXISTS, TTL, EXPIRE
- HGET, HSET, HGETALL, HMGET, HDEL, HINCRBY
- LPUSH, RPUSH, LPOP, RPOP, LRANGE
- MGET, INCR, DECR, PING, TIME
- SCRIPT/EVAL, CONFIG, CLIENT subcommands
- Keys routed by glide; cross-slot operations may return CROSSSLOT errors

## Design notes and upstream assumptions

- [docs/RESP_LAYER.md](docs/RESP_LAYER.md): design of the client protocol layer, measurements, and findings about Glide.
- [docs/UPSTREAM_ASSUMPTIONS.md](docs/UPSTREAM_ASSUMPTIONS.md): every detail of valkey-glide and Valkey that the proxy relies on but that isn't a stable API, such as Glide's error message formats, the Java types it returns, what it retries, and Valkey's reply formats. Each entry lists the code it affects (tagged `[upstream:ID]`) and the test that catches a change. Check it when upgrading either.

## Tech Stack

- Java 25
- Spring Boot 4.1.2
- Netty 4.2 (client-facing RESP server; native epoll on Linux)
- valkey-glide 2.5.3 (cluster client)
- Micrometer + Spring Boot Actuator (metrics and health)
- Maven Wrapper

## Limitations

- RESP2 only (HELLO 3 is answered with protocol 2).
- No TLS and no AUTH, neither for clients nor to the cluster.
- Pub/Sub (SUBSCRIBE, PSUBSCRIBE, ...) is not supported.
- A transaction is atomic only when all its keys share a slot. A cross-slot transaction is rejected with CROSSSLOT by default; with `proxy.transactions.cross-slot=pipeline`, its commands run in order without any atomicity. With WATCH, a cross-slot transaction is always rejected.
- Inside MULTI, INFO, HELLO, SCAN, CLUSTER, PROXY and SELECT are rejected and the transaction is discarded. The proxy answers the first five itself, which EXEC on a node can't reproduce, and SELECT would switch the database of the shared backend connection.
- SELECT between WATCH and EXEC makes EXEC abort, as if a watched key had changed (clients retry).
- EVAL/EVALSHA with keys on several nodes fails with CROSSSLOT.
- During a slot migration, a pipelined command that gets redirected can run after later commands of the same pipeline. Cluster-aware clients (Lettuce, Jedis) behave the same way.
- A primary that hangs without closing its connections isn't routed around until it answers again (see [Cluster changes](#cluster-changes)).
- CLIENT commands (CLIENT LIST, CLIENT KILL, ...) go to a backend node and describe the proxy's backend connections, not the proxy's clients.
- PROXY STATS and PROXY CLIENTINFO return placeholder values.
- SCAN cursors are UUIDs rather than numbers; `0` still marks the end. A cursor can be used once (each call returns the next one) and only in the database it started in.
- SCAN returns keys whose bytes aren't valid UTF-8 with those bytes replaced, and MATCH patterns with such bytes match nothing (a Glide limitation). Other commands are binary-safe.
- Set replies (SMEMBERS, SINTER, ...) contain the right members, but not necessarily in the server's iteration order.
