/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import io.github.nslootsky.valkeyway.transaction.WatchClientPool;
import io.github.nslootsky.valkeyway.blocking.BlockingClientPool;
import io.github.nslootsky.valkeyway.backend.BackendClients;
import io.github.nslootsky.valkeyway.commands.ValkeywayCommands;
import io.github.nslootsky.valkeyway.ops.MetricsCollector;
import io.github.nslootsky.valkeyway.commands.ScanCursorStore;
import io.github.nslootsky.valkeyway.pubsub.PubSubHub;
import io.github.nslootsky.valkeyway.server.RespServer;
import io.github.nslootsky.valkeyway.server.TlsContext;
import io.github.nslootsky.valkeyway.server.Users;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Spring Boot application that runs a RESP proxy in front of a Valkey cluster.
 * Exposes the cluster as a single-connection endpoint for non-cluster-aware clients.
 */
@SpringBootApplication
@EnableConfigurationProperties(ValkeywayProperties.class)
public class ValkeywayApplication {

    private static final Logger log = LoggerFactory.getLogger(ValkeywayApplication.class);

    static void main(String[] args) {
        SpringApplication.run(ValkeywayApplication.class, args);
    }

    @Bean
    ScanCursorStore scanCursorStore() {
        return new ScanCursorStore();
    }

    @Bean
    Users users(ValkeywayProperties props) {
        Users users = new Users(props.auth().password(), props.auth().users());
        if (users.authRequired()) {
            log.info("Client authentication is on (users: {})", users.names());
        } else if (isLoopback(props.host())) {
            log.info("Client authentication is off (proxy.auth.password isn't set)");
        } else {
            log.warn("Client authentication is off and the proxy listens on {}: any client that can reach it can run "
                    + "any command. Set proxy.auth.password (see docs/SECURITY.md).", props.host());
        }
        return users;
    }

    /** Settings that would silently have no effect, or leave a way around what they set up. */
    private static void warnAboutUnusedTlsSettings(ValkeywayProperties props) {
        ValkeywayProperties.Tls tls = props.tls();
        if (tls.port() == 0 && (tls.certFile() != null || tls.keyFile() != null || tls.caFile() != null
                || tls.clientCerts() != TlsContext.ClientCerts.NONE)) {
            log.warn("proxy.tls.* settings have no effect without proxy.tls.port");
        }
        if (tls.port() != 0 && tls.clientCerts() == TlsContext.ClientCerts.REQUIRED && props.port() != 0) {
            log.warn("Client certificates are required on the TLS port {}, but the plain-text port {} is open too "
                    + "(set proxy.port: 0 to close it)", tls.port(), props.port());
        }
        ValkeywayProperties.Backend backend = props.backend();
        if (!backend.tls().enabled() && (backend.tls().caFile() != null || backend.tls().insecure())) {
            log.warn("proxy.backend.tls.ca-file and insecure have no effect without proxy.backend.tls.enabled");
        }
        if (!backend.tls().enabled() && backend.password() != null) {
            log.info("The cluster's password is sent without TLS (proxy.backend.tls.enabled is false)");
        }
    }

    private static boolean isLoopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    @Bean(destroyMethod = "close")
    PubSubHub pubSubHub(BackendClients backendClients, ValkeywayProperties props) {
        return new PubSubHub(backendClients, (int) props.requestTimeoutMs());
    }

    @Bean(destroyMethod = "close")
    RespServer respServer(ValkeywayProperties props, Users users, PubSubHub pubSub, BackendClients backendClients,
                          ScanCursorStore scanCursorStore, MetricsCollector metrics, BlockingClientPool blockingPool,
                          WatchClientPool watchPool) throws InterruptedException, IOException {
        ValkeywayProperties.Clients clients = props.clients();
        ValkeywayProperties.Tls tls = props.tls();
        TlsContext tlsContext = tls.port() == 0 ? null : new TlsContext(new TlsContext.Settings(
                Path.of(tls.certFile()), Path.of(tls.keyFile()), tls.keyPassword(),
                tls.caFile() == null ? null : Path.of(tls.caFile()), tls.clientCerts(), tls.protocols()));
        warnAboutUnusedTlsSettings(props);
        RespServer server = new RespServer(
                new RespServer.Settings(props.host(), props.port() == 0 ? -1 : props.port(),
                        tls.port() == 0 ? -1 : tls.port(), tlsContext, clients.ioThreads(), clients.maxClients(),
                        clients.timeoutSeconds(), users.authRequired(), props.auth().timeoutSeconds()),
                ValkeywayCommands.create(backendClients, scanCursorStore, metrics, blockingPool, watchPool, users, pubSub, props));
        server.start();
        log.info("Proxy ready");
        return server;
    }
}
