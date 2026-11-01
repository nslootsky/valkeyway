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
            if ((request.argCount() == 0 || request.argString(0).equalsIgnoreCase("INFO"))
                    && result.hasSingleData() && result.getSingleValue() instanceof Object[] entries) {
                return commandEntries(entries);
            }
            return GlideReplies.toReply(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error("ERR interrupted");
        } catch (Exception e) {
            return ServerErrors.error(e);
        }
    }

    /**
     * COMMAND / COMMAND INFO entries: [name, arity, flags, first, last, step, categories, tips,
     * key specs, subcommands]. Flags, ACL categories and key-spec flags are statuses on the wire,
     * which Glide returns like bulk strings. [upstream:G7]
     */
    private static Reply commandEntries(Object[] entries) {
        List<Reply> out = new ArrayList<>(entries.length);
        for (Object entry : entries) {
            out.add(entry instanceof Object[] fields && fields.length >= 10 ? commandEntry(fields) : GlideReplies.toReply(entry));
        }
        return new Reply.Array(out);
    }

    private static Reply commandEntry(Object[] fields) {
        List<Reply> out = new ArrayList<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            out.add(switch (i) {
                case 2, 6 -> statuses(fields[i]);
                case 8 -> keySpecs(fields[i]);
                case 9 -> fields[i] instanceof Object[] subcommands ? commandEntries(subcommands) : GlideReplies.toReply(fields[i]);
                default -> GlideReplies.element(fields[i]);
            });
        }
        return new Reply.Array(out);
    }

    /** A set (array in RESP2) of statuses. */
    private static Reply statuses(Object value) {
        if (!(value instanceof Collection<?> || value instanceof Object[])) {
            return GlideReplies.element(value);
        }
        Object[] items = value instanceof Collection<?> c ? c.toArray() : (Object[]) value;
        List<Reply> out = new ArrayList<>(items.length);
        for (Object item : items) {
            out.add(new Reply.Simple(item instanceof GlideString gs ? gs.getString() : String.valueOf(item)));
        }
        return value instanceof Set<?> ? new Reply.SetReply(out) : new Reply.Array(out);
    }

    /** Key specs (a set in RESP3): maps whose "flags" value is a set of statuses. */
    private static Reply keySpecs(Object value) {
        Object[] specs = value instanceof Collection<?> c ? c.toArray() : value instanceof Object[] a ? a : null;
        if (specs == null) {
            return GlideReplies.element(value);
        }
        List<Reply> out = new ArrayList<>(specs.length);
        for (Object spec : specs) {
            if (spec instanceof Map<?, ?> map) {
                List<Reply> kv = new ArrayList<>(map.size() * 2);
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object key = e.getKey();
                    String name = key instanceof GlideString gs ? gs.getString() : String.valueOf(key);
                    kv.add(GlideReplies.element(key));
                    kv.add(name.equals("flags") ? statuses(e.getValue()) : GlideReplies.element(e.getValue()));
                }
                out.add(new Reply.MapReply(kv));
            } else {
                out.add(GlideReplies.element(spec));
            }
        }
        return value instanceof Set<?> ? new Reply.SetReply(out) : new Reply.Array(out);
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
            // History is a set in RESP3, which Glide returns as an unordered Java set.
            case Set<?> set -> new Reply.SetReply(((Reply.Array) toReplyForDocs(set.toArray())).items());
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
