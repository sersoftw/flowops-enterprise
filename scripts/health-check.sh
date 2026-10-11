#!/usr/bin/env bash
set -euo pipefail

check() {
  local name="$1" url="$2" count=0
  echo "Waiting for $name at $url"
  until curl --fail --silent --show-error --max-time 5 "$url" | grep -q '"status":"UP"'; do
    count=$((count + 1))
    if [ "$count" -ge 48 ]; then
      echo "FAILED: $name did not become healthy in 240 seconds" >&2
      exit 1
    fi
    sleep 5
  done
  echo "HEALTHY: $name"
}
check order-service http://localhost:8081/actuator/health
check inventory-service http://localhost:8082/actuator/health
check payment-service http://localhost:8083/actuator/health
printf '%s\n' '3/3 healthy'
