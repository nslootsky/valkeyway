/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import glide.api.GlideClient;
import glide.api.models.Batch;
import glide.api.models.GlideString;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BinaryOperationsTest extends IntegrationTestBase {

    @Test
    void setAndGetBinaryValue() throws Exception {
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
        GlideString key = GlideString.of("binary_test_key");
        GlideString value = GlideString.of(binaryData);

        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();
            assertEquals("OK", glide.set(key, value).get());
            GlideString result = glide.get(key).get();
            assertArrayEquals(binaryData, result.getBytes());
        }
    }

    @Test
    void mgetBinaryValues() throws Exception {
        byte[] data1 = new byte[]{0x01, 0x02, 0x03};
        byte[] data2 = new byte[]{(byte) 0xFF, (byte) 0xFE};
        GlideString key1 = GlideString.of("bin_key1");
        GlideString key2 = GlideString.of("bin_key2");

        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();
            assertEquals("OK", glide.set(key1, GlideString.of(data1)).get());
            assertEquals("OK", glide.set(key2, GlideString.of(data2)).get());
            Object[] results = glide.mget(new GlideString[]{key1, key2}).get();
            assertNotNull(results);
            assertEquals(2, results.length);
            assertArrayEquals(data1, ((GlideString) results[0]).getBytes());
            assertArrayEquals(data2, ((GlideString) results[1]).getBytes());
        }
    }

    @Test
    void hsetAndGetBinaryValues() throws Exception {
        GlideString key = GlideString.of("binary_hash");
        GlideString field = GlideString.of(new byte[]{0x10, 0x20, (byte) 0xFF});
        GlideString value = GlideString.of(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC});

        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();
            Long setResult = glide.hset(key, Map.of(field, value)).get();
            assertEquals(1L, setResult);
            GlideString result = glide.hget(key, field).get();
            assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, result.getBytes());
        }
    }

    @Test
    void hmgetBinaryValues() throws Exception {
        GlideString key = GlideString.of("bin_hash2");
        GlideString field1 = GlideString.of(new byte[]{0x01});
        GlideString field2 = GlideString.of(new byte[]{0x02});
        GlideString value1 = GlideString.of(new byte[]{(byte) 0xAA, (byte) 0xBB});
        GlideString value2 = GlideString.of(new byte[]{(byte) 0xCC, (byte) 0xDD});

        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();
            glide.hset(key, Map.of(field1, value1, field2, value2)).get();
            Object[] results = glide.hmget(key, new GlideString[]{field1, field2}).get();
            assertNotNull(results);
            assertEquals(2, results.length);
            assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB}, ((GlideString) results[0]).getBytes());
            assertArrayEquals(new byte[]{(byte) 0xCC, (byte) 0xDD}, ((GlideString) results[1]).getBytes());
        }
    }

    @Test
    void binaryTransaction() throws Exception {
        byte[] data1 = new byte[]{0x01, 0x02, (byte) 0xFF};
        byte[] data2 = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC};
        GlideString key1 = GlideString.of("tx_bin_key1");
        GlideString key2 = GlideString.of("tx_bin_key2");

        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();
            Batch batch = new Batch(true).withBinaryOutput();
            batch.set(key1, GlideString.of(data1));
            batch.set(key2, GlideString.of(data2));
            Object[] results = glide.exec(batch, true).get();
            assertNotNull(results);
            assertEquals(2, results.length);
            GlideString result1 = glide.get(key1).get();
            GlideString result2 = glide.get(key2).get();
            assertArrayEquals(data1, result1.getBytes());
            assertArrayEquals(data2, result2.getBytes());
        }
    }
}
