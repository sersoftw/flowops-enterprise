package com.flowops.orders.application;

import com.flowops.orders.domain.Order;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class OrderService {
    private final OrderStore store;
    private final Clock clock;

    public OrderService(OrderStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public OrderStore.Creation create(String key, UUID customerId, String currency, List<Order.Item> items) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 128 letters, digits, '.', '_', ':' or '-'");
        }
        return store.create(key, new Order(UUID.randomUUID(), customerId, currency,
                Order.Status.PENDING, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), items));
    }

    public Order get(UUID id) {
        return store.find(id).orElseThrow(NotFound::new);
    }

    public OrderStore.Page list(UUID customerId, Order.Status status, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be between 0 and 1000000; size between 1 and 100");
        }
        return store.list(customerId, status, page, size);
    }

    public static class NotFound extends RuntimeException {
        public NotFound() { super("Order not found"); }
    }
}
