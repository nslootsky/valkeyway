/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.transaction;

import java.util.concurrent.atomic.AtomicBoolean;
import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.backend.LeasePool;
import io.github.nslootsky.valkeyway.backend.routing.ClusterSlots;

import java.util.HashSet;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One proxy connection's watched keys, on a dedicated backend client (see {@link WatchClientPool}).
 *
 * <p>When the proxy can't honor a watch (the client SELECTed another database before EXEC), the
 * session is marked broken and EXEC aborts as if a watched key had changed. That's a spurious but
 * safe abort: clients retry an aborted transaction, and the alternative would be running it
 * unprotected.
 */
public final class WatchSession implements AutoCloseable {

    private final LeasePool<Integer, GlideClusterClient>.Lease lease;
    private final int db;
    private final Set<Integer> slots = new HashSet<>();
    private byte[] firstKey;
    /** The distinct watched keys, for CLIENT LIST (WATCHing a key again doesn't add to it). */
    private final Set<ByteBuffer> keys = ConcurrentHashMap.newKeySet();
    private volatile boolean broken;
    private final AtomicBoolean released = new AtomicBoolean();

    WatchSession(LeasePool<Integer, GlideClusterClient>.Lease lease, int db) {
        this.lease = lease;
        this.db = db;
    }

    public GlideClusterClient client() {
        return lease.resource();
    }

    public int db() {
        return db;
    }

    /** Slots of the watched keys. */
    public Set<Integer> slots() {
        return slots;
    }

    /** One watched key, to give an otherwise empty transaction a key to be routed by. */
    public byte[] firstKey() {
        return firstKey;
    }

    /** How many distinct keys the connection watches, for CLIENT LIST. */
    public int keyCount() {
        return keys.size();
    }

    void watched(byte[][] watchedKeys) {
        for (byte[] key : watchedKeys) {
            keys.add(ByteBuffer.wrap(key));
            slots.add(ClusterSlots.slot(key));
            if (firstKey == null) {
                firstKey = key;
            }
        }
    }

    public boolean broken() {
        return broken;
    }

    public void markBroken() {
        broken = true;
    }

    /**
     * Gives the client back after EXEC ran on it. EXEC clears the watches, but if it failed, its
     * state is unknown and the client is closed instead.
     */
    public void releaseAfterExec(boolean execCompleted) {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        if (!execCompleted) {
            lease.discard();
        }
        lease.close();
    }

    /** Drops the watches (UNWATCH) and gives the client back; closes it if UNWATCH fails. Never blocks. */
    @Override
    public void close() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        lease.resource().customCommand(new GlideString[]{GlideString.of("UNWATCH")})
                .whenComplete((_, error) -> {
                    if (error != null) {
                        lease.discard();
                    }
                    lease.close();
                });
    }
}
