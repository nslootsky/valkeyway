/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LeasePoolTest {

    static final class Res implements AutoCloseable {
        final String key;
        boolean closed;

        Res(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final List<Res> created = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();

    private LeasePool<String, Res> pool(int max) {
        return new LeasePool<>(max, key -> {
            Res r = new Res(key);
            created.add(r);
            return r;
        }, clock::get);
    }

    @Test
    void reusesIdleResourceForSameKey() throws Exception {
        var pool = pool(2);
        var first = pool.acquire("db0", 0);
        Res r = first.resource();
        first.close();

        var second = pool.acquire("db0", 0);
        assertSame(r, second.resource());
        assertEquals(1, pool.live());
    }

    @Test
    void waitsWhenFullAndTimesOut() throws Exception {
        var pool = pool(1);
        var held = pool.acquire("db0", 0);

        long start = System.nanoTime();
        assertNull(pool.acquire("db0", 100));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 90);
        assertEquals(1, pool.exhaustedWaits());
        held.close();
    }

    @Test
    void waiterGetsResourceWhenOneIsReturned() throws Exception {
        var pool = pool(1);
        var held = pool.acquire("db0", 0);

        var waiter = CompletableFuture.supplyAsync(() -> {
            try {
                return pool.acquire("db0", 0);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(50);
        assertFalse(waiter.isDone());

        Res r = held.resource();
        held.close();
        assertSame(r, waiter.get(2, TimeUnit.SECONDS).resource());
    }

    @Test
    void discardedResourceIsClosedNotReused() throws Exception {
        var pool = pool(2);
        var lease = pool.acquire("db0", 0);
        Res r = lease.resource();
        lease.discard();
        lease.close();

        assertTrue(r.closed);
        assertEquals(0, pool.live());
        assertNotSame(r, pool.acquire("db0", 0).resource());
    }

    @Test
    void closingTwiceReleasesOnce() throws Exception {
        var pool = pool(1);
        var lease = pool.acquire("db0", 0);
        lease.close();
        lease.close();
        assertEquals(0, pool.inUse());
        assertNotNull(pool.acquire("db0", 0));
        assertNull(pool.acquire("db0", 10));
    }

    @Test
    void otherKeyReclaimsIdleResourceToStayUnderCap() throws Exception {
        var pool = pool(2);
        var a = pool.acquire("db0", 0);
        var b = pool.acquire("db0", 0);
        a.close();
        b.close();
        assertEquals(2, pool.live());

        var other = pool.acquire("db1", 0);
        assertEquals("db1", other.resource().key);
        assertEquals(2, pool.live(), "never more than maxSize resources open");
        assertEquals(1, created.stream().filter(r -> r.key.equals("db0") && r.closed).count(),
                "exactly one idle db0 resource closed to make room");
    }

    @Test
    void evictsOnlyResourcesIdleLongerThanTimeout() throws Exception {
        var pool = pool(3);
        var old = pool.acquire("db0", 0);
        var fresh = pool.acquire("db0", 0);
        Res oldRes = old.resource();
        Res freshRes = fresh.resource();

        clock.set(0);
        old.close();
        clock.set(Duration.ofSeconds(50).toNanos());
        fresh.close();

        clock.set(Duration.ofSeconds(70).toNanos());
        pool.evictIdle(Duration.ofSeconds(60));

        assertTrue(oldRes.closed);
        assertFalse(freshRes.closed);
        assertEquals(1, pool.live());
    }

    @Test
    void closeClosesIdleAndLateReturns() throws Exception {
        var pool = pool(2);
        var idle = pool.acquire("db0", 0);
        var busy = pool.acquire("db0", 0);
        Res idleRes = idle.resource();
        Res busyRes = busy.resource();
        idle.close();

        pool.close();
        assertTrue(idleRes.closed);
        assertFalse(busyRes.closed);

        busy.close();
        assertTrue(busyRes.closed);
        assertThrows(IllegalStateException.class, () -> pool.acquire("db0", 0));
    }

    @Test
    void factoryFailureReleasesPermit() throws Exception {
        var pool = new LeasePool<String, Res>(1, key -> {
            throw new RuntimeException("cluster down");
        });
        assertThrows(RuntimeException.class, () -> pool.acquire("db0", 0));
        assertEquals(0, pool.inUse());
    }
}
