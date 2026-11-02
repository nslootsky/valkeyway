/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.backend;

import glide.api.GlideClusterClient;
import glide.api.models.GlideString;
import glide.api.models.configuration.AdvancedGlideClusterClientConfiguration;
import glide.api.models.configuration.ClusterSubscriptionConfiguration;
import glide.api.models.configuration.GlideClusterClientConfiguration;
import glide.api.models.configuration.ReadFrom;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.PeriodicChecksManualInterval;
import glide.api.models.configuration.RequestRoutingConfiguration.SimpleSingleNodeRoute;
import glide.api.models.configuration.ServerCredentials;
import glide.api.models.configuration.TlsAdvancedConfiguration;
import io.github.nslootsky.valkeyway.ValkeywayProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    /** The same, reading as {@code proxy.read-from} says, when that reads from replicas. */
    private final Map<Integer, GlideClusterClient> replicaClients = new ConcurrentHashMap<>();
    private final ValkeywayProperties.ReadFromMode readFrom;
    private final String clientAz;
    private final List<String> clusterNodes;
    private final int requestTimeout;
    private final int inflightRequestsLimit;
    private final boolean refreshTopologyFromSeeds;
    private final int topologyCheckSeconds;
    private final ValkeywayProperties.Backend backend;
    private final byte[] rootCertificates;

    public BackendClients(ValkeywayProperties props) {
        this.backend = props.backend();
        this.rootCertificates = readCaFile(backend.tls());
        this.clusterNodes = props.clusterNodes();
        this.requestTimeout = (int) props.requestTimeoutMs();
        this.inflightRequestsLimit = props.glide().inflightRequestsLimit();
        this.refreshTopologyFromSeeds = props.glide().refreshTopologyFromSeeds();
        this.topologyCheckSeconds = props.glide().topologyCheckSeconds();
        this.readFrom = props.readFrom();
        this.clientAz = props.clientAz();
    }

    private static byte[] readCaFile(ValkeywayProperties.BackendTls tls) {
        if (tls.caFile() == null) {
            return null;
        }
        try {
            return Files.readAllBytes(Path.of(tls.caFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read proxy.backend.tls.ca-file " + tls.caFile(), e);
        }
    }

    // [upstream:G13]
    /**
     * Creates a client that receives Pub/Sub messages through {@code subscriptions}' callback, for
     * subscriptions made later. The caller owns it and must close it.
     */
    public GlideClusterClient createSubscriber(ClusterSubscriptionConfiguration subscriptions) throws Exception {
        GlideClusterClientConfiguration config = configBuilder(0, clusterNodes)
                .subscriptionConfiguration(subscriptions)
                .build();
        return GlideClusterClient.createClient(config).get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // [upstream:G8]
    GlideClusterClientConfiguration buildConfig(int db, List<String> seeds) {
        return configBuilder(db, seeds).build();
    }

    private GlideClusterClientConfiguration.GlideClusterClientConfigurationBuilder<?, ?> configBuilder(int db, List<String> seeds) {
        var builder = GlideClusterClientConfiguration.builder()
                .requestTimeout(requestTimeout)
                .inflightRequestsLimit(inflightRequestsLimit)
                .databaseId(db)
                .useTLS(backend.tls().enabled());
        if (backend.password() != null) {
            builder.credentials(ServerCredentials.builder()
                    .username(backend.username())
                    .password(backend.password())
                    .build());
        }
        for (String node : seeds) {
            int colon = node.lastIndexOf(':');
            builder.address(NodeAddress.builder()
                    .host(node.substring(0, colon))
                    .port(Integer.parseInt(node.substring(colon + 1)))
                    .build());
        }
        var advanced = AdvancedGlideClusterClientConfiguration.builder()
                .refreshTopologyFromInitialNodes(refreshTopologyFromSeeds);
        if (backend.tls().enabled()) {
            advanced.tlsAdvancedConfiguration(TlsAdvancedConfiguration.builder()
                    .useInsecureTLS(backend.tls().insecure())
                    .rootCertificates(rootCertificates)
                    .build());
        }
        if (topologyCheckSeconds > 0) {
            advanced.periodicChecks(PeriodicChecksManualInterval.builder().durationInSec(topologyCheckSeconds).build());
        }
        return builder.advancedConfiguration(advanced.build());
    }

    /** Get or create the shared client for the given database index. */
    public GlideClusterClient getClient(int db) {
        return clients.computeIfAbsent(db, _ -> {
            GlideClusterClient client = createClient(db);
            log.info("Created GlideClusterClient for db={}", db);
            return client;
        });
    }

    /** The client for {@code db} if it already exists, without creating one (never blocks). */
    public GlideClusterClient getIfPresent(int db) {
        return clients.get(db);
    }

    /** Whether reads go to replicas ({@code proxy.read-from} is not primary). */
    public boolean readsFromReplicas() {
        return readFrom.readsFromReplicas();
    }

    /** The shared client for {@code db}: the primary-reading one, or the one that reads from replicas. */
    public GlideClusterClient getClient(int db, boolean replicaReads) {
        if (!replicaReads) {
            return getClient(db);
        }
        return replicaClients.computeIfAbsent(db, _ -> {
            GlideClusterClient client = createClient(db, replicaReadFrom());
            log.info("Created GlideClusterClient for db={} reading from replicas ({})", db, replicaReadFrom());
            return client;
        });
    }

    /** {@link #getClient(int, boolean)} if it already exists, without creating one (never blocks). */
    public GlideClusterClient getIfPresent(int db, boolean replicaReads) {
        return replicaReads ? replicaClients.get(db) : clients.get(db);
    }

    /**
     * With replica reads on, creates database 0's replica-reading client in the background, so the
     * first reads don't wait for it. Glide connects to the replicas asynchronously and reads
     * from the primary until it has, which is correct, just not yet from a replica.
     */
    public void preloadReplicaClient() {
        if (!readsFromReplicas()) {
            return;
        }
        Thread.ofVirtual().name("replica-client-preload").start(() -> {
            try {
                getClient(0, true);
            } catch (RuntimeException e) {
                log.warn("Couldn't create the replica-reading client yet; it will be created on first use: {}", e.getMessage());
            }
        });
    }

    private ReadFrom replicaReadFrom() {
        return ReadFrom.valueOf(readFrom.name());
    }

    /**
     * Creates a new, unshared client for the given database. The caller owns it and must close it.
     */
    public GlideClusterClient createClient(int db) {
        return createClient(db, ReadFrom.PRIMARY);
    }

    private GlideClusterClient createClient(int db, ReadFrom readFrom) {
        try {
            return create(db, clusterNodes, readFrom);
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
                return create(db, live, readFrom);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted creating GlideClusterClient for db=" + db, ie);
            } catch (Exception again) {
                again.addSuppressed(e);
                throw new RuntimeException("Failed to create GlideClusterClient for db=" + db, again);
            }
        }
    }

    private GlideClusterClient create(int db, List<String> seeds, ReadFrom readFrom) throws Exception {
        var config = configBuilder(db, seeds).readFrom(readFrom);
        if (readFrom != ReadFrom.PRIMARY && clientAz != null) {
            config.clientAZ(clientAz);
        }
        return GlideClusterClient.createClient(config.build()).get(CREATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    @PreDestroy
    public void closeAll() {
        closeAll(replicaClients);
        closeAll(clients);
    }

    private static void closeAll(Map<Integer, GlideClusterClient> clients) {
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
