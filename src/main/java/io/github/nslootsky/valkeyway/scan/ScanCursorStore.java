package io.github.nslootsky.valkeyway.scan;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import glide.api.models.commands.scan.ClusterScanCursor;
import jakarta.annotation.PreDestroy;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Shared store for SCAN cursors, keyed by UUID.
 * Persists cursors across connections for cluster-wide SCAN iteration.
 * Cursors expire after 5 minutes of inactivity; max 1000 cursors.
 */
public class ScanCursorStore {

    private final Cache<String, ClusterScanCursor> cursors;

    public ScanCursorStore() {
        this.cursors = Caffeine.newBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maximumSize(1000)
                .scheduler(Scheduler.systemScheduler())
                .build();
    }

    public String createCursor(ClusterScanCursor cursor) {
        String id = UUID.randomUUID().toString();
        cursors.put(id, cursor);
        return id;
    }

    public ClusterScanCursor getCursor(String id) {
        return cursors.getIfPresent(id);
    }

    public void removeCursor(String id) {
        cursors.invalidate(id);
    }

    @PreDestroy
    public void close() {
        cursors.invalidateAll();
    }
}
