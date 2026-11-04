/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The proxy through the Java clients most applications use, Jedis and Lettuce, in RESP2 and RESP3
 * (Lettuce's default): what they do on connect, and the common kinds of commands.
 */
class RealClientsTest extends IntegrationTestBase {

    private static final HostAndPort PROXY = new HostAndPort("127.0.0.1", RESP_PORT);

    enum Protocol { RESP2, RESP3 }

    // --- Jedis ---

    private static Jedis jedis(Protocol protocol) {
        return new Jedis(PROXY, DefaultJedisClientConfig.builder()
                .protocol(protocol == Protocol.RESP3 ? RedisProtocol.RESP3 : RedisProtocol.RESP2)
                .socketTimeoutMillis(5000).build());
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void jedisCommands(Protocol protocol) {
        try (Jedis j = jedis(protocol)) {
            String p = "rc:j:" + protocol + ":";
            assertEquals("PONG", j.ping());
            assertEquals("OK", j.set(p + "a", "1"));
            j.set(p + "b", "2");
            assertEquals(List.of("1", "2"), j.mget(p + "a", p + "b"), "keys in different slots");
            assertEquals(2, j.del(p + "a", p + "b"));

            j.hset(p + "h", Map.of("f1", "v1", "f2", "v2"));
            assertEquals(Map.of("f1", "v1", "f2", "v2"), j.hgetAll(p + "h"));
            j.zadd(p + "z", Map.of("one", 1.0, "two", 2.0));
            assertEquals(2.0, j.zscore(p + "z", "two"));
            assertEquals(List.of("one", "two"), j.zrange(p + "z", 0, -1));
            assertEquals(2.0, j.zrangeWithScores(p + "z", 0, -1).get(1).getScore());
            assertEquals(1.5, j.incrByFloat(p + "f", 1.5));

            j.select(3);
            j.set(p + "db3", "x");
            j.select(0);
            assertNull(j.get(p + "db3"), "databases are separate");
            j.clientSetname("jedis-" + protocol);
            assertEquals("jedis-" + protocol, j.clientGetname());
        }
    }

    @Test
    void jedisPipelineAndTransactions() {
        try (Jedis j = jedis(Protocol.RESP2); Jedis other = jedis(Protocol.RESP2)) {
            Pipeline pipeline = j.pipelined();
            List<Response<Long>> counts = new ArrayList<>();
            pipeline.del("rc:jp:n");
            for (int i = 0; i < 100; i++) {
                counts.add(pipeline.incr("rc:jp:n"));
            }
            pipeline.sync();
            for (int i = 0; i < 100; i++) {
                assertEquals(i + 1, counts.get(i).get(), "pipeline order");
            }

            Transaction tx = j.multi();
            tx.set("{rc:jt}a", "1");
            tx.incr("{rc:jt}a");
            assertEquals(List.of("OK", 2L), tx.exec());

            j.watch("{rc:jt}a");
            other.set("{rc:jt}a", "changed");
            Transaction aborted = j.multi();
            aborted.set("{rc:jt}a", "mine");
            assertNull(aborted.exec(), "a watched key changed");
            assertEquals("changed", j.get("{rc:jt}a"));
        }
    }

    @Test
    void jedisPubSubAndBlocking() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        CountDownLatch subscribed = new CountDownLatch(2);
        JedisPubSub listener = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                messages.add(channel + "=" + message);
            }

            @Override
            public void onPMessage(String pattern, String channel, String message) {
                messages.add(pattern + ":" + channel + "=" + message);
            }

            @Override
            public void onSubscribe(String channel, int count) {
                subscribed.countDown();
            }

            @Override
            public void onPSubscribe(String pattern, int count) {
                subscribed.countDown();
            }
        };
        try (Jedis sub = jedis(Protocol.RESP2); Jedis j = jedis(Protocol.RESP2)) {
            CompletableFuture<Void> loop = CompletableFuture.runAsync(() -> sub.subscribe(listener, "rc:jch"));
            assertTrue(waitFor(listener::isSubscribed));
            listener.psubscribe("rc:jp*");
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
            j.publish("rc:jch", "hello");
            j.publish("rc:jpat", "world");
            assertEquals(Set.of("rc:jch=hello", "rc:jp*:rc:jpat=world"),
                    Set.of(messages.poll(5, TimeUnit.SECONDS), messages.poll(5, TimeUnit.SECONDS)));
            listener.unsubscribe();
            listener.punsubscribe();
            loop.get(5, TimeUnit.SECONDS);

            j.del("rc:jlist");
            CompletableFuture<List<String>> pop = CompletableFuture.supplyAsync(() -> {
                try (Jedis blocker = jedis(Protocol.RESP2)) {
                    return blocker.blpop(5, "rc:jlist");
                }
            });
            Thread.sleep(200);
            j.rpush("rc:jlist", "item");
            assertEquals(List.of("rc:jlist", "item"), pop.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void jedisScan() {
        try (Jedis j = jedis(Protocol.RESP2)) {
            for (int i = 0; i < 50; i++) {
                j.set("rc:jscan:" + i, "v");
            }
            Set<String> found = new HashSet<>();
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> page = j.scan(cursor, new ScanParams().match("rc:jscan:*").count(10));
                found.addAll(page.getResult());
                cursor = page.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
            assertEquals(50, found.size(), "every key, from every node");
        }
    }

    // --- Lettuce ---

    private static RedisClient lettuce;

    @BeforeAll
    static void startLettuce() {
        lettuce = RedisClient.create(RedisURI.create("redis://127.0.0.1:" + RESP_PORT));
    }

    @AfterAll
    static void stopLettuce() {
        lettuce.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }

    private static StatefulRedisConnection<String, String> lettuceConnection(Protocol protocol) {
        lettuce.setOptions(ClientOptions.builder()
                .protocolVersion(protocol == Protocol.RESP3 ? ProtocolVersion.RESP3 : ProtocolVersion.RESP2).build());
        return lettuce.connect();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void lettuceCommands(Protocol protocol) {
        try (StatefulRedisConnection<String, String> conn = lettuceConnection(protocol)) {
            RedisCommands<String, String> c = conn.sync();
            String p = "rc:l:" + protocol + ":";
            assertEquals("PONG", c.ping());
            c.set(p + "a", "1");
            c.set(p + "b", "2");
            assertEquals(List.of(KeyValue.just(p + "a", "1"), KeyValue.just(p + "b", "2")), c.mget(p + "a", p + "b"));

            c.hset(p + "h", Map.of("f1", "v1", "f2", "v2"));
            assertEquals(Map.of("f1", "v1", "f2", "v2"), c.hgetall(p + "h"));
            c.zadd(p + "z", 1.0, "one", 2.0, "two");
            assertEquals(List.of(ScoredValue.just(1.0, "one"), ScoredValue.just(2.0, "two")), c.zrangeWithScores(p + "z", 0, -1));
            assertEquals(2.0, c.zscore(p + "z", "two"));
            c.sadd(p + "s", "x", "y");
            assertEquals(Set.of("x", "y"), c.smembers(p + "s"));
            c.xadd(p + "st", Map.of("f", "v"));
            assertEquals(1, c.xrange(p + "st", io.lettuce.core.Range.create("-", "+")).size());
            assertTrue(c.info("server").contains("valkeyway_version"));

            c.select(4);
            c.set(p + "db4", "x");
            c.select(0);
            assertNull(c.get(p + "db4"));
        }
    }

    @Test
    void lettucePipelineAndTransactions() throws Exception {
        try (StatefulRedisConnection<String, String> conn = lettuceConnection(Protocol.RESP3);
             StatefulRedisConnection<String, String> other = lettuceConnection(Protocol.RESP3)) {
            RedisAsyncCommands<String, String> async = conn.async();
            async.del("rc:lp:n").get(5, TimeUnit.SECONDS);
            conn.setAutoFlushCommands(false);
            List<RedisFuture<Long>> counts = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                counts.add(async.incr("rc:lp:n"));
            }
            conn.flushCommands();
            conn.setAutoFlushCommands(true);
            for (int i = 0; i < 100; i++) {
                assertEquals(i + 1, counts.get(i).get(5, TimeUnit.SECONDS), "pipeline order");
            }

            RedisCommands<String, String> c = conn.sync();
            c.multi();
            c.set("{rc:lt}a", "1");
            c.incr("{rc:lt}a");
            TransactionResult result = c.exec();
            assertFalse(result.wasDiscarded());
            assertEquals(List.of("OK", 2L), result.stream().toList());

            c.watch("{rc:lt}a");
            other.sync().set("{rc:lt}a", "changed");
            c.multi();
            c.set("{rc:lt}a", "mine");
            assertTrue(c.exec().wasDiscarded(), "a watched key changed");
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void lettucePubSubAndBlocking(Protocol protocol) throws Exception {
        lettuce.setOptions(ClientOptions.builder()
                .protocolVersion(protocol == Protocol.RESP3 ? ProtocolVersion.RESP3 : ProtocolVersion.RESP2).build());
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        try (StatefulRedisPubSubConnection<String, String> sub = lettuce.connectPubSub();
             StatefulRedisConnection<String, String> conn = lettuceConnection(protocol)) {
            sub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    messages.add(channel + "=" + message);
                }

                @Override
                public void message(String pattern, String channel, String message) {
                    messages.add(pattern + ":" + channel + "=" + message);
                }
            });
            sub.sync().subscribe("rc:lch:" + protocol);
            sub.sync().psubscribe("rc:lp" + protocol + "*");
            RedisCommands<String, String> c = conn.sync();
            assertEquals(1, c.publish("rc:lch:" + protocol, "hello"));
            assertEquals(1, c.publish("rc:lp" + protocol + "x", "world"));
            assertEquals(Set.of("rc:lch:" + protocol + "=hello", "rc:lp" + protocol + "*:rc:lp" + protocol + "x=world"),
                    Set.of(messages.poll(5, TimeUnit.SECONDS), messages.poll(5, TimeUnit.SECONDS)));
            if (protocol == Protocol.RESP3) {
                assertEquals("PONG", sub.sync().ping(), "RESP3 subscribers keep running commands");
            }

            c.del("rc:llist");
            CompletableFuture<KeyValue<String, String>> pop = CompletableFuture.supplyAsync(() -> {
                try (StatefulRedisConnection<String, String> blocker = lettuce.connect()) {
                    return blocker.sync().blpop(5, "rc:llist");
                }
            });
            Thread.sleep(200);
            c.rpush("rc:llist", "item");
            assertEquals(KeyValue.just("rc:llist", "item"), pop.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void lettuceScan() {
        try (StatefulRedisConnection<String, String> conn = lettuceConnection(Protocol.RESP3)) {
            RedisCommands<String, String> c = conn.sync();
            for (int i = 0; i < 50; i++) {
                c.set("rc:lscan:" + i, "v");
            }
            Set<String> found = new HashSet<>();
            ScanIterator.scan(c, ScanArgs.Builder.matches("rc:lscan:*").limit(10)).forEachRemaining(found::add);
            assertEquals(50, found.size(), "every key, from every node");
        }
    }

    // --- A database chosen in the connection settings ---

    /**
     * Clients given a database in their settings select it in their connection handshake, along with
     * HELLO and CLIENT SETINFO, rather than through a SELECT call the application makes.
     */
    @ParameterizedTest
    @EnumSource(Protocol.class)
    void jedisDatabaseFromTheConnectionSettings(Protocol protocol) {
        String key = "rc:jdb:" + protocol;
        try (Jedis j = new Jedis(PROXY, DefaultJedisClientConfig.builder().database(5)
                .protocol(protocol == Protocol.RESP3 ? RedisProtocol.RESP3 : RedisProtocol.RESP2)
                .socketTimeoutMillis(5000).build())) {
            assertEquals("OK", j.set(key, "five"));
            assertEquals("five", j.get(key));
        }
        assertOnlyInDatabase(key, "five", 5);
    }

    /** Lettuce also selects the database again when it reconnects, as after the proxy closes its connection. */
    @ParameterizedTest
    @EnumSource(Protocol.class)
    void lettuceDatabaseFromTheConnectionSettings(Protocol protocol) throws Exception {
        String key = "rc:ldb:" + protocol;
        RedisClient client = RedisClient.create(RedisURI.builder().withHost("127.0.0.1").withPort(RESP_PORT)
                .withDatabase(6).build());
        client.setOptions(ClientOptions.builder()
                .protocolVersion(protocol == Protocol.RESP3 ? ProtocolVersion.RESP3 : ProtocolVersion.RESP2).build());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> c = conn.sync();
            assertEquals("OK", c.set(key, "six"));
            assertEquals("six", c.get(key));

            long before = c.clientId();
            try (Jedis admin = jedis(Protocol.RESP2)) {
                admin.sendCommand(redis.clients.jedis.Protocol.Command.CLIENT, "KILL", "ID", Long.toString(before));
            }
            assertTrue(waitFor(() -> {
                try {
                    return c.clientId() != before;
                } catch (RuntimeException reconnecting) {
                    return false;
                }
            }), "Lettuce reconnected");
            assertEquals("OK", c.set(key + ":after", "six again"));
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
        assertOnlyInDatabase(key, "six", 6);
        assertOnlyInDatabase(key + ":after", "six again", 6);
    }

    /** The key is in {@code db}, read through a plain connection that selects it, and not in database 0. */
    private static void assertOnlyInDatabase(String key, String value, int db) {
        try (Jedis plain = jedis(Protocol.RESP2)) {
            assertNull(plain.get(key), "not in database 0");
            plain.select(db);
            assertEquals(value, plain.get(key), "in database " + db);
            plain.del(key);
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
