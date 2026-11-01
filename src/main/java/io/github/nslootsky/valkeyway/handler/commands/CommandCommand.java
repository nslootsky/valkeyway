/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.handler.commands;

import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.reply.GlideReplies;
import io.github.nslootsky.valkeyway.reply.ServerErrors;
import io.github.nslootsky.valkeyway.server.CommandHandler;
import io.github.nslootsky.valkeyway.server.Request;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * COMMAND: all subcommands are proxied. For COMMAND DOCS, the argument flag values "optional",
 * "multiple" and "multiple_token" are sent as status replies, as Valkey does; redis-cli asserts on it.
 */
public class CommandCommand implements CommandHandler {

    /**
     * redis-cli barfs if flag values aren't simple strings:
     * > redis-cli: redis-cli.c:583: cliAddCommandDocArg: Assertion `flags->element[j]->type == REDIS_REPLY_STATUS' failed.
     */
    // [upstream:G7]
    private static final Set<String> DOC_FLAG_VALUES = Set.of("optional", "multiple", "multiple_token");

    private final GlideClientCache glideClientCache;

    public CommandCommand(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public Reply execute(Request request) {
        try {
            GlideClusterClient client = glideClientCache.getClient(request.connection().db());
            byte[][] args = request.args();
            GlideString[] cmdArgs = new GlideString[args.length];
            for (int i = 0; i < args.length; i++) {
                cmdArgs[i] = GlideString.of(args[i]);
            }
            ClusterValue<Object> result = client.customCommand(cmdArgs).get();
            if (request.argCount() >= 1 && request.argString(0).equalsIgnoreCase("DOCS")) {
                return toReplyForDocs(result.hasSingleData() ? result.getSingleValue() : result.getMultiValue());
            }
            return GlideReplies.toReply(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            return ServerErrors.error(e);
        }
    }

    Reply toReplyForDocs(Object value) {
        return switch (value) {
            case GlideString gs when DOC_FLAG_VALUES.contains(gs.getString()) -> new Reply.Simple(gs.getString());
            case String s when DOC_FLAG_VALUES.contains(s) -> new Reply.Simple(s);
            case String s -> new Reply.Bulk(s.getBytes(StandardCharsets.UTF_8));
            case Object[] arr -> {
                List<Reply> items = new ArrayList<>(arr.length);
                for (Object v : arr) {
                    items.add(toReplyForDocs(v));
                }
                yield new Reply.Array(items);
            }
            case java.util.Collection<?> list -> toReplyForDocs(list.toArray());
            case Map<?, ?> map -> {
                List<Reply> items = new ArrayList<>(map.size() * 2);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    items.add(toReplyForDocs(entry.getKey()));
                    items.add(toReplyForDocs(entry.getValue()));
                }
                yield new Reply.MapReply(items);
            }
            case null, default -> GlideReplies.toReply(value);
        };
    }
}
