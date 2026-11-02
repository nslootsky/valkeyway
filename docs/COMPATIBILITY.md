# Compatibility

ValkeyWay aims to be indistinguishable from a standalone Valkey server. Replies match a server's byte for byte, in RESP2 and RESP3: reply shapes, null kinds, number formats and error texts. `DifferentialRepliesTest` compares more than 70 commands with a direct connection to a node, and [Valkey's own test suite](VALKEY_TEST_SUITE.md) runs against the proxy.

The differences below have two sources. Some things a cluster can't do, such as atomic operations on keys in different slots. Some commands are about one server, not a cluster, such as DEBUG or MONITOR.

## How commands are handled

Most commands go to the cluster unchanged, routed by key, with Glide following MOVED and ASK redirects. The proxy changes or answers some itself:

- **Multi-key commands** (DEL, UNLINK, MGET, MSET, EXISTS, ...) whose keys span slots are split per slot and their replies combined.
- **SCAN** iterates the whole cluster. Its cursors work across connections.
- **SELECT** works per connection (needs Valkey 9 with `cluster-databases`).
- **MULTI/EXEC/DISCARD and WATCH/UNWATCH:** the proxy queues a transaction and runs it atomically on the node that owns its keys. Each connection's WATCH runs on a dedicated backend client, so it is private to that connection. Queue-time errors and EXECABORT behave as on a server.
- **Blocking commands** (BLPOP, BLMOVE, BZPOPMIN, XREAD/XREADGROUP BLOCK, WAIT, ...) run on dedicated backend clients, so they never hold up other clients.
- **Pub/Sub** (SUBSCRIBE, PSUBSCRIBE, SSUBSCRIBE): the proxy keeps one set of subscriptions to the cluster for all its clients and fans each message out. PUBLISH and SPUBLISH answer with the number of proxy clients that receive the message.
- **Connection commands are answered by the proxy:** CLIENT (ID, INFO, LIST, KILL, UNBLOCK, GETNAME/SETNAME, SETINFO, REPLY, NO-EVICT, NO-TOUCH), HELLO, AUTH, RESET, SELECT, READONLY/READWRITE, PING, ECHO, TIME and QUIT. Forwarded, they would act on backend connections that every client shares.
- **INFO** answers like a standalone server:
  - **From the proxy:** the clients, replication and cluster sections (its own clients, including `blocked_clients`; role master; cluster support disabled).
  - **Summed over the primaries:** stats and keyspace.
  - **From one primary:** the other sections.
- **CLUSTER INFO and CLUSTER NODES** answer as on a server without cluster support. Other CLUSTER subcommands go to the cluster.
- **ACL WHOAMI** names the proxy user. Other ACL subcommands go to a node.
- **COMMAND** goes to the cluster, with COMMAND INFO and DOCS flags sent as statuses, as redis-cli expects.
- **PROXY** is the proxy's own command: STATS, CLIENTINFO <id>, CONFIG GET <name|*>, CLUSTER INFO and HELP.

### Commands that administer a node

Through the proxy, these would reach whichever node Glide picks, so they're refused by default (`proxy.node-admin-commands: reject`):
- SHUTDOWN, REPLICAOF/SLAVEOF, FAILOVER and DEBUG;
- SAVE, BGSAVE and BGREWRITEAOF;
- SYNC/PSYNC/REPLCONF and MODULE;
- CONFIG SET/REWRITE/RESETSTAT;
- ACL SETUSER/DELUSER/LOAD/SAVE;
- SWAPDB, which would swap databases on one node only;
- the CLUSTER subcommands that change the cluster (MEET, FORGET, SETSLOT, ADDSLOTS, FAILOVER, RESET, MIGRATESLOTS, FLUSHSLOT, ...).

Their read-only counterparts work, such as CONFIG GET, ACL WHOAMI and CLUSTER KEYSLOT. Run administration against the nodes directly, or set `forward` if you know where they'll land. This guards against mistakes, not malice: see [SECURITY.md](SECURITY.md#hardening).

### Modules

Module commands go to the cluster like any other: Glide routes them by their first argument, which for JSON (`JSON.*`) and Bloom filters (`BF.*`) is the key, and for search (`FT.*`) is the index name. They're tested against `valkey-bundle` nodes, with search replies compared to a node's.
- **Vector and full-text search need the search module's coordinator mode** (`loadmodule libsearch.so --use-coordinator`). Without it, each node indexes only its own keys, and a query through the proxy silently searches one node's share of the data. Any cluster client sees the same.
- **`FT.INFO`** reports the counts of the node it reaches. Use `FT.INFO <index> PRIMARY` for the totals over all primaries.
- **`FT._LIST`** lists each index once. Glide asks every primary, and in coordinator mode they all know every index.
- **Search replies** have the module's shapes: Glide reshapes `FT.SEARCH`, `FT.AGGREGATE` and `FT.INFO` replies, and the proxy rebuilds them. Two details differ: simple strings in them arrive as bulk strings, and `FT.AGGREGATE`'s leading count is the number of rows returned (Glide drops the module's own count, which has been the same in our tests).
- **`FT.SEARCH ... WITHSORTKEYS` fails** with a conversion error from Glide. The search module ignores the option, so leave it out.
- **Search queries** (`FT.SEARCH`, `FT.AGGREGATE`, `FT.PROFILE`) aren't batched with other clients' commands, so a slow query delays only its own connection.

## Differences from a standalone server

### Keys in different slots

A cluster can only run a multi-key operation atomically when all its keys are in one hash slot. Use hash tags, such as `{user1}:name` and `{user1}:email`, to keep related keys together.
- **Transactions:** a transaction is atomic when all its keys share a slot. A cross-slot transaction is rejected with CROSSSLOT, like on a cluster node. With `proxy.transactions.cross-slot: pipeline`, its commands run in order without atomicity. With WATCH, a cross-slot transaction is always rejected.
- **Other multi-key commands:** commands the proxy doesn't split, such as RENAME, SMOVE, SUNIONSTORE, EVAL with keys on several nodes and MSETEX, fail with CROSSSLOT when their keys span slots.
- **Undeclared keys in scripts:** a script that touches keys it didn't declare only works if they happen to live on the node it runs on.

### Transactions

- **Rejected inside MULTI:** INFO, HELLO, SCAN, CLUSTER, PROXY, SELECT, AUTH, CLIENT and the subscribe commands. The transaction is then discarded. The proxy answers these itself, which EXEC on a node can't reproduce.
- **SELECT between WATCH and EXEC** makes EXEC abort, as if a watched key had changed. Clients retry an aborted transaction.
- **Out-of-memory errors** are reported at EXEC, not when a command is queued, since the proxy queues transactions itself.

### Connections

- **MONITOR** is rejected.
- **Rejected CLIENT subcommands:** PAUSE, UNPAUSE, TRACKING (client-side caching), CACHING, GETREDIR and TRACKINGINFO. IMPORT-SOURCE answers as a server with `import-mode` off.
- **CLIENT NO-EVICT and NO-TOUCH** are accepted but only recorded.
- **CLIENT LIST:** `cmd` is the last command's name, and the buffer, memory and network fields are 0.
- **PING** is answered by the proxy, so it never reports a node's state, such as BUSY while a script runs.

### Pub/Sub

- **Counts:** PUBLISH counts only subscribers connected through the proxy, not clients subscribed on the cluster directly. PUBSUB CHANNELS, NUMSUB and NUMPAT are answered by one node, which counts the proxy as a single subscriber.
- **Keyspace notifications** arrive only from the node the proxy's subscription is on, since each node publishes them only for its own keys.
- **Message order:** messages on one channel keep their order. Messages for different channels or patterns may arrive in a different order than they were published, since the proxy's subscriptions can be on different nodes.
- **Channel names** that aren't valid UTF-8 aren't passed on exactly (a Glide limitation). Payloads are binary-safe.
- **Slow subscribers:** one that falls 100,000 messages or 32 MB behind is disconnected, like Valkey's `client-output-buffer-limit pubsub`.

### SCAN and replies

- **SCAN cursors** are UUIDs rather than numbers; `0` still marks the end. A cursor can be used once (each call returns the next one), and only in the database it started in. The proxy keeps up to 10,000 cursors of unfinished scans across all clients, each for 5 minutes after its last use; beyond that, the oldest are dropped.
- **CONFIG GET** redacts the values of password settings, and **ACL LIST and ACL GETUSER** are refused by default, so the cluster's credentials aren't shown through the proxy.
- **Binary keys in SCAN:** keys whose bytes aren't valid UTF-8 come back with those bytes replaced, and MATCH patterns with such bytes match nothing (a Glide limitation). Other commands are binary-safe.
- **Set order:** set replies (SMEMBERS, SINTER, ..., and the sets inside COMMAND INFO and DOCS) have the right members, but not necessarily in the server's order.

### While the cluster changes

- **Pipelines during a slot migration:** a pipelined command that gets redirected can run after later commands of the same pipeline. Cluster-aware clients (Lettuce, Jedis) behave the same way.
- **A hung primary** isn't routed around until it answers again. See [OPERATIONS.md](OPERATIONS.md#cluster-changes).

### Authentication

- **No ACL rules:** proxy users can run every command.
- **AUTH and HELLO inside MULTI** are rejected.
