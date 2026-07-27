package io.github.nslootsky.proxy.integration;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ValkeyClusterTestContainer implements AutoCloseable {

    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:9.1.1-alpine");

    private final List<GenericContainer<?>> nodes = new ArrayList<>();
    private final List<String> addresses = new ArrayList<>();
    private final int[] ports;

    public ValkeyClusterTestContainer(int numNodes) {
        this.ports = new int[numNodes];
        // Use sequential ports starting from 27000
        for (int i = 0; i < numNodes; i++) {
            this.ports[i] = 27000 + i;
        }

        for (int i = 0; i < numNodes; i++) {
            int port = this.ports[i];
            GenericContainer<?> node = new GenericContainer<>(VALKEY_IMAGE)
                    .withNetworkMode("host")
                    .withCommand(String.format(
                            "--port %d --cluster-enabled yes --cluster-config-file nodes.conf " +
                                    "--cluster-node-timeout 5000 --appendonly yes --daemonize no --bind 0.0.0.0 " +
                                    "--cluster-databases 16",
                            port
                    ))
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));
            nodes.add(node);
            addresses.add("127.0.0.1:" + port);
        }
    }

    public void start() {
        Startables.deepStart(nodes).join();

        // Small delay to ensure all nodes are ready
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Create cluster
        String nodeArgs = addresses.stream()
                .collect(Collectors.joining(" "));

        String cmd = String.format(
                "valkey-cli --cluster create %s --cluster-replicas 0 --cluster-yes",
                nodeArgs
        );

        Container.ExecResult result;
        try {
            result = nodes.get(0).execInContainer("sh", "-c", cmd);
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("Failed to execute cluster create command: " + cmd, e);
        }

        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Valkey cluster: " + result.getStdout() + "\n" + result.getStderr());
        }

        // Wait for cluster to stabilize
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String getNodeAddress(int index) {
        return addresses.get(index);
    }

    public List<String> getNodeAddresses() {
        return new ArrayList<>(addresses);
    }

    @Override
    public void close() {
        nodes.forEach(GenericContainer::stop);
    }
}
