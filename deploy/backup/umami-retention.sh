#!/usr/bin/env bash
# Delete Umami analytics older than UMAMI_RETENTION_DAYS (default 730, two
# years) from Umami's own database: the privacy policy's "Analytics: two
# years, then deleted". The SQL is deploy/analytics/umami-retention.sql,
# baked into the image at /app/umami-retention.sql beside the other analytics
# SQL; it says which tables it trims and why.
#
# loop.sh calls this once a day straight after a successful backup.sh, so the
# dump taken minutes earlier still holds every row this removes. It lives in
# the deploy stack, not in Pressroom's Java: Pressroom does not depend on
# Umami's database. Run one pass by hand:
#
#   docker compose -f docker-compose.prod.yml exec backup /app/backup/umami-retention.sh
#
# It connects as POSTGRES_USER, the same role the analytics-views one-shot
# uses. It prints a count per table and never a row. Exits non-zero on
# failure; loop.sh logs that and carries on, and tomorrow's run deletes what
# today's did not. UMAMI_RETENTION_DAYS=0 switches it off. A stack with no
# Umami database, or one whose Umami has not booted yet, has nothing to trim
# and exits 0.
#
# ANALYTICS_SQL_DIR (default /app) exists for the tests.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
# An unreachable server fails the night's run instead of hanging the loop.
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-30}"

UMAMI_DB="${UMAMI_DB:-umami}"
SQL_DIR="${ANALYTICS_SQL_DIR:-/app}"
# Empty means the SQL's own default (730), which is kept in one place.
DAYS="${UMAMI_RETENTION_DAYS:-}"

stamp() { date -u +%FT%TZ; }

if [[ -n "${DAYS}" && ! "${DAYS}" =~ ^[0-9]{1,6}$ ]]; then
    echo "[$(stamp)] UMAMI_RETENTION_DAYS must be a whole number of days (0 = off); nothing deleted." >&2
    exit 1
fi
if [[ -n "${DAYS}" ]] && (( 10#${DAYS} == 0 )); then
    echo "[$(stamp)] Umami retention is off (UMAMI_RETENTION_DAYS=0); nothing deleted."
    exit 0
fi

# Asked of the maintenance database, so a missing Umami database is an
# answer rather than a connection error.
if ! exists="$(psql -d postgres -X --quiet -tAc \
        "SELECT 1 FROM pg_database WHERE datname = ${UMAMI_DB@Q}")"; then
    echo "[$(stamp)] Umami retention: cannot reach PostgreSQL at ${PGHOST}:${PGPORT}." >&2
    exit 1
fi
if [[ "${exists}" != "1" ]]; then
    echo "[$(stamp)] Umami retention: no Umami database (${UMAMI_DB}) on this stack; nothing to trim."
    exit 0
fi
if ! psql -d "${UMAMI_DB}" -X --quiet -tAc \
        "SELECT to_regclass('public.website_event') IS NOT NULL" | grep -qx t; then
    echo "[$(stamp)] Umami retention: Umami has not created its tables yet; nothing to trim."
    exit 0
fi

vars=(-v ON_ERROR_STOP=1 -v VERBOSITY=terse)
if [[ -n "${DAYS}" ]]; then
    vars+=(-v "retention_days=$(( 10#${DAYS} ))")
fi

echo "[$(stamp)] Umami retention: trimming ${UMAMI_DB}..."
# Not --single-transaction: the SQL commits after every batch.
psql -d "${UMAMI_DB}" -X --quiet "${vars[@]}" -f "${SQL_DIR}/umami-retention.sql"
echo "[$(stamp)] Umami retention complete."
