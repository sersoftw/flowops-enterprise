# Day 5 validation report

## Prerequisites

PR #19 merged as bdf849c68b52677025b4528855215fd5e6e67071; Issues #1–#4 closed.
Main Actions run [40727185392](https://github.com/sersoftw/flowops-enterprise/actions/runs/40727185392)
succeeded on that merge. Branch feat/day-05-payment-service-pagos starts from this commit.

## Local execution (2026-10-28)

Java 21, Maven Wrapper 3.9.11: `./mvnw.cmd -B -ntp clean verify` completed BUILD SUCCESS
in 48.363 seconds. Surefire/Failsafe XML totals: 184 discovered, 100 executed successfully,
84 explicitly skipped container tests, zero failures/errors. Docker is unavailable on this
Windows host. This local result is not PostgreSQL, Kafka or Compose evidence.

## Mandatory CI execution

The existing Backend & Infrastructure workflow runs:

- `./mvnw -B -ntp -Dpostgres.tests=true clean verify`
- `docker compose config --quiet`
- `docker compose up --build -d --wait --wait-timeout 240`
- `bash scripts/health-check.sh`
- `bash scripts/verify-db-isolation.sh`
- `bash scripts/verify-orders.sh`
- `bash scripts/verify-kafka.sh`

The PR contains the final tested commit, Actions links, actual remote counts/results and
any failed-run corrections. CI retains Surefire/Failsafe XML and all three process logs in
backend-test-reports. Remote checks are pending when this report is initially committed;
consult the PR evidence rather than interpreting a test definition as a passing result.

## Regression coverage

Shared contracts validate all six v1 envelopes. Order tests validate computed amounts,
atomic InventoryReserved/payment request persistence, causal request lookup, duplicates,
delayed contradictory outcomes and explicit failure-pending-compensation state. Payment
suites validate simulator boundaries, conflicting requests, physical/logical idempotency,
six concurrent deliveries, full outbox rollback, broker/mark retries, logical constraints
and concurrent relay ordering. The PostgreSQL payment suite uses a restricted alternate
schema owner and checks SQLSTATE 42501 on all foreign schemas. Existing inventory stock,
release, concurrency and alternate-schema suites remain enabled.

Seven Kafka/PostgreSQL tests launch packaged JVMs with independent restricted roles.
They exercise completion, inventory rejection, payment rejection with stock retained,
inventory and payment consumer restarts, duplicate IDs/new logical delivery IDs, order
relay restart, additional historical events and real Kafka send followed by database mark
failure/retry. No external payment provider or compensation is invoked.

## Review and risks

Human review is required before merge. Issue #5 stays open; no automatic merge or Day 6 work.
See ADR-0008 for rollout (stop old publishers), no backfill of old reserved orders, trusted
local Kafka producers, unbounded inbox/outbox history, partition-blocking poison events,
at-least-once duplicate windows and unchanged missing-reservation cancellation limitation.
