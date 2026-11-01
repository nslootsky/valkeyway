/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.blocking.BlockingCommands;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One client connection: its session state and the queue of commands it has sent.
 *
 * <p>Commands run strictly in order, one at a time, on a virtual thread, so a handler may block (on
 * Glide, or in BLPOP) without holding up the network threads or any other connection. Session state
 * is only touched by that sequence of commands; the {@code running} flag's compare-and-set orders
 * successive drains, so the fields need no further synchronisation.
 */
public final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    /** Stop reading from the socket when this many commands are waiting... */
    static final int PAUSE_AT = 1000;
    /** ...and resume once the backlog is down to this. */
    static final int RESUME_AT = 250;
    /** Flush at least this often while working through a pipeline. */
    private static final int MAX_UNFLUSHED = 64;

    private final long id;
    private final Channel channel;
    private final CommandTable commands;
    private final Executor executor;

    private final ConcurrentLinkedQueue<byte[][]> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;
    private volatile boolean readPaused; // written on the event loop only
    private boolean closeAfterReply;

    // Session state, used only by this connection's commands.
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
        this.commands = commands;
        this.executor = executor;
    }

    // --- command execution ---

    /** Called on the event loop for each decoded command. */
    void enqueue(byte[][] command) {
        if (closed) {
            return;
        }
        inbox.add(command);
        if (queued.incrementAndGet() >= PAUSE_AT) {
            updateReading();
        }
        if (running.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        int unflushed = 0;
        try {
            while (true) {
                byte[][] command;
                while ((command = inbox.poll()) != null) {
                    if (closed) {
                        inbox.clear();
                        queued.set(0);
                        return;
                    }
                    if (unflushed > 0 && BlockingCommands.detect(command).isPresent()) {
                        // Earlier replies must reach the client before we block.
                        channel.flush();
                        unflushed = 0;
                    }
                    Reply reply = execute(command);
                    channel.write(reply);
                    unflushed++;
                    if (queued.decrementAndGet() == RESUME_AT) {
                        channel.eventLoop().execute(this::updateReading);
                    }
                    if (closeAfterReply) {
                        closed = true;
                        // The reply is already written; flush it, then close.
                        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                        unflushed = 0;
                        return;
                    }
                    if (inbox.isEmpty() || unflushed >= MAX_UNFLUSHED) {
                        channel.flush();
                        unflushed = 0;
                    }
                }
                running.set(false);
                // A command may have arrived between the last poll and clearing the flag.
                if (inbox.isEmpty() || !running.compareAndSet(false, true)) {
                    return;
                }
            }
        } finally {
            if (unflushed > 0) {
                channel.flush();
            }
        }
    }

    private Reply execute(byte[][] command) {
        Request request = new Request(this, command);
        try {
            Reply reply = commands.lookup(command[0]).execute(request);
            return reply != null ? reply : Reply.Null.BULK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            log.error("Unhandled error in {}", request.command(), e);
            return Reply.error("ERR " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Pause or resume reading from the socket. Event loop only. */
    void updateReading() {
        boolean pause = channel.isActive() && (queued.get() >= PAUSE_AT || !channel.isWritable());
        boolean resume = readPaused && channel.isWritable() && queued.get() <= RESUME_AT;
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

    /** Whether the client has commands queued or running (an idle timeout must not cut those off). */
    boolean isBusy() {
        return running.get() || queued.get() > 0;
    }

    void markClosed() {
        closed = true;
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
