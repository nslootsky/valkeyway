/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.commands;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InfoCommandTest {

    private static InfoCommand.Sections node(String text) {
        return InfoCommand.parse(text.replace("\n", "\r\n"));
    }

    @Test
    void parsesSectionsAndFields() {
        InfoCommand.Sections s = node("# Server\nredis_version:9.1.1\nredis_mode:cluster\n\n# Stats\nexpired_keys:3\n");
        assertEquals("Server", s.titles().get("server"));
        assertEquals(Map.of("redis_version", "9.1.1", "redis_mode", "cluster"), s.fields().get("server"));
        assertEquals(List.of("server", "stats"), List.copyOf(s.fields().keySet()));
    }

    @Test
    void countersAreSummedAndTheRestTakenFromTheFirstNode() {
        List<InfoCommand.Sections> nodes = List.of(
                node("# Stats\nexpired_keys:3\nexpired_stale_perc:0.00\ntotal_net_input_bytes:100\n"),
                node("# Stats\nexpired_keys:4\nexpired_stale_perc:1.50\ntotal_net_input_bytes:50\n"));
        assertEquals(Map.of("expired_keys", "7", "expired_stale_perc", "0.00", "total_net_input_bytes", "150"),
                InfoCommand.sum(nodes, "stats"));
    }

    @Test
    void commandAndErrorStatsAreSummedAcrossNodes() {
        List<InfoCommand.Sections> nodes = List.of(
                node("# Commandstats\ncmdstat_get:calls=2,usec=10,usec_per_call=5.00,rejected_calls=0,failed_calls=0\n"),
                node("# Commandstats\ncmdstat_get:calls=3,usec=20,usec_per_call=6.67,rejected_calls=1,failed_calls=0\n"
                        + "cmdstat_set:calls=1,usec=4,usec_per_call=4.00,rejected_calls=0,failed_calls=0\n"));
        Map<String, String> stats = InfoCommand.sumPairs(nodes, "commandstats");
        assertEquals("calls=5,usec=30,usec_per_call=6.00,rejected_calls=1,failed_calls=0", stats.get("cmdstat_get"));
        assertEquals("calls=1,usec=4,usec_per_call=4.00,rejected_calls=0,failed_calls=0", stats.get("cmdstat_set"));

        Map<String, String> errors = InfoCommand.sumPairs(List.of(node("# Errorstats\n"),
                node("# Errorstats\nerrorstat_NOGROUP:count=1\n")), "errorstats");
        assertEquals(Map.of("errorstat_NOGROUP", "count=1"), errors);
    }

    @Test
    void keyspaceIsSummedPerDatabase() {
        List<InfoCommand.Sections> nodes = List.of(
                node("# Keyspace\ndb0:keys=10,expires=2,avg_ttl=1000\ndb9:keys=1,expires=0,avg_ttl=0\n"),
                node("# Keyspace\ndb0:keys=5,expires=2,avg_ttl=3000\n"));
        Map<String, String> keyspace = InfoCommand.keyspace(nodes);
        assertEquals("keys=15,expires=4,avg_ttl=2000", keyspace.get("db0"));
        assertEquals("keys=1,expires=0,avg_ttl=0", keyspace.get("db9"));
        assertEquals(List.of("db0", "db9"), List.copyOf(keyspace.keySet()));
    }
}
