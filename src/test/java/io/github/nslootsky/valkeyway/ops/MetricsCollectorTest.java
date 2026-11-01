/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.ops;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static double count(SimpleMeterRegistry registry, String type) {
        var counter = registry.find("proxy.commands.processed").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void commandsAreCountedByCaseFoldedName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector metrics = new MetricsCollector(registry);
        metrics.answered(b("GET"), Reply.OK, 1);
        metrics.answered(b("get"), Reply.OK, 1);
        metrics.answered(b("module.cmd"), Reply.error("ERR x"), 1);
        assertEquals(2, count(registry, "GET"));
        assertEquals(1, count(registry, "MODULE.CMD"));
        assertEquals(3, metrics.getTotalCommands());
        assertEquals(1, metrics.getTotalErrors());
    }

    /** Clients choose command names: made-up ones must not grow the heap or the metric labels. */
    @Test
    void madeUpNamesAreBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector metrics = new MetricsCollector(registry);
        metrics.answered(b("x".repeat(10_000)), Reply.OK, 1);
        metrics.answered(b("bad\u0000name"), Reply.OK, 1);
        for (int i = 0; i < 100_000; i++) {
            metrics.answered(b("cmd" + i), Reply.OK, 1);
        }
        long labels = registry.find("proxy.commands.processed").counters().size();
        assertTrue(labels <= 512 + 2, "labels: " + labels); // the per-command ones, OTHER, and the untagged total
        assertTrue(count(registry, "OTHER") >= 100_000 - 512 + 2);
        assertEquals(100_002, metrics.getTotalCommands());
    }
}
