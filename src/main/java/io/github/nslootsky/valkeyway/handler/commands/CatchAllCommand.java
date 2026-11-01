/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterBatch;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.FastThreadLocal;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import glide.api.models.exceptions.RequestException;
import io.github.nslootsky.valkeyway.blocking.BlockingClientPool;
import io.github.nslootsky.valkeyway.blocking.BlockingCommands;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.cluster.ClusterSlots;
import io.github.nslootsky.valkeyway.cluster.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.cluster.MapReplyCommands;
import io.github.nslootsky.valkeyway.cluster.SlotRoutes;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.GlideReplies;
import io.github.nslootsky.valkeyway.reply.Resp2Shapes;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
import io.github.nslootsky.valkeyway.server.AsyncCommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Fallback handler for every command without a dedicated handler: forwards it via Glide's
 * customCommand(), or queues it while the connection is in MULTI.
 *
 * <p>Most commands start asynchronously on the network thread (see {@link #canRunAsync}), so a
 * connection can pipeline them. The rest run through {@link #execute} on a virtual thread.
 */
public class CatchAllCommand implements AsyncCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CatchAllCommand.class);

    private final GlideClientCache glideClientCache;
    private final MetricsCollector metrics;
    private final BlockingClientPool blockingPool;
    private final CommandKeyExtractor keyExtractor = new CommandKeyExtractor();

    private static final Executor FALLBACK = Executors.newVirtualThreadPerTaskExecutor();

    private final boolean coalesceRequests;
    /** One coalescer per network thread: commands it collects are flushed at the end of the loop iteration. */
    private final FastThreadLocal<Coalescer> coalescer = new FastThreadLocal<>() {
        @Override
        protected Coalescer initialValue() {
            return new Coalescer();
        }
    };

    public CatchAllCommand(GlideClientCache glideClientCache, MetricsCollector metrics, BlockingClientPool blockingPool) {
        this(glideClientCache, metrics, blockingPool, true);
    }

    public CatchAllCommand(GlideClientCache glideClientCache, MetricsCollector metrics, BlockingClientPool blockingPool,
                           boolean coalesceRequests) {
        this.glideClientCache = glideClientCache;
        this.metrics = metrics;
        this.blockingPool = blockingPool;
        this.coalesceRequests = coalesceRequests;
    }

    /**
     * Collects single commands from different connections on one network thread and sends them to
     * Glide together at the end of the event-loop iteration: one command goes alone (with map-reply
     * routing), several go as one non-atomic batch. Ordering is unaffected: each connection has at most
     * one unit in flight, and different connections' commands have no order between them.
     */
    private final class Coalescer {
        private record Pending(byte[][] args, CompletableFuture<Reply> reply, io.micrometer.core.instrument.Timer.Sample timer) {}

        private final java.util.Map<Integer, List<Pending>> byDb = new java.util.HashMap<>();
        private boolean flushScheduled;

        CompletionStage<Reply> add(int db, byte[][] args, io.micrometer.core.instrument.Timer.Sample timer, EventLoop loop) {
            CompletableFuture<Reply> reply = new CompletableFuture<>();
            byDb.computeIfAbsent(db, d -> new ArrayList<>()).add(new Pending(args, reply, timer));
            if (!flushScheduled) {
                flushScheduled = true;
                loop.execute(() -> flush(loop));
            }
            return reply;
        }

        private void flush(EventLoop loop) {
            flushScheduled = false;
            for (var entry : byDb.entrySet()) {
                List<Pending> pending = entry.getValue();
                if (pending.isEmpty()) {
                    continue;
                }
                List<Pending> work = new ArrayList<>(pending);
                pending.clear();
                GlideClusterClient client = glideClientCache.getIfPresent(entry.getKey());
                if (client == null) {
                    for (Pending p : work) {
                        p.reply().complete(Reply.error("ERR backend client unavailable, retry"));
                    }
                } else if (work.size() == 1) {
                    sendOne(client, work.getFirst(), loop);
                } else {
                    sendBatch(client, work, loop);
                }
            }
        }

        private void sendOne(GlideClusterClient client, Pending p, EventLoop loop) {
            send(client, p.args()).handleAsync((result, error) -> {
                complete(p, error != null ? ServerErrors.error(unwrap(error)) : toReply(p.args(), result));
                return null;
            }, loop);
        }

        private void sendBatch(GlideClusterClient client, List<Pending> work, EventLoop loop) {
            ClusterBatch batch = new ClusterBatch(false).withBinaryOutput();
            for (Pending p : work) {
                byte[][] args = p.args();
                GlideString[] glideArgs = new GlideString[args.length];
                for (int i = 0; i < args.length; i++) {
                    glideArgs[i] = GlideString.of(args[i]);
                }
                batch.customCommand(glideArgs);
            }
            client.exec(batch, false).handleAsync((results, error) -> {
                for (int i = 0; i < work.size(); i++) {
                    Pending p = work.get(i);
                    Reply reply = error != null ? ServerErrors.error(unwrap(error))
                            : results == null || i >= results.length ? Reply.error("ERR missing reply in batch")
                            : GlideReplies.toReply(Resp2Shapes.shape(p.args(), results[i]));
                    complete(p, reply);
                }
                return null;
            }, loop);
        }

        private void complete(Pending p, Reply reply) {
            metrics.stopTimer(p.timer());
            if (reply instanceof Reply.Error) {
                metrics.recordError();
            }
            p.reply().complete(reply);
        }
    }

    /**
     * Async unless the command needs connection state handled on the barrier path (MULTI queueing,
     * CLIENT, WATCH/UNWATCH), may block (BLPOP & co. use a leased client), or would block the network
     * thread to set something up (the database's first Glide client, the COMMAND table).
     */
    @Override
    public boolean canRunAsync(Request request) {
        Connection connection = request.connection();
        byte[][] args = request.args();
        if (connection.inTransaction() || isBarrierCommand(args[0]) || BlockingCommands.detect(args).isPresent()) {
            return false;
        }
        if (glideClientCache.getIfPresent(connection.db()) == null) {
            return false;
        }
        return !MapReplyCommands.mayReturnMap(args) || keyExtractor.isLoaded();
    }

    @Override
    public CompletionStage<Reply> executeAsync(Request request, Executor replyExecutor) {
        GlideClusterClient client = glideClientCache.getIfPresent(request.connection().db());
        if (client == null) {
            // Evicted since canRunAsync: create it off the network thread (execute() records metrics).
            return CompletableFuture.supplyAsync(() -> execute(request), FALLBACK);
        }
        byte[][] args = request.args();
        var timer = metrics.startTimer();
        metrics.recordCommand(request.command());
        if (coalesceRequests && replyExecutor instanceof EventLoop loop && loop.inEventLoop()) {
            return coalescer.get().add(request.connection().db(), args, timer, loop);
        }
        return send(client, args).handleAsync((result, error) -> {
            metrics.stopTimer(timer);
            Reply reply = error != null ? ServerErrors.error(unwrap(error)) : toReply(args, result);
            if (reply instanceof Reply.Error) {
                metrics.recordError();
            }
            return reply;
        }, replyExecutor);
    }

    /**
     * A pipeline: one non-atomic ClusterBatch, which Glide executes in order per node (separately
     * submitted commands can be reordered). Batch results don't go through Glide's ClusterValue, so
     * map replies keep their order and bytes without explicit routing.
     */
    @Override
    public CompletionStage<List<Reply>> executeBatchAsync(List<Request> requests, Executor replyExecutor) {
        GlideClusterClient client = glideClientCache.getIfPresent(requests.getFirst().connection().db());
        if (client == null) {
            return CompletableFuture.supplyAsync(() -> requests.stream().map(this::execute).toList(), FALLBACK);
        }
        ClusterBatch batch = new ClusterBatch(false).withBinaryOutput();
        for (Request request : requests) {
            byte[][] args = request.args();
            GlideString[] glideArgs = new GlideString[args.length];
            for (int i = 0; i < args.length; i++) {
                glideArgs[i] = GlideString.of(args[i]);
            }
            batch.customCommand(glideArgs);
            metrics.recordCommand(request.command());
        }
        var timer = metrics.startTimer();
        return client.exec(batch, false).handleAsync((results, error) -> {
            metrics.stopTimer(timer);
            List<Reply> replies = new ArrayList<>(requests.size());
            for (int i = 0; i < requests.size(); i++) {
                Reply reply;
                if (error != null) {
                    reply = ServerErrors.error(unwrap(error));
                } else if (results == null || i >= results.length) {
                    reply = Reply.error("ERR missing reply in pipeline");
                } else {
                    reply = GlideReplies.toReply(Resp2Shapes.shape(requests.get(i).args(), results[i]));
                }
                if (reply instanceof Reply.Error) {
                    metrics.recordError();
                }
                replies.add(reply);
            }
            return replies;
        }, replyExecutor);
    }

    private static boolean isBarrierCommand(byte[] name) {
        return switch (name.length) {
            case 5 -> equalsIgnoreCase(name, "WATCH");
            case 6 -> equalsIgnoreCase(name, "CLIENT");
            case 7 -> equalsIgnoreCase(name, "UNWATCH");
            default -> false;
        };
    }

    private static boolean equalsIgnoreCase(byte[] name, String expected) {
        for (int i = 0; i < name.length; i++) {
            if ((name[i] | 0x20) != (expected.charAt(i) | 0x20)) {
                return false;
            }
        }
        return true;
    }

    private static Throwable unwrap(Throwable e) {
        return (e instanceof java.util.concurrent.CompletionException || e instanceof ExecutionException) && e.getCause() != null
                ? e.getCause() : e;
    }

    /** Loads routing metadata in the background so map-reply commands can start async soon after startup. */
    public void warmUp() {
        Thread.ofVirtual().name("catchall-warmup").start(() -> {
            try {
                keyExtractor.preload(glideClientCache.getClient(0));
            } catch (Exception e) {
                log.warn("Warm-up failed; routing metadata will load on first use: {}", e.getMessage());
            }
        });
    }

    @Override
    public Reply execute(Request request) {
        Connection connection = request.connection();
        byte[][] args = request.args();
        String command = request.command();
        var timer = metrics.startTimer();

        if (log.isDebugEnabled()) {
            log.debug("CMD {} args={}", command, Arrays.stream(args).map(a -> new String(a, StandardCharsets.UTF_8)).toList());
        }

        try {
            metrics.recordCommand(command);
            if (connection.inTransaction()) {
                queueTransactionCommand(connection, args);
                return Reply.QUEUED;
            }

            if (command.equalsIgnoreCase("CLIENT")) {
                return handleClientCommand(request);
            }

            Optional<BlockingCommands.Spec> blocking = BlockingCommands.detect(args);
            Reply result = blocking.isPresent()
                    ? handleBlockingCommand(connection, args, blocking.get())
                    : handleCustomCommand(connection, args);
            if (result instanceof Reply.Error) {
                metrics.recordError();
            }
            return result;
        } catch (Exception e) {
            metrics.recordError();
            log.error("Error handling command {}", command, e);
            return ServerErrors.error(e);
        } finally {
            metrics.stopTimer(timer);
        }
    }

    /** Connection-scoped CLIENT subcommands are answered from the proxy's own connection state. */
    private Reply handleClientCommand(Request request) {
        if (request.argCount() >= 1) {
            String sub = request.argString(0);
            if (sub.equalsIgnoreCase("SETINFO")) {
                return Reply.OK;
            }
            if (sub.equalsIgnoreCase("ID") && request.argCount() == 1) {
                return Reply.integer(request.connection().id());
            }
            if (sub.equalsIgnoreCase("GETNAME") && request.argCount() == 1) {
                String name = request.connection().name();
                return name == null ? Reply.Null.BULK : Reply.bulk(name);
            }
            if (sub.equalsIgnoreCase("SETNAME") && request.argCount() == 2) {
                String name = request.argString(1);
                for (int i = 0; i < name.length(); i++) {
                    if (name.charAt(i) <= ' ' || name.charAt(i) > '~') {
                        return Reply.error("ERR Client names cannot contain spaces, newlines or special characters.");
                    }
                }
                request.connection().name(name.isEmpty() ? null : name);
                return Reply.OK;
            }
        }
        return handleCustomCommand(request.connection(), request.args());
    }

    private void queueTransactionCommand(Connection connection, byte[][] args) {
        if (connection.transactionError() != null) {
            return;
        }
        GlideClusterClient client = glideClientCache.getClient(connection.db());
        for (byte[] key : keyExtractor.extractKeys(client, args)) {
            connection.transactionSlots().add(ClusterSlots.slot(key));
        }
        connection.transactionCommands().add(args);
    }

    private Reply handleCustomCommand(Connection connection, byte[][] args) {
        try {
            GlideClusterClient client = glideClientCache.getClient(connection.db());
            ClusterValue<Object> result = send(client, args).get();
            if (log.isDebugEnabled()) {
                log.debug("OK {} hasSingle={}, hasMulti={}",
                        new String(args[0], StandardCharsets.US_ASCII), result.hasSingleData(), result.hasMultiData());
            }
            return toReply(args, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            log.debug("ERR {} error={}", new String(args[0], StandardCharsets.US_ASCII), e.getMessage());
            return ServerErrors.error(e);
        }
    }

    /**
     * Sends a command. Commands that can return a top-level map are routed explicitly to their
     * slot's primary when all their keys share a slot; see {@link SlotRoutes} for why that matters
     * for reply integrity. Everything else, and keyless or multi-slot commands, keep Glide's own
     * routing (fan-out, splitting, aggregation), which is also Glide's faster path.
     */
    private CompletableFuture<ClusterValue<Object>> send(GlideClusterClient client, byte[][] args) {
        GlideString[] glideArgs = new GlideString[args.length];
        for (int i = 0; i < args.length; i++) {
            glideArgs[i] = GlideString.of(args[i]);
        }
        int slot = MapReplyCommands.mayReturnMap(args)
                ? keyExtractor.singleSlot(client, args)
                : CommandKeyExtractor.NO_SLOT;
        return slot == CommandKeyExtractor.NO_SLOT
                ? client.customCommand(glideArgs)
                : client.customCommand(glideArgs, SlotRoutes.primary(slot));
    }

    /**
     * Runs a command that can block on a leased, dedicated client so it never blocks the shared
     * multiplexed connection that every other proxy client uses.
     */
    private Reply handleBlockingCommand(Connection connection, byte[][] args, BlockingCommands.Spec spec)
            throws InterruptedException {
        long start = System.nanoTime();
        var lease = blockingPool.acquire(connection.db(), spec.timeoutMillis());
        if (lease == null) {
            log.debug("BLOCKING {} timed out waiting for a pooled client", new String(args[0], StandardCharsets.US_ASCII));
            return timeoutReply(spec.timeoutReply());
        }
        try {
            long waitedMillis = (System.nanoTime() - start) / 1_000_000;
            byte[][] effective = spec.withRemainingTimeout(args, waitedMillis);
            return toReply(args, send(lease.resource(), effective).get());
        } catch (ExecutionException e) {
            // A server error leaves the connection usable. Anything else (timeout, connection loss)
            // may leave the blocking command pending on it, so don't hand it to the next caller.
            if (!(e.getCause() instanceof RequestException)) {
                lease.discard();
            }
            log.debug("ERR {} error={}", new String(args[0], StandardCharsets.US_ASCII), e.getMessage());
            return ServerErrors.error(e);
        } catch (InterruptedException e) {
            lease.discard();
            throw e;
        } finally {
            lease.close();
        }
    }

    /** Single-node replies are reshaped to what a standalone server sends RESP2 clients. */
    private static Reply toReply(byte[][] args, ClusterValue<Object> result) {
        return result.hasSingleData()
                ? GlideReplies.toReply(Resp2Shapes.shape(args, result.getSingleValue()))
                : GlideReplies.toReply(result);
    }

    private static Reply timeoutReply(BlockingCommands.TimeoutReply reply) {
        return switch (reply) {
            case NULL -> Reply.Null.ARRAY;
            case ZERO -> Reply.ZERO;
            case ZERO_PAIR -> Reply.array(Reply.ZERO, Reply.ZERO);
        };
    }
}
