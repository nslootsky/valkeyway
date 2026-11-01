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

    /** A stream with a consumer group in which alice holds entries 1-1..1-3 pending. */
    private static final List<String[]> GROUP = concat(streamOf(5),
            c("XGROUP", "CREATE", "%s", "g1", "0"),
            c("XREADGROUP", "GROUP", "g1", "alice", "COUNT", "3", "STREAMS", "%s", ">"));

    private static final List<String[]> GEO = List.of(c("DEL", "%g"),
            c("GEOADD", "%g", "13.361389", "38.115556", "Palermo", "15.087269", "37.502669", "Catania"));

    static Stream<Case> cases() {
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
                of("XCLAIM JUSTID", GROUP, "XCLAIM", "%s", "g1", "carol", "0", "1-1", "JUSTID"),
                of("XINFO STREAM", GROUP, "XINFO", "STREAM", "%s"),
                of("XINFO GROUPS", GROUP, "XINFO", "GROUPS", "%s"),

                // Pair-shaped replies
                of("BZPOPMIN with data", zsetOf(3), "BZPOPMIN", "%z", "1"),
                of("LMPOP", List.<String[]>of(c("DEL", "%l"), c("RPUSH", "%l", "a", "b", "c")), "LMPOP", "1", "%l", "LEFT", "COUNT", "2"),
                of("BLMPOP with data", List.<String[]>of(c("DEL", "%l"), c("RPUSH", "%l", "a", "b", "c")),
                        "BLMPOP", "1", "1", "%l", "RIGHT", "COUNT", "2"),

                // Geo (Glide parses distances and coordinates as doubles)
                of("GEODIST", List.<String[]>of(c("DEL", "%g"), c("GEOADD", "%g", "13.361389", "38.115556", "Palermo",
                        "15.087269", "37.502669", "Catania")), "GEODIST", "%g", "Palermo", "Catania", "km"),

                // Replies Glide reshapes, restored to RESP2 shape
                of("ZRANGEBYSCORE WITHSCORES", zsetOf(50), "ZRANGEBYSCORE", "%z", "5", "40", "WITHSCORES"),
                of("XRANGE", streamOf(60), "XRANGE", "%s", "-", "+"),
                of("XREVRANGE COUNT", streamOf(20), "XREVRANGE", "%s", "+", "-", "COUNT", "5"),
                of("XREAD two streams", concat(streamOf(4), c("DEL", "%s2"), c("XADD", "%s2", "2-1", "a", "b")),
                        "XREAD", "COUNT", "10", "STREAMS", "%s", "%s2", "0", "0"),
                of("XREADGROUP new entries", concat(streamOf(5), c("XGROUP", "CREATE", "%s", "g1", "0")),
                        "XREADGROUP", "GROUP", "g1", "bob", "COUNT", "2", "STREAMS", "%s", ">"),
                of("XREADGROUP pending with deleted entry", concat(GROUP, c("XDEL", "%s", "1-2")),
                        "XREADGROUP", "GROUP", "g1", "alice", "STREAMS", "%s", "0"),
                of("XCLAIM", GROUP, "XCLAIM", "%s", "g1", "carol", "0", "1-1", "1-3"),
                of("XAUTOCLAIM", GROUP, "XAUTOCLAIM", "%s", "g1", "carol", "0", "0-0", "COUNT", "2"),
                of("ZRANDMEMBER WITHSCORES", List.<String[]>of(c("DEL", "%r"), c("ZADD", "%r", "1.5", "only")),
                        "ZRANDMEMBER", "%r", "-3", "WITHSCORES"),
                of("HRANDFIELD WITHVALUES", List.<String[]>of(c("DEL", "%rh"), c("HSET", "%rh", "only", "v")),
                        "HRANDFIELD", "%rh", "-3", "WITHVALUES"),
                of("ZMPOP", zsetOf(10), "ZMPOP", "1", "%z", "MIN", "COUNT", "3"),
                of("BZMPOP with data", zsetOf(10), "BZMPOP", "1", "1", "%z", "MAX", "COUNT", "2"),
                of("GEOPOS", List.<String[]>of(c("DEL", "%g"), c("GEOADD", "%g", "13.361389", "38.115556", "Palermo")),
                        "GEOPOS", "%g", "Palermo", "nope"),
                of("GEOSEARCH WITHDIST WITHCOORD", List.<String[]>of(c("DEL", "%g"),
                        c("GEOADD", "%g", "13.361389", "38.115556", "Palermo", "15.087269", "37.502669", "Catania")),
                        "GEOSEARCH", "%g", "FROMLONLAT", "15", "37", "BYRADIUS", "200", "km", "ASC",
                        "WITHDIST", "WITHCOORD"),
                of("XAUTOCLAIM JUSTID", GROUP, "XAUTOCLAIM", "%s", "g1", "carol", "0", "0-0", "JUSTID"),
                of("ZREVRANGE WITHSCORES", zsetOf(20), "ZREVRANGE", "%z", "0", "9", "WITHSCORES"),
                of("ZREVRANGEBYSCORE WITHSCORES", zsetOf(20), "ZREVRANGEBYSCORE", "%z", "15", "3", "WITHSCORES"),
                of("ZRANDMEMBER without scores", List.<String[]>of(c("DEL", "%r"), c("ZADD", "%r", "1", "only")),
                        "ZRANDMEMBER", "%r", "-2"),
                of("GEOSEARCH plain", GEO, "GEOSEARCH", "%g", "FROMLONLAT", "15", "37", "BYRADIUS", "200", "km", "ASC"),
                of("GEOSEARCH WITHCOORD", GEO, "GEOSEARCH", "%g", "FROMMEMBER", "Palermo", "BYBOX", "400", "400", "km",
                        "ASC", "WITHCOORD"),
                of("GEORADIUS WITHDIST", GEO, "GEORADIUS", "%g", "15", "37", "200", "km", "WITHDIST", "ASC"),
                of("EXEC with reshaped replies", concat(streamOf(3),
                        c("DEL", "%r"), c("ZADD", "%r", "2.5", "only"),
                        c("MULTI"), c("XRANGE", "%s", "-", "+"), c("ZRANDMEMBER", "%r", "-2", "WITHSCORES"),
                        c("ZSCORE", "%r", "only")), "EXEC"),

                // 64-bit integers, null kinds and status replies (new protocol layer)
                of("INCR past 2^31", List.<String[]>of(c("SET", "%n", "5000000000")), "INCR", "%n"),
                of("DBSIZE-like large INCRBY", List.<String[]>of(c("SET", "%n", "1")), "INCRBY", "%n", "9000000000000000000"),
                of("TYPE is a status reply", zsetOf(1), "TYPE", "%z"),
                of("SET is +OK", List.of(), "SET", "%k", "v"),
                of("GET missing is null bulk", List.<String[]>of(c("DEL", "%k")), "GET", "%k"),
                of("LPOP missing is null bulk", List.<String[]>of(c("DEL", "%l")), "LPOP", "%l"),
                of("LPOP count missing is null array", List.<String[]>of(c("DEL", "%l")), "LPOP", "%l", "2"),
                of("ZRANK missing", zsetOf(2), "ZRANK", "%z", "nope"),
                of("ZRANK WITHSCORE missing is null array", zsetOf(2), "ZRANK", "%z", "nope", "WITHSCORE"),
                of("ZSCORE missing is null bulk", zsetOf(2), "ZSCORE", "%z", "nope"),
                of("BLPOP timeout is null array", List.<String[]>of(c("DEL", "%l")), "BLPOP", "%l", "0.1"),
                of("BLMOVE timeout is null array", List.<String[]>of(c("DEL", "%l")), "BLMOVE", "%l", "%l2", "LEFT", "LEFT", "0.1"),
                of("BZPOPMIN timeout is null array", List.<String[]>of(c("DEL", "%z")), "BZPOPMIN", "%z", "0.1"),
                of("LMPOP empty is null array", List.<String[]>of(c("DEL", "%l")), "LMPOP", "1", "%l", "LEFT"),
                of("ZMPOP empty is null array", List.<String[]>of(c("DEL", "%z")), "ZMPOP", "1", "%z", "MIN"),
                of("XREAD no data is null array", streamOf(2), "XREAD", "STREAMS", "%s", "$"),
                of("XREAD BLOCK timeout is null array", streamOf(2), "XREAD", "BLOCK", "100", "STREAMS", "%s", "$"),
                of("XRANGE COUNT 0 is null array", streamOf(2), "XRANGE", "%s", "-", "+", "COUNT", "0"),
                of("XPENDING summary with nothing pending",
                        concat(streamOf(2), c("XGROUP", "CREATE", "%s", "g9", "$")), "XPENDING", "%s", "g9"),
                of("XPENDING summary with pending", GROUP, "XPENDING", "%s", "g1"),
                of("GEOPOS missing member is a nested null array", GEO, "GEOPOS", "%g", "nope", "Palermo"),
                of("GEOSEARCH WITHHASH (64-bit)", GEO, "GEOSEARCH", "%g", "FROMLONLAT", "15", "37", "BYRADIUS", "200", "km",
                        "ASC", "WITHDIST", "WITHHASH", "WITHCOORD"),
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

        // All of a case's keys share the prefix's hash tag, so one node owns them. Send everything,
        // including keyless commands such as MULTI/EXEC, to that node over one connection.
        RawRespClient owner = nodes().at(nodes().ownerOf(directPrefix));
        for (String[] cmd : testCase.setup()) {
            owner.call(withPrefix(cmd, directPrefix));
        }
        Object expected = owner.call(withPrefix(testCase.command(), directPrefix));

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

    /**
     * The same cases with setup and command sent through the proxy as one pipeline, so the command
     * runs inside a backend batch rather than on its own.
     */
    @ParameterizedTest(name = "pipelined: {0}")
    @MethodSource("cases")
    void pipelinedProxyReplyMatchesServer(Case testCase) throws Exception {
        int id = SEQ.incrementAndGet();
        String directPrefix = "{diff:" + id + ":d}";
        String proxyPrefix = "{diff:" + id + ":p}";

        RawRespClient owner = nodes().at(nodes().ownerOf(directPrefix));
        for (String[] cmd : testCase.setup()) {
            owner.call(withPrefix(cmd, directPrefix));
        }
        Object expected = owner.call(withPrefix(testCase.command(), directPrefix));

        List<String[]> pipeline = new ArrayList<>();
        for (String[] cmd : testCase.setup()) {
            pipeline.add(withPrefix(cmd, proxyPrefix));
        }
        pipeline.add(withPrefix(testCase.command(), proxyPrefix));
        Object actual;
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT)) {
            actual = rename(proxy.pipeline(pipeline).getLast(), proxyPrefix, directPrefix);
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

    private static DirectNodes nodes() {
        if (nodes == null) {
            nodes = new DirectNodes(cluster.getNodeAddresses());
        }
        return nodes;
    }

    @AfterAll
    static void closeNodes() throws IOException {
        if (nodes != null) {
            nodes.close();
        }
    }
}
