package com.flowops.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record Payment(UUID id, UUID orderId, BigDecimal amount, String currency, Status status, Instant createdAt) {
    public enum Status { SUCCEEDED, FAILED }
    public Payment {
        Objects.requireNonNull(id); Objects.requireNonNull(orderId); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("99999999999999999.99")) > 0)
            throw new IllegalArgumentException("Invalid amount");
        if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        Currency.getInstance(currency); amount = amount.setScale(2);
    }
}
