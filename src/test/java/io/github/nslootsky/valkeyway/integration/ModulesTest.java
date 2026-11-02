/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.ValkeywayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JSON, Bloom and search modules through the proxy, on a cluster of valkey-bundle nodes with search
 * in coordinator mode. Search replies are compared with a node's, as DifferentialRepliesTest does for
 * core commands: Glide reshapes several of them by command name, and the proxy rebuilds them.
 */
// [upstream:G16] [upstream:V12]
@Tag("integration")
@SpringBootTest(
        classes = ValkeywayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.host=127.0.0.1", "proxy.port=" + ModulesTest.PORT}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ModulesTest {

    static final int PORT = 6390;
    private static final String INDEX = "mt:items";
    private static final int ITEMS = 12;

    private static final ValkeyClusterTestContainer cluster = ValkeyClusterTestContainer.withSearchCoordinator(3, 7600);
    private static DirectNodes nodes;

    static {
        cluster.start();
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("proxy.cluster-nodes", () -> cluster.getNodeAddresses().toArray(String[]::new));
    }

    /**
     * An index over hashes with a vector, a tag and a number. The vectors are ASCII: any four bytes
     * are a float32, and these grow with the letter (AAAA is about 12.1, BBBB 48.6, CCCC 195.3), so
     * item i is the i-th nearest to {@link #ORIGIN}.
     */
    @BeforeAll
    static void createIndex() throws Exception {
        nodes = new DirectNodes(cluster.getNodeAddresses());
        check(nodes.call("FT.CREATE", INDEX, "ON", "HASH", "PREFIX", "1", "mt:item:", "SCHEMA",
                "vec", "VECTOR", "FLAT", "6", "TYPE", "FLOAT32", "DIM", "2", "DISTANCE_METRIC", "L2",
                "color", "TAG", "n", "NUMERIC"));
        for (int i = 0; i < ITEMS; i++) {
            String letters = String.valueOf((char) ('A' + i)).repeat(8);
            check(nodes.call("HSET", "mt:item:" + i, "vec", letters, "color", i % 2 == 0 ? "red" : "blue",
                    "n", String.valueOf(i)));
        }
        awaitIndexed();
    }

    @AfterAll
    static void stopCluster() throws IOException {
        if (nodes != null) {
            nodes.close();
        }
        cluster.close();
    }

    private static final String ORIGIN = "AAAAAAAA";
    private static final String[] KNN3 = {"*=>[KNN 3 @vec $q]", "PARAMS", "2", "q", ORIGIN, "DIALECT", "2"};

    private static String[] search(String... rest) {
        return Stream.concat(Stream.of("FT.SEARCH", INDEX), Stream.of(rest)).toArray(String[]::new);
    }

    private static String[] knn3(String... options) {
        return search(Stream.concat(Stream.of(KNN3), Stream.of(options)).toArray(String[]::new));
    }

    static Stream<Arguments> searchCommands() {
        return Stream.of(
                Arguments.of("KNN", knn3()),
                Arguments.of("KNN, RETURN", knn3("RETURN", "1", "color")),
                Arguments.of("KNN, NOCONTENT", knn3("NOCONTENT")),
                Arguments.of("KNN, total only", knn3("LIMIT", "0", "0")),
                Arguments.of("KNN with a tag filter", search("@color:{blue}=>[KNN 2 @vec $q]", "PARAMS", "2", "q", ORIGIN,
                        "DIALECT", "2", "RETURN", "1", "n")),
                Arguments.of("numeric range", search("@n:[3 5]", "DIALECT", "2")),
                Arguments.of("no results", search("@n:[100 200]", "DIALECT", "2")),
                Arguments.of("aggregate", new String[]{"FT.AGGREGATE", INDEX, "@n:[0 20]", "LOAD", "1", "@color",
                        "DIALECT", "2"}),
                Arguments.of("aggregate with LIMIT", new String[]{"FT.AGGREGATE", INDEX, "@n:[0 20]", "LOAD", "1",
                        "@color", "LIMIT", "0", "3", "DIALECT", "2"}),
                Arguments.of("info", new String[]{"FT.INFO", INDEX}),
                Arguments.of("info, all primaries", new String[]{"FT.INFO", INDEX, "PRIMARY"}),
                Arguments.of("list", new String[]{"FT._LIST"}),
                Arguments.of("unknown index", new String[]{"FT.INFO", "mt:nosuch"}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("searchCommands")
    void searchRepliesMatchANode(String name, String[] command) throws Exception {
        Object expected;
        try (RawRespClient node = node()) {
            expected = node.call(command);
        }
        Object actual;
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", PORT)) {
            actual = proxy.call(command);
        }
        assertEquals(comparable(command, expected), comparable(command, actual), name);
    }

    /** The same, with the command in the middle of a pipeline, so the proxy sends it in a batch. */
    @ParameterizedTest(name = "pipelined: {0}")
    @MethodSource("searchCommands")
    void pipelinedSearchRepliesMatchANode(String name, String[] command) throws Exception {
        Object expected;
        try (RawRespClient node = node()) {
            expected = node.call(command);
        }
        List<Object> replies;
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", PORT)) {
            String[] neighbor = {"EXISTS", "mt:item:0"};
            replies = proxy.pipeline(List.of(neighbor, command, neighbor));
        }
        assertEquals(comparable(command, expected), comparable(command, replies.get(1)), name);
    }

    @ParameterizedTest(name = "RESP3: {0}")
    @MethodSource("searchCommands")
    void resp3SearchRepliesMatchANode(String name, String[] command) throws Exception {
        Object expected;
        try (RawRespClient node = node()) {
            node.call("HELLO", "3");
            expected = node.call(command);
        }
        Object actual;
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", PORT)) {
            proxy.call("HELLO", "3");
            actual = proxy.call(command);
        }
        assertEquals(comparable(command, expected), comparable(command, actual), name);
    }

    @Test
    void vectorSearchCoversEveryShard() throws Exception {
        assertTrue(primariesHoldingKeys() >= 2, "the items should be spread over the shards");
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", PORT)) {
            List<?> reply = (List<?>) proxy.call(knn3("NOCONTENT"));
            assertEquals(List.of(3L, "mt:item:0", "mt:item:1", "mt:item:2"), reply, "nearest first");
        }
    }

    @Test
    void jsonDocuments() {
        try (Jedis j = jedis()) {
            assertEquals("OK", text(call(j, "JSON.SET", "mt:{a}doc", "$", "{\"name\":\"shoe\",\"tags\":[\"run\"]}")));
            assertEquals("OK", text(call(j, "JSON.SET", "mt:{b}doc", "$", "{\"name\":\"sock\"}")));
            assertEquals("[\"shoe\"]", text(call(j, "JSON.GET", "mt:{a}doc", "$.name")));
            assertEquals(List.of(2L), call(j, "JSON.ARRAPPEND", "mt:{a}doc", "$.tags", "\"trail\""));
            // Keys in different slots: Glide splits the command by slot and joins the replies in order.
            List<?> names = (List<?>) call(j, "JSON.MGET", "mt:{a}doc", "mt:{b}doc", "mt:{c}missing", "$.name");
            assertEquals(3, names.size());
            assertEquals("[\"shoe\"]", text(names.get(0)));
            assertEquals("[\"sock\"]", text(names.get(1)));
            assertNull(names.get(2));
        }
    }

    @Test
    void bloomFilters() {
        try (Jedis j = jedis()) {
            assertEquals(1L, call(j, "BF.ADD", "mt:seen", "https://a.example"));
            assertEquals(1L, call(j, "BF.EXISTS", "mt:seen", "https://a.example"));
            assertEquals(0L, call(j, "BF.EXISTS", "mt:seen", "https://b.example"));
            assertEquals(List.of(1L, 0L), call(j, "BF.MADD", "mt:seen", "https://c.example", "https://a.example"));
        }
    }

    /** The node that owns the index name's slot: where Glide sends FT.* commands, and FT.INFO's scope. */
    private static RawRespClient node() throws IOException {
        String[] owner = nodes.ownerOf(INDEX).split(":");
        return new RawRespClient(owner[0], Integer.parseInt(owner[1]));
    }

    /**
     * A reply in a form that compares equal across what may legitimately differ: simple and bulk
     * strings (Glide doesn't tell them apart), the order of a document's fields (the module's own
     * order varies from call to call), and the order of results that have no ranking.
     */
    private static Object comparable(String[] command, Object reply) {
        Object plain = plain(reply);
        if (!(plain instanceof List<?> items) || items.isEmpty() || !(items.getFirst() instanceof Long total)) {
            return plain;
        }
        boolean aggregate = command[0].equalsIgnoreCase("FT.AGGREGATE");
        boolean keysOnly = items.stream().skip(1).allMatch(String.class::isInstance);
        List<Object> rest = new ArrayList<>();
        if (aggregate) {
            items.stream().skip(1).map(row -> sortedPairs((List<?>) row)).forEach(rest::add);
        } else if (keysOnly) {
            rest.addAll(items.subList(1, items.size()));
        } else {
            for (int i = 1; i + 1 < items.size(); i += 2) {
                rest.add(List.of(items.get(i), sortedPairs((List<?>) items.get(i + 1))));
            }
        }
        rest.sort(Comparator.comparing(String::valueOf));
        List<Object> out = new ArrayList<>();
        out.add(total);
        out.addAll(rest);
        return out;
    }

    private static List<List<Object>> sortedPairs(List<?> flat) {
        List<List<Object>> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            pairs.add(List.of(flat.get(i), flat.get(i + 1)));
        }
        pairs.sort(Comparator.comparing(pair -> String.valueOf(pair.getFirst())));
        return pairs;
    }

    /** Simple strings as plain strings, at every level. */
    private static Object plain(Object reply) {
        return switch (reply) {
            case RawRespClient.Status(String value) -> value;
            case List<?> list -> list.stream().map(ModulesTest::plain).toList();
            case null, default -> reply;
        };
    }

    private static void check(Object reply) {
        if (reply instanceof RawRespClient.Error(String message)) {
            throw new IllegalStateException(message);
        }
    }

    /** Waits until FT.INFO counts every item over all primaries (its PRIMARY scope). */
    private static void awaitIndexed() throws Exception {
        long count = -1;
        for (int attempt = 0; attempt < 100; attempt++) {
            List<?> info = (List<?>) plain(nodes.call("FT.INFO", INDEX, "PRIMARY"));
            for (int i = 0; i + 1 < info.size(); i += 2) {
                if ("num_docs".equals(info.get(i))) {
                    Object value = info.get(i + 1);
                    count = value instanceof Long n ? n : Long.parseLong(String.valueOf(value));
                }
            }
            if (count == ITEMS) {
                return;
            }
            Thread.sleep(100);
        }
        fail("indexed " + count + " of " + ITEMS + " items");
    }

    private static int primariesHoldingKeys() throws IOException {
        int holding = 0;
        for (String address : cluster.getNodeAddresses()) {
            String[] hostPort = address.split(":");
            try (RawRespClient client = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]))) {
                if ((Long) client.call("DBSIZE") > 0) {
                    holding++;
                }
            }
        }
        return holding;
    }

    private static Jedis jedis() {
        return new Jedis("127.0.0.1", PORT);
    }

    /** Sends any command through Jedis; strings are sent as UTF-8. */
    private static Object call(Jedis jedis, String command, String... args) {
        byte[][] raw = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            raw[i] = args[i].getBytes(StandardCharsets.UTF_8);
        }
        ProtocolCommand name = () -> command.getBytes(StandardCharsets.US_ASCII);
        return jedis.sendCommand(name, raw);
    }

    private static String text(Object reply) {
        return reply == null ? null : new String((byte[]) reply, StandardCharsets.UTF_8);
    }
}
