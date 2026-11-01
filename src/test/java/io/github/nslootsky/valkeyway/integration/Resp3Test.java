/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.RMap;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Verbatim;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.github.nslootsky.valkeyway.integration.RawRespClient.NULL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RESP3 (HELLO 3) for what the proxy answers itself, and protocol switches. Replies forwarded to the
 * cluster are compared with a node's in {@code DifferentialRepliesTest}.
 */
class Resp3Test extends IntegrationTestBase {

    private static RawRespClient connect() throws IOException {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    private static RawRespClient node() throws IOException {
        String[] address = cluster.getNodeAddress(0).split(":");
        return new RawRespClient(address[0], Integer.parseInt(address[1]));
    }

    private static List<Object> keys(RMap map) {
        List<Object> keys = new ArrayList<>();
        for (int i = 0; i < map.keysAndValues().size(); i += 2) {
            keys.add(map.keysAndValues().get(i));
        }
        return keys;
    }

    private static Object get(RMap map, String key) {
        for (int i = 0; i < map.keysAndValues().size(); i += 2) {
            if (key.equals(map.keysAndValues().get(i))) {
                return map.keysAndValues().get(i + 1);
            }
        }
        return fail("no " + key + " in " + map);
    }

    /** Sorts the members of every set, which have no order. */
    private static Object unordered(Object reply) {
        return switch (reply) {
            case RawRespClient.RSet(List<Object> items) -> new RawRespClient.RSet(
                    items.stream().map(Resp3Test::unordered).sorted(java.util.Comparator.comparing(String::valueOf)).toList());
            case RMap(List<Object> kv) -> new RMap(kv.stream().map(Resp3Test::unordered).toList());
            case List<?> list -> list.stream().map(Resp3Test::unordered).toList();
            case null, default -> reply;
        };
    }

    @Test
    void helloSwitchesToResp3AndRepliesWithAMap() throws Exception {
        try (RawRespClient c = connect(); RawRespClient node = node()) {
            RMap hello = assertInstanceOf(RMap.class, c.call("HELLO", "3"));
            RMap nodeHello = assertInstanceOf(RMap.class, node.call("HELLO", "3"));
            assertEquals(keys(nodeHello), keys(hello), "the same fields as a server's");
            assertEquals(3L, get(hello, "proto"));
            assertEquals("standalone", get(hello, "mode"));
            assertEquals(NULL, c.call("GET", "resp3:missing"), "RESP3's null");

            assertEquals(2L, ((List<?>) c.call("HELLO", "2")).get(5), "HELLO 2 switches back, flat");
            assertNull(c.call("GET", "resp3:missing"), "RESP2's null bulk string");
        }
    }

    /** As on a server, each reply is encoded in the protocol in effect when its command ran. */
    @Test
    void aPipelinedSwitchAppliesFromHelloOn() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "resp3:h");
            c.call("HSET", "resp3:h", "f", "v");
            List<Object> replies = c.pipeline(List.of(
                    new String[]{"HGETALL", "resp3:h"}, new String[]{"GET", "resp3:missing"},
                    new String[]{"HELLO", "3"},
                    new String[]{"HGETALL", "resp3:h"}, new String[]{"GET", "resp3:missing"},
                    new String[]{"HELLO", "2"},
                    new String[]{"GET", "resp3:missing"}));
            assertEquals(List.of("f", "v"), replies.get(0));
            assertNull(replies.get(1));
            assertInstanceOf(RMap.class, replies.get(2));
            assertEquals(new RMap(List.of("f", "v")), replies.get(3));
            assertEquals(NULL, replies.get(4));
            assertInstanceOf(List.class, replies.get(5));
            assertNull(replies.get(6));
        }
    }

    @Test
    void resetGoesBackToResp2() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("HELLO", "3");
            assertEquals(new Status("RESET"), c.call("RESET"));
            assertNull(c.call("GET", "resp3:missing"));
        }
    }

    @Test
    void textRepliesAreVerbatimStrings() throws Exception {
        try (RawRespClient c = connect(); RawRespClient node = node()) {
            c.call("HELLO", "3");
            node.call("HELLO", "3");
            assertInstanceOf(Verbatim.class, node.call("INFO", "server"), "a server's INFO in RESP3");

            Verbatim info = assertInstanceOf(Verbatim.class, c.call("INFO", "server"));
            assertEquals("txt", info.format());
            assertTrue(info.text().contains("role:master"), info.text());

            Verbatim list = assertInstanceOf(Verbatim.class, c.call("CLIENT", "LIST", "ID", c.call("CLIENT", "ID").toString()));
            assertTrue(list.text().contains(" resp=3 "), list.text());
            assertInstanceOf(Verbatim.class, c.call("CLIENT", "INFO"));
            assertInstanceOf(Verbatim.class, c.call("PROXY", "STATS"));
        }
    }

    @Test
    void commandDocsMatchAServers() throws Exception {
        try (RawRespClient c = connect(); RawRespClient node = node()) {
            c.call("HELLO", "3");
            node.call("HELLO", "3");
            // History is a set, whose order Glide doesn't keep.
            assertEquals(unordered(node.call("COMMAND", "DOCS", "SET")), unordered(c.call("COMMAND", "DOCS", "SET")));
            assertEquals(unordered(node.call("COMMAND", "INFO", "GET")), unordered(c.call("COMMAND", "INFO", "GET")));
            assertEquals(unordered(node.call("COMMAND", "INFO", "KEYS")), unordered(c.call("COMMAND", "INFO", "KEYS")));
        }
    }

    @Test
    void transactionsAndScan() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("HELLO", "3");
            c.call("DEL", "{resp3}a");
            c.call("MULTI");
            c.call("HSET", "{resp3}a", "f", "1");
            c.call("HGETALL", "{resp3}a");
            c.call("GET", "{resp3}missing");
            assertEquals(List.of(1L, new RMap(List.of("f", "1")), NULL), c.call("EXEC"));

            List<?> scan = assertInstanceOf(List.class, c.call("SCAN", "0", "MATCH", "{resp3}*", "COUNT", "1000"));
            assertEquals(2, scan.size());
        }
    }
}
