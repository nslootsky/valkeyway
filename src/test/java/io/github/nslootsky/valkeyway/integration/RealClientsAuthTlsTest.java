/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import io.github.nslootsky.valkeyway.server.TestCertificates;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Jedis and Lettuce against a proxy that requires a password, over plain text and TLS. */
class RealClientsAuthTlsTest extends IntegrationTestBase {

    private static final int PORT = 6397;
    private static final int TLS_PORT = 6398;
    private static final TestCertificates certs = TestCertificates.get();

    @DynamicPropertySource
    static void authAndTls(DynamicPropertyRegistry registry) {
        registry.add("proxy.port", () -> PORT);
        registry.add("proxy.auth.password", () -> "secret");
        registry.add("proxy.auth.users.app", () -> "apppw");
        registry.add("proxy.tls.port", () -> TLS_PORT);
        registry.add("proxy.tls.cert-file", () -> certs.serverCert().toString());
        registry.add("proxy.tls.key-file", () -> certs.serverKey().toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jedisWithPasswordAndTls(boolean resp3) {
        var config = DefaultJedisClientConfig.builder()
                .protocol(resp3 ? RedisProtocol.RESP3 : RedisProtocol.RESP2)
                .user("app").password("apppw")
                .ssl(true).sslSocketFactory(certs.clientSockets())
                .socketTimeoutMillis(5000).build();
        try (Jedis j = new Jedis(new HostAndPort("127.0.0.1", TLS_PORT), config)) {
            assertEquals("OK", j.set("rca:jedis", "v"));
            assertEquals("v", j.get("rca:jedis"));
            assertEquals("app", j.aclWhoAmI());
        }
    }

    @Test
    void jedisWithTheDefaultUsersPassword() {
        var config = DefaultJedisClientConfig.builder().password("secret").build();
        try (Jedis j = new Jedis(new HostAndPort("127.0.0.1", PORT), config)) {
            assertEquals("default", j.aclWhoAmI());
        }
    }

    @Test
    void jedisWithAWrongPasswordFails() {
        var config = DefaultJedisClientConfig.builder().password("wrong").build();
        assertThrows(JedisException.class, () -> {
            try (Jedis j = new Jedis(new HostAndPort("127.0.0.1", PORT), config)) {
                j.ping();
            }
        });
    }

    /** Lettuce authenticates with HELLO 3 AUTH in RESP3, and AUTH in RESP2. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lettuceWithPasswordAndTls(boolean resp3) {
        RedisURI uri = RedisURI.Builder.redis("127.0.0.1", TLS_PORT).withSsl(true)
                .withAuthentication("app", "apppw".toCharArray()).withTimeout(Duration.ofSeconds(5)).build();
        RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .protocolVersion(resp3 ? ProtocolVersion.RESP3 : ProtocolVersion.RESP2)
                .sslOptions(SslOptions.builder().trustManager(certs.caCert().toFile()).build())
                .build());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            assertEquals("OK", conn.sync().set("rca:lettuce", "v"));
            assertEquals("v", conn.sync().get("rca:lettuce"));
            assertEquals("app", conn.sync().aclWhoami());
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }

    @Test
    void lettuceWithoutAPasswordGetsNoauth() {
        RedisClient client = RedisClient.create(RedisURI.create("redis://127.0.0.1:" + PORT));
        try {
            // Lettuce connects with HELLO, which a proxy that requires a password refuses.
            Exception e = assertThrows(Exception.class, () -> client.connect().sync().ping());
            assertTrue(e instanceof RedisConnectionException || e instanceof RedisCommandExecutionException, e.toString());
            assertTrue(String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()).contains("NOAUTH")
                    || e.toString().contains("NOAUTH"), e.toString());
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }
}
