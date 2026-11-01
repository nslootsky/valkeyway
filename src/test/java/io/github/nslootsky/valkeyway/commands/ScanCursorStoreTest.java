/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import glide.api.models.commands.scan.ClusterScanCursor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScanCursorStoreTest {

    /** Counts releases, like Glide's native cursor, which must be released exactly once. */
    static final class FakeCursor implements ClusterScanCursor {
        int releases;

        @Override
        public void releaseCursorHandle() {
            releases++;
        }

        @Override
        public boolean isFinished() {
            return false;
        }
    }

    @Test
    void aTakenCursorIsGoneAndNotReleased() {
        ScanCursorStore store = new ScanCursorStore();
        FakeCursor cursor = new FakeCursor();
        String id = store.put(cursor, 3);
        ScanCursorStore.Entry entry = store.take(id);
        assertSame(cursor, entry.cursor());
        assertEquals(3, entry.db());
        assertNull(store.take(id), "a cursor id is used once; the next call gets a new id");
        assertEquals(0, cursor.releases, "the taker owns it now");
    }

    @Test
    void evictedCursorsAreReleased() {
        ScanCursorStore store = new ScanCursorStore(2);
        List<FakeCursor> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            FakeCursor cursor = new FakeCursor();
            all.add(cursor);
            store.put(cursor, 0);
        }
        store.cleanUp();
        long released = all.stream().filter(c -> c.releases == 1).count();
        assertEquals(8, released, "everything over the limit is released once");
        assertTrue(all.stream().allMatch(c -> c.releases <= 1));
    }

    @Test
    void closeReleasesWhatIsLeft() {
        ScanCursorStore store = new ScanCursorStore();
        FakeCursor cursor = new FakeCursor();
        store.put(cursor, 0);
        store.close();
        assertEquals(1, cursor.releases);
    }
}
