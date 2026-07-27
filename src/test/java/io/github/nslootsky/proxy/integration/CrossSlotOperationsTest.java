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
            String[] keys = {
                "user:profile:1024",
                "session:active:2048",
                "cache:product:3072",
                "queue:task:4096",
                "analytics:event:5120",
                "config:feature:6144",
                "rate:limit:7168",
                "lock:distributed:8192",
                "notification:push:9216",
                "search:index:10240"
            };

            for (int i = 0; i < keys.length; i++) {
                assertEquals("OK", client.set(keys[i], "v" + i));
            }

            String[] result = client.mget(keys);
            assertNotNull(result);
            assertEquals(keys.length, result.length);
            for (int i = 0; i < keys.length; i++) {
                assertEquals("v" + i, result[i], "Mismatch at index " + i);
            }
        }
    }

    @Test
    @Order(2)
    void delCrossSlot() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            String[] keys = {
                "order:history:111",
                "inventory:warehouse:222",
                "payment:transaction:333",
                "shipping:tracking:444",
                "review:product:555",
                "wishlist:user:666",
                "coupon:discount:777",
                "subscription:plan:888",
                "audit:log:999",
                "backup:snapshot:1000"
            };

            for (String key : keys) {
                assertEquals("OK", client.set(key, "val"));
            }

            assertEquals(keys.length, client.del(keys));

            for (String key : keys) {
                assertNull(client.get(key), "Key " + key + " should be deleted");
            }
        }
    }
}
