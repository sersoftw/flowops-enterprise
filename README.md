# FlowOps Enterprise

**Day 1 — Infrastructure scaffold (not yet business-functional).**

Java 21, Spring Boot 4.1.1, Maven multi-module, PostgreSQL 17, Apache Kafka 4.2.2, Docker Compose.

## Requirements

- Docker Desktop with Compose v2 (or equivalent Docker Engine + Compose plugin)
- Java 21 and Maven 3.9+ for local compilation outside containers
- Git and a GitHub repository named `flowops-enterprise`

## Quick start

```bash
cp .env.example .env
# Review .env: development-only passwords; never reuse in production.
docker compose -f infrastructure/docker/compose.yml --env-file .env up -d --build
docker compose -f infrastructure/docker/compose.yml --env-file .env ps
bash scripts/health-check.sh
```

Expected health responses:

- order: http://localhost:8081/actuator/health
- inventory: http://localhost:8082/actuator/health
- payment: http://localhost:8083/actuator/health

Expected JSON contains `"status":"UP"` on all three URLs.

`docker compose -f infrastructure/docker/compose.yml --env-file .env logs -f` to inspect logs.

Clean up:

```bash
docker compose -f infrastructure/docker/compose.yml --env-file .env down
# Only if you want to delete local data:
docker compose -f infrastructure/docker/compose.yml --env-file .env down -v
```

## Local development

Start PostgreSQL and Kafka only:

```bash
docker compose -f infrastructure/docker/compose.yml --env-file .env up -d postgres kafka
mvn -B test
mvn -pl backend/order-service -am spring-boot:run
```

Local development uses `DB_HOST=localhost`, `DB_PORT=5432`, `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`, and module-specific DB usernames/passwords from `.env`. Start the other two modules in separate terminals with the relevant variables. The containerized path is the recommended Day 1 smoke test.

## Maven layout

```text
pom.xml                         aggregator/parent
backend/order-service           REST entry point (later: saga coordinator)
backend/inventory-service       Inventory events and reservations (later)
backend/payment-service         Simulated payments (later)
backend/shared-contracts        Kafka contract types (later)
infrastructure/docker           Compose and Dockerfiles
infrastructure/postgres         Development DB roles and schemas
docs/adr                        Architecture decisions
scripts                         Smoke checks
.github/workflows               Build validation
```

## Day 1 acceptance criteria

- [ ] `mvn -B test` succeeds with Java 21.
- [ ] `docker compose ... config --quiet` succeeds.
- [ ] PostgreSQL creates exactly three application schemas and three restricted roles.
- [ ] Kafka starts and advertises both internal (`kafka:29092`) and host (`localhost:9092`) listeners.
- [ ] `docker compose ... up -d --build` builds and starts all three backend apps.
- [ ] `bash scripts/health-check.sh` prints **3/3 healthy**.
- [ ] A build/validation run is visible in GitHub Actions after pushing.

**Not implemented yet:** order operations, inventory reservation, payment processing, JWT, React UI, outbox, saga, DLQ, Testcontainers. These start on later days.

## Known assumptions and security boundaries

This is development infrastructure, **not production configuration**. No TLS, no production IAM/secrets, and plaintext Kafka in a local Docker network. Only order/inventory/payment ports bind to loopback, and PostgreSQL/Kafka host ports are loopback-bound. Health endpoints are accessible locally. Docker volumes keep database and broker data across container restarts. Each application DB user owns only its schema.

Schema migrations are Flyway-managed and deliberately contain only a small `bootstrap_marker` table on Day 1. Business schemas begin Day 2.

Read `AGENTS.md` and `docs/adr/` before asking Codex to implement any features.
