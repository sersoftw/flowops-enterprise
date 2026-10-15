package com.flowops.inventory.domain;

public record Product(String id, String name) {
    public Product {
        validateId(id);
        if (name == null || name.isBlank() || name.length() > 200 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Product name must contain 1–200 characters without NUL");
        }
    }

    public static void validateId(String id) {
        if (id == null || id.isBlank() || id.length() > 128 || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Product ID must contain 1–128 characters without NUL");
        }
    }
}
