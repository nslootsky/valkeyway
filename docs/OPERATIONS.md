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

## Sizing

### The proxy

**Memory.** Give the proxy container at least 512 MB. The image's defaults keep it well inside that:

- **A small heap** (`-XX:MaxRAMPercentage=15`, in `JAVA_TOOL_OPTIONS`). The proxy's own heap is small: about 20 MB live after a collection. Glide frees the native memory behind larger responses only when the garbage collector collects their Java buffers. With a large heap, collections are rare and that native memory piles up: in our tests, a 512 MB heap let the process reach 1.4 GB, while a single collection brought it back to 330 MB. A small heap keeps collections frequent.
- **`MALLOC_ARENA_MAX=2`**, so glibc doesn't keep memory pools per CPU and hold on to freed memory.
- **`-XX:+ExitOnOutOfMemoryError`:** if the heap does run out, the process exits and the container restarts, rather than running on in a broken state.

Measured with the release image on 2 CPUs: 500 clients pipelining 512-byte SET/GET, then 200 clients with 100 KB values.

| Memory limit | Heap | Idle | Peak | Throughput |
|---|---|---|---|---|
| 512 MB | 126 MB (the JVM's minimum) | ~160 MB | ~470 MB | ~325k requests/s |
| 1 GB | 154 MB | ~140 MB | ~510 MB | ~330k requests/s |

With the JVM's own default heap (25%) memory follows the limit instead, to about 80% of it, and with a larger heap the container is killed for exceeding its limit. More memory doesn't make the proxy faster. Raise the heap only for very many connections (thousands), together with the limit. To change the defaults, set `JAVA_TOOL_OPTIONS` (keep `-XX:+ExitOnOutOfMemoryError` in it) or `MALLOC_ARENA_MAX`. For the jar outside the image, use the same flags: `MALLOC_ARENA_MAX=2 java -Xmx256m -XX:+ExitOnOutOfMemoryError -jar valkeyway.jar ...`.

**CPU.** Throughput grows with CPUs; about 330k requests/s on 2 CPUs in the test above. Set a CPU request, but avoid a hard CPU limit: throttling shows up directly as latency spikes. If you must set one, use whole CPUs. `proxy.clients.io-threads` (default 2) sets the network threads ([CONFIGURATION.md](CONFIGURATION.md#tuning)).

**Instances.** The proxy scales out: several instances behind a TCP load balancer serve one cluster. Each keeps its own connections to every primary, so size the cluster's `maxclients` for all instances.

### The Valkey nodes

These are general Valkey guidelines; the proxy adds nothing to them.

- **Set `maxmemory` below the container's memory limit.** A node also needs memory for client and replication buffers, the replication backlog and fragmentation, and a background save (RDB, or AOF rewrite) forks the process, whose copy-on-write pages can briefly need up to twice the data's memory. As a rule of thumb, `maxmemory` at about 70-75% of the limit with persistence on, about 85% with it off.
- **Choose the eviction policy** (`maxmemory-policy`): `allkeys-lru` for a cache, `noeviction` if writes must fail rather than drop data.
- **Modules:** vector indexes (valkey-search) live in memory; leave extra room for them.
- **CPU:** 1-2 CPUs per node; Valkey runs commands on one thread, with optional I/O threads.
- **Kubernetes:** requests equal to limits (Guaranteed QoS), so nodes aren't evicted under memory pressure. Spread primaries and their replicas over different machines and zones. On the hosts, disable transparent huge pages and set `vm.overcommit_memory=1`, so the fork for a background save doesn't fail.

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
| Slot migration (ASK, TRYAGAIN) | Commands succeed. Inside a client pipeline, a multi-key command whose keys are split mid-migration gets `TRYAGAIN`, as a standalone server's pipeline reports errors per command. |
| Slot moved, also to a newly added shard | Commands succeed, including inside pipelines. |
| Graceful failover (`CLUSTER FAILOVER`) | No errors. |
| A primary dies | Its shard's commands fail until the replica is promoted (about the cluster's node timeout), then succeed without a proxy restart. While the cluster counts itself down, other shards' commands can fail too. The errors are `ERR backend unavailable: ...` (below). |
| A primary hangs (frozen process, network partition) | Its shard's commands time out until the node answers again, even after its replica is promoted (a Glide limitation). |
| A seed DNS name is re-pointed at another cluster | The proxy moves to the new cluster at its next topology check ([CONFIGURATION.md](CONFIGURATION.md#seed-nodes-and-dns)). |

When the cluster can't serve a command, the error says whether the command ran, so the client can decide whether a retry is safe:

- `ERR backend unavailable: ...; the command did not run`
- `ERR backend unavailable: ...; the command may or may not have run`
- `ERR backend request timed out; the command may or may not have run`
