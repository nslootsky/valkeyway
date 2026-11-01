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
 *
 * <p>Relies on upstream behavior: [upstream:G3] [upstream:G4] [upstream:G5] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public class CatchAllCommand implements AsyncCommandHandler {

    @Override
    public InTransaction inTransaction() {
        return InTransaction.RUN;
    }

    private static final Logger log = LoggerFactory.getLogger(CatchAllCommand.class);

    private final GlideClientCache glideClientCache;
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

    public CatchAllCommand(GlideClientCache glideClientCache, BlockingClientPool blockingPool, boolean coalesceRequests) {
        this.glideClientCache = glideClientCache;
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
        private record Pending(byte[][] args, CompletableFuture<Reply> reply) {}

        private final java.util.Map<Integer, List<Pending>> byDb = new java.util.HashMap<>();
        private boolean flushScheduled;

        CompletionStage<Reply> add(int db, byte[][] args, EventLoop loop) {
            CompletableFuture<Reply> reply = new CompletableFuture<>();
            byDb.computeIfAbsent(db, d -> new ArrayList<>()).add(new Pending(args, reply));
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
                p.reply().complete(error != null ? ServerErrors.error(error) : toReply(p.args(), result));
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
                    Reply reply = error != null ? ServerErrors.error(error)
                            : results == null || i >= results.length ? Reply.error("ERR missing reply in batch")
                            : GlideReplies.toReply(Resp2Shapes.shape(p.args(), results[i]));
                    if (error == null && reply instanceof Reply.Error e && ServerErrors.rejectedWithoutRunning(e)) {
                        // [upstream:G3] Glide retries these for a single command but not inside a batch. Each command
                        // here is the only one its connection has in flight, so resending it alone
                        // can't reorder it against that connection's other commands.
                        sendOne(client, p, loop);
                    } else {
                        p.reply().complete(reply);
                    }
                }
                return null;
            }, loop);
        }
    }

    /**
     * Async unless the command needs connection state handled on the barrier path (MULTI queueing,
     * CLIENT), may block (BLPOP & co. use a leased client), or would block the network
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
            // Evicted since canRunAsync: create it off the network thread.
            return CompletableFuture.supplyAsync(() -> execute(request), FALLBACK);
        }
        byte[][] args = request.args();
        if (coalesceRequests && replyExecutor instanceof EventLoop loop && loop.inEventLoop()) {
            return coalescer.get().add(request.connection().db(), args, loop);
        }
        return send(client, args).handleAsync(
                (result, error) -> error != null ? ServerErrors.error(error) : toReply(args, result), replyExecutor);
    }

    /**
     * [upstream:G4] A pipeline: one non-atomic ClusterBatch, which Glide executes in order per node (separately
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
        }
        return client.exec(batch, false).handleAsync((results, error) -> {
            List<Reply> replies = new ArrayList<>(requests.size());
            for (int i = 0; i < requests.size(); i++) {
                Reply reply;
                if (error != null) {
                    reply = ServerErrors.error(error);
                } else if (results == null || i >= results.length) {
                    reply = Reply.error("ERR missing reply in pipeline");
                } else {
                    reply = GlideReplies.toReply(Resp2Shapes.shape(requests.get(i).args(), results[i]));
                }
                replies.add(reply);
            }
            return replies;
        }, replyExecutor);
    }

    private static boolean isBarrierCommand(byte[] name) {
        return name.length == 6 && equalsIgnoreCase(name, "CLIENT");
    }

    private static boolean equalsIgnoreCase(byte[] name, String expected) {
        for (int i = 0; i < name.length; i++) {
            if ((name[i] | 0x20) != (expected.charAt(i) | 0x20)) {
                return false;
            }
        }
        return true;
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

        if (log.isDebugEnabled()) {
            log.debug("CMD {} args={}", command, Arrays.stream(args).map(a -> new String(a, StandardCharsets.UTF_8)).toList());
        }

        try {
            if (connection.inTransaction()) {
                return queueTransactionCommand(connection, args);
            }

            if (command.equalsIgnoreCase("CLIENT")) {
                return handleClientCommand(request);
            }

            Optional<BlockingCommands.Spec> blocking = BlockingCommands.detect(args);
            return blocking.isPresent()
                    ? handleBlockingCommand(connection, args, blocking.get())
                    : handleCustomCommand(connection, args);
        } catch (Exception e) {
            log.error("Error handling command {}", command, e);
            return ServerErrors.error(e);
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

    /**
     * Queues a command inside MULTI. Like Valkey, a command that can't run (unknown, wrong number of
     * arguments) is answered with its error now, and makes EXEC fail with EXECABORT.
     */
    private Reply queueTransactionCommand(Connection connection, byte[][] args) {
        GlideClusterClient client = glideClientCache.getClient(connection.db());
        String error = keyExtractor.queueError(client, args);
        if (error != null) {
            connection.transactionError(EXECABORT);
            return Reply.error(error);
        }
        if (connection.transactionError() == null) {
            for (byte[] key : keyExtractor.extractKeys(client, args)) {
                connection.transactionSlots().add(ClusterSlots.slot(key));
            }
            connection.transactionCommands().add(args);
        }
        return Reply.QUEUED;
    }

    public static final String EXECABORT = "EXECABORT Transaction discarded because of previous errors.";

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
    /**
     * Sends one command. If Glide returns a redirect instead of following it, the command is resent
     * as a one-command batch. [upstream:G10] For single commands to a migrating slot under concurrent
     * traffic, Glide often fails to follow ASK and returns the importing node's MOVED; inside a batch
     * it follows ASK reliably. The node rejected the command without running it, so resending is safe.
     */
    private CompletableFuture<ClusterValue<Object>> send(GlideClusterClient client, byte[][] args) {
        return sendOnce(client, args).exceptionallyCompose(e -> ServerErrors.isUnfollowedRedirect(e)
                ? sendAsBatch(client, args)
                : CompletableFuture.failedFuture(e));
    }

    private static CompletableFuture<ClusterValue<Object>> sendAsBatch(GlideClusterClient client, byte[][] args) {
        GlideString[] glideArgs = new GlideString[args.length];
        for (int i = 0; i < args.length; i++) {
            glideArgs[i] = GlideString.of(args[i]);
        }
        ClusterBatch batch = new ClusterBatch(false).withBinaryOutput();
        batch.customCommand(glideArgs);
        return client.exec(batch, false).thenCompose(results -> {
            Object result = results == null || results.length == 0 ? null : results[0];
            // ofSingleValue, not of(): of() misreads map replies as multi-node results [upstream:G5].
            return result instanceof Throwable failure
                    ? CompletableFuture.<ClusterValue<Object>>failedFuture(failure)
                    : CompletableFuture.completedFuture(ClusterValue.ofSingleValue(result));
        });
    }

    // [upstream:G5] map-reply commands need an explicit route.
    private CompletableFuture<ClusterValue<Object>> sendOnce(GlideClusterClient client, byte[][] args) {
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
