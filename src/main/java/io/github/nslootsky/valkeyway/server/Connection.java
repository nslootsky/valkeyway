/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.protocol.Reply;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * One client connection: its session state and the commands it has sent.
 *
 * <p>Replies always go back in request order, and commands take effect in request order, as on a
 * standalone server:
 * <ul>
 *   <li>Commands an {@link AsyncCommandHandler} can start go to the backend as one <em>unit</em> at a
 *       time: a single command, or (when several have queued up, i.e. a pipeline) one ordered batch
 *       of all of them. The next unit starts when the current one completes. (Glide does not keep the
 *       order of separately submitted requests, but it does keep order within a batch:
 *       [upstream:G4] (docs/UPSTREAM_ASSUMPTIONS.md).)</li>
 *   <li>{@link InlineCommandHandler} commands are answered on the spot.</li>
 *   <li>Everything else is a <em>barrier</em>: it waits until every earlier reply is ready, runs on a
 *       virtual thread (so it may block, e.g. in BLPOP or on Glide), and later commands wait for it.
 *       Barriers are the commands that read or change connection state (SELECT, MULTI/EXEC, CLIENT,
 *       WATCH) or that may block.</li>
 * </ul>
 *
 * <p>The queues and flags below are only touched on the connection's event loop. Session state is
 * read on the event loop when async commands start, and read or written on a virtual thread while a
 * barrier runs; a barrier never overlaps with anything else of the same connection, and the hand-offs
 * between the threads (executor submission, event-loop tasks) order those accesses.
 */
public final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    /** Stop reading from the socket when this many commands are queued or in flight... */
    static final int PAUSE_AT = 1000;
    /** ...and resume once the backlog is down to this. */
    static final int RESUME_AT = 250;
    /**
     * Most commands started but not yet answered, and the largest batch. One socket read can hold
     * thousands of small commands, so pausing reads alone doesn't bound this; decoded commands
     * beyond it wait unstarted.
     */
    static final int MAX_IN_FLIGHT = 1000;

    /** A reply position, in request order. */
    private static final class Slot {
        Reply reply;
        boolean done;
        boolean barrier;
        boolean closeAfter;
    }

    private final long id;
    private final Channel channel;
    private final EventLoop loop;
    private final CommandTable commands;
    private final Executor executor;

    // Event loop only.
    private final ArrayDeque<byte[][]> waiting = new ArrayDeque<>();
    private final ArrayDeque<Slot> slots = new ArrayDeque<>();
    private boolean barrierRunning;
    private boolean unitInFlight;
    private boolean pumping;
    private boolean flushScheduled;
    private volatile boolean readPaused;
    private volatile boolean closed;

    /** Set by QUIT while it runs as a barrier. */
    private volatile boolean closeAfterReply;

    // Session state.
    private int db;
    private String name;
    private boolean inTransaction;
    private List<byte[][]> transactionCommands = new ArrayList<>();
    private Set<Integer> transactionSlots = new HashSet<>();
    private String transactionError;
    private String scanCursorId;

    Connection(long id, Channel channel, CommandTable commands, Executor executor) {
        this.id = id;
        this.channel = channel;
        this.loop = channel.eventLoop();
        this.commands = commands;
        this.executor = executor;
    }

    // --- command execution (event loop) ---

    /** Called on the event loop for each decoded command. */
    void enqueue(byte[][] command) {
        if (closed) {
            return;
        }
        waiting.add(command);
        pump();
        updateReading();
    }

    /** Starts as many waiting commands as ordering allows. */
    private void pump() {
        if (pumping) {
            return; // re-entered from a synchronously completed command; the outer loop continues
        }
        pumping = true;
        try {
            while (!barrierRunning && !closed && slots.size() < MAX_IN_FLIGHT) {
                byte[][] command = waiting.peek();
                if (command == null) {
                    break;
                }
                Request request = new Request(this, command);
                CommandHandler handler = commands.lookup(command[0]);
                if (handler instanceof InlineCommandHandler) {
                    waiting.poll();
                    addInline(handler, request);
                } else if (handler instanceof AsyncCommandHandler async && async.canRunAsync(request)) {
                    if (unitInFlight) {
                        break; // the next unit starts when the current one completes
                    }
                    waiting.poll();
                    startUnit(async, request);
                } else if (slots.isEmpty()) {
                    waiting.poll();
                    startBarrier(handler, request);
                } else {
                    break; // a barrier waits for every earlier reply
                }
            }
        } finally {
            pumping = false;
        }
        writeCompleted();
    }

    private void addInline(CommandHandler handler, Request request) {
        Slot slot = new Slot();
        slot.reply = execute(handler, request);
        slot.done = true;
        slots.add(slot);
    }

    /**
     * Starts {@code first} plus every directly following command the same handler can start
     * asynchronously (inline commands in between are answered in place), as one unit.
     */
    private void startUnit(AsyncCommandHandler handler, Request first) {
        List<Request> requests = new ArrayList<>();
        List<Slot> unitSlots = new ArrayList<>();
        requests.add(first);
        Slot firstSlot = new Slot();
        unitSlots.add(firstSlot);
        slots.add(firstSlot);
        byte[][] next;
        while (slots.size() < MAX_IN_FLIGHT && (next = waiting.peek()) != null) {
            Request request = new Request(this, next);
            CommandHandler h = commands.lookup(next[0]);
            if (h == handler && handler.canRunAsync(request)) {
                waiting.poll();
                Slot slot = new Slot();
                requests.add(request);
                unitSlots.add(slot);
                slots.add(slot);
            } else if (h instanceof InlineCommandHandler) {
                waiting.poll();
                addInline(h, request);
            } else {
                break;
            }
        }
        unitInFlight = true;
        try {
            if (requests.size() == 1) {
                handler.executeAsync(first, loop).whenComplete((reply, error) ->
                        onLoop(() -> finishUnit(unitSlots, error, error == null ? List.of(orNull(reply)) : null)));
            } else {
                handler.executeBatchAsync(requests, loop).whenComplete((replies, error) ->
                        onLoop(() -> finishUnit(unitSlots, error, replies)));
            }
        } catch (Exception e) {
            log.error("Unhandled error starting {}", first.command(), e);
            finishUnit(unitSlots, e, null);
        }
    }

    private void finishUnit(List<Slot> unitSlots, Throwable error, List<Reply> replies) {
        unitInFlight = false;
        if (closed) {
            return;
        }
        for (int i = 0; i < unitSlots.size(); i++) {
            Slot slot = unitSlots.get(i);
            slot.reply = error != null || replies == null || i >= replies.size()
                    ? errorReply(error != null ? error : new IllegalStateException("missing reply"))
                    : orNull(replies.get(i));
            slot.done = true;
        }
        writeCompleted();
        pump();
        updateReading();
    }

    private void onLoop(Runnable task) {
        if (loop.inEventLoop()) {
            task.run();
        } else {
            loop.execute(task);
        }
    }

    private static Reply orNull(Reply reply) {
        return reply != null ? reply : Reply.Null.BULK;
    }

    private void startBarrier(CommandHandler handler, Request request) {
        Slot slot = new Slot();
        slot.barrier = true;
        slots.add(slot);
        barrierRunning = true;
        executor.execute(() -> {
            Reply reply = execute(handler, request);
            boolean close = closeAfterReply;
            loop.execute(() -> {
                slot.closeAfter = close;
                finish(slot, reply);
            });
        });
    }

    /** A command's reply is ready (event loop). */
    private void finish(Slot slot, Reply reply) {
        if (closed) {
            return;
        }
        slot.reply = reply;
        slot.done = true;
        if (slot.barrier) {
            barrierRunning = false;
        }
        writeCompleted();
        pump();
        updateReading();
    }

    /** Writes every finished reply at the head of the queue, in order. */
    private void writeCompleted() {
        boolean wrote = false;
        Slot head;
        while (!closed && (head = slots.peek()) != null && head.done) {
            slots.poll();
            channel.write(head.reply);
            wrote = true;
            if (head.closeAfter) {
                closed = true;
                waiting.clear();
                slots.clear();
                channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                return;
            }
        }
        if (wrote) {
            scheduleFlush();
        }
    }

    /**
     * Flushes once the event loop has run the tasks already queued, so replies that complete
     * together (a pipeline, a burst of backend responses) go out in one write.
     */
    private void scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            loop.execute(() -> {
                flushScheduled = false;
                channel.flush();
            });
        }
    }

    private Reply execute(CommandHandler handler, Request request) {
        try {
            Reply reply = handler.execute(request);
            return reply != null ? reply : Reply.Null.BULK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            log.error("Unhandled error in {}", request.command(), e);
            return errorReply(e);
        }
    }

    private static Reply errorReply(Throwable e) {
        Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
        return Reply.error("ERR " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    /** Pause or resume reading from the socket. Event loop only. */
    void updateReading() {
        int backlog = waiting.size() + slots.size();
        boolean pause = channel.isActive() && (backlog >= PAUSE_AT || !channel.isWritable());
        boolean resume = readPaused && channel.isWritable() && backlog <= RESUME_AT;
        if (pause && !readPaused) {
            readPaused = true;
            channel.config().setAutoRead(false);
        } else if (resume) {
            readPaused = false;
            channel.config().setAutoRead(true);
        }
    }

    /** Whether reading from this client is paused for backpressure (for tests and diagnostics). */
    boolean readingPaused() {
        return readPaused;
    }

    /** Whether the client has commands queued or running (an idle timeout must not cut those off). Event loop only. */
    boolean isBusy() {
        return !waiting.isEmpty() || !slots.isEmpty();
    }

    void markClosed() {
        closed = true;
        waiting.clear();
    }

    /** Close the connection once the current command's reply has been sent (QUIT). */
    public void closeAfterReply() {
        closeAfterReply = true;
    }

    // --- identity ---

    public long id() {
        return id;
    }

    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    // --- session state ---

    public int db() {
        return db;
    }

    public void db(int db) {
        this.db = db;
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    public void inTransaction(boolean inTransaction) {
        this.inTransaction = inTransaction;
    }

    public List<byte[][]> transactionCommands() {
        return transactionCommands;
    }

    public Set<Integer> transactionSlots() {
        return transactionSlots;
    }

    public String transactionError() {
        return transactionError;
    }

    public void transactionError(String error) {
        this.transactionError = error;
    }

    /** Leaves MULTI and forgets everything queued. */
    public void clearTransaction() {
        inTransaction = false;
        transactionCommands = new ArrayList<>();
        transactionSlots = new HashSet<>();
        transactionError = null;
    }

    public String scanCursorId() {
        return scanCursorId;
    }

    public void scanCursorId(String scanCursorId) {
        this.scanCursorId = scanCursorId;
    }
}
