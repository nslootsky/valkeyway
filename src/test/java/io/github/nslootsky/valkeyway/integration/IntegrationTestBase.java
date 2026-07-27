package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicBoolean;

@Tag("integration")
@SpringBootTest(
        classes = io.github.nslootsky.valkeyway.ValkeywayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "proxy.host=0.0.0.0",
                "proxy.port=6379",
                "logging.level.io.github.nslootsky.valkeyway=DEBUG"
        }
)
public abstract class IntegrationTestBase {

    private static final AtomicBoolean clusterStarted = new AtomicBoolean(false);
    protected static ValkeyClusterTestContainer cluster;

    protected static final int RESP_PORT = 6379;

    @LocalManagementPort
    protected int managementPort;

    protected static String clusterNodeAddresses;

    static {
        if (clusterStarted.compareAndSet(false, true)) {
            cluster = new ValkeyClusterTestContainer(3);
            cluster.start();
            clusterNodeAddresses = cluster.getNodeAddresses().stream()
                    .reduce((a, b) -> a + "," + b)
                    .orElseThrow();
        }
    }

    @DynamicPropertySource
    static void configureClusterNodes(DynamicPropertyRegistry registry) {
        registry.add("proxy.cluster-nodes", () -> clusterNodeAddresses.split(","));
    }
}
