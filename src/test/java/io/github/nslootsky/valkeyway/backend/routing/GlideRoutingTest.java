/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor.KeySpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlideRoutingTest {

    private static byte[][] args(String line) {
        return Arrays.stream(line.split(" ")).map(s -> s.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
    }

    /** As in Valkey 9.1's COMMAND table: only MSETEX has a later first key and no Glide rule. */
    @Test
    void derivedFromTheCommandTable() {
        Map<String, KeySpec> table = Map.of(
                "set", new KeySpec(1, 1, 1, false, Map.of()),
                "sort", new KeySpec(1, 1, 1, true, Map.of()),              // movable, but the first key is arg 1
                "msetex", new KeySpec(0, 0, 0, true, Map.of()),            // key after numkeys
                "eval", new KeySpec(0, 0, 0, true, Map.of()),              // Glide has a rule
                "bitop", new KeySpec(2, -1, 1, false, Map.of()),           // Glide has a rule
                "object", new KeySpec(0, 0, 0, false, Map.of("object|encoding", new KeySpec(2, 2, 1, false, Map.of()))),
                "ping", new KeySpec(0, 0, 0, false, Map.of()));
        GlideRouting routing = GlideRouting.from(table);
        assertEquals(Set.of("msetex"), routing.names());
        assertTrue(routing.misroutes(args("MSETEX 2 a{t} 1 b{t} 2 EX 10")));
        assertTrue(routing.misroutes(args("msetex 1 a 1")));
        assertFalse(routing.misroutes(args("SET k v")));
        assertFalse(routing.misroutes(args("MSET a 1 b 2")));
        assertFalse(routing.misroutes(args("EVAL s 1 k")));
    }

    @Test
    void newCommandsWithALaterFirstKeyAreFound() {
        GlideRouting routing = GlideRouting.from(Map.of("futurecmd", new KeySpec(3, 3, 1, false, Map.of())));
        assertTrue(routing.misroutes(args("FUTURECMD a b key")));
        assertTrue(routing.misroutes(args("MSETEX 1 k v")), "known before the table loads, and kept");
    }
}
