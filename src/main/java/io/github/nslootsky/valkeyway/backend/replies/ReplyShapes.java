/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.replies;

import java.util.Set;
import io.github.nslootsky.valkeyway.protocol.DoubleFormat;
import glide.api.models.GlideString;
import io.github.nslootsky.valkeyway.protocol.Reply;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Restores the reply shapes a standalone server sends, to RESP2 and to RESP3 clients.
 *
 * <p>Glide talks RESP3 to the cluster and reshapes some replies by command name before we see them
 * (glide-core value_conversion.rs, {@code expected_type_for_cmd}): stream entries become maps of id
 * to field pairs, geo results get nested and parsed as doubles, and so on. This class maps those
 * values back, given the command that produced them. Where Valkey's RESP2 and RESP3 shapes differ in
 * structure, the result is a {@link Reply} type that carries both ({@link Reply.FlatPairs} for
 * WITHSCORES results, {@link Reply.NestedMap} for XREAD), and the encoder picks.
 *
 * <p>Output uses {@code Object[]} for arrays, {@link GlideString} for strings that must be sent as
 * bulk strings in both protocols (formatted numbers), and {@link Reply}s; everything else passes
 * through unchanged.
 *
 * <p>Relies on upstream behavior: [upstream:G6] [upstream:G7] [upstream:V1] [upstream:V2] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class ReplyShapes {

    private ReplyShapes() {}

    /** Reshapes {@code value}, the reply to {@code args} (args[0] = command name). */
    public static Object shape(byte[][] args, Object value) {
        if (args.length == 0 || args[0].length == 0) {
            return value;
        }
        if (value == null) {
            return nullReply(args);
        }
        if (value instanceof GlideString status && (isStatusCommand(args[0]) || isBarePing(args))) {
            return new Reply.Simple(status.getString());
        }
        if (value instanceof java.lang.Double d && isFloatIncrement(args[0])) {
            // Glide parses the reply as a double, but it's a bulk string in RESP3 too.
            return bulk(DoubleFormat.valkey(d));
        }
        // Every command reshaped below has a name of 5+ chars starting with x, z, h, g, b, l or f, so
        // the hot short commands (GET, SET, HGET, HSET, ZADD, ...) exit without allocating.
        if (args[0].length < 5) {
            return value;
        }
        switch (args[0][0] | 0x20) {
            case 'x', 'z', 'h', 'g', 'b', 'l', 'f' -> { }
            default -> {
                return value;
            }
        }
        String name = new String(args[0], StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        return switch (name) {
            case "xrange", "xrevrange" -> entries(value);
            case "xclaim" -> hasOption(args, "JUSTID") ? value : entries(value);
            case "xread", "xreadgroup" -> streams(value);
            case "xautoclaim" -> hasOption(args, "JUSTID") ? value : autoclaim(value);
            case "zrandmember", "zrangebyscore", "zrevrangebyscore", "zrevrange", "zrange", "zunion", "zinter",
                 "zdiff" -> hasOption(args, "WITHSCORES") ? pairs(value) : value;
            case "hrandfield" -> hasOption(args, "WITHVALUES") ? pairs(value) : value;
            case "zmpop", "bzmpop" -> keyAndScoredMembers(value);
            // With a count, pairs; without, one [member, score].
            case "zpopmin", "zpopmax" -> args.length > 2 ? pairs(value) : flatten(value);
            case "lmpop", "blmpop" -> keyAndElements(value);
            case "geodist" -> distance(value);
            case "geopos" -> positions(value);
            case "geosearch", "georadius", "georadius_ro", "georadiusbymember", "georadiusbymember_ro" ->
                    geoResults(args, value);
            case "xpending" -> pendingSummary(args, value);
            // The search module's replies are arrays, in RESP3 too. [upstream:G16]
            case "ft.search" -> searchResults(args, value);
            case "ft.aggregate" -> aggregateRows(value);
            case "ft.info" -> arrays(value);
            default -> value;
        };
    }

    // --- status replies ---

    /**
     * Commands whose reply is a status (simple string) other than OK. With binary output Glide
     * returns those as GlideString, indistinguishable from a bulk string, so they're listed here.
     */
    // [upstream:G7]
    private static final Set<String> STATUS_COMMANDS = Set.of(
            "type", "bgsave", "bgrewriteaof", "migrate");

    /** PING without an argument answers +PONG (with one, it echoes a bulk string). Reaches Glide only inside EXEC. */
    private static boolean isBarePing(byte[][] args) {
        byte[] name = args[0];
        return args.length == 1 && name.length == 4 && (name[0] | 0x20) == 'p' && (name[1] | 0x20) == 'i'
                && (name[2] | 0x20) == 'n' && (name[3] | 0x20) == 'g';
    }

    /** INCRBYFLOAT and HINCRBYFLOAT (only asked about double replies, which are rare). */
    private static boolean isFloatIncrement(byte[] name) {
        String lower = new String(name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        return lower.equals("incrbyfloat") || lower.equals("hincrbyfloat");
    }

    private static boolean isStatusCommand(byte[] name) {
        switch (name[0] | 0x20) {
            case 't', 'b', 'm' -> { }
            default -> {
                return false;
            }
        }
        return name.length <= 12
                && STATUS_COMMANDS.contains(new String(name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT));
    }

    // --- nulls ---

    /** Commands whose "nothing" reply is a null array ({@code *-1}) in RESP2, not a null bulk string. */
    // [upstream:V2]
    private static final Set<String> NULL_ARRAY_COMMANDS = Set.of(
            // blocking timeouts (blocked.c replyToBlockedClientTimedOut: list, zset and stream types)
            "blpop", "brpop", "blmove", "brpoplpush", "blmpop", "bzpopmin", "bzpopmax", "bzmpop",
            // no data
            "lmpop", "zmpop", "xread", "xreadgroup",
            // XRANGE ... COUNT 0
            "xrange", "xrevrange");

    /**
     * Glide returns Java null for both RESP2 nulls; pick the one Valkey sends for this command
     * (from the server's addReplyNullArray call sites).
     */
    private static Object nullReply(byte[][] args) {
        if (args[0].length > 10) {
            return null;
        }
        String name = new String(args[0], StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        boolean nullArray = NULL_ARRAY_COMMANDS.contains(name)
                // LPOP/RPOP with a count on a missing key
                || ((name.equals("lpop") || name.equals("rpop")) && args.length > 2)
                // ZRANK/ZREVRANK ... WITHSCORE for a missing member
                || ((name.equals("zrank") || name.equals("zrevrank")) && hasOption(args, "WITHSCORE"));
        return nullArray ? Reply.Null.ARRAY : null;
    }

    /** XPENDING key group (summary form): an empty consumer list is a null array. */
    private static Object pendingSummary(byte[][] args, Object value) {
        if (args.length == 3 && value instanceof Object[] arr && arr.length == 4 && arr[3] == null) {
            Object[] out = arr.clone();
            out[3] = Reply.Null.ARRAY;
            return out;
        }
        return value;
    }

    // --- streams ---

    /** {id -> [[f, v], ...]} to [[id, [f, v, ...]], ...]. */
    private static Object entries(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        Object[] out = new Object[map.size()];
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object fields = entry.getValue();
            // A deleted entry still pending in a group has null fields: a null array in RESP2.
            out[i++] = new Object[]{entry.getKey(), fields == null ? Reply.Null.ARRAY : flattenPairs(fields)};
        }
        return out;
    }

    /** {stream -> {id -> fields}} to a map of stream to entries: RESP2 [[stream, entries], ...]. */
    private static Object streams(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        List<Reply> keysAndValues = new ArrayList<>(map.size() * 2);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            keysAndValues.add(GlideReplies.element(entry.getKey()));
            keysAndValues.add(GlideReplies.element(entries(entry.getValue())));
        }
        return new Reply.NestedMap(keysAndValues);
    }

    /** [cursor, {id -> fields}, deleted] to [cursor, entries, deleted]. */
    private static Object autoclaim(Object value) {
        if (value instanceof Object[] arr && arr.length >= 2) {
            Object[] out = arr.clone();
            out[1] = entries(arr[1]);
            return out;
        }
        return value;
    }

    // --- sorted sets / hashes ---

    /**
     * WITHSCORES / WITHVALUES results, as [[a, b], [c, d]] or a map {a: b, c: d}: pairs in RESP3,
     * flat in RESP2. Leaves anything else alone.
     */
    private static Object pairs(Object value) {
        List<Reply> items = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                items.add(GlideReplies.element(entry.getKey()));
                items.add(GlideReplies.element(entry.getValue()));
            }
            return new Reply.FlatPairs(items);
        }
        Object flat = flattenPairs(value);
        if (flat == value || !(flat instanceof Object[] elements) || elements.length % 2 != 0) {
            return value;
        }
        for (Object element : elements) {
            items.add(GlideReplies.element(element));
        }
        return new Reply.FlatPairs(items);
    }

    /** [[a, b], [c, d]] to [a, b, c, d]. Leaves anything else alone. */
    private static Object flattenPairs(Object value) {
        Object[] pairs = toArray(value);
        if (pairs == null) {
            return value;
        }
        Object[][] arrays = new Object[pairs.length][];
        int size = 0;
        for (int p = 0; p < pairs.length; p++) {
            arrays[p] = toArray(pairs[p]);
            if (arrays[p] == null) {
                return value;
            }
            size += arrays[p].length;
        }
        Object[] out = new Object[size];
        int i = 0;
        for (Object[] pair : arrays) {
            for (Object element : pair) {
                out[i++] = element;
            }
        }
        return out;
    }

    /** {member: score} to [member, score]. */
    private static Object flatten(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        Object[] out = new Object[map.size() * 2];
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out[i++] = entry.getKey();
            out[i++] = entry.getValue();
        }
        return out;
    }

    /** {key: [elements]} to [key, [elements]]. */
    private static Object keyAndElements(Object value) {
        if (value instanceof Map<?, ?> map && map.size() == 1) {
            Map.Entry<?, ?> entry = map.entrySet().iterator().next();
            return new Object[]{entry.getKey(), entry.getValue()};
        }
        return value;
    }

    /** [key, {member -> score}] to [key, [[member, score], ...]]. */
    private static Object keyAndScoredMembers(Object value) {
        if (value instanceof Object[] arr && arr.length == 2 && arr[1] instanceof Map<?, ?> scores) {
            Object[] members = new Object[scores.size()];
            int i = 0;
            for (Map.Entry<?, ?> entry : scores.entrySet()) {
                members[i++] = new Object[]{entry.getKey(), entry.getValue()};
            }
            return new Object[]{arr[0], members};
        }
        return value;
    }

    // --- geo ---

    /** GEODIST and WITHDIST: Valkey sends "%.4f". [upstream:V1] */
    private static Object distance(Object value) {
        return value instanceof Double d ? bulk(DoubleFormat.fixed(d, 4, false)) : value;
    }

    /** Longitude/latitude: Valkey sends "%.17Lf" with trailing zeros trimmed (a double in RESP3). */
    private static Object coordinate(Object value) {
        return value instanceof Double d ? new Reply.FormattedDouble(DoubleFormat.fixed(d, 17, true)) : value;
    }

    private static Object positions(Object value) {
        Object[] arr = toArray(value);
        if (arr == null) {
            return value;
        }
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = coordinatePair(arr[i]);
        }
        return out;
    }

    private static Object coordinatePair(Object value) {
        if (value == null) {
            return Reply.Null.ARRAY; // GEOPOS of a missing member
        }
        Object[] pair = toArray(value);
        if (pair == null) {
            return value;
        }
        Object[] out = new Object[pair.length];
        for (int i = 0; i < pair.length; i++) {
            out[i] = coordinate(pair[i]);
        }
        return out;
    }

    /**
     * With WITHDIST / WITHHASH / WITHCOORD, Glide returns [member, [dist?, hash?, [lon, lat]?]] per
     * result; RESP2 is [member, dist?, hash?, [lon, lat]?].
     */
    private static Object geoResults(byte[][] args, Object value) {
        boolean withDist = hasOption(args, "WITHDIST");
        boolean withHash = hasOption(args, "WITHHASH");
        boolean withCoord = hasOption(args, "WITHCOORD");
        Object[] results = toArray(value);
        if (results == null || !(withDist || withHash || withCoord)) {
            return value;
        }
        Object[] out = new Object[results.length];
        for (int r = 0; r < results.length; r++) {
            Object[] item = toArray(results[r]);
            Object[] extras = item != null && item.length == 2 ? toArray(item[1]) : null;
            if (extras == null) {
                return value;
            }
            Object[] flat = new Object[1 + extras.length];
            flat[0] = item[0];
            int i = 0;
            if (withDist && i < extras.length) {
                flat[1 + i] = distance(extras[i]);
                i++;
            }
            if (withHash && i < extras.length) {
                flat[1 + i] = extras[i];
                i++;
            }
            if (withCoord && i < extras.length) {
                flat[1 + i] = coordinatePair(extras[i]);
            }
            out[r] = flat;
        }
        return out;
    }

    // --- helpers ---

    private static GlideString bulk(String s) {
        return GlideString.of(s);
    }

    /**
     * FT.SEARCH: Glide makes {@code [total, key, fields, key, fields, ...]} into
     * {@code [total, {key: {field: value}}]}, and NOCONTENT's {@code [total, key, key, ...]} into keys
     * with empty maps. A reply of the total alone (LIMIT 0 0) is left as it is.
     */
    private static Object searchResults(byte[][] args, Object value) {
        if (!(value instanceof Object[] reply) || reply.length != 2 || !(reply[1] instanceof Map<?, ?> docs)) {
            return value;
        }
        boolean keysOnly = hasOption(args, "NOCONTENT")
                && docs.values().stream().allMatch(fields -> fields instanceof Map<?, ?> m && m.isEmpty());
        Object[] out = new Object[1 + docs.size() * (keysOnly ? 1 : 2)];
        int i = 0;
        out[i++] = reply[0];
        for (Map.Entry<?, ?> doc : docs.entrySet()) {
            out[i++] = doc.getKey();
            if (!keysOnly) {
                out[i++] = arrays(doc.getValue());
            }
        }
        return out;
    }

    /**
     * FT.AGGREGATE: Glide drops the leading count and makes each row's {@code [field, value, ...]} a
     * map. The count comes back as the number of rows, which is what the search module sends.
     */
    private static Object aggregateRows(Object value) {
        Object[] rows = toArray(value);
        if (rows == null) {
            return value;
        }
        Object[] out = new Object[rows.length + 1];
        out[0] = (long) rows.length;
        for (int i = 0; i < rows.length; i++) {
            out[i + 1] = arrays(rows[i]);
        }
        return out;
    }

    /** Maps as flat {@code [key, value, ...]} arrays, at every level. */
    private static Object arrays(Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                Object[] out = new Object[map.size() * 2];
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    out[i++] = entry.getKey();
                    out[i++] = arrays(entry.getValue());
                }
                yield out;
            }
            case Object[] items -> {
                Object[] out = new Object[items.length];
                for (int i = 0; i < items.length; i++) {
                    out[i] = arrays(items[i]);
                }
                yield out;
            }
            case null, default -> value;
        };
    }

    private static Object[] toArray(Object value) {
        return switch (value) {
            case Object[] arr -> arr;
            case Collection<?> c -> c.toArray();
            case null, default -> null;
        };
    }

    private static boolean hasOption(byte[][] args, String option) {
        for (int i = 1; i < args.length; i++) {
            if (args[i].length == option.length()
                    && new String(args[i], StandardCharsets.US_ASCII).equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }
}
