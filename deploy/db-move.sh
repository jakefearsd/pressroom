#!/usr/bin/env bash
# One-shot (compose services `umami-move` and `listmonk-move`): copy a
# back-office service's database from Pressroom's PostgreSQL (`postgres`) to
# the service's own server, once.
#
# Umami kept its data on Pressroom's server until 0.7.2 and listmonk until
# 0.8.5. Each now has a server of its own (umami-db, listmonk-db), the step
# that lets the back-office services leave this stack (wiki SA-15). This
# moves an existing host's data there on the first deploy, before the
# service's container starts (compose makes the service wait for its move to
# exit 0). It decides from what it finds, so it is safe on every `up -d`:
#
#   the target already has tables    -> nothing to do (moved before, or the
#                                       service booted there first): exit 0
#   no MOVE_DB on postgres, or       -> a fresh install, nothing to move:
#   one without MOVE_MARKER_TABLE       exit 0, the service initialises its
#                                       own server
#   otherwise                        -> pg_dump from postgres, pg_restore into
#                                       the target in ONE transaction, print
#                                       the copied counts: exit 0
#
# Anything it cannot check or do fails the run (exit 1) and leaves the target
# as it was: a server it cannot ask is not a server with nothing in it.
# Compose then does not start the service (the blog is not affected: caddy
# waits for the app only) and deploy.sh fails, until a deploy succeeds.
#
# The old database on postgres is left exactly as it was: it is the rollback
# point (the previous release's compose file points straight at it) and is
# dropped by hand once the move is validated (docker_deployment.md).
#
# Objects are restored --no-owner --no-acl, so they belong to MOVE_DB_USER
# and the old server's roles never need to exist on the target;
# analytics-views re-creates grafana_ro's grants after Umami starts.
#
# What to move, from compose:
#   MOVE_NAME              the compose service, which prefixes its messages
#   MOVE_DB                the database's name, the same on both servers
#   MOVE_MARKER_TABLE      a table only the service creates: on the source,
#                          it means the service ran there and has data
#   MOVE_COUNT_TABLES      tables whose copied row counts are printed
#   MOVE_PGHOST, MOVE_PGPORT, MOVE_DB_USER, MOVE_DB_PASSWORD
#                          the target server and its own login
#   MOVE_PASSWORD_SETTING  the .env key holding that password, for the hint
#                          printed when the target refuses it
#
# Two logins, each passed to its own command in that command's environment
# (PGPASSWORD=... cmd), never on a command line and never printed.
set -euo pipefail

SOURCE_HOST="${PGHOST:-postgres}"
SOURCE_PORT="${PGPORT:-5432}"
SOURCE_USER="${POSTGRES_USER:?POSTGRES_USER must be set}"
SOURCE_PASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
NAME="${MOVE_NAME:-db-move}"
MOVE_DB="${MOVE_DB:?MOVE_DB must be set}"
MARKER="${MOVE_MARKER_TABLE:?MOVE_MARKER_TABLE must be set}"
COUNT_TABLES="${MOVE_COUNT_TABLES:-}"
TARGET_HOST="${MOVE_PGHOST:?MOVE_PGHOST must be set}"
TARGET_PORT="${MOVE_PGPORT:-5432}"
TARGET_USER="${MOVE_DB_USER:?MOVE_DB_USER must be set}"
TARGET_PASSWORD="${MOVE_DB_PASSWORD:?MOVE_DB_PASSWORD must be set}"
PASSWORD_SETTING="${MOVE_PASSWORD_SETTING:-the password}"
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
        echo "${NAME}: cannot reach PostgreSQL at ${where} (database ${db}); nothing moved." >&2
        if [[ "${side}" == target ]]; then
            echo "If ${TARGET_HOST} refused the login: it keeps the ${PASSWORD_SETTING} it was" \
                 "first started with, so .env must hold that one (docker_deployment.md, \"If a" \
                 "service database's password is lost\"). If MOVE_DB was renamed since, ${TARGET_HOST}" \
                 "has no database under the new name." >&2
        fi
        exit 1
    fi
    printf '%s' "${answer}"
}

TABLES_SQL="SELECT count(*) FROM pg_tables WHERE schemaname = 'public'"

echo "==> Checking whether ${MOVE_DB} is already on ${TARGET_HOST}..."
tables="$(ask target "${MOVE_DB}" "${TABLES_SQL}")"
if [[ "${tables}" != 0 ]]; then
    echo "    ${TARGET_HOST} already holds ${MOVE_DB}; nothing to move."
    exit 0
fi

echo "==> Looking for the old ${MOVE_DB} database on ${SOURCE_HOST}..."
exists="$(ask source postgres "SELECT 1 FROM pg_database WHERE datname = ${MOVE_DB@Q}")"
if [[ -z "${exists}" ]]; then
    echo "    no ${MOVE_DB} database on ${SOURCE_HOST}: a fresh install, nothing to move."
    exit 0
fi
has_marker="$(ask source "${MOVE_DB}" "SELECT to_regclass(${MARKER@Q}) IS NOT NULL")"
if [[ "${has_marker}" != t ]]; then
    echo "    ${MOVE_DB} on ${SOURCE_HOST} has no ${MARKER} table (the service never ran there): nothing to move."
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
dump="${work}/${MOVE_DB}.dump"

echo "==> Copying ${MOVE_DB} from ${SOURCE_HOST} to ${TARGET_HOST}..."
if ! on_source pg_dump -Fc --no-owner --no-acl -d "${MOVE_DB}" -f "${dump}"; then
    echo "${NAME}: the dump from ${SOURCE_HOST} failed; nothing moved." >&2
    exit 1
fi
if ! on_target pg_restore --no-owner --no-acl --single-transaction --exit-on-error \
        -d "${MOVE_DB}" "${dump}"; then
    echo "${NAME}: the restore into ${TARGET_HOST} failed and was rolled back; ${TARGET_HOST} is unchanged." >&2
    echo "The next deploy tries again. The old database on ${SOURCE_HOST} is untouched." >&2
    exit 1
fi

for table in ${COUNT_TABLES}; do
    present="$(ask target "${MOVE_DB}" "SELECT to_regclass(${table@Q}) IS NOT NULL")"
    if [[ "${present}" == t ]]; then
        rows="$(ask target "${MOVE_DB}" "SELECT count(*) FROM ${table}")"
        echo "${NAME}: ${table}: ${rows} row(s)"
    fi
done
echo "==> ${MOVE_DB} is on ${TARGET_HOST}. The old copy on ${SOURCE_HOST} is unchanged;" \
     "drop it once the move is validated (docker_deployment.md)."
