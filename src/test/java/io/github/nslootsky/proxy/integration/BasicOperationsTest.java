package io.github.nslootsky.proxy.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasicOperationsTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void ping() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("PONG", client.ping());
        }
    }

    @Test
    @Order(2)
    void setAndGet() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("test_key", "hello"));
            assertEquals("hello", client.get("test_key"));
        }
    }

    @Test
    @Order(3)
    void del() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("del_key", "value"));
            assertEquals("value", client.get("del_key"));
            assertEquals(1L, client.del("del_key"));
            assertNull(client.get("del_key"));
        }
    }

    @Test
    @Order(4)
    void unlink() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("unlink_key", "value"));
            assertEquals(1L, client.unlink("unlink_key"));
            assertNull(client.get("unlink_key"));
        }
    }

    @Test
    @Order(5)
    void info() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String result = client.info();
            assertNotNull(result);
            assertTrue(result.contains("role:master"));
        }
    }

    @Test
    @Order(6)
    void time() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String[] result = client.time();
            assertNotNull(result);
            assertEquals(2, result.length);
            assertTrue(Long.parseLong(result[0]) > 0);
        }
    }

    @Test
    @Order(7)
    void expireAndTtl() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("ttl_key", "value"));
            assertTrue(client.expire("ttl_key", 100));
            Long ttl = client.ttl("ttl_key");
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 100);
        }
    }

    @Test
    @Order(8)
    void setWithExpiry() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("setex_key", "value", 100));
            Long ttl = client.ttl("setex_key");
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 100);
        }
    }

    @Test
    @Order(9)
    void exists() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("exists_key", "value"));
            assertEquals(1L, client.exists("exists_key"));
            assertEquals(0L, client.exists("nonexistent_key"));
        }
    }
}
