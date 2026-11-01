/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import java.util.Collection;
import glide.api.GlideClusterClient;
import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.backend.GlideArgs;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.github.nslootsky.valkeyway.backend.replies.GlideReplies;
import io.github.nslootsky.valkeyway.backend.replies.ServerErrors;
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

    private final BackendClients backendClients;

    public CommandCommand(BackendClients backendClients) {
        this.backendClients = backendClients;
    }

    @Override
    public Reply execute(Request request) {
        try {
            GlideClusterClient client = backendClients.getClient(request.connection().db());
            ClusterValue<Object> result = client.customCommand(GlideArgs.of(request.args())).get();
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
            case Collection<?> list -> toReplyForDocs(list.toArray());
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
