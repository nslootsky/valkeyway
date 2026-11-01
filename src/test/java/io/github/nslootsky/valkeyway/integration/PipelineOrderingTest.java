/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipelined commands are started concurrently, so these check they still behave as if run one
 * after another, as on a standalone server. This relies on Glide keeping per-node submission order.
 */
class PipelineOrderingTest extends IntegrationTestBase {

    private static RawRespClient connect() throws Exception {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    @Test
    void writesAndReadsOfOneKeyKeepTheirOrder() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "{po}n");
            List<String[]> pipeline = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                pipeline.add(new String[]{"INCR", "{po}n"});
                pipeline.add(new String[]{"GET", "{po}n"});
            }
            List<Object> replies = c.pipeline(pipeline);
            for (int i = 0; i < 5000; i++) {
                assertEquals((long) i + 1, replies.get(2 * i), "INCR #" + i);
                assertEquals(Long.toString(i + 1), replies.get(2 * i + 1), "GET after INCR #" + i);
            }
        }
    }

    @Test
    void readYourWritesAcrossManyKeysAndNodes() throws Exception {
        try (RawRespClient c = connect()) {
            List<String[]> pipeline = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                pipeline.add(new String[]{"SET", "po:k" + i, "v" + i});
                pipeline.add(new String[]{"GET", "po:k" + i});
                pipeline.add(new String[]{"APPEND", "po:k" + i, "!"});
                pipeline.add(new String[]{"GET", "po:k" + i});
            }
            List<Object> replies = c.pipeline(pipeline);
            for (int i = 0; i < 2000; i++) {
                assertEquals(new Status("OK"), replies.get(4 * i));
                assertEquals("v" + i, replies.get(4 * i + 1));
                assertEquals((long) ("v" + i + "!").length(), replies.get(4 * i + 2));
                assertEquals("v" + i + "!", replies.get(4 * i + 3));
            }
        }
    }

    @Test
    void stateChangingCommandsInsideAPipelineApplyInOrder() throws Exception {
        try (RawRespClient c = connect()) {
            List<Object> replies = c.pipeline(List.of(
                    new String[]{"SET", "{po}x", "db0"},
                    new String[]{"SELECT", "1"},
                    new String[]{"GET", "{po}x"},
                    new String[]{"SET", "{po}x", "db1"},
                    new String[]{"SELECT", "0"},
                    new String[]{"GET", "{po}x"},
                    new String[]{"MULTI"},
                    new String[]{"APPEND", "{po}x", "+tx"},
                    new String[]{"GET", "{po}x"},
                    new String[]{"EXEC"},
                    new String[]{"GET", "{po}x"},
                    new String[]{"SELECT", "1"},
                    new String[]{"GET", "{po}x"},
                    new String[]{"DEL", "{po}x"},
                    new String[]{"SELECT", "0"}));
            assertEquals(new Status("OK"), replies.get(0));
            assertEquals(new Status("OK"), replies.get(1));
            assertNull(replies.get(2), "db1 doesn't have db0's key");
            assertEquals(new Status("OK"), replies.get(3));
            assertEquals(new Status("OK"), replies.get(4));
            assertEquals("db0", replies.get(5));
            assertEquals(new Status("OK"), replies.get(6));
            assertEquals(new Status("QUEUED"), replies.get(7));
            assertEquals(new Status("QUEUED"), replies.get(8));
            assertEquals(List.of(6L, "db0+tx"), replies.get(9));
            assertEquals("db0+tx", replies.get(10));
            assertEquals(new Status("OK"), replies.get(11));
            assertEquals("db1", replies.get(12));
        }
    }

    @Test
    void blockingCommandSeesEarlierPipelinedWrites() throws Exception {
        try (RawRespClient c = connect()) {
            List<Object> replies = c.pipeline(List.of(
                    new String[]{"DEL", "{po}l"},
                    new String[]{"RPUSH", "{po}l", "a", "b"},
                    new String[]{"BLPOP", "{po}l", "1"},
                    new String[]{"LLEN", "{po}l"},
                    new String[]{"BLPOP", "{po}l", "1"},
                    new String[]{"BLPOP", "{po}l", "0.1"},
                    new String[]{"PING"}));
            assertEquals(List.of("{po}l", "a"), replies.get(2));
            assertEquals(1L, replies.get(3));
            assertEquals(List.of("{po}l", "b"), replies.get(4));
            assertEquals(RawRespClient.NULL_ARRAY, replies.get(5));
            assertEquals(new Status("PONG"), replies.get(6));
        }
    }

    @Test
    void manyConnectionsPipeliningAtOnce() throws Exception {
        int connections = 50;
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < connections; t++) {
            int id = t;
            threads.add(Thread.ofVirtual().start(() -> {
                try (RawRespClient c = connect()) {
                    String key = "{po:m" + id + "}n";
                    c.call("DEL", key);
                    List<String[]> pipeline = new ArrayList<>();
                    for (int i = 0; i < 1000; i++) {
                        pipeline.add(new String[]{"INCR", key});
                    }
                    List<Object> replies = c.pipeline(pipeline);
                    for (int i = 0; i < 1000; i++) {
                        assertEquals((long) i + 1, replies.get(i));
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> failures.size() + " connections failed, first: " + failures.getFirst());
    }
}
