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

The image is published for linux/amd64 and linux/arm64, tagged with each version and `latest`. It runs as a non-root user and has a Docker `HEALTHCHECK` on the health endpoint. It is based on Debian 13 slim, with a Java runtime cut down by jlink to the modules the proxy uses.

## Sizing

### The proxy

**Memory: 2 GB.** The image is set up for a 2 GB container:

- **G1 with a 70% heap** (`-XX:MaxRAMPercentage=70`). Glide hands the proxy responses over 16 KB (large values, and batch replies, which the proxy's batching makes common) in native memory, freed when the garbage collector collects their Java buffers. G1's frequent young collections free it promptly.
- **jemalloc** (`LD_PRELOAD`, with `MALLOC_CONF=metadata_thp:auto,background_thread:true`), which gives memory back after load spikes.
- **Compact object headers** (`-XX:+UseCompactObjectHeaders`, Java 25).
- **`-XX:+ExitOnOutOfMemoryError`:** if the heap does run out, the process exits and the container restarts, rather than running on in a broken state.

Measured with the release image in a 2 GB container. The load: 500 clients pipelining 512-byte SET/GET, then 200 clients with 100 KB values.

| CPUs | Throughput | p99 | Peak memory | 30 s after the load |
|---|---|---|---|---|
| 2 | ~390k requests/s | ~21-26 ms | ~1.1-1.2 GB | ~0.8-0.9 GB |
| 4 | ~550k requests/s | ~17-21 ms | ~1.1-1.2 GB | ~0.8-0.9 GB |

What we tried and why the defaults are what they are:

- **ZGC:** don't use it with this proxy. ZGC lets the heap fill and collects in long concurrent cycles, and Glide's native buffers piled up meanwhile: the process reached 6.5 GB with a 1.4 GB heap, while G1 with the same heap levelled off around 1 GB. Collecting every second didn't keep up.
- **Smaller containers:** the proxy needs about 600 MB outside the heap under heavy large-value load (the JVM's own memory plus Glide's buffers in flight). A 1 GB container with a 70% heap leaves 300 MB and was killed; 1 GB works with the JVM's default heap of 25% (peaking at about 80% of the limit). With small values the need is much lower.
- **glibc instead of jemalloc** (`MALLOC_ARENA_MAX=2`) used about the same peak memory, but held on to it after the load. `percpu_arena:percpu` and Glide's in-flight request limit made no difference; compact object headers neither helped nor hurt this workload.

To change the defaults, set `JAVA_TOOL_OPTIONS` (keep `-XX:+ExitOnOutOfMemoryError` in it), `MALLOC_CONF` or `LD_PRELOAD`. For the jar outside the image, the same settings apply: `LD_PRELOAD=/path/to/libjemalloc.so.2 MALLOC_CONF=metadata_thp:auto,background_thread:true java -XX:MaxRAMPercentage=70 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError -jar valkeyway.jar ...`.

**CPU.** Throughput grows with CPUs, as above. Set a CPU request, but avoid a hard CPU limit: throttling shows up directly as latency spikes. If you must set one, use whole CPUs. `proxy.clients.io-threads` (default 2) sets the network threads ([CONFIGURATION.md](CONFIGURATION.md#tuning)).

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
