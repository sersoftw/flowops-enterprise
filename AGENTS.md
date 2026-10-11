# AGENTS.md — FlowOps Enterprise

You are contributing to **FlowOps Enterprise**, a portfolio-grade, event-driven Java application.

## Objective

Deliver production-inspired engineering practices within a **14-day timebox**. Prefer correctness, reproducibility, observability and tests over technology sprawl.

## Stack and boundaries

- Java 21, Spring Boot 4.1.1, Maven multimodule (versions pinned in parent POM).
- Spring MVC, Data JPA, Flyway, PostgreSQL, Spring Kafka, Actuator.
- Three independent services: `order-service`, `inventory-service`, `payment-service`.
- Each service has its own database user/schema and migrations. Never access another service's schema.
- Kafka connects services asynchronously. No cross-service JPA relationships.
- `shared-contracts` can contain transport DTOs/event schemas only — never JPA entities, repositories, business services, or shared persistence.
- Keep domain code separate from frameworks and transport implementations.
- Docker Compose supplies local infrastructure. Containers use Kafka listener `kafka:29092`; host tools use `localhost:9092`.

## Rules for implementation

1. Work ONLY on the assigned day/task and necessary prerequisites. Do not implement future backlog features.
2. Before editing, inspect the current files and write a concise implementation plan.
3. Prefer simple, testable designs. No Kubernetes, Eureka, real money, external secrets, Redis or separate gateway at Day 1.
4. Define contracts before publishing Kafka messages. Future envelopes must include `eventId`, `eventType`, `eventVersion`, `aggregateId`, `correlationId`, `occurredAt` and payload.
5. Assume Kafka at-least-once delivery. Later consumers must be idempotent; maintain transactionally consistent state and a transactional outbox.
6. Money must use `BigDecimal` and validated server-side amounts when payment logic is added.
7. Use Flyway; never use Hibernate's `create` or `update` schema mode.
8. Keep secrets out of commits; `.env.example` contains only example local credentials.
9. Keep changes small and reviewable; branches like `feat/day-01-scaffold` and PRs into `main`.
10. Write clear technical docs, commit messages and source-code comments in English.
11. Run and report REAL checks. Do not claim builds/health tests passed if they were not run.
12. Before completion, report files touched, commands run, results, known blockers, next step.

## Definition of done for Day 1

- Parent pom and module poms are valid; Java 21 and compatible dependency versions are pinned.
- Three distinct Spring Boot applications expose `/actuator/health` on ports 8081/8082/8083.
- PostgreSQL development database has three schemas with separate app owners and migrations.
- Kafka broker starts with working internal and external listener configuration.
- Docker Compose can build/start everything from the root with local `.env`.
- `mvn -B test`, `docker compose config --quiet`, `scripts/health-check.sh` and GitHub Actions pass in a suitable environment.
- README and architecture decisions explain setup and non-production limitations.

**Don't advance to Day 2 until Day 1 evidence is visible.**
