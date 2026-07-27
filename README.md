# ValkeyWay

[![CI](https://github.com/nslootsky/valkeyway/actions/workflows/ci.yml/badge.svg)](https://github.com/nslootsky/valkeyway/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A RESP proxy that presents a Valkey cluster as a single-connection endpoint.

ValkeyWay is a Java 25 + Spring Boot proxy that sits between non-cluster-aware clients and a Valkey/Redis cluster. It uses [valkey-glide](https://github.com/valkey-io/valkey-glide) for cluster routing, MOVED/ASK handling, and multi-slot command splitting, and [resp-server](https://github.com/tonivade/resp-server) for the client-facing RESP protocol.

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

- Cross-slot commands: DEL, MGET, UNLINK split and aggregate automatically
- Multi-DB support: SELECT with per-connection state (Valkey 9+ cluster mode)
- Transactions: MULTI/EXEC with single-slot atomicity or per-slot cross-slot execution
- Cluster-wide SCAN with cursor persistence across connections
- Standalone-compatible responses for HELLO and CLUSTER INFO
- Health checks, metrics, and Prometheus via Spring Boot Actuator on port 6380

## Architecture

```
Client (non-cluster-aware)
         │
         │ RESP commands (GET, SET, SELECT, etc.)
         ▼
┌──────────────────────────────────────┐
│  ValkeyWay :6379                     │
│                                      │
│  resp-server library                 │
│    ├─ RESP server (Netty-based)      │
│    ├─ Command routing                │
│    └─ Per-session state management   │
│                                      │
│    ├─ ProxyCommandSuite              │
│    │   ├─ SelectCommand              │
│    │   ├─ MultiCommand / ExecCommand │
│    │   ├─ DelCommand / UnlinkCommand │
│    │   ├─ MgetCommand                │
│    │   ├─ ScanCommand                │
│    │   ├─ PingCommand / InfoCommand  │
│    │   ├─ HelloCommand               │
│    │   ├─ ClusterCommand             │
│    │   ├─ ProxyAdminCommand          │
│    │   └─ CatchAllCommand            │
│    │       └─ customCommand()        │
│    │                                 │
│    ├─ SessionState (per-session)     │
│    ├─ ScanCursorStore (TTL + max)    │
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
java -jar valkeyway.jar \
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

# Run locally (with cluster on ports 7000-7002)
java -jar target/valkeyway.jar

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

### Basic commands (pass-through via customCommand)

- GET, SET, SETEX, GETSET
- HGET, HSET, HGETALL, HMGET, HDEL, HINCRBY
- LPUSH, RPUSH, LPOP, RPOP, LRANGE
- INCR, DECR, EXISTS, TTL, EXPIRE
- Keys routed by glide; cross-slot operations may return CROSSSLOT errors

## Tech Stack

- Java 25
- Spring Boot 4.1.0
- resp-server (client-facing RESP server)
- valkey-glide 2.5.0 (cluster client)
- Micrometer + Spring Boot Actuator (metrics and health)
- Maven Wrapper

## Limitations

- SCAN cursors during iteration are UUIDs (not numeric), `"0"` returned on completion
- Transactions spanning slots use per-slot atomicity, not global atomicity
- EVAL/EVALSHA scripts with keys on multiple nodes will fail with CROSSSLOT error
- No TLS support
