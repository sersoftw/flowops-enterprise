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
        return jdbc.queryForObject("SELECT event_type FROM \"" + schemaName + "\".event_outbox WHERE aggregate_id=?",String.class,order);
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
    @Test void releasedReservationIsNotResurrectedButMissingReleaseIsNotACancellation() {
        var sku=product(5); var released=UUID.randomUUID();
        inventory.reserve(released,line(sku,2)); inventory.release(released);
        messaging.accept(released.toString(),event(released,UUID.randomUUID(),sku,2));
        assertEquals("InventoryRejected",result(released)); assertEquals(new Stock(sku,5,0),stock(sku));
        var missing=UUID.randomUUID();
        assertTrue(inventory.release(missing).isEmpty());
        messaging.accept(missing.toString(),event(missing,UUID.randomUUID(),sku,2));
        assertEquals("InventoryReserved",result(missing)); assertEquals(new Stock(sku,3,2),stock(sku));
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
}
