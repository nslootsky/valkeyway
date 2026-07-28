/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import com.github.tonivade.resp.RespServer;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import io.github.nslootsky.valkeyway.handler.ValkeywayCommandSuite;
import io.github.nslootsky.valkeyway.metrics.MetricsCollector;
import io.github.nslootsky.valkeyway.scan.ScanCursorStore;
import jakarta.annotation.PreDestroy;
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

    private RespServer server;

    static void main(String[] args) {
        SpringApplication.run(ValkeywayApplication.class, args);
    }

    @Bean
    ScanCursorStore scanCursorStore() {
        return new ScanCursorStore();
    }

    @Bean
    RespServer respServer(ValkeywayProperties props, GlideClientCache glideClientCache, ScanCursorStore scanCursorStore, MetricsCollector metrics) {
        ValkeywayCommandSuite commandSuite = new ValkeywayCommandSuite(glideClientCache, scanCursorStore, metrics);
        server = RespServer.builder()
                .host(props.host())
                .port(props.port())
                .commands(commandSuite)
                .parallelExecution()
                .build();
        log.info("Starting proxy on {}:{}", props.host(), props.port());
        server.start();
        log.info("Proxy ready");
        return server;
    }

    @PreDestroy
    void stopServer() {
        if (server != null) {
            log.info("Stopping proxy server");
            server.stop();
        }
    }
}
