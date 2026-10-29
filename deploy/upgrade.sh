#!/usr/bin/env bash
# Upgrade this host's stack to a release, in one command.
#
# Usage:
#   ./upgrade.sh VERSION [--no-backup] [--prune]
#
#   VERSION      the release to move to, e.g. 0.2.3 (a leading v is fine)
#   --no-backup  skip the backup taken before anything changes
#   --prune      handed to deploy.sh: prune dangling images afterwards
#
# Run from the directory holding docker-compose.prod.yml and .env. It does
# the Upgrades steps of docker_deployment.md, ordered so that nothing changes
# until everything needed to finish is in hand:
#
#   1. downloads the release's docker-compose.prod.yml, deploy.sh and
#      env.example (a missing one stops here)
#   2. takes a backup through the backup service (a failed one stops here)
#   3. shows how the downloaded files differ from the ones in place
#   4. keeps .env and the files it replaces as <name>.bak-<timestamp>
#   5. moves the new files in and sets IMAGE_VERSION in .env
#   6. names any key the new env.example sets that .env does not
#   7. runs ./deploy.sh, then checks the app runs the new image
#
# It refuses to cross 0.5.0 in either direction. 0.5.0's migration V041
# renames the schema in place, so neither swapping in a 0.5 image nor
# swapping a 0.4 one back is an upgrade or a rollback; the one way across is
# the cutover (bin/deploy-host.sh HOST VERSION --cutover, see
# docs/dev/pressroom-cutover.md), and the one way back is the old
# directory's rollback-pressroom.sh.
#
# From a checkout, bin/deploy-host.sh copies this script to a host and runs
# it there. RELEASE_URL overrides where releases download from (a fork).
set -euo pipefail

RELEASE_URL="${RELEASE_URL:-https://github.com/jakefearsd/pressroom/releases/download}"
RELEASE_URL="${RELEASE_URL%/}"
COMPOSE_FILE=docker-compose.prod.yml
COMPOSE=(docker compose -f "${COMPOSE_FILE}")
FILES=(docker-compose.prod.yml deploy.sh env.example)

usage() { awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; }

VERSION=""
BACKUP=1
PRUNE=0
for arg in "$@"; do
    case "$arg" in
        --no-backup) BACKUP=0 ;;
        --prune) PRUNE=1 ;;
        -h|--help) usage; exit 0 ;;
        *)
            if [[ -n "${VERSION}" ]]; then usage >&2; exit 2; fi
            VERSION="${arg#v}" ;;
    esac
done
if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    usage >&2
    exit 2
fi

for required in "${COMPOSE_FILE}" .env; do
    if [[ ! -f "${required}" ]]; then
        echo "Missing ${required} in $(pwd). Run this from the deploy directory." >&2
        exit 1
    fi
done
if ! grep -q '^IMAGE_VERSION=' .env; then
    echo ".env has no IMAGE_VERSION line to move. Add IMAGE_VERSION=<the running version> and rerun." >&2
    exit 1
fi
current=$(sed -n 's/^IMAGE_VERSION=//p' .env | tail -n 1)

# True when version $1 predates the 0.5.0 rename (V041).
before_rename() {
    local major minor
    IFS=. read -r major minor _ <<<"$1"
    [[ "${major}" =~ ^[0-9]+$ && "${minor}" =~ ^[0-9]+$ ]] || return 1
    (( major == 0 && minor < 5 ))
}
if before_rename "${current}" && ! before_rename "${VERSION}"; then
    echo "Refusing: this host runs ${current}, and ${VERSION} renames the database, role and" \
         "tables (migration V041). That is not an image swap, and a swap back would not undo it." >&2
    echo "The only path is the cutover, from a checkout: bin/deploy-host.sh HOST ${VERSION} --cutover" \
         "(docs/dev/pressroom-cutover.md). Nothing has changed." >&2
    exit 1
fi
if ! before_rename "${current}" && before_rename "${VERSION}"; then
    echo "Refusing: ${VERSION} predates the 0.5.0 rename this host's database has had (V041)," \
         "so its image cannot run on it. To return a cutover host to its old stack, run" \
         "rollback-pressroom.sh in the old deploy directory (docs/dev/pressroom-cutover.md)." \
         "Nothing has changed." >&2
    exit 1
fi
echo "==> Upgrading from ${current} to ${VERSION}."

# Beside the files it replaces, so the move in step 5 is a rename.
staging=$(mktemp -d .upgrade-XXXXXX)
trap 'rm -rf "${staging}"' EXIT

echo "==> Downloading the v${VERSION} release files..."
for f in "${FILES[@]}"; do
    if ! curl -fsSL -o "${staging}/${f}" "${RELEASE_URL}/v${VERSION}/${f}"; then
        echo "Could not download ${f} for v${VERSION}; nothing has changed." >&2
        exit 1
    fi
done

if [[ "${BACKUP}" -eq 1 ]]; then
    echo "==> Taking a backup first..."
    # stdin closed: over `ssh host 'bash -s'` it would be the rest of this script.
    if ! "${COMPOSE[@]}" exec -T backup /app/backup/backup.sh </dev/null; then
        echo "The backup failed; nothing has changed. Fix it, or rerun with --no-backup." >&2
        exit 1
    fi
fi

echo "==> How the release files differ from the ones in place:"
for f in "${FILES[@]}"; do
    if [[ ! -f "${f}" ]]; then
        echo "    ${f}: new"
    elif cmp -s "${f}" "${staging}/${f}"; then
        echo "    ${f}: unchanged"
    else
        diff -u "${f}" "${staging}/${f}" || true
    fi
done

ts=$(date -u +%Y%m%dT%H%M%SZ)  # the backup files' own stamp
for f in .env "${FILES[@]}"; do
    if [[ -f "${f}" ]]; then
        cp -p "${f}" "${f}.bak-${ts}"
    fi
done
for f in "${FILES[@]}"; do
    mv "${staging}/${f}" "${f}"
done
chmod +x deploy.sh
sed -i.tmp "s/^IMAGE_VERSION=.*/IMAGE_VERSION=${VERSION}/" .env
rm -f .env.tmp
echo "==> The replaced files are kept as <name>.bak-${ts}. To roll back:"
echo "      for f in .env ${FILES[*]}; do cp -p \"\$f.bak-${ts}\" \"\$f\"; done; ./deploy.sh"
# The cutover leaves its row counts in the directory it made.
if [[ -f cutover-counts-before.txt ]]; then
    echo "    That reaches ${current}, no further back. To return to the stack this host ran before"
    echo "    the cutover, while its old deploy directory is kept, run rollback-pressroom.sh there;"
    echo "    never an image swap:"
    echo "      cd ../roller && bash rollback-pressroom.sh"  # rebrand:keep
fi

missing=""
while IFS= read -r key; do
    if ! grep -q "^${key}=" .env; then
        missing="${missing} ${key}"
    fi
done < <(sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' env.example)
if [[ -n "${missing}" ]]; then
    echo "==> env.example sets keys .env does not:${missing}"
    echo "    Check each in env.example; set it in .env if the default will not do."
fi

echo "==> Running deploy.sh..."
if [[ "${PRUNE}" -eq 1 ]]; then
    ./deploy.sh --prune
else
    ./deploy.sh
fi

# deploy.sh's health check passes on whatever image came up. An
# IMAGE_VERSION exported in this shell beats .env in compose, and would
# leave the old version running, healthy.
running=$("${COMPOSE[@]}" ps --format '{{.Image}}' app </dev/null)
if [[ "${running}" != *":${VERSION}" ]]; then
    echo "The app is running ${running:-nothing}, not ${VERSION}." \
         "Is IMAGE_VERSION exported in this shell? It overrides .env." >&2
    exit 1
fi
echo "==> Upgraded to ${VERSION}."
