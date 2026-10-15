package com.flowops.inventory;

import com.flowops.inventory.persistence.JdbcInventoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventorySchemaValidationTest {
    @Test void rejectsUnsafeOrAmbiguousIdentifiersBeforeUsingTheDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        for (String schema : new String[]{null, "", " ", "inventory,public", "inventory.public",
                "inventory; DROP SCHEMA orders", "inventory\"", "inventory--", "inventory/*x*/",
                "inventory&currentSchema=public", "inventory\n", "invent\0ory", "1inventory", "a".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> new JdbcInventoryStore(jdbc, manager, schema));
        }
        verifyNoInteractions(jdbc, manager);
    }

    @Test void qualifiesQueriesWithTheValidatedQuotedSchemaAndBindsValues() {
        var jdbc = mock(JdbcTemplate.class);
        var manager = mock(PlatformTransactionManager.class);
        var store = new JdbcInventoryStore(jdbc, manager, "Inventory_Alt");
        store.product("sku'; DROP TABLE product; --");
        verify(jdbc).query(eq("SELECT id, name FROM \"Inventory_Alt\".product WHERE id = ?"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<com.flowops.inventory.domain.Product>>any(),
                eq("sku'; DROP TABLE product; --"));
    }
}
