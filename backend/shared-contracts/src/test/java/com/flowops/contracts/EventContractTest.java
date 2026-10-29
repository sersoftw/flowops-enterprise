package com.flowops.contracts;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.networknt.schema.*;
import static org.junit.jupiter.api.Assertions.*;

class EventContractTest {
    @Test void envelopesMatchPublishedVersionedSchemas() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        var legacyJson = new com.fasterxml.jackson.databind.ObjectMapper();
        var id = UUID.randomUUID();
        var payloads = Map.of("OrderCreated", new OrderCreated(List.of(new OrderCreated.Item("sku", 2))),
                "InventoryReserved", new InventoryReserved(id),
                "InventoryRejected", new InventoryRejected(id, InventoryRejected.Reason.INSUFFICIENT_STOCK),
                "PaymentRequested", new PaymentRequested(id, new java.math.BigDecimal("12.50"), "EUR"),
                "PaymentSucceeded", new PaymentSucceeded(id,id),
                "PaymentFailed", new PaymentFailed(id,id,PaymentFailed.Reason.SIMULATED_DECLINE),
                "InventoryReleaseRequested", new InventoryReleaseRequested(id,id),
                "InventoryReleased", new InventoryReleased(id,id,InventoryReleased.Outcome.RELEASED));
        for (var entry : payloads.entrySet()) {
            var event = new EventEnvelope<>(id, entry.getKey(), 1, id, id, Instant.parse("2026-10-15T00:00:00Z"), entry.getValue());
            String raw = json.writeValueAsString(event);
            try (var input = getClass().getResourceAsStream("/contracts/v1/" + entry.getKey() + ".schema.json")) {
                assertNotNull(input);
                var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
                assertTrue(schema.validate(legacyJson.readTree(raw)).isEmpty(), raw);
                assertFalse(schema.validate(legacyJson.readTree(raw.replace("\"eventVersion\":1", "\"eventVersion\":2"))).isEmpty());
                assertFalse(schema.validate(legacyJson.readTree(raw.replace("\"eventId\"", "\"wrongField\""))).isEmpty());
            }
        }
    }

    @Test void transportDtosRejectInvalidVersionsAndRequirements() {
        var id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope<>(id,"OrderCreated",2,id,id,Instant.now(),new Object()));
        assertThrows(IllegalArgumentException.class, () -> new OrderCreated.Item("sku",0));
        assertThrows(IllegalArgumentException.class, () -> new OrderCreated.Item("bad\0id",1));
        assertThrows(IllegalArgumentException.class, () -> new OrderCreated(List.of(new OrderCreated.Item("sku",1),new OrderCreated.Item("sku",2))));
        assertThrows(NullPointerException.class, () -> new InventoryReserved(null));
    }
}
