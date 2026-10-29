#!/usr/bin/env bash
# Starts the executable Roller WAR for the browser IT suite and blocks until
# it answers, or fails with the app log tail for diagnosis -- killing what it
# started on every failure path.
#
# Replaces cargo's Tomcat deploy + pingURL wait: same readiness contract
# (poll pressroom-ui/login.rol, 180s budget), against the actual Boot-executable
# WAR instead of an exploded WAR under an external Tomcat.
#
# THE TRAP IS THE POINT. This runs in pre-integration-test, so a non-zero exit
# here aborts the build *before* post-integration-test -- the phase where
# app-stop lives. Exiting without killing the JVM it had just started therefore
# leaked it permanently, and the readiness-timeout path did exactly that. Every
# exit from this script now goes through cleanup().
#
# Usage: start-app.sh <war> <port> <custom-config-properties> <pidfile> <log> <run-id>
#
# Environment (all optional):
#   IT_APP_JAVA               java command to launch with (default: java)
#   IT_APP_HEAP               max heap for the app under test (default: 1g).
#                             Without -Xmx the JVM takes a quarter of physical
#                             RAM as its ceiling -- 8g on a 32G developer
#                             machine -- for an app whose measured footprint
#                             under this suite is well under a gigabyte. That
#                             ceiling costs nothing until the heap grows into
#                             it, at which point it is competing with four
#                             Chromes for the same memory.
#   IT_READY_TIMEOUT_SECONDS  readiness budget (default: 180)
#   IT_OWNER_PID              build process that owns this app (default: $PPID,
#                             which under exec-maven-plugin is the Maven JVM)
#   IT_CONTEXT_PATH           bare context segment the app is deployed under,
#                             e.g. "pressroom". EMPTY (the default) means the
#                             root, which is what the app itself defaults to --
#                             the suite tracks the shipped default rather than
#                             pinning a prefix production does not use.
#                             `mvn verify -Pit -Dit.context.path=pressroom` runs
#                             the whole suite under a prefix instead, which is
#                             the only end-to-end coverage of that case.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=it-harness-lib.sh
. "$SCRIPT_DIR/it-harness-lib.sh"

WAR="$1"; PORT="$2"; PROPS="$3"; PIDFILE="$4"; LOG="$5"; RUN_ID="$6"

JAVA="${IT_APP_JAVA:-java}"
APP_HEAP="${IT_APP_HEAP:-1g}"
READY_TIMEOUT="${IT_READY_TIMEOUT_SECONDS:-180}"
OWNER="$(it_owner_stamp "${IT_OWNER_PID:-$PPID}")"

# The bare segment ("pressroom") becomes the servlet context path ("/pressroom") and
# the readiness url prefix. Empty means the root: Spring Boot normalises a lone
# "/" to the empty string, so passing "/" is the same thing and keeps the
# command line free of an empty-looking argument. URL_PREFIX must stay empty in
# that case -- "/" would make every readiness url double-slashed.
CONTEXT_SEGMENT="${IT_CONTEXT_PATH:-}"
CONTEXT_SEGMENT="${CONTEXT_SEGMENT#/}"
CONTEXT_SEGMENT="${CONTEXT_SEGMENT%/}"
if [ -n "$CONTEXT_SEGMENT" ]; then
    CONTEXT_PATH="/$CONTEXT_SEGMENT"
    URL_PREFIX="/$CONTEXT_SEGMENT"
else
    CONTEXT_PATH="/"
    URL_PREFIX=""
fi

APP_PID=""

cleanup() {
    local status=$?
    if [ "$status" -ne 0 ] && [ -n "$APP_PID" ]; then
        echo "start-app.sh: exiting $status -- killing the app it started (pid $APP_PID, run $RUN_ID)" >&2
        it_kill_pids 10 "$APP_PID" || echo "start-app.sh: pid $APP_PID survived SIGKILL" >&2
        rm -f "$PIDFILE"
    fi
    exit "$status"
}
trap cleanup EXIT
# Ctrl-C and `mvn` being killed both arrive as signals: turn them into an exit,
# so the EXIT trap above does the killing rather than the shell dying silently.
trap 'exit 130' INT
trap 'exit 143' TERM

if [ ! -f "$WAR" ]; then
    echo "start-app.sh: WAR not found at $WAR -- the reactor must build the" \
         "app module (repackaging it into an executable WAR) before" \
         "it-selenium's pre-integration-test phase runs. Run the full" \
         "reactor ('mvn verify -Pit' from the repo root), not 'mvn verify'" \
         "inside it-selenium alone." >&2
    exit 1
fi

if [ ! -f "$PROPS" ]; then
    echo "start-app.sh: properties file not found at $PROPS -- it is a filtered" \
         "test resource; run the 'verify' lifecycle (which runs" \
         "process-test-resources first), not this script standalone." >&2
    exit 1
fi

mkdir -p "$(dirname "$PIDFILE")" "$(dirname "$LOG")"

# management.server.port=0 (random free port): the ITs never poll actuator
# (readiness below is pressroom-ui/login.rol on the main port), and application
# .properties' default of 8090 would otherwise collide across concurrent IT
# runs the same way a fixed app port would -- 0 sidesteps that with no need
# to reserve/thread a dedicated port property through this module.
#
# pressroom.it.run / pressroom.it.owner are inert as far as Roller is concerned: they
# exist so this process can be found again by anything that has to clean it up.
#
# The two DEBUG loggers exist for one reason: an unexplained 403 (the one on
# createUser!save.rol was named by them -- docs/dev/build-and-ci.md, "CI: three
# tiers" -- and the next one should be too). Every path that answers
# 403 to an admin POST -- Spring Security's CsrfFilter, its
# ExceptionTranslationFilter, and PressroomHandlerInterceptor's own DENIED
# branches -- logs only at DEBUG, so at INFO the app log of a failing run
# said nothing at all. IT runs are short and the log is per-run; the volume
# is affordable and the next occurrence names its cause. Those loggers reach
# $LOG (log4j2.xml sends every logger to the console) and also log4j2's file
# appender; pressroom.log.dir puts that file beside this run's stdout log
# (pressroom-<run id>/pressroom.log) instead of in ./logs under the module, where
# it was shared by every run and found by nobody.
"$JAVA" -Xmx"$APP_HEAP" -Djava.awt.headless=true -Dpressroom.custom.config="$PROPS" \
     "${IT_RUN_PROP}$RUN_ID" "${IT_OWNER_PROP}$OWNER" \
     "-Dpressroom.log.dir=$(dirname "$LOG")/pressroom-$RUN_ID" \
     -jar "$WAR" --server.port="$PORT" --server.servlet.context-path="$CONTEXT_PATH" \
     --management.server.port=0 \
     --logging.level.org.springframework.security=DEBUG \
     --logging.level.com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor=DEBUG \
     > "$LOG" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$PIDFILE"

# A stable name for the newest run's log, since the logs themselves are now
# per-run (a shared app.log was truncated by the next run, destroying the
# diagnostics of the run that had just leaked).
ln -sfn "$(basename "$LOG")" "$(dirname "$LOG")/app-latest.log" 2>/dev/null || true

echo "start-app.sh: run $RUN_ID, app pid $APP_PID, owner $OWNER, log $LOG, context '${URL_PREFIX:-/}'"

DEADLINE=$(( SECONDS + READY_TIMEOUT ))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "start-app.sh: the app exited before answering on ${PORT}; log tail:" >&2
        tail -50 "$LOG" >&2
        APP_PID=""
        exit 1
    fi
    if curl -sf "http://127.0.0.1:${PORT}${URL_PREFIX}/pressroom-ui/login.rol" > /dev/null; then
        exit 0
    fi
    sleep 2
done

echo "start-app.sh: Roller did not answer on ${PORT} within ${READY_TIMEOUT}s; log tail:" >&2
tail -50 "$LOG" >&2
exit 1
