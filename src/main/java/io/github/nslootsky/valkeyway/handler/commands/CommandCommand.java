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
import com.github.tonivade.resp.protocol.SafeString;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * COMMAND subcommand handler. Proxies all COMMAND variants to backend via Glide's customCommand().
 * For COMMAND DOCS, converts flag values "optional"/"multiple"/"multiple_token" to status tokens
 * for redis-cli compatibility (redis-cli expects REDIS_REPLY_STATUS for these flags).
 */
@Command("command")
@ParamLength(value = 0, option = 10)
public class CommandCommand implements RespCommand {

    private static final Logger log = LoggerFactory.getLogger(CommandCommand.class);

    /**
     * redis-cli barfs if flag values aren't simple strings:
     * > redis-cli: redis-cli.c:583: cliAddCommandDocArg: Assertion `flags->element[j]->type == REDIS_REPLY_STATUS' failed.
     */
    private static final Set<String> DOC_FLAG_VALUES = Set.of("optional", "multiple", "multiple_token");

    private final GlideClientCache glideClientCache;

    public CommandCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public RedisToken execute(Request request) {
        log.debug("COMMAND args={}", toArgs(request));

        try {
            GlideClusterClient client = SessionState.getOrCreateGlideClient(request.getSession(), glideClientCache);
            GlideString[] cmdArgs = toGlideArgs(request);
            ClusterValue<Object> result = client.customCommand(cmdArgs).get();

            log.debug("OK COMMAND result={}, hasSingle={}, hasMulti={}",
                    TokenUtils.summarize(result), result.hasSingleData(), result.hasMultiData());

            if (isCommandDocs(request)) {
                return toRedisTokenForDocs(result);
            }
            return TokenUtils.toRedisToken(result);
        } catch (Exception e) {
            log.error("ERR COMMAND error={}", e.getMessage());
            return RedisToken.error(TokenUtils.cleanErrorMessage(e.getMessage()));
        }
    }

    private boolean isCommandDocs(Request request) {
        if (request.getLength() < 1) {
            return false;
        }
        String subCmd = request.getParam(0).toString().toUpperCase();
        return "DOCS".equals(subCmd);
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

    RedisToken toRedisTokenForDocs(ClusterValue<Object> clusterValue) {
        if (clusterValue == null) {
            return RedisToken.nullString();
        }
        if (clusterValue.hasSingleData()) {
            return toRedisTokenForDocs(clusterValue.getSingleValue());
        } else if (clusterValue.hasMultiData()) {
            return toRedisTokenForDocs(clusterValue.getMultiValue());
        }
        return RedisToken.nullString();
    }

    RedisToken toRedisTokenForDocs(Object value) {
        return switch (value) {
            case null -> RedisToken.nullString();
            case GlideString gs -> {
                String str = gs.getString();
                if (DOC_FLAG_VALUES.contains(str)) {
                    yield RedisToken.status(str);
                }
                byte[] bytes = gs.getBytes();
                yield RedisToken.string(new SafeString(bytes));
            }
            case String s -> {
                if (DOC_FLAG_VALUES.contains(s)) {
                    yield RedisToken.status(s);
                }
                if ("OK".equals(s)) {
                    yield RedisToken.responseOk();
                }
                yield RedisToken.string(s);
            }
            case Number n -> RedisToken.integer(n.intValue());
            case Boolean b -> RedisToken.integer(b ? 1 : 0);
            case Object[] arr -> toRedisArrayForDocs(arr);
            case java.util.Collection<?> list -> toRedisArrayForDocs(list.toArray());
            case java.util.Map<?, ?> map -> {
                List<RedisToken> tokens = new ArrayList<>();
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    tokens.add(toRedisTokenForDocs(entry.getKey()));
                    tokens.add(toRedisTokenForDocs(entry.getValue()));
                }
                yield RedisToken.array(tokens);
            }
            default -> RedisToken.string(value.toString());
        };
    }

    private RedisToken toRedisArrayForDocs(Object[] values) {
        if (values == null) {
            return RedisToken.nullString();
        }
        List<RedisToken> tokens = new ArrayList<>();
        for (Object v : values) {
            tokens.add(toRedisTokenForDocs(v));
        }
        return RedisToken.array(tokens);
    }
}
