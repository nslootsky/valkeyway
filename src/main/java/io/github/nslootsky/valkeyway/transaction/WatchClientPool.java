/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.transaction;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import io.github.nslootsky.valkeyway.blocking.DedicatedClients;
import io.github.nslootsky.valkeyway.blocking.LeasePool;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Dedicated Glide clients for connections that use WATCH.
 *
 * <p>Watched keys belong to the server connection that sent WATCH, and EXEC (or UNWATCH) on that
 * connection clears them. The shared Glide client sends every proxy client's commands to a node over
 * one connection, so watches there would mix: one client's EXEC would drop another's watches, and one
 * client's watched key could abort another's transaction. A connection that sends WATCH therefore
 * leases its own client until EXEC, DISCARD, UNWATCH or disconnect. [upstream:G11]
 */
@Component
public class WatchClientPool {

    private final DedicatedClients clients;
    private final long acquireTimeoutMs;

    public WatchClientPool(ValkeywayProperties props, GlideClientCache glideClientCache, MeterRegistry registry) {
        ValkeywayProperties.WatchPool config = props.transactions().watchPool();
        this.clients = new DedicatedClients("watch", config.maxSize(), config.idleTimeoutSeconds(),
                glideClientCache, registry, "proxy.watch.pool");
        this.acquireTimeoutMs = config.acquireTimeoutMs();
    }

    /** A client for {@code db}, or {@code null} if none became free within the acquire timeout. */
    LeasePool<Integer, GlideClusterClient>.Lease acquire(int db) throws InterruptedException {
        return clients.acquire(db, acquireTimeoutMs);
    }

    @PreDestroy
    void close() {
        clients.close();
    }
}
