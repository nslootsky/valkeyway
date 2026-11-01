/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A capped pool of exclusive resources, partitioned by key (e.g. database index).
 *
 * <p>At most {@code maxSize} leases are out at once, across all keys; further callers wait. Idle
 * resources are reused per key, closed after {@code maxIdle}, and closed early when another key
 * needs room under the cap.
 */
public class LeasePool<K, T extends AutoCloseable> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LeasePool.class);

    private record Idle<T>(T resource, long since) {}

    private final int maxSize;
    private final Function<K, T> factory;
    private final LongSupplier clock;
    private final Semaphore permits;
    private final Map<K, Deque<Idle<T>>> idle = new ConcurrentHashMap<>();
    private final AtomicInteger live = new AtomicInteger();
    private final AtomicLong exhaustedWaits = new AtomicLong();
    private final AtomicBoolean exhausted = new AtomicBoolean();
    private volatile boolean closed;

    public LeasePool(int maxSize, Function<K, T> factory) {
        this(maxSize, factory, System::nanoTime);
    }

    LeasePool(int maxSize, Function<K, T> factory, LongSupplier nanoClock) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1");
        }
        this.maxSize = maxSize;
        this.factory = factory;
        this.clock = nanoClock;
        this.permits = new Semaphore(maxSize, true);
    }

    /** An exclusive hold on a resource. Close it to give the resource back. */
    public final class Lease implements AutoCloseable {
        private final K key;
        private final T resource;
        private boolean discard;
        private boolean released;

        private Lease(K key, T resource) {
            this.key = key;
            this.resource = resource;
        }

        public T resource() {
            return resource;
        }

        /** Close the resource instead of reusing it (e.g. it may still have a request in flight). */
        public void discard() {
            discard = true;
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            if (discard || closed) {
                destroy(resource);
            } else {
                idle.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>())
                        .push(new Idle<>(resource, clock.getAsLong()));
            }
            permits.release();
            if (permits.availablePermits() > 0) {
                exhausted.set(false);
            }
        }
    }

    /**
     * Leases a resource for {@code key}, waiting up to {@code timeoutMillis} for one to free up
     * ({@code 0} = wait forever).
     *
     * @return the lease, or {@code null} if none became free in time
     */
    public Lease acquire(K key, long timeoutMillis) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("pool closed");
        }
        if (!permits.tryAcquire()) {
            exhaustedWaits.incrementAndGet();
            if (exhausted.compareAndSet(false, true)) {
                log.warn("Blocking-command pool exhausted ({} in use); new blocking commands will wait", maxSize);
            }
            boolean acquired = timeoutMillis == 0
                    ? acquireForever()
                    : permits.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return null;
            }
        }
        try {
            return new Lease(key, take(key));
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private boolean acquireForever() throws InterruptedException {
        permits.acquire();
        return true;
    }

    private T take(K key) {
        Deque<Idle<T>> mine = idle.get(key);
        Idle<T> reused = mine == null ? null : mine.poll();
        if (reused != null) {
            return reused.resource();
        }
        // We hold a permit, so leases < maxSize; make room by closing an idle resource of another key.
        while (live.get() >= maxSize && closeOneIdle()) {
            // keep going until under the cap or nothing idle remains
        }
        T created = factory.apply(key);
        live.incrementAndGet();
        return created;
    }

    private boolean closeOneIdle() {
        for (Deque<Idle<T>> deque : idle.values()) {
            Idle<T> victim = deque.pollLast();
            if (victim != null) {
                destroy(victim.resource());
                return true;
            }
        }
        return false;
    }

    /** Closes resources that have been idle longer than {@code maxIdle}. */
    public void evictIdle(Duration maxIdle) {
        long cutoff = clock.getAsLong() - maxIdle.toNanos();
        for (Deque<Idle<T>> deque : idle.values()) {
            // Most recently returned are at the head; the oldest are at the tail.
            Idle<T> oldest;
            while ((oldest = deque.peekLast()) != null && oldest.since() - cutoff <= 0) {
                if (deque.removeLastOccurrence(oldest)) {
                    destroy(oldest.resource());
                }
            }
        }
    }

    private void destroy(T resource) {
        live.decrementAndGet();
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("Error closing pooled resource", e);
        }
    }

    public int maxSize() {
        return maxSize;
    }

    public int inUse() {
        return maxSize - permits.availablePermits();
    }

    public int live() {
        return live.get();
    }

    public long exhaustedWaits() {
        return exhaustedWaits.get();
    }

    @Override
    public void close() {
        closed = true;
        for (Deque<Idle<T>> deque : idle.values()) {
            Idle<T> entry;
            while ((entry = deque.poll()) != null) {
                destroy(entry.resource());
            }
        }
    }
}
