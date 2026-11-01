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

    @Test
    void errorsUseServerErrorCodes() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("SET", "fid:str", "v");
            assertError("WRONGTYPE Operation against a key", c.call("LPUSH", "fid:str", "x"));
            assertError("NOSCRIPT ", c.call("EVALSHA", "0000000000000000000000000000000000000000", "0"));
            assertError("CROSSSLOT ", c.call("SUNION", "fid:s1", "fid:s2"));
            assertError("ERR wrong number of arguments for 'get' command", c.call("GET"));
        }
    }
}
