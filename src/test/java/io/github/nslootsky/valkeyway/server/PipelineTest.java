/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipelining in {@link Connection}: async commands overlap, replies stay in order, barriers and
 * inline commands keep their place.
 */
class PipelineTest {

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(4);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> answered = new CopyOnWriteArrayList<>();
    private RespServer server;

    /** "ASYNC <tag> <delayMs>": completes after the delay, off the network thread, with the tag. */
    private final AsyncCommandHandler async = new AsyncCommandHandler() {
        @Override
        public boolean canRunAsync(Request request) {
            return true;
        }

        @Override
        public CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor) {
            String tag = request.argString(0);
            long delay = Long.parseLong(request.argString(1));
            events.add("start " + tag);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            CompletableFuture<Reply> done = new CompletableFuture<>();
            timer.schedule(() -> {
                inFlight.decrementAndGet();
                events.add("end " + tag);
                done.complete(Reply.bulk(tag));
            }, delay, TimeUnit.MILLISECONDS);
            return done.thenApplyAsync(r -> r, replyExecutor);
        }

        @Override
        public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
            batches.incrementAndGet();
            List<CompletableFuture<Reply>> each = requests.stream()
                    .map(r -> executeAsync(r, Runnable::run).toCompletableFuture()).toList();
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApplyAsync(v -> each.stream().map(CompletableFuture::join).toList(), replyExecutor);
        }

        @Override
        public Reply execute(Request request) {
            throw new AssertionError("async handler should not run synchronously");
        }
    };
    private final AtomicInteger batches = new AtomicInteger();

    private void start() throws Exception {
        CommandTable table = new CommandTable(async)
                .register("barrier", r -> {
                    events.add("barrier " + r.argString(0));
                    Thread.sleep(50);
                    return Reply.bulk("B" + r.argString(0));
                })
                .register("inline", (InlineCommandHandler) r -> Reply.bulk("I" + r.argString(0)))
                .listener((name, reply, nanos) -> answered.add(new String(name, StandardCharsets.US_ASCII)
                        + (reply instanceof Reply.Error ? "!" : "") + (nanos >= 0 ? "" : "?")));
        server = new RespServer(new RespServer.Settings("127.0.0.1", 0, 2, 100, 0), table);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        timer.shutdownNow();
    }

    private static String cmd(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            sb.append('$').append(p.length()).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    private static String bulk(String s) {
        return "$" + s.length() + "\r\n" + s + "\r\n";
    }

    private Socket send(String requests) throws Exception {
        Socket socket = new Socket("127.0.0.1", server.port());
        socket.setSoTimeout(20_000);
        socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    @Test
    void repliesStayInOrderWhenCompletionsArriveOutOfOrder() throws Exception {
        start();
        Random random = new Random(7);
        StringBuilder requests = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            requests.append(cmd("ASYNC", "r" + i, Integer.toString(random.nextInt(20))));
            expected.append(bulk("r" + i));
        }
        try (Socket socket = send(requests.toString())) {
            assertEquals(expected.toString(), read(socket.getInputStream(), expected.length()));
        }
        assertTrue(maxInFlight.get() > 50, "requests should overlap, max in flight was " + maxInFlight.get());
    }

    @Test
    void pipelinedRequestsRunConcurrently() throws Exception {
        start();
        StringBuilder requests = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            requests.append(cmd("ASYNC", "p" + i, "100"));
            expected.append(bulk("p" + i));
        }
        long start = System.nanoTime();
        try (Socket socket = send(requests.toString())) {
            assertEquals(expected.toString(), read(socket.getInputStream(), expected.length()));
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < 3000, "300 x 100 ms in a pipeline took " + ms + " ms; they should overlap");
    }

    @Test
    void aBarrierWaitsForEarlierCommandsAndHoldsBackLaterOnes() throws Exception {
        start();
        String requests = cmd("ASYNC", "a", "200") + cmd("BARRIER", "x") + cmd("ASYNC", "c", "0");
        String expected = bulk("a") + bulk("Bx") + bulk("c");
        try (Socket socket = send(requests)) {
            assertEquals(expected, read(socket.getInputStream(), expected.length()));
        }
        assertEquals(List.of("start a", "end a", "barrier x", "start c", "end c"), events);
    }

    @Test
    void onlyOneBackendUnitIsInFlightAtATime() throws Exception {
        start();
        // A slow single command first; everything arriving behind it must wait for it, then go as one batch.
        StringBuilder rest = new StringBuilder();
        StringBuilder expected = new StringBuilder(bulk("first"));
        for (int i = 0; i < 50; i++) {
            rest.append(cmd("ASYNC", "n" + i, "0"));
            expected.append(bulk("n" + i));
        }
        try (Socket socket = send(cmd("ASYNC", "first", "200"))) {
            Thread.sleep(50);
            socket.getOutputStream().write(rest.toString().getBytes(StandardCharsets.US_ASCII));
            assertEquals(expected.toString(), read(socket.getInputStream(), expected.length()));
        }
        assertEquals("start first", events.getFirst());
        assertEquals("end first", events.get(events.indexOf("start n0") - 1), "the batch waited for the first unit");
        assertEquals(1, batches.get(), "the 50 queued commands went as one batch");
    }

    @Test
    void inlineRepliesKeepTheirPlace() throws Exception {
        start();
        String requests = cmd("ASYNC", "slow", "150") + cmd("INLINE", "1") + cmd("ASYNC", "fast", "0") + cmd("INLINE", "2");
        String expected = bulk("slow") + bulk("I1") + bulk("fast") + bulk("I2");
        try (Socket socket = send(requests)) {
            assertEquals(expected, read(socket.getInputStream(), expected.length()));
        }
    }

    @Test
    void backpressureBoundsRequestsInFlight() throws Exception {
        start();
        StringBuilder requests = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        int count = Connection.PAUSE_AT * 5;
        for (int i = 0; i < count; i++) {
            requests.append(cmd("ASYNC", "b" + i, "30"));
            expected.append(bulk("b" + i));
        }
        try (Socket socket = send(requests.toString())) {
            assertEquals(expected.toString(), read(socket.getInputStream(), expected.length()));
        }
        assertTrue(maxInFlight.get() <= Connection.MAX_IN_FLIGHT,
                "max in flight " + maxInFlight.get() + " exceeds " + Connection.MAX_IN_FLIGHT);
        assertTrue(maxInFlight.get() > Connection.MAX_IN_FLIGHT / 2, "but the pipeline should still fill up");
    }

    @Test
    void closingWithRequestsInFlightIsSafe() throws Exception {
        start();
        StringBuilder requests = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            requests.append(cmd("ASYNC", "z" + i, "200"));
        }
        send(requests.toString()).close();
        Thread.sleep(400);
        // The server is still healthy for other clients.
        try (Socket socket = send(cmd("INLINE", "ok"))) {
            assertEquals(bulk("Iok"), read(socket.getInputStream(), bulk("Iok").length()));
        }
        assertTrue(server.clients().size() <= 1);
    }

    @Test
    void everyAnsweredCommandReachesTheListener() throws Exception {
        start();
        String requests = cmd("ASYNC", "a", "20") + cmd("INLINE", "1") + cmd("ASYNC", "b", "0") + cmd("ASYNC", "c", "0")
                + cmd("BARRIER", "x");
        String expected = bulk("a") + bulk("I1") + bulk("b") + bulk("c") + bulk("Bx");
        try (Socket socket = send(requests)) {
            assertEquals(expected, read(socket.getInputStream(), expected.length()));
        }
        assertEquals(List.of("ASYNC", "ASYNC", "ASYNC", "BARRIER", "INLINE"), answered.stream().sorted().toList(),
                "one call per command, with its name, whichever path ran it");
    }

    private static String read(InputStream in, int length) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
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
