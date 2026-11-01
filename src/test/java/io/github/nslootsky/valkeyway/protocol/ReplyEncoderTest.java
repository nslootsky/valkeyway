/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplyEncoderTest {

    private static String wire(Reply reply) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReplyEncoder.write(reply, buf);
            return buf.toString(StandardCharsets.ISO_8859_1);
        } finally {
            buf.release();
        }
    }

    @Test
    void simpleTypes() {
        assertEquals("+OK\r\n", wire(Reply.OK));
        assertEquals("+QUEUED\r\n", wire(Reply.QUEUED));
        assertEquals("+string\r\n", wire(new Reply.Simple("string")));
        assertEquals("-WRONGTYPE Operation against a key\r\n", wire(Reply.error("WRONGTYPE Operation against a key")));
        assertEquals("$5\r\nhello\r\n", wire(Reply.bulk("hello")));
        assertEquals("$0\r\n\r\n", wire(new Reply.Bulk(new byte[0])));
        assertEquals("$-1\r\n", wire(Reply.Null.BULK));
        assertEquals("*-1\r\n", wire(Reply.Null.ARRAY));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 7, 10, 42, -1, -42, 2147483648L, 5000000001L, Long.MAX_VALUE, Long.MIN_VALUE})
    void integersUseTheFull64BitRange(long value) {
        assertEquals(":" + value + "\r\n", wire(Reply.integer(value)));
    }

    @Test
    void binaryBulkIsWrittenVerbatim() {
        byte[] value = {(byte) 0xff, 0, '\r', '\n', 'x'};
        assertEquals("$5\r\nÿ\u0000\r\nx\r\n", wire(new Reply.Bulk(value)));
    }

    @Test
    void nestedArrays() {
        Reply reply = Reply.array(Reply.bulk("a"), Reply.array(Reply.integer(1), Reply.Null.BULK), Reply.EMPTY_ARRAY);
        assertEquals("*3\r\n$1\r\na\r\n*2\r\n:1\r\n$-1\r\n*0\r\n", wire(reply));
    }

    @Test
    void resp3TypesAreDowngradedLikeARespTwoServer() {
        assertEquals("*4\r\n$1\r\nf\r\n$1\r\nv\r\n$1\r\ng\r\n$1\r\nw\r\n",
                wire(new Reply.MapReply(List.of(Reply.bulk("f"), Reply.bulk("v"), Reply.bulk("g"), Reply.bulk("w")))));
        assertEquals("*1\r\n$1\r\nm\r\n", wire(new Reply.SetReply(List.of(Reply.bulk("m")))));
        assertEquals("$3\r\n1.5\r\n", wire(new Reply.Double(1.5)));
        assertEquals("$5\r\n1e+20\r\n", wire(new Reply.Double(1e20)));
        assertEquals("$3\r\ninf\r\n", wire(new Reply.Double(java.lang.Double.POSITIVE_INFINITY)));
        assertEquals(":1\r\n", wire(new Reply.Bool(true)));
        assertEquals(":0\r\n", wire(new Reply.Bool(false)));
        assertEquals("$20\r\n12345678901234567890\r\n", wire(new Reply.BigNumber("12345678901234567890")));
    }

    @Test
    void lineBreaksInStatusAndErrorAreNeutralised() {
        assertEquals("-ERR bad  input\r\n", wire(Reply.error("ERR bad\r\ninput")));
        assertEquals("+a b\r\n", wire(new Reply.Simple("a\nb")));
    }
}
