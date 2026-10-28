package com.flowops.payment.persistence;

import com.flowops.contracts.*;
import com.flowops.payment.domain.*;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentMessaging {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final String schema;

    public PaymentMessaging(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager,
                            @Value("${spring.flyway.default-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        this.schema = "\"" + schema + "\".";
        this.jdbc = jdbc; this.json = json; transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
    }

    public void accept(String key, String raw) {
        var event = json.readValue(raw, new TypeReference<EventEnvelope<PaymentRequested>>() {});
        if (!"PaymentRequested".equals(event.eventType()) || !event.aggregateId().toString().equals(key))
            throw new IllegalArgumentException("Expected PaymentRequested keyed by order");
        // Normalize before comparing; JSON field order and insignificant decimal zeros are irrelevant.
        String canonical = json.writeValueAsString(event);
        try { transaction.executeWithoutResult(tx -> process(event, canonical)); }
        catch (DuplicateKeyException race) {
            // A competing insert must roll back before reading the committed winner.
            transaction.executeWithoutResult(tx -> process(event, canonical));
        }
    }

    private void process(EventEnvelope<PaymentRequested> event, String canonical) {
        if (alreadyProcessed(event, canonical)) return;
        var request = event.payload();
        var existing = jdbc.queryForList("SELECT correlation_id,causation_id,amount,currency FROM " + schema + "payment WHERE order_id=? FOR UPDATE", event.aggregateId());
        if (!existing.isEmpty()) {
            // Another delivery may have committed its inbox while we waited for this row lock.
            if (alreadyProcessed(event, canonical)) return;
            var row = existing.getFirst();
            if (!event.correlationId().equals(row.get("correlation_id")) || !request.causationId().equals(row.get("causation_id"))
                    || request.amount().compareTo((java.math.BigDecimal) row.get("amount")) != 0 || !request.currency().equals(row.get("currency")))
                throw new IllegalArgumentException("Conflicting payment request for existing order");
            inbox(event, canonical);
            return;
        }
        var payment = PaymentSimulator.simulate(event.aggregateId(), request.amount(), request.currency(), Instant.now());
        var attempt = new PaymentAttempt(UUID.randomUUID(), payment.id(), payment.status(), 1, payment.createdAt());
        jdbc.update("INSERT INTO " + schema + "payment(id,order_id,request_event_id,correlation_id,causation_id,amount,currency,status,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                payment.id(), payment.orderId(), event.eventId(), event.correlationId(), request.causationId(), payment.amount(), payment.currency(), payment.status().name(), java.sql.Timestamp.from(payment.createdAt()));
        jdbc.update("INSERT INTO " + schema + "payment_attempt(id,payment_id,outcome,simulator_version,created_at) VALUES (?,?,?,?,?)",
                attempt.id(), attempt.paymentId(), attempt.outcome().name(), attempt.simulatorVersion(), java.sql.Timestamp.from(attempt.createdAt()));
        boolean succeeded = payment.status() == Payment.Status.SUCCEEDED;
        Object payload = succeeded ? new PaymentSucceeded(event.eventId(), payment.id())
                : new PaymentFailed(event.eventId(), payment.id(), PaymentFailed.Reason.SIMULATED_DECLINE);
        var result = new EventEnvelope<>(UUID.randomUUID(), succeeded ? "PaymentSucceeded" : "PaymentFailed", 1,
                event.aggregateId(), event.correlationId(), payment.createdAt(), payload);
        jdbc.update("INSERT INTO " + schema + "event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,?,?,'PaymentDecision')",
                result.eventId(), result.aggregateId(), result.correlationId(), result.eventType(), json.writeValueAsString(result));
        inbox(event, canonical);
        LoggerFactory.getLogger(getClass()).info("event=payment_decided eventId={} orderId={} correlationId={} paymentId={} outcome={}",
                event.eventId(), event.aggregateId(), event.correlationId(), payment.id(), payment.status());
    }
    private boolean alreadyProcessed(EventEnvelope<PaymentRequested> event, String canonical) {
        var seen = jdbc.queryForList("SELECT request_json FROM " + schema + "processed_event WHERE event_id=?", String.class, event.eventId());
        if (seen.isEmpty()) return false;
        if (!seen.getFirst().equals(canonical)) throw new IllegalArgumentException("Event ID reused with different content");
        return true;
    }
    private void inbox(EventEnvelope<PaymentRequested> event, String canonical) {
        jdbc.update("INSERT INTO " + schema + "processed_event(event_id,aggregate_id,request_json) VALUES (?,?,?)", event.eventId(), event.aggregateId(), canonical);
    }
}
