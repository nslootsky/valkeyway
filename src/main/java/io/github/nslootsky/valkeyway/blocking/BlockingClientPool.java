/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Dedicated Glide clients for blocking commands, one lease per running command.
 *
 * <p>Glide multiplexes all traffic to a node over one connection, and the server stops serving a
 * connection while a command on it is blocked. Running BLPOP and friends on a leased client keeps
 * that blocking off the shared client, so other proxy clients are unaffected.
 */
@Component
public class BlockingClientPool {

    private final DedicatedClients clients;

    public BlockingClientPool(ValkeywayProperties props, GlideClientCache glideClientCache, MeterRegistry registry) {
        ValkeywayProperties.BlockingPool config = props.blockingPool();
        this.clients = new DedicatedClients("blocking-command", config.maxSize(), config.idleTimeoutSeconds(),
                glideClientCache, registry, "proxy.blocking.pool");
    }

    /**
     * Leases a client for {@code db}, waiting up to {@code timeoutMillis} (0 = forever).
     *
     * @return the lease, or {@code null} if no client became free in time
     */
    public LeasePool<Integer, GlideClusterClient>.Lease acquire(int db, long timeoutMillis) throws InterruptedException {
        return clients.acquire(db, timeoutMillis);
    }

    @PreDestroy
    void close() {
        clients.close();
    }
}
