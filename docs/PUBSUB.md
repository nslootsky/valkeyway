# Pub/Sub Support Plan for ValkeyWay

## Overview

This document outlines the plan to add Redis/Valkey pub/sub support (SUBSCRIBE, PUBLISH, PSUBSCRIBE) to ValkeyWay.

**Status**: Planned — not yet implemented.

## Feasibility Summary

Pub/sub is **feasible but requires significant architecture changes**. Not compatible with current request-response design without adding a dedicated pub/sub subsystem.

- GlideClusterClient supports SUBSCRIBE/PSUBSCRIBE via callback mode and dynamic subscriptions (v2.3+)
- resp-server framework supports pushing unsolicited messages via `Session.publish()`
- Requires new components: subscription manager, dedicated subscriber clients, message routing

## Problem Statement

SUBSCRIBE fundamentally changes connection semantics from request-response to streaming:

```
CLIENT → SUBSCRIBE channel1 → SERVER blocks connection, streams messages indefinitely
```

Current ValkeyWay architecture handles each command independently via `RespCommand.execute(Request)` returning a single `RedisToken`. This model cannot express the continuous message push required after SUBSCRIBE.

## Architecture Requirements

### 1. SubscribeManager Service

Thread-safe service tracking subscription relationships:

- Map: `Session → Set<Subscription>` (channels/patterns per session)
- Map: `Channel/Pattern → Set<Session>` (for message fanout)
- Methods: `subscribe(session, channels)`, `unsubscribe(session, channels)`, `publish(channel, message)`
- Cleanup on session disconnect via `SessionListener`

### 2. Dedicated Subscriber GlideClusterClient(s)

Separate from command-handling clients in `GlideClientCache`:

- Configured with subscription callbacks (callback mode is mutually exclusive with polling/command mode)
- Callback wired to SubscribeManager.publish()
- One per DB index, or one shared across all DBs
- Uses dynamic subscribe APIs (`client.subscribe(channels)`) for runtime subscription changes

### 3. SubscribeCommand Handler

Intercepts pub/sub commands:

- SUBSCRIBE/PSUBSCRIBE: register session in SubscribeManager, mark as subscribed-mode, return initial OK response
- UNSUBSCRIBE/PUNSUBSCRIBE: unregister channels/patterns, clear subscribed-mode if no remaining subscriptions
- Reject non-pub/sub commands when session is in subscribed-mode with error: `-ERR only (P)SUBSCRIBE / (P)UNSUBSCRIBE / QUIT allowed in this context`

### 4. PUBLISH Handling Modification

In CatchAllCommand or dedicated handler:

- Detect PUBLISH command
- Route with `AllNodes` instead of default slot-based routing to broadcast across cluster
- Return sum of subscriber counts across all nodes (or single node count — needs verification)

### 5. SessionState Extensions

Track per-session state:

- `isInSubscribeMode` flag
- Reject non-pub/sub commands when in subscribe mode

### 6. PubSubMsg → RedisToken Conversion

Convert Glide's PubSubMessage format to RESP arrays matching Redis wire protocol:

```
["message", "channel", "payload"]           // for SUBSCRIBE matches
["pmessage", "pattern", "channel", "payload"] // for PSUBSCRIBE matches
["subscribe", "channel", (integer) count]    // initial subscription confirmation
["unsubscribe", "channel", (integer) count]  // unsubscription confirmation
```

## Cluster Pub/Sub Semantics Options

| Approach | Behavior | Pros | Cons |
|----------|----------|------|------|
| **Broadcast to all nodes** | PUBLISH sent to every cluster node via `AllNodes` route | Simple, predictable — behaves like standalone Redis | Inefficient (O(N) network calls); return value is sum across nodes |
| **Node-local (native)** | PUBLISH reaches only subscribers on same node | Matches Redis cluster behavior exactly | Confusing for clients expecting global pub/sub behind unified proxy |
| **Sharded pub/sub** | Uses Valkey 7.0+ SSUBSCRIBE/SPUBLISH; consistent hashing of channels | Efficient, scales well | Requires Valkey 7.0+; different semantics than standard SUBSCRIBE |

**Recommendation**: Broadcast PUBLISH to all nodes is the simplest viable approach for a proxy presenting a unified view. The inefficiency is acceptable for most workloads and matches what clients expect from a non-cluster endpoint.

## Message Flow Diagram

```
[Client A] --SUBSCRIBE chat*--> [SubscribeCommand handler] --> [SubscribeManager.register()]
                                                                        ↓
[Client B] --PUBLISH chat/1 hi--> [CatchAllCommand with AllNodes route] --> [Backend Node]
                                                                        ↓
[Glide callback on subscriber client] --> [SubscribeManager.publish(channel, msg)]
                                                                        ↓
[Session.publish() for Client A] --> ["pmessage", "chat*", "chat/1", "hi"]
```

## Implementation Estimate

- ~500-700 new lines across 4-5 new files
- Modifications to existing CatchAllCommand and ValkeywayApplication
- New integration tests covering subscribe/unsubscribe, message delivery, pattern matching, cluster broadcast

### Files needed:

1. `SubscribeManager.java` (~200-300 lines)
2. `SubscriberGlideClientPool.java` or similar (~100-150 lines)
3. `SubscribeCommand.java` (~80-120 lines) — handles SUBSCRIBE/PSUBSCRIBE/UNSUBSCRIBE/PUNSUBSCRIBE
4. PubSubMsg → RedisToken conversion utility (~50-80 lines)

## Alternative: Minimal Support

Reject SUBSCRIBE/PSUBSCRIBE with `-ERR not supported`, only proxy PUBLISH as a regular proxied command. This gives clients publish capability without subscription routing complexity. Could be implemented as a stepping stone toward full support.
