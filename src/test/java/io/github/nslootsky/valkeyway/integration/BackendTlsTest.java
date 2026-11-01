/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.ValkeywayApplication;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import io.github.nslootsky.valkeyway.server.TestCertificates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The proxy in front of a cluster that only accepts TLS clients with a password
 * ({@code proxy.backend.*}), and with client certificates required on its own TLS port.
 */
@Tag("integration")
@SpringBootTest(
        classes = ValkeywayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.host=127.0.0.1", "proxy.port=" + BackendTlsTest.PORT,
                "proxy.tls.port=" + BackendTlsTest.TLS_PORT, "proxy.tls.client-certs=required",
                "proxy.backend.username=proxy", "proxy.backend.password=backend-pw",
                "proxy.backend.tls.enabled=true"}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackendTlsTest {

    static final int PORT = 6395;
    static final int TLS_PORT = 6396;
    private static final Status OK = new Status("OK");
    private static final TestCertificates certs = TestCertificates.get();

    private static final ValkeyClusterTestContainer cluster =
            new ValkeyClusterTestContainer(3, 0, 0, 27300, 5000, "", certs);

    static {
        cluster.start();
        // Only the "proxy" user, with a password, may connect from now on.
        cluster.onEveryNode("ACL", "SETUSER", "proxy", "on", ">backend-pw", "~*", "&*", "+@all");
        cluster.onEveryNode("ACL", "SETUSER", "default", "off");
    }

    @AfterAll
    static void stopCluster() {
        cluster.close();
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("proxy.cluster-nodes", () -> cluster.getTlsNodeAddresses().toArray(String[]::new));
        registry.add("proxy.backend.tls.ca-file", () -> certs.caCert().toString());
        registry.add("proxy.tls.cert-file", () -> certs.serverCert().toString());
        registry.add("proxy.tls.key-file", () -> certs.serverKey().toString());
        registry.add("proxy.tls.ca-file", () -> certs.caCert().toString());
    }

    private static RawRespClient proxy() throws IOException {
        return new RawRespClient("127.0.0.1", PORT);
    }

    @Test
    void commandsReachEveryShardOverTls() throws Exception {
        try (RawRespClient c = proxy()) {
            List<String[]> sets = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                sets.add(new String[]{"SET", "btls:" + i, "v" + i});
            }
            for (Object reply : c.pipeline(sets)) {
                assertEquals(OK, reply);
            }
            for (int i = 0; i < 100; i += 7) {
                assertEquals("v" + i, c.call("GET", "btls:" + i));
            }
            assertTrue(((String) c.call("PROXY", "CLUSTER", "INFO")).contains("cluster_state:ok"));
        }
    }

    @Test
    void theProxyAuthenticatesAsTheBackendUserOverTls() throws Exception {
        try (RawRespClient c = proxy()) {
            assertEquals(OK, c.call("SET", "btls:who", "x"));
            assertEquals("default", c.call("ACL", "WHOAMI"), "the client's own user on the proxy");
        }
        String first = cluster.getNodeAddress(0);
        try (RawRespClient node = new RawRespClient("127.0.0.1", Integer.parseInt(first.split(":")[1]))) {
            assertEquals(OK, node.call("AUTH", "proxy", "backend-pw"));
            String tlsAddress = "127.0.0.1:" + ValkeyClusterTestContainer.tlsPort(Integer.parseInt(first.split(":")[1]));
            String clients = (String) node.call("CLIENT", "LIST");
            assertTrue(clients.lines().anyMatch(l -> l.contains("laddr=" + tlsAddress) && l.contains(" user=proxy ")),
                    "the proxy's connections arrive on the TLS port as user proxy:\n" + clients);
        }
    }

    /** Blocking commands and WATCH use their own backend clients, which need the same settings. */
    @Test
    void dedicatedClientsConnectToo() throws Exception {
        try (RawRespClient blocked = proxy(); RawRespClient pusher = proxy(); RawRespClient watcher = proxy()) {
            blocked.call("DEL", "btls:list");
            CompletableFuture<Object> pop = CompletableFuture.supplyAsync(() -> {
                try {
                    return blocked.call("BLPOP", "btls:list", "5");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(200);
            pusher.call("RPUSH", "btls:list", "item");
            assertEquals(List.of("btls:list", "item"), pop.get(10, TimeUnit.SECONDS));

            assertEquals(OK, watcher.call("WATCH", "btls:w"));
            assertEquals(OK, watcher.call("MULTI"));
            watcher.call("SET", "btls:w", "1");
            assertEquals(List.of(OK), watcher.call("EXEC"));
        }
    }

    @Test
    void clientCertificatesAreRequiredOnTheTlsPort() throws Exception {
        assertThrows(IOException.class, () -> {
            SSLSocket socket = (SSLSocket) certs.clientSockets().createSocket("127.0.0.1", TLS_PORT);
            socket.startHandshake();
            try (RawRespClient c = new RawRespClient(socket)) {
                c.call("PING");
            }
        });
        SSLSocket socket = (SSLSocket) certs.clientSocketsWithCertificate().createSocket("127.0.0.1", TLS_PORT);
        socket.startHandshake();
        try (RawRespClient c = new RawRespClient(socket)) {
            assertEquals(OK, c.call("SET", "btls:mtls", "x"));
            assertEquals("x", c.call("GET", "btls:mtls"));
        }
    }
}
