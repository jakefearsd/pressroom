#!/usr/bin/env bash
# Pressroom production backup -- single shot: pg_dump Pressroom's database,
# tar the mediafiles/uploads volumes, then rotate anything older than
# BACKUP_RETENTION_DAYS. Pressroom's own data only: the back-office
# databases are dumped by backoffice-backup.sh, beside their services
# (wiki SA-15). Both artifacts are written atomically: pg_dump/tar
# target "<final-name>.tmp" and a same-filesystem `mv` publishes it under
# its final name only once the write is complete, so a backup killed
# mid-write (OOM, container restart, disk full) leaves an orphaned .tmp
# file rather than a truncated file sitting under the name a restore would
# trust. A dump is published only once `pg_restore -l` has read it back.
#
# The search index is NOT archived: the app rebuilds it from the database
# whenever it finds none (LuceneIndexManager), and it is the volume whose
# files Lucene merges and deletes while tar reads them.
#
# Everything here lives in the pressroom-backups volume, on the same disk as
# the database it protects. Copy it OFF the host (docker_deployment.md,
# "Backup and restore"), e.g. nightly from the host's crontab:
#
#   docker compose cp backup:/backups/. /srv/pressroom-backups/
#
# then ship that directory elsewhere (rsync, rclone). The dumps hold password
# hashes: keep the copy as private as the host. Runs inside the `backup` service of
# docker-compose.prod.yml (the app image, which bakes in postgresql-client
# for pg_dump/psql alongside tar from the base image, so all three are
# already present); deploy/backup/loop.sh calls this once a day at
# /app/backup/backup.sh, or an operator can run one cycle by hand:
#
#   docker compose exec backup /app/backup/backup.sh
#
# RESTORE
# =======
#
# Database (drops and recreates objects in place -- take the app down first
# with `docker compose stop app`).
#
# IMPORTANT: run this via `exec ... bash -c '...'` with the command
# SINGLE-QUOTED, exactly like below -- $POSTGRES_USER/$POSTGRES_PASSWORD/
# $POSTGRES_DB only exist inside the postgres container's environment (set
# by docker-compose.prod.yml). A double-quoted or unquoted command expands
# them in your HOST shell instead, where they're unset, and pg_restore/psql
# silently gets empty values (see deploy.sh's own migration step for the
# same pattern):
#
#   docker compose exec -T postgres bash -c '
#     set -euo pipefail
#     export PGHOST=localhost
#     export PGUSER="${POSTGRES_USER}"
#     export PGPASSWORD="${POSTGRES_PASSWORD}"
#     pg_restore -d "${POSTGRES_DB}" --clean --if-exists
#   ' < /path/on/host/to/pressroom-<timestamp>.dump
#
#   docker compose start app
#
#   The back-office databases (Umami's, listmonk's) are not here: the
#   backoffice-backup service dumps them from their own servers
#   (/app/backup/backoffice-backup.sh, which says how to restore them).
#
#   (The dump lives in the pressroom-backups volume, at
#   /backups/pressroom-<timestamp>.dump inside the backup container --
#   `docker compose cp backup:/backups/pressroom-<timestamp>.dump .` to get it
#   onto the host first if restoring from a different machine.)
#
# Media/uploads volumes (stack must be down -- the archive extracts to
# /data/... paths, matching the volume mount points). The search index is
# not in the archive: remove its volume so the app rebuilds it from the
# restored database (an index left from before would answer for entries the
# database no longer has).
#
# The volume names below are Compose's default PROJECT-PREFIXED names, not
# the bare names declared in docker-compose.prod.yml -- the project name is
# derived from the directory the compose file lives in (`pressroom` if you
# put it in /opt/pressroom per docker_deployment.md), giving e.g.
# `pressroom_pressroom-mediafiles`. Run `docker volume ls` FIRST to confirm the
# exact names on your host and substitute them below if they differ:
#
#   docker compose down
#   docker volume rm pressroom_pressroom-search-index
#   docker run --rm \
#     -v pressroom_pressroom-mediafiles:/data/mediafiles \
#     -v pressroom_pressroom-uploads:/data/uploads \
#     -v pressroom_pressroom-backups:/backups \
#     postgres:18@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280 \
#     tar xzf /backups/volumes-<timestamp>.tar.gz -C /
#   docker compose up -d
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

# Dumps database $1 to $2, published under that name only once the dump is
# complete AND pg_restore can read its table of contents back: a dump that
# cannot be listed cannot be restored, and must not sit under the name a
# restore would trust.
dump_database() {
    local db="$1" file="$2"
    pg_dump -Fc -d "${db}" -f "${file}.tmp"
    if ! pg_restore -l "${file}.tmp" >/dev/null; then
        echo "  ${db}: pg_restore cannot read the dump just written; not published." >&2
        rm -f "${file}.tmp"
        exit 1
    fi
    mv "${file}.tmp" "${file}"
}

DUMP_FILE="${BACKUP_DIR}/pressroom-${TIMESTAMP}.dump"
dump_database "${DB_NAME}" "${DUMP_FILE}"
echo "  database -> ${DUMP_FILE}"

# The volumes are live: the app writes media while this reads them. GNU tar
# exits 1 for "file changed as we read it" (the archive is written, that
# file's copy may be torn) and 2 for a real failure. Under set -e the 1
# used to end the run before the archive was published and before rotation,
# and upgrade.sh then refused to run without --no-backup. Exit 1 is accepted,
# and its warnings are quieted; anything above it fails the backup.
VOLUMES_FILE="${BACKUP_DIR}/volumes-${TIMESTAMP}.tar.gz"
tar_rc=0
tar --create --gzip --file "${VOLUMES_FILE}.tmp" \
    --warning=no-file-changed --warning=no-file-removed \
    -C / data/mediafiles data/uploads || tar_rc=$?
if (( tar_rc > 1 )); then
    echo "  volumes: tar failed (exit ${tar_rc}); not published." >&2
    rm -f "${VOLUMES_FILE}.tmp"
    exit 1
fi
mv "${VOLUMES_FILE}.tmp" "${VOLUMES_FILE}"
echo "  volumes  -> ${VOLUMES_FILE}"

echo "Rotating backups older than ${RETENTION_DAYS} day(s)..."
find "${BACKUP_DIR}" -maxdepth 1 -type f \
    \( -name 'pressroom-*.dump' -o -name '*-[0-9]*T[0-9]*Z.dump' \
       -o -name 'volumes-*.tar.gz' -o -name '*.tmp' \) \
    -mtime "+${RETENTION_DAYS}" -print -delete

echo "[$(date -u +%FT%TZ)] Backup complete."
