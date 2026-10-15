# FlowOps Enterprise

**Day 3 — Inventory catalog and concurrent reservations (pending review).**

Java 21, Spring Boot 4.1.1, Maven multi-module, PostgreSQL 17, Apache Kafka 4.2.2, Docker Compose.

## Requirements

- Docker Desktop with Compose 2.20+ (or equivalent Docker Engine + Compose plugin)
- Java 21; the official Maven Wrapper downloads pinned Maven 3.9.11
- Bash, curl and Python 3 for the smoke scripts
- Git and a GitHub repository named `flowops-enterprise`

## Quick start

```bash
cp .env.example .env
# Review .env: development-only passwords; never reuse in production.
./mvnw -B clean verify
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 240
docker compose ps
bash scripts/health-check.sh
bash scripts/verify-db-isolation.sh
# Linux, or Docker Desktop with host networking enabled:
bash scripts/verify-kafka.sh
```

Expected health responses:

- order: http://localhost:8081/actuator/health
- inventory: http://localhost:8082/actuator/health
- payment: http://localhost:8083/actuator/health

Expected JSON contains `"status":"UP"` on all three URLs.

`docker compose logs -f` to inspect logs. Run all commands from the repository root.
On Windows PowerShell, use `Copy-Item .env.example .env` and `.\mvnw.cmd -B clean verify`;
run the smoke scripts in Git Bash or WSL with access to Docker and Python 3.
Root `compose.yml` includes the infrastructure definition without duplicating it.

Clean up:

```bash
docker compose down
# Only if you want to delete local data:
docker compose down -v
```

## Local development

Start PostgreSQL and Kafka only:

```bash
docker compose up -d postgres kafka
./mvnw -B clean verify
# Set DB_PASSWORD to ORDER_DB_PASSWORD from your local .env first.
./mvnw -pl backend/order-service spring-boot:run
```

Local development uses `DB_HOST=localhost`, `DB_PORT=5432`, `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`, and module-specific DB usernames/passwords. Maven does not load `.env`: export `DB_PASSWORD` for the chosen service (and `DB_USERNAME` if overriding defaults). Start the other two modules in separate terminals with their own passwords. The containerized path loads `.env` automatically and is the recommended Day 1 smoke test.

Maven smoke tests start each application on a random HTTP port with H2 in PostgreSQL mode and execute the real Flyway V1 migration. They do not prove PostgreSQL permissions or Kafka connectivity. Compose smoke scripts authenticate as each application role, require foreign-schema permission errors, exercise both Kafka listeners, and verify a test message survives broker container recreation. The Kafka script creates and deletes only its own temporary test topic.

## Maven layout

```text
pom.xml                         aggregator/parent
backend/order-service           REST entry point (later: saga coordinator)
backend/inventory-service       Internal catalog, stock and atomic reservations
backend/payment-service         Simulated payments (later)
backend/shared-contracts        Kafka contract types (later)
infrastructure/docker           Compose and Dockerfiles
infrastructure/postgres         Development DB roles and schemas
docs/adr                        Architecture decisions
scripts                         Smoke checks
.github/workflows               Build validation
```

## Day 1 acceptance criteria

- [ ] `./mvnw -B clean verify` succeeds with Java 21.
- [ ] `docker compose ... config --quiet` succeeds.
- [ ] PostgreSQL creates exactly three application schemas and three restricted roles.
- [ ] Kafka starts and advertises both internal (`kafka:29092`) and host (`localhost:9092`) listeners.
- [ ] `docker compose ... up -d --build` builds and starts all three backend apps.
- [ ] `bash scripts/health-check.sh` prints **3/3 healthy**.
- [ ] A build/validation run is visible in GitHub Actions after pushing.

See [the validation record](docs/day1-validation.md) for actual results and pending gates.

## Order API (Day 2)

`POST /api/orders` requires `Idempotency-Key` and creates a PENDING order (201).
The same key/content returns the persisted order (200); different content returns 409.
`GET /api/orders/{id}` retrieves an order; `GET /api/orders` supports `customerId`,
`status`, zero-based `page` and `size` (1–100). Invalid requests return problem JSON (400).

The [OpenAPI contract](backend/order-service/src/main/resources/static/openapi.yaml) is also
served at `http://localhost:8081/openapi.yaml`. See [HTTP examples](docs/orders.http) and
[ADR-0005](docs/adr/0005-order-api-and-idempotency.md) for bounds and replay semantics.

```bash
curl -i http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: example-order-001' \
  -d '{"customerId":"f90c8ab2-5acf-4b35-8ec3-917167624075","currency":"EUR","items":[{"productId":"sku-1","quantity":2,"unitPrice":12.50}]}'
curl 'http://localhost:8081/api/orders?status=PENDING&page=0&size=20'
./mvnw -B -ntp clean verify                         # fast tests; PostgreSQL tests reported skipped
./mvnw -B -ntp -Dpostgres.tests=true clean verify    # requires Docker; mandatory in CI
bash scripts/verify-orders.sh                      # Compose HTTP checks, including restart and replay
```

Prices are caller-supplied in this development API; totals are calculated server-side using
BigDecimal. No catalog authority or payment processing is implied. See the
[Day 2 validation record](docs/day2-validation.md) for actual results.

## Inventory service (Day 3)

The internal Java `InventoryService` supports product creation/lookup, stock lookup,
replenishment, `reserve(orderId, items)` and `release(orderId)`. There are no inventory
business HTTP endpoints yet. The health endpoint remains on port 8082.

Reservations atomically move available units to reserved units. A repeated order ID
with the same products/quantities returns its existing reservation; changed content
raises a conflict. Release returns stock once. Replaying a released reservation
returns RELEASED without reserving again. Missing release is a no-op, not a persistent
cancellation. Products are locked in a stable order; insufficient stock rolls back
all lines. See [ADR-0006](docs/adr/0006-inventory-reservations.md) for limits and concurrency.

```java
inventory.createProduct("sku-1", "Example product", 10);
inventory.replenish("sku-1", 5);
inventory.reserve(orderId, List.of(new ReservationLine("sku-1", 3)));
inventory.stock("sku-1"); // available=12, reserved=3
inventory.release(orderId); // available=15, reserved=0
```

Run `./mvnw -B -ntp -Dpostgres.tests=true clean verify` with Docker for the complete
suite, including PostgreSQL concurrency tests. Without that property, PostgreSQL
tests are explicitly skipped. See [Day 3 validation](docs/day3-validation.md).

Inventory supports `DB_SCHEMA` (default `inventory`). Provision that schema with its
own application owner first; schema names must match `[A-Za-z_][A-Za-z0-9_]{0,62}`.
Flyway and JDBC use the same configured schema. CI exercises both the default and
`inventory_alt` with restricted PostgreSQL roles.

**Not implemented yet:** inventory business HTTP API, payment processing, JWT, React UI, outbox, saga, DLQ.

## Known assumptions and security boundaries

This is development infrastructure, **not production configuration**. No TLS, no production IAM/secrets, and plaintext Kafka in a local Docker network. Only order/inventory/payment ports bind to loopback, and PostgreSQL/Kafka host ports are loopback-bound. Health endpoints are accessible locally. Docker volumes keep database and broker data across container restarts. Each application DB user owns only its schema.

Schema migrations are Flyway-managed and deliberately contain only a small `bootstrap_marker` table on Day 1. Business schemas begin Day 2.

PostgreSQL initialization runs only on an empty volume. Changing `.env` passwords or role grants does not reinitialize existing data. For disposable development data only, use the documented `down -v` command and start again; it deletes all database and Kafka data. Kafka explicitly stores its logs in the mounted `/var/lib/kafka/data` directory. Ports 5432 and 9092 are loopback-only for host development; the controller port is not published.

Read `AGENTS.md` and `docs/adr/` before asking Codex to implement any features.
