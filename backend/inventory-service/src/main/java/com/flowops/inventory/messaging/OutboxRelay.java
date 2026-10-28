package com.flowops.inventory.messaging;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="flowops.messaging.enabled", havingValue="true", matchIfMissing=true)
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String,String> kafka;
    private final TransactionTemplate transaction;
    private final String table;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String,String> kafka, PlatformTransactionManager manager,
                       @Value("${spring.flyway.default-schema}") String schema) {
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid schema");
        table = "\"" + schema + "\".event_outbox";
        this.jdbc = jdbc; this.kafka = kafka; transaction = new TransactionTemplate(manager);
        transaction.setTimeout(20);
    }

    @Scheduled(fixedDelayString="${flowops.messaging.relay-delay-ms:500}", initialDelayString="${flowops.messaging.relay-delay-ms:500}")
    public void poll() {
        try { publishNext(); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("event=outbox_retry reason={}", failure.toString());
        }
    }

    public boolean publishNext() {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var rows = jdbc.queryForList("SELECT event_id,aggregate_id,correlation_id,event_json FROM " + table
                    + " WHERE published_at IS NULL ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");
            if (rows.isEmpty()) return false;
            var row = rows.getFirst();
            UUID id = (UUID) row.get("event_id");
            try {
                kafka.send("flowops.inventory.v1",
                        row.get("aggregate_id").toString(), (String) row.get("event_json")).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Relay interrupted", interrupted);
            } catch (Exception failure) { throw new IllegalStateException("Kafka did not acknowledge the outbox event", failure); }
            // A crash after send but before commit can duplicate delivery: stable eventId + consumer inbox handle it.
            jdbc.update("UPDATE " + table + " SET published_at=CURRENT_TIMESTAMP WHERE event_id=?", id);
            LoggerFactory.getLogger(getClass()).info("event=outbox_published eventId={} orderId={} correlationId={}",
                    id, row.get("aggregate_id"), row.get("correlation_id"));
            return true;
        }));
    }
}
