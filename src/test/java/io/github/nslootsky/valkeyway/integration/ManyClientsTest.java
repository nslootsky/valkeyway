/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import java.util.concurrent.Future;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every proxy connection shares one Glide client per database. Glide rejects requests beyond its
 * in-flight limit (1000 by default), so many busy clients must not surface
 * "Client reached maximum inflight requests".
 */
class ManyClientsTest extends IntegrationTestBase {

    @Test
    void manyConcurrentClientsDoNotHitTheBackendInflightLimit() throws Exception {
        int clients = 1500;
        int requestsEach = 40;
        try (RawRespClient setup = new RawRespClient("127.0.0.1", RESP_PORT)) {
            setup.call("SET", "many:k", "v");
        }

        ConcurrentLinkedQueue<Object> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch connected = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                tasks.add(pool.submit(() -> {
                    try (RawRespClient c = new RawRespClient("127.0.0.1", RESP_PORT)) {
                        connected.countDown();
                        go.await();
                        for (int r = 0; r < requestsEach; r++) {
                            Object reply = c.call("GET", "many:k");
                            if (!"v".equals(reply)) {
                                unexpected.add(reply);
                            }
                        }
                    }
                    return null;
                }));
            }
            assertTrue(connected.await(30, TimeUnit.SECONDS), "all clients connected");
            go.countDown();
            for (var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }
        assertTrue(unexpected.isEmpty(), () -> unexpected.size() + " unexpected replies, e.g. "
                + unexpected.stream().filter(r -> r instanceof Error).findFirst().orElse(unexpected.peek()));
    }

    /**
     * Commands from different connections are coalesced into shared backend batches; every reply,
     * including errors and binary map replies, must still reach the connection that sent the command.
     */
    @Test
    void coalescedRepliesReachTheRightConnection() throws Exception {
        int clients = 200;
        int rounds = 50;
        ConcurrentLinkedQueue<String> wrong = new ConcurrentLinkedQueue<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                int id = i;
                tasks.add(pool.submit(() -> {
                    String key = "{coal" + id + "}";
                    String field = "\u00ff" + id; // binary field name
                    try (RawRespClient c = new RawRespClient("127.0.0.1", RESP_PORT)) {
                        c.call("DEL", key + "s", key + "h");
                        c.call("SET", key + "s", "value-" + id);
                        c.call("HSET", key + "h", field, "v" + id, "plain", "p" + id);
                        for (int r = 0; r < rounds; r++) {
                            Object get = c.call("GET", key + "s");
                            Object hash = c.call("HGETALL", key + "h");
                            Object err = c.call("LPUSH", key + "s", "x");
                            Object incr = c.call("HINCRBY", key + "h", "n", "1");
                            if (!("value-" + id).equals(get)) wrong.add(id + " GET " + get);
                            if (!List.of(field, "v" + id, "plain", "p" + id, "n", Long.toString(r)).subList(0, r == 0 ? 4 : 6)
                                    .equals(hash)) wrong.add(id + " HGETALL " + hash);
                            if (!(err instanceof Error e && e.message().startsWith("WRONGTYPE"))) wrong.add(id + " LPUSH " + err);
                            if (!Long.valueOf(r + 1).equals(incr)) wrong.add(id + " HINCRBY " + incr);
                        }
                    }
                    return null;
                }));
            }
            for (var task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }
        assertTrue(wrong.isEmpty(), () -> wrong.size() + " misrouted or wrong replies, e.g. " + wrong.peek());
    }
}
