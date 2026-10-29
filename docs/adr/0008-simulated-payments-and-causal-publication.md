# ADR-0008: Simulated payments and causal publication

**Status:** Implemented for Day 5; pending review.

Day 6 supersedes the missing-release limitation and adds compensation as described in
[ADR-0009](0009-orchestrated-compensation-and-cancellation.md). The sections below
record the original day's baseline.

## Scope and contracts

Payment Service owns `payments` (the established deployment schema, despite Issue #5's
singular wording). DB_SCHEMA can select another validated schema. No service reads
another owner's tables. Payment and PaymentAttempt are framework-free records;
PaymentMessaging is the JDBC transaction/transport adapter. Shared contracts contain
transport DTOs and closed JSON Schema v1 definitions only.

Order Service emits PaymentRequested on `flowops.payment-requests.v1` only when a valid
InventoryReserved causes PENDING -> INVENTORY_RESERVED. The state, inbox and request
outbox commit together. Amount comes from the stored server-computed order total,
never a result event. Existing prices remain caller-provided in this portfolio API;
this is not trusted catalog pricing or authorization for real money.

PaymentRequested carries amount, currency and causationId = accepted InventoryReserved
ID. PaymentSucceeded/PaymentFailed on `flowops.payments.v1` carry paymentId and
causationId = original PaymentRequested ID. All envelopes preserve aggregateId (order),
correlationId, eventId, version and occurredAt. Topics use three partitions keyed by
order UUID. Separate request topics keep Inventory's OrderCreated-only consumer intact.
No card fields, bank SDK, external provider or secret is introduced.

## Determinism, atomicity and retries

Simulator version 1 approves a positive total <= 1000.00 and rejects a larger total,
in every supported currency, with SIMULATED_DECLINE. BigDecimal validates at most two
fractional digits without rounding. This arbitrary threshold is a test mechanism,
not a financial rule. Store the outcome and simulator version; changing future code
must not re-evaluate an existing payment.

A unique payment.order_id arbitrates competing deliveries; payment_attempt.payment_id
is also unique. One payment, one attempt and one decision outbox are persisted with
the inbox in a single local transaction. Unique insert races roll back before a fresh
transaction compares the durable winner. Exact duplicates replay; a new event ID with
the same amount/currency/causation/correlation also replays. Changed content, reused
physical IDs or different correlation/causation are protocol errors, never new payments.
The original result references the original request even when logical retries use new IDs.

The outbox preserves event_id primary keys and nullable logical singleton keys:
OrderCreated and PaymentRequested are separate Order categories; both payment outcomes
share PaymentDecision. CHECK constraints prevent NULL-key bypasses. Other future events
may still recur. Only append-only migrations modify the merged Day 4 schema.

Order accepts a payment result only when causation/correlation match its own specific
PaymentRequested outbox row. INVENTORY_RESERVED -> COMPLETED requires PaymentSucceeded.
PaymentFailed instead produces PAYMENT_FAILED_PENDING_COMPENSATION; reserved stock stays
reserved. Duplicate or delayed contradictory outcomes cannot regress terminal states or
emit a second request. Premature results lacking the causal request fail without inbox
commit. As before, malformed/unrelated events retry indefinitely and block their partition;
DLT/recovery is deferred. Producers are trusted in this local unauthenticated Kafka setup.

## Publication order

All three outboxes have an identity publication_sequence plus an aggregate/pending/sequence
index. A relay selects a pending row only if no lower pending sequence exists for that
aggregate, then locks it with FOR UPDATE SKIP LOCKED. A competing relay sees the earlier
uncommitted publication as pending and cannot skip ahead within that aggregate; unrelated
aggregates remain eligible. Send acknowledgement precedes published_at commit. Tests hold
one publisher open while another attempts to publish the next event.

Identity allocation alone is not a general commit-order guarantee. Current writers are
serialized per aggregate: order creation precedes visibility, subsequent Order writes lock
the order row, inventory decisions use a unique order decision, and payment decisions use
a unique payment order. Future writers must preserve that serialization. Migration assigns
sequence values to historical rows without reconstructing historical causal order; Day 4
has only one production event per aggregate per outbox. Migration deployment must stop old
relay binaries before starting new ones, or old publishers could bypass this ordering rule.

Order routes each event to its designated topic. No total ordering is claimed across topics.
Handlers enforce causation and monotonic state transitions. A lost acknowledgement or crash
after Kafka send can still duplicate an earlier event, including a delayed duplicate after
later events; inboxes and durable decisions remain necessary. This is at-least-once delivery,
not end-to-end exactly once. Failed sends/marking leave the original ID/JSON pending.

## Deferred and operational risks

No payment retries as new attempts, real charges, refund, compensation, cancellation or
reservation expiry is implemented. Release of a nonexistent inventory reservation still
writes no tombstone: a later OrderCreated can reserve. This limitation is unchanged, and
Day 5 does not claim out-of-order cancellation safety. Existing Day 4 orders already in
INVENTORY_RESERVED are not automatically backfilled with payment requests; use fresh orders
for this rollout. Manual data repair is outside this PR. Outbox/inbox retention is unbounded.

Validation includes shared H2/PostgreSQL transaction suites, restricted alternate payment
schema ownership, concurrent duplicates, rollback, causal transitions and relay failure/order
regressions. Packaged three-service Kafka/PostgreSQL tests cover success, decline, persisted
consumer restart, duplicate requests and publication retry after a real send/DB mark failure.
The Compose HTTP listing smoke test no longer assumes asynchronous state stays PENDING.
