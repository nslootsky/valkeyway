/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlideClientCacheTest {

    @Test
    void liveNodesSkipFailingUnknownAndDisconnectedNodes() {
        String reply = """
                07c3 10.0.0.1:6379@16379,node-a myself,master - 0 0 1 connected 0-5460
                67ed 10.0.0.2:6379@16379 master - 0 1426238316232 2 connected 5461-10922
                292f 10.0.0.3:6379@16379 master,fail - 1426238316232 1426238316232 3 disconnected
                6ec2 10.0.0.4:6379@16379 slave 67ed 0 1426238316232 2 connected
                824f :0@0 handshake - 0 0 0 disconnected
                9a1b 10.0.0.5:6379@16379 slave,fail? 07c3 0 0 1 connected
                c3d4 10.0.0.6:6379@16379 master - 0 0 4 disconnected 10923-16383
                """;
        assertEquals(List.of("10.0.0.1:6379", "10.0.0.2:6379", "10.0.0.4:6379"), GlideClientCache.parseClusterNodes(reply));
    }
}
