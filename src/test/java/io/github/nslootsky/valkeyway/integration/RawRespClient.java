/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RESP2 client that exposes reply types exactly as they arrive on the wire,
 * so tests can check what plain (non-Glide) clients see.
 *
 * <p>Strings are byte strings: arguments and replies are converted with ISO-8859-1, which maps each
 * byte to one char, so binary data round-trips exactly (write {@code "\u00ff"} to send byte 0xFF).
 *
 * <p>Replies map to: {@code +status} → {@link Status}, {@code -error} → {@link Error},
 * {@code :n} → {@link Long}, {@code $bulk} → {@link String}, {@code *array} → {@link List},
 * null bulk ({@code $-1}) → {@code null}, null array ({@code *-1}) → {@link #NULL_ARRAY}.
 */
public class RawRespClient implements AutoCloseable {

    public record Status(String value) {}

    public record Error(String message) {}

    /** The RESP2 null array, {@code *-1}, as distinct from the null bulk string. */
    public enum Nil { ARRAY }

    public static final Nil NULL_ARRAY = Nil.ARRAY;

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;

    public RawRespClient(String host, int port) throws IOException {
        this(new Socket(host, port));
    }

    /** A client over an already connected socket (e.g. TLS). */
    public RawRespClient(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(10_000);
        out = socket.getOutputStream();
        in = new BufferedInputStream(socket.getInputStream());
    }

    public Object call(String... args) throws IOException {
        out.write(encode(args));
        out.flush();
        return read();
    }

    /** Sends a command without reading a reply (for CLIENT REPLY OFF and the like). */
    public void send(String... args) throws IOException {
        out.write(encode(args));
        out.flush();
    }

    /** Sends all commands at once, then reads one reply per command. */
    public List<Object> pipeline(List<String[]> commands) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (String[] command : commands) {
            buf.writeBytes(encode(command));
        }
        out.write(buf.toByteArray());
        out.flush();
        List<Object> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            replies.add(read());
        }
        return replies;
    }

    private static byte[] encode(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(("*" + args.length + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.ISO_8859_1);
            buf.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            buf.writeBytes(bytes);
            buf.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        return buf.toByteArray();
    }

    private Object read() throws IOException {
        int type = in.read();
        String line = readLine();
        return switch (type) {
            case '+' -> new Status(line);
            case '-' -> new Error(line);
            case ':' -> Long.parseLong(line);
            case '$' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    yield null;
                }
                byte[] data = in.readNBytes(len);
                readLine();
                yield new String(data, StandardCharsets.ISO_8859_1);
            }
            case '*' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    yield NULL_ARRAY;
                }
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < len; i++) {
                    items.add(read());
                }
                yield items;
            }
            default -> throw new IOException("Unexpected RESP type byte: " + type);
        };
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != '\r') {
            if (b < 0) {
                throw new IOException("Connection closed");
            }
            line.write(b);
        }
        in.read(); // '\n'
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
