package com.flowops.contracts;

import java.util.Objects;
import java.util.UUID;

public record PaymentFailed(UUID causationId, UUID paymentId, Reason reason) {
    public enum Reason { SIMULATED_DECLINE }
    public PaymentFailed { Objects.requireNonNull(causationId); Objects.requireNonNull(paymentId); Objects.requireNonNull(reason); }
}
