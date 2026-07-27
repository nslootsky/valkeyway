package io.github.nslootsky.proxy.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyAdminCommandsTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void proxyClusterInfo() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String result = client.customCommand("PROXY", "CLUSTER", "INFO");
            assertNotNull(result);
            assertTrue(result.contains("cluster_state"));
        }
    }

    @Test
    @Order(2)
    void proxyConfigGet() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            Object[] result = client.customCommandArr("PROXY", "CONFIG", "GET", "cluster_slots");
            assertNotNull(result);
            assertEquals(2, result.length);
            assertEquals("cluster_slots", result[0]);
            assertEquals("16384", result[1]);
        }
    }

    @Test
    @Order(3)
    void proxyConfigSet() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.customCommand("PROXY", "CONFIG", "SET", "test_key", "test_value"));
        }
    }

    @Test
    @Order(4)
    void proxyStats() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String result = client.customCommand("PROXY", "STATS");
            assertNotNull(result);
            assertTrue(result.contains("cluster_nodes"));
        }
    }
}
