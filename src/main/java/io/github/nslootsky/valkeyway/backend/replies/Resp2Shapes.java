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
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Restores the reply shapes a standalone server sends to RESP2 clients.
 *
 * <p>Glide talks RESP3 to the cluster and reshapes some replies by command name before we see them
 * (glide-core value_conversion.rs, {@code expected_type_for_cmd}): stream entries become maps of id
 * to field pairs, geo results get nested and parsed as doubles, and so on. Some RESP3 replies also
 * differ from RESP2 on their own (WITHSCORES results are arrays of pairs). This class maps those
 * values back, given the command that produced them.
 *
 * <p>Output uses {@code Object[]} for arrays and {@link GlideString} for strings that must be sent
 * as bulk strings (formatted numbers); everything else passes through unchanged.
 *
 * <p>Relies on upstream behavior: [upstream:G6] [upstream:G7] [upstream:V1] [upstream:V2] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class Resp2Shapes {

    private Resp2Shapes() {}

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
        // Every command reshaped below has a name of 5+ chars starting with x, z, h, g or b, so the
        // hot short commands (GET, SET, HGET, HSET, ZADD, ...) exit without allocating.
        if (args[0].length < 5) {
            return value;
        }
        switch (args[0][0] | 0x20) {
            case 'x', 'z', 'h', 'g', 'b' -> { }
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
            case "zrandmember", "zrangebyscore", "zrevrangebyscore", "zrevrange" ->
                    hasOption(args, "WITHSCORES") ? flattenPairs(value) : value;
            case "hrandfield" -> hasOption(args, "WITHVALUES") ? flattenPairs(value) : value;
            case "zmpop", "bzmpop" -> keyAndScoredMembers(value);
            case "geodist" -> distance(value);
            case "geopos" -> positions(value);
            case "geosearch", "georadius", "georadius_ro", "georadiusbymember", "georadiusbymember_ro" ->
                    geoResults(args, value);
            case "xpending" -> pendingSummary(args, value);
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

    /** {stream -> {id -> fields}} to [[stream, entries], ...]. */
    private static Object streams(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        Object[] out = new Object[map.size()];
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out[i++] = new Object[]{entry.getKey(), entries(entry.getValue())};
        }
        return out;
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

    /** Longitude/latitude: Valkey sends "%.17Lf" with trailing zeros trimmed. */
    private static Object coordinate(Object value) {
        return value instanceof Double d ? bulk(DoubleFormat.fixed(d, 17, true)) : value;
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
