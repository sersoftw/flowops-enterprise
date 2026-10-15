# Day 3 verification record

Date: 2026-10-15 (Europe/Madrid). Scope: Issue #3 only, pending human review.
Branch: `feat/day-03-inventory-service-catlogo`.
Base: latest main `bdb8f9e009da96bd359f010bb30519a667336635`.

## Prerequisites

GitHub API confirmed PR #17 merged into main, Issues #1 and #2 closed, and no open
PRs before implementation. [Post-merge main Actions run 38666042084](https://github.com/sersoftw/flowops-enterprise/actions/runs/38666042084)
completed successfully on that exact merge commit. Its Maven/PostgreSQL,
Compose config/build, health, role isolation, order restart/replay and Kafka checks
all passed. Read AGENTS.md, ADRs 0001–0005 and Issues #1–#3 before editing.

## Files changed

- Inventory module POM: Boot-managed PostgreSQL Testcontainers test dependencies.
- Inventory domain: Product, Stock, Reservation, ReservationLine.
- Inventory application: InventoryStore port, InventoryService and Spring configuration.
- JDBC persistence adapter: explicit transactions, row locking and duplicate-key replay.
- Flyway V2: product, stock, reservation and reservation_line with constraints/index.
- Domain and shared H2/PostgreSQL integration tests (four new test source files).
- Existing Compose isolation script: require inventory V2 and ownership of all four tables.
- README, ADR-0006 and this evidence record.

No order/payment business changes, event contracts, publishers or consumers.

## Local commands and results

Windows, Java 21.0.4, Maven Wrapper 3.9.11. Process-local certificate trust:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
.\mvnw.cmd -B -ntp clean verify
```

- Full clean verify: BUILD SUCCESS, five modules, 46 executed tests passed,
  24 PostgreSQL tests explicitly skipped locally; 46.093 seconds.
  Inventory: four domain tests, twelve H2 integration tests, two health/migration tests.
- `git diff --check`: passed.
- Git Bash `bash -n scripts/verify-db-isolation.sh`: passed outside the sandbox.
- `docker compose config --quiet`: unavailable because Docker CLI is not installed.
  PostgreSQL Testcontainers and Compose must be verified in GitHub Actions.
- Initial sandbox build failed in existing Order HTTP tests because loopback sockets
  were denied. Rerunning outside the sandbox resolved that environment limitation.
- First unrestricted build caught stock capacity overflow being translated into a
  persistence exception. Added a specific domain CapacityExceeded error and reran
  the complete suite successfully. Both available-only and reserved-inclusive capacity
  limits are checked, and failed replenishment leaves stock unchanged.
- Baseline warnings: empty shared-contracts JAR, H2/Flyway version advisory and Mockito
  dynamic agent warning. Local logs are ignored by Git.

## Remote validation

The existing workflow requires `./mvnw -B -ntp -Dpostgres.tests=true clean verify`,
Compose config/build/start, all health endpoints, database isolation, Order API
restart checks, and both Kafka listeners with broker persistence. PostgreSQL inventory
tests use independent concurrent callers, assert final balances and persisted status,
and cover rollback and replay. Remote run results will be recorded on the review PR.
Local H2 results are not a substitute for those checks.

Issue #3 stays open. Human review and merge are the next gate; Day 4 is not started.
