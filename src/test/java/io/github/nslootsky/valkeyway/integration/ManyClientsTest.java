/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

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
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
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
}
