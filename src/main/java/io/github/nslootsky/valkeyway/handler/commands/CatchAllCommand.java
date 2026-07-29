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
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fallback handler for unregistered commands. Forwards via Glide's customCommand().
 * Queues commands during MULTI/EXEC.
 */
public class CatchAllCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(CatchAllCommand.class);

    private final GlideClientCache glideClientCache;
    private final MetricsCollector metrics;

    public CatchAllCommand(GlideClientCache glideClientCache, MetricsCollector metrics) {
        this.glideClientCache = glideClientCache;
        this.metrics = metrics;
    }

    @Override
    public RedisToken execute(Request request) {
        Session session = request.getSession();
        String command = request.getCommand();
        List<String> args = toArgs(request);
        var timer = metrics.startTimer();

        log.debug("CMD {} args={}", command, args);

        try {
            metrics.recordCommand(command);
            if (SessionState.isInTransaction(session)) {
                queueTransactionCommand(session, request);
                return RedisToken.status("QUEUED");
            }

            if (command.equalsIgnoreCase("CLIENT")) {
                return handleClientCommand(request);
            }

            RedisToken result = handleCustomCommand(request);
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
            return RedisToken.status("OK");
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
        byte[][] cmdBytes = new byte[request.getLength() + 1][];
        cmdBytes[0] = request.getCommand().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int i = 1;
        for (var param : request.getParams()) {
            cmdBytes[i++] = param.getBytes();
        }
        List<byte[]> keys = extractKeys(cmdBytes);
        Set<Integer> slots = SessionState.getTransactionSlots(session);
        for (byte[] key : keys) {
            int hash = 0;
            for (byte b : key) {
                hash = (hash * 31) + b;
            }
            slots.add(hash & 0x3FFF);
        }
        SessionState.setTransactionSlots(session, slots);
        List<byte[][]> commands = SessionState.getTransactionCommands(session);
        commands.add(cmdBytes);
        SessionState.setTransactionCommands(session, commands);
    }

    private List<byte[]> extractKeys(byte[][] cmdBytes) {
        String command = new String(cmdBytes[0], java.nio.charset.StandardCharsets.US_ASCII).toUpperCase();
        if (cmdBytes.length < 2) {
            return List.of();
        }
        return switch (command) {
            case "DEL", "UNLINK", "MGET", "EXISTS", "TOUCH" -> {
                List<byte[]> keys = new ArrayList<>();
                for (int i = 1; i < cmdBytes.length; i++) {
                    keys.add(cmdBytes[i]);
                }
                yield keys;
            }
            case "SET", "GET", "HSET", "HGET", "INCR", "DECR" -> List.of(cmdBytes[1]);
            default -> List.of(cmdBytes[1]);
        };
    }

    private RedisToken handleCustomCommand(Request request) {
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            GlideString[] cmdArgs = toGlideArgs(request);
            ClusterValue<Object> result = client.customCommand(cmdArgs).get();
            Object singleValue = result.hasSingleData() ? result.getSingleValue() : null;
            Object multiValue = result.hasMultiData() ? result.getMultiValue() : null;
            log.debug("OK {} result={}, hasSingle={}, hasMulti={}, singleValue={}, multiValue={}",
                    request.getCommand(), TokenUtils.summarize(result),
                    result.hasSingleData(), result.hasMultiData(), singleValue, multiValue);
            return TokenUtils.toRedisToken(result);
        } catch (Exception e) {
            log.error("ERR {} error={}", request.getCommand(), e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }
}
