/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway;

import io.github.nslootsky.valkeyway.server.TlsContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the proxy.
 * Prefix: proxy.*
 *
 * @param port plain-text port for clients; 0 = none (then {@code tls.port} must be set), like Valkey
 * @param requestTimeoutMs timeout per backend request, including Glide's retries (default 2000, at
 *        least 500)
 * @param connectTimeoutMs the old name of {@code request-timeout-ms}, still accepted; reads back as
 *        the effective request timeout
 */
@SuppressWarnings("deprecation") // connectTimeoutMs, read only to honor the old name
@ConfigurationProperties(prefix = "proxy")
public record ValkeywayProperties(
    String host,
    int port,
    List<String> clusterNodes,
    long requestTimeoutMs,
    @Deprecated long connectTimeoutMs,
    BlockingPool blockingPool,
    Clients clients,
    Glide glide,
    Transactions transactions,
    NodeAdmin nodeAdminCommands,
    Auth auth,
    Tls tls,
    Backend backend
) {
    public ValkeywayProperties {
        if (host == null || host.isBlank()) host = "0.0.0.0";
        if (auth == null) auth = new Auth(null, null, null);
        if (tls == null) tls = new Tls(0, null, null, null, null, null, null);
        if (backend == null) backend = new Backend(null, null, null);
        if (port < 0 || port > 65535 || (port == 0 && tls.port() == 0)) {
            throw new IllegalArgumentException("Invalid port (0 is allowed only with a proxy.tls.port)");
        }
        if (clusterNodes == null || clusterNodes.isEmpty()) throw new IllegalArgumentException("clusterNodes required");
        if (requestTimeoutMs <= 0 && connectTimeoutMs > 0) {
            LoggerFactory.getLogger(ValkeywayProperties.class).warn(
                    "proxy.connect-timeout-ms is deprecated; use proxy.request-timeout-ms (it is a timeout per request, not per connection)");
            requestTimeoutMs = connectTimeoutMs;
        }
        requestTimeoutMs = requestTimeoutMs <= 0 ? 2000 : Math.max(requestTimeoutMs, 500);
        connectTimeoutMs = requestTimeoutMs;
        if (blockingPool == null) blockingPool = new BlockingPool(0, 0);
        if (clients == null) clients = new Clients(0, 0, 0);
        if (glide == null) glide = new Glide(0, null, null, 0);
        if (transactions == null) transactions = new Transactions(null, null);
        if (nodeAdminCommands == null) nodeAdminCommands = NodeAdmin.REJECT;
    }

    /**
     * Backend (Glide) client settings. Prefix: proxy.glide.*
     *
     * @param inflightRequestsLimit requests one Glide client may have outstanding (default 50000).
     *        Glide's own default is 1000, and one Glide client per database is shared by every proxy
     *        connection, so with the default more than 1000 busy clients get "Client reached maximum
     *        inflight requests" errors. Memory stays bounded by the proxy's per-connection backpressure.
     * @param coalesceRequests send single commands that arrive from different connections in the same
     *        network-thread iteration to Glide as one batch (default true). Glide's per-request cost is
     *        much higher than its per-command cost inside a batch.
     * @param refreshTopologyFromSeeds discover topology changes by asking the configured
     *        {@code cluster-nodes}, resolved again each time, rather than the nodes already known
     *        (default true). With a DNS name as the seed (e.g. a configuration endpoint), re-pointing
     *        the name at another cluster moves the proxy there at the next topology check. If the seeds
     *        can't be reached, Glide falls back to the nodes it knows.
     * @param topologyCheckSeconds how often Glide checks for topology changes, which is also how
     *        quickly a re-pointed seed name is followed; 0 = Glide's default (60).
     */
    // [upstream:G8] [upstream:G9]
    public record Glide(int inflightRequestsLimit, Boolean coalesceRequests, Boolean refreshTopologyFromSeeds,
                        int topologyCheckSeconds) {
        public Glide {
            if (inflightRequestsLimit <= 0) inflightRequestsLimit = 50_000;
            if (coalesceRequests == null) coalesceRequests = true;
            if (refreshTopologyFromSeeds == null) refreshTopologyFromSeeds = true;
            if (topologyCheckSeconds < 0) topologyCheckSeconds = 0;
        }
    }

    /**
     * Client connection handling. Prefix: proxy.clients.*
     *
     * @param maxClients     connections accepted at once; more get an error (default 10000, like Valkey)
     * @param timeoutSeconds close a client idle this long; 0 = never (default, like Valkey's timeout 0)
     * @param ioThreads      network event-loop threads (default 2). Fewer threads put more requests
     *                       into each backend batch, which matters more than parallelism here: on a
     *                       24-core machine, 2 threads served 250-280k unpipelined requests/s from 200
     *                       clients versus 160-176k with 24. One thread was faster still for small
     *                       values but ran at ~90% CPU with 10 KB values; raise it for large values.
     */
    public record Clients(int maxClients, long timeoutSeconds, int ioThreads) {
        public Clients {
            if (maxClients <= 0) maxClients = 10_000;
            if (timeoutSeconds < 0) timeoutSeconds = 0;
            if (ioThreads <= 0) ioThreads = Math.min(2, Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * Dedicated backend clients for blocking commands (BLPOP, XREAD BLOCK, WAIT, ...).
     * Prefix: proxy.blocking-pool.*
     *
     * @param maxSize            most blocking commands running at once; further ones wait (default 64)
     * @param idleTimeoutSeconds close a pooled client after this long unused (default 60)
     */
    public record BlockingPool(int maxSize, long idleTimeoutSeconds) {
        public BlockingPool {
            if (maxSize <= 0) maxSize = 64;
            if (idleTimeoutSeconds <= 0) idleTimeoutSeconds = 60;
        }
    }

    /**
     * MULTI/EXEC and WATCH. Prefix: proxy.transactions.*
     *
     * @param crossSlot what EXEC does when the transaction's keys span hash slots, which a cluster
     *        can't run atomically: {@code reject} (default) answers CROSSSLOT, as a cluster node does;
     *        {@code pipeline} runs the commands in order without atomicity. With WATCH, a cross-slot
     *        transaction is always rejected.
     * @param watchPool dedicated backend clients for connections that use WATCH, held from WATCH
     *        until EXEC, DISCARD, UNWATCH or disconnect (watch state belongs to a connection)
     */
    public record Transactions(CrossSlot crossSlot, WatchPool watchPool) {
        public Transactions {
            if (crossSlot == null) crossSlot = CrossSlot.REJECT;
            if (watchPool == null) watchPool = new WatchPool(0, 0, 0);
        }
    }

    public enum CrossSlot { REJECT, PIPELINE }

    /**
     * @param maxSize            connections that can have keys watched at once; more wait (default 64)
     * @param idleTimeoutSeconds close a pooled client after this long unused (default 60)
     * @param acquireTimeoutMs   how long WATCH waits for a free client before failing (default 2000)
     */
    public record WatchPool(int maxSize, long idleTimeoutSeconds, long acquireTimeoutMs) {
        public WatchPool {
            if (maxSize <= 0) maxSize = 64;
            if (idleTimeoutSeconds <= 0) idleTimeoutSeconds = 60;
            if (acquireTimeoutMs <= 0) acquireTimeoutMs = 2000;
        }
    }

    /**
     * Client authentication. Prefix: proxy.auth.*
     *
     * @param password the default user's password, like Valkey's {@code requirepass}: once set,
     *        clients must AUTH (or HELLO ... AUTH) before anything else. Unset = no authentication.
     * @param users    further users and their passwords, for {@code AUTH <user> <password>}. Every
     *        user can run every command; there are no ACL rules.
     * @param timeoutSeconds with a password, close connections that haven't authenticated after this
     *        long (default 10); 0 = never
     */
    public record Auth(String password, Map<String, String> users, Long timeoutSeconds) {
        public Auth {
            if (users == null) users = Map.of();
            if (timeoutSeconds == null) timeoutSeconds = 10L;
            if (timeoutSeconds < 0) throw new IllegalArgumentException("proxy.auth.timeout-seconds can't be negative");
        }

        /** Without the passwords, should this ever be logged. */
        @Override
        public String toString() {
            return "Auth[password=" + mask(password) + ", users=" + users.keySet() + "]";
        }
    }

    private static String mask(String secret) {
        return secret == null || secret.isEmpty() ? "(none)" : "(set)";
    }

    /**
     * TLS for clients. Prefix: proxy.tls.*
     *
     * @param port        TLS port, alongside {@code proxy.port}; 0 = no TLS (default)
     * @param certFile    the server certificate chain (PEM)
     * @param keyFile     its private key (PEM, PKCS#8)
     * @param keyPassword the key's password, if it is encrypted
     * @param caFile      CA certificates for verifying client certificates (PEM)
     * @param clientCerts {@code none} (default), {@code optional} or {@code required}: whether clients
     *        must present a certificate signed by {@code ca-file} (Valkey's {@code tls-auth-clients})
     * @param protocols   TLS versions to accept, e.g. {@code TLSv1.2,TLSv1.3}; default the JDK's
     */
    public record Tls(int port, String certFile, String keyFile, String keyPassword, String caFile,
                      TlsContext.ClientCerts clientCerts, List<String> protocols) {
        public Tls {
            if (keyPassword != null && keyPassword.isEmpty()) keyPassword = null;
            if (caFile != null && caFile.isEmpty()) caFile = null;
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid proxy.tls.port");
            if (port > 0 && (certFile == null || keyFile == null)) {
                throw new IllegalArgumentException("proxy.tls.port needs proxy.tls.cert-file and proxy.tls.key-file");
            }
            if (clientCerts == null) clientCerts = TlsContext.ClientCerts.NONE;
            if (protocols == null) protocols = List.of();
        }

        @Override
        public String toString() {
            return "Tls[port=" + port + ", certFile=" + certFile + ", keyFile=" + keyFile + ", keyPassword="
                    + mask(keyPassword) + ", caFile=" + caFile + ", clientCerts=" + clientCerts + ", protocols=" + protocols + "]";
        }
    }

    /**
     * How the proxy connects to the cluster. Prefix: proxy.backend.*
     *
     * @param username user for the cluster's AUTH; unset or empty = {@code default}
     * @param password password for the cluster's AUTH; unset or empty = no AUTH (so
     *        {@code ${VALKEY_PASSWORD:}} with the variable unset means none)
     */
    public record Backend(String username, String password, BackendTls tls) {
        public Backend {
            if (tls == null) tls = new BackendTls(false, null, false);
            if (username != null && username.isEmpty()) username = null;
            if (password != null && password.isEmpty()) password = null;
            if (username != null && password == null) {
                throw new IllegalArgumentException("proxy.backend.username needs proxy.backend.password");
            }
        }

        @Override
        public String toString() {
            return "Backend[username=" + username + ", password=" + mask(password) + ", tls=" + tls + "]";
        }
    }

    /**
     * TLS to the cluster. Prefix: proxy.backend.tls.*
     *
     * @param enabled  connect to the nodes over TLS
     * @param caFile   CA certificates to trust (PEM); unset = the system's trust store
     * @param insecure skip certificate verification (self-signed certificates, or names that don't
     *                 match the nodes' addresses)
     */
    public record BackendTls(boolean enabled, String caFile, boolean insecure) {
        public BackendTls {
            if (caFile != null && caFile.isEmpty()) caFile = null;
        }
    }

    /**
     * {@code proxy.node-admin-commands}: commands that administer a single node (SHUTDOWN, REPLICAOF,
     * FAILOVER, DEBUG, SAVE, CONFIG SET, ACL SETUSER, CLUSTER MEET, ...). Through the proxy they reach
     * whichever node Glide picks. {@code reject} (default) refuses them; {@code forward} sends them on.
     */
    public enum NodeAdmin { REJECT, FORWARD }
}
