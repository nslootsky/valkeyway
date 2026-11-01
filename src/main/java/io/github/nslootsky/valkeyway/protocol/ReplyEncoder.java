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
 * Writes replies directly into the outbound buffer: a {@link Reply} as RESP2, a {@link Resp3} as RESP3.
 */
@Sharable
public final class ReplyEncoder extends MessageToByteEncoder<Object> {

    /** A reply to a client speaking RESP3. */
    public record Resp3(Reply reply) {}

    private static final byte[] NULL = "_\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRUE = "#t\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALSE = "#f\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VERBATIM_TXT = "txt:".getBytes(StandardCharsets.US_ASCII);

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
    public boolean acceptOutboundMessage(Object msg) {
        return msg instanceof Reply || msg instanceof Resp3;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) {
        if (msg instanceof Resp3(Reply reply)) {
            write(reply, out, true);
        } else {
            write((Reply) msg, out, false);
        }
    }

    /** Appends the RESP2 encoding of {@code reply} to {@code out}. */
    public static void write(Reply reply, ByteBuf out) {
        write(reply, out, false);
    }

    /** Appends the RESP3 ({@code resp3}) or RESP2 encoding of {@code reply} to {@code out}. */
    public static void write(Reply reply, ByteBuf out, boolean resp3) {
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
            case Reply.Array(List<Reply> items) -> aggregate(out, '*', items.size(), items, resp3);
            case Reply.MapReply(List<Reply> kv) -> aggregate(out, resp3 ? '%' : '*', resp3 ? kv.size() / 2 : kv.size(), kv, resp3);
            case Reply.SetReply(List<Reply> items) -> aggregate(out, resp3 ? '~' : '*', items.size(), items, resp3);
            case Reply.Double(double value) -> number(out, resp3, DoubleFormat.valkey(value));
            case Reply.FormattedDouble(String text) -> number(out, resp3, text);
            case Reply.Bool(boolean value) -> out.writeBytes(resp3 ? (value ? TRUE : FALSE) : (value ? ONE : ZERO));
            case Reply.BigNumber(String digits) -> {
                if (resp3) {
                    line(out, '(', digits);
                } else {
                    bulk(out, digits.getBytes(StandardCharsets.US_ASCII));
                }
            }
            case Reply.Verbatim(String text) -> {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (resp3) {
                    out.writeByte('=');
                    writeLong(out, bytes.length + VERBATIM_TXT.length);
                    out.writeBytes(CRLF);
                    out.writeBytes(VERBATIM_TXT);
                    out.writeBytes(bytes);
                    out.writeBytes(CRLF);
                } else {
                    bulk(out, bytes);
                }
            }
            case Reply.FlatPairs(List<Reply> items) -> {
                if (resp3) {
                    header(out, '*', items.size() / 2);
                    for (int i = 0; i + 1 < items.size(); i += 2) {
                        header(out, '*', 2);
                        write(items.get(i), out, true);
                        write(items.get(i + 1), out, true);
                    }
                } else {
                    aggregate(out, '*', items.size(), items, false);
                }
            }
            case Reply.NestedMap(List<Reply> kv) -> {
                if (resp3) {
                    aggregate(out, '%', kv.size() / 2, kv, true);
                } else {
                    header(out, '*', kv.size() / 2);
                    for (int i = 0; i + 1 < kv.size(); i += 2) {
                        header(out, '*', 2);
                        write(kv.get(i), out, false);
                        write(kv.get(i + 1), out, false);
                    }
                }
            }
            case Reply.Null n -> out.writeBytes(resp3 ? NULL : n == Reply.Null.ARRAY ? NULL_ARRAY : NULL_BULK);
        }
    }

    private static void aggregate(ByteBuf out, char type, int count, List<Reply> items, boolean resp3) {
        header(out, type, count);
        for (Reply item : items) {
            write(item, out, resp3);
        }
    }

    private static void header(ByteBuf out, char type, int count) {
        out.writeByte(type);
        writeLong(out, count);
        out.writeBytes(CRLF);
    }

    /** A double: RESP3 {@code ,text}, RESP2 a bulk string. */
    private static void number(ByteBuf out, boolean resp3, String text) {
        if (resp3) {
            line(out, ',', text);
        } else {
            bulk(out, text.getBytes(StandardCharsets.US_ASCII));
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
