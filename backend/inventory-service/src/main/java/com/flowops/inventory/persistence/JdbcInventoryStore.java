package com.flowops.inventory.persistence;

import com.flowops.inventory.application.InventoryStore;
import com.flowops.inventory.domain.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcInventoryStore implements InventoryStore {
    private final String schema;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcInventoryStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${spring.flyway.default-schema}") String schema) {
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Inventory schema must be a simple SQL identifier (1–63 ASCII characters)");
        }
        this.schema = "\"" + schema + "\"";
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    @Override
    public Product create(Product product, int initialStock) {
        new Stock(product.id(), initialStock, 0);
        return transaction.execute(tx -> {
            jdbc.update("INSERT INTO " + schema + ".product (id, name) VALUES (?, ?)", product.id(), product.name());
            jdbc.update("INSERT INTO " + schema + ".stock (product_id, available, reserved) VALUES (?, ?, 0)",
                    product.id(), initialStock);
            return product;
        });
    }

    @Override
    public Optional<Product> product(String id) {
        return jdbc.query("SELECT id, name FROM " + schema + ".product WHERE id = ?",
                (rs, row) -> new Product(rs.getString(1), rs.getString(2)), id).stream().findFirst();
    }

    @Override public Optional<Stock> stock(String id) { return readStock(id, false); }

    private Optional<Stock> readStock(String id, boolean lock) {
        return jdbc.query("SELECT product_id, available, reserved FROM " + schema + ".stock WHERE product_id = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Stock(rs.getString(1), rs.getInt(2), rs.getInt(3)), id).stream().findFirst();
    }

    private Stock lockStock(String id) {
        return readStock(id, true).orElseThrow(() -> new ProductNotFound(id));
    }

    private void saveStock(Stock stock) {
        jdbc.update("UPDATE " + schema + ".stock SET available = ?, reserved = ? WHERE product_id = ?",
                stock.available(), stock.reserved(), stock.productId());
    }

    @Override
    public Stock replenish(String id, int quantity) {
        return transaction.execute(tx -> {
            var stock = lockStock(id).replenish(quantity);
            saveStock(stock);
            return stock;
        });
    }

    @Override
    public Reservation reserve(Reservation request) {
        if (request.status() != Reservation.Status.RESERVED) throw new IllegalArgumentException("Expected RESERVED");
        try {
            return transaction.execute(tx -> reserveInTransaction(request));
        } catch (DuplicateKeyException ex) {
            // PostgreSQL aborts the losing transaction. Replay only after rollback, in a fresh transaction.
            return transaction.execute(tx -> reserveInTransaction(request));
        }
    }

    // The message handler shares its inbox/decision/outbox transaction with this operation.
    // Lock and validate every line before mutating, so expected rejection leaves no partial state.
    Reservation reserveInTransaction(Reservation request) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reservation requires an active transaction");
        }
        boolean cancelled = lockOrder(request.orderId());
        var existing = readReservation(request.orderId(), false);
        if (existing.isPresent()) return replay(existing.get(), request);
        if (cancelled) throw new ReservationCancelled();
        var locked = request.lines().stream().map(line -> lockStock(line.productId())).toList();
        existing = readReservation(request.orderId(), false);
        if (existing.isPresent()) return replay(existing.get(), request);
        var updated = new java.util.ArrayList<Stock>();
        for (int i = 0; i < locked.size(); i++) updated.add(locked.get(i).reserve(request.lines().get(i).quantity()));
        jdbc.update("INSERT INTO " + schema + ".reservation (order_id, status) VALUES (?, 'RESERVED')", request.orderId());
        for (int i = 0; i < updated.size(); i++) {
            saveStock(updated.get(i));
            var line = request.lines().get(i);
            jdbc.update("INSERT INTO " + schema + ".reservation_line (order_id, product_id, quantity) VALUES (?, ?, ?)",
                    request.orderId(), line.productId(), line.quantity());
        }
        return request;
    }
    private Reservation replay(Reservation existing, Reservation request) {
        if (!existing.lines().equals(request.lines())) throw new Conflict();
        return existing;
    }

    @Override
    public Optional<Reservation> reservation(UUID orderId) {
        return transaction.execute(tx -> readReservation(orderId, false));
    }

    private Optional<Reservation> readReservation(UUID orderId, boolean lock) {
        var statuses = jdbc.query("SELECT status FROM " + schema + ".reservation WHERE order_id = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> Reservation.Status.valueOf(rs.getString(1)), orderId);
        if (statuses.isEmpty()) return Optional.empty();
        var lines = jdbc.query("SELECT product_id, quantity FROM " + schema + ".reservation_line WHERE order_id = ?",
                (rs, row) -> new ReservationLine(rs.getString(1), rs.getInt(2)), orderId);
        return Optional.of(new Reservation(orderId, statuses.getFirst(), lines));
    }

    /** Stable row serializes reservation, release and missing-reservation cancellation. */
    boolean lockOrder(UUID orderId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Guard requires an active transaction");
        var existing=jdbc.queryForList("SELECT cancelled FROM " + schema + ".reservation_guard WHERE order_id=? FOR UPDATE",Boolean.class,orderId);
        if (!existing.isEmpty()) return existing.getFirst();
        // Concurrent first inserts are arbitrated by the PK; callers retry after rollback.
        jdbc.update("INSERT INTO " + schema + ".reservation_guard(order_id,cancelled) VALUES (?,FALSE)",orderId);
        return false;
    }

    @Override
    public Optional<Reservation> release(UUID orderId) {
        try { return transaction.execute(tx -> releaseInTransaction(orderId)); }
        catch (DuplicateKeyException race) { return transaction.execute(tx -> releaseInTransaction(orderId)); }
    }

    Optional<Reservation> releaseInTransaction(UUID orderId) {
        lockOrder(orderId);
        var existing = readReservation(orderId, true);
        if (existing.isPresent() && existing.get().status() != Reservation.Status.RELEASED) {
            var reservation = existing.get();
            var updated = reservation.lines().stream().map(line -> lockStock(line.productId()).release(line.quantity())).toList();
            updated.forEach(this::saveStock);
            jdbc.update("UPDATE " + schema + ".reservation SET status='RELEASED' WHERE order_id=?", orderId);
            existing = Optional.of(new Reservation(orderId, Reservation.Status.RELEASED, reservation.lines()));
        }
        jdbc.update("UPDATE " + schema + ".reservation_guard SET cancelled=TRUE WHERE order_id=?", orderId);
        return existing;
    }
}
