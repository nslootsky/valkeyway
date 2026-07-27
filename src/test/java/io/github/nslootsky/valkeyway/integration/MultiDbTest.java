package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiDbTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void selectAndDbIsolation() throws Exception {
        try (GlideTestClient client0 = new GlideTestClient("127.0.0.1", RESP_PORT);
             GlideTestClient client1 = new GlideTestClient("127.0.0.1", RESP_PORT, 1)) {
            client0.set("db0_key", "value0");
            assertEquals("value0", client0.get("db0_key"));
            assertNull(client1.get("db0_key"));
            client1.set("db1_key", "value1");
            assertEquals("value1", client1.get("db1_key"));
            assertNull(client0.get("db1_key"));
        }
    }

    @Test
    @Order(2)
    void selectFollowedBySet() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.select(2));
            assertEquals("OK", client.set("db2_key", "value2"));
            assertEquals("value2", client.get("db2_key"));
        }
    }

    @Test
    @Order(3)
    void multipleSelectOperations() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.select(3));
            assertEquals("OK", client.set("db3_key", "value3"));
            assertEquals("value3", client.get("db3_key"));
            assertEquals("OK", client.select(4));
            assertEquals("OK", client.set("db4_key", "value4"));
            assertEquals("value4", client.get("db4_key"));
        }
    }
}
