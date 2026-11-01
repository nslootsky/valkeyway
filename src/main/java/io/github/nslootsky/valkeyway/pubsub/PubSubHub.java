/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.pubsub;

import glide.api.GlideClusterClient;
import glide.api.models.PubSubMessage;
import glide.api.models.configuration.ClusterSubscriptionConfiguration;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The proxy's subscriptions to the cluster, shared by every client. A channel (or pattern, or shard
 * channel) is subscribed in the cluster while at least one client is subscribed to it, and each
 * message is fanned out to those clients.
 *
 * <p>Two Glide clients carry the subscriptions: one for channels and patterns, one for shard
 * channels, since Glide reports messages and shard messages alike. [upstream:G14] Glide keeps them
 * subscribed across reconnects and slot moves.
 *
 * <p>Every change to the subscriptions runs on one thread, in order: Glide doesn't keep the order of
 * separately sent requests ([upstream:G4]), so an UNSUBSCRIBE and a later SUBSCRIBE of the same
 * channel could otherwise reach the cluster swapped. Message delivery reads the subscriber sets
 * without locking.
 */
public class PubSubHub implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PubSubHub.class);

    private final BackendClients backendClients;
    private final int timeoutMs;
    private final ExecutorService changes = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("pubsub-").factory());
    private final Map<Kind, Map<String, Set<PubSubSession>>> subscribers = new EnumMap<>(Kind.class);
    // Created on first use; only touched on the changes thread (and at close).
    private GlideClusterClient regular;
    private GlideClusterClient sharded;

    public PubSubHub(BackendClients backendClients, int timeoutMs) {
        this.backendClients = backendClients;
        this.timeoutMs = timeoutMs;
        for (Kind kind : Kind.values()) {
            subscribers.put(kind, new ConcurrentHashMap<>());
        }
    }

    /**
     * Subscribes {@code session} to {@code names}, subscribing the cluster to those no other client
     * has; returns once the cluster has confirmed them.
     */
    void subscribe(PubSubSession session, Kind kind, Collection<String> names) throws Exception {
        Future<?> done = changes.submit(() -> {
            Map<String, Set<PubSubSession>> map = subscribers.get(kind);
            Set<String> fresh = new LinkedHashSet<>();
            for (String name : names) {
                if (!map.containsKey(name)) {
                    fresh.add(name);
                }
            }
            if (!fresh.isEmpty()) {
                GlideClusterClient client = client(kind);
                switch (kind) {
                    case CHANNEL -> client.subscribe(fresh, timeoutMs).get();
                    case PATTERN -> client.psubscribe(fresh, timeoutMs).get();
                    case SHARD -> client.ssubscribe(fresh, timeoutMs).get();
                }
            }
            for (String name : names) {
                map.computeIfAbsent(name, _ -> ConcurrentHashMap.newKeySet()).add(session);
            }
            return null;
        });
        try {
            done.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** Drops {@code session}'s subscriptions to {@code names}, unsubscribing the cluster from unused ones. Doesn't wait. */
    void unsubscribe(PubSubSession session, Kind kind, Collection<String> names) {
        if (names.isEmpty()) {
            return;
        }
        changes.execute(() -> {
            Map<String, Set<PubSubSession>> map = subscribers.get(kind);
            Set<String> unused = new LinkedHashSet<>();
            for (String name : names) {
                Set<PubSubSession> sessions = map.get(name);
                if (sessions != null && sessions.remove(session) && sessions.isEmpty()) {
                    map.remove(name);
                    unused.add(name);
                }
            }
            if (unused.isEmpty()) {
                return;
            }
            try {
                GlideClusterClient client = client(kind);
                switch (kind) {
                    case CHANNEL -> client.unsubscribe(unused, timeoutMs).get();
                    case PATTERN -> client.punsubscribe(unused, timeoutMs).get();
                    case SHARD -> client.sunsubscribe(unused, timeoutMs).get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Messages for these may still arrive; no client is subscribed, so they're dropped.
                log.warn("Failed to unsubscribe the cluster from {} {}: {}", kind, unused, e.getMessage());
            }
        });
    }

    private GlideClusterClient client(Kind kind) throws Exception {
        if (kind == Kind.SHARD) {
            if (sharded == null) {
                sharded = backendClients.createSubscriber(subscriptions(true));
            }
            return sharded;
        }
        if (regular == null) {
            regular = backendClients.createSubscriber(subscriptions(false));
        }
        return regular;
    }

    private ClusterSubscriptionConfiguration subscriptions(boolean shard) {
        return ClusterSubscriptionConfiguration.builder()
                .callback((message, _) -> deliver(message, shard), null)
                .build();
    }

    /** Called on Glide's thread for each message. */
    private void deliver(PubSubMessage message, boolean shard) {
        byte[] channel = message.getChannel().getBytes();
        byte[] payload = message.getMessage().getBytes();
        if (shard) {
            fanOut(Kind.SHARD, channel, null, channel, payload);
        } else if (message.getPattern().isPresent()) {
            byte[] pattern = message.getPattern().get().getBytes();
            fanOut(Kind.PATTERN, pattern, pattern, channel, payload);
        } else {
            fanOut(Kind.CHANNEL, channel, null, channel, payload);
        }
    }

    private void fanOut(Kind kind, byte[] subscription, byte[] pattern, byte[] channel, byte[] payload) {
        Set<PubSubSession> sessions = subscribers.get(kind).get(new String(subscription, StandardCharsets.UTF_8));
        if (sessions != null) {
            for (PubSubSession session : sessions) {
                session.deliver(kind, pattern, channel, payload);
            }
        }
    }

    @Override
    public void close() {
        changes.shutdownNow();
        for (GlideClusterClient client : new GlideClusterClient[]{regular, sharded}) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.debug("Error closing a Pub/Sub client: {}", e.getMessage());
                }
            }
        }
    }
}
