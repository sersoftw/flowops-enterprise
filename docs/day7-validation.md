# Day 7: integrated MVP validation

## Prerequisites verified on 2026-10-30

PR #21 merged at dc2f42b1067887f03543fa219563883fe3c17234; Issues #1 through #6 closed.
[Main Actions 40925821119](https://github.com/sersoftw/flowops-enterprise/actions/runs/40925821119)
succeeded on that exact main commit before branching to feat/day-07-mvp-integrado-smoke.
AGENTS.md, ADR-0009 and Issue #7 were read before implementation.

## Local evidence

Java 21, Maven Wrapper 3.9.11: `./mvnw.cmd -B -ntp clean verify` completed BUILD SUCCESS
in 54.072s: 222 discovered, 119 executed successfully, 103 container tests explicitly
skipped, zero failures/errors. The demo CLI help and Python syntax compilation passed.
Docker is unavailable on this Windows host; clean Compose, seed and business demo
execution must be evidenced by CI, not inferred from local Maven success.

## Mandatory CI execution

The workflow uses a unique COMPOSE_PROJECT_NAME per run and asserts that there are no
existing containers or project volumes before building the stack. This supplies clean
installation evidence without deleting a developer's existing data. CI performs:

1. `./mvnw -B -ntp -Dpostgres.tests=true clean verify`
2. `.env.example` copied to .env, `docker compose config --quiet`
3. Assert empty project; `docker compose up --build -d --wait --wait-timeout 240`
4. Health, role isolation and order API/restart scripts
5. `bash scripts/seed-demo-data.sh` twice
6. `bash scripts/demo-scenarios.sh --output build/demo-first.json`
7. Seed again, then `bash scripts/demo-scenarios.sh --restart --output build/demo-repeat-restart.json`
8. Kafka listener and broker persistence script

The PR description records actual final CI conclusions, tested SHA and artifact links.
Remote results are pending at the time of this initial report commit. Do not interpret
the definitions above as successful execution until the linked PR evidence confirms it.

Expected Maven suite inventory: shared-contracts 3, Order 74, Inventory 112, Payment 23,
KafkaFlowIT 10. The extra tests validate eight documented event envelopes and a real
Kafka compensation timeout followed by late release and process restart: MANUAL_REVIEW
persists. Test-only SQL advances deadlines, never business status or retry counters.

## Demonstration evidence and repeatability

Reports preserve actual before/after [available,reserved], stable order IDs, final states,
history, payment/reservation results and logical outbox event types. Initial expectations:

| Scenario | Before | After | Result |
| --- | --- | --- | --- |
| approved | [10,0] | [8,2] | COMPLETED, one successful simulated attempt |
| insufficient | [1,0] | [1,0] | CANCELLED, no payment or release request |
| declined | [5,0] | [5,0] | CANCELLED, failed payment and confirmed release |

On repeat/restart, approved starts and ends at [8,2]; the other balances remain unchanged.
The scripts verify one order per stable key and one attempt per paid/rejected payment.
Seeds insert only absent fixture rows; no stock replenishment, table deletion or reset.
PENDING and INVENTORY_RESERVED are checked in history; unexpected MANUAL_REVIEW fails
the healthy demo. Do not modify the reserved fixture SKUs with unrelated orders.

CI retains build/demo-*.json alongside Surefire/Failsafe reports and service logs in
backend-test-reports. Use those files and the tested SHA for a verifiable demonstration.

## Release boundary

Only notes/checklist for v0.1.0 are prepared in docs/releases/v0.1.0.md. No tag, published
release, final-version artifact or merge is created. Maven remains 0.1.0-SNAPSHOT.
Human review and a separate publication authorization remain required. Issue #8 is out
of scope. Known manual-review, poison-message, retention, upgrade and security risks are
in the revised README and ADR-0009; no business behavior or migration changes in Day 7.
