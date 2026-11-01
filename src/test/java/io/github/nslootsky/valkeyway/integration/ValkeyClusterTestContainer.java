/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A Valkey cluster of {@code primaries} shards with {@code replicasPerPrimary} replicas each, plus
 * {@code spareNodes} cluster-enabled nodes that are started but not joined (for adding shards).
 */
public class ValkeyClusterTestContainer implements AutoCloseable {

    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:9.1.3-alpine");

    private final List<GenericContainer<?>> nodes = new ArrayList<>();
    private final List<String> members = new ArrayList<>();
    private final List<String> spares = new ArrayList<>();
    private final int primaries;
    private final int replicasPerPrimary;

    public ValkeyClusterTestContainer(int numNodes) {
        this(numNodes, 0, 0, 27000, 5000, "");
    }

    public ValkeyClusterTestContainer(int primaries, int replicasPerPrimary, int spareNodes, int basePort,
                                      int nodeTimeoutMs, String extraArgs) {
        this.primaries = primaries;
        this.replicasPerPrimary = replicasPerPrimary;
        int memberCount = primaries * (1 + replicasPerPrimary);
        for (int i = 0; i < memberCount + spareNodes; i++) {
            int port = basePort + i;
            // The cluster bus defaults to port + 10000, which for 27xxx lands in Linux's ephemeral
            // range (32768-60999), where outgoing connections from busy tests can already hold it.
            GenericContainer<?> node = new GenericContainer<>(VALKEY_IMAGE)
                    .withNetworkMode("host")
                    .withCommand(String.format(
                            "--port %d --cluster-port %d --cluster-enabled yes --cluster-config-file nodes.conf " +
                                    "--cluster-node-timeout %d --appendonly yes --daemonize no --bind 0.0.0.0 " +
                                    "--cluster-databases 16 %s",
                            port, busPort(port), nodeTimeoutMs, extraArgs
                    ))
                    // With host networking there are no mapped ports to probe; wait for the server itself.
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));
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

    /** Waits until every member reports cluster_state:ok and sees {@code primaries} primaries with slots. */
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

    public int primaries() {
        return primaries;
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
