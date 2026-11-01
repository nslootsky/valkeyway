/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend.routing;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.nslootsky.valkeyway.backend.routing.CommandKeyExtractor.KeySpec;
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

        assertEquals(new KeySpec(-3, 1, -1, 2, false, Map.of()), specs.get("mset"));
        assertTrue(specs.get("eval").movable());
        KeySpec encoding = specs.get("object").subcommands().get("object|encoding");
        assertNotNull(encoding);
        assertEquals(3, encoding.arity());
        assertEquals(List.of("foo"), keys(encoding, "OBJECT", "ENCODING", "foo"));
    }

    // --- queueError: what MULTI rejects at queue time (formats checked against Valkey in TransactionFidelityTest) ---

    private static final Map<String, KeySpec> ARITY_TABLE = Map.of(
            "get", new KeySpec(2, 1, 1, 1, false, Map.of()),
            "mset", new KeySpec(-3, 1, -1, 2, false, Map.of()),
            "config", new KeySpec(-2, 0, 0, 0, false, Map.of("config|get", new KeySpec(-3, 0, 0, 0, false, Map.of()))),
            "subscribe", new KeySpec(-2, 0, 0, 0, false, true, Map.of()));

    private static String queueError(String... parts) {
        return new CommandKeyExtractor(ARITY_TABLE).queueError(null, args(parts));
    }

    @Test
    void queueErrorsMatchValkey() {
        assertNull(queueError("GET", "k"));
        assertNull(queueError("MSET", "a", "1", "b", "2"));
        assertNull(queueError("config", "get", "maxmemory"));
        assertEquals("ERR wrong number of arguments for 'get' command", queueError("GET"));
        assertEquals("ERR wrong number of arguments for 'mset' command", queueError("MSET", "a"));
        assertEquals("ERR wrong number of arguments for 'config' command", queueError("CONFIG"));
        assertEquals("ERR wrong number of arguments for 'config|get' command", queueError("CONFIG", "GET"));
        assertEquals("ERR unknown subcommand 'nope'. Try CONFIG HELP.", queueError("config", "nope"));
        assertEquals("ERR unknown command 'nosuch', with args beginning with: 'a' 'b c' ", queueError("nosuch", "a", "b c"));
        assertEquals("ERR unknown command 'nosuch', with args beginning with: ", queueError("nosuch"));
        assertEquals("ERR Command 'subscribe' not allowed inside a transaction", queueError("SUBSCRIBE", "ch"));
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
        assertEquals(List.of("a", "b"), parse("msetex", "2", "a", "1", "b", "2", "EX", "10"));
        assertEquals(List.of("a"), parse("msetex", "1", "a", "1"));
        assertNull(parse("msetex", "2", "a", "1", "b"), "a key without its value");
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

    /** After a cluster upgrade adds a command, the next unknown-command check reloads the table, at most once a minute. */
    @Test
    void anUnknownCommandReloadsTheTableAtMostOncePerInterval() {
        AtomicLong now = new AtomicLong(0);
        AtomicInteger loads = new AtomicInteger();
        Map<String, KeySpec> old = Map.of("get", new KeySpec(2, 1, 1, 1, false, Map.of()));
        Map<String, KeySpec> upgraded = Map.of("get", new KeySpec(2, 1, 1, 1, false, Map.of()),
                "newcmd", new KeySpec(-1, 0, 0, 0, false, Map.of()));
        CommandKeyExtractor extractor = new CommandKeyExtractor(
                client -> loads.incrementAndGet() == 1 ? old : upgraded, now::get);

        assertNull(extractor.queueError(null, args("GET", "k")));
        assertEquals(1, loads.get());
        now.set(TimeUnit.SECONDS.toNanos(5));
        assertTrue(extractor.queueError(null, args("NEWCMD")).startsWith("ERR unknown command"),
                "within the interval: no reload, still unknown");
        assertEquals(1, loads.get());
        now.set(TimeUnit.SECONDS.toNanos(61));
        assertNull(extractor.queueError(null, args("NEWCMD")), "reloaded: now known");
        assertEquals(2, loads.get());
        assertTrue(extractor.queueError(null, args("NOSUCH")).startsWith("ERR unknown command"));
        assertEquals(2, loads.get(), "a fresh table isn't reloaded again right away");
    }
}
