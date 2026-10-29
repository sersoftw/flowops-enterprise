package com.flowops.orders.domain;

/** Only the orchestrator advances states; transport validation must precede this decision. */
public final class OrderSaga {
    private OrderSaga() { }
    public static Order.Status next(Order.Status current,String event) {
        return switch (current) {
            case PENDING -> switch(event) {
                case "InventoryReserved" -> Order.Status.INVENTORY_RESERVED;
                case "InventoryRejected" -> Order.Status.CANCELLED;
                default -> current;
            };
            case INVENTORY_RESERVED -> switch(event) {
                case "PaymentSucceeded" -> Order.Status.COMPLETED;
                case "PaymentFailed" -> Order.Status.RELEASING_INVENTORY;
                default -> current;
            };
            case RELEASING_INVENTORY -> switch(event) {
                case "InventoryReleased" -> Order.Status.CANCELLED;
                case "PaymentSucceeded" -> Order.Status.MANUAL_REVIEW;
                default -> current;
            };
            default -> current;
        };
    }
}
