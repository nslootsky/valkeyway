/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.server;

import io.github.nslootsky.valkeyway.integration.RawRespClient;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Error;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import io.github.nslootsky.valkeyway.protocol.Reply;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The TLS listener and the NOAUTH gate, on the server alone (no cluster needed). */
class TlsAndAuthServerTest {

    private static final TestCertificates certs = TestCertificates.get();
    private static final Status OK = new Status("OK");
    private static final Status PONG = new Status("PONG");
    private static final Error NOAUTH = new Error("NOAUTH Authentication required.");

    private RespServer server;

    private static CommandTable table() {
        return new CommandTable(r -> Reply.integer(r.argCount()))
                .register("ping", _ -> Reply.PONG)
                .register("auth", new InlineCommandHandler() {
                    @Override
                    public Reply execute(Request r) {
                        if (!r.argString(0).equals("secret")) {
                            return Reply.error("WRONGPASS");
                        }
                        r.connection().user(Users.DEFAULT);
                        return Reply.OK;
                    }

                    @Override
                    public boolean allowedBeforeAuth() {
                        return true;
                    }
                });
    }

    private void start(TlsContext.ClientCerts clientCerts, boolean authRequired) throws Exception {
        TlsContext tls = new TlsContext(new TlsContext.Settings(certs.serverCert(), certs.serverKey(), null,
                certs.caCert(), clientCerts, List.of()));
        server = new RespServer(new RespServer.Settings("127.0.0.1", 0, 0, tls, 2, 100, 0, authRequired), table());
        server.start();
    }

    private RawRespClient tlsClient(boolean withCertificate) throws IOException {
        var factory = withCertificate ? certs.clientSocketsWithCertificate() : certs.clientSockets();
        SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", server.tlsPort());
        socket.startHandshake();
        return new RawRespClient(socket);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void servesTlsAndPlainTextSideBySide() throws Exception {
        start(TlsContext.ClientCerts.NONE, false);
        try (RawRespClient tls = tlsClient(false); RawRespClient plain = new RawRespClient("127.0.0.1", server.port())) {
            assertEquals(PONG, tls.call("PING"));
            assertEquals(3L, tls.call("ANY", "a", "b", "c"));
            assertEquals(PONG, plain.call("PING"));
        }
    }

    @Test
    void plainTextOnTheTlsPortGetsNoReply() throws Exception {
        start(TlsContext.ClientCerts.NONE, false);
        try (RawRespClient c = new RawRespClient(new Socket("127.0.0.1", server.tlsPort()))) {
            assertThrows(IOException.class, () -> c.call("PING"));
        }
    }

    @Test
    void requiredClientCertificatesAreEnforced() throws Exception {
        start(TlsContext.ClientCerts.REQUIRED, false);
        assertThrows(IOException.class, () -> {
            try (RawRespClient c = tlsClient(false)) {
                c.call("PING"); // TLS 1.3 reports the missing certificate after the handshake
            }
        });
        try (RawRespClient c = tlsClient(true)) {
            assertEquals(PONG, c.call("PING"));
        }
    }

    @Test
    void optionalClientCertificatesAcceptBoth() throws Exception {
        start(TlsContext.ClientCerts.OPTIONAL, false);
        try (RawRespClient without = tlsClient(false); RawRespClient with = tlsClient(true)) {
            assertEquals(PONG, without.call("PING"));
            assertEquals(PONG, with.call("PING"));
        }
    }

    @Test
    void commandsBeforeAuthGetNoauth() throws Exception {
        start(TlsContext.ClientCerts.NONE, true);
        try (RawRespClient c = new RawRespClient("127.0.0.1", server.port())) {
            assertEquals(NOAUTH, c.call("PING"));
            assertEquals(NOAUTH, c.call("GET", "k"));
            assertEquals(new Error("WRONGPASS"), c.call("AUTH", "wrong"));
            assertEquals(NOAUTH, c.call("PING"));
            assertEquals(OK, c.call("AUTH", "secret"));
            assertEquals(PONG, c.call("PING"));
        }
    }

    @Test
    void commandsPipelinedBehindAuthRunAsTheNewUser() throws Exception {
        start(TlsContext.ClientCerts.NONE, true);
        try (RawRespClient c = tlsClient(false)) {
            assertEquals(List.of(NOAUTH, OK, PONG, 1L), c.pipeline(List.of(
                    new String[]{"PING"}, new String[]{"AUTH", "secret"}, new String[]{"PING"}, new String[]{"X", "y"})));
        }
    }

    @Test
    void unauthenticatedClientsCantSendLargeRequests() throws Exception {
        start(TlsContext.ClientCerts.NONE, true);
        String[] manyArgs = new String[11];
        java.util.Arrays.fill(manyArgs, "a");
        try (RawRespClient c = new RawRespClient("127.0.0.1", server.port())) {
            assertEquals(new Error("ERR Protocol error: unauthenticated multibulk length"), c.call(manyArgs));
            assertThrows(IOException.class, () -> c.call("PING"), "the connection is closed");
        }
        try (RawRespClient c = new RawRespClient("127.0.0.1", server.port())) {
            assertEquals(new Error("ERR Protocol error: unauthenticated bulk length"), c.call("AUTH", "x".repeat(16 * 1024 + 1)));
        }
        try (RawRespClient c = new RawRespClient("127.0.0.1", server.port())) {
            assertEquals(OK, c.call("AUTH", "secret"));
            assertEquals(10L, c.call(manyArgs), "limits are lifted once authenticated");
            assertEquals(1L, c.call("X", "x".repeat(100_000)));
        }
    }

    @Test
    void certificateFilesAreReloadedWhenTheyChange(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path cert = Files.copy(certs.serverCert(), dir.resolve("server.crt"));
        Path key = Files.copy(certs.serverKey(), dir.resolve("server.key"));
        TlsContext tls = new TlsContext(new TlsContext.Settings(cert, key, null, null, null, null));
        SslContext first = tls.current();

        tls.reloadIfChanged();
        assertSame(first, tls.current(), "unchanged files aren't reloaded");

        Files.writeString(cert, "not a certificate");
        Files.setLastModifiedTime(cert, FileTime.from(Instant.now().plusSeconds(5)));
        tls.reloadIfChanged();
        assertSame(first, tls.current(), "a bad file keeps the current certificate");

        Files.copy(certs.clientCert(), cert, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(certs.clientKey(), key, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(cert, FileTime.from(Instant.now().plusSeconds(10)));
        tls.reloadIfChanged();
        assertNotSame(first, tls.current(), "a rotated certificate is picked up");
    }
}
