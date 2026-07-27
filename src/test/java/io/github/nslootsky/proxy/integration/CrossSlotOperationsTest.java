package io.github.nslootsky.proxy.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossSlotOperationsTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void mgetCrossSlot() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("key1", "value1"));
            assertEquals("OK", client.set("key2", "value2"));
            assertEquals("OK", client.set("key3", "value3"));
            String[] result = client.mget("key1", "key2", "key3");
            assertNotNull(result);
            assertEquals(3, result.length);
            assertEquals("value1", result[0]);
            assertEquals("value2", result[1]);
            assertEquals("value3", result[2]);
        }
    }

    @Test
    @Order(2)
    void delCrossSlot() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("del1", "v1"));
            assertEquals("OK", client.set("del2", "v2"));
            assertEquals("OK", client.set("del3", "v3"));
            assertEquals(3, client.del("del1", "del2", "del3"));
            assertNull(client.get("del1"));
            assertNull(client.get("del2"));
            assertNull(client.get("del3"));
        }
    }
}
