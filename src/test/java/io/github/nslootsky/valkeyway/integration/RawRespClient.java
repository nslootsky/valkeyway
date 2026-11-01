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
 * <p>Replies map to: {@code +status} → {@link Status}, {@code -error} → {@link Error},
 * {@code :n} → {@link Long}, {@code $bulk} → {@link String}, {@code *array} → {@link List},
 * null bulk/array → {@code null}.
 */
public class RawRespClient implements AutoCloseable {

    public record Status(String value) {}

    public record Error(String message) {}

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;

    public RawRespClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(10_000);
        out = socket.getOutputStream();
        in = new BufferedInputStream(socket.getInputStream());
    }

    public Object call(String... args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.writeBytes(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            buf.writeBytes(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            buf.writeBytes(bytes);
            buf.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(buf.toByteArray());
        out.flush();
        return read();
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
                yield new String(data, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    yield null;
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
        return line.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
