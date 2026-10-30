# Kafka v1 examples

These fictitious envelopes illustrate the published JSON schemas. Kafka key equals
aggregateId. eventId identifies the physical event; correlationId follows the order;
payload.causationId identifies the triggering event. reservationEventId identifies the
original InventoryReserved for release validation. Actual demo IDs are generated at runtime.

| Envelope | Topic |
| --- | --- |
| OrderCreated | flowops.orders.v1 |
| InventoryReserved / InventoryRejected / InventoryReleased | flowops.inventory.v1 |
| PaymentRequested | flowops.payment-requests.v1 |
| PaymentSucceeded / PaymentFailed | flowops.payments.v1 |
| InventoryReleaseRequested | flowops.inventory-release-requests.v1 |

The first six examples form a declined-payment causal chain. InventoryRejected and
PaymentSucceeded are alternative schema illustrations, NOT additional outcomes for that
chain; the success alternative would require an approving amount. Never publish all these
files together. Publishing orphan or contradictory messages to the application topics can
block partitions or cause MANUAL_REVIEW. Use the HTTP demo to generate coherent real events.

Consumer group IDs: flowops-order-v1, flowops-inventory-v1,
flowops-inventory-release-v1 and flowops-payment-v1. Every topic has three partitions and
one replica in the local Compose environment. No schema registry or class-name headers.
