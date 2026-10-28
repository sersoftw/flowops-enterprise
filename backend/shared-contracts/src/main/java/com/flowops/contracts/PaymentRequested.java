package com.flowops.contracts;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** Server-computed order total; no card or customer credentials are transported. */
public record PaymentRequested(UUID causationId, BigDecimal amount, String currency) {
    public PaymentRequested {
        Objects.requireNonNull(causationId);
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("99999999999999999.99")) > 0)
            throw new IllegalArgumentException("Invalid payment amount");
        if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        Currency.getInstance(currency);
        amount = amount.setScale(2);
    }
}
