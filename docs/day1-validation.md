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

Container results and PR/CI links will be recorded after publication. Until that evidence exists, Docker acceptance is **pending**, not passed.
Human review and merge are also pending. Do not close Issue #1 or start Issue #2 until Day 1 is reviewed, integrated and validated.
