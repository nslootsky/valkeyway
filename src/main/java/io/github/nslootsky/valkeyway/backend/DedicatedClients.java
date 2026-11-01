/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import glide.api.GlideClusterClient;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A pool of Glide clients that are each used by one proxy connection at a time, for work that
 * can't share Glide's multiplexed connection to a node: commands that block the connection, and
 * WATCH, whose state belongs to the connection. Idle clients are closed after a while.
 *
 * <p>Metrics, under {@code <metricPrefix>}: {@code .in_use}, {@code .clients} and
 * {@code .exhausted_waits}.
 */
public final class DedicatedClients implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DedicatedClients.class);

    private final LeasePool<Integer, GlideClusterClient> pool;
    private final ScheduledExecutorService evictor;

    public DedicatedClients(String name, int maxSize, long idleTimeoutSeconds, BackendClients backendClients,
                            MeterRegistry registry, String metricPrefix) {
        this.pool = new LeasePool<>(maxSize, db -> {
            GlideClusterClient client = backendClients.createClient(db);
            log.debug("Created {} client for db={}", name, db);
            return client;
        });
        Duration idleTimeout = Duration.ofSeconds(idleTimeoutSeconds);
        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name.replace(' ', '-') + "-evictor");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(1, idleTimeoutSeconds / 2);
        evictor.scheduleWithFixedDelay(() -> pool.evictIdle(idleTimeout), period, period, TimeUnit.SECONDS);

        Gauge.builder(metricPrefix + ".in_use", pool, LeasePool::inUse)
                .description("Dedicated " + name + " clients currently leased")
                .register(registry);
        Gauge.builder(metricPrefix + ".clients", pool, LeasePool::live)
                .description("Open dedicated " + name + " clients")
                .register(registry);
        FunctionCounter.builder(metricPrefix + ".exhausted_waits", pool, LeasePool::exhaustedWaits)
                .description("Leases of a " + name + " client that had to wait because the pool was full")
                .register(registry);
        log.info("{} pool: max {} clients, idle timeout {}s", name, maxSize, idleTimeoutSeconds);
    }

    /**
     * Leases a client for {@code db}, waiting up to {@code timeoutMillis} (0 = forever).
     *
     * @return the lease, or {@code null} if no client became free in time
     */
    public LeasePool<Integer, GlideClusterClient>.Lease acquire(int db, long timeoutMillis) throws InterruptedException {
        return pool.acquire(db, timeoutMillis);
    }

    @Override
    public void close() {
        evictor.shutdownNow();
        pool.close();
    }
}
