package com.flowops.inventory.application;

import com.flowops.inventory.domain.*;
import java.util.Optional;
import java.util.UUID;

/** Transactional persistence port. Every mutation is atomic across the aggregate and stock. */
public interface InventoryStore {
    Product create(Product product, int initialStock);
    Optional<Product> product(String id);
    Optional<Stock> stock(String id);
    Stock replenish(String id, int quantity);
    Reservation reserve(Reservation request);
    Optional<Reservation> reservation(UUID orderId);
    Optional<Reservation> release(UUID orderId);

    final class Conflict extends RuntimeException {
        public Conflict() { super("Order already has a reservation with different items"); }
    }

    final class ProductNotFound extends RuntimeException {
        public ProductNotFound(String id) { super("Product not found: " + id); }
    }
}
