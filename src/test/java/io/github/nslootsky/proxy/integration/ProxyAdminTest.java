package io.github.nslootsky.proxy.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyAdminTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void flushClients() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String result = client.proxyFlushClients();
            assertNotNull(result);
            assertTrue(result.startsWith("Flushed"));
        }
    }

    @Test
    @Order(2)
    void clientInfo() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String result = client.proxyClientInfo("test-client-1");
            assertNotNull(result);
            assertTrue(result.contains("test-client-1"));
        }
    }
}
