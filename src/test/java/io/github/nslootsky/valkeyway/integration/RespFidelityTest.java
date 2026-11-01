/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that replies look the way a standalone Valkey server's RESP2 replies do,
 * as seen by a plain client on the wire.
 */
class RespFidelityTest extends IntegrationTestBase {

    private static final String HOST = "127.0.0.1";

    private static RawRespClient connect() throws Exception {
        return new RawRespClient(HOST, RESP_PORT);
    }

    private static void assertError(String expectedPrefix, Object reply) {
        Error error = assertInstanceOf(Error.class, reply, () -> "expected error reply but got " + reply);
        assertTrue(error.message().startsWith(expectedPrefix), () -> "unexpected error: " + error.message());
    }

    @Test
    void doubleRepliesAreDecimalBulkStrings() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "fid:z");
            c.call("ZADD", "fid:z", "1.5", "a", "2.25", "b");

            assertEquals("1.5", c.call("ZSCORE", "fid:z", "a"));
            assertEquals(List.of("a", "1.5", "b", "2.25"), c.call("ZRANGE", "fid:z", "0", "-1", "WITHSCORES"));
            assertEquals("1.6", c.call("ZINCRBY", "fid:z", "0.1", "a"));
        }
    }

    @Test
    void execReportsEachCommandsErrorInsideTheArray() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{fid:u}x");
            assertEquals(new Status("OK"), c.call("MULTI"));
            c.call("SET", "{fid:u}x", "1");
            c.call("INCR", "{fid:u}x");
            c.call("LPUSH", "{fid:u}x", "v");
            c.call("GET", "{fid:u}x");

            List<?> results = assertInstanceOf(List.class, c.call("EXEC"));
            assertEquals(4, results.size());
            assertEquals(new Status("OK"), results.get(0));
            assertEquals(2L, results.get(1));
            assertError("WRONGTYPE ", results.get(2));
            assertEquals("2", results.get(3));
        }
    }

    @Test
    void hashTaggedTransactionRunsAtomically() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{fid:t}a", "{fid:t}b");
            c.call("MULTI");
            c.call("SET", "{fid:t}a", "1");
            // Wrong arity: a real MULTI on the node rejects it at queue time and discards the
            // whole transaction. A non-atomic pipeline would have applied the first SET.
            c.call("SET", "{fid:t}b");

            // Glide surfaces the queued command's error rather than EXECABORT; either way the
            // transaction must be discarded.
            assertInstanceOf(Error.class, c.call("EXEC"));
            assertNull(c.call("GET", "{fid:t}a"));
        }
    }

    @Test
    void crossSlotMsetInsideTransactionIsDetected() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("MULTI");
            c.call("MSET", "fid:k1", "1", "fid:k2", "2", "fid:k3", "3");

            assertEquals(List.of(new Status("OK")), c.call("EXEC"));
            assertEquals(List.of("1", "2", "3"), c.call("MGET", "fid:k1", "fid:k2", "fid:k3"));
        }
    }

    @Test
    void evalInsideTransactionResolvesMovableKeys() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("MULTI");
            c.call("EVAL", "return redis.call('set', KEYS[1], ARGV[1])", "1", "{fid:e}k", "v");
            c.call("GET", "{fid:e}k");

            assertEquals(List.of(new Status("OK"), "v"), c.call("EXEC"));
        }
    }

    @Test
    void watchAbortedExecReturnsNil() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            a.call("SET", "fid:w", "1");
            assertEquals(new Status("OK"), a.call("WATCH", "fid:w"));
            CompletableFuture.runAsync(() -> {
                try {
                    b.call("SET", "fid:w", "fromB");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).get(5, TimeUnit.SECONDS);

            a.call("MULTI");
            a.call("SET", "fid:w", "fromA");
            assertEquals(RawRespClient.NULL_ARRAY, a.call("EXEC"), "aborted EXEC is a null array");
            assertEquals("fromB", a.call("GET", "fid:w"));
        }
    }

    /** [upstream:G1] Glide's error text is mapped back to the codes Valkey sends. */
    @Test
    void errorsUseServerErrorCodes() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "fid:str", "fid:stream");
            c.call("SET", "fid:str", "v");
            assertError("WRONGTYPE Operation against a key", c.call("LPUSH", "fid:str", "x"));
            assertError("NOSCRIPT ", c.call("EVALSHA", "0000000000000000000000000000000000000000", "0"));
            assertError("CROSSSLOT ", c.call("SUNION", "fid:s1", "fid:s2"));
            assertError("ERR wrong number of arguments for 'get' command", c.call("GET"));
            assertError("ERR unknown command", c.call("NOSUCHCOMMAND", "fid:str"));
            assertEquals(new Status("OK"), c.call("XGROUP", "CREATE", "fid:stream", "g", "$", "MKSTREAM"));
            assertError("BUSYGROUP ", c.call("XGROUP", "CREATE", "fid:stream", "g", "$"));
            assertError("NOGROUP ", c.call("XREADGROUP", "GROUP", "nosuchgroup", "c", "STREAMS", "fid:stream", ">"));
            assertError("NOTBUSY ", c.call("SCRIPT", "KILL"));
        }
    }

    /** [upstream:G7] RESP3 verbatim text arrives from Glide as a String; RESP2 sends a bulk string. */
    @Test
    void verbatimTextRepliesAreBulkStrings() throws Exception {
        try (RawRespClient c = connect()) {
            // LOLWUT is passed through, and its RESP3 reply is a verbatim string.
            Object art = c.call("LOLWUT");
            assertInstanceOf(String.class, art, () -> "LOLWUT should be a bulk string, got " + art);
            assertTrue(((String) art).contains("\n") && ((String) art).contains("ver."), () -> "LOLWUT: " + art);
        }
    }

    /** [upstream:G7] COMMAND DOCS argument flags are statuses; redis-cli asserts on it. */
    @Test
    void commandDocsFlagsAreStatuses() throws Exception {
        try (RawRespClient c = connect()) {
            Object docs = c.call("COMMAND", "DOCS", "SET");
            List<Object> flags = new java.util.ArrayList<>();
            collectFlagValues(docs, flags);
            assertFalse(flags.isEmpty(), "SET's arguments have flags: " + docs);
            assertTrue(flags.stream().allMatch(f -> f instanceof Status), "flags must be statuses: " + flags);
            assertTrue(flags.contains(new Status("optional")), "flags: " + flags);
        }
    }

    /** Collects the values following each "flags" key in a COMMAND DOCS reply. */
    private static void collectFlagValues(Object reply, List<Object> out) {
        if (reply instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if ("flags".equals(item) && i + 1 < list.size() && list.get(i + 1) instanceof List<?> values) {
                    out.addAll(values);
                }
                collectFlagValues(item, out);
            }
        }
    }
}
