/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import glide.api.GlideClient;
import glide.api.models.PubSubMessage;
import glide.api.models.configuration.GlideClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.StandaloneSubscriptionConfiguration;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Push;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static glide.api.models.configuration.StandaloneSubscriptionConfiguration.PubSubChannelMode.EXACT;
import static io.github.nslootsky.valkeyway.integration.RawRespClient.NULL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SUBSCRIBE, PSUBSCRIBE and SSUBSCRIBE through the proxy: confirmations and errors as a server
 * sends them (compared with a node), and messages from the cluster fanned out to subscribers.
 */
class PubSubTest extends IntegrationTestBase {

    private static final int QUIET_MS = 300;
    /** How long a message may take to arrive. */
    private static final int MESSAGE_MS = 3000;

    private static RawRespClient proxy() throws IOException {
        return new RawRespClient("127.0.0.1", RESP_PORT);
    }

    private static RawRespClient node(String key) throws IOException {
        try (DirectNodes nodes = new DirectNodes(cluster.getNodeAddresses())) {
            String[] address = nodes.ownerOf(key).split(":");
            return new RawRespClient(address[0], Integer.parseInt(address[1]));
        }
    }

    private static List<Object> message(String channel, String payload) {
        return List.of("message", channel, payload);
    }

    /** Waits for the next pushed message. */
    private static Object next(RawRespClient c) throws IOException {
        List<Object> got = c.readUntilQuiet(MESSAGE_MS);
        assertFalse(got.isEmpty(), "no message arrived");
        return got.getFirst();
    }

    /** Runs the same session against a node and through the proxy and compares every frame. */
    private static void assertSameSession(boolean resp3, String shardChannel) throws Exception {
        String[][] session = {
                {"SUBSCRIBE", "a", "b"}, {"SUBSCRIBE", "a"}, {"GET", "k"}, {"PING"}, {"PING", "x"},
                {"PSUBSCRIBE", "p*"}, {"SSUBSCRIBE", shardChannel}, {"UNSUBSCRIBE", "a", "zz"}, {"UNSUBSCRIBE"},
                {"PUNSUBSCRIBE"}, {"UNSUBSCRIBE"}, {"SUNSUBSCRIBE"}, {"SUNSUBSCRIBE"}, {"SUBSCRIBE"}, {"GET", "k"},
        };
        List<Object> expected;
        List<Object> actual;
        try (RawRespClient node = node(shardChannel); RawRespClient proxy = proxy()) {
            for (RawRespClient c : List.of(node, proxy)) {
                c.call("DEL", "k");
                if (resp3) {
                    c.call("HELLO", "3");
                }
                for (String[] command : session) {
                    c.send(command);
                }
            }
            expected = node.readUntilQuiet(QUIET_MS);
            // The proxy's first subscription of a kind creates its subscriber client, which takes longer.
            actual = proxy.readUntilQuiet(1000);
        }
        assertEquals(expected, actual);
    }

    @Test
    void confirmationsAndErrorsMatchAServer() throws Exception {
        assertSameSession(false, "pubsub:shard1");
    }

    @Test
    void confirmationsMatchAServerInResp3() throws Exception {
        assertSameSession(true, "pubsub:shard2");
    }

    @Test
    void messagesReachSubscribers() throws Exception {
        try (RawRespClient sub = proxy(); RawRespClient pub = proxy()) {
            assertEquals(List.of("subscribe", "ps:news", 1L), sub.call("SUBSCRIBE", "ps:news"));
            pub.call("PUBLISH", "ps:news", "hello");
            assertEquals(message("ps:news", "hello"), next(sub));

            String binary = "ÿ\u0000\u0080\r\n";
            pub.call("PUBLISH", "ps:news", binary);
            assertEquals(message("ps:news", binary), next(sub), "binary-safe payloads");
        }
    }

    @Test
    void messagesPublishedOnTheClusterArrive() throws Exception {
        try (RawRespClient sub = proxy(); RawRespClient direct = node("ps:anything")) {
            sub.call("SUBSCRIBE", "ps:direct");
            direct.call("PUBLISH", "ps:direct", "from a node");
            assertEquals(message("ps:direct", "from a node"), next(sub));
        }
    }

    @Test
    void patternsAndShardChannels() throws Exception {
        try (RawRespClient sub = proxy(); RawRespClient pub = proxy()) {
            assertEquals(List.of("psubscribe", "ps:p.*", 1L), sub.call("PSUBSCRIBE", "ps:p.*"));
            pub.call("PUBLISH", "ps:p.one", "x");
            assertEquals(List.of("pmessage", "ps:p.*", "ps:p.one", "x"), next(sub));

            assertEquals(List.of("ssubscribe", "{ps}shard", 1L), sub.call("SSUBSCRIBE", "{ps}shard"));
            pub.call("SPUBLISH", "{ps}shard", "y");
            assertEquals(List.of("smessage", "{ps}shard", "y"), next(sub));
        }
    }

    @Test
    void everySubscriberGetsEachMessageUntilItUnsubscribes() throws Exception {
        try (RawRespClient a = proxy(); RawRespClient b = proxy(); RawRespClient pub = proxy()) {
            a.call("SUBSCRIBE", "ps:fan");
            b.call("SUBSCRIBE", "ps:fan");
            pub.call("PUBLISH", "ps:fan", "1");
            assertEquals(message("ps:fan", "1"), next(a));
            assertEquals(message("ps:fan", "1"), next(b));

            assertEquals(List.of("unsubscribe", "ps:fan", 0L), a.call("UNSUBSCRIBE", "ps:fan"));
            pub.call("PUBLISH", "ps:fan", "2");
            assertEquals(message("ps:fan", "2"), next(b));
            assertEquals(List.of(), a.readUntilQuiet(QUIET_MS), "nothing after unsubscribing");
            assertEquals(new Status("OK"), a.call("SET", "ps:k", "v"), "a normal client again");
        }
    }

    /** The cluster subscription is dropped with the last subscriber and made again for the next one. */
    @Test
    void resubscribingAfterTheLastSubscriberLeft() throws Exception {
        try (RawRespClient pub = proxy()) {
            for (int round = 0; round < 5; round++) {
                try (RawRespClient sub = proxy()) {
                    sub.call("SUBSCRIBE", "ps:again");
                    pub.call("PUBLISH", "ps:again", "r" + round);
                    assertEquals(message("ps:again", "r" + round), next(sub), "round " + round);
                    if (round % 2 == 0) {
                        sub.call("UNSUBSCRIBE");
                    }
                } // odd rounds: dropped by the disconnect
            }
        }
    }

    @Test
    void resetDropsSubscriptions() throws Exception {
        try (RawRespClient c = proxy(); RawRespClient pub = proxy()) {
            c.call("SUBSCRIBE", "ps:reset");
            assertEquals(new Status("RESET"), c.call("RESET"));
            pub.call("PUBLISH", "ps:reset", "x");
            assertEquals(List.of(), c.readUntilQuiet(QUIET_MS));
            assertNull(c.call("GET", "ps:missing"));
        }
    }

    @Test
    void resp3ClientsKeepRunningCommandsWhileSubscribed() throws Exception {
        try (RawRespClient c = proxy(); RawRespClient pub = proxy()) {
            c.call("HELLO", "3");
            assertEquals(new Push(List.of("subscribe", "ps:r3", 1L)), c.call("SUBSCRIBE", "ps:r3"));
            assertEquals(NULL, c.call("GET", "ps:missing"));
            String line = ((RawRespClient.Verbatim) c.call("CLIENT", "INFO")).text();
            assertTrue(line.contains(" sub=1 psub=0 ssub=0 ") && line.contains("flags=P"), line);
            assertTrue(((RawRespClient.Verbatim) c.call("CLIENT", "LIST", "TYPE", "pubsub")).text().contains("id=" + c.call("CLIENT", "ID")));

            pub.call("PUBLISH", "ps:r3", "pushed");
            assertEquals(new Push(List.of("message", "ps:r3", "pushed")), next(c));
        }
    }

    @Test
    void notInsideTransactions() throws Exception {
        try (RawRespClient c = proxy()) {
            c.call("MULTI");
            assertInstanceOf(RawRespClient.Error.class, c.call("SUBSCRIBE", "ps:tx"));
            assertInstanceOf(RawRespClient.Error.class, c.call("EXEC"), "the transaction is discarded");
            assertEquals(new Status("PONG"), c.call("PING"), "and the client isn't subscribed");
        }
    }

    /** A real client: Glide subscribes during connection setup and reads pushes (RESP3). */
    @Test
    void glideClientSubscription() throws Exception {
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        GlideClientConfiguration config = GlideClientConfiguration.builder()
                .address(NodeAddress.builder().host("127.0.0.1").port(RESP_PORT).build())
                .subscriptionConfiguration(StandaloneSubscriptionConfiguration.builder()
                        .subscription(EXACT, glide.api.models.GlideString.of("ps:glide"))
                        .callback((PubSubMessage m, Object _) -> received.add(m.getMessage().getString()), null)
                        .build())
                .requestTimeout(5000)
                .build();
        try (GlideClient client = GlideClient.createClient(config).get(10, TimeUnit.SECONDS);
             RawRespClient pub = proxy()) {
            assertEquals("v", client.set("ps:glide:k", "v").thenCompose(_ -> client.get("ps:glide:k")).get(5, TimeUnit.SECONDS));
            List<String> sent = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            for (int i = 0; received.size() < 3 && System.currentTimeMillis() < deadline; i++) {
                pub.call("PUBLISH", "ps:glide", "m" + i);
                sent.add("m" + i);
                Thread.sleep(100);
            }
            assertTrue(received.size() >= 3, "received " + received + " of " + sent);
        }
    }
}
