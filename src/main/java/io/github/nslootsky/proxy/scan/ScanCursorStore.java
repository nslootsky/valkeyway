package io.github.nslootsky.proxy.scan;

import glide.api.models.commands.scan.ClusterScanCursor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
