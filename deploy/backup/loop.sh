#!/usr/bin/env bash
# Nightly backup loop for the `backup` service in docker-compose.prod.yml.
# No cron daemon in the app image this service now runs (it used to be the
# postgres:16 base image, back when the scripts were bind-mounted in), so
# this is the whole scheduler: wake up once an hour, and if it's BACKUP_HOUR
# (UTC) and backup.sh hasn't already run today, run it, and after it
# succeeds, umami-retention.sh (analytics older than UMAMI_RETENTION_DAYS).
# Equivalent to a `0 <BACKUP_HOUR> * * *` crontab entry, without needing cron
# installed.
set -euo pipefail

BACKUP_HOUR="${BACKUP_HOUR:-3}"
last_run_day=""

echo "[$(date -u +%FT%TZ)] Backup loop started; running daily around ${BACKUP_HOUR}:00 UTC."

while true; do
    # Force base-10 on BOTH operands: date can print hours with a leading
    # zero ("08", "09"), which bash's arithmetic/[[ -eq ]] context would
    # otherwise misread as octal -- and an operator is just as likely to
    # write a zero-padded BACKUP_HOUR (e.g. "08") in .env, which would
    # crash this same comparison ("value too great for base") if left
    # unconverted.
    current_hour=$((10#$(date -u +%H)))
    backup_hour=$((10#${BACKUP_HOUR}))
    current_day="$(date -u +%Y-%m-%d)"

    if [[ "${current_hour}" -eq "${backup_hour}" && "${current_day}" != "${last_run_day}" ]]; then
        # Resolve backup.sh relative to this script rather than hardcoding
        # /backup.sh -- that absolute path only ever existed under the old
        # compose file, which bind-mounted both scripts in at the container
        # root. The image now bakes the pair at /app/backup/{loop,backup}.sh
        # (Dockerfile), and a stale /backup.sh here resolved to nothing: bash
        # returned 127, this `if` swallowed it so `set -e` never fired, and
        # the loop logged one line to stderr and slept another hour --
        # forever, with no dump and no volume snapshot ever produced. Keeping
        # the pair addressed relative to each other is what makes that class
        # of drift impossible to reintroduce.
        if "$(dirname "$0")/backup.sh"; then
            last_run_day="${current_day}"
            # Analytics retention (umami-retention.sh), only after a backup
            # that succeeded, so the dump just taken still holds every row it
            # deletes. Its failure is logged and never stops this loop or
            # re-runs the day's backup; tomorrow's run picks up the rest.
            if ! "$(dirname "$0")/umami-retention.sh"; then
                echo "[$(date -u +%FT%TZ)] Umami retention failed; will retry after tomorrow's backup." >&2
            fi
        else
            echo "[$(date -u +%FT%TZ)] Backup failed; will retry at ${BACKUP_HOUR}:00 UTC tomorrow." >&2
        fi
    fi

    sleep 3600
done
