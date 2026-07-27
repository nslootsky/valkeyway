/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.health;

import glide.api.GlideClusterClient;
import io.github.nslootsky.valkeyway.cache.GlideClientCache;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring Boot health indicator that pings the Valkey cluster.
 * Reports UP if reachable, DOWN with error details otherwise.
 */
@Component
public class ClusterHealthIndicator implements HealthIndicator {

    private final GlideClientCache glideClientCache;

    public ClusterHealthIndicator(GlideClientCache glideClientCache) {
        this.glideClientCache = glideClientCache;
    }

    @Override
    public Health health() {
        try {
            GlideClusterClient client = glideClientCache.getClient(0);
            var future = client.ping();
            String result = future.get(2, TimeUnit.SECONDS);
            if ("PONG".equals(result)) {
                return Health.up()
                        .withDetail("cluster", "valkey-cluster")
                        .withDetail("response", result)
                        .build();
            }
            return Health.down()
                    .withDetail("error", "Unexpected ping response: " + result)
                    .build();
        } catch (TimeoutException e) {
            return Health.down()
                    .withDetail("error", "Cluster ping timed out")
                    .build();
        } catch (ExecutionException | InterruptedException e) {
            return Health.down()
                    .withDetail("error", e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                    .build();
        }
    }
}
