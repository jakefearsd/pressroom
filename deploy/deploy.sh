#!/usr/bin/env bash
# Deploy or upgrade the production stack.
#
# There is almost nothing left here, and that is the point. Ordering used to
# live in this script -- bring postgres up, wait, copy migrate.sh into its
# container, run it, then start the app -- and now lives in
# docker-compose.prod.yml as depends_on conditions, where `docker compose up
# -d` enforces it whether or not anyone runs this file. What remains is a
# convenience wrapper: pull, up, and wait for health with a real timeout,
# then check for an admin account and say how to create one if none exists.
#
# The old version also had a genuine bug this shape cannot have: it expanded
# ${UMAMI_DB:-umami} in the host shell, which never sourced .env, so renaming a
# service database in .env made it provision the default names while the
# containers used the renamed ones. Those names are now read inside the
# provision container, which compose populates from .env correctly.
#
# Usage:
#   ./deploy.sh [--prune] [--no-pull]
#
#   --prune    run `docker image prune -f` after a successful deploy
#   --no-pull  start the images already on this host; for images built
#              here (the cutover rehearsal), which no registry holds
#
# Run from the directory holding the compose files and .env.
#
# The one thing it writes: generated secrets. When .env has no (or an empty)
# UMAMI_PRESSROOM_PASSWORD -- Pressroom's Umami account, which analytics-views
# creates and the app signs in as -- or UMAMI_DB_PASSWORD / LISTMONK_DB_PASSWORD
# -- the logins of Umami's and listmonk's own database servers, umami-db
# (0.7.2+) and listmonk-db (0.8.5+) -- it appends a generated
# one before starting the stack. Never printed; .env keeps its mode and its
# other lines.
set -euo pipefail

# No -f: the compose files are COMPOSE_FILE in .env (below), which plain
# `docker compose` reads by itself, so this script and the operator's own
# commands always see the same files.
COMPOSE=(docker compose)
# What a .env without the line gets: the core file and the back-office
# overlay, the shape of every release since 0.8.5.
DEFAULT_COMPOSE_FILES="docker-compose.prod.yml:docker-compose.backoffice.yml"

PRUNE=0
PULL=1
for arg in "$@"; do
    case "$arg" in
        --prune) PRUNE=1 ;;
        --no-pull) PULL=0 ;;
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0 ;;
        *)
            echo "Unknown argument: ${arg}" >&2
            exit 2 ;;
    esac
done

if [[ ! -f .env ]]; then
    echo "Missing .env in $(pwd). Make it from env.example, attached to the GitHub Release." >&2
    exit 1
fi

# env.example ships every secret as a changeme-... placeholder, and those
# satisfy compose's ${VAR:?} checks: a .env copied without editing would
# start a stack whose passwords are printed in a public repository. Refused
# here, before anything is pulled, started or written. Names only, never
# values. A commented-out line is not a setting and is not checked.
refuse_placeholder_secrets() {
    local env_file="${1:-.env}" placeholders
    placeholders=$(sed -n -E "s/^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=[\"']?changeme.*/\2/p" \
        "${env_file}")
    if [[ -n "${placeholders}" ]]; then
        echo "Refusing to deploy: ${env_file} still holds env.example's changeme placeholder for:" >&2
        sed 's/^/    /' <<<"${placeholders}" >&2
        echo "Set a real value for each (openssl rand -hex 32 makes one) and run this again." >&2
        exit 1
    fi
}
refuse_placeholder_secrets .env

# Appends NAME=<openssl rand -hex 24> to the env file ($3, default .env)
# unless it already holds a non-empty NAME ($1); $2 says what it is for. Appending keeps
# the file's mode and owner as the operator set them, and an interrupted
# write cannot lose the rest of the file. Only an empty assignment
# (env.example's shape, uncommented) makes it rewrite the file in place
# (`>`, same inode, same mode), so compose never sees two. printf is a
# builtin: the value goes from openssl into the file and is on no command
# line and in no output.
ensure_generated_secret() {
    local name="$1" what="$2" env_file="${3:-.env}" kept password
    if grep -Eq "^${name}=.+" "${env_file}"; then
        return 0
    fi
    password=$(openssl rand -hex 24)
    if grep -q "^${name}=" "${env_file}"; then
        kept=$(grep -v "^${name}=" "${env_file}" || true)
        printf '%s\n' "${kept}" > "${env_file}"
    elif [[ -s "${env_file}" && -n "$(tail -c 1 "${env_file}")" ]]; then
        printf '\n' >> "${env_file}"
    fi
    printf '%s=%s\n' "${name}" "${password}" >> "${env_file}"
    echo "==> Generated ${name} in ${env_file} (${what}; not shown)."
}
# A service database server (umami-db, listmonk-db) keeps the password it
# was FIRST started with; POSTGRES_PASSWORD is read only when its volume is
# empty. So a missing password line next to an existing volume (an older
# .env restored on a rollback) must not be "fixed" by generating a new one:
# the move and the service would be locked out of their own server. Stop
# before anything starts, and say how to recover. The volume is found by
# compose's labels, for this project.
# $1: the .env key, $2: the volume as compose declares it, $3: the server.
refuse_new_password_for_existing_volume() {
    local key="$1" declared="$2" server="$3" project volume
    if grep -Eq "^${key}=.+" .env; then
        return 0
    fi
    project="${COMPOSE_PROJECT_NAME:-$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' .env | tail -n 1)}"
    if [[ -z "${project}" ]]; then
        project="$(basename "$(pwd)" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-')"
    fi
    volume="$(docker volume ls -q \
        --filter "label=com.docker.compose.project=${project}" \
        --filter "label=com.docker.compose.volume=${declared}" 2>/dev/null || true)"
    if [[ -n "${volume}" ]]; then
        echo "${server}'s volume ${volume} already exists, but .env has no ${key}." >&2
        echo "That server keeps the password it was first started with, so a new one would" \
             "lock its service out of its data. Put the original line back, or reset the" \
             "server's password: docker_deployment.md, \"If a service database's password is lost\"." \
             "Nothing has changed." >&2
        exit 1
    fi
}
refuse_new_password_for_existing_volume UMAMI_DB_PASSWORD pressroom-umami-pgdata umami-db
refuse_new_password_for_existing_volume LISTMONK_DB_PASSWORD pressroom-listmonk-pgdata listmonk-db

# A .env from before 0.8.5 names no compose files; add the line, once, so
# that every `docker compose` on this host, ours and the operator's, sees the
# core file and the back-office overlay. .env decides, not a COMPOSE_FILE
# exported by the calling shell: that is exported here from .env's line.
if ! grep -q '^COMPOSE_FILE=' .env; then
    if [[ -s .env && -n "$(tail -c 1 .env)" ]]; then
        printf '\n' >> .env
    fi
    printf 'COMPOSE_FILE=%s\n' "${DEFAULT_COMPOSE_FILES}" >> .env
    echo "==> Added COMPOSE_FILE=${DEFAULT_COMPOSE_FILES} to .env (the compose files this host runs)."
fi
COMPOSE_FILE="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1)"
export COMPOSE_FILE
IFS=: read -r -a compose_files <<<"${COMPOSE_FILE}"
for required in "${compose_files[@]}"; do
    if [[ ! -f "${required}" ]]; then
        echo "Missing ${required} in $(pwd), which COMPOSE_FILE in .env names." \
             "The release's compose files are attached to the GitHub Release." >&2
        exit 1
    fi
done

ensure_generated_secret UMAMI_PRESSROOM_PASSWORD "Pressroom's Umami account" .env
ensure_generated_secret UMAMI_DB_PASSWORD "Umami's database server" .env
ensure_generated_secret LISTMONK_DB_PASSWORD "listmonk's database server" .env

if [[ "${PULL}" -eq 1 ]]; then
    echo "==> Pulling images..."
    "${COMPOSE[@]}" pull
fi

echo "==> Starting the stack (compose orders provisioning before the app)..."
"${COMPOSE[@]}" up -d

echo "==> Waiting for the app to report healthy (up to 120s)..."
healthy=0
for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T app curl -sf http://localhost:8090/actuator/health >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
done
if [[ "${healthy}" -ne 1 ]]; then
    echo "app did not become healthy within 120s. Check: ${COMPOSE[*]} logs provision app" >&2
    exit 1
fi
echo "    app healthy."

# A fresh install has no admin account, and there is no web page to make
# one (deliberately: see docker_deployment.md, "First run"). Point the
# operator at the tool rather than leave them at a login page they cannot
# pass. A failed check must never fail an otherwise good deploy.
echo "==> Checking for an admin account..."
admin_rc=0
# Captured, not streamed: on exit 3 the tool's own text repeats the create
# command that is printed below.
admin_out=$("${COMPOSE[@]}" run --rm -T admin status </dev/null 2>&1) || admin_rc=$?
case "${admin_rc}" in
    0) sed 's/^/    /' <<<"${admin_out}" ;;
    3)
        echo "    No admin account yet. Create the first one (it prompts for the password):"
        echo "      ${COMPOSE[*]} run --rm admin create --username NAME --email ADDRESS"
        ;;
    *)
        echo "    warning: could not check for an admin account (exit ${admin_rc});" \
             "try: ${COMPOSE[*]} run --rm admin status" >&2
        sed 's/^/    /' <<<"${admin_out}" >&2
        ;;
esac

if [[ "${PRUNE}" -eq 1 ]]; then
    echo "==> Pruning dangling images..."
    docker image prune -f
fi

echo "==> Deploy complete."
