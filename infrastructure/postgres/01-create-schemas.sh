#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_USER:?}"
: "${POSTGRES_DB:?}"
: "${ORDER_DB_PASSWORD:?}"
: "${INVENTORY_DB_PASSWORD:?}"
: "${PAYMENT_DB_PASSWORD:?}"

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=orders_password="$ORDER_DB_PASSWORD" \
  --set=inventory_password="$INVENTORY_DB_PASSWORD" \
  --set=payments_password="$PAYMENT_DB_PASSWORD" <<'SQL'
-- psql :'var' safely quotes string literals to avoid SQL injection.
CREATE ROLE orders_app LOGIN PASSWORD :'orders_password';
CREATE ROLE inventory_app LOGIN PASSWORD :'inventory_password';
CREATE ROLE payments_app LOGIN PASSWORD :'payments_password';

REVOKE ALL ON DATABASE flowops FROM PUBLIC;
GRANT CONNECT ON DATABASE flowops TO orders_app, inventory_app, payments_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA orders AUTHORIZATION orders_app;
CREATE SCHEMA inventory AUTHORIZATION inventory_app;
CREATE SCHEMA payments AUTHORIZATION payments_app;
SQL
