/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.integration.RawRespClient;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import io.github.nslootsky.valkeyway.protocol.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server on its own, with test handlers and real sockets (no cluster needed).
 */
class RespServerTest {

    private RespServer server;

    /** ECHO-like default: replies with the command's arguments as an array. */
    private static Reply echoArgs(Request request) {
        Reply[] items = new Reply[request.argCount()];
        for (int i = 0; i < items.length; i++) {
            items[i] = new Reply.Bulk(request.arg(i));
        }
        return Reply.array(items);
    }

    private RespServer start(int maxClients, long idleTimeoutSeconds) throws Exception {
        CommandTable table = new CommandTable(RespServerTest::echoArgs)
                .register("ping", r -> Reply.PONG)
                .register("sleep", r -> {
                    Thread.sleep(Long.parseLong(r.argString(0)));
                    return Reply.OK;
                })
                .register("blpop", r -> { // stands in for a real blocking command
                    Thread.sleep(1500);
                    return Reply.Null.ARRAY;
                })
                .register("id", r -> Reply.integer(r.connection().id()))
                .register("big", r -> Reply.integer(5_000_000_001L))
                .register("quit", r -> {
                    r.connection().closeAfterReply();
                    return Reply.OK;
                })
                .register("boom", r -> {
                    throw new IllegalStateException("kaboom");
                });
        server = new RespServer(new RespServer.Settings("127.0.0.1", 0, 2, maxClients, idleTimeoutSeconds), table);
        server.start();
        return server;
    }

    private RawRespClient client() throws Exception {
        return new RawRespClient("127.0.0.1", server.port());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void handlesBasicCommands() throws Exception {
        start(100, 0);
        try (RawRespClient c = client()) {
            assertEquals(new Status("PONG"), c.call("PING"));
            assertEquals(List.of("a", "b"), c.call("ANYTHING", "a", "b"));
            assertEquals(5_000_000_001L, c.call("BIG"));
            assertEquals(new Error("ERR kaboom"), c.call("BOOM"));
            assertEquals(new Status("PONG"), c.call("PING"), "connection survives a handler error");
        }
    }

    @Test
    void pipelinedRepliesKeepRequestOrder() throws Exception {
        start(100, 0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            StringBuilder pipeline = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                if (i % 500 == 0) {
                    pipeline.append("*2\r\n$5\r\nSLEEP\r\n$2\r\n20\r\n");
                }
                String n = Integer.toString(i);
                pipeline.append("*2\r\n$1\r\nE\r\n$").append(n.length()).append("\r\n").append(n).append("\r\n");
            }
            out.write(pipeline.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                if (i % 500 == 0) {
                    expected.append("+OK\r\n");
                }
                String n = Integer.toString(i);
                expected.append("*1\r\n$").append(n.length()).append("\r\n").append(n).append("\r\n");
            }
            assertEquals(expected.toString(), readExactly(socket.getInputStream(), expected.length()));
        }
    }

    @Test
    void aBlockedClientDoesNotDelayOtherClients() throws Exception {
        start(100, 0);
        CompletableFuture<Object> blocked = CompletableFuture.supplyAsync(() -> {
            try (RawRespClient c = client()) {
                return c.call("SLEEP", "2000");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        // Many connections land on the same two I/O threads as the blocked one; none may wait.
        for (int i = 0; i < 20; i++) {
            try (RawRespClient c = client()) {
                long start = System.nanoTime();
                assertEquals(new Status("PONG"), c.call("PING"));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500);
            }
        }
        assertFalse(blocked.isDone());
        assertEquals(new Status("OK"), blocked.get(5, TimeUnit.SECONDS));
    }

    @Test
    void repliesBeforeABlockingCommandAreSentImmediately() throws Exception {
        start(100, 0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            // Pipelined PING then BLPOP: like Valkey, PING's reply goes out before the client blocks.
            socket.getOutputStream().write(("*1\r\n$4\r\nPING\r\n*3\r\n$5\r\nBLPOP\r\n$1\r\nk\r\n$1\r\n2\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            long start = System.nanoTime();
            assertEquals("+PONG\r\n", readExactly(socket.getInputStream(), 7));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000,
                    "PONG should arrive before the BLPOP finishes");
            assertEquals("*-1\r\n", readExactly(socket.getInputStream(), 5));
        }
    }

    @Test
    void clientIdsAreUniqueAndStablePerConnection() throws Exception {
        start(100, 0);
        try (RawRespClient a = client(); RawRespClient b = client()) {
            Object idA = a.call("ID");
            assertEquals(idA, a.call("ID"));
            assertNotEquals(idA, b.call("ID"));
        }
    }

    @Test
    void quitRepliesThenCloses() throws Exception {
        start(100, 0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write("*1\r\n$4\r\nQUIT\r\n*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            assertEquals("+OK\r\n", readExactly(in, 5));
            assertEquals(-1, in.read(), "connection closed, PING after QUIT not run");
        }
    }

    @Test
    void protocolErrorRepliesThenCloses() throws Exception {
        start(100, 0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write("*1\r\n:oops\r\n".getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String expected = "-ERR Protocol error: expected '$', got ':'\r\n";
            assertEquals(expected, readExactly(in, expected.length()));
            assertEquals(-1, in.read());
        }
    }

    @Test
    void inlineCommandsWork() throws Exception {
        start(100, 0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            assertEquals("+PONG\r\n", readExactly(socket.getInputStream(), 7));
        }
    }

    @Test
    void enforcesMaxClients() throws Exception {
        start(2, 0);
        try (RawRespClient a = client(); RawRespClient b = client()) {
            assertEquals(new Status("PONG"), a.call("PING"));
            assertEquals(new Status("PONG"), b.call("PING"));
            try (Socket third = new Socket("127.0.0.1", server.port())) {
                String expected = "-ERR max number of clients reached\r\n";
                assertEquals(expected, readExactly(third.getInputStream(), expected.length()));
                assertEquals(-1, third.getInputStream().read());
            }
        }
    }

    @Test
    void idleTimeoutClosesIdleButNotBusyClients() throws Exception {
        start(100, 1);
        try (Socket idle = new Socket("127.0.0.1", server.port()); RawRespClient busy = client()) {
            idle.setSoTimeout(5000);
            assertEquals(new Status("OK"), busy.call("SLEEP", "2500"), "a command running past the timeout is not cut off");
            assertEquals(-1, idle.getInputStream().read(), "idle client closed");
        }
    }

    @Test
    void noIdleTimeoutByDefault() throws Exception {
        start(100, 0);
        try (RawRespClient c = client()) {
            Thread.sleep(1500);
            assertEquals(new Status("PONG"), c.call("PING"));
        }
    }

    @Test
    void readingPausesWhileAClientIsNotReadingReplies() throws Exception {
        start(100, 0);
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            // ~10 KB replies that nobody reads: once replies back up past the write high-water mark
            // the server must stop reading requests rather than buffer without limit.
            String value = "v".repeat(10_000);
            byte[] cmd = ("*2\r\n$1\r\nE\r\n$10000\r\n" + value + "\r\n").getBytes(StandardCharsets.US_ASCII);
            int count = 3000;
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < count; i++) {
                        out.write(cmd);
                    }
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Connection connection = awaitClient();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!connection.readingPaused() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(connection.readingPaused(), "server should pause reading while replies back up");

            // Reading the replies lets everything through, in order, and reading resumes.
            InputStream in = socket.getInputStream();
            byte[] reply = ("*1\r\n$10000\r\n" + value + "\r\n").getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < count; i++) {
                assertArrayEquals(reply, in.readNBytes(reply.length), "reply " + i);
            }
            writer.get(10, TimeUnit.SECONDS);
            assertFalse(connection.readingPaused());
        }
    }

    private Connection awaitClient() throws InterruptedException {
        for (int i = 0; i < 200 && server.clients().isEmpty(); i++) {
            Thread.sleep(10);
        }
        return server.clients().iterator().next();
    }

    private static String readExactly(InputStream in, int length) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (buf.size() < length) {
            int n = in.read(chunk, 0, Math.min(chunk.length, length - buf.size()));
            if (n < 0) {
                break;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }
}
