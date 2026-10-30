#!/usr/bin/env bash
# Stops the Pressroom process started by start-app.sh: TERM, wait, then KILL if it
# hasn't exited.
#
# Works from the run id, not only from the pidfile. A pidfile is a hint that may
# be missing (the app never started), stale (the pid has been recycled) or --
# the case that actually bit -- overwritten, back when every run wrote the same
# app.pid and run N+1 destroyed the only record of run N's leaked JVM. The run
# marker on the process's own command line cannot be lost that way, so the
# pidfile is now belt and the marker is braces.
#
# Usage: stop-app.sh <pidfile> <run-id>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=it-harness-lib.sh
. "$SCRIPT_DIR/it-harness-lib.sh"

PIDFILE="$1"
RUN_ID="${2:-}"

FAILED=0

stop_pid() {
    local pid="$1" why="$2"
    ps -p "$pid" >/dev/null 2>&1 || return 0
    echo "stop-app.sh: stopping pid $pid ($why)"
    echo "  $(it_describe_pid "$pid")"
    if it_kill_pids 30 "$pid"; then
        echo "stop-app.sh: reaped pid $pid"
    else
        echo "stop-app.sh: pid $pid survived SIGKILL" >&2
        FAILED=1
    fi
}

if [ -f "$PIDFILE" ]; then
    stop_pid "$(cat "$PIDFILE")" "from $PIDFILE"
    rm -f "$PIDFILE"
else
    echo "stop-app.sh: no pidfile at $PIDFILE"
fi

if [ -n "$RUN_ID" ]; then
    for pid in $(it_pids_for_run "$RUN_ID" "$$"); do
        stop_pid "$pid" "run $RUN_ID, found by marker"
    done
fi

exit "$FAILED"
