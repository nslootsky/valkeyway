/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import io.github.nslootsky.valkeyway.blocking.BlockingClientPool;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.ValkeywayCommands;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import io.github.nslootsky.valkeyway.scan.ScanCursorStore;
import io.github.nslootsky.valkeyway.server.RespServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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

    @Bean(destroyMethod = "close")
    RespServer respServer(ValkeywayProperties props, GlideClientCache glideClientCache, ScanCursorStore scanCursorStore,
                          MetricsCollector metrics, BlockingClientPool blockingPool) throws InterruptedException {
        ValkeywayProperties.Clients clients = props.clients();
        RespServer server = new RespServer(
                new RespServer.Settings(props.host(), props.port(), clients.ioThreads(), clients.maxClients(),
                        clients.timeoutSeconds()),
                ValkeywayCommands.create(glideClientCache, scanCursorStore, metrics, blockingPool));
        server.start();
        log.info("Proxy ready");
        return server;
    }
}
