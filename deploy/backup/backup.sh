#!/usr/bin/env bash
# Pressroom production backup -- single shot: pg_dump the database, tar the
# mediafiles/search-index/uploads volumes, then rotate anything older than
# BACKUP_RETENTION_DAYS. Both artifacts are written atomically: pg_dump/tar
# target "<final-name>.tmp" and a same-filesystem `mv` publishes it under
# its final name only once the write is complete, so a backup killed
# mid-write (OOM, container restart, disk full) leaves an orphaned .tmp
# file rather than a truncated file sitting under the name a restore would
# trust. Runs inside the `backup` service of
# docker-compose.prod.yml (the app image, which bakes in postgresql-client
# for pg_dump/psql alongside tar from the base image, so all three are
# already present); deploy/backup/loop.sh calls this once a day at
# /app/backup/backup.sh, or an operator can run one cycle by hand:
#
#   docker compose -f docker-compose.prod.yml exec backup /app/backup/backup.sh
#
# RESTORE
# =======
#
# Database (drops and recreates objects in place -- take the app down first
# with `docker compose -f docker-compose.prod.yml stop app`).
#
# IMPORTANT: run this via `exec ... bash -c '...'` with the command
# SINGLE-QUOTED, exactly like below -- $POSTGRES_USER/$POSTGRES_PASSWORD/
# $POSTGRES_DB only exist inside the postgres container's environment (set
# by docker-compose.prod.yml). A double-quoted or unquoted command expands
# them in your HOST shell instead, where they're unset, and pg_restore/psql
# silently gets empty values (see deploy.sh's own migration step for the
# same pattern):
#
#   docker compose -f docker-compose.prod.yml exec -T postgres bash -c '
#     set -euo pipefail
#     export PGHOST=localhost
#     export PGUSER="${POSTGRES_USER}"
#     export PGPASSWORD="${POSTGRES_PASSWORD}"
#     pg_restore -d "${POSTGRES_DB}" --clean --if-exists
#   ' < /path/on/host/to/pressroom-<timestamp>.dump
#
#   docker compose -f docker-compose.prod.yml start app
#
#   (The dump lives in the pressroom-backups volume, at
#   /backups/pressroom-<timestamp>.dump inside the backup container --
#   `docker compose cp backup:/backups/pressroom-<timestamp>.dump .` to get it
#   onto the host first if restoring from a different machine.)
#
# Media/search-index/uploads volumes (stack must be down -- the archive
# extracts to /data/... paths, matching the volume mount points).
#
# The volume names below are Compose's default PROJECT-PREFIXED names, not
# the bare names declared in docker-compose.prod.yml -- the project name is
# derived from the directory the compose file lives in (`pressroom` if you
# put it in /opt/pressroom per docker_deployment.md), giving e.g.
# `pressroom_pressroom-mediafiles`. Run `docker volume ls` FIRST to confirm the
# exact names on your host and substitute them below if they differ:
#
#   docker compose -f docker-compose.prod.yml down
#   docker run --rm \
#     -v pressroom_pressroom-mediafiles:/data/mediafiles \
#     -v pressroom_pressroom-search-index:/data/search-index \
#     -v pressroom_pressroom-uploads:/data/uploads \
#     -v pressroom_pressroom-backups:/backups \
#     postgres:18@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280 \
#     tar xzf /backups/volumes-<timestamp>.tar.gz -C /
#   docker compose -f docker-compose.prod.yml up -d
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
DB_NAME="${POSTGRES_DB:?POSTGRES_DB must be set}"

mkdir -p "${BACKUP_DIR}"

echo "[$(date -u +%FT%TZ)] Starting backup..."

DUMP_FILE="${BACKUP_DIR}/pressroom-${TIMESTAMP}.dump"
pg_dump -Fc -d "${DB_NAME}" -f "${DUMP_FILE}.tmp"
mv "${DUMP_FILE}.tmp" "${DUMP_FILE}"
echo "  database -> ${DUMP_FILE}"

# The service databases (analytics, newsletter), if this stack runs them.
# Dumped separately rather than with pg_dumpall so each restores on its own:
# losing analytics history should never be a reason to hesitate over restoring
# the blog. Missing is not an error -- a stack deployed before these services
# existed simply has no such database, and a backup run must not start failing
# because of it.
SERVICE_DBS="${UMAMI_DB:-umami} ${LISTMONK_DB:-listmonk}"
for service_db in ${SERVICE_DBS}; do
    if psql -d "${DB_NAME}" -tAc \
            "SELECT 1 FROM pg_database WHERE datname = ${service_db@Q}" | grep -q 1; then
        SERVICE_DUMP="${BACKUP_DIR}/${service_db}-${TIMESTAMP}.dump"
        pg_dump -Fc -d "${service_db}" -f "${SERVICE_DUMP}.tmp"
        mv "${SERVICE_DUMP}.tmp" "${SERVICE_DUMP}"
        echo "  ${service_db} -> ${SERVICE_DUMP}"
    else
        echo "  ${service_db} -> none (no such database on this stack)"
    fi
done

VOLUMES_FILE="${BACKUP_DIR}/volumes-${TIMESTAMP}.tar.gz"
tar czf "${VOLUMES_FILE}.tmp" -C / data/mediafiles data/search-index data/uploads
mv "${VOLUMES_FILE}.tmp" "${VOLUMES_FILE}"
echo "  volumes  -> ${VOLUMES_FILE}"

echo "Rotating backups older than ${RETENTION_DAYS} day(s)..."
find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name 'pressroom-*.dump' -o -name '*-[0-9]*T[0-9]*Z.dump' \
       -o -name 'volumes-*.tar.gz' -o -name '*.tmp' \) \
    -mtime "+${RETENTION_DAYS}" -print -delete

echo "[$(date -u +%FT%TZ)] Backup complete."
