# Day 2 follow-up: numeric validation consistency

Date: 2026-10-15 (Europe/Madrid). Branch: `fix/day-02-validation-consistency`.
Base: main `6a9474f4f1150c1f862f41ed35760f553bf6a374`, with merged PR #16 and closed Issue #2.
Main CI [38664887097](https://github.com/sersoftw/flowops-enterprise/actions/runs/38664887097)
was successful before this work. Scope is the two remaining P2 comments on PR #16,
4277982111 and 4277982116; no Day 3 implementation.

## Changes and regression coverage

- A quantity-specific Jackson deserializer converts JSON numbers via BigDecimal.intValueExact.
  It accepts 1.0, 1.000 and 1e0 as 1, preserves the 1–10000 range checks, and rejects
  fractions (including 1.0000000000000001), overflow, strings, booleans and null.
- The request record strips trailing price zeros before @Digits and decimal bounds checks.
  Values such as 12.500, 0.01000 and 999999999.9900 are accepted without rounding;
  12.501 and 0.00100 remain invalid. Domain and database money types are unchanged.
- OpenAPI descriptions and ADR-0005 explain these semantics. Contract tests parse numeric
  instances as BigDecimal to avoid losing decimal precision in the validation harness.
- Contract, domain and shared H2/PostgreSQL HTTP tests cover boundaries, equivalent
  numeric representations, exact persisted amounts, retrieval, idempotent replay and
  corrected retry after invalid input. Existing migration/isolation checks are retained.
- CI push triggers include fix/** in addition to main/feat/**; PR checks remain mandatory.

## Commands actually executed locally

Java 21.0.4 / Maven Wrapper 3.9.11 on Windows, using the process-local Windows trust store
options documented in day2-validation.md. Docker is unavailable on this host.

- Before correction: `mvnw.cmd -B -ntp -pl backend/order-service -Dtest=OpenApiContractTest,OrderDomainTest,OrderApiTest test`:
  24 tests, three expected failures. They reproduced the 1.0 rejection, 12.500 rejection,
  and unexpected acceptance of a quoted quantity (an additional inconsistency now fixed).
- After correction: `mvnw.cmd -B -ntp clean verify`: BUILD SUCCESS, five modules,
  30 tests executed without failures; 12 PostgreSQL tests explicitly skipped locally;
  elapsed time 40.068 seconds.
- `git diff --check`: passed.

The new PR records remote results for the exact pushed commit. GitHub Actions runs
`./mvnw -B -ntp -Dpostgres.tests=true clean verify` and the full Compose config/build,
health, schema isolation, order restart/replay and Kafka checks. Local H2 results do not
substitute for PostgreSQL Testcontainers or those container checks.

No schema migration is required. Issue #2 remains closed; the follow-up PR requires review
and must not be merged automatically. Day 3 is still out of scope.
