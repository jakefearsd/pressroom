-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  The ASF licenses this file to You
-- under the Apache License, Version 2.0 (the "License"); you may not
-- use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- Analytics retention: delete Umami's per-visit rows older than the window
-- (default 730 days, two years), which is what the privacy policy promises
-- ("Analytics: two years, then deleted"). Runs against the umami DATABASE
-- once a day from the backup loop, after the nightly backup
-- (deploy/backup/umami-retention.sh), so the backup taken just before still
-- holds what this removes. Umami 3.3.0's schema; see docs/dev/umami-api.md.
--
-- psql variables (both optional):
--   retention_days  the window in days; 0 deletes nothing. Default 730, here
--                   and nowhere else (UMAMI_RETENTION_DAYS in .env sets it).
--   batch_size      rows per committed DELETE. Default 5000.
--
-- Umami's schema has NO foreign keys (Prisma relation mode), so nothing
-- cascades and every table is trimmed explicitly, children before parents:
--   event_data, revenue      children of website_event
--   website_event            pageviews, custom events, performance beacons
--   heatmap_event,
--   session_replay           per-visit recordings (empty unless enabled)
--   session                  only once it has no website_event left
--   session_data,
--   session_link             only once their session is gone
-- Never touched: website, "user", team, report and every other setting
-- table, session_replay_saved (an operator's bookmark, holding no visit
-- data), and _prisma_migrations.
--
-- Every DELETE carries `created_at < cutoff`, so a row inside the window is
-- never deleted: a session that has just been created and is waiting for its
-- first event survives, and so do a surviving session's data rows.
--
-- Idempotent: a re-run deletes nothing new. It logs a count per table and
-- never a row. Must NOT run inside a transaction (psql --single-transaction):
-- the block commits after every batch, so a large first run holds no long
-- lock and keeps what it has done if it is stopped.

\if :{?retention_days}
\else
\set retention_days 730
\endif
\if :{?batch_size}
\else
\set batch_size 5000
\endif

-- The backup loop runs nothing else until this returns, so the run is
-- bounded. statement_timeout covers the whole DO block below, one top-level
-- statement: the COMMIT after each batch does not reset it, so the whole run
-- is capped at 15 min. A big first run may log a timeout failure; the batches
-- it committed stay deleted, and it finishes over the following nights.
-- lock_timeout fails the run if one batch waits a minute on a lock.
SET lock_timeout = '1min';
SET statement_timeout = '15min';

-- psql does not interpolate inside the dollar-quoted block, so the values
-- travel as session settings. Quoted, so a bad value fails the integer cast.
SET pressroom.umami_retention_days = :'retention_days';
SET pressroom.umami_retention_batch = :'batch_size';

DO $$
DECLARE
    days    integer := current_setting('pressroom.umami_retention_days')::integer;
    batch   integer := current_setting('pressroom.umami_retention_batch')::integer;
    tables  text[] := ARRAY['event_data', 'revenue', 'website_event', 'heatmap_event',
                            'session_replay', 'session', 'session_data', 'session_link'];
    extra   text[] := ARRAY['', '', '', '', '',
                            'AND NOT EXISTS (SELECT 1 FROM website_event e WHERE e.session_id = t.session_id)',
                            'AND NOT EXISTS (SELECT 1 FROM session s WHERE s.session_id = t.session_id)',
                            'AND NOT EXISTS (SELECT 1 FROM session s WHERE s.session_id = t.session_id)'];
    cutoff  timestamptz;
    n       bigint;
    total   bigint;
BEGIN
    IF days < 0 OR batch < 1 THEN
        RAISE EXCEPTION 'umami-retention: retention_days must be >= 0 and batch_size >= 1';
    END IF;
    IF days = 0 THEN
        RAISE NOTICE 'umami-retention: off (retention_days = 0); nothing deleted';
        RETURN;
    END IF;
    IF to_regclass('public.website_event') IS NULL THEN
        RAISE EXCEPTION 'umami-retention: Umami has not created its tables yet';
    END IF;

    cutoff := now() - make_interval(days => days);
    RAISE NOTICE 'umami-retention: deleting analytics older than % days (before %)', days, cutoff;

    FOR i IN 1 .. array_length(tables, 1) LOOP
        -- A table a later Umami drops is skipped, not an error.
        IF to_regclass('public.' || quote_ident(tables[i])) IS NULL THEN
            RAISE NOTICE 'umami-retention: %: absent, skipped', tables[i];
            CONTINUE;
        END IF;
        total := 0;
        LOOP
            EXECUTE format(
                'DELETE FROM %I WHERE ctid = ANY (ARRAY('
                    || 'SELECT t.ctid FROM %I t WHERE t.created_at < $1 %s LIMIT %s))',
                tables[i], tables[i], extra[i], batch)
                USING cutoff;
            GET DIAGNOSTICS n = ROW_COUNT;
            total := total + n;
            COMMIT;
            EXIT WHEN n < batch;
        END LOOP;
        RAISE NOTICE 'umami-retention: %: % row(s) deleted', tables[i], total;
    END LOOP;
END $$;
