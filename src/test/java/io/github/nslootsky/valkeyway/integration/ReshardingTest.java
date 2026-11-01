/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
            long slot = (Long) nodes.at(source).call("CLUSTER", "KEYSLOT", "{reshard}k");
            moveSlot(nodes, slot, source, target);
            assertEquals(target, nodes.ownerOf("{reshard}k"), "slot should now be served by the target");

            // Reads and writes through the proxy, whose slot map still points at the old owner.
            assertEquals("before", proxy.call("GET", "{reshard}k"));
            assertEquals(List.of("a", "1", "b", "2"), proxy.call("ZRANGE", "{reshard}z", "0", "-1", "WITHSCORES"));
            assertEquals(new Status("OK"), proxy.call("SET", "{reshard}k", "after"));
            assertEquals("after", nodes.at(target).call("GET", "{reshard}k"), "write must land on the new owner");
        }
    }

    /** Standard live slot migration: IMPORTING/MIGRATING, MIGRATE the keys, then assign the slot. */
    private static void moveSlot(DirectNodes nodes, long slot, String source, String target) throws Exception {
        String sourceId = (String) nodes.at(source).call("CLUSTER", "MYID");
        String targetId = (String) nodes.at(target).call("CLUSTER", "MYID");
        String s = Long.toString(slot);

        assertEquals(new Status("OK"), nodes.at(target).call("CLUSTER", "SETSLOT", s, "IMPORTING", sourceId));
        assertEquals(new Status("OK"), nodes.at(source).call("CLUSTER", "SETSLOT", s, "MIGRATING", targetId));

        List<?> keys = (List<?>) nodes.at(source).call("CLUSTER", "GETKEYSINSLOT", s, "1000");
        if (!keys.isEmpty()) {
            String[] hostPort = target.split(":");
            List<String> migrate = new ArrayList<>(List.of("MIGRATE", hostPort[0], hostPort[1], "", "0", "5000", "KEYS"));
            keys.forEach(k -> migrate.add((String) k));
            assertEquals(new Status("OK"), nodes.at(source).call(migrate.toArray(String[]::new)));
        }

        for (String address : nodes.addresses()) {
            assertEquals(new Status("OK"), nodes.at(address).call("CLUSTER", "SETSLOT", s, "NODE", targetId));
        }
    }
}
