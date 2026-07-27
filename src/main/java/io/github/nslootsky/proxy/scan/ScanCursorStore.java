package io.github.nslootsky.proxy.scan;

import glide.api.models.commands.scan.ClusterScanCursor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared store for SCAN cursors, keyed by UUID.
 * Persists cursors across connections for cluster-wide SCAN iteration.
 */
public class ScanCursorStore {

    private final Map<String, ClusterScanCursor> cursors = new ConcurrentHashMap<>();

    public String createCursor(ClusterScanCursor cursor) {
        String id = UUID.randomUUID().toString();
        cursors.put(id, cursor);
        return id;
    }

    public ClusterScanCursor getCursor(String id) {
        return cursors.get(id);
    }

    public void removeCursor(String id) {
        cursors.remove(id);
    }
}
