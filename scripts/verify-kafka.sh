#!/usr/bin/env bash
set -euo pipefail
# Test metadata-driven client connections from both sides of the Docker network.
docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka:29092
# Linux host networking reaches the EXTERNAL listener advertised as localhost:9092.
# Docker Desktop requires host networking enabled for this optional local check.
docker run --rm --network host --entrypoint /opt/kafka/bin/kafka-broker-api-versions.sh \
  apache/kafka:4.2.2 --bootstrap-server localhost:9092

topic="day1-smoke-$(date +%s)-$$"
kafka() { docker compose exec -T kafka "/opt/kafka/bin/$1" "${@:2}"; }
cleanup() { kafka kafka-topics.sh --bootstrap-server kafka:29092 --delete --topic "$topic" || true; }
trap cleanup EXIT
kafka kafka-topics.sh --bootstrap-server kafka:29092 --create --topic "$topic" --partitions 1 --replication-factor 1
printf '%s\n' 'day1-persistence-probe' | kafka kafka-console-producer.sh --bootstrap-server kafka:29092 --topic "$topic"
# Recreate the container to prove the volume holds broker logs.
docker compose up -d --no-deps --force-recreate --wait --wait-timeout 180 kafka
message=$(kafka kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic "$topic" \
  --from-beginning --max-messages 1 --timeout-ms 30000)
test "$message" = 'day1-persistence-probe'
echo 'PASS: Kafka internal/external listeners and persistence across container recreation'
