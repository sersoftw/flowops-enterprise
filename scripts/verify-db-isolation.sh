#!/usr/bin/env bash
set -euo pipefail
# Run from repository root after 'docker compose ... up -d'.
COMPOSE=(docker compose -f infrastructure/docker/compose.yml --env-file .env)
"${COMPOSE[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT nspname || '\'' | '\'' || pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname IN ('\''orders'\'','\''inventory'\'','\''payments'\'') ORDER BY nspname"'
