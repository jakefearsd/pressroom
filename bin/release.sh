#!/usr/bin/env bash
# Cut a release: verify, move the version, commit, tag and (with --push)
# publish.
#
# Usage:
#   bin/release.sh VERSION [--skip-verify] [--push]
#
#   VERSION        the new version, e.g. 0.2.3; must be above the current one
#   --skip-verify  skip the builds in step 1 (they already passed on this commit)
#   --push         push master and the tag, then wait for the Release workflow
#                  to publish the images and the release files
#
# Refuses unless the tree is clean, on master and not behind origin/master,
# and the tag is new. Then, in an order that leaves nothing to undo if a step
# fails:
#
#   1. mvn clean install (the unit suite and every gate), then the browser
#      suite at the root and at /pressroom
#   2. moves the version in pom.xml, app/pom.xml and it-selenium/pom.xml,
#      and IMAGE_VERSION in deploy/.env.example
#   3. commits "release: VERSION" and tags vVERSION
#
# Without --push it stops there and prints the push: a pushed v*.*.* tag
# publishes images, so that step is taken on purpose. An agent never passes
# --push (CLAUDE.md: no agent may ever push).
#
# Then deploy it: bin/deploy-host.sh HOST
set -euo pipefail

ROOT="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )/.." >/dev/null 2>&1 && pwd )"
cd "${ROOT}"

POMS="pom.xml app/pom.xml it-selenium/pom.xml"
ENV_EXAMPLE=deploy/.env.example

usage() { awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; }
fail() { echo "release: $*" >&2; exit 1; }

# The project's version is the first <version> in each pom: the root pom's
# own, and the <parent> block's in the two modules.
project_version() { sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$1" | head -n 1; }

VERSION=""
VERIFY=1
PUSH=0
for arg in "$@"; do
    case "$arg" in
        --skip-verify) VERIFY=0 ;;
        --push) PUSH=1 ;;
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
TAG="v${VERSION}"
current=$(project_version pom.xml)

branch=$(git rev-parse --abbrev-ref HEAD)
[[ "${branch}" == master ]] || fail "releases are cut from master, and this is ${branch}."
[[ -z "$(git status --porcelain)" ]] || fail "the working tree is not clean; commit or stash first."
git fetch -q origin master
behind=$(git rev-list --count HEAD..origin/master)
[[ "${behind}" -eq 0 ]] || fail "master is ${behind} commit(s) behind origin/master; pull first."
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null \
        || [[ -n "$(git ls-remote --tags origin "refs/tags/${TAG}")" ]]; then
    fail "${TAG} already exists."
fi
highest=$(printf '%s\n%s\n' "${current}" "${VERSION}" | sort -V | tail -n 1)
if [[ "${VERSION}" == "${current}" || "${highest}" != "${VERSION}" ]]; then
    fail "${VERSION} is not above the current version, ${current}."
fi

if [[ "${VERIFY}" -eq 1 ]]; then
    # One build at a time per tree: builds share app/target. Listed before
    # matching, so the check can never match itself.
    procs=$(ps -eo args)
    if awk -v root="${ROOT}/" 'index($0, "surefirebooter") && index($0, root) { found = 1 }
                               END { exit !found }' <<<"${procs}"; then
        fail "a build is already running in ${ROOT}; wait for it to finish."
    fi
    echo "==> The unit suite and every gate..."
    mvn clean install
    echo "==> The browser suite at the root..."
    mvn verify -Pit -DskipUnitTests=true
    echo "==> The browser suite at /pressroom..."
    mvn verify -Pit -DskipUnitTests=true -Dit.context.path=pressroom
fi

echo "==> Moving the version from ${current} to ${VERSION}..."
for pom in ${POMS}; do
    # The first occurrence only: a dependency may share the number.
    awk -v from="<version>${current}</version>" -v to="<version>${VERSION}</version>" '
        !done && (i = index($0, from)) { $0 = substr($0, 1, i - 1) to substr($0, i + length(from)); done = 1 }
        { print }' "${pom}" > "${pom}.tmp"
    mv "${pom}.tmp" "${pom}"
    [[ "$(project_version "${pom}")" == "${VERSION}" ]] \
        || fail "could not move the version in ${pom}; undo with: git checkout -- ."
done
sed "s/^IMAGE_VERSION=.*/IMAGE_VERSION=${VERSION}/" "${ENV_EXAMPLE}" > "${ENV_EXAMPLE}.tmp"
mv "${ENV_EXAMPLE}.tmp" "${ENV_EXAMPLE}"
grep -qx "IMAGE_VERSION=${VERSION}" "${ENV_EXAMPLE}" \
    || fail "could not move IMAGE_VERSION in ${ENV_EXAMPLE}; undo with: git checkout -- ."

# shellcheck disable=SC2086 # POMS is a word list
git commit -q -m "release: ${VERSION}" -- ${POMS} "${ENV_EXAMPLE}"
git tag "${TAG}"
echo "==> Committed \"release: ${VERSION}\" and tagged ${TAG}."

if [[ "${PUSH}" -ne 1 ]]; then
    echo "Not pushed. To publish (the tag builds and publishes the images):"
    echo "  git push origin master ${TAG}"
    echo "Then deploy it: bin/deploy-host.sh HOST"
    exit 0
fi

git push origin master "${TAG}"
echo "==> Waiting for the Release workflow on ${TAG}..."
run=""
for _ in $(seq 1 30); do
    run=$(gh run list --workflow release.yml --branch "${TAG}" --limit 1 \
            --json databaseId --jq '.[0].databaseId // empty')
    if [[ -n "${run}" ]]; then
        break
    fi
    sleep 5
done
[[ -n "${run}" ]] || fail "pushed, but no Release run for ${TAG} appeared; check GitHub Actions."
gh run watch "${run}" --exit-status
echo "==> ${TAG} is published. Deploy it: bin/deploy-host.sh HOST"
