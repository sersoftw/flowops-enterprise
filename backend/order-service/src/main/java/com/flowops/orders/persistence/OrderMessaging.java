package com.flowops.orders.persistence;

import com.flowops.contracts.*;
import com.flowops.orders.domain.Order;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderMessaging {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final String schema;

    public OrderMessaging(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager,
                          @Value("${spring.flyway.default-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        this.schema = "\"" + schema + "\".";
        this.jdbc = jdbc; this.json = json; this.transaction = new TransactionTemplate(manager);
    }

    // Called inside the order creation transaction. Broker availability cannot affect HTTP creation.
    public void created(Order order) {
        var quantities = new TreeMap<String, Integer>();
        order.items().forEach(item -> quantities.merge(item.productId(), item.quantity(), Math::addExact));
        var payload = new OrderCreated(quantities.entrySet().stream()
                .map(e -> new OrderCreated.Item(e.getKey(), e.getValue())).toList());
        var event = new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", 1, order.id(), order.id(), order.createdAt(), payload);
        jdbc.update("INSERT INTO " + schema + "event_outbox "
                + "(event_id, aggregate_id, correlation_id, event_type, event_json) VALUES (?, ?, ?, ?, ?)",
                event.eventId(), event.aggregateId(), event.correlationId(), event.eventType(), json.writeValueAsString(event));
    }

    public void accept(String key, String raw) {
        String type = json.readTree(raw).path("eventType").asText();
        UUID eventId; UUID orderId; UUID correlation; UUID causation;
        if ("InventoryReserved".equals(type)) {
            var event = json.readValue(raw, new TypeReference<EventEnvelope<InventoryReserved>>() {});
            eventId = event.eventId(); orderId = event.aggregateId(); correlation = event.correlationId(); causation = event.payload().causationId();
        } else if ("InventoryRejected".equals(type)) {
            var event = json.readValue(raw, new TypeReference<EventEnvelope<InventoryRejected>>() {});
            eventId = event.eventId(); orderId = event.aggregateId(); correlation = event.correlationId(); causation = event.payload().causationId();
        } else { throw new IllegalArgumentException("Expected an inventory result"); }
        if (!orderId.toString().equals(key)) throw new IllegalArgumentException("Kafka key must match aggregateId");
        transaction.executeWithoutResult(tx -> {
            var statuses = jdbc.queryForList("SELECT status FROM " + schema + "purchase_order WHERE id=? FOR UPDATE", String.class, orderId);
            if (statuses.isEmpty()) throw new IllegalStateException("Order not yet present; retry without committing offset");
            var expected = jdbc.queryForList("SELECT event_id FROM " + schema + "event_outbox WHERE aggregate_id=? AND correlation_id=?",
                    UUID.class, orderId, correlation);
            if (expected.isEmpty() || !expected.getFirst().equals(causation)) throw new IllegalArgumentException("Unrelated inventory result");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + "processed_event WHERE event_id=?", Integer.class, eventId) != 0) return;
            String target = "InventoryReserved".equals(type) ? "INVENTORY_RESERVED" : "INVENTORY_REJECTED";
            // First valid outcome wins. Delayed contradictory outcomes never regress state.
            if ("PENDING".equals(statuses.getFirst())) {
                jdbc.update("UPDATE " + schema + "purchase_order SET status=? WHERE id=?", target, orderId);
            } else if (!target.equals(statuses.getFirst())) {
                LoggerFactory.getLogger(getClass()).warn("event=conflicting_inventory_result eventId={} orderId={} correlationId={}", eventId, orderId, correlation);
            }
            jdbc.update("INSERT INTO " + schema + "processed_event(event_id,aggregate_id) VALUES (?,?)", eventId, orderId);
            LoggerFactory.getLogger(getClass()).info("event=inventory_result_applied eventId={} orderId={} correlationId={} result={}", eventId, orderId, correlation, target);
        });
    }
}
