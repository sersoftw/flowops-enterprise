package com.flowops.inventory.domain;

public record ReservationLine(String productId, int quantity) {
    public ReservationLine {
        Product.validateId(productId);
        Stock.positive(quantity);
    }
}
