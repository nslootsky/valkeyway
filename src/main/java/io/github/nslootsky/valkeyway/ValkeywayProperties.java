/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for the proxy.
 * Prefix: proxy.*
 */
@ConfigurationProperties(prefix = "proxy")
public record ValkeywayProperties(
    String host,
    int port,
    List<String> clusterNodes,
    long connectTimeoutMs,
    BlockingPool blockingPool,
    Clients clients,
    Glide glide,
    Transactions transactions,
    NodeAdmin nodeAdminCommands
) {
    public ValkeywayProperties {
        if (host == null || host.isBlank()) host = "0.0.0.0";
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (clusterNodes == null || clusterNodes.isEmpty()) throw new IllegalArgumentException("clusterNodes required");
        connectTimeoutMs = Math.max(connectTimeoutMs, 500);
        if (blockingPool == null) blockingPool = new BlockingPool(0, 0);
        if (clients == null) clients = new Clients(0, 0, 0);
        if (glide == null) glide = new Glide(0, null, null, 0);
        if (transactions == null) transactions = new Transactions(null, null);
        if (nodeAdminCommands == null) nodeAdminCommands = NodeAdmin.REJECT;
    }

    /**
     * Backend (Glide) client settings. Prefix: proxy.glide.*
     *
     * @param inflightRequestsLimit requests one Glide client may have outstanding (default 50000).
     *        Glide's own default is 1000, and one Glide client per database is shared by every proxy
     *        connection, so with the default more than 1000 busy clients get "Client reached maximum
     *        inflight requests" errors. Memory stays bounded by the proxy's per-connection backpressure.
     * @param coalesceRequests send single commands that arrive from different connections in the same
     *        network-thread iteration to Glide as one batch (default true). Glide's per-request cost is
     *        much higher than its per-command cost inside a batch.
     * @param refreshTopologyFromSeeds discover topology changes by asking the configured
     *        {@code cluster-nodes}, resolved again each time, rather than the nodes already known
     *        (default true). With a DNS name as the seed (e.g. a configuration endpoint), re-pointing
     *        the name at another cluster moves the proxy there at the next topology check. If the seeds
     *        can't be reached, Glide falls back to the nodes it knows.
     * @param topologyCheckSeconds how often Glide checks for topology changes, which is also how
     *        quickly a re-pointed seed name is followed; 0 = Glide's default (60).
     */
    // [upstream:G8] [upstream:G9]
    public record Glide(int inflightRequestsLimit, Boolean coalesceRequests, Boolean refreshTopologyFromSeeds,
                        int topologyCheckSeconds) {
        public Glide {
            if (inflightRequestsLimit <= 0) inflightRequestsLimit = 50_000;
            if (coalesceRequests == null) coalesceRequests = true;
            if (refreshTopologyFromSeeds == null) refreshTopologyFromSeeds = true;
            if (topologyCheckSeconds < 0) topologyCheckSeconds = 0;
        }
    }

    /**
     * Client connection handling. Prefix: proxy.clients.*
     *
     * @param maxClients     connections accepted at once; more get an error (default 10000, like Valkey)
     * @param timeoutSeconds close a client idle this long; 0 = never (default, like Valkey's timeout 0)
     * @param ioThreads      network event-loop threads (default 2). Fewer threads put more requests
     *                       into each backend batch, which matters more than parallelism here: on a
     *                       24-core machine, 2 threads served 250-280k unpipelined requests/s from 200
     *                       clients versus 160-176k with 24. One thread was faster still for small
     *                       values but ran at ~90% CPU with 10 KB values; raise it for large values.
     */
    public record Clients(int maxClients, long timeoutSeconds, int ioThreads) {
        public Clients {
            if (maxClients <= 0) maxClients = 10_000;
            if (timeoutSeconds < 0) timeoutSeconds = 0;
            if (ioThreads <= 0) ioThreads = Math.min(2, Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * Dedicated backend clients for blocking commands (BLPOP, XREAD BLOCK, WAIT, ...).
     * Prefix: proxy.blocking-pool.*
     *
     * @param maxSize            most blocking commands running at once; further ones wait (default 64)
     * @param idleTimeoutSeconds close a pooled client after this long unused (default 60)
     */
    public record BlockingPool(int maxSize, long idleTimeoutSeconds) {
        public BlockingPool {
            if (maxSize <= 0) maxSize = 64;
            if (idleTimeoutSeconds <= 0) idleTimeoutSeconds = 60;
        }
    }

    /**
     * MULTI/EXEC and WATCH. Prefix: proxy.transactions.*
     *
     * @param crossSlot what EXEC does when the transaction's keys span hash slots, which a cluster
     *        can't run atomically: {@code reject} (default) answers CROSSSLOT, as a cluster node does;
     *        {@code pipeline} runs the commands in order without atomicity. With WATCH, a cross-slot
     *        transaction is always rejected.
     * @param watchPool dedicated backend clients for connections that use WATCH, held from WATCH
     *        until EXEC, DISCARD, UNWATCH or disconnect (watch state belongs to a connection)
     */
    public record Transactions(CrossSlot crossSlot, WatchPool watchPool) {
        public Transactions {
            if (crossSlot == null) crossSlot = CrossSlot.REJECT;
            if (watchPool == null) watchPool = new WatchPool(0, 0, 0);
        }
    }

    public enum CrossSlot { REJECT, PIPELINE }

    /**
     * @param maxSize            connections that can have keys watched at once; more wait (default 64)
     * @param idleTimeoutSeconds close a pooled client after this long unused (default 60)
     * @param acquireTimeoutMs   how long WATCH waits for a free client before failing (default 2000)
     */
    public record WatchPool(int maxSize, long idleTimeoutSeconds, long acquireTimeoutMs) {
        public WatchPool {
            if (maxSize <= 0) maxSize = 64;
            if (idleTimeoutSeconds <= 0) idleTimeoutSeconds = 60;
            if (acquireTimeoutMs <= 0) acquireTimeoutMs = 2000;
        }
    }

    /**
     * {@code proxy.node-admin-commands}: commands that administer a single node (SHUTDOWN, REPLICAOF,
     * FAILOVER, DEBUG, SAVE, CONFIG SET, ACL SETUSER, CLUSTER MEET, ...). Through the proxy they reach
     * whichever node Glide picks. {@code reject} (default) refuses them; {@code forward} sends them on.
     */
    public enum NodeAdmin { REJECT, FORWARD }
}
