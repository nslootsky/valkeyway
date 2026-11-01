/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.blocking;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated Glide clients for blocking commands, one lease per running command.
 *
 * <p>Glide multiplexes all traffic to a node over one connection, and the server stops serving a
 * connection while a command on it is blocked. Running BLPOP and friends on a leased client keeps
 * that blocking off the shared client, so other proxy clients are unaffected.
 */
@Component
public class BlockingClientPool {

    private static final Logger log = LoggerFactory.getLogger(BlockingClientPool.class);

    private final LeasePool<Integer, GlideClusterClient> pool;
    private final Duration idleTimeout;
    private final ScheduledExecutorService evictor;

    public BlockingClientPool(ValkeywayProperties props, GlideClientCache glideClientCache, MeterRegistry registry) {
        ValkeywayProperties.BlockingPool config = props.blockingPool();
        this.pool = new LeasePool<>(config.maxSize(), db -> {
            GlideClusterClient client = glideClientCache.createClient(db);
            log.debug("Created blocking-pool client for db={}", db);
            return client;
        });
        this.idleTimeout = Duration.ofSeconds(config.idleTimeoutSeconds());
        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blocking-pool-evictor");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(1, config.idleTimeoutSeconds() / 2);
        evictor.scheduleWithFixedDelay(() -> pool.evictIdle(idleTimeout), period, period, TimeUnit.SECONDS);

        Gauge.builder("proxy.blocking.pool.in_use", pool, LeasePool::inUse)
                .description("Blocking commands currently holding a dedicated client")
                .register(registry);
        Gauge.builder("proxy.blocking.pool.clients", pool, LeasePool::live)
                .description("Open dedicated clients for blocking commands")
                .register(registry);
        FunctionCounter.builder("proxy.blocking.pool.exhausted_waits", pool, LeasePool::exhaustedWaits)
                .description("Blocking commands that had to wait because the pool was full")
                .register(registry);
        log.info("Blocking-command pool: max {} clients, idle timeout {}s", config.maxSize(), config.idleTimeoutSeconds());
    }

    /**
     * Leases a client for {@code db}, waiting up to {@code timeoutMillis} (0 = forever).
     *
     * @return the lease, or {@code null} if no client became free in time
     */
    public LeasePool<Integer, GlideClusterClient>.Lease acquire(int db, long timeoutMillis) throws InterruptedException {
        return pool.acquire(db, timeoutMillis);
    }

    @PreDestroy
    void close() {
        evictor.shutdownNow();
        pool.close();
    }
}
