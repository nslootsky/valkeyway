/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Keyed commands whose reply, as Glide hands it to us, can be a map at the top level.
 *
 * <p>Only these need an explicit single-node route (see {@link SlotRoutes}): Glide's
 * {@code ClusterValue.of()} only misreads a reply when its top level is a map. Maps nested inside
 * an array (XAUTOCLAIM, ZMPOP, ...) arrive intact. Routing everything would also work but costs
 * measurable throughput: Glide's routed call path adds ~1.5 µs of CPU per request.
 *
 * <p>The list comes from Glide's per-command reply conversion ({@code expected_type_for_cmd} in
 * glide-core's value_conversion.rs) plus commands whose RESP3 reply is natively a map. Keyless
 * commands are not covered: their map replies (CONFIG GET, MEMORY STATS, ...) have ASCII keys
 * whose order doesn't matter.
 *
 * <p>Relies on upstream behavior: [upstream:G5] [upstream:G6] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
public final class MapReplyCommands {

    private static final Set<String> NAMES = Set.of(
            "hgetall",
            "zrange", "zunion", "zinter", "zdiff",      // WITHSCORES -> map of member to score
            "zpopmin", "zpopmax",
            "xrange", "xrevrange", "xclaim",            // map of id to fields
            "xread", "xreadgroup",                      // map of stream to entries
            "xinfo",
            "lcs",                                      // IDX -> map
            "lmpop", "blmpop");                         // map of key to elements

    /**
     * Keyless commands with a top-level map reply that Glide routes by their first argument: routed
     * to that argument's slot explicitly, as Glide would, so the reply isn't misread. (FT.INFO's
     * first argument is an index name; Glide makes its reply a map.) [upstream:G16]
     */
    private static final Set<String> BY_FIRST_ARGUMENT = Set.of("ft.info");

    private MapReplyCommands() {}

    /** Whether {@code args} is routed by its first argument and its reply can be a top-level map. */
    public static boolean routedByFirstArgument(byte[][] args) {
        return args.length >= 2 && args[0].length == 7 && (args[0][0] | 0x20) == 'f'
                && BY_FIRST_ARGUMENT.contains(new String(args[0], StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT));
    }

    /** Whether the reply to {@code args} (args[0] = command name) can be a top-level map. */
    public static boolean mayReturnMap(byte[][] args) {
        if (args.length < 2 || args[0].length < 3 || args[0].length > 10) {
            return false;
        }
        // Cheap pre-check: every name above starts with h, z, x, l or b.
        switch (args[0][0] | 0x20) {
            case 'h', 'z', 'x', 'l', 'b' -> { }
            default -> {
                return false;
            }
        }
        return NAMES.contains(new String(args[0], StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT));
    }
}
