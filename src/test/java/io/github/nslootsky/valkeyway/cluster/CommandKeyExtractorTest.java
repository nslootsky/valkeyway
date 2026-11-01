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

    // --- singleSlot: what the proxy routes explicitly ---

    private static final Map<String, KeySpec> TABLE = Map.of(
            "get", new KeySpec(1, 1, 1, false, Map.of()),
            "mget", new KeySpec(1, -1, 1, false, Map.of()),
            "ping", new KeySpec(0, 0, 0, false, Map.of()),
            "eval", new KeySpec(0, 0, 0, true, Map.of()),
            "xread", new KeySpec(0, 0, 0, true, Map.of()),
            "zunionstore", new KeySpec(1, 1, 1, true, Map.of()),
            "migrate", new KeySpec(0, 0, 0, true, Map.of()),
            "object", new KeySpec(0, 0, 0, false, Map.of("object|encoding", new KeySpec(2, 2, 1, false, Map.of()))));

    private static int slot(String... parts) {
        return new CommandKeyExtractor(TABLE).singleSlot(null, args(parts));
    }

    private static int slotOf(String key) {
        return ClusterSlots.slot(key.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void singleKeyCommandRoutesToItsSlot() {
        assertEquals(slotOf("foo"), slot("GET", "foo"));
    }

    @Test
    void multiKeyCommandRoutesOnlyWhenAllKeysShareASlot() {
        assertEquals(slotOf("{u}a"), slot("MGET", "{u}a", "{u}b", "{u}c"));
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("MGET", "foo", "bar"), "Glide splits these itself");
    }

    @Test
    void keylessAndUnknownCommandsAreNotRouted() {
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("PING", "hello"));
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("NOSUCHCMD", "foo"));
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("GET"));
    }

    @Test
    void subcommandKeysAreUsed() {
        assertEquals(slotOf("foo"), slot("OBJECT", "ENCODING", "foo"));
    }

    @Test
    void movableKeysAreParsedLocally() {
        assertEquals(slotOf("{t}a"), slot("EVAL", "return 1", "2", "{t}a", "{t}b", "argv1"));
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("EVAL", "return 1", "0"), "no keys: any node");
        assertEquals(slotOf("{s}1"), slot("XREAD", "COUNT", "5", "STREAMS", "{s}1", "{s}2", "0", "0"));
        assertEquals(slotOf("{z}dst"), slot("ZUNIONSTORE", "{z}dst", "2", "{z}a", "{z}b", "WEIGHTS", "1", "2"));
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("ZUNIONSTORE", "{z}dst", "2", "{z}a", "other"));
    }

    @Test
    void unparseableMovableKeysFallBackToGlideRouting() {
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("EVAL", "return 1", "notanumber", "k"));
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("EVAL", "return 1", "5", "k"), "numkeys beyond args");
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("MIGRATE", "host", "6379", "k", "0", "1000"), "not parsed locally");
        assertEquals(CommandKeyExtractor.NO_SLOT, slot("XREAD", "STREAMS", "a", "b", "c"), "odd stream/id count");
    }

    @Test
    void movableKeyParserCoversCommonCommands() {
        assertEquals(List.of("a", "b"), parse("evalsha", "sha", "2", "a", "b", "x"));
        assertEquals(List.of("a", "b"), parse("fcall", "fn", "2", "a", "b"));
        assertEquals(List.of("a", "b"), parse("zunion", "2", "a", "b", "WITHSCORES"));
        assertEquals(List.of("a"), parse("lmpop", "1", "a", "LEFT"));
        assertEquals(List.of("a", "b"), parse("blmpop", "0.5", "2", "a", "b", "LEFT"));
        assertEquals(List.of("s1", "s2"), parse("xreadgroup", "GROUP", "g", "c", "STREAMS", "s1", "s2", ">", ">"));
        assertEquals(List.of("src", "dst"), parse("sort", "src", "BY", "w_*", "STORE", "dst"));
        assertEquals(List.of("g", "out"), parse("georadius", "g", "15", "37", "200", "km", "STOREDIST", "out"));
        assertNull(parse("migrate", "h", "1", "k", "0", "1"));
    }

    private static List<String> parse(String name, String... rest) {
        String[] all = new String[rest.length + 1];
        all[0] = name.toUpperCase();
        System.arraycopy(rest, 0, all, 1, rest.length);
        List<byte[]> keys = MovableKeys.parse(name, args(all));
        return keys == null ? null : keys.stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList();
    }
}
