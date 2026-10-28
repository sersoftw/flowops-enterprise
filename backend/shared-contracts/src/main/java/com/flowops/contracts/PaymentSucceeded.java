package com.flowops.contracts;

import java.util.Objects;
import java.util.UUID;

public record PaymentSucceeded(UUID causationId, UUID paymentId) {

    public PaymentSucceeded { Objects.requireNonNull(causationId); Objects.requireNonNull(paymentId);  }
}
