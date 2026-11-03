#!/usr/bin/env bash
# Pressroom fresh-install database bootstrap.
#
# Creates a brand-new Pressroom database and application role, then runs
# migrate.sh to populate the schema. Idempotent: safe to run against an
# already-bootstrapped database (the create-database and create-role steps
# skip if their target already exists).
#
# Usage:
#   DB_APP_PASSWORD='…' ./install-fresh.sh
#   ./install-fresh.sh --help
#
# Environment variables (with defaults):
#   DB_NAME          pressroom   target database (created if absent)
#   DB_APP_USER      pressroom   application role (created if absent)
#   DB_APP_PASSWORD  (required)  password set on the application role
#   PGHOST           localhost
#   PGPORT           5432
#   PGUSER           postgres    superuser that runs the bootstrap
#   PGPASSWORD                   optional (or rely on peer/trust auth)
#
# Needs psql 15 or later (\getenv).
#
# This script MUST be run by a PostgreSQL superuser because it creates the
# database and the application role. Once bootstrapped, routine schema
# updates only need migrate.sh, which can run as the application role.
set -euo pipefail

for arg in "$@"; do
    case "${arg}" in
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0
            ;;
        *)
            echo "Unknown argument: ${arg}" >&2
            exit 2
            ;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

DB_NAME="${DB_NAME:-pressroom}"
DB_APP_USER="${DB_APP_USER:-pressroom}"
DB_APP_PASSWORD="${DB_APP_PASSWORD:-}"
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-postgres}"

RED="\033[0;31m"
GREEN="\033[0;32m"
YELLOW="\033[1;33m"
NC="\033[0m"

print_ok()   { echo -e "${GREEN}✓${NC} $*"; }
print_warn() { echo -e "${YELLOW}!${NC} $*"; }
print_err()  { echo -e "${RED}✗${NC} $*" >&2; }

if [[ -z "${DB_APP_PASSWORD}" ]]; then
    print_err "DB_APP_PASSWORD is required — it is the password set on the"
    print_err "'${DB_APP_USER}' role that Pressroom connects with."
    print_err "Example: DB_APP_PASSWORD='choose-a-real-password' $0"
    exit 1
fi

psql_super=(--no-psqlrc --quiet --tuples-only --no-align -v ON_ERROR_STOP=1 -d postgres)

if ! psql "${psql_super[@]}" -c 'SELECT 1;' >/dev/null 2>&1; then
    print_err "Could not connect as ${PGUSER}@${PGHOST}:${PGPORT}"
    exit 1
fi

# Every name and the password reach psql as psql variables and enter the SQL
# only quoted -- :'name' as a literal, format('%I') as an identifier,
# format('%L') as a literal -- so a quote in either neither breaks a statement
# nor runs one. The SQL arrives on stdin (quoted heredocs: the shell expands
# nothing in them), and the password is not even a -v argument: psql reads it
# from its own environment with \getenv (psql 15+), so it is never on a
# command line, where `ps` shows it to every local user. The same idiom as
# deploy/admin-account.sh.

# --- application role -------------------------------------------------------
role_exists=$(psql "${psql_super[@]}" -v role="${DB_APP_USER}" <<'SQL'
SELECT 1 FROM pg_roles WHERE rolname = :'role';
SQL
)
if [[ "${role_exists// /}" == "1" ]]; then
    print_warn "Role ${DB_APP_USER} already exists — leaving its password unchanged"
else
    DB_APP_PASSWORD="${DB_APP_PASSWORD}" psql "${psql_super[@]}" -v role="${DB_APP_USER}" <<'SQL'
\getenv pw DB_APP_PASSWORD
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'pw') \gexec
SQL
    print_ok "Created role ${DB_APP_USER}"
fi

# --- database ---------------------------------------------------------------
db_exists=$(psql "${psql_super[@]}" -v db="${DB_NAME}" <<'SQL'
SELECT 1 FROM pg_database WHERE datname = :'db';
SQL
)
if [[ "${db_exists// /}" == "1" ]]; then
    print_warn "Database ${DB_NAME} already exists — skipping creation"
else
    # CREATE DATABASE cannot run inside a transaction block; \gexec runs it
    # on its own, outside one.
    psql "${psql_super[@]}" -v db="${DB_NAME}" -v role="${DB_APP_USER}" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'role') \gexec
SQL
    print_ok "Created database ${DB_NAME}"
fi

psql "${psql_super[@]}" -v db="${DB_NAME}" -v role="${DB_APP_USER}" <<'SQL'
SELECT format('GRANT ALL PRIVILEGES ON DATABASE %I TO %I', :'db', :'role') \gexec
SQL

# The app role needs CREATE on schema public to let migrate.sh run as itself.
psql --no-psqlrc --quiet --tuples-only --no-align -v ON_ERROR_STOP=1 -d "${DB_NAME}" \
    -v role="${DB_APP_USER}" <<'SQL'
SELECT format('GRANT CREATE, USAGE ON SCHEMA public TO %I', :'role') \gexec
SQL

print_ok "Database and role ready — applying migrations"

# --- schema -----------------------------------------------------------------
DB_NAME="${DB_NAME}" DB_APP_USER="${DB_APP_USER}" \
    PGHOST="${PGHOST}" PGPORT="${PGPORT}" PGUSER="${PGUSER}" \
    "${SCRIPT_DIR}/migrate.sh"

print_ok "Fresh install complete."
echo
echo "Point Pressroom at this database with:"
echo "  database.jdbc.connectionURL=jdbc:postgresql://${PGHOST}:${PGPORT}/${DB_NAME}"
echo "  database.jdbc.username=${DB_APP_USER}"
