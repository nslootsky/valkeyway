/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blocking commands run on dedicated backend clients, so they must not hold up other clients'
 * commands to the same node (Glide multiplexes one connection per node).
 */
class BlockingCommandsTest extends IntegrationTestBase {

    private static RawRespClient connect() throws Exception {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    /** Runs a command on its own connection in the background. */
    private static CompletableFuture<Object> callAsync(String... args) {
        return CompletableFuture.supplyAsync(() -> {
            try (RawRespClient c = connect()) {
                return c.call(args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static long millisFor(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    void blpopDoesNotStallOtherClientsOnTheSameSlot() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{bc:a}list");
            c.call("SET", "{bc:a}k", "v");

            CompletableFuture<Object> blocked = callAsync("BLPOP", "{bc:a}list", "3");
            Thread.sleep(300);

            Object[] reply = new Object[1];
            long ms = millisFor(() -> {
                try {
                    reply[0] = c.call("GET", "{bc:a}k");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals("v", reply[0], "GET must not fail behind the BLPOP");
            assertTrue(ms < 500, "GET took " + ms + "ms while another client was blocked");
            assertFalse(blocked.isDone(), "BLPOP should still be waiting");
            assertNull(blocked.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void blpopWakesUpWhenAnotherClientPushes() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{bc:b}list");
            CompletableFuture<Object> blocked = callAsync("BLPOP", "{bc:b}list", "5");
            Thread.sleep(300);

            c.call("RPUSH", "{bc:b}list", "hello");
            assertEquals(List.of("{bc:b}list", "hello"), blocked.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void blpopReturnsDataImmediatelyWhenAvailable() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{bc:c}list");
            c.call("RPUSH", "{bc:c}list", "x");
            assertEquals(List.of("{bc:c}list", "x"), c.call("BLPOP", "{bc:c}list", "0"));
        }
    }

    @Test
    void blpopTimesOutWithNil() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{bc:d}list");
            assertNull(c.call("BLPOP", "{bc:d}list", "0.2"));
        }
    }

    @Test
    void xreadBlockDoesNotStallOtherClients() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{bc:e}s");
            c.call("XADD", "{bc:e}s", "*", "f", "v");
            c.call("SET", "{bc:e}k", "v");

            CompletableFuture<Object> blocked = callAsync("XREAD", "BLOCK", "3000", "STREAMS", "{bc:e}s", "$");
            Thread.sleep(300);

            long start = System.nanoTime();
            assertEquals("v", c.call("GET", "{bc:e}k"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500);

            c.call("XADD", "{bc:e}s", "*", "f", "new");
            // RESP2 shape: [[stream, [[id, [field, value]]]]]
            List<?> streams = assertInstanceOf(List.class, blocked.get(2, TimeUnit.SECONDS));
            List<?> stream = (List<?>) streams.getFirst();
            assertEquals("{bc:e}s", stream.get(0));
            List<?> entry = (List<?>) ((List<?>) stream.get(1)).getFirst();
            assertEquals(List.of("f", "new"), entry.get(1));
        }
    }

    @Test
    void blockingCommandsUseTheSelectedDatabase() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("SELECT", "1");
            c.call("DEL", "{bc:f}list");
            c.call("RPUSH", "{bc:f}list", "in-db1");
            assertEquals(List.of("{bc:f}list", "in-db1"), c.call("BLPOP", "{bc:f}list", "1"));

            c.call("RPUSH", "{bc:f}list", "in-db1-again");
            c.call("SELECT", "0");
            c.call("DEL", "{bc:f}list");
            assertNull(c.call("BLPOP", "{bc:f}list", "0.2"), "db0 must not see db1's list");
        }
    }

    @Test
    void serverErrorsPassThrough() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("SET", "{bc:g}str", "v");
            Error error = assertInstanceOf(Error.class, c.call("BLPOP", "{bc:g}str", "1"));
            assertTrue(error.message().startsWith("WRONGTYPE "), error.message());
        }
    }
}
