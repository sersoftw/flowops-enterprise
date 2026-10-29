package com.flowops.inventory;

import com.flowops.contracts.*;
import com.flowops.inventory.domain.*;
import com.flowops.inventory.persistence.InventoryMessaging;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

abstract class InventoryMessagingContract extends InventoryPersistenceContract {
    @Autowired InventoryMessaging messaging;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    String event(UUID order, UUID id, String product, int quantity) {
        return json.writeValueAsString(new EventEnvelope<>(id,"OrderCreated",1,order,order,Instant.now(),
                new OrderCreated(List.of(new OrderCreated.Item(product,quantity)))));
    }
    int count(String table, UUID order) {
        assertTrue(schemaName.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"));
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schemaName + "\"." + table
                + " WHERE " + (table.equals("inventory_decision") ? "order_id" : "aggregate_id") + "=?",Integer.class,order);
    }
    String result(UUID order) {
        return jdbc.queryForObject("SELECT event_type FROM \"" + schemaName + "\".event_outbox WHERE aggregate_id=? AND logical_event_key='InventoryDecision'",String.class,order);
    }

    UUID probe(UUID order) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schemaName + "\".event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json) VALUES (?,?,?,'ArchitectureProbe',?)",
                id,order,order,json.writeValueAsString(Map.of("eventId",id)));
        return id;
    }

    @Test void multipleOutboxEventsPreserveExactlyOneInventoryDecision() {
        for (int quantity : List.of(2,9)) {
            var sku=product(5); var order=UUID.randomUUID(); var raw=event(order,UUID.randomUUID(),sku,quantity);
            messaging.accept(order.toString(),raw); UUID probeId=probe(order); probe(order);
            messaging.accept(order.toString(),raw);
            messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,quantity));
            assertEquals(3,count("event_outbox",order)); assertEquals(1,count("inventory_decision",order));
            assertEquals(quantity==2 ? new Stock(sku,3,2) : new Stock(sku,5,0),stock(sku));
            for (String type : List.of("InventoryReserved","InventoryRejected")) {
                for (String key : Arrays.asList("InventoryDecision",null,"bypass")) {
                    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                            "INSERT INTO \"" + schemaName + "\".event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json,logical_event_key) VALUES (?,?,?,?, '{}',?)",
                            UUID.randomUUID(),order,order,type,key));
                }
            }
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
                    "INSERT INTO \"" + schemaName + "\".event_outbox(event_id,aggregate_id,correlation_id,event_type,event_json) VALUES (?,?,?,'ArchitectureProbe','{}')",
                    probeId,UUID.randomUUID(),order));
            assertEquals(3,count("event_outbox",order));
        }
    }

    @Test @SuppressWarnings("unchecked") void inventoryRelayPublishesEachRowWithoutMarkingSiblingEvents() {
        String table="\"" + schemaName + "\".event_outbox";
        // Drain unrelated fixtures in this isolated test database before selecting pending rows.
        jdbc.update("UPDATE "+table+" SET published_at=CURRENT_TIMESTAMP WHERE published_at IS NULL");
        var order=UUID.randomUUID(); probe(order); probe(order);
        org.springframework.kafka.core.KafkaTemplate<String,String> kafka=org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        var relay=new com.flowops.inventory.messaging.OutboxRelay(jdbc,kafka,manager,schemaName);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        assertTrue(relay.publishNext());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE aggregate_id=? AND published_at IS NULL",Integer.class,order));
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("offline")));
        assertThrows(IllegalStateException.class,relay::publishNext);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE aggregate_id=? AND published_at IS NULL",Integer.class,order));
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        assertTrue(relay.publishNext()); assertFalse(relay.publishNext());
        var sent=org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kafka,org.mockito.Mockito.times(3)).send(org.mockito.ArgumentMatchers.eq("flowops.inventory.v1"),
                org.mockito.ArgumentMatchers.eq(order.toString()),sent.capture());
        assertNotEquals(sent.getAllValues().get(0),sent.getAllValues().get(1));
        assertEquals(sent.getAllValues().get(1),sent.getAllValues().get(2));
    }

    @Test void concurrentDeliveryAndNewEventIdDeduplicateByOrder() throws Exception {
        var sku=product(5); var order=UUID.randomUUID(); String raw=event(order,UUID.randomUUID(),sku,3);
        var outcomes=race(()->{messaging.accept(order.toString(),raw);return true;},
                ()->{messaging.accept(order.toString(),raw);return true;});
        assertEquals(List.of(true,true),outcomes);
        messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        assertEquals(new Stock(sku,2,3),stock(sku));
        assertEquals(1,count("event_outbox",order)); assertEquals(2,count("processed_event",order));
        assertEquals("InventoryReserved",result(order));
    }
    @Test void rejectedDecisionIsStableAfterReplenishment() {
        var sku=product(1); var order=UUID.randomUUID(); var raw=event(order,UUID.randomUUID(),sku,3);
        messaging.accept(order.toString(),raw);
        inventory.replenish(sku,10);
        messaging.accept(order.toString(),raw);
        messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        assertEquals(new Stock(sku,11,0),stock(sku));
        assertEquals("InventoryRejected",result(order)); assertTrue(inventory.reservation(order).isEmpty());
        assertEquals(1,count("event_outbox",order));
    }
    @Test void publicationRecordFailureRollsBackReservationDecisionAndInbox() {
        var sku=product(5); var order=UUID.randomUUID(); var raw=event(order,UUID.randomUUID(),sku,3);
        String table="\"" + schemaName + "\".event_outbox";
        jdbc.execute("ALTER TABLE "+table+" ADD CONSTRAINT reject_message_test CHECK (aggregate_id <> '"+order+"')");
        try {
            assertThrows(RuntimeException.class,()->messaging.accept(order.toString(),raw));
            assertEquals(new Stock(sku,5,0),stock(sku));
            assertTrue(inventory.reservation(order).isEmpty());
            assertEquals(0,count("inventory_decision",order)); assertEquals(0,count("processed_event",order));
        } finally { jdbc.execute("ALTER TABLE "+table+" DROP CONSTRAINT reject_message_test"); }
        messaging.accept(order.toString(),raw);
        assertEquals(new Stock(sku,2,3),stock(sku));
    }
    @Test void releasedAndMissingReservationsCannotBeResurrected() {
        var sku=product(5); var released=UUID.randomUUID();
        inventory.reserve(released,line(sku,2)); inventory.release(released);
        messaging.accept(released.toString(),event(released,UUID.randomUUID(),sku,2));
        assertEquals("InventoryRejected",result(released)); assertEquals(new Stock(sku,5,0),stock(sku));
        var missing=UUID.randomUUID();
        assertTrue(inventory.release(missing).isEmpty());
        messaging.accept(missing.toString(),event(missing,UUID.randomUUID(),sku,2));
        assertEquals("InventoryRejected",result(missing)); assertEquals(new Stock(sku,5,0),stock(sku));
    }
    @Test void invalidKeyVersionAndConflictingContentCannotMutateStock() {
        var sku=product(5); var order=UUID.randomUUID(); var raw=event(order,UUID.randomUUID(),sku,2);
        assertThrows(RuntimeException.class,()->messaging.accept(UUID.randomUUID().toString(),raw));
        assertThrows(RuntimeException.class,()->messaging.accept(order.toString(),raw.replace("\"eventVersion\":1","\"eventVersion\":2")));
        assertEquals(0,count("inventory_decision",order)); assertEquals(new Stock(sku,5,0),stock(sku));
        messaging.accept(order.toString(),raw);
        assertThrows(IllegalArgumentException.class,()->messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,4)));
        assertEquals(new Stock(sku,3,2),stock(sku));
    }
    @Test void multilineRejectionDoesNotLeavePartialStockOrReservation() {
        var ids=List.of(product(3),product(1)).stream().sorted().toList();
        var order=UUID.randomUUID();
        var payload=new OrderCreated(List.of(new OrderCreated.Item(ids.get(0),1),new OrderCreated.Item(ids.get(1),10)));
        var before=ids.stream().map(this::stock).toList();
        var event=new EventEnvelope<>(UUID.randomUUID(),"OrderCreated",1,order,order,Instant.now(),payload);
        messaging.accept(order.toString(),json.writeValueAsString(event));
        assertEquals(before,ids.stream().map(this::stock).toList());
        assertTrue(inventory.reservation(order).isEmpty()); assertEquals("InventoryRejected",result(order));
    }

    @Autowired com.flowops.inventory.persistence.InventoryReleaseMessaging releases;
    String qualified(String table) { return "\""+schemaName+"\"."+table; }
    EventEnvelope<InventoryReleaseRequested> releaseEvent(UUID order,UUID reservation) {
        return new EventEnvelope<>(UUID.randomUUID(),"InventoryReleaseRequested",1,order,order,Instant.now(),new InventoryReleaseRequested(UUID.randomUUID(),reservation));
    }
    void release(EventEnvelope<InventoryReleaseRequested> e) { releases.accept(e.aggregateId().toString(),json.writeValueAsString(e)); }
    UUID reservedEvent(UUID order) {return jdbc.queryForObject("SELECT event_id FROM "+qualified("event_outbox")+" WHERE aggregate_id=? AND event_type='InventoryReserved'",UUID.class,order);}
    @Test void concurrentReleaseDeliveriesRestoreStockOnceAndCommitOneResult() throws Exception {
        String sku=product(5); UUID order=UUID.randomUUID(); messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        var request=releaseEvent(order,reservedEvent(order));
        assertEquals(List.of(true,true),race(()->{release(request);return true;},()->{release(request);return true;}));
        release(new EventEnvelope<>(UUID.randomUUID(),request.eventType(),1,order,order,Instant.now(),request.payload()));
        assertEquals(new Stock(sku,5,0),stock(sku));
        assertEquals(Reservation.Status.RELEASED,inventory.reservation(order).orElseThrow().status());
        assertEquals(2,count("event_outbox",order));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+qualified("inventory_release")+" WHERE order_id=?",Integer.class,order));
        messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        assertEquals(new Stock(sku,5,0),stock(sku));
    }
    @Test void earlyReleaseCreatesDurableTombstoneBeforeDelayedCreation() {
        String sku=product(5); UUID order=UUID.randomUUID(); var request=releaseEvent(order,UUID.randomUUID());
        release(request);
        assertEquals("ABSENT_PREVENTED",jdbc.queryForObject("SELECT outcome FROM "+qualified("inventory_release")+" WHERE order_id=?",String.class,order));
        messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        assertEquals("InventoryRejected",result(order)); assertTrue(inventory.reservation(order).isEmpty());
        assertThrows(com.flowops.inventory.application.InventoryStore.ReservationCancelled.class,()->inventory.reserve(order,line(sku,3)));
        assertEquals(new Stock(sku,5,0),stock(sku));
    }
    @Test void firstReserveRacingMissingReleaseCannotEscapeTombstone() throws Exception {
        for(int i=0;i<5;i++) {
            String sku=product(5); UUID order=UUID.randomUUID();
            var outcomes=race(()->{inventory.release(order);return true;},()->{
                try {inventory.reserve(order,line(sku,3));} catch(com.flowops.inventory.application.InventoryStore.ReservationCancelled expected) { }
                return true;
            });
            assertEquals(List.of(true,true),outcomes);
            assertEquals(true,jdbc.queryForObject("SELECT cancelled FROM "+qualified("reservation_guard")+" WHERE order_id=?",Boolean.class,order));
            assertEquals(new Stock(sku,5,0),stock(sku));
            assertTrue(inventory.reservation(order).isEmpty() || inventory.reservation(order).orElseThrow().status()==Reservation.Status.RELEASED);
        }
    }
    @Test void releaseResultFailureRollsBackStockTombstoneDecisionAndInbox() {
        String sku=product(5); UUID order=UUID.randomUUID(); messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        var request=releaseEvent(order,reservedEvent(order));
        jdbc.execute("ALTER TABLE "+qualified("event_outbox")+" ADD CONSTRAINT no_release_test CHECK (event_type<>'InventoryReleased' OR aggregate_id<>'"+order+"')");
        try {
            assertThrows(RuntimeException.class,()->release(request));
            assertEquals(new Stock(sku,2,3),stock(sku));
            assertEquals(false,jdbc.queryForObject("SELECT cancelled FROM "+qualified("reservation_guard")+" WHERE order_id=?",Boolean.class,order));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+qualified("inventory_release")+" WHERE order_id=?",Integer.class,order));
            assertEquals(1,count("processed_event",order));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM "+qualified("compensation_error")+" WHERE order_id=?",Integer.class,order));
        } finally {jdbc.execute("ALTER TABLE "+qualified("event_outbox")+" DROP CONSTRAINT no_release_test");}
        release(request);assertEquals(new Stock(sku,5,0),stock(sku));
    }
    @Test void incorrectReservationOrCorrelationCannotReleaseStock() {
        String sku=product(5); UUID order=UUID.randomUUID(); messaging.accept(order.toString(),event(order,UUID.randomUUID(),sku,3));
        assertThrows(IllegalArgumentException.class,()->release(releaseEvent(order,UUID.randomUUID())));
        var valid=releaseEvent(order,reservedEvent(order));
        assertThrows(IllegalArgumentException.class,()->release(new EventEnvelope<>(valid.eventId(),valid.eventType(),1,order,UUID.randomUUID(),valid.occurredAt(),valid.payload())));
        assertEquals(new Stock(sku,2,3),stock(sku));
        release(valid);
        assertThrows(IllegalArgumentException.class,()->release(releaseEvent(order,reservedEvent(order))));
        assertEquals(new Stock(sku,5,0),stock(sku));
    }
}
