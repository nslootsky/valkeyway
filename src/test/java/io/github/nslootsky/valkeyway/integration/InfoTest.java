/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** INFO as a standalone server answers it, from the cluster and the proxy's own state. [upstream:V10] */
class InfoTest extends IntegrationTestBase {

    private static RawRespClient connect() throws IOException {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    private static String field(String info, String name) {
        for (String line : info.split("\r\n")) {
            if (line.startsWith(name + ":")) {
                return line.substring(name.length() + 1);
            }
        }
        return null;
    }

    @Test
    void looksLikeAStandaloneServer() throws Exception {
        try (RawRespClient c = connect()) {
            String info = (String) c.call("INFO");
            assertEquals("standalone", field(info, "server_mode"), info);
            assertEquals("name=tcp,bind=127.0.0.1,port=" + RESP_PORT, field(info, "listener0"), "the proxy's listener");
            assertNull(field(info, "listener1"));
            assertEquals("master", field(info, "role"));
            assertEquals("0", field(info, "connected_slaves"));
            assertEquals("0", field(info, "cluster_enabled"));
            assertEquals(Integer.toString(RESP_PORT), field(info, "tcp_port"));
            assertNotNull(field(info, "redis_version"));
            assertNotNull(field(info, "used_memory"), "sections from a node");
            assertEquals(io.github.nslootsky.valkeyway.ProxyVersion.get(), field(info, "valkeyway_version"));
            assertNotEquals("unknown", io.github.nslootsky.valkeyway.ProxyVersion.get(), "from the build");
        }
    }

    @Test
    void blockedClientsAreCounted() throws Exception {
        try (RawRespClient c = connect(); RawRespClient blocked = connect()) {
            blocked.call("DEL", "info:list");
            CompletableFuture<Object> pop = CompletableFuture.supplyAsync(() -> {
                try {
                    return blocked.call("BLPOP", "info:list", "5");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            long deadline = System.currentTimeMillis() + 5000;
            String count = "0";
            while (System.currentTimeMillis() < deadline && "0".equals(count)) {
                count = field((String) c.call("INFO", "clients"), "blocked_clients");
            }
            assertEquals("1", count);
            assertTrue(((String) c.call("CLIENT", "LIST")).contains("flags=b"));
            c.call("RPUSH", "info:list", "x");
            pop.get(5, TimeUnit.SECONDS);
            assertEquals("0", field((String) c.call("INFO", "clients"), "blocked_clients"));
        }
    }

    /** total_blocking_keys counts keys, not clients: two clients blocked on one key are one key. */
    @Test
    void blockingKeysAreCountedOnce() throws Exception {
        try (RawRespClient c = connect(); RawRespClient a = connect(); RawRespClient b = connect()) {
            c.call("DEL", "info:same");
            a.send("BLPOP", "info:same", "5");
            b.send("BLPOP", "info:same", "5");
            long deadline = System.currentTimeMillis() + 5000;
            String info = "";
            while (System.currentTimeMillis() < deadline && !"2".equals(field(info, "blocked_clients"))) {
                info = (String) c.call("INFO", "clients");
            }
            assertEquals("2", field(info, "blocked_clients"));
            assertEquals("1", field(info, "total_blocking_keys"));
            c.call("RPUSH", "info:same", "1", "2");
            a.readReply();
            b.readReply();
        }
    }

    @Test
    void keyspaceAndExpiredKeysCoverTheWholeCluster() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("SELECT", "7");
            c.call("FLUSHDB");
            for (int i = 0; i < 30; i++) {
                c.call("SET", "info:k" + i, "v"); // spread over every primary
            }
            assertEquals("keys=30,expires=0", field((String) c.call("INFO", "keyspace"), "db7").replaceAll(",avg_ttl=.*", ""));

            long before = Long.parseLong(field((String) c.call("INFO", "stats"), "expired_keys"));
            for (int i = 0; i < 5; i++) {
                c.call("SET", "info:e" + i, "v", "PX", "1");
            }
            Thread.sleep(50);
            for (int i = 0; i < 5; i++) {
                assertNull(c.call("GET", "info:e" + i)); // expires them lazily
            }
            long after = Long.parseLong(field((String) c.call("INFO", "stats"), "expired_keys"));
            assertTrue(after - before >= 5, before + " -> " + after);
            c.call("FLUSHDB");
        }
    }

    @Test
    void errorRepliesAreTheProxys() throws Exception {
        try (RawRespClient c = connect()) {
            long before = Long.parseLong(field((String) c.call("INFO", "stats"), "total_error_replies"));
            c.call("NOSUCHCOMMAND");
            c.call("GET");
            long after = Long.parseLong(field((String) c.call("INFO", "stats"), "total_error_replies"));
            assertTrue(after - before >= 2, before + " -> " + after);
        }
    }
}
