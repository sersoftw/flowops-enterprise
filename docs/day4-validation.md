# Day 4 verification record

Date: 2026-10-15 (Europe/Madrid). Scope: Issue #4 only.
Branch: feat/day-04-kafka-contratos-versionados.
Base: main 19941d26e79d0878817c4ac26caa1306e68482aa.

## Prerequisites

GitHub API confirmed PR #18 merged into main and Issues #1, #2 and #3 closed.
[Main merge CI 38668966342](https://github.com/sersoftw/flowops-enterprise/actions/runs/38668966342)
completed successfully on that exact merge SHA. No open PRs were returned.
Read AGENTS.md, ADRs 0001–0006 and Issues #1–#4 before implementing.

## Changed areas

- shared-contracts: three payloads, generic envelope, published v1 JSON schemas and tests.
- Order Service: transactional outbox creation, relay, Kafka result consumer,
  processed-event persistence, guarded status transition, correlation response header,
  additive Flyway migrations and OpenAPI status enum update.
- Inventory Service: transaction-internal stock operation, durable decision/inbox/result
  outbox, relay and Kafka OrderCreated consumer; existing standalone API retained.
- Both service configurations: explicit String JSON transport, record acknowledgements,
  stable consumer groups and retry policy. Unit/service tests disable broker transports.
- Root/integration-tests: Failsafe tests launch packaged service JVMs against PostgreSQL
  and Kafka Testcontainers, use restricted roles, verify Kafka key/envelope/stock/HTTP
  results and restart consumer/relay processes.
- CI report/log artifacts, README and ADRs. Payment implementation is unchanged.

## Local verification

Java 21.0.4, Maven Wrapper 3.9.11 on Windows. Process-local Windows trust options:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
.\mvnw.cmd -B -ntp clean verify
```

The final local clean build passed: six modules, 79 executed tests passed and 60
container tests explicitly skipped; 51.949 seconds.
Docker is unavailable on this host. No local PostgreSQL/Kafka/Compose success is
claimed. This verification includes the status-constraint preservation assertions;
exact remote results are recorded on the PR for its pushed commit.

Tests cover producer outbox rollback, failed send and send-before-mark retry, stock
decision/outbox rollback, duplicate deliveries with identical/new event IDs, stable
rejection after replenishment, multi-line rejection, existing release versus missing
release, contradictory outcomes, premature/unrelated results and default/alternate
schemas. PostgreSQL repeats the service suites; KafkaFlowIT is only run with Docker.

The status migration explicitly selects the PENDING status check, preserving NOT NULL.
PostgreSQL's information schema also exposes NOT NULL as CHECK entries
([official documentation](https://www.postgresql.org/docs/17/infoschema-check-constraints.html)).
No merged V1/V2 migration is edited.

## Remote gate and limitations

Required command: `./mvnw -B -ntp -Dpostgres.tests=true clean verify`.
Existing Compose config/build, health, isolation, Order restart/replay and Kafka
listener/persistence checks remain required. Actual run links and results are added
to the PR after completion; a local H2 pass cannot replace them.

At-least-once delivery remains explicit. Unknown/invalid messages retry and can block
a partition. No DLT, payments or cancellation events are added. Missing reservation
release has no tombstone and cannot prevent a later OrderCreated reservation.
Human review is required; Issue #4 must remain open until reviewed and merged.
