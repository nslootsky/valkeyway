# Changelog

All notable changes to ValkeyWay. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-11-01

A new client protocol layer, and most of what a standalone Valkey server offers: RESP3, Pub/Sub, transactions with WATCH, TLS and passwords. Replies are checked against real nodes byte for byte, and against [Valkey's own test suite](docs/VALKEY_TEST_SUITE.md).

### Upgrading from 0.1.0

No configuration change is needed: every new setting is off by default. Some behavior that clients can see has changed:
- **RESP3:** clients that send `HELLO 3` now get RESP3 replies. In 0.1.0, `HELLO 3` was answered with protocol 2.
- **Node administration commands are refused by default:** SHUTDOWN, REPLICAOF, FAILOVER, DEBUG, CONFIG SET, ACL SETUSER, CLUSTER MEET and similar commands would reach whichever node Glide picks. Set `proxy.node-admin-commands: forward` for the old behavior.
- **Connection commands are answered by the proxy:** CLIENT, RESET, AUTH and READONLY/READWRITE. Forwarded, they acted on backend connections that every client shares.
- **PUBLISH** returns the number of proxy clients that receive the message.
- **`proxy.connect-timeout-ms` is now `proxy.request-timeout-ms`.** The old name still works, with a deprecation warning.
- **Admin commands:** `PROXY FLUSHCLIENTS` is gone, and `PROXY CONFIG` is GET-only.
- **The Docker image** is now based on Ubuntu (glibc) rather than Alpine, and is published for linux/amd64 and linux/arm64 as `ghcr.io/nslootsky/valkeyway`.

### Added

- **RESP3** (`HELLO 3`), with Valkey's RESP3 reply shapes: maps, sets, doubles, verbatim strings and pushes.
- **Pub/Sub:** SUBSCRIBE, PSUBSCRIBE and SSUBSCRIBE, in RESP2 subscribed mode and with RESP3 pushes. The proxy's clients share its subscriptions to the cluster.
- **WATCH**, private to each client connection. With `proxy.transactions.cross-slot: pipeline`, cross-slot transactions run in order without atomicity.
- **Client authentication:** a password for the default user and named users (`proxy.auth.*`), with AUTH and HELLO ... AUTH.
- **TLS for clients** (`proxy.tls.*`), optionally requiring client certificates, with certificates reloaded when their files change.
- **TLS and credentials to the cluster** (`proxy.backend.*`).
- **CLIENT** LIST, INFO, KILL, UNBLOCK, REPLY, NO-EVICT and NO-TOUCH, from the proxy's own connections.
- **INFO** as a standalone server answers it: the proxy's own clients, and stats and keyspace summed over the cluster.
- **Real PROXY commands:** STATS, CLIENTINFO and CONFIG GET.
- **Seed DNS names** are re-resolved at topology checks (`proxy.glide.refresh-topology-from-seeds`), so a re-pointed name moves the proxy to another cluster.
- **Topology changes** under live traffic are tested: migrations, new shards, failovers, and dead or hung primaries.
- **Release artifacts:** a GitHub release with the jar, and a multi-architecture image on the GitHub Container Registry.
- **Docs:** [configuration](docs/CONFIGURATION.md), [security](docs/SECURITY.md), [operations](docs/OPERATIONS.md), [compatibility](docs/COMPATIBILITY.md) and [architecture](docs/ARCHITECTURE.md). There's also a [catalog of the Glide and Valkey behaviors the proxy relies on](docs/UPSTREAM_ASSUMPTIONS.md).

### Changed

- **A new client-facing server on Netty,** replacing resp-server:
  - pipelines go to the cluster as ordered batches;
  - single commands from different connections are coalesced into shared batches;
  - blocking commands run on dedicated backend clients, so they never hold up other clients.

  Unpipelined throughput rose from about 150k to 200–290k requests/s, and pipelined clients are 2–4× faster. See [docs/RESP_LAYER.md](docs/RESP_LAYER.md).
- **Clearer errors when the cluster can't serve a command:** they say whether the command may have run.
- **SCAN cursors** belong to the database they started in, can be used once, and are released when abandoned.

### Fixed

- **Replies Glide reshapes** (WITHSCORES, streams, geo, ...) now have a standalone server's shapes.
- **Reply details now match a server's:** both null kinds, 64-bit integers, number formats and error codes.
- **Map replies** keep their order and binary keys. Glide misread unrouted map replies as multi-node results.
- **MSETEX** is routed by its keys. Glide routed it by its key count, and Valkey 9.1.1 nodes stored the keys even for slots they don't own.
- **A WATCH on a keyless transaction** (`MULTI`, `PING`, `EXEC`) now aborts when a watched key changes.
- **Blocking commands' argument errors** use Valkey's wording.
- **Pipelines:** a pipeline that mixed answered-locally commands and barriers could stall.
- **Inline commands** follow Valkey 9's quoting, and RESP requests are checked for strict CRLF.
- **Memory exhaustion:** a request's announced argument count (`*1048576`) no longer allocates the whole array up front, and per-command metrics no longer remember every made-up command name a client sends.
- **Refused node commands:** SWAPDB and Valkey 9's slot migration commands (CLUSTER MIGRATESLOTS, SYNCSLOTS, CANCELSLOTMIGRATIONS, FLUSHSLOT) are now refused by default, like other node administration.
- **ACL refusals** from the cluster are reported as NOPERM, not ERR.

## [0.1.0] - 2026-07-30

Initial release: a binary-safe RESP2 proxy presenting a Valkey cluster as a standalone server, with multiple databases, cross-slot command splitting, cluster-wide SCAN, MULTI/EXEC and blocking commands.

[0.2.0]: https://github.com/nslootsky/valkeyway/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/nslootsky/valkeyway/releases/tag/v0.1.0
