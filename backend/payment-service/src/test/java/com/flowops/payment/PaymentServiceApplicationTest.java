package com.flowops.payment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentServiceApplicationTest {
    @Test
    void bootstrapEntryPointExists() throws NoSuchMethodException {
        assertNotNull(PaymentServiceApplication.class.getMethod("main", String[].class));
    }
}
