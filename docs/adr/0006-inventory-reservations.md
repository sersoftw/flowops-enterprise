# ADR-0006: Atomic inventory reservations with database row locks

**Status:** Implemented for Day 3; pending human review.

## Boundary and internal interface

Product, Stock, Reservation and ReservationLine are immutable framework-free domain
records. InventoryService exposes createProduct, product, stock, replenish,
reserve(orderId, items), reservation and release(orderId) as an internal Java API.
No business HTTP endpoints or Kafka handlers are introduced in this issue. Future
transports must call this API and define their own contracts before integration.
Product IDs are case-sensitive opaque strings (1–128 characters, nonblank, no NUL),
compatible with order item identifiers; no foreign order tables are consulted.
Names have 1–200 characters. Quantities are positive integers; initial stock may be
zero. Total available plus reserved units cannot exceed Integer.MAX_VALUE.

Flyway V2 owns four tables exclusively in the configured inventory service schema
(`DB_SCHEMA`, default `inventory`). Primary/foreign keys, positive
line quantities, nonnegative balances, total capacity and status checks protect
persistent invariants. The order UUID is the reservation primary key, not a
cross-schema relationship. Hibernate remains in validate mode; these tables use
explicit JDBC mappings rather than JPA entities because the operations need a small,
auditable set of row locks and SQL statements. JdbcTemplate participates in Spring's
JpaTransactionManager through the same DataSource. No shared-contracts changes.

V2 uses unqualified table names, matching V1: Flyway applies it in its configured
default schema. The JDBC adapter reads that same `spring.flyway.default-schema`
property, accepts only `[A-Za-z_][A-Za-z0-9_]{0,62}`, and double-quotes the validated
identifier when qualifying its fixed table names. SQL values remain bound parameters.
No raw schema placeholder is interpolated into migration SQL, and JDBC does not rely
on the pool's search path. Existing datasource currentSchema and Hibernate/Flyway
settings continue to derive from DB_SCHEMA in application.yml.

## Transactions and concurrency

Each mutation runs in its own READ COMMITTED transaction with a 15-second statement
timeout. The boundary deliberately commits independently of any future caller's
transaction (REQUIRES_NEW); callers must not assume cross-service atomicity.
Product creation inserts product and stock together. Replenishment, reservation and
release read stock using SELECT FOR UPDATE and write balances before committing.
Domain arithmetic checks availability before decrementing; no negative stock can
be committed. Any missing product or insufficient line rolls back the entire
reservation, its lines and every earlier stock update.

Reservations contain 1–100 distinct products. Duplicate product IDs are rejected;
input order is irrelevant. Domain canonicalization sorts IDs using Java String
ordering, and every reserve/release acquires stock locks in that order. Reservation
rows are acquired before stock rows. This avoids opposite-line-order deadlocks
without a JVM mutex, even with multiple application instances. Replenishment locks
one stock row and never acquires a reservation lock. There is no automatic retry
of lock timeouts or infrastructure errors.

The unique order key is inserted before acquiring stock locks. Concurrent inserts
for the same order are serialized by PostgreSQL's primary key. A losing duplicate
insert rolls back before a fresh transaction reads and compares the winner. A
different canonical product/quantity list raises Conflict; identical requests
return the persisted reservation without changing stock. Insufficient-stock
failures leave no key and may be retried after replenishment.

Release locks the reservation row, restores every line and marks RELEASED in the
same transaction. Concurrent releases observe that committed status and do not
restore units twice. Reservation lines remain immutable and retained indefinitely.
A reserve retry after release returns RELEASED and never resurrects the reservation;
different items still conflict. A missing reservation release returns Optional.empty
and does not create a cancellation tombstone. A concurrent first reserve may therefore
commit after a missing release. Out-of-order event handling and cancellation
tombstones require a future messaging decision, not an implied Day 3 guarantee.

## Validation and limitations

Domain tests cover validation, conservation, overflow and defensive copies.
The shared integration contract runs against H2 for fast feedback and PostgreSQL
17.6 Testcontainers with `-Dpostgres.tests=true` (mandatory in existing CI). It checks
rollback after an earlier line update, retry after replenishment, same/different
content races, competition for the last units, concurrent releases, reversed line
ordering, replenishment races, release/replay races and database constraints.
Workers use independent transactions, a start barrier, bounded waits and exact
final-stock assertions. H2 is not evidence of PostgreSQL concurrency correctness.
Compose verifies application role isolation, migrations and all health endpoints.

The complete integration contract also runs with `DB_SCHEMA=inventory_alt` on H2
and PostgreSQL. Both PostgreSQL schema variants connect as separate restricted
application owners, not the Testcontainers admin. Tests verify migration/table
ownership, lack of database CREATE/superuser privileges, and SQLSTATE 42501 for
reads/writes to foreign inventory, orders, payments and public schemas.
Unit tests reject invalid/ambiguous schema identifiers before JDBC use and verify
quoted qualification with bound values. This covers review comment 4278148339.

V2 is corrected while still unmerged. A disposable database that already applied
the earlier PR version has an obsolete V2 checksum and must be explicitly recreated
before testing this version; no automatic Flyway repair is performed. Main databases
that only have inventory V1 migrate normally.

No reservation expiry, fulfillment, stock consumption, payment, outbox, events,
saga, REST catalog, authentication or Day 4 behavior is implemented.
