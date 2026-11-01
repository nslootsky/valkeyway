/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
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
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Connection;
import io.github.nslootsky.valkeyway.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Fallback handler for every command without a dedicated handler: forwards it via Glide's
 * customCommand(), or queues it while the connection is in MULTI.
 */
public class CatchAllCommand implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CatchAllCommand.class);

    private final GlideClientCache glideClientCache;
    private final MetricsCollector metrics;
    private final BlockingClientPool blockingPool;
    private final CommandKeyExtractor keyExtractor = new CommandKeyExtractor();

    public CatchAllCommand(GlideClientCache glideClientCache, MetricsCollector metrics, BlockingClientPool blockingPool) {
        this.glideClientCache = glideClientCache;
        this.metrics = metrics;
        this.blockingPool = blockingPool;
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
