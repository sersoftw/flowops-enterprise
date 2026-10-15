package com.flowops.inventory;

import com.flowops.inventory.application.*;
import com.flowops.inventory.domain.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryDomainTest {
    @Test void stockConservesUnitsAndRejectsInvalidTransitions() {
        var stock = new Stock("sku", 10, 0);
        assertEquals(new Stock("sku", 6, 4), stock.reserve(4));
        assertEquals(stock, stock.reserve(4).release(4));
        assertEquals(new Stock("sku", 13, 0), stock.replenish(3));
        assertThrows(Stock.InsufficientStock.class, () -> stock.reserve(11));
        assertThrows(IllegalStateException.class, () -> stock.release(1));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> stock.replenish(-1));
        assertThrows(IllegalArgumentException.class, () -> new Stock("sku", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Stock("sku", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Stock("sku", Integer.MAX_VALUE, 1));
        assertThrows(Stock.CapacityExceeded.class, () -> new Stock("sku", Integer.MAX_VALUE, 0).replenish(1));
        assertEquals(new Stock("sku", 0, Integer.MAX_VALUE),
                new Stock("sku", Integer.MAX_VALUE, 0).reserve(Integer.MAX_VALUE));
    }

    @Test void validatesProductsAndReservationLines() {
        for (String id : new String[]{null, "", " ", "a\0b", "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> new Product(id, "Name"));
        }
        for (String name : new String[]{null, "", " ", "a\0b", "a".repeat(201)}) {
            assertThrows(IllegalArgumentException.class, () -> new Product("sku", name));
        }
        assertThrows(IllegalArgumentException.class, () -> new ReservationLine("sku", 0));
        assertThrows(IllegalArgumentException.class, () -> new ReservationLine("sku", -1));
        assertThrows(NullPointerException.class, () -> new Reservation(null, Reservation.Status.RESERVED,
                List.of(new ReservationLine("sku", 1))));
        assertThrows(IllegalArgumentException.class, () -> Reservation.canonicalLines(null));
        assertThrows(IllegalArgumentException.class, () -> Reservation.canonicalLines(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Reservation.canonicalLines(
                java.util.Collections.nCopies(101, new ReservationLine("sku", 1))));
        assertThrows(IllegalArgumentException.class, () -> Reservation.canonicalLines(
                java.util.Arrays.asList((ReservationLine) null)));
    }

    @Test void canonicalizesWithoutMutatingInputAndRejectsDuplicateProducts() {
        var items = new ArrayList<>(List.of(new ReservationLine("z", 2), new ReservationLine("a", 1)));
        var reservation = new Reservation(UUID.randomUUID(), Reservation.Status.RESERVED, items);
        items.clear();
        assertEquals(List.of(new ReservationLine("a", 1), new ReservationLine("z", 2)), reservation.lines());
        assertThrows(UnsupportedOperationException.class, () -> reservation.lines().clear());
        assertThrows(IllegalArgumentException.class, () -> Reservation.canonicalLines(
                List.of(new ReservationLine("a", 1), new ReservationLine("a", 2))));
    }

    @Test void invalidCommandsNeverReachPersistence() {
        var store = mock(InventoryStore.class);
        var service = new InventoryService(store);
        assertThrows(IllegalArgumentException.class, () -> service.createProduct("sku", "Name", -1));
        assertThrows(IllegalArgumentException.class, () -> service.reserve(UUID.randomUUID(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.replenish("sku", 0));
        assertThrows(NullPointerException.class, () -> service.release(null));
        verifyNoInteractions(store);
    }
}
