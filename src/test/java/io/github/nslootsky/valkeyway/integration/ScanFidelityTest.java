/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** SCAN options, binary keys and cursor ownership, through raw RESP. */
class ScanFidelityTest extends IntegrationTestBase {

    private static RawRespClient connect() throws Exception {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    /** Every key a full iteration returns. */
    private static Set<String> scanAll(RawRespClient c, String... options) throws Exception {
        Set<String> keys = new TreeSet<>();
        String cursor = "0";
        do {
            List<String> cmd = new ArrayList<>(List.of("SCAN", cursor));
            cmd.addAll(List.of(options));
            List<?> reply = (List<?>) c.call(cmd.toArray(String[]::new));
            cursor = (String) reply.get(0);
            for (Object key : (List<?>) reply.get(1)) {
                keys.add((String) key);
            }
        } while (!cursor.equals("0"));
        return keys;
    }

    /**
     * [upstream:G12] Glide's cluster scan replaces bytes that aren't valid UTF-8 with U+FFFD, in keys and
     * in MATCH patterns. This pins the current behavior; when Glide fixes it, this fails: update the
     * catalog entry and assert the keys come back intact.
     */
    @Test
    void glideCorruptsNonUtf8KeysInScan() throws Exception {
        try (RawRespClient c = connect()) {
            String binary = "\u00ffsbin\u0001a";
            c.call("SET", binary, "1");
            assertEquals("1", c.call("GET", binary), "other commands are binary-safe");
            String replacement = "\u00ef\u00bf\u00bd"; // U+FFFD in UTF-8, as ISO-8859-1 chars
            assertEquals(Set.of(replacement + "sbin\u0001a"), scanAll(c, "MATCH", "*sbin\u0001*", "COUNT", "100"));
            assertEquals(Set.of(), scanAll(c, "MATCH", "\u00ffsbin*", "COUNT", "100"), "a non-UTF-8 pattern matches nothing");
        }
    }

    @Test
    void typeFiltersByType() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "stype:h", "stype:s");
            c.call("HSET", "stype:h", "f", "v");
            c.call("SET", "stype:s", "v");
            assertEquals(Set.of("stype:h"), scanAll(c, "MATCH", "stype:*", "TYPE", "hash", "COUNT", "100"));
        }
    }

    @Test
    void optionErrorsMatchTheServer() throws Exception {
        try (RawRespClient proxy = connect(); DirectNodes nodes = new DirectNodes(cluster.getNodeAddresses())) {
            RawRespClient node = nodes.at(nodes.addresses().getFirst());
            for (String[] cmd : List.of(
                    new String[]{"SCAN", "0", "COUNT", "abc"},
                    new String[]{"SCAN", "0", "COUNT", "0"},
                    new String[]{"SCAN", "0", "TYPE", "nosuchtype"},
                    new String[]{"SCAN", "0", "BOGUS", "x"},
                    new String[]{"SCAN", "0", "MATCH"})) {
                Object fromNode = node.call(cmd);
                assertInstanceOf(Error.class, fromNode, String.join(" ", cmd));
                assertEquals(fromNode, proxy.call(cmd), String.join(" ", cmd));
            }
        }
    }

    @Test
    void aCursorBelongsToItsDatabaseAndIsUsedOnce() throws Exception {
        try (RawRespClient c = connect()) {
            for (int i = 0; i < 50; i++) {
                c.call("SET", "sdb:" + i, "v");
            }
            List<?> first = (List<?>) c.call("SCAN", "0", "MATCH", "sdb:*", "COUNT", "1");
            String cursor = (String) first.get(0);
            assertNotEquals("0", cursor, "50 keys don't fit in one COUNT 1 step");

            c.call("SELECT", "1");
            assertEquals(new Error("ERR invalid SCAN cursor"), c.call("SCAN", cursor, "MATCH", "sdb:*"),
                    "a database 0 cursor can't continue in database 1");
            c.call("SELECT", "0");

            List<?> again = (List<?>) c.call("SCAN", "0", "MATCH", "sdb:*", "COUNT", "1");
            String next = (String) again.get(0);
            c.call("SCAN", next, "MATCH", "sdb:*", "COUNT", "1");
            assertEquals(new Error("ERR invalid SCAN cursor"), c.call("SCAN", next, "MATCH", "sdb:*", "COUNT", "1"),
                    "each call hands out a new cursor id");
        }
    }
}
