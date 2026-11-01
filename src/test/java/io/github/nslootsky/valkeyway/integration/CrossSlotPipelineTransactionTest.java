/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code proxy.transactions.cross-slot=pipeline}: cross-slot transactions run, without atomicity. */
class CrossSlotPipelineTransactionTest extends IntegrationTestBase {

    private static final int PORT = 6392;
    private static final Status OK = new Status("OK");

    @DynamicPropertySource
    static void pipelineMode(DynamicPropertyRegistry registry) {
        registry.add("proxy.port", () -> PORT);
        registry.add("proxy.transactions.cross-slot", () -> "pipeline");
    }

    @Test
    void crossSlotTransactionsRunAsAPipeline() throws Exception {
        try (RawRespClient c = new RawRespClient("127.0.0.1", PORT)) {
            c.call("DEL", "xsp:a", "xsp:b");
            c.call("MULTI");
            c.call("SET", "xsp:a", "1");
            c.call("SET", "xsp:b", "2");
            assertEquals(List.of(OK, OK), c.call("EXEC"));
            assertEquals(List.of("1", "2"), c.call("MGET", "xsp:a", "xsp:b"));
        }
    }

    @Test
    void withWatchACrossSlotTransactionIsStillRejected() throws Exception {
        try (RawRespClient c = new RawRespClient("127.0.0.1", PORT)) {
            c.call("SET", "xsp:w", "1");
            c.call("WATCH", "xsp:w");
            c.call("MULTI");
            c.call("SET", "xsp:a", "3");
            c.call("SET", "xsp:b", "4");
            Object reply = c.call("EXEC");
            assertTrue(reply instanceof Error(String message) && message.startsWith("CROSSSLOT "), "got " + reply);
        }
    }
}
