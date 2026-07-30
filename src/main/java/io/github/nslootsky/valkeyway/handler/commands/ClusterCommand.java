/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import com.github.tonivade.resp.annotation.Command;
import com.github.tonivade.resp.annotation.ParamLength;
import com.github.tonivade.resp.command.Request;
import com.github.tonivade.resp.command.RespCommand;
import com.github.tonivade.resp.protocol.RedisToken;
import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * CLUSTER command handler. Returns standalone-compatible errors for INFO/NODES
 * to mimic real standalone server behavior. Proxies other subcommands to backend
 * via Glide's customCommand().
 */
@Command("cluster")
@ParamLength(value = 1, option = 10)
public class ClusterCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(ClusterCommand.class);

    /**
     * Subcommands that must return standalone-mode errors (real standalone servers don't support cluster mode).
     */
    private static final java.util.Set<String> STANDALONE_ERROR_SUBCOMMANDS =
            java.util.Set.of("INFO", "NODES");

    private final GlideClientCache glideClientCache;

    public ClusterCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        if (request.getLength() < 1) {
            log.debug("CLUSTER ERR wrong number of arguments");
            return RedisToken.error("ERR wrong number of arguments for 'cluster' command");
        }

        String subCmd = request.getParam(0).toString().toUpperCase();
        log.debug("CLUSTER subCmd={}", subCmd);

        if (STANDALONE_ERROR_SUBCOMMANDS.contains(subCmd)) {
            return RedisToken.error("This instance has cluster support disabled");
        }

        return handleClusterSubcommand(request);
    }

    private RedisToken handleClusterSubcommand(Request request) {
        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            GlideString[] cmdArgs = toGlideArgs(request);
            var result = client.customCommand(cmdArgs).get();
            log.debug("CLUSTER OK subCmd={} result={}", request.getParam(0).toString(), TokenUtils.summarize(result));
            return TokenUtils.toRedisToken(result);
        } catch (Exception e) {
            log.error("CLUSTER ERR subCmd={} error={}", request.getParam(0).toString(), e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
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
}
