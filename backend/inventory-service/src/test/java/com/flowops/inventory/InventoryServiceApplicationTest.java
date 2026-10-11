package com.flowops.inventory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InventoryServiceApplicationTest {
    @Test
    void bootstrapEntryPointExists() throws NoSuchMethodException {
        assertNotNull(InventoryServiceApplication.class.getMethod("main", String[].class));
    }
}
