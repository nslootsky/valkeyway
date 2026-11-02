/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.ValkeywayApplication;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import io.lettuce.core.masterreplica.StatefulRedisMasterReplicaConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * proxy.read-from=prefer-replica: reads go to replicas, writes and transactions to primaries. Where a
 * command ran is read from each node's command statistics. Also ROLE, which a cluster with replicas
 * could otherwise answer as a replica.
 */
@Tag("integration")
@SpringBootTest(
        classes = ValkeywayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.host=127.0.0.1", "proxy.port=" + ReplicaReadsTest.PORT,
                "proxy.read-from=prefer-replica"}
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
        try (RawRespClient c = proxy()) {
            for (int i = 0; i < KEYS; i++) {
                assertEquals(OK, c.call("SET", "rr:" + i, "v" + i));
            }
            // Until the replicas have the values and Glide has connected to every replica (it does so
            // in the background, reading from primaries meanwhile), reads don't all reach replicas.
            long deadline = System.currentTimeMillis() + 10_000;
            boolean ready = false;
            while (!ready && System.currentTimeMillis() < deadline) {
                cluster.onEveryNode("CONFIG", "RESETSTAT");
                ready = true;
                for (int i = 0; i < KEYS; i++) {
                    ready &= ("v" + i).equals(c.call("GET", "rr:" + i));
                }
                ready &= getCalls(Role.REPLICA) == KEYS;
                if (!ready) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "reads never all reached replicas:" + breakdown() + "\nproxy: "
                    + c.call("PROXY", "CONFIG", "GET", "read-from"));
        }
        cluster.onEveryNode("CONFIG", "RESETSTAT");
    }

    @Test
    void readsGoToReplicas() throws Exception {
        try (RawRespClient c = proxy()) {
            for (int i = 0; i < KEYS; i++) {
                assertEquals("v" + i, c.call("GET", "rr:" + i));
            }
        }
        assertEquals(KEYS, getCalls(Role.REPLICA), breakdown());
        assertEquals(0, getCalls(Role.PRIMARY), breakdown());
    }

    @Test
    void pipelinedReadsGoToReplicasToo() throws Exception {
        List<String[]> pipeline = new ArrayList<>();
        for (int i = 0; i < KEYS; i++) {
            pipeline.add(new String[]{"GET", "rr:" + i});
        }
        try (RawRespClient c = proxy()) {
            List<Object> replies = c.pipeline(pipeline);
            for (int i = 0; i < KEYS; i++) {
                assertEquals("v" + i, replies.get(i));
            }
        }
        assertEquals(KEYS, getCalls(Role.REPLICA), breakdown());
        assertEquals(0, getCalls(Role.PRIMARY), breakdown());
    }

    @Test
    void writesAndTransactionsStayOnPrimaries() throws Exception {
        try (RawRespClient c = proxy()) {
            assertEquals(OK, c.call("SET", "rr:w", "1"));
            assertEquals(OK, c.call("WATCH", "rr:w"));
            assertEquals(OK, c.call("MULTI"));
            assertEquals(new Status("QUEUED"), c.call("GET", "rr:w"));
            assertEquals(List.of("1"), c.call("EXEC"));
        }
        assertEquals(1, getCalls(Role.PRIMARY), "the GET inside the transaction");
        assertEquals(0, getCalls(Role.REPLICA));
    }

    /** READONLY and READWRITE are accepted and change nothing: proxy.read-from decides. */
    @Test
    void readOnlyAndReadWriteChangeNothing() throws Exception {
        try (RawRespClient c = proxy()) {
            assertEquals(OK, c.call("READWRITE"));
            assertEquals("v0", c.call("GET", "rr:0"));
            assertEquals(OK, c.call("READONLY"));
            assertEquals("v1", c.call("GET", "rr:1"));
            assertTrue(c.call("MULTI") instanceof Status);
            assertTrue(c.call("READONLY") instanceof RawRespClient.Error, "not inside a transaction");
            c.call("DISCARD");
        }
        assertEquals(2, getCalls(Role.REPLICA), breakdown());
    }

    /** ROLE describes the proxy, a primary without replicas, never one of the nodes behind it. */
    @Test
    void roleIsAPrimaryWithoutReplicas() throws Exception {
        try (RawRespClient c = proxy()) {
            for (int i = 0; i < 20; i++) {
                assertEquals(List.of("master", 0L, List.of()), c.call("ROLE"));
            }
        }
    }

    /**
     * Lettuce's MasterReplica with a static topology asks each node for its ROLE to find the primary;
     * answered by a replica, it would refuse writes ("Master is currently unknown").
     */
    @Test
    void lettuceMasterReplicaFindsThePrimary() {
        RedisClient lettuce = RedisClient.create();
        try (StatefulRedisMasterReplicaConnection<String, String> conn = MasterReplica.connect(lettuce,
                StringCodec.UTF8, List.of(RedisURI.create("redis://127.0.0.1:" + PORT)))) {
            conn.setReadFrom(ReadFrom.REPLICA_PREFERRED);
            RedisCommands<String, String> c = conn.sync();
            assertEquals("OK", c.set("rr:lettuce", "x"));
            assertEquals("x", c.get("rr:lettuce"));
        } finally {
            lettuce.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
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
