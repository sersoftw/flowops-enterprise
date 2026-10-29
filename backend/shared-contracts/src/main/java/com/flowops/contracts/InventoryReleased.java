package com.flowops.contracts;
import java.util.Objects;
import java.util.UUID;
public record InventoryReleased(UUID causationId, UUID reservationEventId, Outcome outcome) {
    public enum Outcome { RELEASED, ALREADY_RELEASED, ABSENT_PREVENTED }
    public InventoryReleased { Objects.requireNonNull(causationId); Objects.requireNonNull(reservationEventId); Objects.requireNonNull(outcome); }
}
