package com.flowops.contracts;

import java.util.Objects;
import java.util.UUID;

public record InventoryRejected(UUID causationId, Reason reason) {
    public enum Reason { INSUFFICIENT_STOCK, PRODUCT_NOT_FOUND, RESERVATION_RELEASED, RESERVATION_CONFLICT }
    public InventoryRejected { Objects.requireNonNull(causationId); Objects.requireNonNull(reason); }
}
