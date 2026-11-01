/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.protocol.RedisToken;
import com.github.tonivade.resp.protocol.RedisTokenType;
import glide.api.models.GlideString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.github.tonivade.resp.protocol.RedisSerializer;

import java.nio.charset.StandardCharsets;

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

    /**
     * Raw Glide messages captured from a Valkey 9.1 cluster, paired with what the server itself sends.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - ResponseError: wrong number of arguments for 'get' command"
                    + " | ERR wrong number of arguments for 'get' command",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - NoScriptError: No matching script."
                    + " | NOSCRIPT No matching script.",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - CrossSlot: Keys in request don't hash to the same slot"
                    + " | CROSSSLOT Keys in request don't hash to the same slot",
            "glide.api.models.exceptions.RequestException: An error was signalled by the server: - NotBusy: No scripts in execution right now."
                    + " | NOTBUSY No scripts in execution right now.",
            "glide.api.models.exceptions.RequestException: WRONGTYPE: Operation against a key holding the wrong kind of value"
                    + " | WRONGTYPE Operation against a key holding the wrong kind of value",
            "glide.api.models.exceptions.RequestException: BUSYGROUP: Consumer Group name already exists"
                    + " | BUSYGROUP Consumer Group name already exists",
            "An error was signalled by the server: - ExecAbortError: Transaction discarded because of previous errors."
                    + " | EXECABORT Transaction discarded because of previous errors.",
            "ResponseError: syntax error | ERR syntax error",
            "ERR invalid SCAN cursor | ERR invalid SCAN cursor",
            "glide.api.models.exceptions.TimeoutException: Request timed out | ERR Request timed out",
    })
    void errorMessagesUseServerErrorCodes(String glideMessage, String expected) {
        assertEquals(expected, TokenUtils.cleanErrorMessage(glideMessage));
    }

    @Test
    void nullErrorMessageStillHasCode() {
        assertEquals("ERR Unknown error", TokenUtils.cleanErrorMessage(null));
    }

    /**
     * Expected strings are what Valkey 9.1 returns for ZSCORE over RESP2.
     */
    @ParameterizedTest
    @CsvSource({
            "1.5, 1.5",
            "2, 2",
            "0.1, 0.1",
            "1e20, 1e+20",
            "1e-7, 1e-7",
            "-0.5, -0.5",
            "123456789012345678, 123456789012345680",
            "3.14159265358979, 3.14159265358979",
            "1e17, 100000000000000000",
            "1234567.125, 1234567.125",
            "0, 0",
            "-0.0, 0",
            "Infinity, inf",
            "-Infinity, -inf",
    })
    void doublesFormatLikeValkey(double value, String expected) {
        assertEquals(expected, TokenUtils.formatDouble(value));
    }

    private static String wire(RedisToken token) {
        return new String(RedisSerializer.encodeToken(token), StandardCharsets.UTF_8);
    }

    @Test
    void doublesBecomeBulkStrings() {
        assertEquals("$3\r\n1.5\r\n", wire(TokenUtils.toRedisToken((Object) 1.5d)));
    }

    @Test
    void integersStayIntegers() {
        assertEquals(":42\r\n", wire(TokenUtils.toRedisToken((Object) 42L)));
    }

    @Test
    void throwableInArrayBecomesErrorElement() {
        Object[] execResults = {"OK", new RuntimeException("WRONGTYPE: Operation against a key holding the wrong kind of value")};
        assertEquals("*2\r\n+OK\r\n-WRONGTYPE Operation against a key holding the wrong kind of value\r\n",
                wire(TokenUtils.toRedisArray(execResults)));
    }
}
