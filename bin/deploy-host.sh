#!/usr/bin/env bash
# Upgrade a host to a published release, from this checkout.
#
# Usage:
#   bin/deploy-host.sh HOST [VERSION] [--dir DIR] [--no-backup] [--prune] [--url URL]
#   bin/deploy-host.sh HOST VERSION --cutover [--url URL]
#
#   HOST         an ssh destination, e.g. docker1
#   VERSION      the release, e.g. 0.2.3; default: this checkout's version,
#                the one bin/release.sh last cut
#   --dir DIR    the deploy directory on the host; default: pressroom, in the
#                ssh user's home
#   --no-backup  handed to upgrade.sh: skip the pre-upgrade backup
#   --prune      handed to upgrade.sh: prune dangling images afterwards
#   --url URL    afterwards, require URL to answer 200. This is the public
#                route, which the health check inside the container never
#                takes.
#   --cutover    the one-time move of a 0.4.x host from its old deploy
#                directory to pressroom/ (docs/dev/pressroom-cutover.md).
#                It always backs up and always makes the default directory,
#                so it takes none of --dir, --no-backup or --prune.
#
# Copies deploy/upgrade.sh into DIR on the host and runs it there; that
# script does the work (backup, release files, IMAGE_VERSION, deploy.sh).
# With --cutover it copies deploy/cutover-pressroom.sh and
# deploy/rollback-pressroom.sh into the old directory instead and runs the
# cutover there; the rollback stays beside it for as long as that directory
# is kept. The release must be published first: bin/release.sh VERSION --push.
set -euo pipefail

ROOT="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )/.." >/dev/null 2>&1 && pwd )"

usage() { awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; }

HOST=""
VERSION=""
DIR=pressroom
DIR_GIVEN=""
FLAGS=""
URL=""
CUTOVER=0
# The deploy directory before the cutover; the cutover makes DIR beside it.
OLD_DIR=roller  # rebrand:keep
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dir) DIR="${2:?--dir needs a directory}"; DIR_GIVEN=--dir; shift ;;
        --url) URL="${2:?--url needs a url}"; shift ;;
        --no-backup|--prune) FLAGS="${FLAGS} $1" ;;
        --cutover) CUTOVER=1 ;;
        -h|--help) usage; exit 0 ;;
        -*) usage >&2; exit 2 ;;
        *)
            if [[ -z "${HOST}" ]]; then
                HOST="$1"
            elif [[ -z "${VERSION}" ]]; then
                VERSION="${1#v}"
            else
                usage >&2; exit 2
            fi ;;
    esac
    shift
done
if [[ -z "${HOST}" ]]; then
    usage >&2
    exit 2
fi
if [[ -z "${VERSION}" ]]; then
    VERSION=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "${ROOT}/pom.xml" | head -n 1)
fi
if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Not a release version: ${VERSION}" >&2
    usage >&2
    exit 2
fi

if [[ "${CUTOVER}" -eq 1 ]]; then
    for flag in ${DIR_GIVEN} ${FLAGS}; do
        echo "--cutover takes no ${flag}: it always backs up, and always moves ${OLD_DIR}/ to pressroom/." >&2
        exit 2
    done
    echo "==> Copying the cutover and rollback scripts to ${HOST}:${OLD_DIR}/"
    scp -q "${ROOT}/deploy/cutover-pressroom.sh" "${ROOT}/deploy/rollback-pressroom.sh" "${HOST}:${OLD_DIR}/"
    echo "==> Cutting ${HOST} over to ${VERSION}..."
    ssh "${HOST}" "cd ${OLD_DIR} && bash cutover-pressroom.sh ${VERSION}"
else
    echo "==> Copying upgrade.sh to ${HOST}:${DIR}/"
    scp -q "${ROOT}/deploy/upgrade.sh" "${HOST}:${DIR}/upgrade.sh"
    echo "==> Upgrading ${HOST} to ${VERSION}..."
    ssh "${HOST}" "cd $(printf %q "${DIR}") && bash upgrade.sh ${VERSION}${FLAGS}"
fi

if [[ -n "${URL}" ]]; then
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "${URL}" || true)
    if [[ "${code}" != 200 ]]; then
        echo "${URL} answered ${code:-nothing}, not 200. ${HOST} is upgraded; the route to it is not right." >&2
        exit 1
    fi
    echo "==> ${URL} answers 200."
fi
echo "==> ${HOST} is on ${VERSION}."
