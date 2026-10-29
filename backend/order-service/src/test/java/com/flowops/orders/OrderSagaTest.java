package com.flowops.orders;
import com.flowops.orders.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OrderSagaTest {
    @Test void terminalStatesCannotBeRegressedByAnyKnownResult() {
        for(var state:java.util.List.of(Order.Status.COMPLETED,Order.Status.CANCELLED,Order.Status.MANUAL_REVIEW))
            for(String event:java.util.List.of("InventoryReserved","InventoryRejected","PaymentSucceeded","PaymentFailed","InventoryReleased"))
                assertEquals(state,OrderSaga.next(state,event));
    }
    @Test void compensationRequiresFailureAndReleaseConfirmation() {
        assertEquals(Order.Status.RELEASING_INVENTORY,OrderSaga.next(Order.Status.INVENTORY_RESERVED,"PaymentFailed"));
        assertEquals(Order.Status.CANCELLED,OrderSaga.next(Order.Status.RELEASING_INVENTORY,"InventoryReleased"));
        assertEquals(Order.Status.PENDING,OrderSaga.next(Order.Status.PENDING,"InventoryReleased"));
        assertEquals(Order.Status.MANUAL_REVIEW,OrderSaga.next(Order.Status.RELEASING_INVENTORY,"PaymentSucceeded"));
    }
}
