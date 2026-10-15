package com.flowops.inventory;

import com.flowops.inventory.application.*;
import com.flowops.inventory.domain.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

abstract class InventoryPersistenceContract {
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;

    String product(int units) {
        String id = UUID.randomUUID().toString();
        inventory.createProduct(id, "Test product", units);
        return id;
    }

    List<ReservationLine> line(String id, int quantity) { return List.of(new ReservationLine(id, quantity)); }
    Stock stock(String id) { return inventory.stock(id).orElseThrow(); }

    @Test void catalogReplenishmentAndDatabaseConstraints() {
        var id = product(0);
        assertEquals(new Product(id, "Test product"), inventory.product(id).orElseThrow());
        assertEquals(new Stock(id, 8, 0), inventory.replenish(id, 8));
        assertTrue(inventory.product("unknown").isEmpty());
        assertTrue(inventory.stock("unknown").isEmpty());
        assertThrows(InventoryStore.ProductNotFound.class, () -> inventory.replenish("unknown", 1));
        assertThrows(DataIntegrityViolationException.class, () -> inventory.createProduct(id, "Duplicate", 100));
        assertEquals(new Stock(id, 8, 0), stock(id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE inventory.stock SET available = -1 WHERE product_id = ?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE inventory.stock SET reserved = -1 WHERE product_id = ?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE inventory.stock SET available = 2147483647, reserved = 1 WHERE product_id = ?", id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory.flyway_schema_history WHERE version = '2' AND success = TRUE", Integer.class));
    }

    @Test void reserveReplayConflictAndReleaseExactlyOnce() {
        var a = product(5); var b = product(10); var order = UUID.randomUUID();
        var items = List.of(new ReservationLine(a, 3), new ReservationLine(b, 2));
        var reserved = inventory.reserve(order, items);
        assertEquals(reserved, inventory.reserve(order, items.reversed()));
        assertEquals(reserved, inventory.reservation(order).orElseThrow());
        assertEquals(new Stock(a, 2, 3), stock(a));
        assertEquals(new Stock(b, 8, 2), stock(b));
        assertThrows(InventoryStore.Conflict.class, () -> inventory.reserve(order, line(a, 1)));
        var released = inventory.release(order).orElseThrow();
        assertEquals(Reservation.Status.RELEASED, released.status());
        assertEquals(released, inventory.release(order).orElseThrow());
        assertEquals(released, inventory.reserve(order, items));
        assertEquals(new Stock(a, 5, 0), stock(a));
        assertEquals(new Stock(b, 10, 0), stock(b));
        assertThrows(InventoryStore.Conflict.class, () -> inventory.reserve(order, line(a, 1)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory.reservation WHERE order_id = ?", Integer.class, order));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO inventory.reservation (order_id, status) VALUES (?, 'RESERVED')", order));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE inventory.reservation_line SET quantity = 0 WHERE order_id = ?", order));
    }

    @Test void insufficientStockRollsBackEarlierLinesAndAllowsRetry() {
        // Force a successful first update before the failing line, independent of random SKU order.
        var ids = List.of(product(3), product(3)).stream().sorted().toList();
        var order = UUID.randomUUID();
        var items = List.of(new ReservationLine(ids.get(0), 2), new ReservationLine(ids.get(1), 4));
        assertThrows(Stock.InsufficientStock.class, () -> inventory.reserve(order, items));
        assertTrue(inventory.reservation(order).isEmpty());
        for (var id : ids) assertEquals(new Stock(id, 3, 0), stock(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory.reservation_line WHERE order_id = ?", Integer.class, order));
        inventory.replenish(ids.get(1), 1);
        assertEquals(Reservation.Status.RESERVED, inventory.reserve(order, items).status());
        assertEquals(new Stock(ids.get(0), 1, 2), stock(ids.get(0)));
        assertEquals(new Stock(ids.get(1), 0, 4), stock(ids.get(1)));
    }

    @Test void unknownProductRollsBackAndMissingReleaseIsNoOp() {
        var id = product(3); var order = UUID.randomUUID();
        assertTrue(inventory.release(order).isEmpty());
        assertTrue(inventory.release(order).isEmpty());
        assertThrows(InventoryStore.ProductNotFound.class, () -> inventory.reserve(order,
                List.of(new ReservationLine(id, 2), new ReservationLine("zz-missing", 1))));
        assertEquals(new Stock(id, 3, 0), stock(id));
        assertTrue(inventory.reservation(order).isEmpty());
        assertEquals(Reservation.Status.RESERVED, inventory.reserve(order, line(id, 1)).status());
    }

    @Test void twoOrdersCannotReserveTheSameLastUnits() throws Exception {
        var id = product(5); var first = UUID.randomUUID(); var second = UUID.randomUUID();
        var outcomes = race(() -> inventory.reserve(first, line(id, 5)), () -> inventory.reserve(second, line(id, 5)));
        assertEquals(1, outcomes.stream().filter(Reservation.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(Stock.InsufficientStock.class::isInstance).count());
        assertEquals(new Stock(id, 0, 5), stock(id));
        assertEquals(1, List.of(first, second).stream().filter(o -> inventory.reservation(o).isPresent()).count());
    }

    @Test void concurrentIdenticalReservationsDeductOnce() throws Exception {
        var id = product(5); var order = UUID.randomUUID();
        var results = race(() -> inventory.reserve(order, line(id, 3)), () -> inventory.reserve(order, line(id, 3)));
        assertInstanceOf(Reservation.class, results.getFirst());
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(new Stock(id, 2, 3), stock(id));
    }

    @Test void concurrentConflictingReservationsHaveOneWinner() throws Exception {
        var id = product(5); var order = UUID.randomUUID();
        var results = race(() -> inventory.reserve(order, line(id, 2)), () -> inventory.reserve(order, line(id, 3)));
        assertEquals(1, results.stream().filter(Reservation.class::isInstance).count());
        assertEquals(1, results.stream().filter(InventoryStore.Conflict.class::isInstance).count());
        int reserved = inventory.reservation(order).orElseThrow().lines().getFirst().quantity();
        assertEquals(new Stock(id, 5 - reserved, reserved), stock(id));
    }

    @Test void concurrentReleasesReturnUnitsOnce() throws Exception {
        var id = product(5); var order = UUID.randomUUID();
        inventory.reserve(order, line(id, 3));
        var results = race(() -> inventory.release(order).orElseThrow(), () -> inventory.release(order).orElseThrow());
        assertInstanceOf(Reservation.class, results.getFirst());
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(Reservation.Status.RELEASED, ((Reservation) results.getFirst()).status());
        assertEquals(new Stock(id, 5, 0), stock(id));
    }

    @Test void oppositeInputOrderDoesNotDeadlock() throws Exception {
        var a = product(4); var b = product(4);
        var items = List.of(new ReservationLine(a, 2), new ReservationLine(b, 2));
        var results = race(() -> inventory.reserve(UUID.randomUUID(), items),
                () -> inventory.reserve(UUID.randomUUID(), items.reversed()));
        results.forEach(r -> assertInstanceOf(Reservation.class, r));
        assertEquals(new Stock(a, 0, 4), stock(a));
        assertEquals(new Stock(b, 0, 4), stock(b));
    }

    @Test void concurrentReplenishmentAndReservationDoNotLoseUpdates() throws Exception {
        var id = product(5);
        var results = race(() -> inventory.replenish(id, 7), () -> inventory.reserve(UUID.randomUUID(), line(id, 3)));
        assertInstanceOf(Stock.class, results.getFirst());
        assertInstanceOf(Reservation.class, results.getLast());
        assertEquals(new Stock(id, 9, 3), stock(id));
    }

    @Test void releaseRacingWithReplayCannotReserveAgain() throws Exception {
        var id = product(5); var order = UUID.randomUUID();
        inventory.reserve(order, line(id, 3));
        var results = race(() -> inventory.release(order).orElseThrow(), () -> inventory.reserve(order, line(id, 3)));
        results.forEach(r -> assertInstanceOf(Reservation.class, r));
        assertEquals(new Stock(id, 5, 0), stock(id));
        assertEquals(Reservation.Status.RELEASED, inventory.reservation(order).orElseThrow().status());
    }

    @Test void replenishmentOverflowLeavesStockUnchanged() {
        var id = product(Integer.MAX_VALUE);
        assertThrows(Stock.CapacityExceeded.class, () -> inventory.replenish(id, 1));
        var order = UUID.randomUUID();
        inventory.reserve(order, line(id, 1));
        assertThrows(Stock.CapacityExceeded.class, () -> inventory.replenish(id, 1));
        assertEquals(new Stock(id, Integer.MAX_VALUE - 1, 1), stock(id));
        inventory.release(order);
        assertEquals(new Stock(id, Integer.MAX_VALUE, 0), stock(id));
    }

    // Independent worker threads enter through the real application bean and use separate DB transactions.
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new TimeoutException("Start barrier timed out");
                try { return (Object) action.get(); }
                catch (RuntimeException ex) { return ex; }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both callers must be ready");
            start.countDown();
            return List.of(futures.getFirst().get(25, TimeUnit.SECONDS), futures.getLast().get(25, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Workers must terminate");
        }
    }
}
