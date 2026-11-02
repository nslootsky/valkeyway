/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import io.github.nslootsky.valkeyway.server.TestCertificates;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A Valkey cluster of {@code primaries} shards with {@code replicasPerPrimary} replicas each, plus
 * {@code spareNodes} cluster-enabled nodes that are started but not joined (for adding shards).
 */
public class ValkeyClusterTestContainer implements AutoCloseable {

    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:9.1.3-alpine");
    /** Valkey with the JSON, Bloom, search and LDAP modules. */
    private static final DockerImageName BUNDLE_IMAGE = DockerImageName.parse("valkey/valkey-bundle:9.1.3");
    private static final String MODULES_CONF = "/valkey-modules.conf";
    /**
     * Search runs in coordinator mode, the only mode in which an index covers every shard's keys and a
     * query searches them all. Its argument can't be given on the command line, where it would read as
     * a server option, hence the configuration file.
     */
    private static final String MODULES = """
            loadmodule /usr/lib/valkey/libjson.so
            loadmodule /usr/lib/valkey/libvalkey_bloom.so
            loadmodule /usr/lib/valkey/libsearch.so --use-coordinator
            """;

    private final List<GenericContainer<?>> nodes = new ArrayList<>();
    private final List<String> members = new ArrayList<>();
    private final List<String> spares = new ArrayList<>();
    private final List<String> tlsMembers = new ArrayList<>();
    private final int replicasPerPrimary;

    public ValkeyClusterTestContainer(int numNodes) {
        this(numNodes, 0, 0, 27000, 5000, "");
    }

    public ValkeyClusterTestContainer(int primaries, int replicasPerPrimary, int spareNodes, int basePort,
                                      int nodeTimeoutMs, String extraArgs) {
        this(primaries, replicasPerPrimary, spareNodes, basePort, nodeTimeoutMs, extraArgs, null);
    }

    /**
     * @param tls with certificates, each node also listens for TLS on {@link #tlsPort} (its plain-text
     *            port stays open, for setup and checks), and the cluster bus uses TLS. Clients
     *            connecting over TLS are told the nodes' TLS ports. Client certificates aren't required.
     */
    public ValkeyClusterTestContainer(int primaries, int replicasPerPrimary, int spareNodes, int basePort,
                                      int nodeTimeoutMs, String extraArgs, TestCertificates tls) {
        this(primaries, replicasPerPrimary, spareNodes, basePort, nodeTimeoutMs, extraArgs, tls, false);
    }

    /**
     * A cluster of nodes with the JSON, Bloom and search modules, search in coordinator mode. The
     * coordinator listens on its node's port + 20294: keep {@code basePort} under 12000, so that lands
     * below Linux's ephemeral range (as the bus port, port - 6000, needs it above 7024).
     */
    public static ValkeyClusterTestContainer withModules(int primaries, int basePort) {
        return new ValkeyClusterTestContainer(primaries, 0, 0, basePort, 5000, "", null, true);
    }

    private ValkeyClusterTestContainer(int primaries, int replicasPerPrimary, int spareNodes, int basePort,
                                       int nodeTimeoutMs, String extraArgs, TestCertificates tls, boolean modules) {
        this.replicasPerPrimary = replicasPerPrimary;
        int memberCount = primaries * (1 + replicasPerPrimary);
        for (int i = 0; i < memberCount + spareNodes; i++) {
            int port = basePort + i;
            // The cluster bus defaults to port + 10000, which for 27xxx lands in Linux's ephemeral
            // range (32768-60999), where outgoing connections from busy tests can already hold it.
            String tlsArgs = tls == null ? "" : String.format(
                    " --tls-port %d --tls-cert-file /tls/server.crt --tls-key-file /tls/server.key"
                            + " --tls-ca-cert-file /tls/ca.crt --tls-auth-clients no --tls-cluster yes", tlsPort(port));
            GenericContainer<?> node = new GenericContainer<>(modules ? BUNDLE_IMAGE : VALKEY_IMAGE)
                    .withNetworkMode("host")
                    .withCommand((modules ? MODULES_CONF + " " : "") + String.format(
                            "--port %d --cluster-port %d --cluster-enabled yes --cluster-config-file nodes.conf " +
                                    "--cluster-node-timeout %d --appendonly yes --daemonize no --bind 0.0.0.0 " +
                                    "--cluster-databases 16 %s",
                            port, busPort(port), nodeTimeoutMs, (extraArgs + tlsArgs).trim()
                    ))
                    // With host networking there are no mapped ports to probe; wait for the server itself.
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));
            if (modules) {
                // Started directly, not through the image's script, which would load the modules again.
                node.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("valkey-server"))
                        .withCopyToContainer(Transferable.of(MODULES), MODULES_CONF);
            }
            if (tls != null) {
                for (String file : List.of("ca.crt", "server.crt", "server.key")) {
                    node.withCopyFileToContainer(MountableFile.forHostPath(tls.dir().resolve(file), 0644), "/tls/" + file);
                }
                if (i < memberCount) {
                    tlsMembers.add("127.0.0.1:" + tlsPort(port));
                }
            }
            nodes.add(node);
            (i < memberCount ? members : spares).add("127.0.0.1:" + port);
        }
    }

    public void start() {
        try {
            Startables.deepStart(nodes).join();
        } catch (RuntimeException e) {
            StringBuilder logs = new StringBuilder();
            for (int i = 0; i < nodes.size(); i++) {
                GenericContainer<?> node = nodes.get(i);
                if (!node.isRunning()) {
                    logs.append("\n--- node ").append(i).append(" (").append(i < members.size() ? members.get(i)
                            : spares.get(i - members.size())).append(") ---\n").append(safeLogs(node));
                }
            }
            throw new IllegalStateException("Valkey nodes failed to start" + logs, e);
        }
        sleep(1000);

        String cmd = String.format(
                "valkey-cli --cluster create %s --cluster-replicas %d --cluster-yes",
                String.join(" ", members), replicasPerPrimary);
        Container.ExecResult result;
        try {
            result = nodes.getFirst().execInContainer("sh", "-c", cmd);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute cluster create command: " + cmd, e);
        }
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Valkey cluster: " + result.getStdout() + "\n" + result.getStderr());
        }
        awaitHealthy(Duration.ofSeconds(60));
    }

    /** Waits until every member reports cluster_state:ok and every replica has finished its initial sync. */
    public void awaitHealthy(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (members.stream().allMatch(this::healthy)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Cluster did not become healthy within " + timeout);
            }
            sleep(200);
        }
    }

    private boolean healthy(String address) {
        String[] hostPort = address.split(":");
        try (RawRespClient client = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]))) {
            String info = (String) client.call("CLUSTER", "INFO");
            if (!info.contains("cluster_state:ok")) {
                return false;
            }
            // Replicas must have finished their initial sync.
            String replication = (String) client.call("INFO", "replication");
            return !replication.contains("role:slave") || replication.contains("master_link_status:up");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The TLS port of the node listening on {@code port}, in a TLS cluster. */
    public static int tlsPort(int port) {
        return port + 1000;
    }

    /** TLS addresses of the cluster's members, in a TLS cluster. */
    public List<String> getTlsNodeAddresses() {
        return new ArrayList<>(tlsMembers);
    }

    /** Runs {@code args} on every member over plain text (e.g. CONFIG SET, ACL SETUSER). */
    public void onEveryNode(String... args) {
        for (String address : members) {
            String[] hostPort = address.split(":");
            try (RawRespClient client = new RawRespClient(hostPort[0], Integer.parseInt(hostPort[1]))) {
                Object reply = client.call(args);
                if (reply instanceof RawRespClient.Error error) {
                    throw new IllegalStateException(address + ": " + error.message());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The cluster bus port of the node listening on {@code port}. */
    public static int busPort(int port) {
        return port - 6000;
    }

    /** Kills the node's process (its connections are reset); {@link #restart} brings it back. */
    public void kill(String address) {
        GenericContainer<?> node = container(address);
        node.getDockerClient().killContainerCmd(node.getContainerId()).exec();
    }

    /** Starts a killed node again; it keeps its cluster config and rejoins. */
    public void restart(String address) {
        GenericContainer<?> node = container(address);
        node.getDockerClient().startContainerCmd(node.getContainerId()).exec();
    }

    private GenericContainer<?> container(String address) {
        int index = members.indexOf(address);
        if (index < 0) {
            index = members.size() + spares.indexOf(address);
        }
        return nodes.get(index);
    }


    public String getNodeAddress(int index) {
        return members.get(index);
    }

    /** Addresses of the nodes that are part of the cluster (primaries and replicas). */
    public List<String> getNodeAddresses() {
        return new ArrayList<>(members);
    }

    /** Addresses of started nodes that have not been joined to the cluster. */
    public List<String> getSpareAddresses() {
        return new ArrayList<>(spares);
    }

    private static String safeLogs(GenericContainer<?> node) {
        try {
            return node.getLogs();
        } catch (RuntimeException e) {
            return "(no logs: " + e.getMessage() + ")";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        nodes.forEach(GenericContainer::stop);
    }
}
