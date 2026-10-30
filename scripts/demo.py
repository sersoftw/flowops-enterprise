#!/usr/bin/env python3
"""Local Compose demonstration. Uses only stdlib and each service's own database role."""
import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CUSTOMER = "00000000-0000-4000-8000-000000000007"
SCENARIOS = {
    "approved": {"stock": 10, "quantity": 2, "price": 12.50, "state": "COMPLETED", "balance": [8, 2]},
    "insufficient": {"stock": 1, "quantity": 2, "price": 12.50, "state": "CANCELLED", "balance": [1, 0]},
    "declined": {"stock": 5, "quantity": 1, "price": 1001, "state": "CANCELLED", "balance": [5, 0]},
}

def compose(*args, input=None):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, input=input,
                          text=True, capture_output=True, check=True, timeout=300).stdout.strip()


def sql(schema, statement):
    # Identifiers and credential variable names come solely from this fixed allowlist.
    role, password = {"orders": ("orders_app", "ORDER_DB_PASSWORD"),
                      "inventory": ("inventory_app", "INVENTORY_DB_PASSWORD"),
                      "payments": ("payments_app", "PAYMENT_DB_PASSWORD")}[schema]
    command = 'PGPASSWORD="$' + password + '" exec psql -X -qAt -h 127.0.0.1 -U ' + role + ' -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
    return compose("exec", "-T", "postgres", "sh", "-eu", "-c", command, input=statement)


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def sku(name):
    return "flowops-demo-v1-" + name


def key(name):
    return "flowops-demo-v1-" + name


def seed():
    statements = ["BEGIN;", "SELECT pg_advisory_xact_lock(7001001);"]
    for name, spec in SCENARIOS.items():
        product = literal(sku(name))
        statements.extend([
            f"INSERT INTO inventory.product(id,name) VALUES ({product},'Fictitious FlowOps demo product') ON CONFLICT(id) DO NOTHING;",
            f"INSERT INTO inventory.stock(product_id,available,reserved) VALUES ({product},{spec['stock']},0) ON CONFLICT(product_id) DO NOTHING;",
        ])
    statements.append("COMMIT;")
    sql("inventory", "\n".join(statements))
    for name, spec in SCENARIOS.items():
        current = stock(name)
        require(sum(current) == spec["stock"], f"Demo stock was changed externally for {name}; seed never resets balances")
    print("Demo seed ready; existing stock and reservations preserved.")


def stock(name):
    raw = sql("inventory", f"SELECT json_build_array(available,reserved) FROM inventory.stock WHERE product_id={literal(sku(name))};")
    require(bool(raw), "Missing demo product; run seed-demo-data first")
    return json.loads(raw)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def http(path, payload=None, idempotency=None, port=8081):
    headers = {"Content-Type": "application/json"}
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    request = urllib.request.Request(f"http://localhost:{port}" + path,
                                     data=None if payload is None else json.dumps(payload).encode(), headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.load(response), response.headers


def wait(predicate, label, seconds=90):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last = str(error)
        time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for {label}; last network error={last}")


def health():
    for port in (8081, 8082, 8083):
        wait(lambda: http("/actuator/health", port=port)[1].get("status") == "UP", f"health on {port}")


def evidence(name, order):
    oid = literal(order)
    history = json.loads(sql("orders", f"SELECT json_agg(to_state ORDER BY id) FROM orders.saga_history WHERE order_id={oid} AND from_state IS DISTINCT FROM to_state;"))
    payment = sql("payments", f"SELECT status FROM payments.payment WHERE order_id={oid};") or None
    reservation = sql("inventory", f"SELECT status FROM inventory.reservation WHERE order_id={oid};") or None
    events = json.loads(sql("orders", f"SELECT json_agg(event_type ORDER BY publication_sequence) FROM orders.event_outbox WHERE aggregate_id={oid};"))
    return {"orderId": order, "history": history, "payment": payment, "reservation": reservation, "orderEvents": events}


def scenario(name, spec):
    before = stock(name)
    payload = {"customerId": CUSTOMER, "currency": "EUR", "items": [
        {"productId": sku(name), "quantity": spec["quantity"], "unitPrice": spec["price"]}]}
    code, order, headers = http("/api/orders", payload, key(name))
    require(code in (200, 201), f"Unexpected creation status {code}")
    oid = order["id"]
    require(headers.get("X-Correlation-Id") == oid, "Lost correlation header")
    if code == 201:
        require(order["status"] == "PENDING", "Fresh POST must return the initial state")
    def terminal():
        current = http("/api/orders/" + oid)[1]
        require(current["status"] != "MANUAL_REVIEW", f"Unexpected MANUAL_REVIEW for healthy demo {name}")
        return current if current["status"] == spec["state"] else None
    final = wait(terminal, f"{name} -> {spec['state']}")
    after = stock(name)
    require(after == spec["balance"], f"Wrong final stock for {name}: {after}")
    details = evidence(name, oid)
    expected_history = ["PENDING", "CANCELLED"] if name == "insufficient" else (
        ["PENDING", "INVENTORY_RESERVED", "COMPLETED"] if name == "approved" else
        ["PENDING", "INVENTORY_RESERVED", "PAYMENT_FAILED_PENDING_COMPENSATION", "RELEASING_INVENTORY", "CANCELLED"])
    require(details["history"] == expected_history, f"Unexpected persisted history: {details}")
    expected_payment = {"approved": "SUCCEEDED", "insufficient": None, "declined": "FAILED"}[name]
    require(details["payment"] == expected_payment, f"Unexpected payment: {details}")
    require(details["reservation"] == {"approved": "RESERVED", "insufficient": None, "declined": "RELEASED"}[name], "Wrong reservation state")
    expected_events = ["OrderCreated"] + ([] if name == "insufficient" else ["PaymentRequested"]) + (["InventoryReleaseRequested"] if name == "declined" else [])
    require(details["orderEvents"] == expected_events, "Duplicate or unexpected logical outbox event")
    replay_code, replay, _ = http("/api/orders", payload, key(name))
    require(replay_code == 200 and replay["id"] == oid and replay["status"] == final["status"], "HTTP replay was not stable")
    require(stock(name) == after, "Replay changed stock")
    count = sql("orders", f"SELECT count(*) FROM orders.purchase_order WHERE idempotency_key={literal(key(name))};")
    require(count == "1", "Duplicate demo order")
    if name != "insufficient":
        require(sql("payments", f"SELECT count(*) FROM payments.payment_attempt a JOIN payments.payment p ON p.id=a.payment_id WHERE p.order_id={literal(oid)};") == "1", "Duplicate payment attempt")
    return {"scenario": name, "httpStatus": code, "state": final["status"], "stockBefore": before,
            "stockAfter": after, **details}


def run(restart, output):
    health()
    report = {"formatVersion": 1, "stockColumns": ["available", "reserved"],
              "scenarios": [scenario(name, spec) for name, spec in SCENARIOS.items()]}
    if restart:
        compose("restart", "order-service", "inventory-service", "payment-service")
        health()
        repeated = [scenario(name, spec) for name, spec in SCENARIOS.items()]
        for before, after in zip(report["scenarios"], repeated):
            require(after["httpStatus"] == 200, "Restart lost idempotency state")
            for field in ("orderId", "state", "stockAfter", "history", "payment", "reservation", "orderEvents"):
                require(before[field] == after[field], f"Restart changed {field}")
        report["afterRestart"] = repeated
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Demo checks passed; evidence: {target}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("seed", "scenarios"))
    parser.add_argument("--restart", action="store_true", help="Restart all three apps and verify durable state again")
    parser.add_argument("--output", default="build/demo-evidence.json")
    args = parser.parse_args()
    if args.command == "seed":
        seed()
    else:
        run(args.restart, args.output)


if __name__ == "__main__":
    main()
