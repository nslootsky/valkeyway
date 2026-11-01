/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.ConnectionKey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One client's subscriptions. Closed (all of them dropped) when the client disconnects or RESETs.
 *
 * <p>Changed by the client's (P|S)(UN)SUBSCRIBE commands, read by message delivery on Glide's thread.
 */
public final class PubSubSession implements AutoCloseable {

    static final ConnectionKey<PubSubSession> KEY = ConnectionKey.of("pubsub");

    private final Connection connection;
    private final PubSubHub hub;
    private final Map<Kind, Set<String>> names = new EnumMap<>(Kind.class);

    private PubSubSession(Connection connection, PubSubHub hub) {
        this.connection = connection;
        this.hub = hub;
        for (Kind kind : Kind.values()) {
            names.put(kind, Collections.synchronizedSet(new LinkedHashSet<>()));
        }
    }

    static PubSubSession of(Connection connection, PubSubHub hub) {
        PubSubSession session = connection.get(KEY);
        if (session == null) {
            session = new PubSubSession(connection, hub);
            connection.set(KEY, session);
        }
        return session;
    }

    /** The client's subscription counts (channels, patterns, shard channels), all 0 without a session. */
    public static int[] counts(Connection connection) {
        PubSubSession session = connection.get(KEY);
        return session == null ? new int[3]
                : new int[]{session.size(Kind.CHANNEL), session.size(Kind.PATTERN), session.size(Kind.SHARD)};
    }

    /** Drops all of the client's subscriptions (RESET). */
    public static void end(Connection connection) {
        PubSubSession session = connection.remove(KEY);
        if (session != null) {
            session.close();
        }
        connection.subscribed(false);
    }

    int size(Kind kind) {
        return names.get(kind).size();
    }

    boolean has(Kind kind, String name) {
        return names.get(kind).contains(name);
    }

    void add(Kind kind, String name) {
        names.get(kind).add(name);
    }

    boolean remove(Kind kind, String name) {
        return names.get(kind).remove(name);
    }

    List<String> all(Kind kind) {
        Set<String> set = names.get(kind);
        synchronized (set) {
            return new ArrayList<>(set);
        }
    }

    /** The count Valkey reports in a confirmation: channels plus patterns, or shard channels. */
    int count(Kind kind) {
        return kind == Kind.SHARD ? size(Kind.SHARD) : size(Kind.CHANNEL) + size(Kind.PATTERN);
    }

    boolean empty() {
        return size(Kind.CHANNEL) + size(Kind.PATTERN) + size(Kind.SHARD) == 0;
    }

    /** Sends a message, unless the client unsubscribed in the meantime. */
    void deliver(Kind kind, byte[] pattern, byte[] channel, byte[] payload) {
        String name = new String(pattern != null ? pattern : channel, StandardCharsets.UTF_8);
        if (!has(kind, name)) {
            return;
        }
        connection.push(new Reply.Push(pattern == null
                ? List.of(kind.message, new Reply.Bulk(channel), new Reply.Bulk(payload))
                : List.of(kind.message, new Reply.Bulk(pattern), new Reply.Bulk(channel), new Reply.Bulk(payload))));
    }

    @Override
    public void close() {
        for (Kind kind : Kind.values()) {
            List<String> subscribed = all(kind);
            names.get(kind).clear();
            hub.unsubscribe(this, kind, subscribed);
        }
    }
}
