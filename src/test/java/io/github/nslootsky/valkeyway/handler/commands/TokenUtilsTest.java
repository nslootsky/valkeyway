/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.protocol.RedisToken;
import com.github.tonivade.resp.protocol.RedisTokenType;
import glide.api.models.GlideString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TokenUtils.toRedisToken. Verifies that special value names used in command docs
 * ("optional", "multiple", "multiple_token") are NOT special-cased here — they should be treated
 * as regular bulk strings to preserve data integrity. The special-casing is done only in
 * CommandCommand for COMMAND DOCS responses.
 */
class TokenUtilsTest {

    @Test
    void glideStringSpecialValuesReturnBulkStrings() {
        // These must return as bulk strings — NOT status tokens — to avoid corrupting actual data
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken(GlideString.of("optional")).getType());
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken(GlideString.of("multiple")).getType());
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken(GlideString.of("multiple_token")).getType());
    }

    @Test
    void glideStringNonSpecialValuesReturnBulkStrings() {
        // Regular values must return as bulk strings
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken(GlideString.of("hello")).getType());
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken(GlideString.of("OK")).getType());
    }

    @Test
    void stringSpecialValuesReturnBulkStrings() {
        // Plain String objects should always return as bulk strings
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken("optional").getType());
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken("multiple").getType());
        assertEquals(RedisTokenType.STRING, TokenUtils.toRedisToken("multiple_token").getType());
    }

}
