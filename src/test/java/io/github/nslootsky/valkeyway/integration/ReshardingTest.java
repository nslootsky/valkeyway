/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The proxy routes single-slot commands to an explicit slot. After that slot moves to another
 * node, the proxy's (Glide's) slot map is stale; commands must still succeed by following MOVED.
 */
class ReshardingTest extends IntegrationTestBase {

    @Test
    void routedCommandsFollowASlotMovedToAnotherNode() throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT);
             DirectNodes nodes = new DirectNodes(cluster.getNodeAddresses())) {

            proxy.call("DEL", "{reshard}z", "{reshard}k");
            proxy.call("ZADD", "{reshard}z", "1", "a", "2", "b");
            proxy.call("SET", "{reshard}k", "before");
            // Warm up the proxy's routing for this slot.
            assertEquals(List.of("a", "1", "b", "2"), proxy.call("ZRANGE", "{reshard}z", "0", "-1", "WITHSCORES"));

            String source = nodes.ownerOf("{reshard}k");
            String target = nodes.addresses().stream().filter(a -> !a.equals(source)).findFirst().orElseThrow();
            nodes.moveSlot(nodes.slotOf("{reshard}k"), source, target);
            assertEquals(target, nodes.ownerOf("{reshard}k"), "slot should now be served by the target");

            // Reads and writes through the proxy, whose slot map still points at the old owner.
            assertEquals("before", proxy.call("GET", "{reshard}k"));
            assertEquals(List.of("a", "1", "b", "2"), proxy.call("ZRANGE", "{reshard}z", "0", "-1", "WITHSCORES"));
            assertEquals(new Status("OK"), proxy.call("SET", "{reshard}k", "after"));
            assertEquals("after", nodes.at(target).call("GET", "{reshard}k"), "write must land on the new owner");
        }
    }
}
