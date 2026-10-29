package com.flowops.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

public record Order(UUID id, UUID customerId, String currency, Status status,
                    Instant createdAt, List<Item> items) {
    public enum Status { PENDING, INVENTORY_RESERVED, INVENTORY_REJECTED, COMPLETED, PAYMENT_FAILED_PENDING_COMPENSATION, RELEASING_INVENTORY, CANCELLED, MANUAL_REVIEW }

    public Order {
        if (id == null || customerId == null || status == null || createdAt == null) {
            throw new IllegalArgumentException("Order identity, customer, status and timestamp are required");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be an uppercase ISO 4217 code");
        }
        try {
            Currency.getInstance(currency);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Currency must be an uppercase ISO 4217 code");
        }
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("An order requires 1 to 100 items");
        }
        items = List.copyOf(items);
    }

    public BigDecimal total() {
        return items.stream().map(Item::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    public record Item(String productId, int quantity, BigDecimal unitPrice) {
        public Item {
            if (productId == null || productId.isBlank() || productId.length() > 100) {
                throw new IllegalArgumentException("Product ID must contain 1 to 100 characters");
            }
            if (productId.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Product ID must not contain NUL characters");
            }
            if (quantity < 1 || quantity > 10000) {
                throw new IllegalArgumentException("Quantity must be between 1 and 10000");
            }
            if (unitPrice == null || unitPrice.signum() <= 0
                    || unitPrice.compareTo(new BigDecimal("999999999.99")) > 0
                    || unitPrice.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Unit price must be positive with at most two decimal places");
            }
            unitPrice = unitPrice.setScale(2);
        }

        public BigDecimal subtotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
