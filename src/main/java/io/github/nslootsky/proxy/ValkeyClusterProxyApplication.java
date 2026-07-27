package io.github.nslootsky.proxy;

import com.github.tonivade.resp.RespServer;
import io.github.nslootsky.proxy.cache.GlideClientCache;
import io.github.nslootsky.proxy.handler.ProxyCommandSuite;
import io.github.nslootsky.proxy.metrics.MetricsCollector;
import io.github.nslootsky.proxy.scan.ScanCursorStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application that runs a RESP proxy in front of a Valkey cluster.
 * Exposes the cluster as a single-connection endpoint for non-cluster-aware clients.
 */
@SpringBootApplication
@EnableConfigurationProperties(ProxyProperties.class)
public class ValkeyClusterProxyApplication {

    private static final Logger log = LoggerFactory.getLogger(ValkeyClusterProxyApplication.class);

    private RespServer server;

    static void main(String[] args) {
        SpringApplication.run(ValkeyClusterProxyApplication.class, args);
    }

    @Bean
    ScanCursorStore scanCursorStore() {
        return new ScanCursorStore();
    }

    @Bean
    RespServer respServer(ProxyProperties props, GlideClientCache glideClientCache, ScanCursorStore scanCursorStore, MetricsCollector metrics) {
        ProxyCommandSuite commandSuite = new ProxyCommandSuite(glideClientCache, scanCursorStore, metrics);
        server = RespServer.builder()
                .host(props.host())
                .port(props.port())
                .commands(commandSuite)
                .parallelExecution()
                .build();
        return server;
    }

    @Bean
    CommandLineRunner startServer(RespServer server, ProxyProperties props) {
        return args -> {
            log.info("Starting proxy on {}:{}", props.host(), props.port());
            server.start();
        };
    }

    @PreDestroy
    void stopServer() {
        if (server != null) {
            log.info("Stopping proxy server");
            server.stop();
        }
    }
}
