/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.nslootsky.valkeyway.integration.DirectNodes.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The cluster changes shape under live traffic: slots migrate (ASK, TRYAGAIN), move (MOVED, also
 * inside batches), land on a newly added shard, and primaries fail over. Each scenario runs through
 * the three backend paths: single commands, client pipelines, and single commands coalesced
 * across connections.
 *
 * <p>Runs against its own cluster (3 primaries with a replica each, plus a spare node) so the
 * changes can't affect other tests.
 */
@Tag("integration")
@SpringBootTest(
        classes = io.github.nslootsky.valkeyway.ValkeywayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.host=127.0.0.1", "proxy.port=" + TopologyChangeTest.PROXY_PORT}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TopologyChangeTest {

    static final int PROXY_PORT = 6391;
    private static final Status OK = new Status("OK");
    private static final int CLIENTS = 50;

    private static final ValkeyClusterTestContainer cluster =
            new ValkeyClusterTestContainer(3, 1, 1, 27100, 2000, "--enable-debug-command yes");

    static {
        cluster.start();
    }

    @DynamicPropertySource
    static void clusterNodes(DynamicPropertyRegistry registry) {
        registry.add("proxy.cluster-nodes", () -> cluster.getNodeAddresses().toArray(String[]::new));
    }

    @Test
    @Order(1)
    void askRedirectsDuringAMigrationAreFollowed() throws Exception {
        String moved = "{ask}moved", stays = "{ask}stays", created = "{ask}new";
        try (RawRespClient proxy = proxy(); DirectNodes nodes = nodes()) {
            proxy.call("DEL", moved, stays, created);
            proxy.call("SET", moved, "m");
            proxy.call("SET", stays, "s");
            assertEquals("m", proxy.call("GET", moved));

            String source = nodes.ownerOf(moved);
            String target = otherPrimary(nodes, source);
            long slot = nodes.slotOf(moved);
            nodes.beginMigration(slot, source, target);
            try {
                nodes.migrateKeys(source, target, List.of(moved));
                assertTrue(nodes.at(source).call("GET", moved) instanceof Error(String m) && m.startsWith("ASK "),
                        "the source should now redirect with ASK");

                assertEquals("m", proxy.call("GET", moved), "single command");
                assertEquals("s", proxy.call("GET", stays), "single command");
                assertEquals(List.of("m", "s", OK, "n"), proxy.pipeline(List.of(
                        cmd("GET", moved), cmd("GET", stays), cmd("SET", created, "n"), cmd("GET", created))),
                        "pipeline; the new key is created on the importing node");
                assertEquals(List.of(), concurrently(CLIENTS, (id, c, problems) -> {
                    expect(problems, id + " GET moved", "m", c.call("GET", moved));
                    expect(problems, id + " GET stays", "s", c.call("GET", stays));
                }), "coalesced single commands");
            } finally {
                nodes.finishMigration(slot, source, target);
            }
            assertEquals("n", nodes.at(target).call("GET", created));
        }
    }

    /**
     * A multi-key command whose keys are split between the two nodes of a migration gets TRYAGAIN
     * until the migration finishes. Single commands wait it out, whether or not they were coalesced
     * into a batch with other connections' commands. In a client pipeline the command gets the
     * TRYAGAIN error: retrying it would run it after the commands behind it, and a standalone server's
     * pipeline reports errors per command anyway.
     */
    @Test
    @Order(2)
    void multiKeyCommandsWaitOutAMigration() throws Exception {
        String a = "{try}a", b = "{try}b";
        try (RawRespClient proxy = proxy(); DirectNodes nodes = nodes()) {
            proxy.call("DEL", a, b);
            proxy.call("SET", a, "va");
            proxy.call("SET", b, "vb");
            assertEquals(List.of("va", "vb"), proxy.call("MGET", a, b));

            String source = nodes.ownerOf(a);
            String target = otherPrimary(nodes, source);
            long slot = nodes.slotOf(a);
            nodes.beginMigration(slot, source, target);
            nodes.migrateKeys(source, target, List.of(a));
            assertTrue(nodes.at(source).call("MGET", a, b) instanceof Error(String m) && m.startsWith("TRYAGAIN"),
                    "the source should answer TRYAGAIN while the keys are split");

            AtomicBoolean finished = new AtomicBoolean();
            Thread finisher = Thread.ofVirtual().start(() -> {
                try (DirectNodes own = nodes()) {
                    Thread.sleep(500);
                    own.finishMigration(slot, source, target);
                    finished.set(true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                // Started together, so all of them are in flight while the keys are split.
                var piped = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        // The first command of a pipeline is sent alone; the rest go as one batch.
                        return proxy.pipeline(List.of(cmd("GET", b), cmd("MGET", a, b), cmd("GET", b)));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, Executors.newVirtualThreadPerTaskExecutor());
                AtomicLong sentDuringMigration = new AtomicLong();
                assertEquals(List.of(), concurrently(CLIENTS, (id, c, problems) -> {
                    if (!finished.get()) {
                        sentDuringMigration.incrementAndGet();
                    }
                    expect(problems, id + " MGET", List.of("va", "vb"), c.call("MGET", a, b));
                }), "single commands, coalesced or not, wait out the migration");
                List<Object> replies = piped.get(10, TimeUnit.SECONDS);
                assertEquals("vb", replies.get(0));
                assertTrue(replies.get(1) instanceof Error(String m) && m.startsWith("TRYAGAIN"),
                        "the pipeline reports TRYAGAIN, got " + replies.get(1));
                assertEquals("vb", replies.get(2));
                assertTrue(sentDuringMigration.get() > 0, "the commands should have overlapped the migration");
            } finally {
                finisher.join();
            }
            assertTrue(finished.get(), "migration finished");
        }
    }

    /** After a full slot move the proxy's slot map is stale; batches must follow MOVED too. */
    @Test
    @Order(3)
    void movedRedirectsInsideBatchesAreFollowed() throws Exception {
        String k1 = "{moved}k1", k2 = "{moved}k2", other = "{elsewhere}k";
        try (RawRespClient proxy = proxy(); DirectNodes nodes = nodes()) {
            proxy.call("DEL", k1, k2, other);
            proxy.pipeline(List.of(cmd("SET", k1, "v1"), cmd("SET", k2, "v2"), cmd("SET", other, "o")));
            assertEquals(List.of("v1", "v2"), proxy.pipeline(List.of(cmd("GET", k1), cmd("GET", k2))));

            String source = nodes.ownerOf(k1);
            String target = otherPrimary(nodes, source);
            nodes.moveSlot(nodes.slotOf(k1), source, target);

            assertEquals(List.of("v1", OK, "new", "o"), proxy.pipeline(List.of(
                    cmd("GET", k1), cmd("SET", k2, "new"), cmd("GET", k2), cmd("GET", other))), "pipeline");
            assertEquals("new", nodes.at(target).call("GET", k2), "the write landed on the new owner");
            assertEquals(List.of(), concurrently(CLIENTS, (id, c, problems) ->
                    expect(problems, id + " GET", "v1", c.call("GET", k1))), "coalesced single commands");
        }
    }

    /** A slot moved to a node the proxy has never connected to: MOVED points at an unknown address. */
    @Test
    @Order(4)
    void aSlotMovedToANewShardIsServed() throws Exception {
        String key = "{newshard}k", key2 = "{newshard}k2";
        String spare = cluster.getSpareAddresses().getFirst();
        try (RawRespClient proxy = proxy(); DirectNodes nodes = nodes()) {
            proxy.call("DEL", key, key2);
            proxy.call("SET", key, "v");
            assertEquals("v", proxy.call("GET", key));

            String[] hostPort = cluster.getNodeAddress(0).split(":");
            assertEquals(OK, nodes.at(spare).call("CLUSTER", "MEET", hostPort[0], hostPort[1],
                    Integer.toString(ValkeyClusterTestContainer.busPort(Integer.parseInt(hostPort[1])))));
            String spareId = nodes.nodeId(spare);
            await("the new node to see the whole cluster", Duration.ofSeconds(20), () -> {
                try {
                    return ((String) nodes.at(spare).call("CLUSTER", "INFO")).contains("cluster_state:ok");
                } catch (IOException e) {
                    return false;
                }
            });
            await("the new node to join", Duration.ofSeconds(20), () -> cluster.getNodeAddresses().stream()
                    .allMatch(a -> knows(nodes, a, spareId)) && nodes.addresses().stream().allMatch(a -> knows(nodes, spare, id(nodes, a))));

            String source = nodes.ownerOf(key);
            nodes.moveSlot(nodes.slotOf(key), source, spare);
            assertEquals(spare, nodes.ownerOf(key));

            assertEquals("v", proxy.call("GET", key), "single command");
            assertEquals(OK, proxy.call("SET", key2, "w"), "single command");
            assertEquals("w", nodes.at(spare).call("GET", key2), "the write landed on the new shard");
            assertEquals(List.of("v", "w", 1L), proxy.pipeline(List.of(cmd("GET", key), cmd("GET", key2), cmd("EXISTS", key))),
                    "pipeline");
            assertEquals(List.of(), concurrently(CLIENTS, (id, c, problems) ->
                    expect(problems, id + " GET", "v", c.call("GET", key))), "coalesced single commands");
        }
    }

    /**
     * A manual (graceful) failover pauses writes on the primary until the replica has caught up, then
     * swaps roles. Every write must be acknowledged exactly once and none may fail.
     */
    @Test
    @Order(5)
    void writesSurviveAGracefulFailover() throws Exception {
        String counter = "{failover}counter";
        try (RawRespClient proxy = proxy(); DirectNodes nodes = nodes()) {
            proxy.call("DEL", counter);
            String primary = nodes.ownerOf(counter);
            String replica = nodes.replicaOf(primary);

            Load load = new Load(counter, 20, 5, 10);
            try (load) {
                Thread.sleep(300);
                assertEquals(OK, nodes.at(replica).call("CLUSTER", "FAILOVER"));
                await("the replica to take over", Duration.ofSeconds(20), () -> isPrimary(replica));
                Thread.sleep(500);
            }
            assertEquals(List.of(), load.errors(), "no write may fail during a graceful failover");
            assertTrue(load.acked() > 0);
            assertEquals(Long.toString(load.acked()), nodes.at(replica).call("GET", counter),
                    "every acknowledged INCR applied exactly once");
            assertEquals(replica, nodes.ownerOf(counter));
            cluster.awaitHealthy(Duration.ofSeconds(30));
        }
    }

    /**
     * The primary's process dies, so its connections are reset. Requests to it fail until the
     * cluster promotes the replica; then all three paths must work again without a restart.
     */
    @Test
    @Order(6)
    void trafficRecoversAfterAPrimaryIsKilled() throws Exception {
        long recoveryMs = failOverUnderLoad("{killed}", primary -> {
            cluster.kill(primary);
            return () -> cluster.restart(primary);
        });
        assertTrue(recoveryMs < 5000, "the proxy took " + recoveryMs + " ms after promotion to serve the slot");
    }

    /**
     * The primary stops responding without closing its connections (DEBUG SLEEP; like a frozen
     * process or a network partition). Glide doesn't move to the promoted replica until the old node
     * answers again: request timeouts don't trigger a topology refresh, and the refresh started by its
     * periodic check waits on the hung node with no timeout. So this only checks that traffic recovers
     * once the node is back, and reports how long that took.
     */
    @Test
    @Order(7)
    void trafficRecoversAfterAPrimaryStopsResponding() throws Exception {
        failOverUnderLoad("{hung}", primary -> {
            Thread sleeper = Thread.ofVirtual().start(() -> {
                String[] hp = primary.split(":");
                try (RawRespClient direct = new RawRespClient(hp[0], Integer.parseInt(hp[1]))) {
                    direct.call("DEBUG", "SLEEP", "12");
                } catch (IOException ignored) {
                    // The node wakes up regardless.
                }
            });
            return sleeper::join;
        });
    }

    interface Outage {
        /** Takes {@code primary} down; the returned action ends the outage. */
        AutoCloseable start(String primary) throws Exception;
    }

    /**
     * Runs INCR traffic while {@code outage} takes the primary for {@code tag} down, waits for its
     * replica's promotion and for the proxy to serve the slot again, then checks every path.
     * Returns the milliseconds from promotion until the proxy served the slot.
     */
    private long failOverUnderLoad(String tag, Outage outage) throws Exception {
        String key = tag + "k", counter = tag + "counter";
        try (RawRespClient proxy = proxy(); DirectNodes nodes = nodes()) {
            proxy.call("DEL", counter);
            proxy.call("SET", key, "v");
            String primary = nodes.ownerOf(key);
            String replica = nodes.replicaOf(primary);

            Load load = new Load(counter, 20, 5, 10);
            long recoveryMs;
            try (load) {
                Thread.sleep(300);
                AutoCloseable end = outage.start(primary);
                await("the replica to be promoted", Duration.ofSeconds(20), () -> isPrimary(replica));
                long promotedAt = System.nanoTime();
                await("the proxy to serve the slot again", Duration.ofSeconds(40), () -> {
                    try {
                        return "v".equals(proxy.call("GET", key));
                    } catch (IOException e) {
                        return false;
                    }
                });
                recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - promotedAt);
                System.out.printf("%s: proxy served the slot %d ms after promotion; %d writes failed during the outage: %s%n",
                        tag, recoveryMs, load.errorCount(), load.errorKinds());
                end.close();
            }

            assertEquals("v", proxy.call("GET", key), "single command");
            assertEquals(List.of("v", 1L, "v"), proxy.pipeline(List.of(cmd("GET", key), cmd("EXISTS", key), cmd("GET", key))),
                    "pipeline");
            assertEquals(List.of(), concurrently(CLIENTS, (id, c, problems) ->
                    expect(problems, id + " GET", "v", c.call("GET", key))), "coalesced single commands");

            // Failed writes may or may not have applied; acknowledged ones must not apply twice.
            long value = Long.parseLong((String) nodes.at(replica).call("GET", counter));
            assertTrue(value <= load.acked() + load.errorCount(),
                    "counter " + value + " exceeds acknowledged + failed writes " + (load.acked() + load.errorCount()));
            await("the old primary to rejoin as a replica", Duration.ofSeconds(30), () -> !isPrimary(primary));
            cluster.awaitHealthy(Duration.ofSeconds(30));
            return recoveryMs;
        }
    }

    // ---- helpers ----

    private static RawRespClient proxy() throws IOException {
        return new RawRespClient("127.0.0.1", PROXY_PORT);
    }

    private static DirectNodes nodes() {
        List<String> all = new ArrayList<>(cluster.getNodeAddresses());
        all.addAll(cluster.getSpareAddresses());
        return new DirectNodes(all);
    }

    private static String[] cmd(String... args) {
        return args;
    }

    private static String otherPrimary(DirectNodes nodes, String source) throws IOException {
        return nodes.primaries().stream().filter(a -> !a.equals(source)).findFirst().orElseThrow();
    }

    /** Uses a fresh connection, so a node that stopped responding can't wedge a cached one. */
    private static boolean isPrimary(String address) {
        String[] hp = address.split(":");
        try (RawRespClient c = new RawRespClient(hp[0], Integer.parseInt(hp[1]))) {
            return "master".equals(((List<?>) c.call("ROLE")).getFirst());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean knows(DirectNodes nodes, String viewFrom, String nodeId) {
        try {
            String view = (String) nodes.at(viewFrom).call("CLUSTER", "NODES");
            return view.lines().anyMatch(l -> l.startsWith(nodeId + " ") && !l.contains("handshake") && !l.contains("noaddr"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String id(DirectNodes nodes, String address) {
        try {
            return nodes.nodeId(address);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void expect(Queue<String> problems, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            problems.add(what + ": expected " + expected + ", got " + actual);
        }
    }

    interface ClientTask {
        void run(int id, RawRespClient client, Queue<String> problems) throws Exception;
    }

    /** Runs {@code task} on {@code clients} proxy connections at once; returns the problems found. */
    private static List<String> concurrently(int clients, ClientTask task) throws Exception {
        Queue<String> problems = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                int id = i;
                tasks.add(pool.submit(() -> {
                    try (RawRespClient c = proxy()) {
                        ready.countDown();
                        go.await();
                        task.run(id, c, problems);
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "clients connected");
            go.countDown();
            for (Future<?> f : tasks) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        List<String> sorted = new ArrayList<>(problems);
        return sorted.size() > 5 ? List.of(sorted.size() + " problems, e.g. " + sorted.subList(0, 5)) : sorted;
    }

    /**
     * Background INCR traffic: {@code singles} clients sending one command at a time (the coalesced
     * path) and {@code pipelines} clients sending {@code depth} commands at once.
     */
    private static final class Load implements AutoCloseable {
        private final AtomicBoolean stop = new AtomicBoolean();
        private final AtomicLong acked = new AtomicLong();
        private final Queue<String> errors = new ConcurrentLinkedQueue<>();
        private final List<Thread> threads = new ArrayList<>();
        private final long start = System.nanoTime();

        Load(String key, int singles, int pipelines, int depth) {
            for (int i = 0; i < singles + pipelines; i++) {
                int n = i < singles ? 1 : depth;
                threads.add(Thread.ofVirtual().start(() -> run(key, n)));
            }
        }

        private void run(String key, int depth) {
            List<String[]> batch = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                batch.add(new String[]{"INCR", key});
            }
            RawRespClient client = null;
            while (!stop.get()) {
                try {
                    if (client == null) {
                        client = proxy();
                    }
                    List<Object> replies = depth == 1 ? List.of(client.call("INCR", key)) : client.pipeline(batch);
                    for (Object reply : replies) {
                        if (reply instanceof Long) {
                            acked.incrementAndGet();
                        } else {
                            errors.add(elapsed() + (depth == 1 ? " single: " : " pipeline: ") + reply);
                        }
                    }
                } catch (IOException e) {
                    errors.add(elapsed() + " connection: " + e.getMessage());
                    closeQuietly(client);
                    client = null;
                }
            }
            closeQuietly(client);
        }

        private String elapsed() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + "ms";
        }

        long acked() {
            return acked.get();
        }

        /** Error counts by path and message (digits blanked so similar messages group). */
        java.util.Map<String, Long> errorKinds() {
            return errors.stream().collect(java.util.stream.Collectors.groupingBy(
                    e -> e.substring(e.indexOf(' ') + 1).replaceAll("\\d+", "N"),
                    java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        }

        int errorCount() {
            return errors.size();
        }

        List<String> errors() {
            List<String> all = new ArrayList<>(errors);
            return all.size() > 10 ? List.of(all.size() + " errors, e.g. " + all.subList(0, 10)) : all;
        }

        @Override
        public void close() throws InterruptedException {
            stop.set(true);
            for (Thread t : threads) {
                t.join();
            }
        }

        private static void closeQuietly(RawRespClient client) {
            if (client != null) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }
    }
}
