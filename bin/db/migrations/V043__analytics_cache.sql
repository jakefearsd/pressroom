-- Migration: local cache of Umami registration and traffic.
--
-- Pressroom registers every weblog with Umami itself and batch-reads traffic
-- into these tables, so pages render from local rows and never wait on Umami.
-- All four tables hang off weblog and cascade with it.
--
-- website_id is varchar(64), the type of weblog.analytics_site_id, so that
-- the analytics_weblog_sites view below keeps its column types: CREATE OR
-- REPLACE VIEW refuses any change of name, order or type. The old
-- weblog.analytics_site_id column is no longer read; a later migration drops it.

CREATE TABLE IF NOT EXISTS analytics_site (
    weblog_id     varchar(48) PRIMARY KEY REFERENCES weblog (id) ON DELETE CASCADE,
    website_id    varchar(64) NOT NULL,
    synced_name   varchar(255),
    synced_domain varchar(255),
    registered_at timestamptz,
    last_attempt  timestamptz,
    last_error    text
);

CREATE TABLE IF NOT EXISTS analytics_summary (
    weblog_id       varchar(48) NOT NULL REFERENCES weblog (id) ON DELETE CASCADE,
    period          varchar(8) NOT NULL,
    as_of           timestamptz NOT NULL,
    visitors        bigint NOT NULL,
    visits          bigint NOT NULL,
    views           bigint NOT NULL,
    bounces         bigint NOT NULL,
    total_time      bigint NOT NULL,
    prev_visitors   bigint NOT NULL,
    prev_visits     bigint NOT NULL,
    prev_views      bigint NOT NULL,
    prev_bounces    bigint NOT NULL,
    prev_total_time bigint NOT NULL,
    PRIMARY KEY (weblog_id, period)
);

CREATE TABLE IF NOT EXISTS analytics_path_views (
    weblog_id varchar(48) NOT NULL REFERENCES weblog (id) ON DELETE CASCADE,
    period    varchar(8) NOT NULL,
    path      varchar(1024) NOT NULL,
    entry_id  varchar(48),
    views     bigint NOT NULL,
    visitors  bigint NOT NULL,
    PRIMARY KEY (weblog_id, period, path)
);

-- payload is JSON: a series, or a list of key/count pairs.
CREATE TABLE IF NOT EXISTS analytics_breakdown (
    weblog_id varchar(48) NOT NULL REFERENCES weblog (id) ON DELETE CASCADE,
    period    varchar(8) NOT NULL,
    kind      varchar(16) NOT NULL,
    payload   text NOT NULL,
    PRIMARY KEY (weblog_id, period, kind)
);

-- Grafana's handle <-> website id <-> hostname join, now fed by the cache.
CREATE OR REPLACE VIEW analytics_weblog_sites AS
SELECT w.handle        AS weblog_handle,
       s.website_id    AS website_id,
       w.custom_domain AS custom_domain
FROM weblog w
LEFT JOIN analytics_site s ON s.weblog_id = w.id
WHERE s.website_id IS NOT NULL
   OR w.custom_domain IS NOT NULL;

DO $$ BEGIN
    CREATE ROLE grafana_ro NOLOGIN;
EXCEPTION WHEN duplicate_object THEN
    NULL;
END $$;

GRANT SELECT ON analytics_weblog_sites TO grafana_ro;
