# ADR-0004: Reproducible Day 1 builds and two layers of smoke checks

**Status:** Accepted for implementation; runtime acceptance requires CI and human review.

## Decision

Preserve the initial scaffold and its Java 21 / Spring Boot 4.1.1 baseline.
The [official Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html)
listed 4.1.1 as stable and compatible with Java 21 when checked on 2026-10-11.
Maven successfully resolved that parent and compiled all modules.
Generate the [official Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/)
with plugin 3.3.4, only-script distribution, and Maven 3.9.11. Use it in CI and Docker.

Use H2 only in test scope for fast application startup, HTTP health and real V1 migration checks.
Use the Compose PostgreSQL container for database ownership, authentication and negative cross-schema tests.
The latter must fail on unexpected SQL/connection errors instead of treating any error as isolation.
Use Kafka command-line clients against both advertised listeners and recreate the broker container
before consuming a test message to check persistent storage. This is infrastructure validation,
not a business event contract or a Day 4 publisher.

## Consequences

Local Maven tests need no Docker. Passing them cannot replace the real PostgreSQL/Kafka smoke run.
Compose 2.20+ is required for the root include. The external Kafka probe uses host networking,
available on Linux CI and requiring explicit enablement in Docker Desktop.
All application ports remain loopback-bound. No business endpoints, entities or contracts are added.
