package com.flowops.contracts;

import java.util.Objects;
import java.util.UUID;

public record InventoryReserved(UUID causationId) {
    public InventoryReserved { Objects.requireNonNull(causationId); }
}
