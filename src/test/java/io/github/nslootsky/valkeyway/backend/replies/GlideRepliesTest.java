/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.replies;

import java.math.BigInteger;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.protocol.ReplyEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlideRepliesTest {

    private static String wire(Object glideValue) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReplyEncoder.write(GlideReplies.toReply(glideValue), buf);
            return buf.toString(StandardCharsets.ISO_8859_1);
        } finally {
            buf.release();
        }
    }

    @Test
    void bulkStringsStayBulkWhateverTheirContent() {
        // Values never become status replies, even ones that look like COMMAND DOCS flags or OK.
        assertEquals("$8\r\noptional\r\n", wire(GlideString.of("optional")));
        assertEquals("$2\r\nOK\r\n", wire(GlideString.of("OK")));
    }

    @Test
    void topLevelStringsAreStatusReplies() {
        // With binary output Glide returns bulk strings as GlideString; a top-level String is a status.
        assertEquals("+OK\r\n", wire("OK"));
        assertEquals("+string\r\n", wire("string"));
        assertEquals("+PONG\r\n", wire("PONG"));
    }

    @Test
    void multiLineTextIsABulkString() {
        // RESP3 verbatim strings (CLIENT LIST, CLUSTER INFO) also arrive as String.
        assertEquals("$6\r\na\nb\nc\n\r\n", wire("a\nb\nc\n"));
    }

    @Test
    void nestedStringsAreBulk() {
        // e.g. LCS IDX map keys, which Glide converts to String.
        assertEquals("*2\r\n$7\r\nmatches\r\n$3\r\nlen\r\n", wire(new Object[]{"matches", "len"}));
    }

    @Test
    void numbers() {
        assertEquals(":42\r\n", wire(42L));
        assertEquals(":5000000001\r\n", wire(5_000_000_001L));
        assertEquals("$3\r\n1.5\r\n", wire(1.5d));
        assertEquals(":1\r\n", wire(true));
        assertEquals("$20\r\n12345678901234567890\r\n", wire(new BigInteger("12345678901234567890")));
    }

    @Test
    void nullsAndCollections() {
        assertEquals("$-1\r\n", wire(null));
        assertEquals("*-1\r\n", wire(Reply.Null.ARRAY));
        assertEquals("*2\r\n:1\r\n$-1\r\n", wire(new Object[]{1L, null}));
        assertEquals("*1\r\n$1\r\na\r\n", wire(List.of(GlideString.of("a"))));
        assertEquals("*1\r\n$1\r\nm\r\n", wire(Set.of(GlideString.of("m"))));
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put(GlideString.of("f"), GlideString.of("v"));
        map.put(GlideString.of("g"), 2L);
        assertEquals("*4\r\n$1\r\nf\r\n$1\r\nv\r\n$1\r\ng\r\n:2\r\n", wire(map));
    }

    @Test
    void throwableInArrayBecomesErrorElement() {
        Object[] execResults = {"OK", new RuntimeException("WRONGTYPE: Operation against a key holding the wrong kind of value")};
        assertEquals("*2\r\n$2\r\nOK\r\n-WRONGTYPE Operation against a key holding the wrong kind of value\r\n",
                wire(execResults));
    }
}
