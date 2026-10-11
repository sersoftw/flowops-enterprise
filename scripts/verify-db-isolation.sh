#!/usr/bin/env bash
set -euo pipefail
# Authenticate over TCP as each application role, never as the administrator.
docker compose exec -T postgres sh -eu <<'SH'
query() {
  PGPASSWORD="$password" psql -X -h 127.0.0.1 -U "$role" -d "$POSTGRES_DB" \
    -v ON_ERROR_STOP=1 -Atqc "$1"
}
for schema in orders inventory payments; do
  case "$schema" in
    orders) role=orders_app; password="$ORDER_DB_PASSWORD"; service=order-service ;;
    inventory) role=inventory_app; password="$INVENTORY_DB_PASSWORD"; service=inventory-service ;;
    payments) role=payments_app; password="$PAYMENT_DB_PASSWORD"; service=payment-service ;;
  esac
  test "$(query "SELECT service_name FROM $schema.bootstrap_marker")" = "$service"
  test "$(query "SELECT count(*) FROM $schema.flyway_schema_history WHERE version='1' AND success")" = 1
  test "$(query "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls FROM pg_roles WHERE rolname=current_user")" = f
  test "$(query "SELECT pg_get_userbyid(nspowner)=current_user FROM pg_namespace WHERE nspname='$schema'")" = t
  test "$(query "SELECT has_database_privilege(current_user,current_database(),'CREATE') OR has_schema_privilege(current_user,'public','CREATE') OR has_schema_privilege(current_user,'public','USAGE')")" = f
  query "BEGIN; CREATE TABLE $schema.isolation_probe(id integer); INSERT INTO $schema.isolation_probe VALUES(1); ROLLBACK;" >/dev/null
  for other in orders inventory payments; do
    [ "$other" = "$schema" ] && continue
    test "$(query "SELECT has_schema_privilege(current_user,'$other','USAGE') OR has_schema_privilege(current_user,'$other','CREATE')")" = f
    # Require the expected denial; a connection error alone is not proof of isolation.
    if output=$(query "SELECT * FROM $other.bootstrap_marker" 2>&1); then
      echo "FAIL: $role can read $other" >&2; exit 1
    fi
    printf '%s\n' "$output" | grep -q "permission denied for schema $other"
    if output=$(query "BEGIN; CREATE TABLE $other.isolation_probe(id integer); ROLLBACK;" 2>&1); then
      echo "FAIL: $role can write $other" >&2; exit 1
    fi
    printf '%s\n' "$output" | grep -q "permission denied for schema $other"
  done
  echo "PASS: $role owns $schema, migrations applied, other schemas denied"
done
SH
