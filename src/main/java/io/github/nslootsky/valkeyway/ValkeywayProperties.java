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
    BlockingPool blockingPool
) {
    public ValkeywayProperties {
        if (host == null || host.isBlank()) host = "0.0.0.0";
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (clusterNodes == null || clusterNodes.isEmpty()) throw new IllegalArgumentException("clusterNodes required");
        connectTimeoutMs = Math.max(connectTimeoutMs, 500);
        if (blockingPool == null) blockingPool = new BlockingPool(0, 0);
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
