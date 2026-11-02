/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import io.github.nslootsky.valkeyway.server.TlsContext;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ValkeywayPropertiesTest {

    private static ValkeywayProperties bind(Map<String, String> extra) {
        Map<String, String> map = new HashMap<>(Map.of(
                "proxy.port", "6379",
                "proxy.cluster-nodes[0]", "127.0.0.1:7000"));
        map.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(map)).bind("proxy", ValkeywayProperties.class).get();
    }

    @Test
    void replicaReadsAreOffByDefault() {
        ValkeywayProperties.ReplicaReads reads = bind(Map.of()).replicaReads();
        assertEquals(ValkeywayProperties.ReplicaConnections.NONE, reads.connections());
        assertEquals(ValkeywayProperties.ReplicaReadFrom.PREFER_REPLICA, reads.readFrom());
    }

    @Test
    void replicaReadSettingsBind() {
        ValkeywayProperties.ReplicaReads reads = bind(Map.of("proxy.replica-reads.connections", "readonly",
                "proxy.replica-reads.read-from", "az-affinity", "proxy.replica-reads.client-az", "us-east-1a")).replicaReads();
        assertEquals(ValkeywayProperties.ReplicaConnections.READONLY, reads.connections());
        assertEquals(ValkeywayProperties.ReplicaReadFrom.AZ_AFFINITY, reads.readFrom());
        assertEquals("us-east-1a", reads.clientAz());
    }

    @Test
    void azAffinityNeedsTheProxysZone() {
        Exception e = assertThrows(Exception.class, () -> bind(Map.of("proxy.replica-reads.connections", "all",
                "proxy.replica-reads.read-from", "az-affinity")));
        assertTrue(rootMessage(e).contains("proxy.replica-reads.client-az"), rootMessage(e));
        // Not needed while no connection reads from replicas.
        assertDoesNotThrow(() -> bind(Map.of("proxy.replica-reads.read-from", "az-affinity")));
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        return String.valueOf(e.getMessage());
    }

    @Test
    void requestTimeoutDefaultsTo2000() {
        assertEquals(2000, bind(Map.of()).requestTimeoutMs());
    }

    @Test
    void requestTimeoutIsUsedAsSet() {
        assertEquals(3500, bind(Map.of("proxy.request-timeout-ms", "3500")).requestTimeoutMs());
    }

    @Test
    void oldConnectTimeoutNameStillWorks() {
        ValkeywayProperties props = bind(Map.of("proxy.connect-timeout-ms", "1500"));
        assertEquals(1500, props.requestTimeoutMs());
    }

    @Test
    void newNameWinsOverOldName() {
        ValkeywayProperties props = bind(Map.of("proxy.connect-timeout-ms", "1500", "proxy.request-timeout-ms", "4000"));
        assertEquals(4000, props.requestTimeoutMs());
    }

    /** The environment variable names docs/AUTH_AND_TLS.md gives. */
    @Test
    void securitySettingsBindFromEnvironmentVariables() {
        Map<String, Object> env = Map.of(
                "PROXY_PORT", "0",
                "PROXY_CLUSTERNODES", "127.0.0.1:7000",
                "PROXY_AUTH_PASSWORD", "secret",
                "PROXY_AUTH_USERS_APP", "apppw",
                "PROXY_TLS_PORT", "6379",
                "PROXY_TLS_CERTFILE", "/tls/tls.crt",
                "PROXY_TLS_KEYFILE", "/tls/tls.key",
                "PROXY_TLS_CLIENTCERTS", "required",
                "PROXY_TLS_CAFILE", "/tls/ca.crt",
                "PROXY_BACKEND_TLS_ENABLED", "true");
        Map<String, Object> more = new HashMap<>(env);
        more.put("PROXY_BACKEND_USERNAME", "proxy");
        more.put("PROXY_BACKEND_PASSWORD", "backend-pw");
        more.put("PROXY_BACKEND_TLS_INSECURE", "true");
        var source = new SystemEnvironmentPropertySource("systemEnvironment", more);
        ValkeywayProperties props = new Binder(ConfigurationPropertySources.from(source))
                .bind("proxy", ValkeywayProperties.class).get();
        assertEquals(0, props.port());
        assertEquals("secret", props.auth().password());
        assertEquals(Map.of("app", "apppw"), props.auth().users());
        assertEquals(6379, props.tls().port());
        assertEquals("/tls/tls.crt", props.tls().certFile());
        assertEquals("/tls/tls.key", props.tls().keyFile());
        assertEquals("/tls/ca.crt", props.tls().caFile());
        assertEquals(TlsContext.ClientCerts.REQUIRED, props.tls().clientCerts());
        assertEquals("proxy", props.backend().username());
        assertEquals("backend-pw", props.backend().password());
        assertTrue(props.backend().tls().enabled());
        assertTrue(props.backend().tls().insecure());
    }

    /** {@code ${VALKEY_PASSWORD:}} with the variable unset is an empty string: no password, not an empty one. */
    @Test
    void emptySecretsMeanNone() {
        ValkeywayProperties props = bind(Map.of("proxy.auth.password", "", "proxy.backend.password", "",
                "proxy.backend.username", "", "proxy.backend.tls.ca-file", ""));
        assertNull(props.backend().password());
        assertNull(props.backend().username());
        assertNull(props.backend().tls().caFile());
        assertFalse(new io.github.nslootsky.valkeyway.server.Users(props.auth().password(), props.auth().users()).authRequired());
    }

    @Test
    void secretsAreNotInToString() {
        ValkeywayProperties props = bind(Map.of("proxy.auth.password", "hunter2", "proxy.auth.users.app", "apppw",
                "proxy.backend.password", "backendpw", "proxy.tls.key-password", "keypw"));
        String text = props.toString();
        for (String secret : new String[]{"hunter2", "apppw", "backendpw", "keypw"}) {
            assertFalse(text.contains(secret), secret + " in " + text);
        }
        assertTrue(text.contains("users=[app]"), text);
    }

    @Test
    void requestTimeoutHasAFloor() {
        assertEquals(500, bind(Map.of("proxy.request-timeout-ms", "10")).requestTimeoutMs());
    }
}
