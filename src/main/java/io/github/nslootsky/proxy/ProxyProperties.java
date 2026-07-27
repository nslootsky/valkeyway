package io.github.nslootsky.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(
    String host,
    int port,
    List<String> clusterNodes,
    long connectTimeoutMs
) {
    public ProxyProperties {
        if (host == null || host.isBlank()) host = "0.0.0.0";
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (clusterNodes == null || clusterNodes.isEmpty()) throw new IllegalArgumentException("clusterNodes required");
        connectTimeoutMs = Math.max(connectTimeoutMs, 500);
    }
}
