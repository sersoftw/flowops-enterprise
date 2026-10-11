package com.flowops.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderServiceApplicationTest {
    @Test
    void bootstrapEntryPointExists() throws NoSuchMethodException {
        assertNotNull(OrderServiceApplication.class.getMethod("main", String[].class));
    }
}
