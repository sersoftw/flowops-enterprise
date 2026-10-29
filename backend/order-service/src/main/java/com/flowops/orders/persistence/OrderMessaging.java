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
    private final long retrySeconds;

    public OrderMessaging(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager,
                          @Value("${spring.flyway.default-schema}") String schema,
                          @Value("${flowops.saga.retry-seconds:60}") long retrySeconds) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        if (retrySeconds < 1) throw new IllegalArgumentException("Retry interval must be positive");
        this.retrySeconds=retrySeconds;
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
                + "(event_id, aggregate_id, correlation_id, event_type, event_json, logical_event_key) VALUES (?, ?, ?, ?, ?, 'OrderCreated')",
                event.eventId(), event.aggregateId(), event.correlationId(), event.eventType(), json.writeValueAsString(event));
        history(order.id(),event.eventId(),null,"PENDING","ORDER_CREATED");
    }

    private record Incoming(EventEnvelope<?> event, UUID causation, UUID reservation) { }
    private Incoming decode(String raw) {
        String type=json.readTree(raw).path("eventType").asText();
        return switch(type) {
            case "InventoryReserved" -> { var e=json.readValue(raw,new TypeReference<EventEnvelope<InventoryReserved>>() {}); yield new Incoming(e,e.payload().causationId(),null); }
            case "InventoryRejected" -> { var e=json.readValue(raw,new TypeReference<EventEnvelope<InventoryRejected>>() {}); yield new Incoming(e,e.payload().causationId(),null); }
            case "PaymentSucceeded" -> { var e=json.readValue(raw,new TypeReference<EventEnvelope<PaymentSucceeded>>() {}); yield new Incoming(e,e.payload().causationId(),null); }
            case "PaymentFailed" -> { var e=json.readValue(raw,new TypeReference<EventEnvelope<PaymentFailed>>() {}); yield new Incoming(e,e.payload().causationId(),null); }
            case "InventoryReleased" -> { var e=json.readValue(raw,new TypeReference<EventEnvelope<InventoryReleased>>() {}); yield new Incoming(e,e.payload().causationId(),e.payload().reservationEventId()); }
            default -> throw new IllegalArgumentException("Expected inventory/payment result");
        };
    }
    public void accept(String key,String raw) {
        var incoming=decode(raw); var e=incoming.event();
        if (!e.aggregateId().toString().equals(key)) throw new IllegalArgumentException("Kafka key must match aggregateId");
        try { transaction.executeWithoutResult(tx -> apply(incoming)); }
        catch (RuntimeException failure) {
            if (e.eventType().equals("InventoryReleased")) {
                try { transaction.executeWithoutResult(tx -> error(e.aggregateId(),e.eventId(),"INVALID_RELEASE_OR_TRANSACTION_FAILURE")); }
                catch (RuntimeException diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            }
            throw failure;
        }
    }
    private void apply(Incoming incoming) {
        var e=incoming.event(); UUID order=e.aggregateId(); String type=e.eventType();
        var states=jdbc.queryForList("SELECT status FROM "+schema+"purchase_order WHERE id=? FOR UPDATE",String.class,order);
        if (states.isEmpty()) throw new IllegalStateException("Order not yet present; retry without offset commit");
        String expectedType=type.equals("InventoryReleased") ? "InventoryReleaseRequested" : type.startsWith("Payment") ? "PaymentRequested" : "OrderCreated";
        var expected=jdbc.queryForList("SELECT event_json FROM "+schema+"event_outbox WHERE event_id=? AND event_type=? AND aggregate_id=? AND correlation_id=?",
                String.class,incoming.causation(),expectedType,order,e.correlationId());
        if (expected.isEmpty()) throw new IllegalArgumentException("Missing or unrelated causal request");
        if (type.equals("InventoryReleased")) {
            var release=json.readValue(expected.getFirst(),new TypeReference<EventEnvelope<InventoryReleaseRequested>>() {});
            if (!release.payload().reservationEventId().equals(incoming.reservation())) throw new IllegalArgumentException("Wrong reservation confirmation");
        }
        String canonical=json.writeValueAsString(e);
        var seen=jdbc.queryForList("SELECT aggregate_id,event_json FROM "+schema+"processed_event WHERE event_id=?",e.eventId());
        if (!seen.isEmpty()) {
            var row=seen.getFirst();
            if (!order.equals(row.get("aggregate_id")) || (row.get("event_json")!=null && !canonical.equals(row.get("event_json"))))
                throw new IllegalArgumentException("Event ID reused with conflicting content");
            return;
        }
        var current=Order.Status.valueOf(states.getFirst());
        var target=com.flowops.orders.domain.OrderSaga.next(current,type);
        if (current==Order.Status.RELEASING_INVENTORY && type.equals("InventoryReleased")
                && ((InventoryReleased)e.payload()).outcome()==InventoryReleased.Outcome.ABSENT_PREVENTED) target=Order.Status.MANUAL_REVIEW;
        if (target!=current) {
            if (target==Order.Status.RELEASING_INVENTORY) {
                history(order,e.eventId(),current.name(),"PAYMENT_FAILED_PENDING_COMPENSATION","PaymentFailed");
                history(order,e.eventId(),"PAYMENT_FAILED_PENDING_COMPENSATION",target.name(),"COMPENSATION_REQUESTED");
            } else history(order,e.eventId(),current.name(),target.name(),type);
            jdbc.update("UPDATE "+schema+"purchase_order SET status=? WHERE id=?",target.name(),order);
            if (target==Order.Status.INVENTORY_RESERVED) {
                var amounts=jdbc.queryForMap("SELECT total,currency FROM "+schema+"purchase_order WHERE id=?",order);
                outbox(new EventEnvelope<>(UUID.randomUUID(),"PaymentRequested",1,order,e.correlationId(),java.time.Instant.now(),
                        new PaymentRequested(e.eventId(),(java.math.BigDecimal)amounts.get("total"),(String)amounts.get("currency"))));
            } else if (target==Order.Status.RELEASING_INVENTORY) {
                var payment=json.readValue(expected.getFirst(),new TypeReference<EventEnvelope<PaymentRequested>>() {});
                var request=new EventEnvelope<>(UUID.randomUUID(),"InventoryReleaseRequested",1,order,e.correlationId(),java.time.Instant.now(),
                        new InventoryReleaseRequested(e.eventId(),payment.payload().causationId()));
                outbox(request);
                jdbc.update("INSERT INTO "+schema+"saga_compensation(order_id,request_event_id,attempts,next_retry_at,status) VALUES (?,?,1,?,'ACTIVE')",
                        order,request.eventId(),java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(retrySeconds)));
            } else if (target==Order.Status.CANCELLED && type.equals("InventoryReleased")) {
                jdbc.update("UPDATE "+schema+"saga_compensation SET status='CONFIRMED',last_error=NULL WHERE order_id=?",order);
            } else if (target==Order.Status.MANUAL_REVIEW) {
                abandon(order,e.eventId(),"CONTRADICTORY_COMPENSATION_EVIDENCE");
            }
        } else {
            history(order,e.eventId(),current.name(),current.name(),"IGNORED_"+type);
            if (type.equals("InventoryReleased") || (current==Order.Status.COMPLETED && type.equals("PaymentFailed")))
                error(order,e.eventId(),"LATE_OR_CONTRADICTORY_RESULT");
        }
        jdbc.update("INSERT INTO "+schema+"processed_event(event_id,aggregate_id,event_json) VALUES (?,?,?)",e.eventId(),order,canonical);
        LoggerFactory.getLogger(getClass()).info("event=saga_transition eventId={} orderId={} correlationId={} from={} to={}",e.eventId(),order,e.correlationId(),current,target);
    }
    private void outbox(EventEnvelope<?> event) {
        jdbc.update("INSERT INTO "+schema+"event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,?,?,?)",
                event.eventId(),event.aggregateId(),event.correlationId(),event.eventType(),json.writeValueAsString(event),event.eventType());
    }
    private void history(UUID order,UUID event,String from,String to,String reason) {
        jdbc.update("INSERT INTO "+schema+"saga_history(order_id,event_id,from_state,to_state,reason) VALUES (?,?,?,?,?)",order,event,from,to,reason);
    }
    private void error(UUID order,UUID event,String reason) {
        jdbc.update("INSERT INTO "+schema+"saga_error(order_id,event_id,error_code) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM "+schema+"saga_error WHERE event_id=? AND error_code=?)",
                order,event,reason,event,reason);
    }
    private void abandon(UUID order,UUID event,String reason) {
        jdbc.update("UPDATE "+schema+"saga_compensation SET status='MANUAL_REVIEW',last_error=? WHERE order_id=?",reason,order);
        jdbc.update("UPDATE "+schema+"event_outbox SET abandoned_at=CURRENT_TIMESTAMP WHERE aggregate_id=? AND event_type='InventoryReleaseRequested'",order);
        error(order,event,reason);
    }
    /** Durable timeout scan; row lock serializes it with confirmations and other scanners. */
    public void retryCompensations(java.time.Instant now) {
        var orders=jdbc.queryForList("SELECT order_id FROM "+schema+"saga_compensation WHERE status='ACTIVE' AND next_retry_at<=? ORDER BY next_retry_at LIMIT 100",
                UUID.class,java.sql.Timestamp.from(now));
        for(UUID order:orders) transaction.executeWithoutResult(tx -> {
            String state=jdbc.queryForObject("SELECT status FROM "+schema+"purchase_order WHERE id=? FOR UPDATE",String.class,order);
            var rows=jdbc.queryForList("SELECT request_event_id,attempts FROM "+schema+"saga_compensation WHERE order_id=? AND status='ACTIVE' AND next_retry_at<=?",
                    order,java.sql.Timestamp.from(now));
            if (!state.equals("RELEASING_INVENTORY") || rows.isEmpty()) return;
            var row=rows.getFirst(); UUID request=(UUID)row.get("request_event_id"); int attempts=((Number)row.get("attempts")).intValue();
            if (attempts>=3) {
                jdbc.update("UPDATE "+schema+"purchase_order SET status='MANUAL_REVIEW' WHERE id=?",order);
                history(order,request,state,"MANUAL_REVIEW","COMPENSATION_TIMEOUT");
                abandon(order,request,"COMPENSATION_TIMEOUT");
            } else {
                jdbc.update("UPDATE "+schema+"event_outbox SET published_at=NULL WHERE event_id=? AND abandoned_at IS NULL",request);
                jdbc.update("UPDATE "+schema+"saga_compensation SET attempts=attempts+1,next_retry_at=?,last_error='CONFIRMATION_TIMEOUT' WHERE order_id=?",
                        java.sql.Timestamp.from(now.plusSeconds(retrySeconds)),order);
                history(order,request,state,state,"RETRY_"+(attempts+1));
                error(order,request,"CONFIRMATION_TIMEOUT_"+attempts);
            }
        });
    }
}
