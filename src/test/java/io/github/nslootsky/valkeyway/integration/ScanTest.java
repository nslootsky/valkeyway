package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScanTest extends IntegrationTestBase {

    @Test
    @Order(1)
    void scanWithMatch() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("scan_key_1", "v1"));
            assertEquals("OK", client.set("scan_key_2", "v2"));
            assertEquals("OK", client.set("scan_key_3", "v3"));
            assertEquals("OK", client.set("other_key", "other"));
            List<String> keys = new ArrayList<>();
            String cursor = "0";
            do {
                Object[] result = client.scan(cursor, "scan_key_*");
                cursor = (String) result[0];
                Object[] scannedKeys = (Object[]) result[1];
                for (Object key : scannedKeys) {
                    keys.add((String) key);
                }
            } while (!"0".equals(cursor));
            assertTrue(keys.size() >= 3);
            for (int i = 1; i <= 3; i++) {
                assertTrue(keys.contains("scan_key_" + i));
            }
        }
    }

    @Test
    @Order(2)
    void scanWithoutMatch() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            assertEquals("OK", client.set("scan_all_key_1", "v1"));
            assertEquals("OK", client.set("scan_all_key_2", "v2"));
            List<String> keys = new ArrayList<>();
            String cursor = "0";
            do {
                Object[] result = client.scan(cursor);
                cursor = (String) result[0];
                Object[] scannedKeys = (Object[]) result[1];
                for (Object key : scannedKeys) {
                    keys.add((String) key);
                }
            } while (!"0".equals(cursor));
            assertTrue(keys.size() >= 2);
            assertTrue(keys.contains("scan_all_key_1"));
            assertTrue(keys.contains("scan_all_key_2"));
        }
    }
}
