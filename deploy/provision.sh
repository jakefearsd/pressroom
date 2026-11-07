#!/usr/bin/env bash
# One-shot database provisioning for the production stack.
#
# Runs as the `provision` service in docker-compose.prod.yml, from the app
# image, after postgres reports healthy and before app/listmonk start
# (compose enforces both with depends_on conditions). Two steps: apply the
# pressroom migration chain, and grant grafana_ro CONNECT on the pressroom
# database. Every step is idempotent, so
# it runs on every `docker compose up -d` and is a no-op once the stack is
# current.
#
# No back-office database is here: Umami's lives on umami-db since 0.7.2 and
# listmonk's on listmonk-db since 0.8.5, each created by its server's image
# (wiki SA-15). An old host's former copies on this server are the moves'
# rollback points and are dropped by hand; nothing here may name them, or a
# statement on one would fail every deploy after the drop.
#
# This replaces the equivalent steps that used to live in deploy/deploy.sh and
# needed a git checkout on the host to copy migrate.sh and the migrations into
# the postgres container. It also fixes a real bug in that version: it expanded
# ${UMAMI_DB:-umami} in the HOST shell, which never sourced .env -- so renaming
# either service database in .env made the script provision the default names
# while the containers pointed at the renamed ones. Here the names arrive as
# container environment, which compose populates from .env correctly.
#
# The one behavioural difference from the old version: this connects to
# postgres over the compose network rather than running inside the postgres
# container itself.
#
# The analytics views used to be applied here too, as a fourth step. They
# moved to deploy/analytics-views.sh, a separate one-shot that runs AFTER
# umami starts, because analytics_traffic is defined over website_event -- a
# table umami creates on its own first boot -- and provision runs entirely
# before umami is allowed to start. Applying it here deadlocked every fresh
# install: provision waited on a table only umami could create, and umami
# waited on provision to exit. See analytics-views.sh for the full story.
set -euo pipefail

export PGHOST="${PGHOST:-postgres}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${POSTGRES_USER:?POSTGRES_USER must be set}"
export PGPASSWORD="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"

PRESSROOM_DB="${POSTGRES_DB:-pressroom}"

echo "==> Applying ${PRESSROOM_DB} migrations..."
# migrate.sh unmodified, so a deploy's migration step and a manual run can
# never disagree about what "applied" means. It resolves its migrations
# directory relative to its own location, which is why the Dockerfile places it
# at /app/migrate.sh beside /app/migrations.
DB_NAME="${PRESSROOM_DB}" DB_APP_USER="${PGUSER}" bash /app/migrate.sh

echo "==> Granting grafana_ro CONNECT on ${PRESSROOM_DB}..."
# Issued here rather than inside a migration or umami-views.sql because neither
# can portably learn its own database's name (current_database() needs dynamic
# SQL to use inside a GRANT), but the real names are known here. Double quotes,
# not ${db@Q}: a database name after GRANT ... ON DATABASE is an SQL
# IDENTIFIER, which takes double quotes, and @Q emits a shell-style literal.
psql -d "${PRESSROOM_DB}" -v ON_ERROR_STOP=1 -c \
    "GRANT CONNECT ON DATABASE \"${PRESSROOM_DB}\" TO grafana_ro;"

echo "==> Provisioning complete."
