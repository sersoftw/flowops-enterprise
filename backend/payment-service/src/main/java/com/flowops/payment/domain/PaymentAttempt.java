package com.flowops.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One deterministic attempt per order on Day 5; retries replay its durable outcome. */
public record PaymentAttempt(UUID id, UUID paymentId, Payment.Status outcome, int simulatorVersion, Instant createdAt) {
    public PaymentAttempt {
        Objects.requireNonNull(id); Objects.requireNonNull(paymentId); Objects.requireNonNull(outcome); Objects.requireNonNull(createdAt);
        if (simulatorVersion != 1) throw new IllegalArgumentException("Unsupported simulator");
    }
}
