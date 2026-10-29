# Day 6 validation and review evidence

## Verified prerequisites (2026-10-29)

PR #20 merged at d461f624a36e0bdd5f5b933357e091138558681e; Issues #1 through #5 closed.
Main Actions [40766194797](https://github.com/sersoftw/flowops-enterprise/actions/runs/40766194797)
succeeded on that exact current main commit. The Day 6 feature branch starts there.

## Local execution

Java 21, Maven Wrapper 3.9.11: `./mvnw.cmd -B -ntp clean verify` completed BUILD SUCCESS
in 47.469 seconds. Surefire/Failsafe XML: 220 discovered, 118 executed successfully,
102 container tests explicitly skipped; zero failures/errors. Docker is unavailable on
the local Windows host. Local results are not evidence of PostgreSQL/Kafka/Compose success.

Development runs exposed and corrected guard creation within an existing transaction,
updated the old missing-release behavior test, aligned the deterministic retry clock with
DB microsecond precision, and scoped a failure-injection constraint to its own order.
The final local run above includes the fixes and all Day 6 regression cases.

## CI commands and evidence

The PR description records the final tested SHA, actual Actions URL, counts, infrastructure
results and any failed-run corrections. Remote validation is pending in this initial
committed report; do not interpret a defined test as a passing execution.

- `./mvnw -B -ntp -Dpostgres.tests=true clean verify`
- `docker compose config --quiet`
- `docker compose up --build -d --wait --wait-timeout 240`
- `bash scripts/health-check.sh`
- `bash scripts/verify-db-isolation.sh`
- `bash scripts/verify-orders.sh`
- `bash scripts/verify-kafka.sh`

Expected suite inventory: shared-contracts 2, order-service 74, inventory-service 112,
payment-service 23, KafkaFlowIT 9. CI retains Surefire/Failsafe reports and packaged
service JVM logs in backend-test-reports. The PR will record actual remote results.

## Coverage and review focus

- Approved payment -> COMPLETED, no release; insufficient stock -> CANCELLED without payment.
- Failed payment -> RELEASING_INVENTORY -> CANCELLED only on valid release confirmation.
- Correlation, original request and reservation identity checks; repeated logical/physical events.
- Concurrent duplicate releases and first reserve versus missing release; durable cancellation marks.
- Stock, tombstone, release decision, inbox and outbox rollback; persisted error evidence.
- Three durable retry windows, handler/process restart, stable event ID and manual escalation.
- Delayed/contradictory results and a paid terminal order that never requests compensation.
- All prior inventory concurrency, PostgreSQL restricted alternate-schema, payment and API tests.
- Nine packaged-service Kafka scenarios, including compensation consumer/relay restart and
  injected release-outbox failure followed by recovery. Kafka send/mark retries remain covered.

## Limits and next step

Review ADR-0009 and the PR before merge. Issue #6 remains open; no automatic merge or Day 7.
There is no exactly-once guarantee across Kafka/PostgreSQL. MANUAL_REVIEW retains ambiguity:
commands already in Kafka may still execute, and late confirmations do not auto-resolve it.
Stop old writers during migration. Historical missing releases cannot be reconstructed;
old failed-payment orders require explicit operational assessment. No DLT, cleanup policy,
authentication rollout or manual recovery endpoint is added. See ADR-0009 for the full policy.
