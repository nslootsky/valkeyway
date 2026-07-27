/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import glide.api.GlideClusterClient;
import glide.api.models.configuration.GlideClusterClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages GlideClusterClient instances per database index.
 * Clients are cached with 30s expiry and closed automatically on eviction.
 */
@Component
public class GlideClientCache {

    private static final Logger log = LoggerFactory.getLogger(GlideClientCache.class);

    private final Cache<Integer, GlideClusterClient> cache;
    private final List<String> clusterNodes;
    private final int requestTimeout;

    public GlideClientCache(ValkeywayProperties props) {
        this.clusterNodes = props.clusterNodes();
        this.requestTimeout = (int) props.connectTimeoutMs();

        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.SECONDS)
                .scheduler(Scheduler.systemScheduler())
                .executor(Executors.newSingleThreadExecutor())
                .removalListener((db, client, cause) -> {
                    try {
                        ((GlideClusterClient) Objects.requireNonNull(client)).close();
                        log.info("Closed GlideClusterClient for db={}", db);
                    } catch (Exception e) {
                        log.error("Error closing GlideClusterClient for db={}", db, e);
                    }
                })
                .build();
    }

    private GlideClusterClientConfiguration buildConfig(int db) {
        var builder = GlideClusterClientConfiguration.builder()
                .requestTimeout(requestTimeout)
                .databaseId(db);

        for (String node : clusterNodes) {
            String[] parts = node.split(":");
            builder.address(NodeAddress.builder()
                    .host(parts[0])
                    .port(Integer.parseInt(parts[1]))
                    .build());
        }

        return builder.build();
    }

    /**
     * Get or create a GlideClusterClient for the given database index.
     */
    public GlideClusterClient getClient(int db) {
        return cache.get(db, key -> {
            try {
                GlideClusterClient client = GlideClusterClient.createClient(buildConfig(db)).get(10, TimeUnit.SECONDS);
                log.info("Created GlideClusterClient for db={}", db);
                return client;
            } catch (Exception e) {
                throw new RuntimeException("Failed to create GlideClusterClient for db=" + db, e);
            }
        });
    }

    public int getActiveClients() {
        return (int) cache.estimatedSize();
    }

    @PreDestroy
    public void closeAll() {
        cache.invalidateAll();
        log.info("Closed all GlideClusterClient instances");
    }
}
