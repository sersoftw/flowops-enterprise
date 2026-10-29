package com.flowops.orders;

import com.flowops.contracts.*;
import com.flowops.orders.application.*;
import com.flowops.orders.domain.Order;
import com.flowops.orders.messaging.OutboxRelay;
import com.flowops.orders.persistence.OrderMessaging;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

abstract class OrderMessagingContract {
    @Autowired OrderService orders;
    @Autowired OrderMessaging messaging;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void clean() {
        for (var table : List.of("saga_error","saga_compensation","saga_history","processed_event","event_outbox","order_item","purchase_order")) jdbc.update("DELETE FROM orders." + table);
    }
    Order create(String key) {
        return orders.create(key, UUID.fromString("00000000-0000-0000-0000-000000000001"), "EUR",
                List.of(new Order.Item("sku",2,new BigDecimal("1.00")), new Order.Item("sku",3,new BigDecimal("2.00")))).order();
    }
    UUID eventId(Order order) { return jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE aggregate_id=? AND event_type='OrderCreated'",UUID.class,order.id()); }
    String result(Order order, String type, UUID id) {
        Object payload = type.equals("InventoryReserved") ? new InventoryReserved(eventId(order))
                : new InventoryRejected(eventId(order),InventoryRejected.Reason.INSUFFICIENT_STOCK);
        return json.writeValueAsString(new EventEnvelope<>(id,type,1,order.id(),order.id(),Instant.now(),payload));
    }

    UUID probe(Order order) {
        UUID id=UUID.randomUUID();
        // Storage-only fixture, not a new production event contract.
        jdbc.update("INSERT INTO orders.event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json) VALUES (?,?,?,'ArchitectureProbe',?)",
                id,order.id(),order.id(),json.writeValueAsString(Map.of("eventId",id)));
        return id;
    }

    @Test void multipleEventsPerAggregateRetainLogicalAndPhysicalUniqueness() {
        var order=create("multiple"); UUID first=probe(order); probe(order);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox WHERE aggregate_id=?",Integer.class,order.id()));
        assertEquals(order,create("multiple"));
        for (String logicalKey : Arrays.asList("OrderCreated",null,"bypass")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                    "INSERT INTO orders.event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,'OrderCreated','{}',?)",
                    UUID.randomUUID(),order.id(),order.id(),logicalKey));
        }
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                "INSERT INTO orders.event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json) VALUES (?,?,?,'ArchitectureProbe','{}')",
                first,UUID.randomUUID(),order.id()));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox",Integer.class));
    }

    @Test void causationMustReferenceTheOriginalCreatedEventNotAnotherOutboxRow() {
        var order=create("causality"); UUID unrelated=probe(order); probe(order);
        var invalid=new EventEnvelope<>(UUID.randomUUID(),"InventoryReserved",1,order.id(),order.id(),Instant.now(),new InventoryReserved(unrelated));
        assertThrows(IllegalArgumentException.class,()->messaging.accept(order.id().toString(),json.writeValueAsString(invalid)));
        var wrongCorrelation=new EventEnvelope<>(UUID.randomUUID(),"InventoryReserved",1,order.id(),UUID.randomUUID(),Instant.now(),new InventoryReserved(eventId(order)));
        assertThrows(IllegalArgumentException.class,()->messaging.accept(order.id().toString(),json.writeValueAsString(wrongCorrelation)));
        assertEquals(Order.Status.PENDING,orders.get(order.id()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders.processed_event",Integer.class));
        String valid=result(order,"InventoryReserved",UUID.randomUUID());
        messaging.accept(order.id().toString(),valid); messaging.accept(order.id().toString(),valid);
        assertEquals(Order.Status.INVENTORY_RESERVED,orders.get(order.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders.processed_event",Integer.class));
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox",Integer.class));
    }

    @Test @SuppressWarnings("unchecked") void relayMarksIndividualEventsAndRetriesOnlyTheFailedRow() {
        var order=create("multi-relay");
        jdbc.update("UPDATE orders.event_outbox SET published_at=CURRENT_TIMESTAMP WHERE event_id=?",eventId(order));
        probe(order); probe(order);
        KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var relay=new OutboxRelay(jdbc,kafka,manager,"orders");
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        assertTrue(relay.publishNext());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox WHERE published_at IS NULL",Integer.class));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("offline")));
        assertThrows(IllegalStateException.class,relay::publishNext);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox WHERE published_at IS NULL",Integer.class));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        assertTrue(relay.publishNext()); assertFalse(relay.publishNext());
        var sent=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka,times(3)).send(eq("flowops.orders.v1"),eq(order.id().toString()),sent.capture());
        assertNotEquals(sent.getAllValues().get(0),sent.getAllValues().get(1));
        assertEquals(sent.getAllValues().get(1),sent.getAllValues().get(2));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox WHERE published_at IS NOT NULL",Integer.class));
    }

    @Test void creationAndReplayWriteExactlyOneOutboxWithSummedRequirements() {
        var order=create("once"); assertEquals(order,create("once"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox",Integer.class));
        var event=json.readTree(jdbc.queryForObject("SELECT event_json FROM orders.event_outbox",String.class));
        assertEquals(order.id().toString(),event.get("correlationId").asText());
        assertEquals(5,event.at("/payload/items/0/quantity").asInt());
        assertEquals(1,event.at("/payload/items").size());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders.purchase_order SET status=NULL WHERE id=?",order.id()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE orders.purchase_order SET status='PAID' WHERE id=?",order.id()));
    }
    @Test void outboxFailureRollsBackTheOrderAndIdempotencyKey() {
        jdbc.execute("ALTER TABLE orders.event_outbox ADD CONSTRAINT reject_test CHECK (event_type <> 'OrderCreated')");
        try {
            assertThrows(RuntimeException.class,()->create("rollback"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders.purchase_order",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders.event_outbox",Integer.class));
        } finally { jdbc.execute("ALTER TABLE orders.event_outbox DROP CONSTRAINT reject_test"); }
        assertNotNull(create("rollback"));
    }
    @Test @SuppressWarnings("unchecked") void relayRetriesAfterFailureAndAfterSendBeforeCommitWindow() {
        var order=create("relay");
        KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        var relay=new OutboxRelay(jdbc,kafka,manager,"orders");
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("offline")));
        assertThrows(IllegalStateException.class,relay::publishNext);
        assertNull(jdbc.queryForObject("SELECT published_at FROM orders.event_outbox",Object.class));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        jdbc.execute("ALTER TABLE orders.event_outbox ADD CONSTRAINT no_mark_test CHECK (published_at IS NULL)");
        try { assertThrows(RuntimeException.class,relay::publishNext); }
        finally { jdbc.execute("ALTER TABLE orders.event_outbox DROP CONSTRAINT no_mark_test"); }
        assertNull(jdbc.queryForObject("SELECT published_at FROM orders.event_outbox",Object.class));
        assertTrue(relay.publishNext()); assertFalse(relay.publishNext());
        verify(kafka,times(3)).send(eq("flowops.orders.v1"),eq(order.id().toString()),anyString());
    }
    @Test void duplicateAndContradictoryResultsNeverRegressTheOrder() {
        var order=create("result"); String raw=result(order,"InventoryReserved",UUID.randomUUID());
        messaging.accept(order.id().toString(),raw); messaging.accept(order.id().toString(),raw);
        messaging.accept(order.id().toString(),result(order,"InventoryRejected",UUID.randomUUID()));
        assertEquals(Order.Status.INVENTORY_RESERVED,orders.get(order.id()).status());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM orders.processed_event",Integer.class));
        assertEquals(Order.Status.INVENTORY_RESERVED,create("result").status());
    }
    @Test void prematureOrUnrelatedResultsAreNotAcknowledged() {
        var id=UUID.randomUUID();
        var premature=new EventEnvelope<>(UUID.randomUUID(),"InventoryReserved",1,id,id,Instant.now(),new InventoryReserved(UUID.randomUUID()));
        assertThrows(IllegalStateException.class,()->messaging.accept(id.toString(),json.writeValueAsString(premature)));
        var order=create("unrelated");
        var bad=new EventEnvelope<>(UUID.randomUUID(),"InventoryReserved",1,order.id(),order.id(),Instant.now(),new InventoryReserved(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->messaging.accept(order.id().toString(),json.writeValueAsString(bad)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders.processed_event",Integer.class));
        assertEquals(Order.Status.PENDING,orders.get(order.id()).status());
    }

    @Test void inventoryConfirmationAtomicallyRequestsServerComputedPaymentOnce() {
        var order=create("payment-once"); UUID inventoryId=UUID.randomUUID();
        String confirmed=result(order,"InventoryReserved",inventoryId);
        messaging.accept(order.id().toString(),confirmed);
        messaging.accept(order.id().toString(),confirmed);
        messaging.accept(order.id().toString(),result(order,"InventoryReserved",UUID.randomUUID()));
        var request=json.readTree(jdbc.queryForObject("SELECT event_json FROM orders.event_outbox WHERE event_type='PaymentRequested'",String.class));
        assertEquals("8.00",request.at("/payload/amount").decimalValue().setScale(2).toString());
        assertEquals("EUR",request.at("/payload/currency").asText());
        assertEquals(inventoryId.toString(),request.at("/payload/causationId").asText());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders.event_outbox",Integer.class));
        for(String logical:Arrays.asList(null,"PaymentRequested","wrong")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                    "INSERT INTO orders.event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,'PaymentRequested','{}',?)",
                    UUID.randomUUID(),order.id(),order.id(),logical));
        }
    }
    @Test void paymentRequestFailureRollsBackInventoryTransitionAndInbox() {
        var order=create("payment-rollback"); String confirmed=result(order,"InventoryReserved",UUID.randomUUID());
        jdbc.execute("ALTER TABLE orders.event_outbox ADD CONSTRAINT reject_payment_test CHECK (event_type <> 'PaymentRequested')");
        try {
            assertThrows(RuntimeException.class,()->messaging.accept(order.id().toString(),confirmed));
            assertEquals(Order.Status.PENDING,orders.get(order.id()).status());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders.processed_event",Integer.class));
        } finally {jdbc.execute("ALTER TABLE orders.event_outbox DROP CONSTRAINT reject_payment_test");}
        messaging.accept(order.id().toString(),confirmed);
        assertEquals(Order.Status.INVENTORY_RESERVED,orders.get(order.id()).status());
    }
    String paymentResult(Order order,UUID cause,boolean success) {
        Object payload=success ? new PaymentSucceeded(cause,UUID.randomUUID()) : new PaymentFailed(cause,UUID.randomUUID(),PaymentFailed.Reason.SIMULATED_DECLINE);
        return json.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(),success ? "PaymentSucceeded" : "PaymentFailed",1,order.id(),order.id(),Instant.now(),payload));
    }
    @Test void paymentResultsRequireTheirRequestAndNeverRegressTerminalState() {
        for(boolean success:List.of(true,false)) {
            var order=create("payment-result-"+success);
            assertThrows(IllegalArgumentException.class,()->messaging.accept(order.id().toString(),paymentResult(order,eventId(order),success)));
            assertEquals(Order.Status.PENDING,orders.get(order.id()).status());
            messaging.accept(order.id().toString(),result(order,"InventoryReserved",UUID.randomUUID()));
            UUID cause=jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE aggregate_id=? AND event_type='PaymentRequested'",UUID.class,order.id());
            String outcome=paymentResult(order,cause,success);
            messaging.accept(order.id().toString(),outcome); messaging.accept(order.id().toString(),outcome);
            messaging.accept(order.id().toString(),paymentResult(order,cause,!success));
            messaging.accept(order.id().toString(),result(order,"InventoryReserved",UUID.randomUUID()));
            messaging.accept(order.id().toString(),result(order,"InventoryRejected",UUID.randomUUID()));
            assertEquals(success ? Order.Status.COMPLETED : Order.Status.MANUAL_REVIEW,orders.get(order.id()).status());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders.event_outbox WHERE aggregate_id=? AND event_type='PaymentRequested'",Integer.class,order.id()));
        }
    }

    UUID failPayment(Order order) {
        messaging.accept(order.id().toString(),result(order,"InventoryReserved",UUID.randomUUID()));
        UUID request=jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE aggregate_id=? AND event_type='PaymentRequested'",UUID.class,order.id());
        messaging.accept(order.id().toString(),paymentResult(order,request,false));
        return jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE aggregate_id=? AND event_type='InventoryReleaseRequested'",UUID.class,order.id());
    }
    String releaseResult(Order order,UUID request,InventoryReleased.Outcome outcome) {
        var original=json.readTree(jdbc.queryForObject("SELECT event_json FROM orders.event_outbox WHERE event_id=?",String.class,request));
        UUID reservation=UUID.fromString(original.at("/payload/reservationEventId").asText());
        return json.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(),"InventoryReleased",1,order.id(),order.id(),Instant.now(),new InventoryReleased(request,reservation,outcome)));
    }
    @Test void failedPaymentCancelsOnlyAfterMatchingReleaseAndKeepsHistory() {
        var order=create("saga"); UUID request=failPayment(order);
        assertEquals(Order.Status.RELEASING_INVENTORY,orders.get(order.id()).status());
        String confirmed=releaseResult(order,request,InventoryReleased.Outcome.RELEASED);
        String unrelated=confirmed.replace(request.toString(),UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->messaging.accept(order.id().toString(),unrelated));
        var wrongReservation=(tools.jackson.databind.node.ObjectNode)json.readTree(confirmed);
        ((tools.jackson.databind.node.ObjectNode)wrongReservation.get("payload")).put("reservationEventId",UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->messaging.accept(order.id().toString(),json.writeValueAsString(wrongReservation)));
        assertEquals(Order.Status.RELEASING_INVENTORY,orders.get(order.id()).status());
        messaging.accept(order.id().toString(),confirmed); messaging.accept(order.id().toString(),confirmed);
        assertEquals(Order.Status.CANCELLED,orders.get(order.id()).status());
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM orders.saga_compensation WHERE order_id=?",String.class,order.id()));
        assertEquals(List.of("PENDING","INVENTORY_RESERVED","PAYMENT_FAILED_PENDING_COMPENSATION","RELEASING_INVENTORY","CANCELLED"),
                jdbc.queryForList("SELECT to_state FROM orders.saga_history WHERE order_id=? ORDER BY id",String.class,order.id()));
        messaging.retryCompensations(Instant.now().plusSeconds(10000));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM orders.saga_compensation WHERE order_id=?",Integer.class,order.id()));
    }
    @Test void boundedRetriesSurviveHandlerRestartAndLateReleaseRequiresManualReview() {
        var order=create("timeout"); UUID request=failPayment(order);
        String before=jdbc.queryForObject("SELECT event_json FROM orders.event_outbox WHERE event_id=?",String.class,request);
        var restarted=new OrderMessaging(jdbc,json,manager,"orders",60);
        Instant first=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(120);
        restarted.retryCompensations(first); restarted.retryCompensations(first);
        assertEquals(2,jdbc.queryForObject("SELECT attempts FROM orders.saga_compensation",Integer.class));
        restarted.retryCompensations(first.plusSeconds(60)); restarted.retryCompensations(first.plusSeconds(120));
        assertEquals(Order.Status.MANUAL_REVIEW,orders.get(order.id()).status());
        assertEquals(3,jdbc.queryForObject("SELECT attempts FROM orders.saga_compensation",Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT abandoned_at FROM orders.event_outbox WHERE event_id=?",Object.class,request));
        assertEquals(before,jdbc.queryForObject("SELECT event_json FROM orders.event_outbox WHERE event_id=?",String.class,request));
        messaging.accept(order.id().toString(),releaseResult(order,request,InventoryReleased.Outcome.RELEASED));
        assertEquals(Order.Status.MANUAL_REVIEW,orders.get(order.id()).status());
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM orders.saga_error",Integer.class)>=3);
    }
    @Test void missingExpectedReservationDoesNotPretendCompensationWasSafe() {
        var order=create("absent"); UUID request=failPayment(order);
        messaging.accept(order.id().toString(),releaseResult(order,request,InventoryReleased.Outcome.ABSENT_PREVENTED));
        assertEquals(Order.Status.MANUAL_REVIEW,orders.get(order.id()).status());
    }
    @Test void releaseOutboxFailureRollsBackFailureTransitionHistoryAndInbox() {
        var order=create("release-rollback"); messaging.accept(order.id().toString(),result(order,"InventoryReserved",UUID.randomUUID()));
        UUID cause=jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE event_type='PaymentRequested'",UUID.class);
        String failure=paymentResult(order,cause,false);
        jdbc.execute("ALTER TABLE orders.event_outbox ADD CONSTRAINT no_release_test CHECK (event_type<>'InventoryReleaseRequested')");
        try {
            assertThrows(RuntimeException.class,()->messaging.accept(order.id().toString(),failure));
            assertEquals(Order.Status.INVENTORY_RESERVED,orders.get(order.id()).status());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM orders.saga_history",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders.saga_compensation",Integer.class));
        } finally {jdbc.execute("ALTER TABLE orders.event_outbox DROP CONSTRAINT no_release_test");}
        messaging.accept(order.id().toString(),failure);
        assertEquals(Order.Status.RELEASING_INVENTORY,orders.get(order.id()).status());
    }
    @Test void approvedOrdersAndInventoryRejectionsNeverRequestCompensationOrAnotherPayment() {
        var rejected=create("no-stock"); messaging.accept(rejected.id().toString(),result(rejected,"InventoryRejected",UUID.randomUUID()));
        assertEquals(Order.Status.CANCELLED,orders.get(rejected.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders.event_outbox WHERE aggregate_id=?",Integer.class,rejected.id()));
        var paid=create("paid"); messaging.accept(paid.id().toString(),result(paid,"InventoryReserved",UUID.randomUUID()));
        UUID request=jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE aggregate_id=? AND event_type='PaymentRequested'",UUID.class,paid.id());
        messaging.accept(paid.id().toString(),paymentResult(paid,request,true));
        messaging.accept(paid.id().toString(),paymentResult(paid,request,false));
        messaging.retryCompensations(Instant.now().plusSeconds(10000));
        assertEquals(Order.Status.COMPLETED,orders.get(paid.id()).status());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders.event_outbox WHERE event_type='InventoryReleaseRequested'",Integer.class));
    }
    @Test void concurrentContradictoryPaymentResultsNeverCompensateACompletedOrder() throws Exception {
        var order=create("race-results"); messaging.accept(order.id().toString(),result(order,"InventoryReserved",UUID.randomUUID()));
        UUID cause=jdbc.queryForObject("SELECT event_id FROM orders.event_outbox WHERE event_type='PaymentRequested'",UUID.class);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(boolean success:List.of(true,false)) {
                String raw=paymentResult(order,cause,success);
                futures.add(pool.submit(()->{gate.await();messaging.accept(order.id().toString(),raw);return null;}));
            }
            gate.countDown();for(var f:futures)f.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        var state=orders.get(order.id()).status();
        assertTrue(state==Order.Status.COMPLETED || state==Order.Status.MANUAL_REVIEW);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders.event_outbox WHERE event_type='InventoryReleaseRequested' AND abandoned_at IS NULL",Integer.class));
    }
}
