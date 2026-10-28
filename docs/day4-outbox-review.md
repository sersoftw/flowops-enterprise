# Day 4 architectural review: multiple events per aggregate

Date: 2026-10-28 (Europe/Madrid). PR #19 remains open on
feat/day-04-kafka-contratos-versionados; base under review was ade6e84.

## Change and rationale

Both unmerged V3 messaging migrations no longer make aggregate_id unique.
event_id remains the physical primary key. A nullable logical_event_key with an
aggregate/key unique constraint and a type-dependent CHECK preserves exactly one
OrderCreated and one Inventory decision (Reserved OR Rejected) per order, without
limiting other event types to a single occurrence. NULL cannot bypass protected types.
The existing HTTP key, reservation/decision primary keys and processed-event table
remain the business idempotency controls.

OrderMessaging now resolves causation by exact event ID, OrderCreated type, aggregate
and correlation. Both relays already lock/send/mark by event ID; their pending index
now includes the event ID tie-breaker. No new publisher, consumer, payment or compensation
contract is introduced.

## Regression evidence to reproduce

Run from the repository root:

```bash
./mvnw -B -ntp clean verify
./mvnw -B -ntp -Dpostgres.tests=true clean verify
git diff --check
```

The first run uses local H2 and explicitly skips container suites. The second runs
PostgreSQL (including restricted default/alternate inventory owners) and Kafka
Testcontainers. KafkaFlowIT starts real packaged service processes and tests
additional outbox history before inventory processing, followed by duplicate delivery.

New Order tests: repeated probe types on one aggregate; primary-key and logical-key
violations; invalid/null singleton keys; wrong-type/wrong-correlation causal events;
valid causation among several rows; relay failure/retry affecting only one row.
Inventory repeats storage/decision integrity and relay checks under both schemas,
including both accepted and rejected decisions. Existing concurrency, rollback and
replay tests are retained.

Actual command outcomes and exact-commit Actions links are recorded in the PR after
execution. The earlier 139-test result is historical, not evidence of this correction.
Local Docker availability and skipped tests must be distinguished from CI results.

Local run on 2026-10-28: Java 21, Maven Wrapper 3.9.11,
`mvnw.cmd -B -ntp clean verify` completed with BUILD SUCCESS in 52.416 seconds:
86 executed tests passed and 68 container tests were explicitly skipped. Docker CLI
was absent from PATH and the standard Docker Desktop installation path.
`git diff --check` passed. The Windows run used the process-local Windows-ROOT
trust-store options already documented in day4-validation.md.

## Review risks

- V3 checksum changes require explicit recreation of disposable databases from the
  earlier PR. No existing database is repaired or erased automatically.
- Multiple relay instances can reorder pending events for one aggregate via SKIP LOCKED.
  Future causally ordered events need an explicit sequence/serialization design.
- Unknown event types still block the current consumers; probe fixtures do not extend
  the supported contracts. Payments and compensation remain out of scope.
- Missing release still creates no cancellation tombstone.
- Automated Codex review is quota-limited; this report and CI evidence support human review.

Files touched: both V3 migrations and messaging persistence adapters, Order/Inventory
shared test contracts, KafkaFlowIT, ADR-0007 and this review record.
