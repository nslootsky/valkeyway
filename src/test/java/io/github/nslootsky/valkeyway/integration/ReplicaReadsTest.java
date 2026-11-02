/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.ValkeywayApplication;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * proxy.replica-reads.connections=readonly: connections that send READONLY read from replicas, others
 * from primaries. Where a command ran is read from each node's command statistics.
 */
@Tag("integration")
@SpringBootTest(
        classes = ValkeywayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.host=127.0.0.1", "proxy.port=" + ReplicaReadsTest.PORT,
                "proxy.replica-reads.connections=readonly"}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReplicaReadsTest {

    static final int PORT = 6389;
    private static final int KEYS = 30;
    private static final Status OK = new Status("OK");

    private static final ValkeyClusterTestContainer cluster = new ValkeyClusterTestContainer(3, 1, 0, 27500, 5000, "");

    static {
        cluster.start();
    }

    @AfterAll
    static void stopCluster() {
        cluster.close();
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("proxy.cluster-nodes", () -> cluster.getNodeAddresses().toArray(String[]::new));
    }

    private static RawRespClient proxy() throws IOException {
        return new RawRespClient("127.0.0.1", PORT);
    }

    /** Keys spread over the shards, written and replicated before each test; then statistics reset. */
    @BeforeEach
    void writeKeysAndResetStatistics() throws Exception {
        try (RawRespClient c = proxy(); RawRespClient replicaReader = proxy()) {
            for (int i = 0; i < KEYS; i++) {
                assertEquals(OK, c.call("SET", "rr:" + i, "v" + i));
            }
            // Until the replicas have the values and Glide has connected to every replica (it does so
            // in the background, reading from primaries meanwhile), reads don't all reach replicas.
            assertEquals(OK, replicaReader.call("READONLY"));
            long deadline = System.currentTimeMillis() + 10_000;
            boolean ready = false;
            while (!ready && System.currentTimeMillis() < deadline) {
                cluster.onEveryNode("CONFIG", "RESETSTAT");
                ready = true;
                for (int i = 0; i < KEYS; i++) {
                    ready &= ("v" + i).equals(replicaReader.call("GET", "rr:" + i));
                }
                ready &= getCalls(Role.REPLICA) == KEYS;
                if (!ready) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "reads never all reached replicas:" + breakdown() + "\nproxy: "
                    + replicaReader.call("PROXY", "CONFIG", "GET", "replica-reads.connections") + " flags="
                    + flags(replicaReader, replicaReader.call("CLIENT", "ID").toString())
                    + " port=" + replicaReader.call("PROXY", "CONFIG", "GET", "port"));
        }
        cluster.onEveryNode("CONFIG", "RESETSTAT");
    }

    @Test
    void readOnlyConnectionsReadFromReplicas() throws Exception {
        try (RawRespClient c = proxy()) {
            assertEquals(OK, c.call("READONLY"));
            for (int i = 0; i < KEYS; i++) {
                assertEquals("v" + i, c.call("GET", "rr:" + i));
            }
        }
        assertEquals(KEYS, getCalls(Role.REPLICA), breakdown());
        assertEquals(0, getCalls(Role.PRIMARY), breakdown());
    }

    @Test
    void otherConnectionsReadFromPrimaries() throws Exception {
        try (RawRespClient c = proxy()) {
            for (int i = 0; i < KEYS; i++) {
                assertEquals("v" + i, c.call("GET", "rr:" + i));
            }
            // READWRITE switches a READONLY connection back.
            assertEquals(OK, c.call("READONLY"));
            assertEquals(OK, c.call("READWRITE"));
            assertEquals("v0", c.call("GET", "rr:0"));
        }
        assertEquals(KEYS + 1, getCalls(Role.PRIMARY));
        assertEquals(0, getCalls(Role.REPLICA));
    }

    @Test
    void pipelinedReadsGoToReplicasToo() throws Exception {
        List<String[]> pipeline = new ArrayList<>();
        pipeline.add(new String[]{"READONLY"});
        for (int i = 0; i < KEYS; i++) {
            pipeline.add(new String[]{"GET", "rr:" + i});
        }
        try (RawRespClient c = proxy()) {
            List<Object> replies = c.pipeline(pipeline);
            for (int i = 0; i < KEYS; i++) {
                assertEquals("v" + i, replies.get(i + 1));
            }
        }
        assertEquals(KEYS, getCalls(Role.REPLICA), breakdown());
        assertEquals(0, getCalls(Role.PRIMARY), breakdown());
    }

    @Test
    void writesAndTransactionsStayOnPrimaries() throws Exception {
        try (RawRespClient c = proxy()) {
            assertEquals(OK, c.call("READONLY"));
            assertEquals(OK, c.call("SET", "rr:w", "1"));
            assertEquals(OK, c.call("WATCH", "rr:w"));
            assertEquals(OK, c.call("MULTI"));
            assertEquals(new Status("QUEUED"), c.call("GET", "rr:w"));
            assertEquals(List.of("1"), c.call("EXEC"));
        }
        assertEquals(1, getCalls(Role.PRIMARY), "the GET inside the transaction");
        assertEquals(0, getCalls(Role.REPLICA));
    }

    @Test
    void readOnlyShowsInClientListUntilReset() throws Exception {
        try (RawRespClient c = proxy()) {
            String id = c.call("CLIENT", "ID").toString();
            assertFalse(flags(c, id).contains("r"));
            assertEquals(OK, c.call("READONLY"));
            assertTrue(flags(c, id).contains("r"));
            assertEquals(new Status("RESET"), c.call("RESET"));
            assertFalse(flags(c, id).contains("r"));
            assertTrue(c.call("MULTI") instanceof Status);
            assertTrue(c.call("READONLY") instanceof RawRespClient.Error, "not inside a transaction");
            c.call("DISCARD");
        }
    }

    private static String flags(RawRespClient c, String id) throws IOException {
        String line = (String) c.call("CLIENT", "LIST", "ID", id);
        return line.split(" flags=")[1].split(" ")[0];
    }

    enum Role { PRIMARY, REPLICA }

    /** Each node's role, GET calls and replication state, for failure messages. */
    private static String breakdown() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String address : cluster.getNodeAddresses()) {
            String[] hostPort = address.split(":");
            try (RawRespClient node = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]))) {
                String replication = (String) node.call("INFO", "replication");
                String gets = ((String) node.call("INFO", "commandstats")).lines()
                        .filter(l -> l.startsWith("cmdstat_get:")).findFirst().orElse("no GETs");
                out.append('\n').append(address).append(' ')
                        .append(replication.lines().filter(l -> l.startsWith("role:") || l.startsWith("master_port:")
                                || l.startsWith("master_link_status:") || l.startsWith("slave0:")).toList())
                        .append(' ').append(gets);
                String clients = (String) node.call("CLIENT", "LIST");
                out.append(" connections=").append(clients.lines().count() - 1).append(" readonly=")
                        .append(clients.lines().filter(l -> l.matches(".* flags=\\S*r\\S* .*")).count());
            }
        }
        return out.toString();
    }

    /** GET calls since the last reset, summed over the nodes with {@code role}. */
    private static long getCalls(Role role) throws IOException {
        long calls = 0;
        for (String address : cluster.getNodeAddresses()) {
            String[] hostPort = address.split(":");
            try (RawRespClient node = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]))) {
                boolean replica = ((String) node.call("INFO", "replication")).contains("role:slave");
                if (replica != (role == Role.REPLICA)) {
                    continue;
                }
                String stats = (String) node.call("INFO", "commandstats");
                for (String line : stats.split("\r\n")) {
                    if (line.startsWith("cmdstat_get:calls=")) {
                        calls += Long.parseLong(line.substring("cmdstat_get:calls=".length()).split(",")[0]);
                    }
                }
            }
        }
        return calls;
    }
}
