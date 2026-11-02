# Configuration

Settings come from `application.yml`, command-line arguments (`--proxy.port=6379`) or environment variables (`PROXY_PORT=6379`: drop the dashes, replace dots with underscores, uppercase). Only `proxy.cluster-nodes` is required.

```bash
java -jar valkeyway.jar --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002
```

## Reference

Every setting, with its default:

```yaml
proxy:
  host: 0.0.0.0                 # Listen address for clients
  port: 6379                    # Listen port for clients; 0 = none (TLS only, see tls.port)
  cluster-nodes:                # Seed nodes: IPs or DNS names (e.g. a configuration endpoint)
    - "127.0.0.1:7000"
    - "127.0.0.1:7001"
    - "127.0.0.1:7002"
  request-timeout-ms: 2000      # Timeout per backend request, including Glide's retries (old name: connect-timeout-ms)
  node-admin-commands: reject   # SHUTDOWN, REPLICAOF, CONFIG SET, ...: reject, or forward (to whichever node Glide picks)

  clients:
    max-clients: 10000          # Connections accepted at once (like Valkey's maxclients)
    timeout-seconds: 0          # Close clients idle this long; 0 = never (like Valkey's timeout)
    io-threads: 2               # Network threads; fewer = bigger backend batches (1 is fastest for small values)

  transactions:
    cross-slot: reject          # EXEC whose keys span slots: reject (CROSSSLOT, like a cluster node) or pipeline (run without atomicity)
    watch-pool:                 # Dedicated backend clients for connections that use WATCH
      max-size: 64              # Connections with keys watched at once; more wait
      idle-timeout-seconds: 60  # Close a pooled client after this long unused
      acquire-timeout-ms: 2000  # How long WATCH waits for a free client before failing

  blocking-pool:                # Dedicated backend clients for BLPOP, XREAD BLOCK, WAIT, ...
    max-size: 64                # Blocking commands running at once; more wait for a free client
    idle-timeout-seconds: 60    # Close a pooled client after this long unused

  read-from: primary            # Which nodes serve reads; replicas can lag the primary. See "Reading from replicas" below.
  client-az:                    # This proxy's availability zone; required by the az-affinity modes

  glide:
    inflight-requests-limit: 50000     # Outstanding requests per Glide client (Glide's default is 1000)
    coalesce-requests: true            # Send concurrent clients' commands to Glide in shared batches
    refresh-topology-from-seeds: true  # Check topology via the seeds, re-resolving DNS names
    topology-check-seconds: 0          # How often Glide checks for topology changes; 0 = Glide's default (60)

  auth:                         # Client passwords; unset = none. See AUTH_AND_TLS.md.
    password:                   # The default user's password: AUTH <password>
    users:                      # Named users: AUTH <user> <password>
      app: ...
    timeout-seconds: 10         # With a password, close connections not authenticated by then; 0 = never
  tls:                          # TLS for clients. See AUTH_AND_TLS.md.
    port: 0                     # TLS port; 0 = off
    cert-file:                  # PEM certificate chain (reloaded when it changes)
    key-file:                   # PEM private key, PKCS#8
    key-password:
    ca-file:                    # CA for client certificates
    client-certs: none          # none, optional or required
    protocols:                  # e.g. TLSv1.2,TLSv1.3; default the JDK's
  backend:                      # How the proxy connects to the cluster. See AUTH_AND_TLS.md.
    username:                   # Unset = default
    password:                   # Unset = no AUTH
    tls:
      enabled: false
      ca-file:                  # Unset = the system trust store
      insecure: false           # Skip certificate verification

management:                     # Spring Boot Actuator: health, metrics, Prometheus
  server:
    port: 6380
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
```

`PROXY CONFIG GET *` shows the settings in effect (passwords only as `(set)`). Settings are read at startup.

## Seed nodes and DNS

Seed nodes can be IPs or DNS names. With `refresh-topology-from-seeds` (the default), Glide resolves and asks the seeds again at each topology check. Re-pointing a DNS name at another cluster (a blue/green cutover) therefore moves the proxy there at the next check. Glide refreshes slots at most once every 15–30 s, so a cutover can take that much longer. If the seeds can't be reached, the proxy keeps using the nodes it already knows.

## Reading from replicas

By default every command goes to the primary of its shard. `read-from` lets reads go to replicas instead, for every client of the proxy. The proxy decides, not the client: to a client the proxy is a standalone server without replicas (INFO and ROLE say so), so a client's own read preference, such as Lettuce's or Glide's `ReadFrom`, has nothing to choose from. READONLY and READWRITE are accepted and change nothing. To let some applications read from replicas and others not, run two proxies with different `read-from` settings.

`read-from` takes Glide's `ReadFrom` strategies:

| `read-from` | Reads go to |
|---|---|
| `primary` (default) | The shard's primary |
| `prefer-replica` | Replicas in turn; the primary if a shard has none |
| `az-affinity` | Replicas in the proxy's availability zone, then other replicas, then the primary |
| `az-affinity-replicas-and-primary` | Replicas in the proxy's zone, then its primary if in the zone, then any node |
| `all-nodes` | Every node in turn, primaries included |
| `az-affinity-all-nodes` | Every node in the proxy's zone in turn, then any node |

The zone modes need `client-az`, and nodes announce their zone with Valkey's `availability-zone` setting. In the cloud they keep reads within a zone, which avoids cross-zone latency and traffic charges.

Replication is asynchronous, so a replica can return data a moment older than the primary's, including a value the same client has just overwritten, even within one pipeline. Writes always go to primaries, and so does everything that decides on data or holds a connection's state: transactions (MULTI/EXEC), WATCH, blocking commands, SCAN and Pub/Sub. HGETALL, ZRANGE ... WITHSCORES, XRANGE and the other commands whose reply is a map also stay on primaries, because the proxy routes them to a slot explicitly to keep their replies intact.

Glide connects to the replicas in the background, so right after startup, reads go to primaries for a moment. A replica that joins later, or that Glide didn't see when it read the topology, gets reads after Glide's next topology check (`glide.topology-check-seconds`, 60 by default); until then its shard's reads go to the primary.

## Tuning

- **`clients.io-threads`:** fewer network threads put more requests into each backend batch, which matters more than parallelism here. On a 24-core machine, 2 threads served 250–280k unpipelined requests/s from 200 clients, against 160–176k with 24. Raise it for large values.
- **`glide.inflight-requests-limit`:** one Glide client per database is shared by every connection, so Glide's default of 1000 would fail busy proxies with "maximum inflight requests" errors.
- **`blocking-pool.max-size`, `transactions.watch-pool.max-size`:** each blocking command and each connection with keys watched holds a backend client (one connection per primary) while it waits. The `proxy.blocking.pool.*` and `proxy.watch.pool.*` metrics show when the pools run full.
