# Day 2 verification record

**Date:** 2026-10-15 (Europe/Madrid). **Scope:** Issue #2 only.
Branch: `feat/day-02-order-service-dominio` from main `3234200100d55ebbcd1de797a776b456d09adfcb`.

## Prerequisite evidence

GitHub API confirmed PR #15 merged into main on 2026-10-11 and Issue #1 closed.
[Post-merge main CI](https://github.com/sersoftw/flowops-enterprise/actions/runs/38177523961)
completed successfully on that exact commit. No open PRs were returned before starting.
Read AGENTS.md, Issue #1, Issue #2 and ADRs 0001–0004 before editing.

## Changes

- Order service: immutable domain, application port/service, JPA adapter, REST DTOs,
  problem responses, strict JSON validation, Flyway V2 and static OpenAPI.
- Tests: domain arithmetic/invariants; HTTP creation/read/filter/page/errors, replay,
  same/different-content concurrency, database quantity constraint; same HTTP suite
  opt-in with PostgreSQL Testcontainers 2.0.5 (Boot-managed), PostgreSQL 17.6.
- Workflow: require PostgreSQL tests and Compose order smoke checks after restart.
- Docs: README, HTTP examples, ADR-0005 and this record; new verify-orders.sh.
- No changes to inventory/payment business code or shared-contracts.

## Local commands and results

Windows host, JAVA_HOME `C:\Program Files\Java\jdk-21`, Java 21.0.4, Maven Wrapper 3.9.11.
Maven requires process-local Windows certificate trust, as documented on Day 1:

```powershell
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp -pl backend/order-service -am verify
```

- Full clean verify: BUILD SUCCESS, all five modules. 16 executed tests passed;
  seven PostgreSQL tests explicitly skipped without the opt-in property.
- Order-module verify after strict JSON validation: BUILD SUCCESS, 12 executed tests
  passed and seven PostgreSQL tests skipped. Repeated successfully on the final
  implementation, including explicit problem type assertions (16.928 seconds).
- An earlier test caught invalid UUIDs incorrectly returning 500; corrected to 400.
  Fractional quantities and unknown body fields now return 400.
- `git diff --check`: passed. Git Bash `bash -n scripts/verify-orders.sh`: passed.
- OpenAPI YAML parsed successfully and all internal references resolved using the
  existing SnakeYAML 2.6 dependency via a temporary Java source launcher. The first
  Python attempt could not run because that Python installation lacked PyYAML.
- Docker CLI unavailable: `docker compose config --quiet` could not run locally.
  No local container, PostgreSQL Testcontainers or Compose HTTP result is claimed.
- Initial sandbox Maven dependency-cache writes and Bash MSYS initialization were denied;
  reruns outside the sandbox succeeded. One overlapping log redirection was rejected by
  Windows; the completed build was collected before rerunning.
- Non-blocking baseline warnings: H2/Flyway tested-version warning, dynamic Mockito agent,
  empty shared-contracts JAR. Expected unique-key errors are logged during race tests.

## Remote acceptance

PR: [#16 — Day 2 order domain, persistence and idempotent REST API](https://github.com/sersoftw/flowops-enterprise/pull/16).
Implementation tested: `3397a68025037dd3e4051f8eb5e3e7808ea957ed`.
[PR CI run 38662753323](https://github.com/sersoftw/flowops-enterprise/actions/runs/38662753323)
completed with **success** on 2026-10-15. The completed job log was downloaded and read.

| Actual CI command | Result |
| --- | --- |
| `./mvnw -B -ntp -Dpostgres.tests=true clean verify` | PASS: five modules, 23 tests, zero failures/errors/skips |
| PostgreSQL Testcontainers HTTP suite | PASS: seven tests, including simultaneous identical and conflicting requests |
| `docker compose config --quiet` | PASS |
| `docker compose up --build -d --wait --wait-timeout 240` | PASS |
| `bash scripts/health-check.sh` | PASS: 3/3 healthy |
| `bash scripts/verify-db-isolation.sh` | PASS: restricted roles and cross-schema denials |
| `bash scripts/verify-orders.sh` | PASS: curl creation/list/validation/conflict, service restart, persisted read and replay |
| `bash scripts/verify-kafka.sh` | PASS: both listeners and broker persistence |

The follow-up evidence commit changes documentation only; its CI is tracked on the PR.
No known failing implementation checks remain. Local Docker remains unavailable;
all PostgreSQL/container evidence above comes from GitHub Actions.

Human review and merge remain required. Do not advance to Day 3 or close Issue #2 manually.
