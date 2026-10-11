package com.flowops.payment;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.assertEquals;

// H2 checks application wiring and migrations. PostgreSQL isolation is tested in Compose.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:payments;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.properties.hibernate.default_schema=payments",
    "spring.flyway.default-schema=payments",
    "spring.flyway.schemas=payments"
})
class PaymentServiceApplicationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void healthEndpointReportsUp() {
        var response = RestClient.create().get()
            .uri("http://localhost:" + port + "/actuator/health")
            .retrieve().toEntity(Map.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void flywayBootstrapsTheServiceSchema() {
        assertEquals("payment-service", jdbc.queryForObject(
            "SELECT service_name FROM payments.bootstrap_marker", String.class));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM payments.flyway_schema_history WHERE version = '1' AND success = TRUE",
            Integer.class));
    }
}
