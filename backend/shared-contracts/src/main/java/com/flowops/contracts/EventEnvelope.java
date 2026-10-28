package com.flowops.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T>(UUID eventId, String eventType, int eventVersion,
                               UUID aggregateId, UUID correlationId, Instant occurredAt, T payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId); Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(correlationId); Objects.requireNonNull(occurredAt); Objects.requireNonNull(payload);
        if (eventVersion != 1 || !java.util.Set.of("OrderCreated", "InventoryReserved", "InventoryRejected", "PaymentRequested", "PaymentSucceeded", "PaymentFailed").contains(eventType)) {
            throw new IllegalArgumentException("Unsupported event type/version");
        }
    }
}
