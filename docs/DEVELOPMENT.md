# Development

Requires Java 25. The Maven wrapper is included.

```bash
./mvnw package -DskipTests                     # build target/valkeyway-*.jar
./mvnw test                                    # unit tests, no containers needed
./mvnw verify -Dskip.integration.tests=false   # unit and integration tests (Docker or Podman)
```

Integration tests start Valkey clusters with Testcontainers. CI (`.github/workflows/ci.yml`) runs the unit tests, then the integration tests against the packaged jar.

## Running locally

Either everything in containers (a 3-node cluster and the proxy, built from source):

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

or a cluster from the `valkey-server` on your PATH, and the proxy from the jar:

```bash
scripts/setup-cluster.sh                           # 3 primaries and 3 replicas on 7000-7005
java -jar target/valkeyway-*.jar                   # the proxy on 6379, with application.yml's defaults
scripts/setup-cluster.sh stop
```

Then:

```bash
valkey-cli SET mykey hello
valkey-cli MGET key1 key2 key3                     # keys in different slots: split by the proxy
valkey-cli SCAN 0 MATCH "prefix:*" COUNT 100       # across the whole cluster
```

To build the Docker image: `docker build -t valkeyway:latest .`

## Scripts

| Script | Purpose |
|---|---|
| `scripts/setup-cluster.sh` | A local cluster for running the proxy against |
| `scripts/bench-throughput.sh` | Throughput and latency for SET/GET, pipelined and not; compare `proxy -p 6379` with `direct --cluster -p 7000` |
| `scripts/bench-blocking-stall.sh` | Whether a blocked BLPOP holds up other clients |
| `scripts/valkey-suite.sh` | Valkey's own test suite against the proxy, see [VALKEY_TEST_SUITE.md](VALKEY_TEST_SUITE.md) |

## Tests worth knowing

- **`DifferentialRepliesTest`:** runs each case against the node that owns its keys and through the proxy, and requires identical replies, in RESP2, pipelined and RESP3. Add a case for any new reply type.
- **`TopologyChangeTest`:** slot migrations, failovers and killed or hung primaries under live traffic.
- **`UpstreamAssumptionsTest`:** keeps [UPSTREAM_ASSUMPTIONS.md](UPSTREAM_ASSUMPTIONS.md) and the `[upstream:ID]` tags in the code in step.

## Tech stack

Java 25, Spring Boot 4.1, Netty 4.2 (client-facing server, native epoll on Linux), valkey-glide 2.5 (cluster client), Micrometer and Spring Boot Actuator.
