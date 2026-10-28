package com.flowops.payment;

import com.flowops.contracts.*;
import com.flowops.payment.persistence.PaymentMessaging;
import com.flowops.payment.messaging.OutboxRelay;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

abstract class PaymentMessagingContract {
    @Autowired PaymentMessaging handler;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Value("${spring.flyway.default-schema}") String schema;
    String table(String name) { return "\""+schema+"\"."+name; }
    @BeforeEach void clean() {
        for (String t : List.of("processed_event","event_outbox","payment_attempt","payment")) jdbc.update("DELETE FROM "+table(t));
    }
    EventEnvelope<PaymentRequested> request(String amount) {
        return new EventEnvelope<>(UUID.randomUUID(),"PaymentRequested",1,UUID.randomUUID(),UUID.randomUUID(),Instant.now(),
                new PaymentRequested(UUID.randomUUID(),new BigDecimal(amount),"EUR"));
    }
    void accept(EventEnvelope<PaymentRequested> event) { handler.accept(event.aggregateId().toString(),json.writeValueAsString(event)); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table(table),Integer.class); }
    @Test void approvedAndDeclinedPersistExactlyOneAttemptAndCausalResult() {
        for (String amount : List.of("1000.00","1000.01")) {
            var event=request(amount); accept(event);
            String expected=amount.equals("1000.00") ? "SUCCEEDED" : "FAILED";
            assertEquals(expected,jdbc.queryForObject("SELECT status FROM "+table("payment")+" WHERE order_id=?",String.class,event.aggregateId()));
            var result=json.readTree(jdbc.queryForObject("SELECT event_json FROM "+table("event_outbox")+" WHERE aggregate_id=?",String.class,event.aggregateId()));
            assertEquals(event.eventId().toString(),result.at("/payload/causationId").asText());
            assertEquals(event.correlationId().toString(),result.get("correlationId").asText());
            assertEquals(event.aggregateId().toString(),result.get("aggregateId").asText());
            assertEquals(expected.equals("SUCCEEDED") ? "PaymentSucceeded" : "PaymentFailed",result.get("eventType").asText());
        }
        assertEquals(2,count("payment_attempt")); assertEquals(2,count("processed_event"));
    }
    @Test void exactAndLogicalDuplicatesSurviveHandlerRecreation() {
        var event=request("2.00"); accept(event); accept(event);
        var restarted=new PaymentMessaging(jdbc,json,manager,schema);
        restarted.accept(event.aggregateId().toString(),json.writeValueAsString(event));
        accept(new EventEnvelope<>(UUID.randomUUID(),event.eventType(),1,event.aggregateId(),event.correlationId(),Instant.now(),event.payload()));
        assertEquals(1,count("payment")); assertEquals(1,count("payment_attempt")); assertEquals(1,count("event_outbox")); assertEquals(2,count("processed_event"));
    }
    @Test void conflictingContentAndReusedEventIdsAreRejected() {
        var event=request("2.00"); accept(event);
        for (UUID id : List.of(event.eventId(),UUID.randomUUID())) {
            var conflict=new EventEnvelope<>(id,event.eventType(),1,event.aggregateId(),event.correlationId(),event.occurredAt(),
                    new PaymentRequested(event.payload().causationId(),new BigDecimal("3.00"),"EUR"));
            assertThrows(IllegalArgumentException.class,()->accept(conflict));
        }
        var unrelated=new EventEnvelope<>(event.eventId(),event.eventType(),1,UUID.randomUUID(),event.correlationId(),event.occurredAt(),event.payload());
        assertThrows(IllegalArgumentException.class,()->accept(unrelated));
        assertEquals(1,count("payment_attempt")); assertEquals(1,count("processed_event"));
    }
    @Test void concurrentDeliveriesAndNewEventIdsCannotPayTwice() throws Exception {
        var event=request("2.00"); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) {
                var delivery=new EventEnvelope<>(i<3 ? event.eventId() : UUID.randomUUID(),event.eventType(),1,event.aggregateId(),event.correlationId(),event.occurredAt(),event.payload());
                futures.add(pool.submit(()-> { gate.await(); accept(delivery); return null; }));
            }
            gate.countDown(); for(var f:futures) f.get(20,TimeUnit.SECONDS);
        }
        assertEquals(1,count("payment")); assertEquals(1,count("payment_attempt")); assertEquals(1,count("event_outbox")); assertEquals(4,count("processed_event"));
    }
    @Test void outboxFailureRollsBackPaymentAttemptAndInbox() {
        var event=request("2.00");
        jdbc.execute("ALTER TABLE "+table("event_outbox")+" ADD CONSTRAINT reject_test CHECK (event_type <> 'PaymentSucceeded')");
        try {
            assertThrows(RuntimeException.class,()->accept(event));
            for(String t:List.of("payment","payment_attempt","event_outbox","processed_event")) assertEquals(0,count(t));
        } finally {jdbc.execute("ALTER TABLE "+table("event_outbox")+" DROP CONSTRAINT reject_test");}
        accept(event); assertEquals(1,count("payment_attempt"));
    }
    @Test void malformedKeyAndVersionNeverMutateState() {
        var event=request("2.00"); var raw=json.writeValueAsString(event);
        assertThrows(IllegalArgumentException.class,()->handler.accept("wrong",raw));
        assertThrows(RuntimeException.class,()->handler.accept(event.aggregateId().toString(),raw.replace("\"eventVersion\":1","\"eventVersion\":2")));
        assertEquals(0,count("payment"));
    }
    @Test void logicalDecisionConstraintRejectsContradictoryAndNullKeyBypasses() {
        var event=request("2.00"); accept(event);
        for(String key:Arrays.asList(null,"wrong","PaymentDecision")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                    "INSERT INTO "+table("event_outbox")+"(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,'PaymentFailed','{}',?)",
                    UUID.randomUUID(),event.aggregateId(),event.correlationId(),key));
        }
    }
    @Test @SuppressWarnings("unchecked") void relayRetriesStableEventAfterBrokerAndMarkFailures() {
        var event=request("2.00"); accept(event);
        KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var relay=new OutboxRelay(jdbc,kafka,manager,schema);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("offline")));
        assertThrows(IllegalStateException.class,relay::publishNext);
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        jdbc.execute("ALTER TABLE "+table("event_outbox")+" ADD CONSTRAINT mark_test CHECK (published_at IS NULL)");
        try { assertThrows(RuntimeException.class,relay::publishNext); }
        finally { jdbc.execute("ALTER TABLE "+table("event_outbox")+" DROP CONSTRAINT mark_test"); }
        assertTrue(relay.publishNext()); assertFalse(relay.publishNext());
        var sent=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka,times(3)).send(eq("flowops.payments.v1"),eq(event.aggregateId().toString()),sent.capture());
        assertEquals(1,sent.getAllValues().stream().distinct().count());
    }
    @Test @SuppressWarnings("unchecked") void concurrentRelaysCannotOvertakePendingEventForSameAggregate() throws Exception {
        var event=request("2.00"); accept(event);
        jdbc.update("INSERT INTO "+table("event_outbox")+"(event_id,aggregate_id,correlation_id,event_type,event_json) VALUES (?,?,?,'ArchitectureProbe','{}')",
                UUID.randomUUID(),event.aggregateId(),event.correlationId());
        KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(call->{entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));return CompletableFuture.completedFuture(null);});
        var relay=new OutboxRelay(jdbc,kafka,manager,schema);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(relay::publishNext);
            try { assertTrue(entered.await(5,TimeUnit.SECONDS)); assertFalse(new OutboxRelay(jdbc,kafka,manager,schema).publishNext()); }
            finally {release.countDown();}
            assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        assertTrue(relay.publishNext()); assertFalse(relay.publishNext());
    }
}
