/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * Decodes client requests into {@code byte[][]} (command name followed by arguments).
 *
 * <p>Accepts RESP arrays of bulk strings ({@code *2\r\n$3\r\nGET\r\n$1\r\nk\r\n}) and inline commands
 * ({@code GET k\r\n}). Progress through a partly received request is kept between reads, so a large or
 * fragmented request is never re-parsed. Limits match Valkey's defaults.
 */
public final class RespDecoder extends ByteToMessageDecoder {

    /** Valkey's PROTO_INLINE_MAX_SIZE; also bounds header lines. */
    static final int MAX_INLINE = 64 * 1024;
    /** Valkey's limit on arguments per request. */
    static final int MAX_ARGS = 1024 * 1024;
    /** Valkey's default proto-max-bulk-len. */
    static final long MAX_BULK = 512L * 1024 * 1024;

    // [upstream:V7]
    /** Valkey's limits on requests from clients that haven't authenticated (when a password is required). */
    static final int MAX_ARGS_UNAUTHENTICATED = 10;
    static final int MAX_BULK_UNAUTHENTICATED = 16 * 1024;

    /** Whether the client must still authenticate; set by the connection. Event loop only. */
    private boolean unauthenticated;

    public RespDecoder() {
        this(false);
    }

    public RespDecoder(boolean unauthenticated) {
        this.unauthenticated = unauthenticated;
    }

    /** Applies (or lifts) the limits for clients that haven't authenticated. Event loop only. */
    public void unauthenticated(boolean unauthenticated) {
        this.unauthenticated = unauthenticated;
    }

    /** Set after a protocol error: the stream can't be resynchronised, so input is discarded. */
    private boolean failed;

    // State of a partly decoded array request.
    private byte[][] args;
    private int argIndex;
    private int bulkLength = -1;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        try {
            decodeAvailable(in, out);
        } catch (ProtocolException e) {
            failed = true;
            in.skipBytes(in.readableBytes());
            throw e;
        }
    }

    private void decodeAvailable(ByteBuf in, List<Object> out) {
        while (in.isReadable()) {
            if (args == null && !startRequest(in, out)) {
                return;
            }
            if (args != null) {
                if (!readArguments(in)) {
                    return;
                }
                out.add(args);
                args = null;
            }
        }
    }

    /**
     * Reads a request header (or a whole inline request). Returns false if more bytes are needed.
     */
    private boolean startRequest(ByteBuf in, List<Object> out) {
        int start = in.readerIndex();
        if (in.getByte(start) != '*') {
            return readInline(in, out);
        }
        int eol = lineEnd(in, start);
        if (eol < 0) {
            if (in.readableBytes() > MAX_INLINE) {
                throw new ProtocolException("too big mbulk count string");
            }
            return false;
        }
        long count = parseLong(in, start + 1, eol, "invalid multibulk length");
        in.readerIndex(eol + 2);
        if (count > MAX_ARGS) {
            throw new ProtocolException("invalid multibulk length");
        }
        if (unauthenticated && count > MAX_ARGS_UNAUTHENTICATED) {
            throw new ProtocolException("unauthenticated multibulk length");
        }
        if (count <= 0) {
            return true; // "*0" / "*-1": nothing to run, as in Valkey
        }
        args = new byte[(int) count][];
        argIndex = 0;
        bulkLength = -1;
        return true;
    }

    private boolean readInline(ByteBuf in, List<Object> out) {
        int start = in.readerIndex();
        int eol = in.forEachByte(start, in.readableBytes(), ByteProcessor.FIND_LF);
        if (eol < 0) {
            if (in.readableBytes() > MAX_INLINE) {
                throw new ProtocolException("too big inline request");
            }
            return false;
        }
        int end = eol > start && in.getByte(eol - 1) == '\r' ? eol - 1 : eol;
        byte[] line = new byte[end - start];
        in.getBytes(start, line);
        in.readerIndex(eol + 1);
        byte[][] inline = InlineArgs.split(line);
        if (inline.length > 0) {
            out.add(inline);
        }
        return true;
    }

    /** Reads bulk-string arguments. Returns false if more bytes are needed. */
    private boolean readArguments(ByteBuf in) {
        while (argIndex < args.length) {
            if (bulkLength < 0) {
                if (!in.isReadable()) {
                    return false;
                }
                int start = in.readerIndex();
                byte type = in.getByte(start);
                if (type != '$') {
                    throw new ProtocolException("expected '$', got '" + (char) type + "'");
                }
                int eol = lineEnd(in, start);
                if (eol < 0) {
                    if (in.readableBytes() > MAX_INLINE) {
                        throw new ProtocolException("too big bulk count string");
                    }
                    return false;
                }
                long length = parseLong(in, start + 1, eol, "invalid bulk length");
                if (length < 0 || length > MAX_BULK) {
                    throw new ProtocolException("invalid bulk length");
                }
                if (unauthenticated && length > MAX_BULK_UNAUTHENTICATED) {
                    throw new ProtocolException("unauthenticated bulk length");
                }
                in.readerIndex(eol + 2);
                bulkLength = (int) length;
            }
            if (in.readableBytes() < bulkLength + 2) {
                return false;
            }
            byte[] value = new byte[bulkLength];
            in.readBytes(value);
            if (in.readByte() != '\r' || in.readByte() != '\n') {
                throw new ProtocolException("bulk string not terminated by CRLF");
            }
            args[argIndex++] = value;
            bulkLength = -1;
        }
        return true;
    }

    /**
     * Index of the CR ending a header line that starts at {@code start}, or -1 until both the CR and
     * the byte after it (the LF) have arrived. Like Valkey, the LF itself isn't checked.
     */
    private static int lineEnd(ByteBuf in, int start) {
        int cr = in.indexOf(start, in.writerIndex(), (byte) '\r');
        return cr >= 0 && cr + 1 < in.writerIndex() ? cr : -1;
    }

    private static long parseLong(ByteBuf in, int from, int to, String error) {
        if (from >= to || to - from > 20) {
            throw new ProtocolException(error);
        }
        boolean negative = in.getByte(from) == '-';
        int i = negative ? from + 1 : from;
        if (i >= to) {
            throw new ProtocolException(error);
        }
        long value = 0;
        for (; i < to; i++) {
            byte b = in.getByte(i);
            if (b < '0' || b > '9') {
                throw new ProtocolException(error);
            }
            value = value * 10 + (b - '0');
            if (value > MAX_BULK) { // larger than any count or length we accept; also stops overflow
                throw new ProtocolException(error);
            }
        }
        return negative ? -value : value;
    }
}
