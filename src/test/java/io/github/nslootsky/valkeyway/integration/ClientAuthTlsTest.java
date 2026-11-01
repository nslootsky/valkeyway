/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import glide.api.GlideClient;
import glide.api.models.configuration.AdvancedGlideClientConfiguration;
import glide.api.models.configuration.GlideClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.ServerCredentials;
import glide.api.models.configuration.TlsAdvancedConfiguration;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import io.github.nslootsky.valkeyway.server.TestCertificates;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Client authentication ({@code proxy.auth.*}) and the client TLS port ({@code proxy.tls.*}), with
 * replies as a standalone Valkey 9 with {@code requirepass} and an ACL user answers them.
 */
class ClientAuthTlsTest extends IntegrationTestBase {

    private static final int PORT = 6393;
    private static final int TLS_PORT = 6394;
    private static final TestCertificates certs = TestCertificates.get();
    private static final Status OK = new Status("OK");
    private static final Status PONG = new Status("PONG");
    private static final Error NOAUTH = new Error("NOAUTH Authentication required.");
    private static final Error WRONGPASS = new Error("WRONGPASS invalid username-password pair or user is disabled.");

    @DynamicPropertySource
    static void authAndTls(DynamicPropertyRegistry registry) {
        registry.add("proxy.port", () -> PORT);
        registry.add("proxy.auth.password", () -> "secret");
        registry.add("proxy.auth.users.app", () -> "apppw");
        registry.add("proxy.tls.port", () -> TLS_PORT);
        registry.add("proxy.tls.cert-file", () -> certs.serverCert().toString());
        registry.add("proxy.tls.key-file", () -> certs.serverKey().toString());
        registry.add("proxy.tls.ca-file", () -> certs.caCert().toString());
        registry.add("proxy.tls.client-certs", () -> "optional");
    }

    private static RawRespClient plain() throws IOException {
        return new RawRespClient("127.0.0.1", PORT);
    }

    private static RawRespClient tls(boolean withCertificate) throws IOException {
        var factory = withCertificate ? certs.clientSocketsWithCertificate() : certs.clientSockets();
        SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", TLS_PORT);
        socket.startHandshake();
        return new RawRespClient(socket);
    }

    @Test
    void commandsNeedAuthFirst() throws Exception {
        try (RawRespClient c = plain()) {
            assertEquals(NOAUTH, c.call("GET", "auth:k"));
            assertEquals(NOAUTH, c.call("PING"));
            assertEquals(NOAUTH, c.call("CLIENT", "ID"));
            assertEquals(NOAUTH, c.call("SELECT", "1"));
            assertEquals(NOAUTH, c.call("MULTI"));
            assertEquals(NOAUTH, c.call("ACL", "WHOAMI"));
            assertEquals(WRONGPASS, c.call("AUTH", "wrong"));
            assertEquals(WRONGPASS, c.call("AUTH", "default", "wrong"));
            assertEquals(WRONGPASS, c.call("AUTH", "nobody", "secret"));
            assertEquals(new Error("ERR syntax error"), c.call("AUTH", "a", "b", "c"));
            assertEquals(NOAUTH, c.call("GET", "auth:k"));

            assertEquals(OK, c.call("AUTH", "secret"));
            assertEquals(OK, c.call("SET", "auth:k", "v"));
            assertEquals("v", c.call("GET", "auth:k"));
            assertEquals("default", c.call("ACL", "WHOAMI"));
        }
    }

    @Test
    void namedUsers() throws Exception {
        try (RawRespClient c = plain()) {
            assertEquals(WRONGPASS, c.call("AUTH", "app", "secret"));
            assertEquals(OK, c.call("AUTH", "app", "apppw"));
            assertEquals("app", c.call("ACL", "WHOAMI"));
            String line = (String) c.call("CLIENT", "INFO");
            assertTrue(line.contains(" user=app "), line);
            assertEquals(WRONGPASS, c.call("AUTH", "default", "wrong"));
            assertEquals("app", c.call("ACL", "WHOAMI"), "a failed AUTH keeps the current user");
        }
    }

    @Test
    void helloAuthenticates() throws Exception {
        String helloNoauth = "NOAUTH HELLO must be called with the client already authenticated, otherwise the "
                + "HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client and select "
                + "the RESP protocol version at the same time";
        try (RawRespClient c = plain()) {
            assertEquals(new Error(helloNoauth), c.call("HELLO"));
            assertEquals(new Error(helloNoauth), c.call("HELLO", "2"));
            assertEquals(new Error("NOPROTO unsupported protocol version"), c.call("HELLO", "4"));
            assertEquals(new Error("ERR Protocol version is not an integer or out of range"), c.call("HELLO", "x"));
            assertEquals(new Error("ERR Syntax error in HELLO option 'AUTH'"), c.call("HELLO", "2", "AUTH", "default"));
            assertEquals(new Error("ERR Syntax error in HELLO option 'FOO'"), c.call("HELLO", "2", "FOO"));
            assertEquals(WRONGPASS, c.call("HELLO", "2", "AUTH", "app", "wrong"));
            assertEquals(NOAUTH, c.call("PING"));

            List<?> hello = (List<?>) c.call("HELLO", "2", "AUTH", "app", "apppw", "SETNAME", "via-hello");
            assertEquals("valkeyway", hello.get(1));
            assertEquals("via-hello", c.call("CLIENT", "GETNAME"));
            assertEquals("app", c.call("ACL", "WHOAMI"));
            assertEquals(new Error("ERR Client names cannot contain spaces, newlines or special characters."),
                    c.call("HELLO", "2", "SETNAME", "a b"));
        }
    }

    @Test
    void resetLogsOut() throws Exception {
        try (RawRespClient c = plain()) {
            assertEquals(OK, c.call("AUTH", "app", "apppw"));
            assertEquals(new Status("RESET"), c.call("RESET"));
            assertEquals(NOAUTH, c.call("PING"));
            assertEquals(OK, c.call("QUIT"), "QUIT works unauthenticated");
        }
    }

    @Test
    void clientKillByUser() throws Exception {
        try (RawRespClient admin = plain(); RawRespClient victim = plain()) {
            admin.call("AUTH", "secret");
            victim.call("AUTH", "app", "apppw");
            victim.call("CLIENT", "SETNAME", "kill-by-user");
            assertTrue(((String) admin.call("CLIENT", "LIST")).contains("name=kill-by-user"));
            assertEquals(1L, admin.call("CLIENT", "KILL", "USER", "app"));
            assertThrows(IOException.class, () -> victim.call("PING"));
            assertEquals(PONG, admin.call("PING"));
        }
    }

    @Test
    void unauthenticatedClientsCantSendLargeRequests() throws Exception {
        String[] eleven = new String[11];
        Arrays.fill(eleven, "x");
        try (RawRespClient c = plain()) {
            assertEquals(new Error("ERR Protocol error: unauthenticated multibulk length"), c.call(eleven));
            assertThrows(IOException.class, () -> c.call("PING"));
        }
        try (RawRespClient c = plain()) {
            c.call("AUTH", "secret");
            eleven[0] = "DEL";
            assertEquals(0L, c.call(eleven));
        }
    }

    @Test
    void tlsPort() throws Exception {
        try (RawRespClient c = tls(false)) {
            assertEquals(NOAUTH, c.call("PING"));
            assertEquals(OK, c.call("AUTH", "secret"));
            assertEquals(OK, c.call("SET", "tls:k", "over-tls"));
            assertEquals("over-tls", c.call("GET", "tls:k"));
        }
        try (RawRespClient c = tls(true)) {
            assertEquals(OK, c.call("AUTH", "app", "apppw"));
            assertEquals("over-tls", c.call("GET", "tls:k"), "a client certificate is optional here, and accepted");
        }
    }

    /** A real client's handshake: Glide authenticates during connection setup (over TLS here). */
    @Test
    void glideClientWithCredentialsOverTls() throws Exception {
        GlideClientConfiguration config = GlideClientConfiguration.builder()
                .address(NodeAddress.builder().host("127.0.0.1").port(TLS_PORT).build())
                .useTLS(true)
                .credentials(ServerCredentials.builder().username("app").password("apppw").build())
                .advancedConfiguration(AdvancedGlideClientConfiguration.builder()
                        .tlsAdvancedConfiguration(TlsAdvancedConfiguration.builder()
                                .rootCertificates(Files.readAllBytes(certs.caCert())).build())
                        .build())
                .requestTimeout(5000)
                .build();
        try (GlideClient client = GlideClient.createClient(config).get(10, TimeUnit.SECONDS)) {
            assertEquals("OK", client.set("glide:tls", "yes").get(5, TimeUnit.SECONDS));
            assertEquals("yes", client.get("glide:tls").get(5, TimeUnit.SECONDS));
            assertEquals("app", client.customCommand(new String[]{"ACL", "WHOAMI"}).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void glideClientWithAWrongPasswordFails() {
        GlideClientConfiguration config = GlideClientConfiguration.builder()
                .address(NodeAddress.builder().host("127.0.0.1").port(PORT).build())
                .credentials(ServerCredentials.builder().password("wrong").build())
                .requestTimeout(2000)
                .build();
        assertThrows(Exception.class, () -> GlideClient.createClient(config).get(10, TimeUnit.SECONDS).close());
    }

    @Test
    void configShowsWhetherPasswordsAreSetButNotThem() throws Exception {
        try (RawRespClient c = plain()) {
            c.call("AUTH", "secret");
            List<?> config = (List<?>) c.call("PROXY", "CONFIG", "GET", "*");
            assertFalse(config.contains("secret"), config.toString());
            assertFalse(config.contains("apppw"), config.toString());
            assertEquals(List.of("auth.password", "(set)"), c.call("PROXY", "CONFIG", "GET", "auth.password"));
            assertEquals(List.of("auth.users", "app"), c.call("PROXY", "CONFIG", "GET", "auth.users"));
            assertEquals(List.of("tls.port", Integer.toString(TLS_PORT)), c.call("PROXY", "CONFIG", "GET", "tls.port"));
        }
    }
}
