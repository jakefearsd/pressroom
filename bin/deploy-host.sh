#!/usr/bin/env bash
# Upgrade a host to a published release, from this checkout.
#
# Usage:
#   bin/deploy-host.sh HOST [VERSION] [--dir DIR] [--no-backup] [--prune] [--url URL]
#   bin/deploy-host.sh HOST VERSION --cutover [--url URL]
#   bin/deploy-host.sh HOST VERSION --cutover --check
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
#   --check      with --cutover: the read-only check to run first. Copies
#                only deploy/cutover-pressroom.sh into the old directory and
#                runs its --check there, in the foreground: no backup, no
#                stop, no copy. Exits with its status.
#
# Copies deploy/upgrade.sh into DIR on the host and runs it there; that
# script does the work (backup, release files, IMAGE_VERSION, deploy.sh).
# With --cutover it copies deploy/cutover-pressroom.sh and
# deploy/rollback-pressroom.sh into the old directory instead and runs the
# cutover there, detached from the ssh session (a dropped connection does not
# stop it), logging to ~/pressroom-cutover-<UTC stamp>.log, which it follows
# until the cutover exits. The rollback stays beside it for as long as that
# directory is kept. The release must be published first: bin/release.sh VERSION --push.
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
CHECK=0
# The deploy directory before the cutover; the cutover makes DIR beside it.
OLD_DIR=roller  # rebrand:keep
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dir) DIR="${2:?--dir needs a directory}"; DIR_GIVEN=--dir; shift ;;
        --url) URL="${2:?--url needs a url}"; shift ;;
        --no-backup|--prune) FLAGS="${FLAGS} $1" ;;
        --cutover) CUTOVER=1 ;;
        --check) CHECK=1 ;;
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

if [[ "${CHECK}" -eq 1 && "${CUTOVER}" -eq 0 ]]; then
    echo "--check is for --cutover only." >&2
    usage >&2
    exit 2
fi
if [[ "${CHECK}" -eq 1 && -n "${URL}" ]]; then
    echo "--check takes no --url: it moves nothing, so there is nothing new to reach." >&2
    exit 2
fi

if [[ "${CUTOVER}" -eq 1 ]]; then
    for flag in ${DIR_GIVEN} ${FLAGS}; do
        echo "--cutover takes no ${flag}: it always backs up, and always moves ${OLD_DIR}/ to pressroom/." >&2
        exit 2
    done
    # A log with no .exit beside it is a cutover still running, or one that
    # died uncleanly; either way, not one to start a second beside.
    # shellcheck disable=SC2016
    unfinished=$(ssh "${HOST}" 'for f in pressroom-cutover-*.log; do [ -e "$f" ] || continue; [ -e "$f.exit" ] || echo "$f"; done')
    if [[ -n "${unfinished}" ]]; then
        echo "Refusing: a cutover on ${HOST} has not exited (its log has no .exit beside it):" \
             "it is still running, or it died uncleanly. Reattach:" >&2
        while IFS= read -r f; do
            echo "  ssh ${HOST} 'tail -f ~/${f}'" >&2
        done <<<"${unfinished}"
        echo "If the process is confirmed dead, delete or rename that log on ${HOST} and run this again." >&2
        exit 1
    fi
    if [[ "${CHECK}" -eq 1 ]]; then
        # Read only, so in the foreground: nothing to lose if ssh drops.
        echo "==> Copying the cutover script to ${HOST}:${OLD_DIR}/"
        scp -q "${ROOT}/deploy/cutover-pressroom.sh" "${HOST}:${OLD_DIR}/"
        echo "==> Checking a cutover of ${HOST} to ${VERSION} (read only)..."
        rc=0
        # Expanded here on purpose: OLD_DIR is fixed and VERSION checked as x.y.z.
        # shellcheck disable=SC2029
        ssh "${HOST}" "cd ${OLD_DIR} && bash cutover-pressroom.sh --check ${VERSION}" || rc=$?
        if [[ "${rc}" -ne 0 ]]; then
            echo "The cutover check on ${HOST} exited ${rc}; fix what it names before the cutover." >&2
            exit "${rc}"
        fi
        echo "==> The cutover check on ${HOST} passed; nothing on it was changed."
        exit 0
    fi
    echo "==> Copying the cutover and rollback scripts to ${HOST}:${OLD_DIR}/"
    scp -q "${ROOT}/deploy/cutover-pressroom.sh" "${ROOT}/deploy/rollback-pressroom.sh" "${HOST}:${OLD_DIR}/"
    # Detached on the host: its own session (setsid), deaf to the hang-up
    # (nohup), output to a log beside both deploy directories. This session
    # only follows the log, so a dropped ssh stops the following, never the
    # cutover. The exit status lands in <log>.exit.
    log="pressroom-cutover-$(date -u +%Y%m%dT%H%M%SZ).log"
    echo "==> Cutting ${HOST} over to ${VERSION}, detached on the host, logging to ~/${log}."
    echo "    If this session drops, reattach with: ssh ${HOST} 'tail -f ~/${log}'"
    echo "    (the cutover's exit status is then in ~/${log}.exit)"
    # shellcheck disable=SC2016
    remote='cd OLD_DIR || exit 1; log=../LOG
setsid nohup bash -c '"'"'bash cutover-pressroom.sh "$1"; echo "$?" > "$2.exit"'"'"' cutover VERSION "$log" > "$log" 2>&1 < /dev/null &
pid=$!
tail -n +1 --pid="$pid" -F "$log" 2>/dev/null
while [ ! -s "$log.exit" ] && kill -0 "$pid" 2>/dev/null; do sleep 1; done
rc=$(cat "$log.exit" 2>/dev/null || echo 255)
exit "$rc"'
    remote="${remote//OLD_DIR/${OLD_DIR}}"
    remote="${remote//LOG/${log}}"
    remote="${remote//VERSION/${VERSION}}"
    rc=0
    ssh "${HOST}" "${remote}" || rc=$?
    if [[ "${rc}" -ne 0 ]]; then
        echo "The cutover on ${HOST} exited ${rc}. Its log: ${HOST}:~/${log}" \
             "(255 can also mean the ssh session dropped: reattach with tail -f as above)." >&2
        exit "${rc}"
    fi
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
