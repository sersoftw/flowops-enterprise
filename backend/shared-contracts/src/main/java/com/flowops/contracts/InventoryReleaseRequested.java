package com.flowops.contracts;
import java.util.Objects;
import java.util.UUID;
/** Causation is PaymentFailed; reservationEventId identifies the original InventoryReserved. */
public record InventoryReleaseRequested(UUID causationId, UUID reservationEventId) {
    public InventoryReleaseRequested { Objects.requireNonNull(causationId); Objects.requireNonNull(reservationEventId); }
}
