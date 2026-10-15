package com.flowops.inventory;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Mandatory in CI. Opt-in locally, with no silent fallback when Docker is missing.
@EnabledIfSystemProperty(named = "postgres.tests", matches = "true")
@Testcontainers
@SpringBootTest
class PostgresInventoryTest extends RestrictedInventoryContract {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("inventory_test").withInitScript("restricted-inventory.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", postgres::getFirstMappedPort);
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", () -> "inventory_app");
        registry.add("DB_PASSWORD", () -> "inventory_test_password");
    }
}
