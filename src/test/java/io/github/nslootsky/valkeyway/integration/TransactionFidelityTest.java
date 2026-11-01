/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transactions answer like a Valkey node: the proxy queues them itself, so its queue-time errors and
 * EXEC results are compared with a direct connection to the node that owns the keys.
 * [upstream:V6]
 */
class TransactionFidelityTest extends IntegrationTestBase {

    private static String[] cmd(String... args) {
        return args;
    }

    @Test
    void queueTimeErrorsAndExecAbortMatchTheServer() throws Exception {
        String k = "{txf}k";
        List<String[]> script = List.of(
                cmd("SET", k, "before"),
                cmd("MULTI"),
                cmd("SET", k, "aborted"),
                cmd("NOSUCHCOMMAND", "a", "b c"),
                cmd("GET"),
                cmd("SET", k),
                cmd("CONFIG", "NOPE"),
                cmd("CONFIG"),
                cmd("CONFIG", "GET"),
                cmd("EXEC"),
                cmd("GET", k),
                // A valid transaction afterwards, including commands the proxy otherwise answers itself.
                cmd("MULTI"),
                cmd("SET", k, "v"),
                cmd("PING"),
                cmd("ECHO", "hi"),
                cmd("GET", k),
                cmd("EXEC"));
        assertSameAsServer(k, script);
    }

    @Test
    void execWithoutMultiAndNestedMultiMatchTheServer() throws Exception {
        String k = "{txf}n";
        assertSameAsServer(k, List.of(
                cmd("SET", k, "before"),
                cmd("EXEC"),
                cmd("DISCARD"),
                cmd("MULTI"),
                cmd("MULTI"),
                cmd("SET", k, "1"),
                cmd("EXEC"),
                cmd("GET", k),
                cmd("MULTI"),
                cmd("WATCH", k),
                cmd("SET", k, "2"),
                cmd("EXEC"),
                cmd("GET", k),
                cmd("MULTI"),
                cmd("EXEC")));
    }

    /** The proxy rejects cross-slot transactions with the error text a node uses for cross-slot keys. */
    @Test
    void crossSlotErrorUsesTheServersText() throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT);
             DirectNodes nodes = new DirectNodes(cluster.getNodeAddresses())) {
            proxy.call("MULTI");
            proxy.call("SET", "txf:a", "1");
            proxy.call("SET", "txf:b", "2");
            Object proxyError = proxy.call("EXEC");
            Object serverError = nodes.call("MSET", "txf:a", "1", "txf:b", "2");
            assertInstanceOf(Error.class, serverError, "MSET across slots on a node: " + serverError);
            assertEquals(serverError, proxyError);
        }
    }

    /** INFO, HELLO, SCAN, CLUSTER and PROXY are answered from proxy state, which EXEC on a node can't reproduce. */
    @Test
    void proxyAnsweredCommandsAreRejectedInsideMulti() throws Exception {
        try (RawRespClient c = new RawRespClient("127.0.0.1", RESP_PORT)) {
            c.call("SET", "{txf}r", "v");
            c.call("MULTI");
            c.call("SET", "{txf}r", "aborted");
            assertEquals(new Error("ERR Command 'info' is not supported inside a transaction by this proxy"), c.call("INFO"));
            assertEquals(new Error("EXECABORT Transaction discarded because of previous errors."), c.call("EXEC"));
            assertEquals(new Status("PONG"), c.call("PING"), "outside MULTI it's answered as usual");

            // SELECT too: running the rest in the wrong database would be worse than failing.
            c.call("MULTI");
            assertEquals(new Error("ERR Command 'select' is not supported inside a transaction by this proxy"),
                    c.call("SELECT", "1"));
            c.call("SET", "{txf}r", "wrong-db");
            assertEquals(new Error("EXECABORT Transaction discarded because of previous errors."), c.call("EXEC"));
            assertEquals("v", c.call("GET", "{txf}r"), "nothing from the discarded transactions applied");
        }
    }

    private static void assertSameAsServer(String key, List<String[]> script) throws Exception {
        try (RawRespClient proxy = new RawRespClient("127.0.0.1", RESP_PORT);
             DirectNodes nodes = new DirectNodes(cluster.getNodeAddresses())) {
            List<Object> fromProxy = proxy.pipeline(script);
            List<Object> fromServer = nodes.at(nodes.ownerOf(key)).pipeline(script);
            for (int i = 0; i < script.size(); i++) {
                assertEquals(fromServer.get(i), fromProxy.get(i), "reply to " + String.join(" ", script.get(i)));
            }
            assertTrue(fromProxy.contains(new Status("QUEUED")));
        }
    }
}
