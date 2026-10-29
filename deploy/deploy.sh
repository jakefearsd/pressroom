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
# Run from the directory holding docker-compose.prod.yml and .env.
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
COMPOSE=(docker compose -f "${COMPOSE_FILE}")

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

for required in "${COMPOSE_FILE}" .env; do
    if [[ ! -f "${required}" ]]; then
        echo "Missing ${required} in $(pwd). Both are attached to the GitHub Release." >&2
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
