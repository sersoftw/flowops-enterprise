# Day 1 verification record

**Date:** 2026-10-11 (Europe/Madrid). **Scope:** Issue #1 only.
Read AGENTS.md, all initial ADRs and Issues #1–#14 before implementation.
Base: `0615e4e673d87eca8984c4d3acb890c47693a97f`; branch: `feat/day-01-bootstrap`.

## Local environment and actual results

- Windows 11 amd64. `mvn -version`: Maven 3.9.11, Oracle Java 21.0.4 from `C:\Program Files\Java\jdk-21`.
- `java -version` on PATH reports Java 22.0.1; Maven uses JAVA_HOME and the tests explicitly logged Java 21.0.4.
- Spring Boot 4.1.1 was verified against official stable documentation, resolved from Maven Central and used by the successful build. See ADR-0004.
- `mvn -N -B -ntp org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.11 -Dtype=only-script`: succeeded after the TLS retry below.
- `.\mvnw.cmd -B -ntp clean verify`: **exit 0, BUILD SUCCESS**, five reactor modules, 49.552 seconds. All three executable application JARs were packaged.
- Six tests executed: two per service, zero failures/errors/skips. Each test suite started Tomcat on a random port, asserted HTTP 200 and top-level `status=UP`, and verified the Flyway V1 marker/history in H2. These are not PostgreSQL or fixed-port container results.
- Git Bash `bash -n` for health, database isolation, Kafka verification and PostgreSQL initialization scripts: exit 0.
- `git diff --check`: exit 0.
- `docker --version` and `docker compose config --quiet`: attempted but command unavailable. Docker CLI is absent, including the standard Docker Desktop install path. No local Docker build/up, PostgreSQL isolation, Kafka connectivity/persistence or fixed-port health test was executed.

## Environment troubleshooting and warnings

Git initially failed DNS in the sandbox, then certificate trust outside it. Using `git -c http.sslBackend=schannel ...` succeeded with Windows certificate validation enabled. Maven initially failed PKIX trust; the successful invocation used this process-local setting:

```powershell
$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
.\mvnw.cmd -B -ntp clean verify
```

No certificate validation was disabled and no machine-wide trust configuration was changed.
Git Bash also required execution outside the sandbox because MSYS named-object creation was denied.
Build warnings: intentionally empty shared-contracts JAR, Mockito dynamic agent attachment, and Flyway's warning that Boot-managed H2 2.4.240 is newer than its last verified H2 version. The six tests passed despite those warnings; H2 is test-scoped only.

## Container acceptance and review gates

The CI workflow now runs Wrapper clean verify, root Compose config/build/up, HTTP health, authenticated PostgreSQL isolation, both Kafka listeners and persistence after broker container recreation. Test reports are uploaded and container logs are printed on failure.

PR: [#15 — Complete Day 1 bootstrap](https://github.com/sersoftw/flowops-enterprise/pull/15).
Implementation tested: `b16d66504cc6922bbaeb226e71dd457a016658de`.
Both the [branch CI run](https://github.com/sersoftw/flowops-enterprise/actions/runs/38174734040)
and [PR CI run](https://github.com/sersoftw/flowops-enterprise/actions/runs/38174768400)
completed with **success** on 2026-10-11. The logs were read after completion.

Runner versions: Eclipse Adoptium Java **21.0.12.1**, Maven **3.9.11**,
Docker client/server **28.0.4**, Docker Compose **2.38.2** (Linux amd64).

| Actual CI command | Result |
| --- | --- |
| `./mvnw -B -ntp clean verify` | PASS: five modules, six tests, zero failures/errors/skips, executable JARs packaged |
| `cp .env.example .env` then `docker compose config --quiet` | PASS |
| `docker compose up --build -d --wait --wait-timeout 240` | PASS: PostgreSQL, Kafka and all three services built/started |
| `bash scripts/health-check.sh` | PASS: HTTP 200 and top-level `status=UP` on ports 8081, 8082 and 8083; `3/3 healthy` |
| `bash scripts/verify-db-isolation.sh` | PASS: all three roles own/read/write their schemas, V1 migrations applied, six foreign-schema pairs deny reads/creation, public schema and database creation denied |
| `bash scripts/verify-kafka.sh` | PASS: internal and external listeners answer, test message survives forced broker container recreation with the same volume |
| `docker compose down -v` | PASS: isolated CI stack removed |

The initial scaffold's separate service applications, configuration and V1 migrations were retained.
Changed files cover the official Wrapper and line endings, root Compose entry point, Kafka log path,
public-schema permissions, three service test dependencies/suites, smoke scripts, CI, README and ADR-0004.
Only example local credentials are committed; `.env` and runtime logs remain ignored.

Container acceptance is validated **in GitHub Actions**, not on this Windows host.
There are no observed failing checks. The build warnings documented above remain non-blocking.
Human review and merge are pending. Do not close Issue #1 or start Issue #2 until Day 1 is reviewed, integrated and validated after merge.
