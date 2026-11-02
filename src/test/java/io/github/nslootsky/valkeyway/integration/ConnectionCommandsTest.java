/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Connection-scoped commands (CLIENT, RESET, AUTH, MONITOR, SUBSCRIBE, ...) are answered by the
 * proxy. Forwarded, they acted on backend connections every client shares: CLIENT REPLY OFF
 * silenced a node for everyone, RESET on a database-1 connection switched everyone to database 0,
 * MONITOR desynchronized a connection, CLIENT PAUSE stalled a node.
 */
class ConnectionCommandsTest extends IntegrationTestBase {

    private static final Status OK = new Status("OK");

    private static RawRespClient connect() throws IOException {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    /** Every key in both databases still reads back correctly through another connection. */
    private static void assertOthersUnaffected() throws IOException {
        try (RawRespClient b = connect()) {
            for (int i = 0; i < 30; i++) {
                assertEquals("v0", b.call("GET", "conn:" + i), "db 0 key " + i);
            }
            b.call("SELECT", "1");
            for (int i = 0; i < 30; i++) {
                assertEquals("v1", b.call("GET", "conn:" + i), "db 1 key " + i);
            }
        }
    }

    private static void seed() throws IOException {
        try (RawRespClient c = connect()) {
            for (int i = 0; i < 30; i++) {
                c.call("SET", "conn:" + i, "v0");
            }
            c.call("SELECT", "1");
            for (int i = 0; i < 30; i++) {
                c.call("SET", "conn:" + i, "v1");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT REPLY OFF", "RESET", "MONITOR", "CLIENT PAUSE 1000", "CLIENT KILL TYPE normal",
            "CLIENT TRACKING ON", "SUBSCRIBE ch", "READONLY", "AUTH default x"})
    void connectionCommandsDontAffectOtherClients(String command) throws Exception {
        seed();
        for (int db : new int[]{0, 1}) {
            try (RawRespClient a = connect()) {
                a.call("SELECT", Integer.toString(db));
                a.send(command.split(" "));
                Thread.sleep(100);
                assertOthersUnaffected();
            }
        }
    }

    @Test
    void clientReplyOffOnAndSkip() throws Exception {
        try (RawRespClient c = connect()) {
            c.send("CLIENT", "REPLY", "OFF");
            c.send("SET", "conn:reply", "1");
            assertEquals(OK, c.call("CLIENT", "REPLY", "ON"), "the first reply after OFF is ON's own");
            assertEquals("1", c.call("GET", "conn:reply"));

            c.send("CLIENT", "REPLY", "SKIP");
            c.send("SET", "conn:reply", "2");
            assertEquals("2", c.call("GET", "conn:reply"), "SKIP drops its own reply and the next one");
        }
    }

    @Test
    void clientListAndInfoShowTheProxysClients() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            assertEquals(OK, a.call("CLIENT", "SETNAME", "alpha"));
            assertEquals(OK, a.call("CLIENT", "SETINFO", "LIB-NAME", "mylib"));
            a.call("SELECT", "3");
            long aId = (Long) a.call("CLIENT", "ID");

            String list = (String) b.call("CLIENT", "LIST");
            String aLine = list.lines().filter(l -> l.startsWith("id=" + aId + " ")).findFirst().orElseThrow();
            assertTrue(aLine.contains(" name=alpha ") && aLine.contains(" db=3 ") && aLine.contains(" lib-name=mylib "), aLine);
            assertTrue(aLine.contains(" addr=127.0.0.1:") && aLine.contains(" laddr=127.0.0.1:" + RESP_PORT + " "), aLine);

            assertEquals(1, ((String) b.call("CLIENT", "LIST", "ID", Long.toString(aId))).lines().count());
            assertEquals("", b.call("CLIENT", "LIST", "TYPE", "pubsub"));
            String info = (String) a.call("CLIENT", "INFO");
            assertTrue(info.startsWith("id=" + aId + " ") && info.endsWith("\n"), info);
            assertEquals(new Error("ERR unknown subcommand 'NOPE'. Try CLIENT HELP."), a.call("CLIENT", "NOPE"));
            assertEquals(new Error("ERR CLIENT PAUSE is not supported by this proxy"), a.call("CLIENT", "PAUSE", "10"));
        }
    }

    @Test
    void clientKillByIdAddressAndSelf() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect(); RawRespClient c = connect()) {
            long bId = (Long) b.call("CLIENT", "ID");
            assertEquals(1L, a.call("CLIENT", "KILL", "ID", Long.toString(bId)));
            assertThrows(IOException.class, () -> b.call("PING"), "the killed client is disconnected");

            String cLine = (String) c.call("CLIENT", "INFO");
            String cAddr = cLine.split(" addr=")[1].split(" ")[0];
            assertEquals(OK, a.call("CLIENT", "KILL", cAddr), "old form");
            assertThrows(IOException.class, () -> c.call("PING"));
            assertEquals(new Error("ERR No such client"), a.call("CLIENT", "KILL", "1.2.3.4:5"));

            long aId = (Long) a.call("CLIENT", "ID");
            assertEquals(1L, a.call("CLIENT", "KILL", "ID", Long.toString(aId), "SKIPME", "no"),
                    "a client killing itself gets its reply first");
            assertThrows(IOException.class, () -> a.call("PING"));
        }
    }

    @Test
    void resetRestoresTheConnectionsDefaults() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("SET", "conn:reset", "db0");
            c.call("SELECT", "1");
            c.call("CLIENT", "SETNAME", "named");
            c.call("MULTI");
            c.call("SET", "conn:reset", "never");
            assertEquals(new Status("RESET"), c.call("RESET"));
            assertEquals("db0", c.call("GET", "conn:reset"), "back in database 0");
            assertNull(c.call("CLIENT", "GETNAME"));
            assertEquals(new Error("ERR EXEC without MULTI"), c.call("EXEC"), "the transaction is gone");
        }
    }

    /** As a standalone Valkey 9 with no password configured answers. */
    @Test
    void authAnswersLikeAServerWithoutPasswords() throws Exception {
        try (RawRespClient c = connect()) {
            assertEquals(new Error("ERR AUTH <password> called without any password configured for the default user. "
                    + "Are you sure your configuration is correct?"), c.call("AUTH", "x"));
            assertEquals(OK, c.call("AUTH", "default", "x"));
            assertEquals(new Error("WRONGPASS invalid username-password pair or user is disabled."), c.call("AUTH", "bob", "x"));
            assertEquals(new Error("ERR syntax error"), c.call("AUTH", "a", "b", "c"));
        }
    }

    /**
     * A command unblocked while still on its way to the node must not block there afterwards and
     * take an element nobody receives. The proxy had that race (about 1 in 16 times locally): it ended
     * the client's wait itself when the node didn't have the command yet. Each round unblocks a fresh
     * BLPOP at once, then checks that the next element goes to the next BLPOP.
     */
    @Test
    void anUnblockedCommandNeverTakesALaterElement() throws Exception {
        for (int round = 0; round < 60; round++) {
            try (RawRespClient admin = connect(); RawRespClient blocked = connect()) {
                String id = blocked.call("CLIENT", "ID").toString();
                for (String mode : new String[]{"TIMEOUT", "ERROR"}) {
                    blocked.call("DEL", "conn:race");
                    blocked.send("BLPOP", "conn:race", "0");
                    long deadline = System.currentTimeMillis() + 5000;
                    while (admin.call("CLIENT", "UNBLOCK", id, mode).equals(0L) && System.currentTimeMillis() < deadline) {
                        Thread.onSpinWait();
                    }
                    blocked.readReply();
                    assertEquals(new Status("PONG"), blocked.call("PING"));
                }
                blocked.send("BLPOP", "conn:race", "0");
                long deadline = System.currentTimeMillis() + 5000;
                while (!((String) admin.call("CLIENT", "LIST", "ID", id)).contains("flags=b")
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(1);
                }
                admin.call("LPUSH", "conn:race", "x" + round);
                assertEquals(List.of("conn:race", "x" + round), blocked.readReply(), "round " + round);
            }
        }
    }

    /** CLIENT UNBLOCK ends another client's blocking command, as a timeout or with an error, as on Valkey. */
    @Test
    void clientUnblock() throws Exception {
        try (RawRespClient admin = connect(); RawRespClient blocked = connect()) {
            long id = (Long) blocked.call("CLIENT", "ID");
            assertEquals(0L, admin.call("CLIENT", "UNBLOCK", Long.toString(id)), "not blocked");
            assertEquals(new Error("ERR CLIENT UNBLOCK reason should be TIMEOUT or ERROR"),
                    admin.call("CLIENT", "UNBLOCK", Long.toString(id), "maybe"));
            assertEquals(new Error("ERR value is not an integer or out of range"), admin.call("CLIENT", "UNBLOCK", "x"));

            for (String mode : new String[]{"TIMEOUT", "ERROR"}) {
                blocked.call("DEL", "conn:unblock");
                blocked.send("BLPOP", "conn:unblock", "0");
                long deadline = System.currentTimeMillis() + 5000;
                Object unblocked = 0L;
                while (unblocked.equals(0L) && System.currentTimeMillis() < deadline) {
                    unblocked = admin.call("CLIENT", "UNBLOCK", Long.toString(id), mode);
                }
                assertEquals(1L, unblocked, mode);
                Object reply = blocked.readReply();
                assertEquals(mode.equals("ERROR") ? new Error("UNBLOCKED client unblocked via CLIENT UNBLOCK")
                        : RawRespClient.NULL_ARRAY, reply, mode);
                assertEquals(new Status("PONG"), blocked.call("PING"));
            }
            // The unblocked commands are gone from the node too: the next one gets the element.
            blocked.send("BLPOP", "conn:unblock", "0");
            long deadline = System.currentTimeMillis() + 5000;
            while (!((String) admin.call("CLIENT", "LIST", "ID", Long.toString(id))).contains("flags=b")
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            admin.call("LPUSH", "conn:unblock", "x");
            assertEquals(List.of("conn:unblock", "x"), blocked.readReply());
        }
    }

    /** A disconnected client's BLPOP ends: it doesn't pop the next value pushed, for nobody. */
    @Test
    void aDisconnectedClientsBlockingCommandEnds() throws Exception {
        try (RawRespClient c = connect()) {
            c.call("DEL", "conn:orphan");
            for (int i = 0; i < 3; i++) {
                RawRespClient blocked = connect();
                blocked.send("BLPOP", "conn:orphan", "0");
                long deadline = System.currentTimeMillis() + 5000;
                while (!((String) c.call("INFO", "clients")).contains("blocked_clients:1")
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                blocked.close();
            }
            Thread.sleep(500);
            assertTrue(((String) c.call("INFO", "clients")).contains("blocked_clients:0"));
            c.call("RPUSH", "conn:orphan", "kept");
            Thread.sleep(200);
            assertEquals(List.of("kept"), c.call("LRANGE", "conn:orphan", "0", "-1"), "no orphaned BLPOP took it");
        }
    }

    @Test
    void clusterCredentialsAreNotShown() throws Exception {
        try (RawRespClient c = connect()) {
            Object requirepass = c.call("CONFIG", "GET", "requirepass");
            assertEquals(List.of("requirepass", "(redacted by the proxy)"), requirepass);
            List<?> all = (List<?>) c.call("CONFIG", "GET", "*");
            int i = all.indexOf("masterauth");
            assertTrue(i >= 0 && "(redacted by the proxy)".equals(all.get(i + 1)), "masterauth in CONFIG GET *");
            assertEquals(2, ((List<?>) c.call("CONFIG", "GET", "maxmemory")).size(), "other settings as usual");
            assertInstanceOf(Error.class, c.call("ACL", "LIST"));
            assertInstanceOf(Error.class, c.call("ACL", "GETUSER", "default"));
            assertInstanceOf(List.class, c.call("ACL", "USERS"), "names only: allowed");
        }
    }

    @Test
    void selectingADatabaseTheClusterDoesntHave() throws Exception {
        try (RawRespClient c = connect()) {
            assertEquals(new Error("ERR DB index is out of range"), c.call("SELECT", "99"));
            assertEquals(new Status("OK"), c.call("SELECT", "15"));
            assertEquals(new Status("PONG"), c.call("PING"));
        }
    }

    @Test
    void importSourceAnswersAsWithImportModeOff() throws Exception {
        try (RawRespClient c = connect()) {
            assertEquals(new Error("ERR Server is not in import mode"), c.call("CLIENT", "IMPORT-SOURCE", "ON"));
        }
    }

    @Test
    void proxyCommands() throws Exception {
        try (RawRespClient a = connect(); RawRespClient b = connect()) {
            // A connection counts once the proxy has accepted it, which can be after connect() returns.
            assertEquals(new Status("PONG"), b.call("PING"));
            String stats = (String) a.call("PROXY", "STATS");
            int connected = Integer.parseInt(stats.split("connected_clients:")[1].split("\r\n")[0]);
            assertTrue(connected >= 2, stats);
            assertTrue(stats.contains("transactions_cross_slot:reject"), stats);

            long bId = (Long) b.call("CLIENT", "ID");
            String info = (String) a.call("PROXY", "CLIENTINFO", Long.toString(bId));
            assertTrue(info.startsWith("id=" + bId + " "), info);
            assertEquals(new Error("ERR No such client"), a.call("PROXY", "CLIENTINFO", "999999999"));

            assertEquals(List.of("transactions.cross-slot", "reject"), a.call("PROXY", "CONFIG", "GET", "transactions.cross-slot"));
            assertTrue(((List<?>) a.call("PROXY", "CONFIG", "GET", "*")).size() > 10);
            assertInstanceOf(Error.class, a.call("PROXY", "CONFIG", "SET", "x", "y"));
            assertEquals(new Error("ERR unknown subcommand 'FLUSHCLIENTS'. Try PROXY HELP."), a.call("PROXY", "FLUSHCLIENTS"));
            assertTrue(((String) a.call("PROXY", "CLUSTER", "INFO")).contains("cluster_state:ok"));
        }
    }

    /** Node administration through the proxy would hit whichever node Glide picks; it's refused. */
    @Test
    void nodeAdminCommandsAreRefused() throws Exception {
        try (RawRespClient c = connect()) {
            for (String[] cmd : List.of(new String[]{"SHUTDOWN", "NOSAVE"}, new String[]{"CONFIG", "SET", "maxmemory", "1"},
                    new String[]{"CLUSTER", "MEET", "127.0.0.1", "1"}, new String[]{"DEBUG", "SLEEP", "1"},
                    new String[]{"BGSAVE"})) {
                Object reply = c.call(cmd);
                assertTrue(reply instanceof Error(String m) && m.contains("administers a single node"),
                        String.join(" ", cmd) + " -> " + reply);
            }
            assertEquals(OK, c.call("SET", "conn:admin", "still-up"), "every node is still there");
            assertInstanceOf(List.class, c.call("CONFIG", "GET", "maxmemory"), "read-only variants pass");
            assertInstanceOf(Long.class, c.call("CLUSTER", "KEYSLOT", "k"));

            c.call("MULTI");
            Object queued = c.call("SAVE");
            assertTrue(queued instanceof Error(String m) && m.contains("administers a single node"), "in MULTI: " + queued);
            assertEquals(new Error("EXECABORT Transaction discarded because of previous errors."), c.call("EXEC"));
        }
    }
}
