/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import glide.api.models.configuration.AdvancedGlideClusterClientConfiguration;
import glide.api.models.configuration.GlideClusterClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.PeriodicChecksManualInterval;
import glide.api.models.configuration.RequestRoutingConfiguration.SimpleSingleNodeRoute;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One shared GlideClusterClient per database index, kept for the life of the proxy.
 *
 * <p>Clients are never evicted: a live client follows the cluster's topology itself (MOVED,
 * periodic checks), and with at most a few databases there is little to save.
 *
 * <p>New clients (another database's first client, blocking-pool clients) are seeded from the
 * configured {@code proxy.cluster-nodes}, passed to Glide as given so hostnames are resolved by DNS
 * each time. If none of them can be reached, for example because every node they named has since
 * been replaced, the client is created from the nodes a live client currently knows.
 *
 * <p>Relies on upstream behavior: [upstream:G8] [upstream:V3] (docs/UPSTREAM_ASSUMPTIONS.md).
 */
@Component
public class BackendClients {

    private static final Logger log = LoggerFactory.getLogger(BackendClients.class);
    private static final long CREATE_TIMEOUT_SECONDS = 10;

    private final Map<Integer, GlideClusterClient> clients = new ConcurrentHashMap<>();
    private final List<String> clusterNodes;
    private final int requestTimeout;
    private final int inflightRequestsLimit;
    private final boolean refreshTopologyFromSeeds;
    private final int topologyCheckSeconds;

    public BackendClients(ValkeywayProperties props) {
        this.clusterNodes = props.clusterNodes();
        this.requestTimeout = (int) props.connectTimeoutMs();
        this.inflightRequestsLimit = props.glide().inflightRequestsLimit();
        this.refreshTopologyFromSeeds = props.glide().refreshTopologyFromSeeds();
        this.topologyCheckSeconds = props.glide().topologyCheckSeconds();
    }

    // [upstream:G8]
    GlideClusterClientConfiguration buildConfig(int db, List<String> seeds) {
        var builder = GlideClusterClientConfiguration.builder()
                .requestTimeout(requestTimeout)
                .inflightRequestsLimit(inflightRequestsLimit)
                .databaseId(db);
        for (String node : seeds) {
            int colon = node.lastIndexOf(':');
            builder.address(NodeAddress.builder()
                    .host(node.substring(0, colon))
                    .port(Integer.parseInt(node.substring(colon + 1)))
                    .build());
        }
        var advanced = AdvancedGlideClusterClientConfiguration.builder()
                .refreshTopologyFromInitialNodes(refreshTopologyFromSeeds);
        if (topologyCheckSeconds > 0) {
            advanced.periodicChecks(PeriodicChecksManualInterval.builder().durationInSec(topologyCheckSeconds).build());
        }
        return builder.advancedConfiguration(advanced.build()).build();
    }

    /** Get or create the shared client for the given database index. */
    public GlideClusterClient getClient(int db) {
        return clients.computeIfAbsent(db, key -> {
            GlideClusterClient client = createClient(db);
            log.info("Created GlideClusterClient for db={}", db);
            return client;
        });
    }

    /** The client for {@code db} if it already exists, without creating one (never blocks). */
    public GlideClusterClient getIfPresent(int db) {
        return clients.get(db);
    }

    /**
     * Creates a new, unshared client for the given database. The caller owns it and must close it.
     */
    public GlideClusterClient createClient(int db) {
        try {
            return create(db, clusterNodes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted creating GlideClusterClient for db=" + db, e);
        } catch (Exception e) {
            List<String> live = liveNodes();
            if (live.isEmpty() || live.equals(clusterNodes)) {
                throw new RuntimeException("Failed to create GlideClusterClient for db=" + db, e);
            }
            log.warn("Can't reach the configured cluster nodes {} ({}); creating the client for db={} from the "
                    + "nodes the cluster currently has: {}. Update proxy.cluster-nodes.", clusterNodes, e.getMessage(), db, live);
            try {
                return create(db, live);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted creating GlideClusterClient for db=" + db, ie);
            } catch (Exception again) {
                again.addSuppressed(e);
                throw new RuntimeException("Failed to create GlideClusterClient for db=" + db, again);
            }
        }
    }

    private GlideClusterClient create(int db, List<String> seeds) throws Exception {
        return GlideClusterClient.createClient(buildConfig(db, seeds)).get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** host:port of every healthy node, as a live client sees the cluster now; empty if there is none. */
    List<String> liveNodes() {
        for (GlideClusterClient client : clients.values()) {
            try {
                Object nodes = client.customCommand(new GlideString[]{GlideString.of("CLUSTER"), GlideString.of("NODES")},
                        SimpleSingleNodeRoute.RANDOM).get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS).getSingleValue();
                return parseClusterNodes(nodes.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception e) {
                log.debug("CLUSTER NODES through a live client failed: {}", e.getMessage());
            }
        }
        return List.of();
    }

    /** host:port of the connected, non-failing nodes in a CLUSTER NODES reply. [upstream:V3] */
    static List<String> parseClusterNodes(String reply) {
        List<String> nodes = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String[] fields = line.trim().split(" ");
            if (fields.length < 8) {
                continue;
            }
            List<String> flags = Arrays.asList(fields[2].split(","));
            if (flags.contains("fail") || flags.contains("fail?") || flags.contains("handshake")
                    || flags.contains("noaddr") || !"connected".equals(fields[7])) {
                continue;
            }
            String address = fields[1].split("@")[0];
            if (!address.startsWith(":")) { // ":0" = address not known yet
                nodes.add(address);
            }
        }
        return nodes;
    }

    public int getActiveClients() {
        return clients.size();
    }

    @PreDestroy
    public void closeAll() {
        for (Integer db : List.copyOf(clients.keySet())) {
            GlideClusterClient client = clients.remove(db);
            if (client != null) {
                try {
                    client.close();
                    log.info("Closed GlideClusterClient for db={}", db);
                } catch (Exception e) {
                    log.error("Error closing GlideClusterClient for db={}", db, e);
                }
            }
        }
    }
}
