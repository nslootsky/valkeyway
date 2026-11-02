# Operations

## Running

```bash
# Docker, against an existing cluster
docker run -d --name valkeyway -p 6379:6379 -p 6380:6380 \
  ghcr.io/nslootsky/valkeyway:latest --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002

# Or the jar (Java 25), from the GitHub release
java -jar valkeyway.jar --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002
```

Clients connect on port 6379. The management port, 6380, serves health checks and metrics. Settings are described in [CONFIGURATION.md](CONFIGURATION.md), and passwords and TLS in [AUTH_AND_TLS.md](AUTH_AND_TLS.md).

The image is published for linux/amd64 and linux/arm64, tagged with each version and `latest`. It runs as a non-root user and has a Docker `HEALTHCHECK` on the health endpoint.

## Health and metrics

Spring Boot Actuator, on the management port:

- **Health:** `GET /actuator/health` is UP when the cluster answers, and DOWN with details otherwise (the `cluster` indicator).
- **Metrics:** `GET /actuator/metrics`, and in Prometheus format at `GET /actuator/prometheus`:

| Metric | Meaning |
|---|---|
| `proxy.commands.processed` | Commands answered, tagged by command |
| `proxy.errors` | Error replies |
| `proxy.auth.failures` | AUTH attempts with a wrong user or password |
| `proxy.commands.latency` | Command latency histogram |
| `proxy.blocking.pool.in_use`, `.clients` | Blocking commands running; pooled backend clients open |
| `proxy.blocking.pool.exhausted_waits` | Blocking commands that waited because the pool was full |
| `proxy.watch.pool.in_use`, `.clients`, `.exhausted_waits` | The same for connections using WATCH |

`PROXY STATS` and `INFO` give a client's view of the same: connected and blocked clients, commands, errors and the settings in effect.

The management port has no authentication. Keep it on an internal network, or bind it to localhost with `management.server.address=127.0.0.1`.

## Cluster changes

What clients see while the cluster changes shape. `TopologyChangeTest` runs each case under live traffic.

| Change | Clients see |
|---|---|
| Slot migration (ASK, TRYAGAIN) | Commands succeed. Inside a client pipeline, a multi-key command whose keys are split mid-migration gets `TRYAGAIN` (likewise `CLUSTERDOWN` and similar errors during a failover), as a standalone server's pipeline reports errors per command. |
| Slot moved, also to a newly added shard | Commands succeed, including inside pipelines. |
| Graceful failover (`CLUSTER FAILOVER`) | No errors. |
| A primary dies | Its shard's commands fail until the replica is promoted (about the cluster's node timeout), then succeed without a proxy restart. |
| A primary hangs (frozen process, network partition) | Its shard's commands time out until the node answers again, even after its replica is promoted (a Glide limitation). |
| A seed DNS name is re-pointed at another cluster | The proxy moves to the new cluster at its next topology check ([CONFIGURATION.md](CONFIGURATION.md#seed-nodes-and-dns)). |

When the cluster can't serve a command, the error says whether the command ran, so the client can decide whether a retry is safe:

- `ERR backend unavailable: ...; the command did not run`
- `ERR backend unavailable: ...; the command may or may not have run`
- `ERR backend request timed out; the command may or may not have run`
