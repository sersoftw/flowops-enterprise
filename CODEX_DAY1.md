# Day 1 — Codex execution brief

Read `AGENTS.md`, `README.md`, and all `docs/adr/` files before changing code.

## Assignment

Inspect and validate the provided FlowOps Enterprise scaffold. Finish only Day 1:

1. Check the current latest compatible *stable* Spring Boot 4.1.x dependency versions and resolve Maven modules.
2. Generate and commit an official Maven Wrapper for a pinned Maven 3.9.x release (`mvn -N wrapper:wrapper -Dmaven=3.9.11`, or the documented equivalent). Do not write a fake wrapper.
3. Validate PostgreSQL initialization: one database, `orders`, `inventory`, `payments` schemas and three non-superuser accounts. Confirm each app role cannot query another service's schema.
4. Validate Kafka bootstrap and internal/host listeners with the official Kafka image.
5. Execute `mvn -B test`, `docker compose -f infrastructure/docker/compose.yml --env-file .env config --quiet`, Docker Compose build/up, `bash scripts/health-check.sh`.
6. Fix only failures relevant to Day 1. Do not implement orders, payments, inventory APIs, JWT, UI or future days.
7. Add a CI job or improve existing CI to execute this smoke scenario in GitHub Actions.
8. In `docs/day1-validation.md`, record exact tested commands, actual results and links to the PR and CI run.

## Git workflow

Create a branch `feat/day-01-scaffold` and a PR targeting `main`. Use small commits and explain fixes. Never force-push `main`.

## Done when

All three `/actuator/health` endpoints return HTTP 200 with `status=UP`; DB access boundaries checked; Java 21 build/tests pass; Compose stack is reproducible; GitHub Actions green. If anything cannot be tested, say so explicitly.

## Expected final report

Provide: branch/PR link, summary of changed files, Java/Maven/Docker versions, commands executed and exit statuses, three endpoint results, DB isolation evidence, Kafka connectivity evidence, CI URL, any remaining issues, and readiness decision for Day 2.
