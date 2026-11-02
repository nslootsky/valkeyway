/*
 * Copyright (c) 2026 Nick Slootsky
 * Licensed under the MIT License. See LICENSE file.
 */

package io.github.nslootsky.valkeyway.integration;

import java.util.concurrent.TimeUnit;
import io.github.nslootsky.valkeyway.integration.RawRespClient.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.nslootsky.valkeyway.integration.DirectNodes.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The proxy is seeded with a DNS name, as with a cloud configuration endpoint, and the name is
 * re-pointed: first at another cluster while the old one stays up (a blue/green cutover), then at
 * nothing (every seed gone).
 *
 * <p>The proxy has to resolve a name the test controls, so it runs in a JRE container with the
 * packaged jar mounted (Maven passes its path as {@code valkeyway.jar}; {@code mvn verify} builds
 * it before the integration tests), and the test rewrites that container's /etc/hosts.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedDnsTest {

    private static final DockerImageName VALKEY = ValkeyClusterTestContainer.IMAGE;
    private static final String SEED = "valkey-endpoint";

    private static Network network;
    private static GenericContainer<?> clusterA;
    private static GenericContainer<?> clusterB;
    private static GenericContainer<?> proxy;

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        clusterA = singleNodeCluster("a");
        clusterB = singleNodeCluster("b");
        valkey(clusterA, "SET", "which", "A");
        valkey(clusterB, "SET", "which", "B");

        String jarProperty = System.getProperty("valkeyway.jar");
        assertNotNull(jarProperty, "run through Maven (mvn verify), which passes the packaged jar as valkeyway.jar");
        Path jar = Path.of(jarProperty);
        assertTrue(Files.exists(jar), "package the proxy first: " + jar);
        // The stock JRE image with the jar mounted read-only: nothing to build or upload.
        proxy = new GenericContainer<>(DockerImageName.parse("docker.io/eclipse-temurin:25-jre-alpine"))
                .withFileSystemBind(jar.toAbsolutePath().toString(), "/app.jar", BindMode.READ_ONLY)
                .withNetwork(network)
                .withExtraHost(SEED, ip(clusterA))
                .withExposedPorts(6379)
                .withCommand("java", "-jar", "/app.jar",
                        "--proxy.cluster-nodes=" + SEED + ":6379",
                        "--proxy.glide.topology-check-seconds=2",
                        "--management.server.port=6380")
                .waitingFor(Wait.forLogMessage(".*Started ValkeywayApplication.*", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
        proxy.start();
    }

    @AfterAll
    static void stop() {
        Stream.of(proxy, clusterA, clusterB).filter(java.util.Objects::nonNull).forEach(GenericContainer::stop);
        if (network != null) {
            network.close();
        }
    }

    @Test
    @Order(1)
    void aSeedNameRepointedToAnotherClusterIsFollowed() throws Exception {
        try (RawRespClient client = client()) {
            assertEquals("A", client.call("GET", "which"));

            pointSeedAt(ip(clusterB));
            long repointedAt = System.nanoTime();
            await("the proxy to follow the seed name to cluster B", Duration.ofSeconds(30), () -> {
                try {
                    return "B".equals(client.call("GET", "which"));
                } catch (IOException e) {
                    return false;
                }
            });
            System.out.printf("Followed the re-pointed seed in %d ms%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - repointedAt));
            assertEquals(new Status("OK"), client.call("SET", "cutover", "done"));
            assertEquals("done", valkey(clusterB, "GET", "cutover"), "writes go to the new cluster");
            assertEquals("", valkey(clusterA, "GET", "cutover"), "and not to the old one, which is still up");
        }
    }

    @Test
    @Order(2)
    void newClientsFallBackToKnownNodesWhenTheSeedsAreGone() throws Exception {
        pointSeedAt(null);
        try (RawRespClient client = client()) {
            // Database 3 has no client yet; creating it from the unresolvable seed fails, so the
            // proxy uses the nodes its database-0 client knows.
            assertEquals(new Status("OK"), client.call("SELECT", "3"));
            assertEquals(new Status("OK"), client.call("SET", "fallback", "yes"));
            assertEquals("yes", valkey(clusterB, "-n", "3", "GET", "fallback"));
        }
        assertTrue(proxy.getLogs().contains("Can't reach the configured cluster nodes"), "the fallback is logged");
    }

    // ---- helpers ----

    private static RawRespClient client() throws IOException {
        return new RawRespClient(proxy.getHost(), proxy.getMappedPort(6379));
    }

    /** A one-node cluster owning every slot, reachable on the test network as {@code alias}. */
    private static GenericContainer<?> singleNodeCluster(String alias) throws Exception {
        GenericContainer<?> node = new GenericContainer<>(VALKEY)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withCommand("--cluster-enabled yes --cluster-databases 16 --appendonly no")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        node.start();
        // A node that never met another doesn't know its own address; announce it for CLUSTER SLOTS.
        valkey(node, "CONFIG", "SET", "cluster-announce-ip", ip(node));
        valkey(node, "CLUSTER", "ADDSLOTSRANGE", "0", "16383");
        await("cluster " + alias + " to be ok", Duration.ofSeconds(20), () -> {
            try {
                return valkey(node, "CLUSTER", "INFO").contains("cluster_state:ok");
            } catch (Exception e) {
                return false;
            }
        });
        return node;
    }

    private static String ip(GenericContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
    }

    /** Rewrites the proxy container's /etc/hosts entry for the seed name ({@code null} removes it). */
    private static void pointSeedAt(String ip) throws Exception {
        String entry = ip == null ? "" : "echo '" + ip + " " + SEED + "' >> /tmp/hosts; ";
        // /etc/hosts is bind-mounted, so overwrite it in place rather than replacing the file.
        Container.ExecResult result = proxy.execInContainer("sh", "-c",
                "grep -vw " + SEED + " /etc/hosts > /tmp/hosts; " + entry + "cat /tmp/hosts > /etc/hosts");
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    private static String valkey(GenericContainer<?> node, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "valkey-cli";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Container.ExecResult result = node.execInContainer(cmd);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", args) + ": " + result.getStderr());
        }
        return result.getStdout().strip();
    }
}
