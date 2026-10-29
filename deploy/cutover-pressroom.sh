#!/usr/bin/env bash
# Move this host from its 0.4.x Roller stack to Pressroom, once.
#
# Usage:
#   bash cutover-pressroom.sh VERSION [--files-from DIR] [--no-pull]
#
#   VERSION           the Pressroom release to move to, 0.5.0 or later
#   --files-from DIR  take docker-compose.prod.yml, deploy.sh, env.example and
#                     upgrade.sh from DIR instead of downloading the release's
#   --no-pull         start images already on this host (the rehearsal)
#
# Run from the OLD deploy directory (~/roller), which holds the 0.4.x
# docker-compose.prod.yml and .env. bin/deploy-host.sh HOST VERSION --cutover
# copies this script and rollback-pressroom.sh there and runs it. The
# checklist around it is docs/dev/pressroom-cutover.md.
#
# Environment (the rehearsal sets these so it cannot touch other projects):
#   OLD_PROJECT  the old compose project; default roller
#   NEW_PROJECT  the new compose project; default pressroom
#   NEW_DIR      the new deploy directory; default ../pressroom. Its last
#                path segment must be NEW_PROJECT, so that a later upgrade.sh
#                there, which names no project, finds this one.
#   RELEASE_URL  where releases download from
#
# Nothing in this directory and nothing in the old volumes is modified at any
# point: the old stack is stopped (its volumes kept), its volumes are copied
# read-only, and the rename runs on the copies. rollback-pressroom.sh puts the
# old stack back exactly as it was. Every step stops the script on failure.
#
#   1. refuses unless .env is on 0.4.x, NEW_DIR does not exist, every old
#      volume exists and no new one does
#   2. fetches the release files into NEW_DIR
#   3. backs up through the old stack's backup service, and records row counts
#      into NEW_DIR/cutover-counts-before.txt
#   4. writes NEW_DIR/.env from .env (key names and the database values move;
#      every other byte stays), checks it, and pulls the new images
#   5. stops the old stack: down, volumes kept. Nothing can run the rename
#      migration (V041) on data the old app still uses
#   6. copies every old volume into a new one with cp -a
#   7. starts the new postgres alone and waits for it
#   8. renames the database and the role, resets a password the rename
#      cleared, and proves a password login works
#   9. runs NEW_DIR/deploy.sh, whose provision step applies V041
#  10. recounts, under the renamed tables, into cutover-counts-after.txt
#  11. prints the manual steps that remain
#
# A second run after a failure refuses (NEW_DIR exists). The way back is
# rollback-pressroom.sh; the way to try again is in the checklist.
#
# It never prints a value from .env other than IMAGE_VERSION, and the
# database password never appears in any command line: it stays inside the
# postgres container, which compose gave it from NEW_DIR/.env.
#
# This file names the old layout on purpose; OldNameInventoryTest keeps it.
set -euo pipefail

OLD_PROJECT="${OLD_PROJECT:-roller}"
NEW_PROJECT="${NEW_PROJECT:-pressroom}"
NEW_DIR="${NEW_DIR:-../pressroom}"
RELEASE_URL="${RELEASE_URL:-https://github.com/jakefearsd/pressroom/releases/download}"
RELEASE_URL="${RELEASE_URL%/}"
COMPOSE_FILE=docker-compose.prod.yml
FILES=(docker-compose.prod.yml deploy.sh env.example upgrade.sh)
# Every volume the 0.4.x compose file declares, as old name -> new name.
VOLUME_SUFFIXES=(pgdata mediafiles search-index uploads backups)
CADDY_VOLUMES=(caddy-data caddy-config)
WAIT_TRIES="${CUTOVER_WAIT_TRIES:-60}"

usage() { awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; }

refuse() {
    echo "Refusing: $*" >&2
    exit 1
}

VERSION=""
FILES_FROM=""
PULL=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --files-from) FILES_FROM="${2:?--files-from needs a directory}"; shift ;;
        --no-pull) PULL=0 ;;
        -h|--help) usage; exit 0 ;;
        -*) usage >&2; exit 2 ;;
        *)
            if [[ -n "${VERSION}" ]]; then usage >&2; exit 2; fi
            VERSION="${1#v}" ;;
    esac
    shift
done
if [[ ! "${VERSION}" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+$ ]] \
        || (( BASH_REMATCH[1] == 0 && BASH_REMATCH[2] < 5 )); then
    echo "Not a release at or after 0.5.0: ${VERSION:-none given}" >&2
    usage >&2
    exit 2
fi

# The last value .env gives KEY, without an export prefix, a trailing CR or
# surrounding quotes. Used only for names and versions, never for a secret.
env_value() {
    local key="$1" file="$2"
    awk -v key="${key}" '
        {
            line = $0
            sub(/\r$/, "", line)
            if (match(line, "^[ \t]*(export[ \t]+)?" key "[ \t]*=")) {
                v = substr(line, RLENGTH + 1)
                if (v ~ /^".*"$/ || v ~ /^\047.*\047$/) { v = substr(v, 2, length(v) - 2) }
                found = 1; value = v
            }
        }
        END { if (found) print value }' "${file}"
}

# ---- 1. Pre-flight: refuse rather than guess; nothing is touched yet -------
STEP=1
for required in "${COMPOSE_FILE}" .env; do
    [[ -f "${required}" ]] || refuse "no ${required} in $(pwd). Run this from the old deploy directory."
done
current=$(env_value IMAGE_VERSION .env)
[[ "${current}" =~ ^0\.4\.[0-9]+$ ]] \
    || refuse ".env's IMAGE_VERSION is ${current:-unset}, not 0.4.x. The cutover moves a 0.4.x stack only."
OLD_ROLE=$(env_value POSTGRES_USER .env)
OLD_ROLE="${OLD_ROLE:-roller}"
OLD_DB=$(env_value POSTGRES_DB .env)
OLD_DB="${OLD_DB:-rollerdb}"
[[ "${OLD_ROLE}" == roller ]] \
    || refuse "POSTGRES_USER in .env is not roller; this cutover renames role roller only."
[[ "${OLD_DB}" == rollerdb ]] \
    || refuse "POSTGRES_DB in .env is not rollerdb; this cutover renames database rollerdb only."
UMAMI_DB=$(env_value UMAMI_DB .env)
UMAMI_DB="${UMAMI_DB:-umami}"
LISTMONK_DB=$(env_value LISTMONK_DB .env)
LISTMONK_DB="${LISTMONK_DB:-listmonk}"
if grep -Eq '^[[:space:]]*(export[[:space:]]+)?PRESSROOM_' .env; then
    refuse ".env already sets PRESSROOM_ keys; renaming the ROLLER_ ones would duplicate them."
fi

new_parent=$(dirname -- "${NEW_DIR}")
[[ -d "${new_parent}" ]] || refuse "${new_parent}, where NEW_DIR would go, does not exist."
NEW_DIR="$(cd -- "${new_parent}" && pwd)/$(basename -- "${NEW_DIR}")"
[[ ! -e "${NEW_DIR}" ]] \
    || refuse "${NEW_DIR} already exists: a cutover has run here before. See docs/dev/pressroom-cutover.md, \"Trying again\"."
[[ "$(basename -- "${NEW_DIR}")" == "${NEW_PROJECT}" ]] \
    || refuse "NEW_DIR ${NEW_DIR} must end in NEW_PROJECT (${NEW_PROJECT})."
[[ "${NEW_PROJECT}" != "${OLD_PROJECT}" ]] || refuse "NEW_PROJECT and OLD_PROJECT are both ${OLD_PROJECT}."
if [[ -n "${FILES_FROM}" ]]; then
    for f in "${FILES[@]}"; do
        [[ -f "${FILES_FROM}/${f}" ]] || refuse "no ${f} in ${FILES_FROM}."
    done
fi

OLD_VOLUMES=()
NEW_VOLUMES=()
for s in "${VOLUME_SUFFIXES[@]}"; do
    OLD_VOLUMES+=("${OLD_PROJECT}_roller-${s}")
    NEW_VOLUMES+=("${NEW_PROJECT}_pressroom-${s}")
done
for v in "${CADDY_VOLUMES[@]}"; do
    OLD_VOLUMES+=("${OLD_PROJECT}_${v}")
    NEW_VOLUMES+=("${NEW_PROJECT}_${v}")
done
for v in "${OLD_VOLUMES[@]}"; do
    docker volume inspect "${v}" >/dev/null 2>&1 </dev/null || refuse "volume ${v} does not exist; is OLD_PROJECT right?"
done
for v in "${NEW_VOLUMES[@]}"; do
    if docker volume inspect "${v}" >/dev/null 2>&1 </dev/null; then
        refuse "volume ${v} already exists. See docs/dev/pressroom-cutover.md, \"Trying again\"."
    fi
done

# From here on, a failure says where it stopped and what still stands.
on_exit() {
    local rc=$?
    if [[ "${rc}" -ne 0 && "${STEP}" -ge 2 ]]; then
        echo >&2
        echo "The cutover stopped at step ${STEP} (exit ${rc})." >&2
        if [[ "${STEP}" -lt 5 ]]; then
            echo "Nothing was stopped: the old stack still serves. ${NEW_DIR} holds a partial" \
                 "cutover; remove it before running this again." >&2
        else
            echo "The old stack is down; its directory and volumes are untouched. To put it back:" >&2
            echo "  bash rollback-pressroom.sh" >&2
            echo "Before trying again, see docs/dev/pressroom-cutover.md, \"Trying again\"." >&2
        fi
    fi
}
trap on_exit EXIT

old_compose() { docker compose -p "${OLD_PROJECT}" -f "${COMPOSE_FILE}" "$@"; }
new_compose() { docker compose -p "${NEW_PROJECT}" -f "${NEW_DIR}/${COMPOSE_FILE}" "$@"; }
# SQL arrives on stdin, never in an argument.
old_psql() { old_compose exec -T postgres psql -v ON_ERROR_STOP=1 -X -q -tA -U "$1" -d "$2"; }
new_psql() { new_compose exec -T postgres psql -v ON_ERROR_STOP=1 -X -q -tA -U "$1" -d "$2"; }

# ---- 2. The release files ---------------------------------------------------
STEP=2
echo "==> Fetching the v${VERSION} release files into ${NEW_DIR}..."
mkdir -- "${NEW_DIR}"
for f in "${FILES[@]}"; do
    if [[ -n "${FILES_FROM}" ]]; then
        cp -- "${FILES_FROM}/${f}" "${NEW_DIR}/${f}"
    else
        curl -fsSL -o "${NEW_DIR}/${f}" "${RELEASE_URL}/v${VERSION}/${f}" </dev/null
    fi
done
chmod +x "${NEW_DIR}/deploy.sh" "${NEW_DIR}/upgrade.sh"
PG_IMAGE=$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(postgres:[^[:space:]]*\).*/\1/p' "${NEW_DIR}/${COMPOSE_FILE}" | head -n 1)
[[ "${PG_IMAGE}" == postgres:*@sha256:* ]] \
    || refuse "the new ${COMPOSE_FILE} names no digest-pinned postgres image to copy volumes with."

# ---- 3. Backup and the counts to check against -----------------------------
STEP=3
echo "==> Taking a backup through the old stack..."
old_compose exec -T backup /app/backup/backup.sh </dev/null

# label database table, in the order both files list them.
COUNT_TABLES_BEFORE=(
    "app ${OLD_DB} roller_user" "app ${OLD_DB} weblog" "app ${OLD_DB} weblogentry"
    "app ${OLD_DB} roller_mediafile" "app ${OLD_DB} roller_reader_comment"
    "umami ${UMAMI_DB} website" "umami ${UMAMI_DB} website_event" "listmonk ${LISTMONK_DB} subscribers")
COUNT_TABLES_AFTER=(
    "app pressroom pressroom_user" "app pressroom weblog" "app pressroom weblogentry"
    "app pressroom pressroom_mediafile" "app pressroom pressroom_reader_comment"
    "umami ${UMAMI_DB} website" "umami ${UMAMI_DB} website_event" "listmonk ${LISTMONK_DB} subscribers")

# count PSQL-FUNCTION ROLE OUT SPEC...: "label table n" per line into OUT.
count_rows() {
    local psql_fn="$1" role="$2" out="$3" spec label db table n
    shift 3
    : > "${out}"
    for spec in "$@"; do
        read -r label db table <<<"${spec}"
        n=$(printf 'SELECT count(*) FROM %s;\n' "${table}" | "${psql_fn}" "${role}" "${db}")
        [[ "${n}" =~ ^[0-9]+$ ]] || { echo "Could not count ${db}.${table}." >&2; return 1; }
        echo "${label} ${table} ${n}" >> "${out}"
    done
}
count_rows old_psql "${OLD_ROLE}" "${NEW_DIR}/cutover-counts-before.txt" "${COUNT_TABLES_BEFORE[@]}"
sed 's/^/    /' "${NEW_DIR}/cutover-counts-before.txt"

# ---- 4. The new .env --------------------------------------------------------
STEP=4
echo "==> Writing ${NEW_DIR}/.env from .env..."
# Line by line, and only three kinds of change: a ROLLER_ key becomes a
# PRESSROOM_ key; the role and database values (roller, rollerdb, and the
# JDBC URL's /rollerdb) become pressroom; IMAGE_VERSION becomes VERSION.
# Comments, blank lines, quoting, export prefixes, line endings and every
# other value pass through byte for byte. Prints the names (never the
# values) of keys whose value still mentions roller.
(umask 077 && : > "${NEW_DIR}/.env")
chmod --reference=.env "${NEW_DIR}/.env"
still_named=$(awk -v version="${VERSION}" -v out="${NEW_DIR}/.env" '
    # VALUE with an exact WHOLE (optionally quoted) swapped for TO.
    function swap_whole(value, whole, to) {
        if (value == whole) return to
        if (value == "\"" whole "\"") return "\"" to "\""
        if (value == "\047" whole "\047") return "\047" to "\047"
        return value
    }
    # The JDBC URL path segment /rollerdb, where it ends the database name.
    function swap_db_path(value,    i, rest, next_char) {
        i = index(value, "/rollerdb")
        if (i == 0) return value
        rest = substr(value, i + 9)
        next_char = substr(rest, 1, 1)
        if (next_char != "" && next_char != "?" && next_char != "\"" && next_char != "\047") return value
        return substr(value, 1, i - 1) "/pressroom" rest
    }
    {
        line = $0
        cr = ""
        if (line ~ /\r$/) { cr = "\r"; line = substr(line, 1, length(line) - 1) }
        if (match(line, /^[ \t]*(export[ \t]+)?[A-Za-z_][A-Za-z0-9_]*[ \t]*=/)) {
            head = substr(line, 1, RLENGTH)
            value = substr(line, RLENGTH + 1)
            if (head ~ /^[ \t]*(export[ \t]+)?ROLLER_/) sub(/ROLLER_/, "PRESSROOM_", head)
            key = head
            sub(/^[ \t]*(export[ \t]+)?/, "", key)
            sub(/[ \t]*=$/, "", key)
            if (key == "POSTGRES_USER" || key == "PRESSROOM_DATABASE_JDBC_USERNAME") value = swap_whole(value, "roller", "pressroom")
            else if (key == "POSTGRES_DB") value = swap_whole(value, "rollerdb", "pressroom")
            else if (key == "PRESSROOM_DATABASE_JDBC_CONNECTIONURL") value = swap_db_path(value)
            else if (key == "IMAGE_VERSION") value = version
            if (tolower(value) ~ /(^|[^a-z])roller/) print key
            line = head value
        }
        printf "%s%s\n", line, cr > out
    }' .env)
# Whatever the rewrite could not place is a refusal now, while the old site
# still serves, not a failed login after it has stopped.
new_value() { env_value "$1" "${NEW_DIR}/.env"; }
for key in POSTGRES_USER POSTGRES_DB PRESSROOM_DATABASE_JDBC_USERNAME; do
    v=$(new_value "${key}")
    [[ -z "${v}" || "${v}" == pressroom ]] \
        || refuse "${key} in .env is not roller/rollerdb in a form this script rewrites; set it by hand."
done
url=$(new_value PRESSROOM_DATABASE_JDBC_CONNECTIONURL)
[[ -z "${url}" || "${url}" == */pressroom || "${url}" == */pressroom\?* ]] \
    || refuse "PRESSROOM_DATABASE_JDBC_CONNECTIONURL does not end in /rollerdb in a form this script rewrites."
if grep -Eq '^[[:space:]]*(export[[:space:]]+)?ROLLER_' "${NEW_DIR}/.env"; then
    refuse "a ROLLER_ key survived the rewrite."
fi
if [[ -n "${still_named}" ]]; then
    echo "    These keys' values still mention roller; check them in ${NEW_DIR}/.env:"
    while IFS= read -r key; do echo "      ${key}"; done <<<"${still_named}"
fi
new_compose config -q </dev/null
if [[ "${PULL}" -eq 1 ]]; then
    echo "==> Pulling the new images while the old site still serves..."
    new_compose pull </dev/null
fi

# ---- 5. Stop the old stack, volumes kept ------------------------------------
STEP=5
echo "==> Stopping the old stack (its volumes are kept)..."
old_compose down </dev/null

# ---- 6. Copy the volumes ----------------------------------------------------
STEP=6
echo "==> Copying the volumes..."
for i in "${!OLD_VOLUMES[@]}"; do
    from="${OLD_VOLUMES[$i]}"
    to="${NEW_VOLUMES[$i]}"
    if docker volume inspect "${to}" >/dev/null 2>&1 </dev/null; then
        refuse "volume ${to} appeared since the pre-flight."
    fi
    # Labelled so compose adopts it as its own without a warning. No
    # config-hash label: compose offers to recreate (empty) a volume whose
    # hash differs, and never compares one that has none.
    docker volume create --label "com.docker.compose.project=${NEW_PROJECT}" \
        --label "com.docker.compose.volume=${to#"${NEW_PROJECT}_"}" "${to}" >/dev/null </dev/null
    docker run --rm -v "${from}:/from:ro" -v "${to}:/to" --entrypoint sh "${PG_IMAGE}" \
        -c 'cp -a /from/. /to/ && diff -rq --no-dereference /from /to' </dev/null
    echo "    ${from} -> ${to}"
done

# ---- 7. The new postgres, alone ---------------------------------------------
STEP=7
echo "==> Starting the new postgres..."
new_compose up -d postgres </dev/null
ready=0
for _ in $(seq 1 "${WAIT_TRIES}"); do
    if new_compose exec -T postgres pg_isready -U "${OLD_ROLE}" -d "${OLD_DB}" >/dev/null 2>&1 </dev/null; then
        ready=1
        break
    fi
    sleep 2
done
[[ "${ready}" -eq 1 ]] || { echo "The new postgres did not become ready." >&2; exit 1; }

# ---- 8. Rename the database and the role ------------------------------------
STEP=8
echo "==> Renaming database ${OLD_DB} and role ${OLD_ROLE} to pressroom..."
# A role cannot rename itself, so a second superuser does it.
echo "CREATE ROLE pressroom_cutover SUPERUSER LOGIN;" | new_psql "${OLD_ROLE}" "${OLD_DB}"
printf 'ALTER DATABASE %s RENAME TO pressroom;\nALTER ROLE %s RENAME TO pressroom;\n' "${OLD_DB}" "${OLD_ROLE}" \
    | new_psql pressroom_cutover postgres
# An MD5 password is salted with the role name, so PostgreSQL clears it on a
# rename. SCRAM survives. Only the kind is read, never the hash.
kind=$(echo "SELECT coalesce(left(rolpassword, 3), 'none') FROM pg_authid WHERE rolname = 'pressroom';" \
    | new_psql pressroom_cutover postgres)
if [[ "${kind}" == md5 || "${kind}" == none ]]; then
    echo "    The rename cleared role pressroom's ${kind} password; setting it again from .env."
    # Single-quoted: runs in the container, whose POSTGRES_PASSWORD compose
    # read from NEW_DIR/.env. printf is a bash builtin, so the password
    # reaches psql on stdin and is in no argument list, here or there.
    # shellcheck disable=SC2016
    new_compose exec -T postgres bash -c 'set -euo pipefail
q="'"'"'"
printf "ALTER ROLE pressroom PASSWORD %s;\n" "${q}${POSTGRES_PASSWORD//${q}/${q}${q}}${q}" \
    | psql -v ON_ERROR_STOP=1 -X -q -U pressroom_cutover -d postgres' </dev/null
elif [[ "${kind}" != SCR ]]; then
    echo "Role pressroom's password is of a kind this script does not know (${kind})." >&2
    exit 1
fi
echo "DROP ROLE pressroom_cutover;" | new_psql pressroom pressroom
# Over TCP to the service name, as the app connects: the local socket is
# trust, and would prove nothing.
# shellcheck disable=SC2016
new_compose exec -T postgres bash -c \
    'PGPASSWORD="${POSTGRES_PASSWORD}" psql -w -X -q -tA -h postgres -U pressroom -d pressroom -c "SELECT 1" >/dev/null' \
    </dev/null
echo "    A password login as pressroom works."

# ---- 9. The new stack -------------------------------------------------------
STEP=9
echo "==> Running deploy.sh in ${NEW_DIR} (its provision step applies V041)..."
deploy_args=()
if [[ "${PULL}" -eq 0 ]]; then
    deploy_args+=(--no-pull)
fi
(cd -- "${NEW_DIR}" && COMPOSE_PROJECT_NAME="${NEW_PROJECT}" ./deploy.sh "${deploy_args[@]}" </dev/null)

# ---- 10. Recount ------------------------------------------------------------
STEP=10
echo "==> Recounting under the renamed tables..."
count_rows new_psql pressroom "${NEW_DIR}/cutover-counts-after.txt" "${COUNT_TABLES_AFTER[@]}"
lost=0
while IFS=' ' read -r -u 3 label table_before n_before && IFS=' ' read -r -u 4 _ table_after n_after; do
    if (( n_after < n_before )); then
        echo "    ${label} ${table_after}: ${n_after} rows, but ${table_before} had ${n_before}." >&2
        lost=1
    elif (( n_after > n_before )); then
        # The old site served until step 5 and the new one from step 9.
        echo "    ${label} ${table_after}: ${n_after} rows, ${table_before} had ${n_before} (written since)."
    else
        echo "    ${label} ${table_after}: ${n_after} rows, as before."
    fi
done 3<"${NEW_DIR}/cutover-counts-before.txt" 4<"${NEW_DIR}/cutover-counts-after.txt"
if [[ "${lost}" -ne 0 ]]; then
    echo "Rows are missing after the cutover. The new stack is up, so new writes land in it;" \
         "decide on the rollback before they matter." >&2
    exit 1
fi

# ---- 11. What remains by hand -----------------------------------------------
STEP=11
cat <<EOF
==> The cutover to ${VERSION} is done; the new stack runs from ${NEW_DIR}.
    The old directory, its volumes and images stay for 30 days; until then
    \`bash rollback-pressroom.sh\` here puts the old stack back.
    By hand now (docs/dev/pressroom-cutover.md, "After the cutover"):
      - Google console: add the redirect URI /pressroom-ui/sso/callback
      - revoke the old agent token; mint a prs_ token for blogger-agent
      - mv ~/.roller ~/.pressroom on the machine that holds the CLI credentials
      - run the post-deploy checks: health, a weblog page, admin login,
        the /roller-ui/ redirect, an MCP call, umami and listmonk
EOF
