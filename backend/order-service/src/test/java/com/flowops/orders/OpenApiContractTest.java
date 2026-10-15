package com.flowops.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiContractTest {
    private static final String REQUEST = """
        {"customerId":"f90c8ab2-5acf-4b35-8ec3-917167624075","currency":"EUR",
         "items":[{"productId":"sku-1","quantity":2,"unitPrice":12.50}]}
        """;

    @Test void validRequestAndResponseRemainValid() throws Exception {
        assertTrue(OpenApiSchemas.validate("CreateOrder", REQUEST).isEmpty());
        String response = REQUEST.replace("\"currency\"", """
            "id":"e84a93b8-cc2f-4e6b-bca0-c06a24d03b27","status":"PENDING",
            "createdAt":"2026-10-15T00:00:00Z","total":25.00,"currency"
            """.strip());
        assertTrue(OpenApiSchemas.validate("Order", response).isEmpty());
    }

    @Test void rejectsUndeclaredOrderProperties() throws Exception {
        var errors = OpenApiSchemas.validate("CreateOrder", REQUEST.replace("\"currency\"", "\"total\":1,\"currency\""));
        assertTrue(errors.stream().anyMatch(e -> e.getType().equals("additionalProperties")), errors.toString());
    }

    @Test void rejectsUndeclaredItemProperties() throws Exception {
        var errors = OpenApiSchemas.validate("CreateOrder", REQUEST.replace("\"quantity\"", "\"discount\":1,\"quantity\""));
        assertTrue(errors.stream().anyMatch(e -> e.getType().equals("additionalProperties")), errors.toString());
    }

    @Test void rejectsNulInProductId() throws Exception {
        for (String product : new String[]{"\\u0000sku", "sku\\u0000one", "sku\\u0000"}) {
            var errors = OpenApiSchemas.validate("CreateOrder", REQUEST.replace("sku-1", product));
            assertTrue(errors.stream().anyMatch(e -> e.getType().equals("pattern")), errors.toString());
        }
    }
}
