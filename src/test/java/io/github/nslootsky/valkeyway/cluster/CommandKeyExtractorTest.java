/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.cluster;

import io.github.nslootsky.valkeyway.cluster.CommandKeyExtractor.KeySpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandKeyExtractorTest {

    private static byte[][] args(String... parts) {
        return Arrays.stream(parts).map(p -> p.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
    }

    private static List<String> keys(KeySpec spec, String... parts) {
        return CommandKeyExtractor.keysFromSpec(spec, args(parts)).stream()
                .map(b -> new String(b, StandardCharsets.UTF_8))
                .toList();
    }

    @Test
    void msetTakesEveryOtherArgument() {
        KeySpec mset = new KeySpec(1, -1, 2, false, Map.of());
        assertEquals(List.of("k1", "k2", "k3"), keys(mset, "MSET", "k1", "v1", "k2", "v2", "k3", "v3"));
    }

    @Test
    void delTakesAllArguments() {
        KeySpec del = new KeySpec(1, -1, 1, false, Map.of());
        assertEquals(List.of("a", "b", "c"), keys(del, "DEL", "a", "b", "c"));
    }

    @Test
    void singleKeyCommand() {
        KeySpec set = new KeySpec(1, 1, 1, false, Map.of());
        assertEquals(List.of("k"), keys(set, "SET", "k", "v", "EX", "10"));
    }

    @Test
    void keylessCommand() {
        KeySpec ping = new KeySpec(0, 0, 0, false, Map.of());
        assertEquals(List.of(), keys(ping, "PING", "hello"));
    }

    @Test
    void parsesCommandTableIncludingSubcommandsAndMovableFlag() {
        // Shapes as returned by Glide for COMMAND (see COMMAND INFO mset / eval / object).
        List<Object> table = List.of(
                new Object[]{"mset", -3L, Set.of("write", "denyoom"), 1L, -1L, 2L},
                new Object[]{"eval", -3L, Set.of("noscript", "movablekeys"), 0L, 0L, 0L},
                new Object[]{"object", -2L, Set.of(), 0L, 0L, 0L, Set.of(), new Object[0], new Object[0],
                        new Object[]{new Object[]{"object|encoding", 3L, Set.of("readonly"), 2L, 2L, 1L}}});

        Map<String, KeySpec> specs = CommandKeyExtractor.parseTable(table);

        assertEquals(new KeySpec(1, -1, 2, false, Map.of()), specs.get("mset"));
        assertTrue(specs.get("eval").movable());
        KeySpec encoding = specs.get("object").subcommands().get("object|encoding");
        assertNotNull(encoding);
        assertEquals(List.of("foo"), keys(encoding, "OBJECT", "ENCODING", "foo"));
    }
}
