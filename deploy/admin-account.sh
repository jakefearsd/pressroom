#!/usr/bin/env bash
#
# Roller admin accounts, for whoever controls the host:
#
#   C="docker compose -f docker-compose.prod.yml"
#   $C run --rm admin create --username NAME --email ADDRESS
#   $C run --rm admin reset-password --username NAME
#   $C run --rm admin status
#
# create and reset-password prompt for the password twice. With --password-stdin it reads
# exactly one line from stdin instead (use `run --rm -T`).
#
# Trust model: running this at all means controlling the host and its
# .env, which already holds every database credential. Nothing reachable
# over HTTP can do what this does. See
# docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md.
#
# The password is never an argument and is never printed. It reaches psql
# only through psql's environment (\getenv) and then as a bind parameter
# ($1 ... \bind :pw \g), never as statement text: a failed statement is
# logged by the server, and a bind parameter is not part of that text.
# Every psql call reads its SQL from a heredoc: -c would not substitute -v
# variables, and the heredoc stops psql eating this script's stdin, where
# the password is.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"
export PGDATABASE="${POSTGRES_DB:-pressroom}"

MIN_PASSWORD_LENGTH=8
EMAIL_MAX=255
EMAIL_RE='^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'

usage() {
    cat >&2 <<'EOF'
usage: admin-account.sh create --username NAME --email ADDRESS [--password-stdin]
       admin-account.sh reset-password --username NAME [--password-stdin]
       admin-account.sh status
EOF
    exit 2
}

die() {
    echo "admin-account: $*" >&2
    exit 1
}

# Bracket ranges follow LC_ALL/LC_COLLATE: under the image's en_US.UTF-8,
# [A-Za-z] matches ä and fullwidth letters. Scope C to this check only; a
# global LC_ALL=C would make ${#PASSWORD} count bytes.
valid_username() {
    local LC_ALL=C
    [[ "$1" =~ ^[A-Za-z0-9]+$ && ${#1} -le 255 ]]
}

# sql [psql -v args...] <<'SQL' ... -- unaligned, tuples only, stops on error
sql() {
    psql -X -q -v ON_ERROR_STOP=1 -tA "$@"
}

require_schema() {
    local present
    present=$(sql <<'SQL'
SELECT to_regclass('public.pressroom_user') IS NOT NULL;
SQL
) || die "database error (is ${PGDATABASE} on ${PGHOST}:${PGPORT} reachable?)"
    [[ "${present}" == t ]] \
        || die "the Roller schema is not installed in ${PGDATABASE}: run deploy.sh first"
}

# Sets PASSWORD. IFS= keeps leading/trailing spaces; -r keeps backslashes.
read_password() {
    local confirm=""
    PASSWORD=""
    if [[ "${FROM_STDIN}" -eq 1 ]]; then
        if [[ -t 0 ]]; then
            IFS= read -r -s -p "Password: " PASSWORD || true
            echo >&2
        else
            IFS= read -r PASSWORD || true
        fi
        [[ -n "${PASSWORD}" ]] || die "no password on stdin"
    else
        IFS= read -r -s -p "Password: " PASSWORD || true
        if [[ -t 0 ]]; then echo >&2; fi
        IFS= read -r -s -p "Repeat the password: " confirm || true
        if [[ -t 0 ]]; then echo >&2; fi
        [[ "${PASSWORD}" == "${confirm}" ]] || die "the two passwords do not match"
    fi
    (( ${#PASSWORD} >= MIN_PASSWORD_LENGTH )) \
        || die "the password must be at least ${MIN_PASSWORD_LENGTH} characters"
}

cmd_create() {
    [[ ${#EMAIL} -le ${EMAIL_MAX} ]] || die "email address is longer than ${EMAIL_MAX} characters"
    valid_username "${USERNAME}" \
        || die "username must be 1-255 letters and digits"
    [[ "${EMAIL}" =~ ${EMAIL_RE} ]] || die "not an email address: ${EMAIL}"
    require_schema
    local taken
    taken=$(sql -v username="${USERNAME}" <<'SQL'
SELECT count(*) FROM pressroom_user WHERE lower(username) = lower(:'username');
SQL
) || die "database error while checking for an existing account"
    [[ "${taken}" == 0 ]] || die "an account named ${USERNAME} already exists (perhaps disabled)"
    read_password
    PRESSROOM_ADMIN_PASSWORD="${PASSWORD}" sql -v username="${USERNAME}" -v email="${EMAIL}" >/dev/null <<'SQL' || die "database error: nothing was written"
SET client_min_messages = warning;
\getenv pw PRESSROOM_ADMIN_PASSWORD
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO pressroom_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled)
VALUES (gen_random_uuid()::text, :'username',
        '{bcrypt}' || crypt($1, gen_salt('bf', 10)),
        :'username', :'username', :'email', now(), 'en_US', 'UTC', true)
\bind :pw \g
INSERT INTO userrole (id, rolename, username)
VALUES (gen_random_uuid()::text, 'admin', :'username'),
       (gen_random_uuid()::text, 'editor', :'username');
COMMIT;
SQL
    echo "created admin account ${USERNAME} (roles: admin, editor)"
}

cmd_reset() {
    require_schema
    local found enabled kind
    found=$(sql -v username="${USERNAME}" <<'SQL'
SELECT isenabled, kind FROM pressroom_user WHERE username = :'username';
SQL
) || die "database error while looking up ${USERNAME}"
    [[ -n "${found}" ]] || die "no account named ${USERNAME}"
    enabled="${found%%|*}"
    kind="${found#*|}"
    # An agent never signs in with a password; setting one would only mislead.
    [[ "${kind}" == PERSON ]] \
        || die "${USERNAME} is an agent and has no password: manage it from Admin > Users, agent page"
    read_password
    PRESSROOM_ADMIN_PASSWORD="${PASSWORD}" sql -v username="${USERNAME}" >/dev/null <<'SQL' || die "database error: password not changed"
SET client_min_messages = warning;
\getenv pw PRESSROOM_ADMIN_PASSWORD
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE pressroom_user SET passphrase = '{bcrypt}' || crypt($1, gen_salt('bf', 10))
 WHERE username = :'username'
\bind :pw \g
COMMIT;
SQL
    if [[ "${enabled}" != t ]]; then
        echo "admin-account: warning: ${USERNAME} is disabled and still cannot log in;" \
             "enable it from another admin account, or create a new admin" >&2
    fi
    echo "password reset for ${USERNAME}."
    echo "The running app caches accounts it has loaded; restart it so the new password takes effect:"
    echo "  docker compose -f docker-compose.prod.yml restart app"
}

cmd_status() {
    require_schema
    local admins
    admins=$(sql <<'SQL'
SELECT u.username FROM pressroom_user u
 WHERE u.isenabled
   AND u.kind = 'PERSON'
   AND EXISTS (SELECT 1 FROM userrole r
                WHERE r.username = u.username AND r.rolename = 'admin')
 ORDER BY u.username;
SQL
) || die "database error while listing admin accounts"
    if [[ -z "${admins}" ]]; then
        echo "no enabled admin account. Create one with:"
        echo "  docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS"
        exit 3
    fi
    echo "enabled admin accounts:"
    sed 's/^/  /' <<<"${admins}"
}

COMMAND="${1:-}"
[[ -n "${COMMAND}" ]] || usage
shift
USERNAME="" EMAIL="" FROM_STDIN=0
while (( $# > 0 )); do
    case "$1" in
        --username) (( $# >= 2 )) || usage; USERNAME="$2"; shift 2 ;;
        --email)    (( $# >= 2 )) || usage; EMAIL="$2"; shift 2 ;;
        --password-stdin) FROM_STDIN=1; shift ;;
        *) usage ;;
    esac
done

case "${COMMAND}" in
    create)
        [[ -n "${USERNAME}" && -n "${EMAIL}" ]] || usage
        cmd_create ;;
    reset-password)
        [[ -n "${USERNAME}" && -z "${EMAIL}" ]] || usage
        cmd_reset ;;
    status)
        [[ -z "${USERNAME}${EMAIL}" && "${FROM_STDIN}" -eq 0 ]] || usage
        cmd_status ;;
    *) usage ;;
esac
