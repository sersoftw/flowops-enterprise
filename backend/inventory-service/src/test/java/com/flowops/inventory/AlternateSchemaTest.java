package com.flowops.inventory;

import org.springframework.boot.test.context.SpringBootTest;

// Fast feedback for schema routing; restricted PostgreSQL remains mandatory in CI.
@SpringBootTest(properties = {
    "DB_SCHEMA=inventory_alt",
    "spring.datasource.url=jdbc:h2:mem:inventory-alt;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password="
})
class AlternateSchemaTest extends InventoryMessagingContract {}
