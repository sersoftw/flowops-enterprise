package com.flowops.inventory.domain;

public record Stock(String productId, int available, int reserved) {
    public Stock {
        Product.validateId(productId);
        if (available < 0 || reserved < 0 || (long) available + reserved > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Stock must be nonnegative and total must fit an integer");
        }
    }

    public Stock reserve(int quantity) {
        positive(quantity);
        if (quantity > available) throw new InsufficientStock(productId);
        return new Stock(productId, available - quantity, Math.addExact(reserved, quantity));
    }

    public Stock release(int quantity) {
        positive(quantity);
        if (quantity > reserved) throw new IllegalStateException("Cannot release unreserved stock");
        return new Stock(productId, Math.addExact(available, quantity), reserved - quantity);
    }

    public Stock replenish(int quantity) {
        positive(quantity);
        if ((long) available + reserved + quantity > Integer.MAX_VALUE) throw new CapacityExceeded();
        return new Stock(productId, Math.addExact(available, quantity), reserved);
    }

    public static void positive(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
    }

    public static final class InsufficientStock extends RuntimeException {
        public InsufficientStock(String id) { super("Insufficient stock for " + id); }
    }

    public static final class CapacityExceeded extends RuntimeException {
        public CapacityExceeded() { super("Total stock exceeds integer capacity"); }
    }
}
