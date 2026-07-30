/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.protocol.AbstractRedisToken;
import com.github.tonivade.resp.protocol.RedisToken;
import com.github.tonivade.resp.protocol.RedisTokenType;
import glide.api.models.GlideString;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CommandCommand to verify COMMAND DOCS responses properly convert
 * flag values ("optional", "multiple", "multiple_token") to status tokens.
 */
class CommandCommandTest {

    @Test
    void docsFlagValuesConvertedToStatusTokens() {
        Object[] flags = new Object[]{GlideString.of("optional"), GlideString.of("multiple"), GlideString.of("multiple_token")};

        CommandCommand command = new CommandCommand(null);
        RedisToken token = command.toRedisTokenForDocs(flags);

        assertEquals(RedisTokenType.ARRAY, token.getType());
        @SuppressWarnings("unchecked")
        Collection<RedisToken> arrayTokens = (Collection<RedisToken>) ((Object) ((AbstractRedisToken<?>) token).getValue());
        assertNotNull(arrayTokens);
        assertEquals(3, arrayTokens.size());

        var iterator = arrayTokens.iterator();
        RedisToken t0 = iterator.next();
        RedisToken t1 = iterator.next();
        RedisToken t2 = iterator.next();

        assertEquals(RedisTokenType.STATUS, t0.getType());
        assertEquals("optional", getStatusValue(t0));
        assertEquals(RedisTokenType.STATUS, t1.getType());
        assertEquals("multiple", getStatusValue(t1));
        assertEquals(RedisTokenType.STATUS, t2.getType());
        assertEquals("multiple_token", getStatusValue(t2));
    }

    @Test
    void docsNonFlagValuesRemainBulkStrings() {
        Object[] args = new Object[]{GlideString.of("hello"), GlideString.of("world")};

        CommandCommand command = new CommandCommand(null);
        RedisToken token = command.toRedisTokenForDocs(args);

        assertEquals(RedisTokenType.ARRAY, token.getType());
        @SuppressWarnings("unchecked")
        Collection<RedisToken> arrayTokens = (Collection<RedisToken>) ((Object) ((AbstractRedisToken<?>) token).getValue());
        assertNotNull(arrayTokens);
        assertEquals(2, arrayTokens.size());

        var iterator = arrayTokens.iterator();
        RedisToken t0 = iterator.next();
        RedisToken t1 = iterator.next();

        assertEquals(RedisTokenType.STRING, t0.getType());
        assertEquals("hello", getStringValue(t0));
        assertEquals(RedisTokenType.STRING, t1.getType());
        assertEquals("world", getStringValue(t1));
    }

    @Test
    void docsMixedValuesHandledCorrectly() {
        Object[] mixed = new Object[]{GlideString.of("optional"), GlideString.of("regular_value")};

        CommandCommand command = new CommandCommand(null);
        RedisToken token = command.toRedisTokenForDocs(mixed);

        assertEquals(RedisTokenType.ARRAY, token.getType());
        @SuppressWarnings("unchecked")
        Collection<RedisToken> arrayTokens = (Collection<RedisToken>) ((Object) ((AbstractRedisToken<?>) token).getValue());
        assertNotNull(arrayTokens);
        assertEquals(2, arrayTokens.size());

        var iterator = arrayTokens.iterator();
        RedisToken t0 = iterator.next();
        RedisToken t1 = iterator.next();

        assertEquals(RedisTokenType.STATUS, t0.getType());
        assertEquals("optional", getStatusValue(t0));
        assertEquals(RedisTokenType.STRING, t1.getType());
        assertEquals("regular_value", getStringValue(t1));
    }

    private String getStatusValue(RedisToken token) {
        return ((AbstractRedisToken<String>) token).getValue();
    }

    private String getStringValue(RedisToken token) {
        Object value = ((AbstractRedisToken<?>) token).getValue();
        if (value instanceof com.github.tonivade.resp.protocol.SafeString ss) {
            return new String(ss.getBytes());
        }
        return value.toString();
    }

}
