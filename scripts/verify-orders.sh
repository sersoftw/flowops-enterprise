#!/usr/bin/env bash
set -euo pipefail

# Run against the disposable Compose stack. Requires curl and Python 3.
base="${ORDER_BASE_URL:-http://localhost:8081}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
key=$(python3 -c 'import uuid; print(uuid.uuid4())')
customer=$(python3 -c 'import uuid; print(uuid.uuid4())')
body="{\"customerId\":\"$customer\",\"currency\":\"EUR\",\"items\":[{\"productId\":\"smoke-sku\",\"quantity\":2,\"unitPrice\":12.50}]}"

request() {
    local expected=$1
    shift
    local status
    status=$(curl --silent --show-error --max-time 20 -o "$work/response.json" -w '%{http_code}' "$@")
    if [[ "$status" != "$expected" ]]; then
        cat "$work/response.json"
        echo "Expected HTTP $expected, got $status" >&2
        exit 1
    fi
}

request 201 -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body" "$base/api/orders"
id=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["status"] == "PENDING" and d["total"] == 25; print(d["id"])' "$work/response.json")
request 200 "$base/api/orders?customerId=$customer&status=PENDING&size=1"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["totalElements"] == 1 and d["content"][0]["id"] == sys.argv[2]' "$work/response.json" "$id"
request 400 -H 'Content-Type: application/json' -H "Idempotency-Key: invalid-$key" -d '{}' "$base/api/orders"
request 409 -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "${body/12.50/13.00}" "$base/api/orders"

docker compose restart order-service
bash scripts/health-check.sh
request 200 "$base/api/orders/$id"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["id"] == sys.argv[2] and d["total"] == 25' "$work/response.json" "$id"
request 200 -H 'Content-Type: application/json' -H "Idempotency-Key: $key" -d "$body" "$base/api/orders"
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["id"] == sys.argv[2]' "$work/response.json" "$id"
echo 'Orders: creation, listing, validation, conflict, restart persistence and replay passed'
