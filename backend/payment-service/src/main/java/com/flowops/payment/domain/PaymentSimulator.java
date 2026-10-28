package com.flowops.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Simulation v1: totals up to 1000.00 approve, larger totals decline in every supported currency. */
public final class PaymentSimulator {
    private PaymentSimulator() { }
    public static Payment simulate(UUID order, BigDecimal amount, String currency, Instant now) {
        // Validate before deciding; never round a caller-supplied fractional cent.
        var validated = new Payment(UUID.randomUUID(), order, amount, currency, Payment.Status.FAILED, now);
        return new Payment(validated.id(), order, validated.amount(), currency,
                validated.amount().compareTo(new BigDecimal("1000.00")) <= 0 ? Payment.Status.SUCCEEDED : Payment.Status.FAILED, now);
    }
}
