/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.ScanParams;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mixed load for a while, to catch leaks: heap, threads, client connections and backend
 * connections must level off rather than grow, and the proxy must return to its idle state when the
 * load stops. Skipped unless asked for:
 *
 * <pre>./mvnw failsafe:integration-test -Dskip.integration.tests=false -Dit.test=SoakTest -Dsoak.minutes=60 -Dit.log.level=INFO</pre>
 *
 * The proxy runs in this JVM, so heap and threads are the proxy's (and the load's).
 */
@EnabledIfSystemProperty(named = "soak.minutes", matches = "\\d+")
class SoakTest extends IntegrationTestBase {

    private static final HostAndPort PROXY = new HostAndPort("127.0.0.1", RESP_PORT);
    private static final long SAMPLE_SECONDS = 30;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, AtomicLong> operations = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();

    record Sample(long minute, long heapMb, int threads, long clients, long backendConnections) {}

    private static Jedis jedis() {
        return new Jedis(PROXY, DefaultJedisClientConfig.builder().socketTimeoutMillis(10_000).build());
    }

    /** Runs {@code step} in a loop until the load stops, counting operations and errors. */
    private Runnable worker(String name, ThrowingRunnable step) {
        return () -> {
            while (running.get()) {
                try {
                    step.run();
                    operations.computeIfAbsent(name, _ -> new AtomicLong()).incrementAndGet();
                } catch (Exception e) {
                    if (running.get()) {
                        errors.computeIfAbsent(name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                                _ -> new AtomicLong()).incrementAndGet();
                    }
                }
            }
        };
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void mixedLoadDoesNotLeak() throws Exception {
        long minutes = Long.parseLong(System.getProperty("soak.minutes"));
        Sample idle = sample(0);
        RedisClient lettuce = RedisClient.create(RedisURI.create("redis://127.0.0.1:" + RESP_PORT));
        ExecutorService pool = Executors.newCachedThreadPool();
        List<Sample> samples = new ArrayList<>();
        try (Jedis pipeliner = jedis(); Jedis watcher = jedis(); Jedis rival = jedis(); Jedis scanner = jedis();
             Jedis producer = jedis(); Jedis blocker = jedis(); Jedis unblocker = jedis(); Jedis publisher = jedis();
             StatefulRedisConnection<String, String> async = lettuce.connect()) {
            RedisAsyncCommands<String, String> a = async.async();
            long blockerId = blocker.clientId();

            pool.execute(worker("pipeline", () -> {
                Pipeline p = pipeliner.pipelined();
                for (int i = 0; i < 100; i++) {
                    p.set("soak:p:" + i, "v" + i);
                    p.get("soak:p:" + i);
                }
                p.sync();
            }));
            pool.execute(worker("async", () -> {
                int i = ThreadLocalRandom.current().nextInt(1000);
                a.hset("soak:h:" + i, Map.of("f", "v")).get(10, TimeUnit.SECONDS);
                a.hgetall("soak:h:" + i).get(10, TimeUnit.SECONDS);
                a.zadd("soak:z", i, "m" + i).get(10, TimeUnit.SECONDS);
                a.zrangeWithScores("soak:z", 0, 10).get(10, TimeUnit.SECONDS);
                a.mget("soak:p:1", "soak:p:2", "soak:p:3").get(10, TimeUnit.SECONDS);
            }));
            pool.execute(worker("churn", () -> {
                try (Jedis j = jedis()) {
                    j.set("soak:churn", "x");
                    j.get("soak:churn");
                }
            }));
            pool.execute(worker("blocking", () -> blocker.blpop(1, "soak:list")));
            pool.execute(worker("producer", () -> {
                producer.rpush("soak:list", "item");
                Thread.sleep(5);
            }));
            pool.execute(worker("unblock", () -> {
                unblocker.clientUnblock(blockerId);
                Thread.sleep(200);
            }));
            pool.execute(worker("watch", () -> {
                watcher.watch("{soak:w}k");
                if (ThreadLocalRandom.current().nextBoolean()) {
                    rival.incr("{soak:w}k");
                }
                Transaction t = watcher.multi();
                t.incr("{soak:w}k");
                t.exec();
            }));
            pool.execute(worker("scan", () -> {
                // Half the scans are abandoned after one page, which leaves cursors behind.
                String cursor = ScanParams.SCAN_POINTER_START;
                do {
                    cursor = scanner.scan(cursor, new ScanParams().match("soak:p:*").count(100)).getCursor();
                } while (!cursor.equals("0") && ThreadLocalRandom.current().nextBoolean());
            }));
            pool.execute(worker("subscribe", () -> {
                String channel = "soak:ch:" + ThreadLocalRandom.current().nextInt(50);
                try (Jedis sub = jedis()) {
                    JedisPubSub listener = new JedisPubSub() {
                        @Override
                        public void onMessage(String ch, String message) {
                            unsubscribe();
                        }

                        @Override
                        public void onSubscribe(String ch, int count) {
                            publisher.publish(ch, "hi"); // ends this subscription
                        }
                    };
                    sub.subscribe(listener, channel);
                }
            }));

            long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
            while (System.nanoTime() < end) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(SAMPLE_SECONDS));
                Sample s = sample(samples.size() * SAMPLE_SECONDS / 60 + 1);
                samples.add(s);
                System.out.printf("SOAK %s ops=%s errors=%d%n", s, total(operations), total(errors));
            }
            stop(pool); // before the connections close, which would fail the workers still using them
        } finally {
            stop(pool);
            lettuce.shutdown(Duration.ZERO, Duration.ofSeconds(5));
        }

        // Idle again: the load's connections close, and the proxy lets go of what they held.
        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
        Sample after = sample(-1);
        System.out.println("SOAK idle before " + idle + " after " + after);
        System.out.println("SOAK operations " + operations);
        System.out.println("SOAK errors " + errors);

        assertTrue(errors.isEmpty(), "errors under load: " + errors);
        assertTrue(samples.size() >= 4, "too short to judge; use at least 2 minutes");
        // Compare the second half with the first: anything leaking keeps growing.
        Sample early = samples.get(samples.size() / 4);
        Sample late = samples.getLast();
        assertTrue(late.heapMb() <= early.heapMb() * 1.5 + 64, "heap grew: " + early + " -> " + late);
        assertTrue(late.threads() <= early.threads() + 20, "threads grew: " + early + " -> " + late);
        assertTrue(late.backendConnections() <= early.backendConnections() + 10,
                "backend connections grew: " + early + " -> " + late);
        assertTrue(after.clients() <= idle.clients() + 1, "clients left connected: " + idle + " -> " + after);
    }

    private void stop(ExecutorService pool) throws InterruptedException {
        running.set(false);
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }

    private static long total(Map<String, AtomicLong> counters) {
        return counters.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /** Heap after a GC, live threads, the proxy's clients, and the cluster's client connections. */
    private Sample sample(long minute) throws Exception {
        System.gc();
        Thread.sleep(200);
        long heapMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long clients;
        try (Jedis j = jedis()) {
            clients = j.info("clients").lines().filter(l -> l.startsWith("connected_clients:"))
                    .mapToLong(l -> Long.parseLong(l.substring(l.indexOf(':') + 1).trim())).sum() - 1;
        }
        long backend = 0;
        for (String node : cluster.getNodeAddresses()) {
            String[] hostPort = node.split(":");
            try (RawRespClient direct = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]))) {
                backend += ((String) direct.call("CLIENT", "LIST")).lines().count() - 1;
            }
        }
        return new Sample(minute, heapMb, threads, clients, backend);
    }
}
