/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletionException;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.protocol.ReplyEncoder;
import io.github.nslootsky.valkeyway.protocol.RespDecoder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final class Slot {
        final byte[] name;
        final long started = System.nanoTime();
        Reply reply;
        boolean done;
        boolean barrier;
        boolean closeAfter;
        /**
         * Whether to send the reply as RESP3: the protocol when the command ran, as on a server, which
         * encodes each reply as it executes. Commands that may switch it (HELLO, RESET) are inline or
         * barriers and take it again after running.
         */
        boolean resp3 = protocol == 3;
        /** A Pub/Sub message rather than a reply: never dropped by CLIENT REPLY. */
        boolean push;
        long pushBytes;

        Slot(byte[] name) {
            this.name = name;
        }
    }

    private final long id;
    private final Channel channel;
    private final EventLoop loop;
    private final CommandTable commands;
    private final Executor executor;
    private final CommandListener listener;
    private final ClientRegistry registry;
    private final long createdNanos = System.nanoTime();
    private volatile long lastCommandNanos = createdNanos;
    private volatile byte[] lastCommand;
    private final AtomicLong totalCommands = new AtomicLong();
    private volatile ReplyMode replyMode = ReplyMode.ON;
    /** Replies still to drop for CLIENT REPLY SKIP (its own and the next command's). Event loop only. */
    private int skipReplies;
    private volatile String libName = "";
    private volatile String libVersion = "";
    private volatile boolean noEvict;
    private volatile boolean noTouch;

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
    /** The authenticated user, or {@code null} before AUTH when a password is required. */
    private volatile String user;
    /** RESP version, 2 or 3 (HELLO). */
    private volatile int protocol = 2;
    /** Whether the client has Pub/Sub subscriptions. */
    private volatile boolean subscribed;
    /** The keys of the blocking command the client waits in (BLPOP, XREAD BLOCK, ...), or {@code null}. */
    private volatile List<byte[]> blockedOn;
    /** Whether that command is also unblocked by its key being deleted (XREADGROUP). */
    private volatile boolean blockedOnNoKey;
    /** Ends the client's blocking command early (CLIENT UNBLOCK): true = with an error, false = as a timeout. */
    private volatile java.util.function.Consumer<Boolean> unblocker;
    /** Pushed messages waiting behind replies or for the socket, and their payload bytes. Event loop only. */
    private int pendingPushes;
    private long pendingPushBytes;
    private boolean inTransaction;
    /** State kept by command handlers; see {@link ConnectionKey}. Written by barriers, read anywhere. */
    private final Map<ConnectionKey<?>, Object> attributes = new ConcurrentHashMap<>(4);

    Connection(long id, Channel channel, CommandTable commands, Executor executor, ClientRegistry registry,
               boolean authRequired) {
        this.id = id;
        this.user = authRequired ? null : Users.DEFAULT;
        this.registry = registry;
        this.channel = channel;
        this.loop = channel.eventLoop();
        this.commands = commands;
        this.executor = executor;
        this.listener = commands.listener();
    }

    // --- command execution (event loop) ---

    /** Called on the event loop for each decoded command. */
    void enqueue(byte[][] command) {
        if (closed) {
            return;
        }
        waiting.add(command);
        lastCommandNanos = System.nanoTime();
        lastCommand = command[0];
        totalCommands.incrementAndGet();
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
                CommandHandler handler = commands.lookup(command[0], inTransaction, user != null, subscribed && protocol == 2);
                if (handler instanceof InlineCommandHandler) {
                    waiting.poll();
                    addInline(handler, request);
                } else if (handler instanceof AsyncCommandHandler async && async.canRunAsync(request)) {
                    if (unitInFlight) {
                        break; // the next unit starts when the current one completes
                    }
                    waiting.poll();
                    startUnit(async, request);
                } else {
                    // A barrier waits for every earlier reply. Write those that are ready (inline
                    // replies just added): nothing else would come back here to start it.
                    writeCompleted();
                    if (closed || !slots.isEmpty()) {
                        break; // an earlier unit's completion pumps again
                    }
                    waiting.poll();
                    startBarrier(handler, request);
                }
            }
        } finally {
            pumping = false;
        }
        writeCompleted();
    }

    private void addInline(CommandHandler handler, Request request) {
        Slot slot = new Slot(request.args()[0]);
        slots.add(slot);
        Reply reply = execute(handler, request);
        slot.resp3 = protocol == 3;
        complete(slot, reply);
    }

    /** Records a slot's reply and tells the listener. Event loop only. */
    private void complete(Slot slot, Reply reply) {
        slot.reply = reply;
        slot.done = true;
        listener.answered(slot.name, reply, System.nanoTime() - slot.started);
    }

    /**
     * Starts {@code first} plus every directly following command the same handler can start
     * asynchronously (inline commands in between are answered in place), as one unit.
     */
    private void startUnit(AsyncCommandHandler handler, Request first) {
        List<Request> requests = new ArrayList<>();
        List<Slot> unitSlots = new ArrayList<>();
        requests.add(first);
        Slot firstSlot = new Slot(first.args()[0]);
        unitSlots.add(firstSlot);
        slots.add(firstSlot);
        byte[][] next;
        while (slots.size() < MAX_IN_FLIGHT && (next = waiting.peek()) != null) {
            Request request = new Request(this, next);
            CommandHandler h = commands.lookup(next[0], inTransaction, user != null, subscribed && protocol == 2);
            if (h == handler && handler.canRunAsync(request)) {
                waiting.poll();
                Slot slot = new Slot(next[0]);
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
            complete(unitSlots.get(i), error != null || replies == null || i >= replies.size()
                    ? errorReply(error != null ? error : new IllegalStateException("missing reply"))
                    : orNull(replies.get(i)));
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
        Slot slot = new Slot(request.args()[0]);
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
        if (slot.barrier) {
            slot.resp3 = protocol == 3;
        }
        complete(slot, reply);
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
            if (head.push) {
                pendingPushes--;
                pendingPushBytes -= head.pushBytes;
            }
            if (head.push || isPush(head.reply) || sendReply()) {
                channel.write(head.resp3 ? new ReplyEncoder.Resp3(head.reply) : head.reply);
                wrote = true;
            }
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
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        return Reply.error("ERR " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    /** Pause or resume reading from the socket. Event loop only. */
    void updateReading() {
        int backlog = waiting.size() + slots.size() - pendingPushes;
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
        // A blocking command must not outlive its client: it would hold a pooled backend client, and
        // pop a value nobody receives. Ending it may call the cluster, so not on the event loop.
        var hook = unblocker;
        if (hook != null) {
            Thread.ofVirtual().start(() -> hook.accept(false));
        }
        for (ConnectionKey<?> key : List.copyOf(attributes.keySet())) {
            if (attributes.remove(key) instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.warn("Failed to release {} of a closed connection", key, e);
                }
            }
        }
    }

    /**
     * Pub/Sub confirmations ((P|S)(UN)SUBSCRIBE), like messages, are sent even with CLIENT REPLY OFF,
     * as Valkey sends everything it pushes. [upstream:V8]
     */
    private static boolean isPush(Reply reply) {
        return reply instanceof Reply.Push
                || (reply instanceof Reply.Multi(List<Reply> replies) && !replies.isEmpty() && replies.getFirst() instanceof Reply.Push);
    }

    /** Whether to send the next reply, per CLIENT REPLY. Event loop only. */
    private boolean sendReply() {
        if (skipReplies > 0) {
            skipReplies--;
            return false;
        }
        return replyMode == ReplyMode.ON;
    }

    /** CLIENT REPLY modes. */
    public enum ReplyMode { ON, OFF, SKIP }

    /**
     * CLIENT REPLY, called while that command runs: OFF drops every reply from here on, its own
     * included; SKIP drops its own reply and the next command's; ON sends replies again.
     */
    public void replyMode(ReplyMode mode) {
        onLoop(() -> {
            if (mode == ReplyMode.SKIP) {
                skipReplies = 2;
                replyMode = ReplyMode.ON;
            } else {
                skipReplies = 0;
                replyMode = mode;
            }
        });
    }

    /** Closes the connection now (CLIENT KILL). Safe from any thread. */
    public void kill() {
        channel.close();
    }

    public ClientRegistry registry() {
        return registry;
    }

    /** Milliseconds since the connection was accepted. */
    public long ageMillis() {
        return (System.nanoTime() - createdNanos) / 1_000_000;
    }

    /** Milliseconds since the client last sent a command. */
    public long idleMillis() {
        return (System.nanoTime() - lastCommandNanos) / 1_000_000;
    }

    /** The name of the last command the client sent, or {@code null}. */
    public byte[] lastCommand() {
        return lastCommand;
    }

    public long totalCommands() {
        return totalCommands.get();
    }

    public String libName() {
        return libName;
    }

    public void libName(String libName) {
        this.libName = libName;
    }

    public String libVersion() {
        return libVersion;
    }

    public void libVersion(String libVersion) {
        this.libVersion = libVersion;
    }

    public boolean noEvict() {
        return noEvict;
    }

    public void noEvict(boolean noEvict) {
        this.noEvict = noEvict;
    }

    public boolean noTouch() {
        return noTouch;
    }

    public void noTouch(boolean noTouch) {
        this.noTouch = noTouch;
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

    /** Most pushed messages queued for one client before it is disconnected (Valkey: client-output-buffer-limit pubsub). */
    static final int MAX_PENDING_PUSHES = 100_000;
    /** ...or queued bytes not yet written to its socket. */
    static final long MAX_PENDING_PUSH_BYTES = 32L * 1024 * 1024;

    /**
     * Sends a Pub/Sub message. Safe from any thread. It goes out after the replies to every command
     * already received (so a SUBSCRIBE's confirmation always precedes its first message), in the
     * protocol the client speaks then. A client that doesn't keep up is disconnected.
     */
    public void push(Reply.Push message) {
        onLoop(() -> {
            if (closed) {
                return;
            }
            var outbound = channel.unsafe().outboundBuffer();
            long bytes = payloadBytes(message);
            // Both queues count: pushes held behind a slow command (BLPOP in RESP3, ...) and those
            // the socket hasn't taken yet.
            long queued = pendingPushBytes + bytes + (outbound != null ? outbound.totalPendingWriteBytes() : 0);
            if (pendingPushes >= MAX_PENDING_PUSHES || queued > MAX_PENDING_PUSH_BYTES) {
                log.warn("Disconnecting client {}: it isn't reading its Pub/Sub messages fast enough", id);
                closed = true;
                waiting.clear();
                slots.clear();
                channel.close();
                return;
            }
            Slot slot = new Slot(null);
            slot.reply = message;
            slot.done = true;
            slot.push = true;
            slot.pushBytes = bytes;
            slots.add(slot);
            pendingPushes++;
            pendingPushBytes += bytes;
            writeCompleted();
        });
    }

    /** Whether the client connected over TLS. */
    public boolean tls() {
        return channel.pipeline().get(SslHandler.class) != null;
    }

    /** Whether the client is in a blocking command, for INFO's blocked_clients and CLIENT LIST. */
    public boolean blocked() {
        return blockedOn != null;
    }

    /** The keys the client is blocked on (for INFO's total_blocking_keys), or {@code null}. */
    public List<byte[]> blockedOn() {
        return blockedOn;
    }

    /**
     * Marks the client as waiting in a blocking command on {@code keys}, which {@code unblocker} ends
     * early (CLIENT UNBLOCK). The hook is in place before the client shows as blocked, so an UNBLOCK
     * that sees it blocked always reaches it.
     */
    public void block(List<byte[]> keys, boolean onNoKey, java.util.function.Consumer<Boolean> unblocker) {
        this.unblocker = unblocker;
        this.blockedOnNoKey = onNoKey;
        this.blockedOn = keys;
    }

    /** Whether the client's blocking command also ends when its key is deleted (XREADGROUP). */
    public boolean blockedOnNoKey() {
        return blockedOnNoKey;
    }

    public void unblocked() {
        this.blockedOn = null;
        this.unblocker = null;
    }

    /**
     * CLIENT UNBLOCK: ends the client's blocking command, as a timeout or ({@code withError}) with an
     * error. Returns whether it was blocked.
     */
    public boolean unblock(boolean withError) {
        var hook = unblocker;
        if (hook == null) {
            return false;
        }
        hook.accept(withError);
        return true;
    }

    private static long payloadBytes(Reply.Push message) {
        long bytes = 0;
        for (Reply item : message.items()) {
            bytes += item instanceof Reply.Bulk(byte[] value) ? value.length + 16 : 16;
        }
        return bytes;
    }

    /** Whether the client has subscriptions (set by the Pub/Sub commands). */
    public boolean subscribed() {
        return subscribed;
    }

    public void subscribed(boolean subscribed) {
        this.subscribed = subscribed;
    }

    /** The RESP version the client speaks: 2, or 3 after {@code HELLO 3}. */
    public int protocol() {
        return protocol;
    }

    /** Switches the RESP version for the replies to this command and later ones. */
    public void protocol(int protocol) {
        this.protocol = protocol;
    }

    /** The authenticated user; {@code null} if the connection hasn't authenticated and must. */
    public String user() {
        return user;
    }

    /**
     * Authenticates the connection as {@code user}, or ({@code null}) leaves it unauthenticated.
     * Until it authenticates, requests are also limited in size, as in Valkey.
     */
    public void user(String user) {
        this.user = user;
        boolean limited = user == null;
        onLoop(() -> {
            if (channel.pipeline().get(RespDecoder.class) instanceof RespDecoder decoder) {
                decoder.unauthenticated(limited);
            }
        });
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

    /** Whether the client has disconnected (or is being disconnected). */
    public boolean isClosed() {
        return closed;
    }

    /** The handler state stored under {@code key}, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> T get(ConnectionKey<T> key) {
        return (T) attributes.get(key);
    }

    /**
     * Stores handler state under {@code key} ({@code null} removes it). State that is
     * {@link AutoCloseable} is closed when the client disconnects.
     */
    public <T> void set(ConnectionKey<T> key, T value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    /** Removes and returns the state stored under {@code key}. */
    @SuppressWarnings("unchecked")
    public <T> T remove(ConnectionKey<T> key) {
        return (T) attributes.remove(key);
    }
}
