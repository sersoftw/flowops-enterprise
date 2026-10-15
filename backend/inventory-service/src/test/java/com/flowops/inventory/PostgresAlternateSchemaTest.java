package com.flowops.inventory;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@EnabledIfSystemProperty(named = "postgres.tests", matches = "true")
@Testcontainers
@SpringBootTest(properties = "DB_SCHEMA=inventory_alt")
class PostgresAlternateSchemaTest extends RestrictedInventoryContract {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("inventory_test").withInitScript("restricted-inventory.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", postgres::getFirstMappedPort);
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", () -> "inventory_alt_app");
        registry.add("DB_PASSWORD", () -> "inventory_alt_test_password");
    }
}
