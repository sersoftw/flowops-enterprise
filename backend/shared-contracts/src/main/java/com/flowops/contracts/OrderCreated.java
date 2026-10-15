package com.flowops.contracts;

import java.util.List;

/** Inventory requirements only; prices/customer data remain owned by Order Service. */
public record OrderCreated(List<Item> items) {
    public OrderCreated {
        if (items == null || items.isEmpty() || items.size() > 100) throw new IllegalArgumentException("Expected 1–100 items");
        items = List.copyOf(items);
        if (items.stream().map(Item::productId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Event product IDs must be unique");
        }
        items = items.stream().sorted(java.util.Comparator.comparing(Item::productId)).toList();
    }
    public record Item(String productId, int quantity) {
        public Item {
            if (productId == null || productId.isBlank() || productId.length() > 100 || productId.indexOf('\0') >= 0
                    || quantity < 1 || quantity > 1_000_000) throw new IllegalArgumentException("Invalid inventory requirement");
        }
    }
}
