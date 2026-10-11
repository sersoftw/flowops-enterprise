# ADR-0002: PostgreSQL per-service schemas and Kafka in KRaft mode

**Status:** Accepted (Day 1)

## Context
One Docker-hosted PostgreSQL instance limits local resource use. Services must not directly depend on each other's database. Kafka is needed for future asynchronous workflows.

## Decision
Create three PostgreSQL roles and schemas. Each Spring Boot service authenticates as its dedicated role and uses Flyway migrations. Use Apache Kafka 4.2.2 in single-node KRaft mode for development with internal `kafka:29092` and host `localhost:9092` listeners.

## Consequences
Separation of logical ownership with lower memory use than three database servers. This is not physical isolation or production Kafka resilience. Local-only insecure listeners are intentional and must not be exposed publicly.
