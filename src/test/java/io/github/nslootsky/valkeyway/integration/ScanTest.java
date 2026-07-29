/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

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

    private static final int NUM_KEYS = 100;

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

    @Test
    @Order(3)
    void scanCountMissingReturnsAtLeastDefault() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_count_missing_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0");
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= 10, "Expected at least 10 keys with default COUNT, got " + keys.length);
        }
    }

    @Test
    @Order(4)
    void scanCountBelowDbSizeReturnsAtLeastCount() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_count_below_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0", "COUNT", "50");
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= 50, "Expected at least 50 keys, got " + keys.length);
        }
    }

    @Test
    @Order(5)
    void scanCountEqualDbSizeReturnsAllKeys() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_count_equal_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0", "COUNT", String.valueOf(NUM_KEYS));
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= NUM_KEYS, "Expected at least " + NUM_KEYS + " keys, got " + keys.length);
        }
    }

    @Test
    @Order(6)
    void scanCountAboveDbSizeReturnsAllKeys() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_count_above_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0", "COUNT", "999");
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= NUM_KEYS, "Expected at least " + NUM_KEYS + " keys, got " + keys.length);
        }
    }

    @Test
    @Order(7)
    void scanCountWithMatchBelowDbSizeReturnsAtLeastCount() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_match_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0", "MATCH", "scan_match_*", "COUNT", "50");
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= 50, "Expected at least 50 keys with MATCH, got " + keys.length);
        }
    }

    @Test
    @Order(8)
    void scanCountWithMatchEqualDbSizeReturnsAllKeys() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_match_eq_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0", "MATCH", "scan_match_eq_*", "COUNT", String.valueOf(NUM_KEYS));
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= NUM_KEYS, "Expected at least " + NUM_KEYS + " keys with MATCH, got " + keys.length);
        }
    }

    @Test
    @Order(9)
    void scanCountWithMatchAboveDbSizeReturnsAllKeys() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            for (int i = 0; i < NUM_KEYS; i++) {
                client.set("scan_match_above_" + i, "v");
            }

            Object[] result = client.customCommandArr("SCAN", "0", "MATCH", "scan_match_above_*", "COUNT", "999");
            Object[] keys = (Object[]) result[1];
            assertTrue(keys.length >= NUM_KEYS, "Expected at least " + NUM_KEYS + " keys with MATCH, got " + keys.length);
        }
    }
}
