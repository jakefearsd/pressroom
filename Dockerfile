# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  The ASF licenses this file to You
# under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.  For additional information regarding
# copyright in this work, please see the NOTICE file in the top level
# directory of this distribution.

# Production image for Roller, built from THIS tree (never from an upstream
# release tag or a fresh git clone). Two stages:
#
#   1. builder -- Maven reactor build of the app module only, producing the
#      executable WAR (spring-boot-maven-plugin repackages it; see
#      app/pom.xml). Layer caching is intentionally NOT optimized here: this
#      reactor (root pom + app, with bin/db/migrations pulled in as a
#      resource at generate-resources time) doesn't play well with
#      `mvn dependency:go-offline` as a separate cached layer, so every build
#      re-resolves dependencies. Acceptable for now; revisit if build time
#      becomes a problem.
#   2. runtime -- just a JRE, the WAR, baked-in themes, and the migrations
#      SQL for reference/exec-based use by deploy tooling (Stage 1C Task 3).
#
# Run: java -jar pressroom.war (Boot's embedded Tomcat serves the app at /pressroom
# on 8080, and a second embedded connector serves /actuator/health on the
# management port 8090 -- see app/src/main/resources/application.properties).
# Runtime config is supplied entirely by PRESSROOM_* environment variables, which
# WebloggerConfig overlays on top of its built-in defaults; at minimum set
# PRESSROOM_DATABASE_CONFIGURATIONTYPE, PRESSROOM_DATABASE_JDBC_*, and the
# PRESSROOM_MAIL_* keys. See deploy/.env.example for the full set.

# ---- Stage 1: build -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS builder

WORKDIR /build

# Minimal reactor context: root pom (declares the app module), the app
# module itself, and bin/db/migrations (app/pom.xml's copy-db-migrations
# execution reads ${basedir}/../bin/db/migrations at generate-resources time
# to bake the SQL onto the WAR's classpath at /dbmigrations).
COPY pom.xml ./
COPY app app
COPY bin/db/migrations bin/db/migrations

# This step needs outbound network access beyond the obvious Maven/apt
# fetches: app/pom.xml's generate-resources phase runs frontend-maven-plugin,
# which downloads Node v24.22.1 from nodejs.org and then the editor bundle's
# npm packages from registry.npmjs.org (see CLAUDE.md's "Frontend build" and
# docker_deployment.md's "Test a release locally before deploying it"). This
# stage starts clean every time -- no cached app/frontend/node/ or
# node_modules/ carries over -- so an air-gapped build fails here.
RUN mvn -ntp -pl app -DskipTests package

# ---- Stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f AS runtime

# curl is not in the base JRE image; installed for container healthchecks
# against the management port (docker exec ... curl 8090/actuator/health --
# used here and by docker-compose.prod.yml's app healthcheck in Task 2).
#
# webp (Debian/Ubuntu's name for libwebp-tools) provides the cwebp binary
# that CwebpEncoder shells out to for WebP renditions of uploaded images.
# Roller feature-detects it at first use and falls back to a JPEG/PNG-only
# rendition ladder when absent, so this is an enhancement, not a hard
# dependency -- but production should always have it.
#
# postgresql-client provides psql/createdb for /app/provision.sh and pg_dump
# for the nightly backup loop -- this one image is the app, the provisioner and
# the backup runner, because those are exactly the roles needing the WAR or a
# PostgreSQL client. The version assertion is load-bearing: pg_dump refuses a
# server newer than itself, so a base-image change that dropped the client
# below the server's major would break backups silently at 03:00 rather than
# at build time. It has already moved once without a signal: the base image
# became Ubuntu 26.04, taking the client from 16 to 18.
RUN apt-get update && apt-get install -y --no-install-recommends curl webp postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# DECLARED rather than buried in a regular expression's bounds, so
# PostgresMajorPinTest can check it against the major the compose files
# actually run. Raise this and the server image together.
ARG PG_CLIENT_MIN_MAJOR=18

# Separate RUN on purpose. Chaining this onto the install with `&& ... || exit`
# makes a failed apt-get report the pg_dump version message instead of its own,
# which sends you looking in the wrong place.
RUN major="$(pg_dump --version | sed -E 's/.*PostgreSQL\) ([0-9]+).*/\1/')"; \
    if [ "${major:-0}" -lt "${PG_CLIENT_MIN_MAJOR}" ]; then \
        echo "pg_dump is ${major:-unknown}: the stack runs PostgreSQL ${PG_CLIENT_MIN_MAJOR} and pg_dump refuses a server newer than itself" >&2; \
        exit 1; \
    fi

RUN groupadd --system --gid 999 pressroom && useradd --system --uid 999 --gid 999 --home-dir /app --shell /usr/sbin/nologin pressroom

WORKDIR /app

COPY --from=builder /build/app/target/pressroom.war /app/pressroom.war
COPY --from=builder /build/app/src/main/webapp/themes /app/themes
COPY --from=builder /build/bin/db/migrations /app/migrations

# migrate.sh resolves its migrations directory as $(dirname $0)/migrations, so
# it must sit beside /app/migrations. Copied unmodified from the build context:
# the deploy path and a manual ./bin/db/migrate.sh run must never disagree
# about what "applied" means.
COPY bin/db/migrate.sh /app/migrate.sh
COPY deploy/provision.sh /app/provision.sh
COPY deploy/admin-account.sh /app/admin-account.sh
COPY deploy/analytics-views.sh /app/analytics-views.sh
COPY deploy/analytics/umami-views.sql /app/umami-views.sql
COPY deploy/backup/backup.sh /app/backup/backup.sh
COPY deploy/backup/loop.sh /app/backup/loop.sh
COPY bin/pressroom-api /app/pressroom-api

# Runtime data: mediafiles, search index, uploads, all under /data per the
# runtime contract. There is no /config any more -- configuration arrives as
# PRESSROOM_* environment variables (see WebloggerConfig.applyEnvironmentOverrides).
RUN chmod 755 /app/migrate.sh /app/provision.sh /app/admin-account.sh /app/analytics-views.sh /app/backup/backup.sh /app/backup/loop.sh \
    && chmod +x /app/pressroom-api \
    && mkdir -p /data/mediafiles /data/search-index /data/uploads \
    && chown -R pressroom:pressroom /app /data

VOLUME ["/data"]

USER pressroom

EXPOSE 8080 8090

ENV JAVA_OPTS=""

# Shell form so JAVA_OPTS expands. No -Dpressroom.custom.config: configuration
# comes from PRESSROOM_* environment variables, which compose supplies from .env.
# WebloggerConfig still honours the flag if an operator sets it in JAVA_OPTS.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/pressroom.war"]
