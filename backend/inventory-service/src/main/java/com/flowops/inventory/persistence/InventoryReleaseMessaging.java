package com.flowops.inventory.persistence;

import com.flowops.contracts.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class InventoryReleaseMessaging {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JdbcInventoryStore store;
    private final TransactionTemplate transaction;
    private final String schema;
    public InventoryReleaseMessaging(JdbcTemplate jdbc, ObjectMapper json, JdbcInventoryStore store,
            PlatformTransactionManager manager, @Value("${spring.flyway.default-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        this.schema="\""+schema+"\"."; this.jdbc=jdbc; this.json=json; this.store=store;
        transaction=new TransactionTemplate(manager); transaction.setTimeout(15);
    }
    public void accept(String key,String raw) {
        var event=json.readValue(raw,new TypeReference<EventEnvelope<InventoryReleaseRequested>>() {});
        if (!event.eventType().equals("InventoryReleaseRequested") || !event.aggregateId().toString().equals(key))
            throw new IllegalArgumentException("Expected release keyed by order");
        try {
            try { transaction.executeWithoutResult(tx -> process(event)); }
            catch (DuplicateKeyException race) { transaction.executeWithoutResult(tx -> process(event)); }
        } catch (RuntimeException failure) {
            // Persist diagnostic evidence only after the failed stock transaction has rolled back.
            try { transaction.executeWithoutResult(tx -> jdbc.update("INSERT INTO "+schema
                    +"compensation_error(event_id,order_id,error_code) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM "+schema
                    +"compensation_error WHERE event_id=?)",event.eventId(),event.aggregateId(),failure.getClass().getSimpleName(),event.eventId())); }
            catch (RuntimeException diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        }
    }
    private void process(EventEnvelope<InventoryReleaseRequested> event) {
        store.lockOrder(event.aggregateId());
        String canonical=json.writeValueAsString(event);
        var seen=jdbc.queryForList("SELECT event_json FROM "+schema+"processed_event WHERE event_id=?",String.class,event.eventId());
        if (!seen.isEmpty()) {
            if (!canonical.equals(seen.getFirst())) throw new IllegalArgumentException("Release event ID reused");
            return;
        }
        String request=json.writeValueAsString(event.payload());
        var previous=jdbc.queryForList("SELECT request_json,correlation_id FROM "+schema+"inventory_release WHERE order_id=?",event.aggregateId());
        if (!previous.isEmpty()) {
            if (!request.equals(previous.getFirst().get("request_json")) || !event.correlationId().equals(previous.getFirst().get("correlation_id")))
                throw new IllegalArgumentException("Conflicting release request");
            inbox(event,canonical); return;
        }
        var statuses=jdbc.queryForList("SELECT status FROM "+schema+"reservation WHERE order_id=?",String.class,event.aggregateId());
        int decisions=jdbc.queryForObject("SELECT count(*) FROM "+schema+"inventory_decision WHERE order_id=?",Integer.class,event.aggregateId());
        if (!statuses.isEmpty() || decisions!=0) {
            int matches=jdbc.queryForObject("SELECT count(*) FROM "+schema+"event_outbox WHERE event_id=? AND aggregate_id=? AND correlation_id=? AND event_type='InventoryReserved'",
                    Integer.class,event.payload().reservationEventId(),event.aggregateId(),event.correlationId());
            if (matches!=1) throw new IllegalArgumentException("Release does not reference this reservation decision");
        }
        var outcome=statuses.isEmpty() ? InventoryReleased.Outcome.ABSENT_PREVENTED
                : statuses.getFirst().equals("RELEASED") ? InventoryReleased.Outcome.ALREADY_RELEASED : InventoryReleased.Outcome.RELEASED;
        store.releaseInTransaction(event.aggregateId());
        jdbc.update("INSERT INTO "+schema+"inventory_release(order_id,request_event_id,correlation_id,request_json,outcome) VALUES (?,?,?,?,?)",
                event.aggregateId(),event.eventId(),event.correlationId(),request,outcome.name());
        var result=new EventEnvelope<>(UUID.randomUUID(),"InventoryReleased",1,event.aggregateId(),event.correlationId(),Instant.now(),
                new InventoryReleased(event.eventId(),event.payload().reservationEventId(),outcome));
        jdbc.update("INSERT INTO "+schema+"event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,'InventoryReleased',?,'InventoryRelease')",
                result.eventId(),event.aggregateId(),event.correlationId(),json.writeValueAsString(result));
        inbox(event,canonical);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("event=inventory_released eventId={} orderId={} correlationId={} outcome={}",event.eventId(),event.aggregateId(),event.correlationId(),outcome);
    }
    private void inbox(EventEnvelope<?> event,String canonical) {
        jdbc.update("INSERT INTO "+schema+"processed_event(event_id,aggregate_id,event_json) VALUES (?,?,?)",event.eventId(),event.aggregateId(),canonical);
    }
}
