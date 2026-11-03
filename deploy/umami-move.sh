#!/usr/bin/env bash
# One-shot (compose service `umami-move`): copy Umami's database from
# Pressroom's PostgreSQL (`postgres`) to Umami's own (`umami-db`), once.
#
# Until 0.7.2 Umami kept its data in a database on Pressroom's server. From
# 0.7.2 it has a server of its own, and this moves an existing host's data
# there on the first deploy, before the new `umami` container starts (compose
# makes `umami` wait for this to exit 0). It decides from what it finds, so it
# is safe on every `up -d`:
#
#   umami-db already has tables      -> nothing to do (moved before, or Umami
#                                       booted there first): exit 0
#   no ${UMAMI_DB} on postgres, or   -> a fresh install, nothing to move:
#   one without website_event           exit 0, Umami initialises umami-db
#   otherwise                        -> pg_dump from postgres, pg_restore into
#                                       umami-db in ONE transaction, print the
#                                       copied counts: exit 0
#
# Anything it cannot check or do fails the run (exit 1) and leaves umami-db as
# it was: a server it cannot ask is not a server with nothing in it. Compose
# then does not start `umami`, so analytics is off (the blog is not: caddy
# does not wait for umami) and deploy.sh fails, until a deploy succeeds.
#
# The old database on postgres is left exactly as it was: it is the rollback
# point (0.7.1's compose file points straight at it) and is dropped by hand
# once the move is validated (docker_deployment.md, "Upgrading to 0.7.2").
#
# Objects are restored --no-owner --no-acl, so they belong to UMAMI_DB_USER
# and the old server's roles never need to exist here; analytics-views
# re-creates grafana_ro's grants after Umami starts.
#
# Two logins, each passed to its own command in that command's environment
# (PGPASSWORD=... cmd), never on a command line and never printed.
set -euo pipefail

SOURCE_HOST="${PGHOST:-postgres}"
SOURCE_PORT="${PGPORT:-5432}"
SOURCE_USER="${POSTGRES_USER:?POSTGRES_USER must be set}"
SOURCE_PASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
TARGET_HOST="${UMAMI_PGHOST:-umami-db}"
TARGET_PORT="${UMAMI_PGPORT:-5432}"
TARGET_USER="${UMAMI_DB_USER:-umami}"
TARGET_PASSWORD="${UMAMI_DB_PASSWORD:?UMAMI_DB_PASSWORD must be set}"
UMAMI_DB="${UMAMI_DB:-umami}"
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-30}"
# Neither login's environment leaks into the other's commands.
unset PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE

# on_source/on_target CMD ARGS...: run a client command against one server.
on_source() {
    PGHOST="${SOURCE_HOST}" PGPORT="${SOURCE_PORT}" PGUSER="${SOURCE_USER}" \
        PGPASSWORD="${SOURCE_PASSWORD}" "$@"
}
on_target() {
    PGHOST="${TARGET_HOST}" PGPORT="${TARGET_PORT}" PGUSER="${TARGET_USER}" \
        PGPASSWORD="${TARGET_PASSWORD}" "$@"
}

# The answer to one SQL question, or exit 1 naming the server it could not ask.
# Always assign it (x="$(ask ...)"): set -e stops the script on a failed
# assignment, but not on a failed substitution inside [[ ]] or a pipeline.
ask() {
    local side="$1" db="$2" sql="$3" answer
    if ! answer="$("on_${side}" psql -d "${db}" -X --quiet -tAc "${sql}")"; then
        local where="${SOURCE_HOST}"
        [[ "${side}" == target ]] && where="${TARGET_HOST}"
        echo "umami-move: cannot reach PostgreSQL at ${where} (database ${db}); nothing moved." >&2
        if [[ "${side}" == target ]]; then
            echo "If umami-db refused the login: it keeps the UMAMI_DB_PASSWORD it was first" \
                 "started with, so .env must hold that one (docker_deployment.md, \"If" \
                 "umami-db's password is lost\"). If UMAMI_DB was renamed since, umami-db" \
                 "has no database under the new name." >&2
        fi
        exit 1
    fi
    printf '%s' "${answer}"
}

TABLES_SQL="SELECT count(*) FROM pg_tables WHERE schemaname = 'public'"

echo "==> Checking whether Umami's data is already on ${TARGET_HOST}..."
tables="$(ask target "${UMAMI_DB}" "${TABLES_SQL}")"
if [[ "${tables}" != 0 ]]; then
    echo "    umami-db already holds Umami's data; nothing to move."
    exit 0
fi

echo "==> Looking for Umami's old database on ${SOURCE_HOST}..."
exists="$(ask source postgres "SELECT 1 FROM pg_database WHERE datname = ${UMAMI_DB@Q}")"
if [[ -z "${exists}" ]]; then
    echo "    no ${UMAMI_DB} database on ${SOURCE_HOST}: a fresh install, nothing to move."
    exit 0
fi
has_events="$(ask source "${UMAMI_DB}" "SELECT to_regclass('public.website_event') IS NOT NULL")"
if [[ "${has_events}" != t ]]; then
    echo "    ${UMAMI_DB} on ${SOURCE_HOST} has no Umami tables (Umami never ran there): nothing to move."
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
dump="${work}/umami.dump"

echo "==> Copying ${UMAMI_DB} from ${SOURCE_HOST} to ${TARGET_HOST}..."
if ! on_source pg_dump -Fc --no-owner --no-acl -d "${UMAMI_DB}" -f "${dump}"; then
    echo "umami-move: the dump from ${SOURCE_HOST} failed; nothing moved." >&2
    exit 1
fi
if ! on_target pg_restore --no-owner --no-acl --single-transaction --exit-on-error \
        -d "${UMAMI_DB}" "${dump}"; then
    echo "umami-move: the restore into ${TARGET_HOST} failed and was rolled back; umami-db is unchanged." >&2
    echo "The next deploy tries again. The old database on ${SOURCE_HOST} is untouched." >&2
    exit 1
fi

for table in website session website_event; do
    present="$(ask target "${UMAMI_DB}" "SELECT to_regclass('public.${table}') IS NOT NULL")"
    if [[ "${present}" == t ]]; then
        rows="$(ask target "${UMAMI_DB}" "SELECT count(*) FROM ${table}")"
        echo "umami-move: ${table}: ${rows} row(s)"
    fi
done
echo "==> Umami's data is on ${TARGET_HOST}. The old copy on ${SOURCE_HOST} is unchanged;" \
     "drop it once the move is validated (docker_deployment.md, \"Upgrading to 0.7.2\")."
