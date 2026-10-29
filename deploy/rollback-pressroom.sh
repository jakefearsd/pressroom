#!/usr/bin/env bash
# Put this host back on the stack it ran before cutover-pressroom.sh.
#
# Usage:
#   bash rollback-pressroom.sh
#
# Run from the OLD deploy directory (~/roller), where the cutover left this
# script. It stops the new stack (its volumes kept, for a look at what went
# wrong), then runs this directory's own deploy.sh on its untouched .env
# (IMAGE_VERSION 0.4.x) and old volumes, and waits for the app to be healthy.
#
# Anything written to the new stack since the cutover (posts, comments,
# subscribers, page views) is not in the old database and does not come back
# with it. The new directory and its volumes are left as they are; to try the
# cutover again, see docs/dev/pressroom-cutover.md, "Trying again".
#
# Environment, as for the cutover: OLD_PROJECT (default roller), NEW_PROJECT
# (default pressroom), NEW_DIR (default ../pressroom).
#
# This file names the old layout on purpose; OldNameInventoryTest keeps it.
set -euo pipefail

OLD_PROJECT="${OLD_PROJECT:-roller}"
NEW_PROJECT="${NEW_PROJECT:-pressroom}"
NEW_DIR="${NEW_DIR:-../pressroom}"
COMPOSE_FILE=docker-compose.prod.yml
HEALTH_TRIES="${ROLLBACK_HEALTH_TRIES:-60}"
WAIT_SECONDS="${ROLLBACK_WAIT_SECONDS:-2}"

refuse() {
    echo "Refusing: $*" >&2
    exit 1
}

case "${1:-}" in
    "") ;;
    -h|--help) awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; exit 0 ;;
    *) echo "rollback-pressroom.sh takes no arguments." >&2; exit 2 ;;
esac

for required in "${COMPOSE_FILE}" .env deploy.sh; do
    [[ -f "${required}" ]] || refuse "no ${required} in $(pwd). Run this from the old deploy directory."
done
current=$(sed -n 's/^[[:space:]]*\(export[[:space:]]\{1,\}\)\{0,1\}IMAGE_VERSION=//p' .env | tail -n 1)
current="${current%$'\r'}"
[[ "${current}" =~ ^0\.4\.[0-9]+$ ]] \
    || refuse ".env's IMAGE_VERSION is ${current:-unset}, not 0.4.x: this is not the directory the cutover left."

# The cutover writes the compose file at its step 2 and .env at its step 4,
# and starts no container before both are there.
if [[ -f "${NEW_DIR}/${COMPOSE_FILE}" && -f "${NEW_DIR}/.env" ]]; then
    echo "==> Stopping the new stack (its volumes are kept)..."
    docker compose -p "${NEW_PROJECT}" -f "${NEW_DIR}/${COMPOSE_FILE}" down </dev/null
else
    echo "==> ${NEW_DIR} holds no new stack to stop."
fi

echo "==> Starting the old stack from $(pwd)..."
COMPOSE_PROJECT_NAME="${OLD_PROJECT}" ./deploy.sh </dev/null

echo "==> Waiting for the old app to be healthy..."
healthy=0
for _ in $(seq 1 "${HEALTH_TRIES}"); do
    if docker compose -p "${OLD_PROJECT}" -f "${COMPOSE_FILE}" exec -T app \
            curl -sf http://localhost:8090/actuator/health >/dev/null 2>&1 </dev/null; then
        healthy=1
        break
    fi
    sleep "${WAIT_SECONDS}"
done
if [[ "${healthy}" -ne 1 ]]; then
    echo "The old app did not become healthy. Check:" \
         "docker compose -p ${OLD_PROJECT} -f ${COMPOSE_FILE} logs provision app" >&2
    exit 1
fi
echo "==> Rolled back: the old stack (${current}) serves again from $(pwd)."
echo "    ${NEW_DIR} and its volumes are kept; nothing written there since the cutover is here."
