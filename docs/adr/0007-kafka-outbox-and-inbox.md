# ADR-0007: First asynchronous order/inventory flow

**Status:** Implemented for Day 4, pending review.

## Contracts and boundaries

The shared-contracts module contains transport records and JSON Schema 2020-12 files
only. Three strict v1 envelopes carry eventId, eventType, eventVersion, aggregateId,
correlationId, occurredAt and payload. No Java class/type headers are required:
Kafka keys and values use String serializers; values are JSON. Unknown versions,
invalid payloads and mismatched keys are rejected before mutation.

OrderCreated is sent to flowops.orders.v1; InventoryReserved/InventoryRejected to
flowops.inventory.v1. Both topics have three partitions and one local-development
replica. The key is the order UUID, equal to aggregateId. Kafka ordering applies
within a partition, not between the two topics. Product lines are sorted and
duplicate product IDs in the HTTP order are summed for inventory; prices remain
in Order Service. Result payloads include causationId, the original OrderCreated
eventId. A correlation UUID is generated with the order (currently its order ID),
returned in X-Correlation-Id, persisted in the outbox and propagated unchanged.
Logs include eventId, orderId and correlationId as structured key/value fields.

The JSON schemas are closed contracts: changing required fields, payload meaning
or accepted structure requires a new version/topic and an explicit rollout.
Unknown versions are not interpreted as v1. There is no schema registry.

## Local atomicity and delivery

Order persistence and OrderCreated outbox insertion use the same JPA transaction
and DataSource. Replays of an HTTP idempotency key do not write another event.
The canonical request comparison still excludes mutable status. V3 adds messaging
tables; V4 discovers/replaces V2's database-generated status constraint without
editing the merged migration. Older orders are not automatically backfilled with
events: only newly created orders enter the Day 4 flow.

Inventory stores a unique order decision, processed event ID, reservation changes
and result outbox record in one local transaction. It locks all stock rows in stable
product order and validates every line before writing balances, so expected business
rejection can be committed with its inbox/outbox without partial stock writes.
The existing internal reserve/release API retains its independent transaction
boundary; the message processor uses an explicit operation inside its own transaction.
No service reads another service's schema.

The order decision is durable even for rejection. Exact duplicates and new event
IDs carrying the same order requirements/correlation cannot reserve again or
reconsider rejection after replenishment. Conflicting content for that order is a
protocol error. Duplicate unique-key races roll back before replay in a fresh
transaction. Event IDs are immutable and globally unique by contract.

Each service has its own small relay implementation; shared-contracts does not
become a shared infrastructure library. A relay locks one pending row using
FOR UPDATE SKIP LOCKED, sends the stored JSON/key, waits for the broker's acks=all
confirmation, then marks it published and commits. A failure keeps it pending.
A crash between broker acknowledgement and database commit can send it again
with the same eventId. Kafka producer idempotence helps transport retries, but
does not remove that crash window. This is at-least-once delivery, not end-to-end
exactly once. Pending rows and published history are retained; cleanup is deferred.

Listeners disable auto-commit and use RECORD acknowledgement: only a successful
database handler return allows offset commit. Failed processing retries indefinitely
with backoff, including unknown versions and premature results. The initial policy
deliberately blocks the affected partition instead of discarding data; operators
must investigate malformed/unrelated events. DLT and recovery tooling belong to
later work. Database transactions and Kafka offset commits are not a single transaction.

## Results, ordering and cancellation limitations

Order Service locks its order row, verifies the original causation/correlation,
and changes PENDING to INVENTORY_RESERVED or INVENTORY_REJECTED together with the
processed-event record. A result for an absent order retries without marking the
event processed. Repeated results are harmless. A later contradictory result is
recorded/logged and does not reverse the first valid outcome. This is valid for
Day 4's one durable inventory decision per order; it is not a general saga/version
ordering algorithm and must be revisited before additional state transitions.

No release/cancellation event is implemented. An existing RELEASED reservation is
never resurrected by OrderCreated. However, release of a nonexistent reservation
still returns empty and writes no cancellation tombstone: a later OrderCreated
CAN reserve stock. There is no claim of out-of-order cancellation safety.
Payments, compensation, reservation expiry and Day 5 behavior are absent.

## Validation and operation

Fast tests exercise schema contracts, order/outbox rollback, relay send/mark failure,
inventory decision/outbox rollback, duplicate races, stable rejection, conflicting
results and the missing-release limitation. PostgreSQL runs the same inventory
contract with default and alternate schemas under restricted owners.

The integration-tests module uses Failsafe and PostgreSQL/Kafka Testcontainers.
It launches the packaged order and inventory JARs as separate JVMs, seeds products
through the inventory test role, creates orders over HTTP and observes real Kafka
records, stock and eventual HTTP status. It stops/restarts the inventory process
with queued messages, replays events and restarts the order relay with a pending
published event. Each application uses its own restricted database role.
Run root clean verify (not an isolated integration module build) to package services
first. CI retains Surefire/Failsafe reports and both process logs.

Messaging is enabled in normal deployments. MESSAGING_ENABLED=false disables
listeners/relays for isolated development; order creation still writes its outbox.
Unit/service tests explicitly disable transports; KafkaFlowIT enables them.
Compose keeps its existing listener topology and default inventory schema.
