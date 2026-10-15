package com.flowops.inventory.persistence;

import com.flowops.contracts.*;
import com.flowops.inventory.application.InventoryStore;
import com.flowops.inventory.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class InventoryMessaging {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JdbcInventoryStore store;
    private final TransactionTemplate transaction;
    private final String schema;

    public InventoryMessaging(JdbcTemplate jdbc, ObjectMapper json, JdbcInventoryStore store,
                              PlatformTransactionManager manager, @Value("${spring.flyway.default-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        this.schema = "\"" + schema + "\".";
        this.jdbc = jdbc; this.json = json; this.store = store; this.transaction = new TransactionTemplate(manager);
    }

    public void accept(String key, String raw) {
        var event = json.readValue(raw, new TypeReference<EventEnvelope<OrderCreated>>() {});
        if (!"OrderCreated".equals(event.eventType()) || !event.aggregateId().toString().equals(key)) {
            throw new IllegalArgumentException("Expected OrderCreated keyed by aggregateId");
        }
        try {
            transaction.executeWithoutResult(tx -> process(event));
        } catch (DuplicateKeyException race) {
            // A competing order decision or event ID won. The failed transaction must roll back first.
            transaction.executeWithoutResult(tx -> process(event));
        }
    }

    private void process(EventEnvelope<OrderCreated> event) {
        var seen = jdbc.queryForList("SELECT aggregate_id FROM " + schema + "processed_event WHERE event_id=?", UUID.class, event.eventId());
        if (!seen.isEmpty()) {
            if (!seen.getFirst().equals(event.aggregateId())) throw new IllegalArgumentException("Event ID reused for another order");
            return;
        }
        String request = json.writeValueAsString(event.payload());
        var decisions = jdbc.queryForList("SELECT request_json, correlation_id FROM " + schema + "inventory_decision WHERE order_id=? FOR UPDATE",
                event.aggregateId());
        if (!decisions.isEmpty()) {
            var decision = decisions.getFirst();
            if (!request.equals(decision.get("request_json")) || !event.correlationId().equals(decision.get("correlation_id"))) {
                throw new IllegalArgumentException("OrderCreated conflicts with the durable inventory decision");
            }
            markProcessed(event);
            return;
        }
        jdbc.update("INSERT INTO " + schema + "inventory_decision(order_id,correlation_id,request_json,result_type) VALUES (?,?,?,'InventoryReserved')",
                event.aggregateId(), event.correlationId(), request);
        InventoryRejected.Reason rejection = null;
        try {
            var reservation = store.reserveInTransaction(new Reservation(event.aggregateId(), Reservation.Status.RESERVED,
                    event.payload().items().stream().map(i -> new ReservationLine(i.productId(), i.quantity())).toList()));
            if (reservation.status() == Reservation.Status.RELEASED) rejection = InventoryRejected.Reason.RESERVATION_RELEASED;
        } catch (Stock.InsufficientStock ex) {
            rejection = InventoryRejected.Reason.INSUFFICIENT_STOCK;
        } catch (InventoryStore.ProductNotFound ex) {
            rejection = InventoryRejected.Reason.PRODUCT_NOT_FOUND;
        } catch (InventoryStore.Conflict ex) {
            rejection = InventoryRejected.Reason.RESERVATION_CONFLICT;
        }
        String type = rejection == null ? "InventoryReserved" : "InventoryRejected";
        Object payload = rejection == null ? new InventoryReserved(event.eventId()) : new InventoryRejected(event.eventId(), rejection);
        var result = new EventEnvelope<>(UUID.randomUUID(), type, 1, event.aggregateId(), event.correlationId(), Instant.now(), payload);
        jdbc.update("UPDATE " + schema + "inventory_decision SET result_type=? WHERE order_id=?", type, event.aggregateId());
        jdbc.update("INSERT INTO " + schema + "event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json) VALUES (?,?,?,?,?)",
                result.eventId(), result.aggregateId(), result.correlationId(), result.eventType(), json.writeValueAsString(result));
        markProcessed(event);
        LoggerFactory.getLogger(getClass()).info("event=inventory_decision eventId={} orderId={} correlationId={} result={}",
                event.eventId(), event.aggregateId(), event.correlationId(), type);
    }

    private void markProcessed(EventEnvelope<?> event) {
        jdbc.update("INSERT INTO " + schema + "processed_event(event_id,aggregate_id) VALUES (?,?)", event.eventId(), event.aggregateId());
    }
}
