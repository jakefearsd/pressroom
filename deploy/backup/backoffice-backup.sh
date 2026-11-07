#!/usr/bin/env bash
# Back-office backup -- single shot: pg_dump each back-office database
# (Umami's, listmonk's) from its own server with its own login, then rotate
# anything older than BACKUP_RETENTION_DAYS. Runs inside the
# `backoffice-backup` service of docker-compose.backoffice.yml, beside the
# services it protects (wiki SA-15); Pressroom's own database and media are
# backup.sh's. deploy/backup/loop.sh calls this once a day, and Umami's
# retention after it succeeds; or run one cycle by hand:
#
#   docker compose exec backoffice-backup /app/backup/backoffice-backup.sh
#
# Each database is dumped separately, so each restores on its own, and a
# fault on one server costs that dump only: the other is still written, and
# the run fails afterwards (loop.sh then skips retention and says so). A
# database that does not exist (a service that never started) is reported
# and is not a failure; a server that cannot be asked is. A dump is published
# under its final name only once `pg_restore -l` has read it back.
#
# Copy the volume OFF the host as for the core backup (docker_deployment.md,
# "Backup and restore"):
#
#   docker compose cp backoffice-backup:/backups/. /srv/pressroom-backoffice-backups/
#
# RESTORE
# =======
#
# Into the service's own server, as its own login, with the service stopped.
# Single-quoted, so the variables expand inside the database container:
#
#   docker compose stop umami
#   docker compose exec -T umami-db bash -c '
#     set -euo pipefail
#     export PGHOST=localhost PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}"
#     pg_restore -d "${POSTGRES_DB}" --clean --if-exists --no-owner --no-acl
#   ' < /path/on/host/to/umami-<timestamp>.dump
#   docker compose start umami
#   docker compose up -d analytics-views   # re-grants grafana_ro
#
# listmonk the same way: stop listmonk, restore listmonk-<timestamp>.dump
# into listmonk-db, start listmonk.
#
# Logins are passed to each command in its own environment, never on a
# command line and never printed.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
# An unreachable server fails its dump instead of hanging the night's run.
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-30}"
# Each server's login is passed explicitly; none leaks in from outside.
unset PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

mkdir -p "${BACKUP_DIR}"
echo "[$(date -u +%FT%TZ)] Starting back-office backup..."

# dump_service DB HOST PORT USER PASSWORD: dump DB from its server, or say
# why not. Returns non-zero when the dump should have been written and was
# not.
dump_service() {
    local db="$1" host="$2" port="$3" user="$4" password="$5" exists file
    if ! exists="$(PGHOST="${host}" PGPORT="${port}" PGUSER="${user}" PGPASSWORD="${password}" \
            psql -d postgres -X -tAc "SELECT 1 FROM pg_database WHERE datname = ${db@Q}")"; then
        echo "  ${db} -> FAILED: cannot reach ${host}" >&2
        return 1
    fi
    if [[ "${exists}" != 1 ]]; then
        echo "  ${db} -> none (no such database on ${host})"
        return 0
    fi
    file="${BACKUP_DIR}/${db}-${TIMESTAMP}.dump"
    if ! PGHOST="${host}" PGPORT="${port}" PGUSER="${user}" PGPASSWORD="${password}" \
            pg_dump -Fc -d "${db}" -f "${file}.tmp"; then
        echo "  ${db} -> FAILED: pg_dump from ${host}" >&2
        rm -f "${file}.tmp"
        return 1
    fi
    if ! pg_restore -l "${file}.tmp" >/dev/null; then
        echo "  ${db} -> FAILED: pg_restore cannot read the dump just written; not published." >&2
        rm -f "${file}.tmp"
        return 1
    fi
    mv "${file}.tmp" "${file}"
    echo "  ${db} -> ${file} (from ${host})"
}

failed=""
UMAMI_DB_NAME="${UMAMI_DB:-umami}"
dump_service "${UMAMI_DB_NAME}" "${UMAMI_PGHOST:-umami-db}" "${UMAMI_PGPORT:-5432}" \
    "${UMAMI_DB_USER:-umami}" "${UMAMI_DB_PASSWORD:?UMAMI_DB_PASSWORD must be set}" \
    || failed="${failed} ${UMAMI_DB_NAME}"
LISTMONK_DB_NAME="${LISTMONK_DB:-listmonk}"
dump_service "${LISTMONK_DB_NAME}" "${LISTMONK_PGHOST:-listmonk-db}" "${LISTMONK_PGPORT:-5432}" \
    "${LISTMONK_DB_USER:-listmonk}" "${LISTMONK_DB_PASSWORD:?LISTMONK_DB_PASSWORD must be set}" \
    || failed="${failed} ${LISTMONK_DB_NAME}"

echo "Rotating back-office backups older than ${RETENTION_DAYS} day(s)..."
find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name '*-[0-9]*T[0-9]*Z.dump' -o -name '*.tmp' \) \
    -mtime "+${RETENTION_DAYS}" -print -delete

if [[ -n "${failed}" ]]; then
    echo "[$(date -u +%FT%TZ)] Back-office backup INCOMPLETE: not written:${failed}." >&2
    exit 1
fi
echo "[$(date -u +%FT%TZ)] Back-office backup complete."
