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
# Usage:
#   ./deploy.sh [--prune] [--no-pull]
#
#   --prune    run `docker image prune -f` after a successful deploy
#   --no-pull  start the images already on this host; for images built
#              here (the cutover rehearsal), which no registry holds
#
# Run from the directory holding the compose files and .env.
#
# The one thing it may write: a COMPOSE_FILE line, when .env has none.
# Umami and listmonk are not deployed here: they run in the back-office
# suite (wiki SimpleAgilityBackOfficeSuite), which Pressroom reaches through
# the ANALYTICS_UPSTREAM and PRESSROOM_ANALYTICS_*/PRESSROOM_NEWSLETTER_*
# settings in .env, and which keeps their secrets.
set -euo pipefail

# No -f: the compose files are COMPOSE_FILE in .env (below), which plain
# `docker compose` reads by itself, so this script and the operator's own
# commands always see the same files.
COMPOSE=(docker compose)
# What a .env without the line gets.
DEFAULT_COMPOSE_FILES="docker-compose.prod.yml"

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

# 0.8.5 to 0.16.6 ran Umami and listmonk from docker-compose.backoffice.yml.
# They live in the back-office suite now, and the image no longer carries the
# add-on's one-shots and backup scripts, so a host whose .env still selects
# it must cut over first. Stop before anything is pulled, started or written.
if grep -Eq '^COMPOSE_FILE=.*docker-compose\.backoffice\.yml' .env; then
    echo "Refusing to deploy: COMPOSE_FILE in .env still names docker-compose.backoffice.yml," >&2
    echo "the Umami and listmonk add-on this release no longer supports. Cut over to the" >&2
    echo "back-office suite first (wiki page SimpleAgilityBackOfficeSuite, \"Cutover\"), then" >&2
    echo "set COMPOSE_FILE=docker-compose.prod.yml. Nothing has changed." >&2
    exit 1
fi

# A .env from before 0.8.5 names no compose files; add the line, once, so
# that every `docker compose` on this host, ours and the operator's, sees the
# same files. .env decides, not a COMPOSE_FILE exported by the calling shell:
# that is exported here from .env's line.
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
