# Upstream assumptions

ValkeyWay depends on details of valkey-glide and Valkey that neither documents as a stable API: the wording of error messages, the Java types Glide returns for a reply, what Glide retries and how it refreshes topology, and how Valkey formats some replies. This page lists each one. The code (and the tests) that rely on an entry are tagged with its ID, such as `[upstream:G4]`, so you can find every affected place with:

```bash
grep -rn "upstream:G4" src
```

`UpstreamAssumptionsTest` checks that every ID listed here is tagged somewhere in `src/main`, and that every tag names a listed ID.

**Last verified against:** valkey-glide 2.5.3 (Java), Valkey 9.1.3, valkey-bundle 9.1.3 (search 1.2.1).

## When upgrading Glide or Valkey

1. Run the integration suite (`./mvnw verify -Dskip.integration.tests=false`). Each entry names the live test that fails if the assumption breaks. A failure points at an entry, and the entry points at the code.
2. For entries marked *source only*, no test can observe a change, so re-read the cited source at the new version.
3. Update "Last verified against" above, and the version an entry cites if its details changed.

Glide sources below are relative to the valkey-glide repository at tag `v2.5.3`; `redis-rs/` is `glide-core/redis-rs/redis/src/`.

---

## Glide

### G1: Server errors arrive as text, in Glide's own format

Glide's Java client has typed exceptions only for timeouts, a closed client, EXECABORT and an open circuit breaker (`java/client/.../glide/internal/AsyncRegistry.java`, the `errorTypeCode` switch). Every server error is a `RequestException` whose message Glide formats from redis-rs's `ErrorKind`, in one of these forms:

- `An error was signalled by the server: - TryAgain: Multiple keys request during rehashing of slot`: a kind name (`redis-rs/types.rs`, `ErrorKind`) that we map back to the server's code (`TRYAGAIN`).
- `WRONGTYPE: Operation against a key holding the wrong kind of value`: the server's code, followed by a colon.
- Errors that are already in wire format (`ERR invalid SCAN cursor`).

We strip the Java class prefix, recognize these forms, and send `CODE detail`, so clients can dispatch on the code.

Codes Glide doesn't know arrive as `Code: detail` (`types.rs`, `ErrorRepr::ExtensionError`), all-caps or not (the search module's `Index`). Glide's own kind names, and Rust's I/O error kinds (`BrokenPipe: ...` when a connection drops), are never server codes.

- **Code:** `ServerErrors` (`GLIDE_KIND_ERROR`, `BARE_KIND_ERROR`, `CODE_COLON_ERROR`, `EXTENSION_ERROR`, `GLIDE_KIND_CODES`, `GLIDE_KINDS`).
- **Live test:** `RespFidelityTest.errorsUseServerErrorCodes`. Captured strings are in `ServerErrorsTest`.

### G2: Outage failures arrive as text, and some leak Glide internals

While a node is down or unresponsive, Glide reports failures as text:

| Glide message (as normalized by G1) | Meaning | We send |
|---|---|---|
| `MOVED 3739 127.0.0.1:7003; ExtensionError` (also `ASK`) | A redirect Glide could not follow (target unreachable) | `ERR backend unavailable: can't reach …; the command did not run` |
| `ERR Received connection error `` `Requested connection not found - ConnectionNotFoundForRoute: <addr>` ``…` | No connection to the slot's node | `ERR backend unavailable: no connection to …; the command did not run` |
| `ERR BrokenPipe: …`, `ERR Received connection error …`, `… Failed to receive command response …` | Connection lost with the request outstanding | `ERR backend unavailable: lost the connection …; the command may or may not have run` |
| `TimeoutException` (any message), or `ERR timed out` for one command inside a batch | Request timeout | `ERR backend request timed out; the command may or may not have run` |
| `ClosingException` | The Glide client is closed | `ERR backend unavailable: the backend client is closed; the command may or may not have run` |

A raw MOVED would mislead clients of a standalone server (Jedis turns it into `JedisMovedDataException`), and the other messages mean nothing to them.

- **Code:** `ServerErrors.backendFailure`, `ServerErrors.error`.
- **Live test:** `TopologyChangeTest.trafficRecoversAfterAPrimaryIsKilled` and `trafficRecoversAfterAPrimaryStopsResponding` assert that every error clients see during an outage starts with `ERR backend`.

### G3: Inside a batch, Glide retries redirects but not other server errors

For a single command, Glide retries TRYAGAIN, CLUSTERDOWN, MASTERDOWN and LOADING (waiting between attempts), refreshes slots and retries on READONLY, and follows MOVED/ASK. Inside a non-atomic `ClusterBatch`, it follows MOVED/ASK "regardless", but returns the other errors unless the batch's `retry_server_error` is set (`redis-rs/cluster_async/pipeline_routing.rs`, `update_retry_map`). READONLY can't be retried in a batch at all (the TODO references valkey-glide #5226). Glide's request timeout bounds all retries of a request (`proxy.request-timeout-ms`).

We rely on this twice:
- **Coalesced batches:** commands from a coalesced batch that come back with one of these codes, or with a G2 "did not run" error, are resent alone.
- **Client pipelines:** they keep the errors.

- **Code:** `ServerErrors.REJECTED_UNRUN` and `rejectedWithoutRunning`, `Coalescer`.
- **Live test:** `TopologyChangeTest.multiKeyCommandsWaitOutAMigration`. It fails if coalesced commands start surfacing TRYAGAIN, or if pipelines stop surfacing it (meaning Glide started retrying in batches).

### G4: A non-atomic batch keeps order; separate requests don't

Glide executes a non-atomic `ClusterBatch` in order on each node, but it can reorder requests submitted separately, even from one thread to one node. This is why a connection has only one backend unit (a single command or a batch) in flight at a time.

- **Code:** `Connection` (class comment, `startUnit`), `Backend.pipeline`.
- **Live test:** `PipelineOrderingTest`.

### G5: Unrouted `customCommand` scrambles top-level map replies

Without a route, `ClusterValue.of()` treats any reply whose top level is a map as a multi-node result keyed by node address. It rebuilds the reply as an unordered `HashMap` and decodes its keys as UTF-8, which loses order and corrupts binary keys (valkey-glide #4963). With an explicit single-node route, the reply comes back untouched. Batch results don't go through `ClusterValue`.

- **Code:** `SlotRoutes`, `MapReplyCommands`, `Backend.send`.
- **Live test:** `DifferentialRepliesTest` (HGETALL, ZRANGE WITHSCORES, XRANGE with binary fields, compared byte for byte with a direct connection).

### G6: Glide reshapes replies by command name

Glide talks RESP3 to the cluster and converts some replies according to the command name (`glide-core/src/client/value_conversion.rs`, `expected_type_for_cmd`). Examples: stream entries become maps of id to fields, geo results are nested and parsed as doubles, and WITHSCORES results become maps or arrays of pairs. Some RESP3 replies also differ from RESP2 on their own. We map each of these back to the RESP2 shape a standalone server sends. The list of commands whose reply can be a top-level map (needed for G5) comes from the same function.

- **Code:** `ReplyShapes.shape`, `MapReplyCommands.NAMES`.
- **Live test:** `DifferentialRepliesTest`. If Glide reshapes a new command, that command's case fails. A command not listed there is not covered, so add a case for any new reply type.

### G7: Which Java types Glide returns for a reply

With binary output (`withBinaryOutput`, and `GlideString` arguments to `customCommand`):

- **`OK`, `PONG` and `QUEUED`** arrive as `String`.
- **Other status replies** (TYPE, BGSAVE, BGREWRITEAOF, MIGRATE, and PING inside EXEC) arrive as `GlideString`, which looks exactly like a bulk string, so we list those commands.
- **RESP3 verbatim strings** (CLIENT LIST, CLUSTER INFO, INFO, ...) arrive as `String` and contain line breaks, which is how we tell them from statuses.
- **Strings nested inside a reply** were bulk strings on the wire.
- **Both RESP2 nulls** (`$-1` and `*-1`) arrive as Java `null` (see V2).
- **A failed command inside a batch or EXEC** arrives as a `Throwable` element.
- **COMMAND DOCS argument flags** arrive as strings. Valkey sends them as statuses, and redis-cli asserts that they are (`cliAddCommandDocArg`). Likewise COMMAND INFO's flags, ACL categories and key-spec flags.
- **Booleans** come from Glide's per-command conversions (SMISMEMBER, ...) of integer replies; Valkey sends integers, in RESP3 too.
- **RESP3 sets** arrive as unordered Java sets, so the server's order is lost (SMEMBERS, and the sets inside COMMAND INFO and COMMAND DOCS).

- **Code:** `GlideReplies` (`simpleOrText`, `nested`), `ReplyShapes.STATUS_COMMANDS`, `CommandCommand.DOC_FLAG_VALUES` and `commandEntries`.
- **Live test:** `DifferentialRepliesTest` (TYPE is a status reply, SET is +OK, null cases, EXEC with reshaped replies, SMISMEMBER), `RespFidelityTest.verbatimTextRepliesAreBulkStrings`, `RespFidelityTest.commandDocsFlagsAreStatuses` and `commandInfoFlagsAreStatuses`, `Resp3Test.commandDocsMatchAServers`.

### G8: Topology refresh behavior

- A live client follows topology changes itself: through MOVED, through connection errors, and through periodic checks every 60 s by default (`redis-rs/cluster_async/mod.rs`, `periodic_topology_check`).
- **Request timeouts don't trigger a refresh.** The refresh a periodic check eventually starts queries every known node over connections with no response timeout, so a hung (not dead) primary blocks its shard until it answers again (`refresh_slots_inner`; observed log: `response_timeout=18446744073709551615s`). Not something we can fix on our side; an upstream issue is drafted.
- With `refreshTopologyFromInitialNodes`, every refresh re-resolves the configured seeds and asks them. If they can't be reached, Glide falls back to its existing connections (`calculate_topology_from_random_nodes`, `get_random_connections_from_initial_nodes`).
- Slot refreshes are throttled to one per 15 s plus up to 15 s of jitter (`redis-rs/cluster_topology.rs`, `DEFAULT_SLOTS_REFRESH_WAIT_DURATION`, `DEFAULT_SLOTS_REFRESH_MAX_JITTER_MILLI`). This throttle can't be configured from Java.

- **Code:** `BackendClients` (`buildConfig`), `ValkeywayProperties.Glide` (`refreshTopologyFromSeeds`, `topologyCheckSeconds`).
- **Live test:** `SeedDnsTest` (refresh from seeds, fallback), `TopologyChangeTest.trafficRecoversAfterAPrimaryStopsResponding` (hung primary; it would start recovering sooner if Glide fixes this).

### G9: Glide's default in-flight limit is 1000 requests per client

Every proxy connection shares one Glide client per database, so with Glide's default (1000), more than 1000 busy clients get `Client reached maximum inflight requests`. We set 50000; per-connection backpressure keeps memory bounded.

- **Code:** `ValkeywayProperties.Glide` (`inflightRequestsLimit`).
- **Live test:** `ManyClientsTest.manyConcurrentClientsDoNotHitTheBackendInflightLimit`.

### G10: Single commands often fail to follow ASK under concurrent traffic

While a slot is migrating, the source node answers with ASK for a key that has already moved, and Glide follows it to the importing node. For single commands sharing a connection with many concurrent requests, Glide often returns the importing node's `Moved: <slot> <source>` instead. With coalescing off, this happened to about a third of 3,000 concurrent GETs in `TopologyChangeTest`. The importing node evidently received the command without the preceding `ASKING`, plausibly because another request landed between the two on the shared connection (not confirmed). Inside a batch, Glide follows ASK reliably, since it writes `ASKING` and the command together.

The node rejected the command without running it, so a single command that comes back with a redirect is resent as a one-command batch. Other errors Glide has already retried on this path (TRYAGAIN, CLUSTERDOWN, ...) are not resent, since that would only double the wait during an outage. Worth reporting upstream.

- **Code:** `ServerErrors.isUnfollowedRedirect`, `Backend.send`.
- **Live test:** `TopologyChangeTest.askRedirectsDuringAMigrationAreFollowed` (3,000 GETs during a migration). Re-check with `proxy.glide.coalesce-requests=false`, which sends every command alone.

### G11: One Glide client multiplexes one connection per node

A `GlideClusterClient` sends all its requests for a node over one connection. Connection-scoped server state is therefore shared by everyone using the client. For WATCH, that means one proxy client's EXEC or UNWATCH would drop another's watches, and one client's watched key would abort another's transaction. Connections that use WATCH lease their own client until EXEC, DISCARD, UNWATCH or disconnect. An atomic `ClusterBatch` on that client runs MULTI/EXEC over the same connection as the WATCH (for keys in the same slot). Blocking commands lease clients for the same reason (V5).

The same goes for every other connection-scoped command, which the proxy therefore answers itself and never forwards. Measured on the shared connections before that change:
- `CLIENT REPLY OFF` made one node's keys unreachable for every client;
- `RESET` on a database-1 connection switched every database-1 client to database 0;
- `MONITOR` desynchronized a connection;
- `CLIENT PAUSE` stalled a node, and `CLIENT KILL` killed other applications' connections;
- `AUTH` would switch the shared connection's user.

- **Code:** `WatchClientPool`, `WatchSession`, `WatchCommand`, `ExecCommand`, `ClientCommand`, `ResetCommand`, `AuthCommand`, `LocalCommand`.
- **Live test:** `WatchTest` (two and three clients interleaving WATCH, UNWATCH and EXEC), `ConnectionCommandsTest` (other clients' reads in both databases after each connection-scoped command).

### G12: Glide's cluster scan isn't binary-safe

`GlideClusterClient.scan` and `scanBinary` replace bytes that aren't valid UTF-8 with U+FFFD, both in the keys they return and in the MATCH pattern. (`CommandManager.submitClusterScanToJni` passes the pattern on as a Java `String`, `options.getMatchPattern().toString()`, even when it was given as a binary `GlideString`, and the keys come back decoded the same way.) A key `\xffkey` comes back from SCAN as `\xef\xbf\xbdkey`, and a pattern with such bytes matches nothing. Other commands are binary-safe. Fixing it on our side would mean implementing cluster-wide SCAN ourselves, node by node, and losing Glide's handling of slots that move during a scan, so this is documented instead. Worth reporting upstream.

- **Code:** `ScanCommand`.
- **Live test:** `ScanFidelityTest.glideCorruptsNonUtf8KeysInScan` pins the current behavior and fails once Glide fixes it.

### G13: Glide's TLS to the cluster has no client certificate

Glide 2.5.3 can connect over TLS (`useTLS`), with custom root certificates or without verification (`TlsAdvancedConfiguration`), and authenticate with a username and password (`ServerCredentials`), but it can't present a client certificate. A cluster with `tls-auth-clients yes`, Valkey's default once TLS is on, refuses the proxy; it needs `tls-auth-clients no` or `optional`. Glide checks the node's certificate against the address it connects to, which for every node but the seeds is the address in CLUSTER SLOTS, usually an IP. The certificates must name those addresses, or `proxy.backend.tls.insecure` must be set. Over TLS, a node reports the other nodes' TLS ports (`tls-cluster yes`), and Glide follows them.

- **Code:** `BackendClients.buildConfig`.
- **Live test:** `BackendTlsTest` (a TLS-only cluster that requires a password, with certificates for 127.0.0.1).

### G14: Glide's Pub/Sub API

The proxy subscribes through Glide's dynamic Pub/Sub API (`subscribe`, `psubscribe`, `ssubscribe` and their unsubscribe counterparts, with a timeout), with messages delivered to the callback of the client's `ClusterSubscriptionConfiguration`. Glide keeps the subscriptions across reconnects and slot moves. Three details shape the design:
- The callback gets the same `PubSubMessage` for a message and a shard message (`MessageHandler`: `Message` and `SMessage` share a case), so shard channels get a client of their own.
- Channel names and patterns are passed as `String`, so names that aren't valid UTF-8 don't reach the cluster exactly. Payloads are binary-safe.
- A subscription to a regular channel or pattern lives on one node. PUBLISH reaches every node, but keyspace notifications are published only on the node that holds the key, so through the proxy they arrive only from that one node. *Not covered by a test.*

- **Code:** `PubSubHub`, `PubSubCommand`.
- **Live test:** `PubSubTest` (messages, patterns and shard messages; messages published on a node).

### G15: Glide routes commands it doesn't know by their first argument

For a command without a rule in its routing table, Glide takes the first argument as the key (`redis-rs/cluster_routing.rs`, `base_routing`: `_ => RouteBy::FirstKey`). For a command whose first key comes later, that's the wrong slot. The proxy derives the affected commands from the COMMAND table (first key not argument 1, minus those Glide has a rule for: EVAL, FCALL, LMPOP, ZUNION, XREAD, MIGRATE, BITOP, ...) and routes them to their keys' slot itself, outside batches, which can't route individual commands. With Valkey 9.1 that's only MSETEX, and it matters more than an extra MOVED hop: Valkey 9.1.1 nodes run MSETEX for keys of slots they don't own, storing them where no read finds them.

- **Code:** `GlideRouting`, `Backend.sendOnce`, `CatchAllCommand.canRunAsync`.
- **Live test:** `MsetexRoutingTest`.

### G16: Glide's handling of the search module's commands

Glide has rules for the search module (`FT.*`) that change its replies:
- **Routing:** `FT._LIST` goes to all primaries, with the replies joined (`redis-rs/cluster_routing.rs`: `RouteBy::AllPrimaries`, `ResponsePolicy::CombineArrays`). With search in coordinator mode (V12) every primary knows every index, so each name came back once per primary; the proxy removes the repeats. Other `FT.*` commands are routed by the index name (G15).
- **Reshaped replies** (`glide-core/src/client/value_conversion.rs`, `expected_type_for_cmd`): `FT.SEARCH` becomes `[total, {key: {field: value}}]` (NOCONTENT's keys get empty maps), `FT.AGGREGATE` loses its leading count and its rows become maps, and `FT.INFO` becomes a map, which at the top level Glide's `ClusterValue` would misread (G5) unless routed explicitly. The proxy routes `FT.INFO` to its index name's slot and rebuilds all three. `FT.SEARCH ... WITHSORTKEYS` expects a reply the module doesn't send, so Glide fails it.
- **Errors:** the module's error code `Index` (`Index with name 'x' not found`) arrives as `Index: with name ...`, Glide's form for codes it doesn't know (G1).

- **Code:** `SearchListCommand`, `ReplyShapes.searchResults`, `aggregateRows`, `MapReplyCommands.routedByFirstArgument`, `ServerErrors.EXTENSION_ERROR`.
- **Live test:** `ModulesTest` (search replies compared with a node's, single, pipelined and RESP3).

---

## Valkey

### V1: How Valkey formats numbers as text

- **Doubles** (`util.c`, `d2string`): exact integers print as integers, everything else as the shortest round-trip form in fpconv notation (`0.1`, `1e+20`), and `inf`/`-inf`/`nan`.
- **Geo distances:** `%.4f`.
- **Coordinates:** `%.17Lf` with trailing zeros trimmed.

Glide hands us these as parsed doubles (G6), so we have to format them again.

- **Code:** `DoubleFormat`, `ReplyShapes` (`distance`, `coordinate`).
- **Live test:** `DifferentialRepliesTest` (ZSCORE, ZINCRBY, GEODIST, GEOPOS, GEOSEARCH WITH*), `RespFidelityTest`.

### V2: Which commands answer "nothing" with a null array

RESP2 has two nulls, and Glide returns both as Java `null` (G7). Valkey sends `*-1` for:
- blocking timeouts of list, zset and stream commands (`blocked.c`, `replyToBlockedClientTimedOut`);
- LMPOP/ZMPOP/XREAD/XREADGROUP with no data;
- XRANGE COUNT 0;
- LPOP/RPOP with a count on a missing key;
- ZRANK WITHSCORE for a missing member;
- an aborted EXEC.

Everything else gets `$-1`. These come from the `addReplyNullArray` call sites.

- **Code:** `ReplyShapes.NULL_ARRAY_COMMANDS` and `nullReply`, `ExecCommand`.
- **Live test:** `DifferentialRepliesTest` (null cases), `RespFidelityTest` (aborted EXEC), `BlockingCommandsTest` (timeouts).

### V3: CLUSTER NODES line format

`<id> <ip:port@cport[,hostname]> <flags> <primary> <ping-sent> <pong-recv> <epoch> <link-state> <slots…>`. The flags we skip on are `fail`, `fail?`, `handshake` and `noaddr`. We use this to seed new clients from the live topology when the configured seeds can't be reached.

- **Code:** `BackendClients.parseClusterNodes`.
- **Live test:** `SeedDnsTest.newClientsFallBackToKnownNodesWhenTheSeedsAreGone`.

### V4: COMMAND key specs and movable keys

Key positions come from the legacy `first`/`last`/`step` fields of the COMMAND reply. Commands flagged `movablekeys` (EVAL, ZUNIONSTORE, XREAD, ...) are parsed locally by command-specific rules, which mirror the server's `*GetKeys` functions in `db.c`.

- **Code:** `CommandKeyExtractor`, `MovableKeys`.
- **Live test:** `HashTagsTest`, `CrossSlotOperationsTest`, `DifferentialRepliesTest` (routed map-reply commands). Unit tests: `CommandKeyExtractorTest`.

### V5: Which commands block, and where their timeout is

A command that can block holds its connection on the server, so it must not run on Glide's shared multiplexed connection. The list and the timeout positions and units follow the Valkey command reference: BLPOP-style commands take seconds as the last argument, XREAD/XREADGROUP take `BLOCK <ms>`, and WAIT/WAITAOF take ms. So do the replies they send on timeout (V2).

- **Code:** `BlockingCommands`, `BlockingCommandRunner`.
- **Live test:** `BlockingCommandsTest` (integration).

### V6: Queue-time errors inside MULTI

Valkey answers a command queued in MULTI that can't run, and the transaction is then discarded: EXEC answers `EXECABORT Transaction discarded because of previous errors.` The errors come from `processCommand` in `server.c`:

- `ERR unknown command '<name>', with args beginning with: '<arg>' '<arg>' `: arguments quoted until the list reaches 128 characters, with a trailing space.
- `ERR unknown subcommand '<sub>'. Try <NAME> HELP.`
- `ERR wrong number of arguments for '<name>' command`: the full name, e.g. `config|get`, checked against the COMMAND table's arity.
- `ERR Command '<name>' not allowed inside a transaction`: for commands flagged `no_multi` in the COMMAND table (MULTI, WATCH, SUBSCRIBE, ...). This is Valkey 9's wording; older servers answered `ERR MULTI calls can not be nested` and `ERR WATCH inside MULTI is not allowed`, and didn't discard the transaction.

The proxy checks unknown commands and arity itself, from the COMMAND table, since it queues transactions rather than sending MULTI ahead.

- **Code:** `CommandKeyExtractor.queueError` and `notAllowedInTransaction`, `TransactionQueue`, `MultiCommand`, `WatchCommand`.
- **Live test:** `TransactionFidelityTest` (proxy and a direct node compared byte for byte).

### V7: Authentication replies and limits

With `requirepass` set (or ACL users), Valkey 9 answers:
- `NOAUTH Authentication required.` to any command but AUTH, HELLO, QUIT and RESET (the `no_auth` commands) before the client authenticates;
- `WRONGPASS invalid username-password pair or user is disabled.` to a wrong password or an unknown user, after which the connection keeps its current user;
- `NOAUTH HELLO must be called with the client already authenticated, otherwise the HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and select the RESP protocol version at the same time` to HELLO without AUTH;
- `ERR Syntax error in HELLO option '<option>'`, `NOPROTO unsupported protocol version` and `ERR Protocol version is not an integer or out of range` for malformed HELLO.

RESET switches back to the default user, so with a password the connection must authenticate again. Before a client authenticates, a request may have at most 10 arguments of at most 16 KB each, or Valkey answers `ERR Protocol error: unauthenticated multibulk length` (or `bulk length`) and closes the connection (`processMultibulkBuffer` in `networking.c`). Checked by hand against Valkey 9.1.1.

- **Code:** `AuthCommand`, `HelloCommand`, `ResetCommand`, `CommandTable` (NOAUTH), `RespDecoder` (limits).
- **Live test:** `ClientAuthTlsTest` (the replies, as Valkey gives them), `TlsAndAuthServerTest`.

### V8: Pub/Sub replies and subscribed mode

A (P|S)SUBSCRIBE confirms each name with `[subscribe, name, count]` (a push in RESP3), where the count is the client's channels plus patterns, or for SSUBSCRIBE its shard channels. An UNSUBSCRIBE with nothing to unsubscribe answers `[unsubscribe, nil, count]`. A RESP2 client with subscriptions may only run (P|S)SUBSCRIBE, (P|S)UNSUBSCRIBE, PING, QUIT and RESET; anything else gets `ERR Can't execute '<command>': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are allowed in this context`, and PING answers `[pong, message]`. Messages are `[message, channel, payload]`, `[pmessage, pattern, channel, payload]` and `[smessage, channel, payload]`. RESET drops every subscription. Confirmations and messages are sent even with CLIENT REPLY OFF (Valkey sends all pushes regardless).

- **Code:** `PubSubCommand`, `PubSubSession`, `CommandTable` (subscribed-mode error), `PingCommand`, `Connection.isPush` (CLIENT REPLY OFF).
- **Live test:** `PubSubTest.confirmationsAndErrorsMatchAServer` and `confirmationsMatchAServerInResp3` (proxy and node compared frame by frame).

### V9: Blocking commands' argument errors

Valkey checks a blocking command's timeout in `getTimeoutFromObjectOrReply` (timeout.c): seconds (BLPOP & co.) must parse as a long double, hexadecimal included as `strtold` reads it (`ERR timeout is not a float or out of range`), be at most LLONG_MAX milliseconds once added to the current time (`ERR timeout is out of range`, also for `inf`), and not be negative (`ERR timeout is negative`); milliseconds (XREAD BLOCK, WAIT, WAITAOF) must be a long long (`ERR timeout is not an integer or out of range`). Glide parses blocking commands' timeouts itself and rejects bad ones (or a missing one) with its own text, so the proxy checks arity (from the COMMAND table) and the timeout first, with Valkey's words.

CLIENT UNBLOCK is passed on to the node as `CLIENT UNBLOCK <id> TIMEOUT|ERROR` for the pooled backend connection the command waits on (its ID from CLIENT ID on that connection), so the node ends the command and answers it: the command's timeout reply, or `UNBLOCKED client unblocked via CLIENT UNBLOCK`. Closing the connection instead isn't enough: until the node notices, the command still waits there, and the next element pushed to the key goes to it.

- **Code:** `BlockingCommands.seconds`/`millis`, `CatchAllCommand.execute`, `BlockingCommandRunner` (`Waiter`).
- **Live test:** `DifferentialRepliesTest` (bad timeouts, compared with a node), `BlockingCommandsTest`.

### V10: INFO fields that clients and tools read

Valkey's test suite and monitoring tools read INFO fields by name: `blocked_clients` (how the suite waits for a client to block), `connected_clients`, `expired_keys`, `total_error_replies`, the `dbN:keys=..,expires=..,avg_ttl=..` keyspace lines, and `role`/`redis_mode`, which clients use to detect a standalone server. The proxy answers clients, replication and cluster from its own state, sums stats and keyspace over the primaries, and takes the other sections from one primary.

- **Code:** `InfoCommand`.
- **Live test:** `InfoTest`.

### V11: Request parsing (Valkey 9)

Inline commands are split by `sdssplitargs` (`sdsparsearg` in sds.c). Since Valkey 9 a closing quote ends the quoted part but not the argument, so `"test"'-'value` is the single argument `test-value`; older servers required whitespace after a closing quote. A quote left open, or a NUL byte inside quotes, is `ERR Protocol error: unbalanced quotes in request`. Valkey 9 also checks RESP requests strictly: every CR ending a header line must be followed by LF, and every argument by CRLF, or it answers `ERR Protocol error: invalid CRLF in request` (`processMultibulkBuffer` in networking.c).

- **Code:** `InlineArgs`, `RespDecoder.checkLf`.
- **Live test:** `RespDecoderTest.quotedAndUnquotedPartsJoinUp` and `requiresLfAfterEveryCr` (Valkey's own cases), `RespFidelityTest.inlineQuotingMatchesAServer` (compared with a node).

### V12: valkey-search searches across shards only in coordinator mode

The search module indexes and queries every shard's keys only when loaded with `--use-coordinator`: `FT.CREATE` then reaches every primary, and `FT.SEARCH` on any node fans out to all shards. Without it, an index lives on the node that created it, covers that node's keys, and queries answer from that node alone, with no error. `FT.INFO` reports the node it reaches; `FT.INFO <index> PRIMARY` sums over all primaries.

- **Code:** `SearchListCommand` (correct in both modes).
- **Live test:** `ModulesTest.vectorSearchCoversEveryShard`, against valkey-bundle 9.1.3 (search 1.2.1).
