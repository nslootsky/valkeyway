/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.command.Session;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import glide.api.models.exceptions.RequestException;
import io.github.nslootsky.valkeyway.blocking.BlockingClientPool;
import io.github.nslootsky.valkeyway.blocking.BlockingCommands;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.cluster.ClusterSlots;
import io.github.nslootsky.valkeyway.cluster.CommandKeyExtractor;
import io.github.nslootsky.valkeyway.handler.SessionState;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Fallback handler for unregistered commands. Forwards via Glide's customCommand().
 * Queues commands during MULTI/EXEC.
 */
public class CatchAllCommand implements RespCommand {

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
    public RedisToken execute(Request request) {
        Session session = request.getSession();
        String command = request.getCommand();
        var timer = metrics.startTimer();

        if (log.isDebugEnabled()) {
            log.debug("CMD {} args={}", command, toArgs(request));
        }

        try {
            metrics.recordCommand(command);
            if (SessionState.isInTransaction(session)) {
                queueTransactionCommand(session, request);
                return RedisToken.status("QUEUED");
            }

            if (command.equalsIgnoreCase("CLIENT")) {
                return handleClientCommand(request);
            }

            byte[][] rawArgs = toBytes(request);
            Optional<BlockingCommands.Spec> blocking = BlockingCommands.detect(rawArgs);
            RedisToken result = blocking.isPresent()
                    ? handleBlockingCommand(session, rawArgs, blocking.get())
                    : handleCustomCommand(request);
            if (result.getType().name().equals("ERROR")) {
                metrics.recordError();
            }
            return result;
        } catch (Exception e) {
            metrics.recordError();
            log.error("Error handling command {}", command, e);
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        } finally {
            metrics.stopTimer(timer);
        }
    }

    private RedisToken handleClientCommand(Request request) {
        List<String> args = toArgs(request);
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("SETINFO")) {
            log.debug("CLIENT SETINFO handled locally");
            return RedisToken.responseOk();
        }
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("GETNAME")) {
            return RedisToken.nullString();
        }
        if (args.size() >= 2 && args.get(1).equalsIgnoreCase("ID")) {
            return RedisToken.integer((int) (System.currentTimeMillis() % 1000000));
        }
        return handleCustomCommand(request);
    }

    private List<String> toArgs(Request request) {
        List<String> args = new ArrayList<>();
        args.add(request.getCommand());
        for (var param : request.getParams()) {
            args.add(param.toString());
        }
        return args;
    }

    private GlideString[] toGlideArgs(Request request) {
        List<GlideString> args = new ArrayList<>();
        args.add(GlideString.of(request.getCommand()));
        for (var param : request.getParams()) {
            args.add(GlideString.of(param.getBytes()));
        }
        return args.toArray(new GlideString[0]);
    }

    private void queueTransactionCommand(Session session, Request request) {
        String txError = SessionState.getTransactionError(session);
        if (txError != null) {
            return;
        }
        byte[][] cmdBytes = toBytes(request);
        GlideClusterClient client = SessionState.getOrCreateGlideClient(session, glideClientCache);
        Set<Integer> slots = SessionState.getTransactionSlots(session);
        for (byte[] key : keyExtractor.extractKeys(client, cmdBytes)) {
            slots.add(ClusterSlots.slot(key));
        }
        SessionState.setTransactionSlots(session, slots);
        List<byte[][]> commands = SessionState.getTransactionCommands(session);
        commands.add(cmdBytes);
        SessionState.setTransactionCommands(session, commands);
    }

    private RedisToken handleCustomCommand(Request request) {
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            GlideString[] cmdArgs = toGlideArgs(request);
            ClusterValue<Object> result = client.customCommand(cmdArgs).get();
            if (log.isDebugEnabled()) {
                log.debug("OK {} result={}, hasSingle={}, hasMulti={}",
                        request.getCommand(), TokenUtils.summarize(result), result.hasSingleData(), result.hasMultiData());
            }
            return TokenUtils.toRedisToken(result);
        } catch (Exception e) {
            log.error("ERR {} error={}", request.getCommand(), e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }

    /**
     * Runs a command that can block on a leased, dedicated client so it never blocks the shared
     * multiplexed connection that every other proxy client uses.
     */
    private RedisToken handleBlockingCommand(Session session, byte[][] args, BlockingCommands.Spec spec)
            throws InterruptedException {
        int db = SessionState.getCurrentDb(session);
        long start = System.nanoTime();
        var lease = blockingPool.acquire(db, spec.timeoutMillis());
        if (lease == null) {
            log.debug("BLOCKING {} timed out waiting for a pooled client", new String(args[0], StandardCharsets.US_ASCII));
            return timeoutReply(spec.timeoutReply());
        }
        try {
            long waitedMillis = (System.nanoTime() - start) / 1_000_000;
            byte[][] effective = spec.withRemainingTimeout(args, waitedMillis);
            GlideString[] cmdArgs = new GlideString[effective.length];
            for (int i = 0; i < effective.length; i++) {
                cmdArgs[i] = GlideString.of(effective[i]);
            }
            return TokenUtils.toRedisToken(lease.resource().customCommand(cmdArgs).get());
        } catch (ExecutionException e) {
            // A server error leaves the connection usable. Anything else (timeout, connection loss)
            // may leave the blocking command pending on it, so don't hand it to the next caller.
            if (!(e.getCause() instanceof RequestException)) {
                lease.discard();
            }
            log.debug("ERR {} error={}", new String(args[0], StandardCharsets.US_ASCII), e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        } catch (InterruptedException e) {
            lease.discard();
            throw e;
        } finally {
            lease.close();
        }
    }

    private static RedisToken timeoutReply(BlockingCommands.TimeoutReply reply) {
        return switch (reply) {
            // Valkey sends a null array here; resp-server 1.0 can only send a null bulk string.
            case NULL -> RedisToken.nullString();
            case ZERO -> RedisToken.integer(0);
            case ZERO_PAIR -> RedisToken.array(RedisToken.integer(0), RedisToken.integer(0));
        };
    }

    private static byte[][] toBytes(Request request) {
        byte[][] args = new byte[request.getLength() + 1][];
        args[0] = request.getCommand().getBytes(StandardCharsets.US_ASCII);
        int i = 1;
        for (var param : request.getParams()) {
            args[i++] = param.getBytes();
        }
        return args;
    }
}
