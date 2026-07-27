package io.github.nslootsky.proxy.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HashTagsTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void hashTagRouting() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("{user}:1:name", "Alice"));
            assertEquals("OK", client.set("{user}:1:email", "alice@example.com"));
            assertEquals("Alice", client.get("{user}:1:name"));
            assertEquals("alice@example.com", client.get("{user}:1:email"));
        }
    }

    @Test
    @Order(2)
    void hashOperations() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals(1, client.hset("myhash", "field1", "value1"));
            assertEquals("value1", client.hget("myhash", "field1"));
            assertEquals(1, client.hset("myhash", "field2", "value2"));
            assertEquals("value2", client.hget("myhash", "field2"));
        }
    }

    @Test
    @Order(3)
    void hgetall() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            client.hset("hgetall_hash", "f1", "v1");
            client.hset("hgetall_hash", "f2", "v2");
            java.util.Map<String, String> result = client.hgetall("hgetall_hash");
            assertNotNull(result);
            assertEquals(2, result.size());
            assertEquals("v1", result.get("f1"));
            assertEquals("v2", result.get("f2"));
        }
    }

    @Test
    @Order(4)
    void hdel() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            client.hset("hdel_hash", "field1", "value1");
            client.hset("hdel_hash", "field2", "value2");
            assertEquals(1, client.hdel("hdel_hash", "field1"));
            assertNull(client.hget("hdel_hash", "field1"));
            assertEquals("value2", client.hget("hdel_hash", "field2"));
        }
    }
}
