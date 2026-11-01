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
        return new Users(props.auth().password(), props.auth().users());
    }

    @Bean(destroyMethod = "close")
    RespServer respServer(ValkeywayProperties props, Users users, BackendClients backendClients,
                          ScanCursorStore scanCursorStore, MetricsCollector metrics, BlockingClientPool blockingPool,
                          WatchClientPool watchPool) throws InterruptedException, IOException {
        ValkeywayProperties.Clients clients = props.clients();
        ValkeywayProperties.Tls tls = props.tls();
        TlsContext tlsContext = tls.port() == 0 ? null : new TlsContext(new TlsContext.Settings(
                Path.of(tls.certFile()), Path.of(tls.keyFile()), tls.keyPassword(),
                tls.caFile() == null ? null : Path.of(tls.caFile()), tls.clientCerts(), tls.protocols()));
        RespServer server = new RespServer(
                new RespServer.Settings(props.host(), props.port() == 0 ? -1 : props.port(),
                        tls.port() == 0 ? -1 : tls.port(), tlsContext, clients.ioThreads(), clients.maxClients(),
                        clients.timeoutSeconds(), users.authRequired()),
                ValkeywayCommands.create(backendClients, scanCursorStore, metrics, blockingPool, watchPool, users, props));
        server.start();
        log.info("Proxy ready");
        return server;
    }
}
