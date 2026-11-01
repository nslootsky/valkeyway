/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Commands Glide sends to the wrong node. Glide routes a command it has no rule for by its first
 * argument, as if that were the key (redis-rs {@code cluster_routing.rs}, {@code base_routing}:
 * {@code _ => RouteBy::FirstKey}). For a command whose first key comes later, after a key count for
 * instance, that's another key's slot.
 *
 * <p>The set is derived from the COMMAND table: commands whose first key isn't their first argument,
 * minus those Glide has a rule for. With Valkey 9.1 that's only MSETEX. Usually the wrong node
 * answers MOVED and Glide follows it, but Valkey 9.1.1 nodes run MSETEX for keys of slots they don't
 * own, storing keys where no read finds them. The proxy routes these commands to their keys' slot.
 */
// [upstream:G15]
public final class GlideRouting {

    /** Commands whose first key isn't their first argument that Glide routes correctly. */
    static final Set<String> GLIDE_RULES = Set.of(
            "eval", "evalsha", "eval_ro", "evalsha_ro", "fcall", "fcall_ro",  // key after numkeys
            "blmpop", "bzmpop",                                                 // after timeout and numkeys
            "lmpop", "zmpop", "sintercard", "zdiff", "zinter", "zintercard", "zunion",
            "xread", "xreadgroup",                                              // after STREAMS
            "migrate",                                                          // third argument
            "bitop", "pfdebug");                                                // second argument

    /** Used until the COMMAND table is loaded. */
    static final Set<String> KNOWN = Set.of("msetex");

    private final Set<String> names;
    /** Cheap pre-check: names' (length, first letter) pairs, so most commands exit without allocating. */
    private final boolean[] shapes = new boolean[32 * 26];

    private GlideRouting(Set<String> names) {
        this.names = names;
        for (String name : names) {
            int shape = shape(name.length(), name.charAt(0));
            if (shape >= 0) {
                shapes[shape] = true;
            }
        }
    }

    static GlideRouting of(Set<String> names) {
        return new GlideRouting(Set.copyOf(names));
    }

    /** From the COMMAND table: keyed commands whose first key isn't argument 1, without a Glide rule. */
    static GlideRouting from(Map<String, CommandKeyExtractor.KeySpec> table) {
        Set<String> names = new HashSet<>(KNOWN);
        table.forEach((name, spec) -> {
            boolean laterKey = spec.first() > 1 || (spec.first() == 0 && spec.movable());
            if (laterKey && spec.subcommands().isEmpty() && !GLIDE_RULES.contains(name)) {
                names.add(name);
            }
        });
        return of(names);
    }

    /** Whether Glide would send {@code args} (args[0] = command name) by the wrong argument. */
    boolean misroutes(byte[][] args) {
        byte[] name = args[0];
        if (name.length == 0) {
            return false;
        }
        int shape = shape(name.length, (char) (name[0] | 0x20));
        return shape >= 0 && shapes[shape]
                && names.contains(new String(name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT));
    }

    Set<String> names() {
        return names;
    }

    private static int shape(int length, char first) {
        return length < 32 && first >= 'a' && first <= 'z' ? length * 26 + (first - 'a') : -1;
    }
}
