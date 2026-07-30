/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import glide.api.GlideClient;
import glide.api.models.GlideString;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ensures that actual keys/values named "optional", "multiple", or "multiple_token"
 * are returned as bulk strings, not status tokens. These names are special-cased in
 * TokenUtils for command docs but must not affect normal data operations.
 */
class SpecialValueNamesTest extends IntegrationTestBase {

    @Test
    void getReturnsBulkStringForSpecialValues() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();

            // Store each special value name as a regular string value
            assertEquals("OK", glide.set("key_optional", "optional").get());
            assertEquals("OK", glide.set("key_multiple", "multiple").get());
            assertEquals("OK", glide.set("key_multiple_token", "multiple_token").get());

            // GET must return bulk strings, not status tokens
            String v1 = glide.get("key_optional").get();
            String v2 = glide.get("key_multiple").get();
            String v3 = glide.get("key_multiple_token").get();

            assertEquals("optional", v1);
            assertEquals("multiple", v2);
            assertEquals("multiple_token", v3);
        }
    }

    @Test
    void mgetReturnsBulkStringsForSpecialValues() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();

            glide.set("m1", "optional").get();
            glide.set("m2", "multiple").get();
            glide.set("m3", "multiple_token").get();

            Object[] results = glide.mget(new String[]{"m1", "m2", "m3"}).get();
            assertNotNull(results);
            assertEquals(3, results.length);
            assertEquals("optional", results[0]);
            assertEquals("multiple", results[1]);
            assertEquals("multiple_token", results[2]);
        }
    }

    @Test
    void hgetReturnsBulkStringForSpecialValues() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();

            glide.hset("hash_special", Map.of(
                    "field_optional", "optional",
                    "field_multiple", "multiple",
                    "field_multiple_token", "multiple_token")).get();

            assertEquals("optional", glide.hget("hash_special", "field_optional").get());
            assertEquals("multiple", glide.hget("hash_special", "field_multiple").get());
            assertEquals("multiple_token", glide.hget("hash_special", "field_multiple_token").get());
        }
    }

    @Test
    void hmgetReturnsBulkStringsForSpecialValues() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();

            glide.hset("hash_mget", Map.of(
                    "f1", "optional",
                    "f2", "multiple",
                    "f3", "multiple_token")).get();

            Object[] results = glide.hmget("hash_mget", new String[]{"f1", "f2", "f3"}).get();
            assertNotNull(results);
            assertEquals(3, results.length);
            assertEquals("optional", results[0]);
            assertEquals("multiple", results[1]);
            assertEquals("multiple_token", results[2]);
        }
    }

    @Test
    void specialNamesAsKeysWorkCorrectly() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();

            // Use the special names as keys themselves
            assertEquals("OK", glide.set("optional", "value1").get());
            assertEquals("OK", glide.set("multiple", "value2").get());
            assertEquals("OK", glide.set("multiple_token", "value3").get());

            assertEquals("value1", glide.get("optional").get());
            assertEquals("value2", glide.get("multiple").get());
            assertEquals("value3", glide.get("multiple_token").get());
        }
    }

    @Test
    void getReturnsBulkStringForSpecialValuesViaBinaryApi() throws Exception {
        try (GlideTestClient client = new GlideTestClient("127.0.0.1", RESP_PORT)) {
            GlideClient glide = client.getClient();

            // Store via binary/GlideString API — these must still return as bulk strings, not status tokens
            assertEquals("OK", glide.set(GlideString.of("bin_optional"), GlideString.of("optional")).get());
            assertEquals("OK", glide.set(GlideString.of("bin_multiple"), GlideString.of("multiple")).get());
            assertEquals("OK", glide.set(GlideString.of("bin_multiple_token"), GlideString.of("multiple_token")).get());

            // Read back via binary API and verify exact bytes match (status vs bulk string would differ in RESP)
            GlideString v1 = glide.get(GlideString.of("bin_optional")).get();
            GlideString v2 = glide.get(GlideString.of("bin_multiple")).get();
            GlideString v3 = glide.get(GlideString.of("bin_multiple_token")).get();

            assertEquals("optional", v1.getString());
            assertEquals("multiple", v2.getString());
            assertEquals("multiple_token", v3.getString());
        }
    }
}
