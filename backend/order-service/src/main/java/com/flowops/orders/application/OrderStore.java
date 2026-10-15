package com.flowops.orders.application;

import com.flowops.orders.domain.Order;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStore {
    Creation create(String key, Order order);
    Optional<Order> find(UUID id);
    Page list(UUID customerId, Order.Status status, int page, int size);

    record Creation(Order order, boolean replayed) { }
    record Page(List<Order> content, int page, int size, long totalElements, int totalPages) { }

    class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict() { super("Idempotency-Key was already used for a different request"); }
    }
}
