# Valkey Cluster Proxy

[![CI](https://github.com/nslootsky/valkey-cluster-proxy/actions/workflows/ci.yml/badge.svg)](https://github.com/nslootsky/valkey-cluster-proxy/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Java 25 + Spring Boot 4.1 proxy that exposes a Valkey/Redis cluster as a single-connection endpoint for non-cluster-aware clients.

The proxy uses the [valkey-glide](https://github.com/valkey-io/valkey-glide) client library to handle all cluster complexity: routing, MOVED/ASK redirects, multi-slot command splitting, and connection pooling. The proxy itself handles RESP protocol translation via the [resp-server](https://github.com/tonivade/resp-server) library.

## Architecture

```
Client (non-cluster-aware)
         │
         │ RESP commands (GET, SET, SELECT, etc.)
         ▼
┌──────────────────────────────────────┐
│  Valkey Cluster Proxy :6379          │
│                                      │
│  resp-server library                 │
│    ├─ RESP server (Netty-based)      │
│    ├─ Command routing                │
│    └─ Per-session state management   │
│                                      │
│    ├─ ProxyCommandSuite              │
│    │   ├─ SelectCommand              │
│    │   ├─ MultiCommand / ExecCommand │
│    │   │   └─ ClusterBatch           │
│    │   ├─ DelCommand / UnlinkCommand │
│    │   ├─ MgetCommand                │
│    │   ├─ ScanCommand                │
│    │   ├─ PingCommand / InfoCommand  │
│    │   ├─ TimeCommand                │
│    │   ├─ HelloCommand               │
│    │   ├─ ClusterCommand             │
│    │   ├─ ProxyAdminCommand          │
│    │   └─ CatchAllCommand            │
│    │       └─ customCommand()        │
│    │                                 │
│    ├─ SessionState (per-session)     │
│    │   ├─ currentDB                  │
│    │   ├─ glideClient                │
│    │   ├─ transaction state          │
│    │   └─ scan cursor ID             │
│    │                                 │
│    ├─ ScanCursorStore (shared)       │
│    ├─ GlideClientCache               │
│    ├─ MetricsCollector               │
│    └─ ClusterHealthIndicator         │
│                                      │
│    ▼ via valkey-glide                │
│  GlideClusterClient (cluster-aware)  │
│    - Connection pooling              │
│    - Slot routing                    │
│    - MOVED/ASK handling              │
│    - Multi-slot command splitting    │
└──────────────────────────────────────┘
         │
         ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│ Valkey   │ │ Valkey   │ │ Valkey   │
│ :7000    │ │ :7001    │ │ :7002    │
│ slots    │ │ slots    │ │ slots    │
└──────────┘ └──────────┘ └──────────┘
```

### Components

- **resp-server library**: Provides the RESP2 server, protocol parsing, command routing, and session management. The proxy extends `CommandSuite` and implements `RespCommand` handlers.

- **ProxyCommandSuite** (`handler/ProxyCommandSuite.java`): Command router extending `CommandSuite`. Registers named command handlers and returns `CatchAllCommand` for unregistered commands.

- **Command Handlers** (`handler/commands/*.java`): Individual `RespCommand` implementations:
  - `SelectCommand` — SELECT with DB isolation via `GlideClientCache`
  - `MultiCommand` / `ExecCommand` — MULTI/EXEC with `ClusterBatch`
  - `DiscardCommand` — aborts transaction
  - `DelCommand` / `UnlinkCommand` — multi-slot deletion via glide typed APIs
  - `MgetCommand` — multi-slot reads via glide typed APIs
  - `ScanCommand` — cluster-wide SCAN with `ClusterScanCursor`
  - `PingCommand` / `InfoCommand` / `TimeCommand` — standard commands
  - `ProxyAdminCommand` — PROXY admin subcommands
  - `CatchAllCommand` — pass-through via `customCommand()`; queues commands in transactions

- **SessionState** (`handler/SessionState.java`): Utility for managing per-session state stored in the resp-server library's `Session` object: current DB, glide client reference, transaction state (commands, slots), and scan cursor ID.

- **ScanCursorStore** (`scan/ScanCursorStore.java`): Shared `ConcurrentHashMap<String, ClusterScanCursor>` keyed by UUID. Persists SCAN cursors across connections since each valkey-cli SCAN call is a new TCP connection.

- **GlideClientCache** (Spring bean): Manages `GlideClusterClient` instances per database index. Each client is configured with a `databaseId`. Initialized lazily on first use; cached with 30-second expiry.

- **TokenUtils** (`handler/commands/TokenUtils.java`): Shared utilities for converting glide results to `RedisToken` and cleaning error messages.

- **MetricsCollector** (`metrics/MetricsCollector.java`): Micrometer-based metrics tracking: commands processed (by type), errors, and command latency. Exposed via actuator endpoints.

- **ClusterHealthIndicator** (`health/ClusterHealthIndicator.java`): Spring Boot health indicator that pings the cluster. Reports UP if reachable, DOWN with error details otherwise.

## How It Works

### Command Routing

`ProxyCommandSuite.getCommand(name)` looks up registered handlers by command name. Registered commands use typed glide APIs; all others fall through to `CatchAllCommand`, which uses `customCommand()`. glide routes single-key commands to the correct node based on the key's slot. No manual topology tracking or MOVED handling required.

### Multi-Key Commands (DEL, MGET)

glide's typed APIs (`del(keys)`, `mget(keys)`) automatically split keys by slot and aggregate results:
- `DEL key1 key2 key3` across 3 slots → glide sends to each node, returns total deleted count
- `MGET key1 key2 key3` across 3 slots → glide sends to each node, returns values in original key order

### Transactions (MULTI/EXEC)

`MultiCommand` sets transaction state in `SessionState`. Subsequent commands are queued by `CatchAllCommand` into per-session buffers. On EXEC:
- If all keys map to the same slot: `ClusterBatch(true)` (atomic, single-node)
- If keys span multiple slots: `ClusterBatch(false)` (per-slot atomicity, not global)

Slots are tracked via `key.hashCode() & 0x3FFF` in `SessionState`.

### Cluster-Wide SCAN

SCAN iterates across all cluster nodes using glide's `ClusterScanCursor`:

1. Client sends `SCAN 0 MATCH pattern COUNT n`
2. Handler creates `ClusterScanCursor.initialCursor()`, calls `glideClient.scan()`
3. glide returns keys from first node + new cursor
4. Handler stores cursor in `ScanCursorStore` with a UUID, returns UUID to client as cursor value
5. Client sends `SCAN <uuid> MATCH pattern COUNT n` on next iteration
6. Handler retrieves cursor from store, resumes scan
7. When cursor is finished, returns `"0"` cursor (standard RESP convention)

This allows SCAN to work correctly even though each valkey-cli invocation is a separate TCP connection.

### SELECT (Multi-DB)

`SelectCommand` tracks the current DB in `SessionState` and creates or reuses a `GlideClusterClient` from `GlideClientCache` configured with that `databaseId`. Each DB index gets its own client instance, providing basic DB isolation per connection.

### PROXY Admin Commands

Custom admin commands for proxy management:
- `PROXY CLUSTER INFO` — delegates to `CLUSTER INFO`
- `PROXY CONFIG GET <key>` — returns proxy config value (from in-memory store or defaults)
- `PROXY CONFIG SET <key> <value>` — sets in-memory config
- `PROXY STATS` — returns basic cluster stats
- `PROXY FLUSHCLIENTS` — closes all cached Glide clients
- `PROXY CLIENTINFO <id>` — returns client info

## Configuration

Via `application.yml` or command-line args:

```yaml
proxy:
  host: 0.0.0.0                 # Listen address for clients
  port: 6379                    # Listen port for clients
  cluster-nodes:                # Seed nodes to discover topology
    - "127.0.0.1:7000"
    - "127.0.0.1:7001"
    - "127.0.0.1:7002"
  connect-timeout-ms: 2000      # Request timeout for glide

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

Or via CLI:

```bash
java -jar valkey-cluster-proxy-0.1.0-SNAPSHOT.jar \
  --proxy.host=0.0.0.0 \
  --proxy.port=6379 \
  --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002
```

## Building and Running

```bash
# Build
./mvnw package -DskipTests

# Run unit tests (no Docker required)
./mvnw test

# Run integration tests (requires Docker/Podman)
./mvnw verify -Dskip.integration.tests=false

# Run locally (with cluster on ports 7000-7005)
java -jar target/valkey-cluster-proxy-0.1.0-SNAPSHOT.jar

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
docker build -t valkey-cluster-proxy:latest .

# Run with existing cluster
docker run -d --name proxy \
  -p 6379:6379 -p 6380:6380 \
  valkey-cluster-proxy:latest \
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
  - `proxy.commands.latency` — command execution latency histogram

- **Prometheus**: `GET http://localhost:6380/actuator/prometheus`
  - Prometheus-scrapeable metrics format

## Supported Commands

### Native support (typed glide APIs)

- DEL, UNLINK (multi-slot splitting)
- MGET (multi-slot splitting)
- SCAN (cluster-wide iteration)
- SELECT, PING, INFO, TIME

### Transaction support

- MULTI/EXEC (cross-slot via ClusterBatch(false))
- DISCARD

### Pass-through (customCommand)

All other Valkey commands are forwarded via `customCommand()`. glide routes single-key commands correctly. Commands whose keys span multiple slots may return `CROSSSLOT` errors from the cluster.

## Tech Stack

- Java 25
- Spring Boot 4.1.0
- resp-server (client-facing RESP2 server)
- valkey-glide 2.5.0 (cluster client)
- Micrometer + Spring Boot Actuator (metrics and health)
- Maven Wrapper

## Limitations

- SCAN cursors during iteration are UUIDs (not numeric), `"0"` returned on completion
- Transactions spanning slots use per-slot atomicity, not global atomicity
- EVAL/EVALSHA scripts with keys on multiple nodes will fail with CROSSSLOT error
- No TLS support
