package io.github.nslootsky.valkeyway.integration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthAndMetricsTest extends IntegrationTestBase {

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    @Order(1)
    void healthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health", String.class);

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("UP") || body.contains("cluster"));
    }

    @Test
    @Order(2)
    void metricsEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/metrics", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    @Order(3)
    void prometheusEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }
}
