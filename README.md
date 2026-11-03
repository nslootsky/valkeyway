<div align="center">
  <img src="images/logo.png" alt="ValkeyWay Logo" width="280"/>
  <h1>ValkeyWay</h1>
  <p>
    <a href="https://github.com/nslootsky/valkeyway/actions/workflows/ci.yml"><img src="https://github.com/nslootsky/valkeyway/actions/workflows/ci.yml/badge.svg" alt="CI"/></a>
    <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"/></a>
  </p>
</div>

ValkeyWay is a proxy that presents a [Valkey cluster](https://valkey.io/topics/cluster-tutorial/) as a single standalone server. Applications and tools that only speak to one server connect to it unchanged, with no cluster-aware client needed, while the data lives in a cluster. Any Valkey or Redis client works.

Multi-key commands such as MGET, MSET and DEL, which a cluster rejects with a `CROSSSLOT` error when their keys are on different slots, just work. SELECT works too, on clusters with multiple databases (Valkey 9, including Amazon ElastiCache). It runs in front of self-managed and managed clusters, such as ElastiCache and Google Cloud Memorystore.

It is built on Java 25 and Netty, and uses [valkey-glide](https://github.com/valkey-io/valkey-glide) to talk to the cluster.

## Quick start

```bash
docker run -d --name valkeyway -p 6379:6379 -p 6380:6380 \
  ghcr.io/nslootsky/valkeyway:latest --proxy.cluster-nodes=node1:7000,node2:7001,node3:7002

valkey-cli SET mykey hello
valkey-cli MGET key1 key2 key3     # keys on different nodes, one reply
```

## Features

- **Looks like a standalone server.** RESP2 and RESP3 clients get the replies a standalone Valkey sends, byte for byte, which is checked against real nodes and Valkey's own test suite.
- **Cluster work handled.** Commands are routed by key, redirects are followed, and multi-key commands are split across slots. SCAN covers the whole cluster, and SELECT works per connection.
- **Transactions and WATCH,** atomic when a transaction's keys share a slot, and **Pub/Sub,** including sharded channels.
- **Fast.** Pipelines and concurrent clients' commands are batched to the cluster: 200–290k requests/s unpipelined and ~620k with pipelines, measured on one 24-core machine. Blocking commands never hold up other clients.
- **Survives cluster changes:** slot migrations, new shards, failovers, and a seed DNS name re-pointed at another cluster.
- **Secure when you need it:** client passwords, TLS for clients, and TLS with credentials to the cluster. All of it is off by default.
- **Observable:** health checks, metrics and Prometheus on a separate port.

## Compatibility

Most applications see no difference from a standalone server. The exceptions come from what a cluster can't do, chiefly atomic operations on keys in different hash slots. Use hash tags, such as `{user1}:name` and `{user1}:email`, to keep keys that are used together in one slot. Commands that administer a single node, such as SHUTDOWN or CONFIG SET, are refused by default.

[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) describes how each kind of command is handled, and every difference from a standalone server.

## Configuration

Only the seed nodes are required. Settings can come from `application.yml`, command-line arguments or environment variables:

```yaml
proxy:
  port: 6379
  cluster-nodes:                # IPs or DNS names, e.g. a configuration endpoint
    - "node1:7000"
    - "node2:7001"
```

The full reference is in [docs/CONFIGURATION.md](docs/CONFIGURATION.md), and passwords and TLS are in [docs/AUTH_AND_TLS.md](docs/AUTH_AND_TLS.md).

## Documentation

| Document | What's in it |
|---|---|
| [Configuration](docs/CONFIGURATION.md) | Every setting, seed nodes and DNS, tuning |
| [Authentication and TLS](docs/AUTH_AND_TLS.md) | Client passwords, TLS for clients and to the cluster |
| [Operations](docs/OPERATIONS.md) | Running, sizing the proxy and Valkey, health and metrics, what clients see during cluster changes |
| [Compatibility](docs/COMPATIBILITY.md) | How commands are handled, differences from a standalone server |
| [Architecture](docs/ARCHITECTURE.md) | How the proxy works, and links to its design notes |
| [Development](docs/DEVELOPMENT.md) | Building, testing, running locally |

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Report security vulnerabilities privately, as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)
