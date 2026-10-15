# ADR-0005: Order domain, persistence and HTTP idempotency

**Status:** Implemented for Day 2; pending human review.

## Decision

Keep immutable Order and Item records free of framework dependencies. The application
depends on an OrderStore port. A JPA adapter owns transactions and maps separate persistence
entities; REST exposes validated request/response DTOs. Only the orders schema changes.
Flyway V2 adds orders/items, constraints and indexes; Hibernate remains in validate mode.

Orders begin in PENDING. No transitions or messaging are implemented in this issue.
Each order has a customer UUID, an uppercase ISO currency and 1–100 ordered line items.
Quantity is 1–10000, unit price is 0.01–999999999.99 with at most two decimal places.
BigDecimal computes totals on the server; clients cannot submit a total. Prices are supplied
by the caller for this portfolio API: there is no trusted catalog or payment authorization yet.
All supported currencies use two decimal places in this intentionally limited API.

POST requires an opaque, case-sensitive Idempotency-Key of 1–128 ASCII letters, digits,
periods, underscores, colons or hyphens. Keys are globally unique within this service and
retained with the order indefinitely. There is no authenticated tenant scope on Day 2.
Creation returns 201; replay returns 200, the same order and Location, and
Idempotency-Replayed: true. A key with different validated content returns 409.
Comparison includes customer, currency and ordered items, with prices normalized to two
decimal places. JSON whitespace/property order and equivalent decimal values do not matter;
item ordering and exact product IDs do. Invalid requests always return 400, including replays.

The unique key and aggregate are committed in one transaction. Concurrent inserts are
arbitrated by the database. A loser rolls back before reading the winning transaction in
a new transaction; unrelated integrity failures are rethrown. No cache or process lock
is used. Orders cannot be changed/deleted through this API, so the persisted aggregate is
also the canonical request for replay comparison. Revisit this before adding mutable fields.

List uses bounded offset pagination (page 0–1000000, size 1–100), optional customerId and
status filters, and createdAt DESC/id DESC ordering. Each response includes totalElements
and totalPages. Offset pagination is not a snapshot across concurrent insertions.
Errors use application/problem+json; validation errors include field paths without rejected values.
OpenAPI is maintained as a static versioned contract, avoiding an extra runtime library.
CreateOrder and Item reject undeclared properties in both OpenAPI and JSON deserialization.
The response schema declares its own properties instead of extending the closed request
with allOf. Product IDs cannot contain NUL characters: Bean Validation returns a field-level
400 and the domain independently enforces this invariant before PostgreSQL persistence.
Rejected requests do not reserve an idempotency key, so callers can correct and retry them.

## Validation

Fast H2 tests cover wiring, migration and the HTTP contract. The same HTTP contract runs
against PostgreSQL 17.6 with `-Dpostgres.tests=true`; CI requires it, and Docker failures
are not silently skipped. Compose additionally checks restricted application roles and
HTTP read/replay after restarting order-service. PostgreSQL Testcontainers uses its isolated
test role; production-like role isolation remains the separate Compose test's responsibility.
Regression tests validate the published schemas using a test-only JSON Schema 2020-12
validator, including unknown order/item properties and complete valid responses. The shared
H2/PostgreSQL HTTP suite covers NUL at the beginning, middle and end of a product ID,
absence of persisted orders after rejection, and corrected retries with the same key.

## Deferred

No inventory/payment logic, Kafka publication, outbox, saga, authentication or Day 3 work.
Future event publication must follow ADR-0003 and define versioned contracts first.
