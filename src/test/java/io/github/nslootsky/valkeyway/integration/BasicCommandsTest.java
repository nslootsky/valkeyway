/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicCommandsTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void incrDecr() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals(1L, client.incr("counter"));
            assertEquals(2L, client.incr("counter"));
            assertEquals(1L, client.decr("counter"));
            assertEquals(0L, client.decr("counter"));
        }
    }

    @Test
    @Order(2)
    void getset() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertNull(client.getset("gs_key", "new"));
            assertEquals("new", client.getset("gs_key", "updated"));
            assertEquals("updated", client.get("gs_key"));
        }
    }

    @Test
    @Order(3)
    void listOperations() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals(1L, client.lpush("mylist", "a"));
            assertEquals(2L, client.lpush("mylist", "b"));
            assertEquals(3L, client.rpush("mylist", "c"));
            assertEquals("c", client.rpop("mylist"));
            assertEquals("b", client.lpop("mylist"));
            String[] range = client.lrange("mylist", 0, -1);
            assertEquals(1, range.length);
            assertEquals("a", range[0]);
        }
    }

    @Test
    @Order(4)
    void hmget() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            client.hset("myhash", "f1", "v1");
            client.hset("myhash", "f2", "v2");
            String[] result = client.hmget("myhash", "f1", "f2");
            assertArrayEquals(new String[]{"v1", "v2"}, result);
        }
    }

    @Test
    @Order(5)
    void hincrby() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals(10L, client.hincrby("myhash", "count", 10));
            assertEquals(15L, client.hincrby("myhash", "count", 5));
            assertEquals(-5L, client.hincrby("myhash", "count", -20));
        }
    }

    @Test
    @Order(6)
    void setex() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("setex_key", "value", 100));
            assertEquals("value", client.get("setex_key"));
            Long ttl = client.ttl("setex_key");
            assertTrue(ttl > 0 && ttl <= 100, "TTL should be between 1 and 100, got: " + ttl);
        }
    }
}
