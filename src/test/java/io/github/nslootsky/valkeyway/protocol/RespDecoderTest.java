/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.protocol;

import java.util.Random;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespDecoderTest {

    private static List<List<String>> decode(String... chunks) {
        EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());
        for (String chunk : chunks) {
            channel.writeInbound(Unpooled.copiedBuffer(chunk, StandardCharsets.ISO_8859_1));
        }
        List<List<String>> commands = new ArrayList<>();
        byte[][] cmd;
        while ((cmd = channel.readInbound()) != null) {
            commands.add(Arrays.stream(cmd).map(b -> new String(b, StandardCharsets.ISO_8859_1)).toList());
        }
        channel.finishAndReleaseAll();
        return commands;
    }

    private static String protocolError(String input) {
        EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());
        DecoderException e = assertThrows(DecoderException.class,
                () -> channel.writeInbound(Unpooled.copiedBuffer(input, StandardCharsets.ISO_8859_1)));
        // Later input (and the final decode on close) is discarded rather than failing again.
        channel.writeInbound(Unpooled.copiedBuffer("*1\r\n$4\r\nPING\r\n", StandardCharsets.ISO_8859_1));
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
        return assertInstanceOf(ProtocolException.class, e.getCause()).reply();
    }

    @Test
    void decodesArrayRequest() {
        assertEquals(List.of(List.of("SET", "k", "v")), decode("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n"));
    }

    @Test
    void decodesPipelinedRequestsInOneRead() {
        assertEquals(List.of(List.of("PING"), List.of("GET", "a"), List.of("GET", "b")),
                decode("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\na\r\n*2\r\n$3\r\nGET\r\n$1\r\nb\r\n"));
    }

    @Test
    void decodesRequestSplitAtEveryByte() {
        String request = "*2\r\n$3\r\nGET\r\n$5\r\nhello\r\n";
        String[] bytes = request.split("");
        assertEquals(List.of(List.of("GET", "hello")), decode(bytes));
    }

    @Test
    void keepsBinaryAndEmbeddedCrlf() {
        String value = "a\r\nb\u0000ÿ";
        assertEquals(List.of(List.of("SET", "k", value)),
                decode("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$" + value.length() + "\r\n" + value + "\r\n"));
    }

    @Test
    void decodesEmptyBulkString() {
        assertEquals(List.of(List.of("SET", "k", "")), decode("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$0\r\n\r\n"));
    }

    @Test
    void skipsEmptyAndNullArrays() {
        assertEquals(List.of(List.of("PING")), decode("*0\r\n*-1\r\n*1\r\n$4\r\nPING\r\n"));
    }

    @Test
    void decodesLargeValueArrivingInPieces() {
        String big = "x".repeat(200_000);
        String request = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$200000\r\n" + big + "\r\n";
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < request.length(); i += 8192) {
            chunks.add(request.substring(i, Math.min(request.length(), i + 8192)));
        }
        List<List<String>> decoded = decode(chunks.toArray(String[]::new));
        assertEquals(1, decoded.size());
        assertEquals(big, decoded.getFirst().get(2));
    }

    @Test
    void inlineCommandsFollowValkeyQuoting() {
        assertEquals(List.of(List.of("PING")), decode("PING\r\n"));
        assertEquals(List.of(List.of("PING")), decode("PING\n"));
        assertEquals(List.of(List.of("set", "k", "v")), decode("  set   k    v  \r\n"));
        assertEquals(List.of(List.of("SET", "k", "hello world")), decode("SET k \"hello world\"\r\n"));
        assertEquals(List.of(List.of("SET", "k", "a\nbA\"")), decode("SET k \"a\\nb\\x41\\\"\"\r\n"));
        assertEquals(List.of(List.of("SET", "k", "it's")), decode("SET k 'it\\'s'\r\n"));
        assertEquals(List.of(List.of("SET", "k", "")), decode("SET k \"\"\r\n"));
    }

    /** Valkey 9: a closing quote ends the quoted part, not the argument (Valkey's "Mixing quoted and unquoted strings"). */
    @Test
    void quotedAndUnquotedPartsJoinUp() {
        assertEquals(List.of(List.of("set", "test-key", "test-value")),
                decode("set \"\"\"tes\"t-'k'e\"y\" \"test\"'-'value\r\n"));
        assertEquals(List.of(List.of("SET", "k", "xy")), decode("SET k \"x\"y\r\n"));
        assertEquals(List.of(List.of("SET", "k", "ab")), decode("SET k 'a'b\r\n"));
    }

    @Test
    void unbalancedInlineQuotesAreProtocolErrors() {
        String error = "ERR Protocol error: unbalanced quotes in request";
        assertEquals(error, protocolError("SET k \"unterminated\r\n"));
        assertEquals(error, protocolError("set foo 'b'a'r\r\n"));
        assertEquals(error, protocolError("set foo \"b\"a\"r\r\n"));
    }

    @Test
    void randomCommandsSurviveRandomSplitting() {
        Random random = new Random(42);
        List<List<String>> expected = new ArrayList<>();
        StringBuilder stream = new StringBuilder();
        for (int c = 0; c < 500; c++) {
            int argc = 1 + random.nextInt(6);
            List<String> cmd = new ArrayList<>();
            stream.append('*').append(argc).append("\r\n");
            for (int a = 0; a < argc; a++) {
                StringBuilder arg = new StringBuilder();
                int len = random.nextInt(random.nextBoolean() ? 10 : 3000);
                for (int i = 0; i < len; i++) {
                    arg.append((char) random.nextInt(256)); // any byte, including CR/LF
                }
                cmd.add(arg.toString());
                stream.append('$').append(len).append("\r\n").append(arg).append("\r\n");
            }
            expected.add(cmd);
        }
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < stream.length(); ) {
            int size = 1 + random.nextInt(random.nextBoolean() ? 7 : 5000);
            chunks.add(stream.substring(i, Math.min(stream.length(), i + size)));
            i += size;
        }
        assertEquals(expected, decode(chunks.toArray(String[]::new)));
    }

    @Test
    void emptyInlineLinesAreIgnored() {
        assertEquals(List.of(List.of("PING")), decode("\r\n  \r\nPING\r\n"));
    }

    @Test
    void rejectsMalformedRequests() {
        assertEquals("ERR Protocol error: invalid multibulk length", protocolError("*x\r\n"));
        assertEquals("ERR Protocol error: invalid multibulk length", protocolError("*9999999\r\n"));
        assertEquals("ERR Protocol error: expected '$', got ':'", protocolError("*1\r\n:3\r\n"));
        assertEquals("ERR Protocol error: invalid bulk length", protocolError("*1\r\n$-5\r\n"));
        assertEquals("ERR Protocol error: invalid bulk length", protocolError("*1\r\n$999999999999\r\n"));
        assertEquals("ERR Protocol error: invalid CRLF in request", protocolError("*1\r\n$1\r\nab\r\n"));
    }

    /** Valkey 9's "Check CRLF when parsing the querybuf": every CR must be followed by LF. */
    @Test
    void requiresLfAfterEveryCr() {
        String error = "ERR Protocol error: invalid CRLF in request";
        assertEquals("ERR Protocol error: invalid multibulk length", protocolError("*3x\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"));
        assertEquals(error, protocolError("*3\rx$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"));
        assertEquals("ERR Protocol error: invalid multibulk length", protocolError("*3xx$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"));
        assertEquals(error, protocolError("*3\r\n$3\rxSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"));
        assertEquals(error, protocolError("*3\r\n$3\r\nSETxx$3\r\nkey\r\n$5\r\nvalue\r\n"));
    }

    @Test
    void rejectsOversizedInlineRequest() {
        assertEquals("ERR Protocol error: too big inline request", protocolError("x".repeat(70_000)));
    }
}
