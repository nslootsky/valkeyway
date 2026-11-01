/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes {@link Reply} values as RESP2 directly into the outbound buffer.
 */
@Sharable
public final class ReplyEncoder extends MessageToByteEncoder<Reply> {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OK = "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUEUED = "+QUEUED\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PONG = "+PONG\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZERO = ":0\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ONE = ":1\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EMPTY_ARRAY = "*0\r\n".getBytes(StandardCharsets.US_ASCII);

    public static final ReplyEncoder INSTANCE = new ReplyEncoder();

    private ReplyEncoder() {}

    @Override
    protected void encode(ChannelHandlerContext ctx, Reply reply, ByteBuf out) {
        write(reply, out);
    }

    /** Appends the RESP2 encoding of {@code reply} to {@code out}. */
    public static void write(Reply reply, ByteBuf out) {
        if (reply == Reply.OK) {
            out.writeBytes(OK);
            return;
        }
        if (reply == Reply.QUEUED) {
            out.writeBytes(QUEUED);
            return;
        }
        if (reply == Reply.PONG) {
            out.writeBytes(PONG);
            return;
        }
        if (reply == Reply.ZERO) {
            out.writeBytes(ZERO);
            return;
        }
        if (reply == Reply.ONE) {
            out.writeBytes(ONE);
            return;
        }
        if (reply == Reply.EMPTY_ARRAY) {
            out.writeBytes(EMPTY_ARRAY);
            return;
        }
        switch (reply) {
            case Reply.Simple(String value) -> line(out, '+', value);
            case Reply.Error(String message) -> line(out, '-', message);
            case Reply.Int(long value) -> {
                out.writeByte(':');
                writeLong(out, value);
                out.writeBytes(CRLF);
            }
            case Reply.Bulk(byte[] value) -> bulk(out, value);
            case Reply.Array(List<Reply> items) -> array(out, items);
            case Reply.MapReply(List<Reply> keysAndValues) -> array(out, keysAndValues);
            case Reply.SetReply(List<Reply> items) -> array(out, items);
            case Reply.Double(double value) -> bulk(out, DoubleFormat.valkey(value).getBytes(StandardCharsets.US_ASCII));
            case Reply.Bool(boolean value) -> out.writeBytes(value ? ONE : ZERO);
            case Reply.BigNumber(String digits) -> bulk(out, digits.getBytes(StandardCharsets.US_ASCII));
            case Reply.Null n -> out.writeBytes(n == Reply.Null.ARRAY ? NULL_ARRAY : NULL_BULK);
        }
    }

    private static void array(ByteBuf out, List<Reply> items) {
        out.writeByte('*');
        writeLong(out, items.size());
        out.writeBytes(CRLF);
        for (Reply item : items) {
            write(item, out);
        }
    }

    private static void bulk(ByteBuf out, byte[] value) {
        out.writeByte('$');
        writeLong(out, value.length);
        out.writeBytes(CRLF);
        out.writeBytes(value);
        out.writeBytes(CRLF);
    }

    /** Simple strings and errors are single lines; CR/LF inside would corrupt the stream. */
    private static void line(ByteBuf out, char type, String text) {
        out.writeByte(type);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            out.writeByte(ch == '\r' || ch == '\n' ? ' ' : ch < 0x80 ? ch : '?');
        }
        out.writeBytes(CRLF);
    }

    static void writeLong(ByteBuf out, long value) {
        if (value >= 0 && value < 10) {
            out.writeByte('0' + (int) value);
            return;
        }
        if (value == Long.MIN_VALUE) {
            out.writeCharSequence("-9223372036854775808", StandardCharsets.US_ASCII);
            return;
        }
        if (value < 0) {
            out.writeByte('-');
            value = -value;
        }
        byte[] digits = new byte[19];
        int pos = digits.length;
        while (value > 0) {
            digits[--pos] = (byte) ('0' + (value % 10));
            value /= 10;
        }
        out.writeBytes(digits, pos, digits.length - pos);
    }
}
