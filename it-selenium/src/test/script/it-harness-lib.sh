#!/usr/bin/env bash
# Shared identity and reaping helpers for the browser IT harness. Sourced by
# start-app.sh, stop-app.sh, sweep-stale.sh and supervise-run.sh; not executable
# on its own.
#
# WHY IDENTITY RATHER THAN A PIDFILE
#
# Everything the harness starts carries two system properties on its command
# line:
#
#   -Dpressroom.it.run=<run id>          which run it belongs to
#   -Dpressroom.it.owner=<pid>@<start>   which Maven build owns it
#
# so any process can be found again without a file surviving, and "is this
# stale?" has an exact answer -- the owning build is either still running or it
# is not. A pidfile answers neither question once it has been truncated by the
# next run, which is how four app JVMs came to be running on a machine whose
# it-work directory held no app.pid at all.
#
# The owner token pins the *start time* as well as the pid, because pids are
# recycled: a token whose pid is alive but started at a different moment names
# a build that has exited and had its pid reused, not a live one.
#
# Process discovery is `ps` plus shell matching, never `pgrep -f`: a pgrep
# pattern matches the command line of the process doing the grepping (and of
# any `while pgrep ...; do` loop waiting on it), which is the self-match trap
# CLAUDE.md records. `ps` output cannot contain this script's own pattern.

# THE NAMESPACE. Every marker lives under -D<namespace>.*, and a sweep sees only
# its own namespace. Real runs never set IT_NAMESPACE, so they all share
# "pressroom.it" and a real sweep still finds every real leftover on the machine.
# ItHarnessLeakTest gives each test a namespace of its own: a sweep is
# machine-wide by design, and with one shared namespace a concurrent run's
# sweep reaped that test's deliberately stale fixtures before the test's own
# sweep could report them (and the test's sweeps reaped and printed other
# builds' leftovers), so its verdict depended on what else the machine was
# running. Tests only; never set it for a real run, or its leftovers become
# invisible to the next real sweep.
IT_NAMESPACE="${IT_NAMESPACE:-pressroom.it}"

IT_RUN_PROP="-D${IT_NAMESPACE}.run="
IT_OWNER_PROP="-D${IT_NAMESPACE}.owner="

# The supervisor marks itself with a DIFFERENT property, and the split is not
# cosmetic. A forked shell subprocess (command substitution, either side of a
# pipeline) shows its parent's command line in ps, so a supervisor marked with
# -Dpressroom.it.run= finds its own short-lived subshells while enumerating the
# run's processes and kills them. Observed, not theoretical. Keeping the two
# roles on two properties means the kill list ("what belongs to this run?")
# and the liveness question ("is this run still claimed?") never overlap.
IT_SUPERVISOR_PROP="-D${IT_NAMESPACE}.supervisor="

# Container names are <prefix>-<run id>; the sweep matches on the prefix. The
# default must match it.db.container-alias in it-selenium/pom.xml. Overridden
# by ItHarnessLeakTest alongside IT_NAMESPACE, so its sweeps never remove a
# real run's container.
IT_CONTAINER_PREFIX="${IT_CONTAINER_PREFIX:-roller-it-postgres}"

# A run's second container, the Keycloak that SsoSignInIT signs readers in
# through: <this prefix>-<run id>, the postgres prefix with its role swapped
# (roller-it-keycloak by default, matching it.kc.container-alias). DERIVED, never
# defaulted on its own: ItHarnessLeakTest overrides IT_CONTAINER_PREFIX alone,
# and an independent default would hand its sweeps the real prefix -- from
# another namespace a live run looks unclaimed, so a test would remove that
# run's Keycloak mid-suite. The derivation needs IT_CONTAINER_PREFIX to end in
# -postgres, as both the real and the test values do; otherwise one prefix
# would begin with the other and the postgres pass would misread its run id.
# Checked, not assumed: without the suffix the swap only appends, and the
# postgres pass would match a live run's Keycloak container, read its run id as
# "keycloak-<run id>", and remove it. Every caller runs under set -e, so the
# failed source stops it before it does anything.
case "$IT_CONTAINER_PREFIX" in
    ?*-postgres) ;;
    *)
        printf 'it-harness-lib.sh: IT_CONTAINER_PREFIX must end in -postgres (got "%s"); the Keycloak prefix is derived from it\n' \
            "$IT_CONTAINER_PREFIX" >&2
        return 1 2>/dev/null || exit 1
        ;;
esac
IT_KC_CONTAINER_PREFIX="${IT_CONTAINER_PREFIX%-postgres}-keycloak"

# ---------------------------------------------------------------- ownership

# it_owner_stamp <pid> -> "<pid>@<start time, punctuation stripped>"
#
# Never fails: an unresolvable start time becomes "unknown", which
# it_owner_alive then treats as "cannot disprove", so a missing stamp can only
# ever spare a process, never kill one.
it_owner_stamp() {
    local pid="${1:-0}" started=""
    if [ -n "$pid" ]; then
        started="$(ps -o lstart= -p "$pid" 2>/dev/null | tr -d ' :' || true)"
    fi
    printf '%s@%s' "$pid" "${started:-unknown}"
}

# it_owner_alive <token> -> 0 if the build that owns the token is still running.
it_owner_alive() {
    local token="${1:-}" pid stamp now
    pid="${token%%@*}"
    stamp="${token#*@}"
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    ps -p "$pid" >/dev/null 2>&1 || return 1
    now="$(ps -o lstart= -p "$pid" 2>/dev/null | tr -d ' :' || true)"
    # Alive, but one side has no start time to compare: refuse to guess and
    # call it live. Reaping on a guess is the one failure this must not have.
    if [ -z "$now" ] || [ "$stamp" = "unknown" ]; then
        return 0
    fi
    [ "$now" = "$stamp" ]
}

# ---------------------------------------------------------------- discovery

# Emits "<pid> <run id> <owner token>" for every live process carrying the
# given marker property, owned by this user. Restricted to this user
# deliberately: another account's processes are not ours to kill, and matching
# them would only produce failed kills.
it_processes_with_prop() {
    local prop="$1"
    local pid args runid owner
    ps -ww -U "$(id -u)" -o pid=,args= 2>/dev/null | while read -r pid args; do
        case "$args" in
            *"$prop"*) ;;
            *) continue ;;
        esac
        runid="${args##*"$prop"}"
        runid="${runid%% *}"
        case "$args" in
            *"$IT_OWNER_PROP"*)
                owner="${args##*"$IT_OWNER_PROP"}"
                owner="${owner%% *}"
                ;;
            *) owner="0@unknown" ;;
        esac
        [ -n "$runid" ] || continue
        printf '%s %s %s\n' "$pid" "$runid" "$owner"
    done
}

# Processes a run OWNS and that must be killed to clean it up (the app JVM).
it_marked_processes() {
    it_processes_with_prop "$IT_RUN_PROP"
}

# Supervisors, which merely CLAIM a run. Never killed to clean a run up; they
# exit on their own once the build they watch is gone.
it_supervisor_processes() {
    it_processes_with_prop "$IT_SUPERVISOR_PROP"
}

# it_pids_for_run <run id> [pid to skip...] -> pids belonging to that run.
it_pids_for_run() {
    local want="$1"
    shift
    local pid runid owner skip skipped
    it_marked_processes | while read -r pid runid owner; do
        [ "$runid" = "$want" ] || continue
        skipped=0
        for skip in "$@"; do
            [ "$pid" = "$skip" ] && skipped=1
        done
        [ "$skipped" -eq 1 ] || printf '%s\n' "$pid"
    done
}

# it_run_is_live <run id> -> 0 if anything still holds that run: its app, or
# the supervisor that claims it. The supervisor half matters most between
# pg-start and app-start, the window in which a run owns a container but has
# not started an app yet; without it a concurrent run's fresh container would
# look like an orphan.
it_run_is_live() {
    [ -n "$(it_pids_for_run "$1")" ] && return 0
    local pid runid owner
    while read -r pid runid owner; do
        [ "$runid" = "$1" ] && return 0
    done < <(it_supervisor_processes)
    return 1
}

# it_descendants <pid> -> every process below it, depth first. Used to attribute
# chromedrivers to a run while their JVM is alive; once that JVM dies they
# reparent to init and become unattributable, which is exactly why the
# supervisor records them in advance rather than looking for them afterwards.
it_descendants() {
    local pid="${1:-}" child
    [ -n "$pid" ] || return 0
    for child in $(pgrep -P "$pid" 2>/dev/null || true); do
        printf '%s\n' "$child"
        it_descendants "$child"
    done
}

# ------------------------------------------------------------- attribution

# Where the supervisor's chromedriver records live. This MUST NOT be under
# target/: `mvn clean` deletes target/ wholesale, and a chromedriver can only
# ever be attributed to a run while the JVM that spawned it is alive (see
# it_descendants above) -- once the record naming it is gone, the pid is
# unattributable *forever*, by design, because the sweep correctly refuses to
# kill anything it cannot attribute. That is exactly how 81 processes (8.1GB)
# under 11 chromedrivers went permanently unreapable on one developer machine:
# the record lived at target/it-work/supervisor-<run>.chromedrivers and a
# routine `mvn clean install` erased it out from under a still-running driver.
#
# Deliberately not /tmp either: some systems sweep /tmp on a timer regardless
# of whether the process that owns a file is still alive, which would
# reintroduce the same bug in a subtler, host-dependent form. XDG_CACHE_HOME
# (falling back to ~/.cache, the same convention selenium-manager already
# uses on this machine) is neither of those things. Override with
# IT_RECORD_DIR for tests -- never point it at a real target/ or /tmp.
IT_RECORD_DIR="${IT_RECORD_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/roller-it}"

# it_record_path <run id> -> the path of that run's chromedriver record.
it_record_path() {
    printf '%s/%s.chromedrivers\n' "$IT_RECORD_DIR" "$1"
}

# it_record_init <run id> <owner token> -- create the record (and its
# directory) with a header line naming the owning build, unless it already
# exists. The header is what makes a record self-describing: a bare pid list
# means nothing without a live process to interpret it, which is precisely
# the state a run reaches once BOTH its app and its supervisor have exited --
# the sweep must be able to judge staleness from the file alone.
it_record_init() {
    local path="$(it_record_path "$1")" owner="$2"
    mkdir -p "$IT_RECORD_DIR"
    [ -f "$path" ] || printf 'owner %s\n' "$owner" > "$path"
}

# it_record_add_pid <run id> <pid> -- append, de-duplicated.
it_record_add_pid() {
    local path="$(it_record_path "$1")"
    grep -qx "$2" "$path" 2>/dev/null || printf '%s\n' "$2" >> "$path"
}

# it_record_owner <record path> -> the owner token from its header line, or
# empty if the record has none. Callers must treat empty the way
# it_owner_alive treats an unresolvable start time: as "cannot disprove
# liveness", never as licence to reap -- a record this library did not write
# is not this library's to guess about.
it_record_owner() {
    local first="$(head -n 1 "$1" 2>/dev/null || true)"
    case "$first" in
        "owner "*) printf '%s\n' "${first#owner }" ;;
    esac
}

# it_record_pids <record path> -> the numeric pid lines only; the header line
# and anything blank are excluded by construction (a bare digits-only test),
# not by knowing the header's exact shape.
it_record_pids() {
    local line
    while IFS= read -r line; do
        case "$line" in
            ''|*[!0-9]*) continue ;;
        esac
        printf '%s\n' "$line"
    done < "$1"
}

# ------------------------------------------------------------------ reaping

# The command name of a pid, as a bare basename on every platform.
#
# `ps -o comm=` is NOT portable in the way this harness assumed. On Linux it
# prints the executable's basename ("chromedriver"); on macOS/BSD it prints the
# FULL PATH (there, the short accounting name is `ucomm`). So a bare
# `case "$comm" in chromedriver*)` matches nothing on a Mac against a real path
# like /Users/x/.cache/selenium/chromedriver/mac-arm64/141.0/chromedriver --
# record_chromedrivers records nothing, the supervisor and the sweep reap
# nothing, and both still report success. That is the harness's own failure
# mode (a leak whose silence is the defect) reintroduced by a userland
# difference. Stripping any leading path makes the match identical on both.
it_comm() {
    local pid="${1:-}" comm
    [ -n "$pid" ] || return 0
    comm="$(ps -o comm= -p "$pid" 2>/dev/null || true)"
    printf '%s\n' "${comm##*/}"
}

# it_kill_pids <grace seconds> <pid...> -- TERM, wait, KILL. Returns non-zero
# if anything survived both, so callers can report a failure to clean up rather
# than assuming success.
it_kill_pids() {
    local grace="${1:-10}"
    shift
    [ "$#" -gt 0 ] || return 0

    local pid i alive
    for pid in "$@"; do
        kill -TERM "$pid" 2>/dev/null || true
    done

    for i in $(seq 1 "$grace"); do
        alive=""
        for pid in "$@"; do
            ps -p "$pid" >/dev/null 2>&1 && alive="$alive $pid"
        done
        [ -n "$alive" ] || return 0
        sleep 1
    done

    for pid in $alive; do
        kill -KILL "$pid" 2>/dev/null || true
    done
    sleep 1

    for pid in "$@"; do
        if ps -p "$pid" >/dev/null 2>&1; then
            return 1
        fi
    done
    return 0
}

# it_reap_chromedriver_pid <pid> [grace seconds] -- kills a recorded
# chromedriver AND its descendants. A bare SIGKILL to chromedriver does NOT
# take its Chrome children with it -- that is where a reaped run's memory
# actually sits, and it is why a leak measured in gigabytes hid under a
# process count that looked like just "some chromedrivers". Re-checks the pid
# is STILL a chromedriver first, since pids recycle; a record is written once
# and can outlive the pid it names by a long time.
#
# Returns 0 if everything in the tree died, 1 if something survived SIGKILL
# (a real failure), 2 if the pid is no longer a chromedriver at all (expected
# whenever a recycled pid is caught by the recheck -- not a failure).
it_reap_chromedriver_pid() {
    local pid="$1" grace="${2:-10}" tree
    case "$(it_comm "$pid")" in
        chromedriver*) ;;
        *) return 2 ;;
    esac
    tree="$pid $(it_descendants "$pid")"
    it_kill_pids "$grace" $tree
}

# it_describe_pid <pid> -- one line of evidence for the report: what it is and
# how long it has been running. Printed before killing, never after.
it_describe_pid() {
    local pid="$1"
    ps -ww -o pid=,etime=,rss=,args= -p "$pid" 2>/dev/null | cut -c1-160 || true
}

# it_remove_container <name> -- idempotent, and always with -v: the IT postgres
# container is anonymous-volume-backed (postgres:18 declares VOLUME
# /var/lib/postgresql), so removing it without -v orphans a volume per run.
it_remove_container() {
    local name="${1:-}"
    [ -n "$name" ] || return 0
    command -v docker >/dev/null 2>&1 || return 0
    if [ -n "$(docker ps -aq --filter "name=^${name}$" 2>/dev/null)" ]; then
        docker rm -f -v "$name" >/dev/null 2>&1 && printf 'removed container %s (with its volume)\n' "$name"
    fi
}
