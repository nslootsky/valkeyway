/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MSETEX, whose first key follows a key count. Glide would route it by the count ([upstream:G15]),
 * and nodes run it for keys of slots they don't own, so the keys would land where no read finds them.
 */
class MsetexRoutingTest extends IntegrationTestBase {

    @Test
    void keysLandOnTheNodeThatOwnsThem() throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT);
             DirectNodes nodes = new DirectNodes(cluster.getNodeAddresses())) {
            proxy.call("DEL", "{msx}a", "{msx}b");
            assertEquals(1L, proxy.call("MSETEX", "2", "{msx}a", "1", "{msx}b", "2", "EX", "100"));
            assertEquals(List.of("1", "2"), proxy.call("MGET", "{msx}a", "{msx}b"));
            long ttl = (Long) proxy.call("TTL", "{msx}a");
            assertTrue(ttl > 0 && ttl <= 100, "ttl " + ttl);
            // On the owner node itself, not just readable through the proxy.
            assertEquals("1", nodes.at(nodes.ownerOf("{msx}a")).call("GET", "{msx}a"));
        }
    }

    @Test
    void pipelinedMsetexIsRoutedToo() throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT)) {
            proxy.call("DEL", "{msp}a", "{msp}b");
            List<Object> replies = proxy.pipeline(List.of(
                    new String[]{"MSETEX", "1", "{msp}a", "x", "PX", "100000"},
                    new String[]{"MSETEX", "1", "{msp}b", "y", "PX", "100000"},
                    new String[]{"MGET", "{msp}a", "{msp}b"}));
            assertEquals(List.of(1L, 1L, List.of("x", "y")), replies);
        }
    }

    @Test
    void argumentErrorsComeBeforeCrossSlot() throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT)) {
            assertEquals(new Error("ERR wrong number of arguments for 'msetex' command"), proxy.call("MSETEX", "2"));
            assertEquals(new Error("ERR invalid numkeys value or out of range"), proxy.call("MSETEX", "0", "k", "v"));
            assertEquals(new Error("ERR invalid numkeys value or out of range"), proxy.call("MSETEX", "-1", "k", "v"));
        }
    }

    @Test
    void keysInSeveralSlotsAreCrossSlot() throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT)) {
            assertEquals(new Error("CROSSSLOT Keys in request don't hash to the same slot"),
                    proxy.call("MSETEX", "2", "msx:one", "1", "msx:two", "2", "EX", "10"));
        }
    }
}
