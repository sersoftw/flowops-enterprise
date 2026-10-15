package com.flowops.orders;

import com.flowops.orders.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderDomainTest {
    @Test void rejectsNulInProductIdBeforePersistence() {
        for (String product : List.of("\0sku", "sku\0one", "sku\0")) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new Order.Item(product, 1, BigDecimal.ONE));
            assertEquals("Product ID must not contain NUL characters", error.getMessage());
        }
    }

    @Test void calculatesExactTotalsAndCopiesItems() {
        var items = new ArrayList<>(List.of(new Order.Item("sku", 3, new BigDecimal("0.10"))));
        var order = order(items);
        items.clear();
        assertEquals(new BigDecimal("0.30"), order.total());
        assertThrows(UnsupportedOperationException.class, () -> order.items().clear());
    }

    @Test void rejectsInvalidItemsWithoutFrameworkValidation() {
        for (String amount : List.of("0", "-1", "0.001", "1000000000")) {
            assertThrows(IllegalArgumentException.class, () -> new Order.Item("sku", 1, new BigDecimal(amount)));
        }
        for (int quantity : new int[]{0, -1, 10001}) {
            assertThrows(IllegalArgumentException.class, () -> new Order.Item("sku", quantity, BigDecimal.ONE));
        }
        assertThrows(IllegalArgumentException.class, () -> new Order.Item(" ", 1, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> order(List.of()));
    }

    @Test void normalizesPricesAndSupportsMaximumTotal() {
        assertEquals(new Order.Item("sku", 1, new BigDecimal("1.0")), new Order.Item("sku", 1, new BigDecimal("1.00")));
        var items = java.util.Collections.nCopies(100, new Order.Item("sku", 10000, new BigDecimal("999999999.99")));
        assertEquals(new BigDecimal("999999999990000.00"), order(items).total());
    }

    private Order order(List<Order.Item> items) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), "EUR", Order.Status.PENDING, Instant.now(), items);
    }

    @Test void normalizesEquivalentPricesWithoutRounding() {
        for (String price : List.of("12.50", "12.500", "12.5000", "1.2500e1")) {
            var item = new Order.Item("sku", 1, new BigDecimal(price));
            assertEquals(new BigDecimal("12.50"), item.unitPrice());
            assertEquals(new BigDecimal("12.50"), item.subtotal());
        }
        assertEquals(new BigDecimal("0.01"), new Order.Item("sku", 10000, new BigDecimal("0.01000")).unitPrice());
        for (String price : List.of("12.501", "12.5000000000000001", "0.00100", "1000000000.000")) {
            assertThrows(IllegalArgumentException.class, () -> new Order.Item("sku", 1, new BigDecimal(price)));
        }
    }

    @Test void preservesIntegerQuantityBounds() {
        assertEquals(1, new Order.Item("sku", 1, BigDecimal.ONE).quantity());
        assertEquals(10000, new Order.Item("sku", 10000, BigDecimal.ONE).quantity());
        for (int quantity : new int[]{Integer.MIN_VALUE, 0, 10001, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Order.Item("sku", quantity, BigDecimal.ONE));
        }
    }
}
