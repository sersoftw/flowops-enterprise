package com.flowops.inventory.application;

import com.flowops.inventory.domain.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal application API; no transport or cross-service persistence dependency. */
public final class InventoryService {
    private final InventoryStore store;

    public InventoryService(InventoryStore store) { this.store = Objects.requireNonNull(store); }

    public Product createProduct(String id, String name, int initialStock) {
        var product = new Product(id, name);
        new Stock(id, initialStock, 0);
        return store.create(product, initialStock);
    }

    public Optional<Product> product(String id) { Product.validateId(id); return store.product(id); }
    public Optional<Stock> stock(String id) { Product.validateId(id); return store.stock(id); }

    public Stock replenish(String id, int quantity) {
        Product.validateId(id);
        Stock.positive(quantity);
        return store.replenish(id, quantity);
    }

    public Reservation reserve(UUID orderId, List<ReservationLine> items) {
        return store.reserve(new Reservation(orderId, Reservation.Status.RESERVED, items));
    }

    public Optional<Reservation> reservation(UUID orderId) {
        return store.reservation(Objects.requireNonNull(orderId));
    }

    public Optional<Reservation> release(UUID orderId) {
        return store.release(Objects.requireNonNull(orderId));
    }
}
