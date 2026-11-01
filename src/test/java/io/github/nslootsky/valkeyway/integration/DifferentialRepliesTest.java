/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs each command both directly against the node that owns its keys and through the proxy, on
 * identically prepared keys, and requires identical replies. "%" in a case stands for the case's
 * hash-tagged key prefix, so all of a case's keys share one slot.
 */
class DifferentialRepliesTest extends IntegrationTestBase {

    record Case(String name, List<String[]> setup, String[] command, boolean unordered) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static DirectNodes nodes;

    private static String[] c(String... args) {
        return args;
    }

    private static Case of(String name, List<String[]> setup, String... command) {
        return new Case(name, setup, command, false);
    }

    private static Case unordered(String name, List<String[]> setup, String... command) {
        return new Case(name, setup, command, true);
    }

    private static List<String[]> zsetOf(int n) {
        List<String> args = new ArrayList<>(List.of("ZADD", "%z"));
        for (int i = 1; i <= n; i++) {
            args.add(Integer.toString(i));
            args.add("m" + i);
        }
        return List.<String[]>of(c("DEL", "%z"), args.toArray(String[]::new));
    }

    private static List<String[]> streamOf(int n) {
        List<String[]> setup = new ArrayList<>();
        setup.add(c("DEL", "%s"));
        for (int i = 1; i <= n; i++) {
            setup.add(c("XADD", "%s", "1-" + i, "f" + i, "v" + i, "g", "x"));
        }
        return setup;
    }

    private static List<String[]> hashOf(int n) {
        List<String> args = new ArrayList<>(List.of("HSET", "%h"));
        for (int i = 1; i <= n; i++) {
            args.add("field" + i);
            args.add("value" + i);
        }
        return List.<String[]>of(c("DEL", "%h"), args.toArray(String[]::new));
    }

    private static List<String[]> concat(List<String[]> a, String[]... more) {
        List<String[]> all = new ArrayList<>(a);
        all.addAll(Arrays.asList(more));
        return all;
    }

    static Stream<Case> cases() {
        List<String[]> group = concat(streamOf(5),
                c("XGROUP", "CREATE", "%s", "g1", "0"),
                c("XREADGROUP", "GROUP", "g1", "alice", "COUNT", "3", "STREAMS", "%s", ">"));
        return Stream.of(
                // Ordering and binary integrity (Glide's ClusterValue map handling)
                of("ZRANGE WITHSCORES keeps order", zsetOf(100), "ZRANGE", "%z", "0", "-1", "WITHSCORES"),
                of("ZRANGE REV BYSCORE LIMIT WITHSCORES", zsetOf(100),
                        "ZRANGE", "%z", "+inf", "-inf", "BYSCORE", "REV", "LIMIT", "10", "20", "WITHSCORES"),
                of("ZPOPMIN with count", zsetOf(30), "ZPOPMIN", "%z", "10"),
                of("ZPOPMAX single", zsetOf(30), "ZPOPMAX", "%z"),
                of("ZUNION WITHSCORES", concat(zsetOf(20), c("ZADD", "%z2", "5", "m3", "100", "x")),
                        "ZUNION", "2", "%z", "%z2", "WITHSCORES"),
                of("HGETALL large", hashOf(200), "HGETALL", "%h"),
                of("HGETALL binary field names",
                        List.<String[]>of(c("DEL", "%b"), c("HSET", "%b", "ÿþA", "v1", "plain", "\u0000\u0001")),
                        "HGETALL", "%b"),
                of("ZRANGE binary members WITHSCORES",
                        List.<String[]>of(c("DEL", "%bz"), c("ZADD", "%bz", "1", "ÿ\u0080", "2", "ok")),
                        "ZRANGE", "%bz", "0", "-1", "WITHSCORES"),
                of("LCS IDX", List.<String[]>of(c("SET", "%a", "ohmytext"), c("SET", "%b2", "mynewtext")),
                        "LCS", "%a", "%b2", "IDX", "MINMATCHLEN", "2", "WITHMATCHLEN"),
                of("CONFIG GET single", List.of(), "CONFIG", "GET", "maxmemory-policy"),

                // Numbers Glide turns into doubles
                of("ZSCORE", zsetOf(3), "ZSCORE", "%z", "m2"),
                of("ZMSCORE", zsetOf(3), "ZMSCORE", "%z", "m1", "nope", "m3"),
                of("ZINCRBY", zsetOf(3), "ZINCRBY", "%z", "0.5", "m1"),
                of("INCRBYFLOAT", List.<String[]>of(c("SET", "%f", "10.5")), "INCRBYFLOAT", "%f", "0.1"),
                of("HINCRBYFLOAT", List.<String[]>of(c("DEL", "%hf"), c("HSET", "%hf", "x", "1")), "HINCRBYFLOAT", "%hf", "x", "2.5"),
                of("ZRANK WITHSCORE", zsetOf(5), "ZRANK", "%z", "m3", "WITHSCORE"),
                of("ZADD INCR", zsetOf(3), "ZADD", "%z", "INCR", "1.25", "m1"),
                of("SMISMEMBER", List.<String[]>of(c("DEL", "%st"), c("SADD", "%st", "a", "b")), "SMISMEMBER", "%st", "a", "z", "b"),
                of("EXPIRE returns integer", List.<String[]>of(c("SET", "%e", "v")), "EXPIRE", "%e", "100"),

                // Streams (Glide reshapes these into maps)
                of("XCLAIM JUSTID", group, "XCLAIM", "%s", "g1", "carol", "0", "1-1", "JUSTID"),
                of("XINFO STREAM", group, "XINFO", "STREAM", "%s"),
                of("XINFO GROUPS", group, "XINFO", "GROUPS", "%s"),

                // Pair-shaped replies
                of("BZPOPMIN with data", zsetOf(3), "BZPOPMIN", "%z", "1"),
                of("LMPOP", List.<String[]>of(c("DEL", "%l"), c("RPUSH", "%l", "a", "b", "c")), "LMPOP", "1", "%l", "LEFT", "COUNT", "2"),
                of("BLMPOP with data", List.<String[]>of(c("DEL", "%l"), c("RPUSH", "%l", "a", "b", "c")),
                        "BLMPOP", "1", "1", "%l", "RIGHT", "COUNT", "2"),

                // Geo (Glide parses distances and coordinates as doubles)
                of("GEODIST", List.<String[]>of(c("DEL", "%g"), c("GEOADD", "%g", "13.361389", "38.115556", "Palermo",
                        "15.087269", "37.502669", "Catania")), "GEODIST", "%g", "Palermo", "Catania", "km"),

                // Sets come back from Glide as Java sets; members are unordered by definition
                unordered("SMEMBERS", List.<String[]>of(c("DEL", "%st"), c("SADD", "%st", "c", "a", "b", "ÿ")), "SMEMBERS", "%st"),
                unordered("SINTER", List.<String[]>of(c("DEL", "%st", "%st2"), c("SADD", "%st", "a", "b", "c"), c("SADD", "%st2", "b", "c")),
                        "SINTER", "%st", "%st2")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void proxyReplyMatchesServer(Case testCase) throws Exception {
        int id = SEQ.incrementAndGet();
        String directPrefix = "{diff:" + id + ":d}";
        String proxyPrefix = "{diff:" + id + ":p}";

        for (String[] cmd : testCase.setup()) {
            direct(withPrefix(cmd, directPrefix));
        }
        Object expected = direct(withPrefix(testCase.command(), directPrefix));

        Object actual;
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT)) {
            for (String[] cmd : testCase.setup()) {
                proxy.call(withPrefix(cmd, proxyPrefix));
            }
            actual = rename(proxy.call(withPrefix(testCase.command(), proxyPrefix)), proxyPrefix, directPrefix);
        }

        if (testCase.unordered()) {
            expected = sorted(expected);
            actual = sorted(actual);
        }
        assertEquals(expected, actual, testCase.name());
    }

    private static String[] withPrefix(String[] cmd, String prefix) {
        return Arrays.stream(cmd).map(a -> a.replace("%", prefix)).toArray(String[]::new);
    }

    private static Object rename(Object reply, String from, String to) {
        return switch (reply) {
            case String s -> s.replace(from, to);
            case List<?> list -> list.stream().map(v -> rename(v, from, to)).toList();
            case null, default -> reply;
        };
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object reply) {
        if (reply instanceof List<?> list) {
            List<Object> copy = new ArrayList<>((List<Object>) list);
            copy.sort(Comparator.comparing(String::valueOf));
            return copy;
        }
        return reply;
    }

    private static Object direct(String[] cmd) throws IOException {
        if (nodes == null) {
            nodes = new DirectNodes(cluster.getNodeAddresses());
        }
        return nodes.call(cmd);
    }

    @AfterAll
    static void closeNodes() throws IOException {
        if (nodes != null) {
            nodes.close();
        }
    }
}
