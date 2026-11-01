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
    Glide glide
) {
    public ValkeywayProperties {
        if (host == null || host.isBlank()) host = "0.0.0.0";
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (clusterNodes == null || clusterNodes.isEmpty()) throw new IllegalArgumentException("clusterNodes required");
        connectTimeoutMs = Math.max(connectTimeoutMs, 500);
        if (blockingPool == null) blockingPool = new BlockingPool(0, 0);
        if (clients == null) clients = new Clients(0, 0, 0);
        if (glide == null) glide = new Glide(0, null);
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
     */
    public record Glide(int inflightRequestsLimit, Boolean coalesceRequests) {
        public Glide {
            if (inflightRequestsLimit <= 0) inflightRequestsLimit = 50_000;
            if (coalesceRequests == null) coalesceRequests = true;
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
}
