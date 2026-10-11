# ADR-0001: One repository, three Spring Boot services

**Status:** Accepted (Day 1)

## Context
We need to demonstrate distributed design in a two-week portfolio project without the operational burden of many repositories.

## Decision
Use a Maven aggregator at the root and three independently deployable Spring Boot services (orders, inventory, payment). `shared-contracts` is reserved for event contract types only; no shared persistence models. No gateway, discovery or UI until later.

## Consequences
Simpler CI, repeatable local setup, explicit service ownership. The monorepo does not imply a shared database model or monolith.
