package com.flowops.inventory.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Reservation(UUID orderId, Status status, List<ReservationLine> lines) {
    public enum Status { RESERVED, RELEASED }

    public Reservation {
        Objects.requireNonNull(orderId, "Order ID is required");
        Objects.requireNonNull(status, "Status is required");
        lines = canonicalLines(lines);
    }

    public static List<ReservationLine> canonicalLines(List<ReservationLine> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 100 || lines.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A reservation requires 1–100 non-null lines");
        }
        var sorted = lines.stream().sorted(Comparator.comparing(ReservationLine::productId)).toList();
        if (sorted.stream().map(ReservationLine::productId).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("Duplicate product IDs are not allowed");
        }
        return sorted;
    }
}
