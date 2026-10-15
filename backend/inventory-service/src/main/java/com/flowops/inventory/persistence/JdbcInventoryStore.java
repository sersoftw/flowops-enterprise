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
            return transaction.execute(tx -> {
                var existing = readReservation(request.orderId(), false);
                if (existing.isPresent()) return replay(existing.get(), request);
                // The unique order key serializes contenders before any stock is changed.
                jdbc.update("INSERT INTO " + schema + ".reservation (order_id, status) VALUES (?, 'RESERVED')", request.orderId());
                // Domain canonicalization orders all stock locks consistently across reservations/releases.
                for (var line : request.lines()) {
                    saveStock(lockStock(line.productId()).reserve(line.quantity()));
                    jdbc.update("INSERT INTO " + schema + ".reservation_line (order_id, product_id, quantity) VALUES (?, ?, ?)",
                            request.orderId(), line.productId(), line.quantity());
                }
                return request;
            });
        } catch (DuplicateKeyException ex) {
            // PostgreSQL aborts the losing transaction. Replay only after rollback, in a fresh transaction.
            return transaction.execute(tx -> readReservation(request.orderId(), false)
                    .map(existing -> replay(existing, request)).orElseThrow(() -> ex));
        }
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

    @Override
    public Optional<Reservation> release(UUID orderId) {
        return transaction.execute(tx -> {
            var existing = readReservation(orderId, true);
            if (existing.isEmpty() || existing.get().status() == Reservation.Status.RELEASED) return existing;
            var reservation = existing.get();
            for (var line : reservation.lines()) {
                saveStock(lockStock(line.productId()).release(line.quantity()));
            }
            jdbc.update("UPDATE " + schema + ".reservation SET status = 'RELEASED' WHERE order_id = ?", orderId);
            return Optional.of(new Reservation(orderId, Reservation.Status.RELEASED, reservation.lines()));
        });
    }
}
