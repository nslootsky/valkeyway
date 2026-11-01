/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.reply;

import glide.api.models.ClusterValue;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.protocol.Reply;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the Java values Glide returns into {@link Reply}s.
 *
 * <p>With binary output, Glide returns bulk strings as {@link GlideString}, and most status replies
 * too (those are restored per command in {@link Resp2Shapes}). A {@link String} at the top level of
 * a reply is a status (in practice OK) unless it contains a line break: RESP3 verbatim strings
 * (CLIENT LIST, CLUSTER INFO, ...) also arrive as {@code String} and are bulk strings in RESP2.
 * Strings nested inside a reply come from Glide's per-command conversions (e.g. LCS IDX map keys)
 * and were bulk strings on the wire.
 */
public final class GlideReplies {

    private GlideReplies() {}

    public static Reply toReply(ClusterValue<Object> value) {
        if (value == null) {
            return Reply.Null.BULK;
        }
        if (value.hasMultiData()) {
            // One value per node, keyed by node address.
            return nested(value.getMultiValue());
        }
        return toReply(value.getSingleValue());
    }

    /** Converts a whole reply (the value one command returned). */
    public static Reply toReply(Object value) {
        return value instanceof String s ? simpleOrText(s) : nested(value);
    }

    private static Reply nested(Object value) {
        return switch (value) {
            case null -> Reply.Null.BULK;
            case Reply reply -> reply;
            case GlideString gs -> new Reply.Bulk(gs.getBytes());
            case String s -> new Reply.Bulk(s.getBytes(StandardCharsets.UTF_8));
            case Long l -> Reply.integer(l);
            case Integer i -> Reply.integer(i);
            case java.lang.Double d -> new Reply.Double(d);
            case Float f -> new Reply.Double(f);
            case Boolean b -> new Reply.Bool(b);
            case BigInteger bi -> new Reply.BigNumber(bi.toString());
            case BigDecimal bd -> new Reply.BigNumber(bd.toPlainString());
            case Number n -> Reply.integer(n.longValue());
            // A failed command inside an EXEC or batch result.
            case Throwable t -> ServerErrors.error(t);
            case byte[] bytes -> new Reply.Bulk(bytes);
            case Object[] arr -> new Reply.Array(toReplies(arr));
            case Set<?> set -> new Reply.SetReply(toReplies(set.toArray()));
            case Collection<?> list -> new Reply.Array(toReplies(list.toArray()));
            case Map<?, ?> map -> {
                List<Reply> keysAndValues = new ArrayList<>(map.size() * 2);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    keysAndValues.add(nested(entry.getKey()));
                    keysAndValues.add(nested(entry.getValue()));
                }
                yield new Reply.MapReply(keysAndValues);
            }
            default -> Reply.bulk(value.toString());
        };
    }

    private static List<Reply> toReplies(Object[] values) {
        List<Reply> replies = new ArrayList<>(values.length);
        for (Object v : values) {
            replies.add(nested(v));
        }
        return replies;
    }

    private static Reply simpleOrText(String s) {
        if (s.equals("OK")) {
            return Reply.OK;
        }
        if (s.equals("PONG")) {
            return Reply.PONG;
        }
        if (s.equals("QUEUED")) {
            return Reply.QUEUED;
        }
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                ? new Reply.Bulk(s.getBytes(StandardCharsets.UTF_8))
                : new Reply.Simple(s);
    }
}
