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
-- The Umami half of the analytics contract (see
-- bin/db/migrations/V017__analytics_contract.sql for the pressroom half).
-- This file lives in the umami DATABASE, not the pressroom migration chain
-- -- PostgreSQL has no cross-database queries, so V017 could not create a
-- view over website_event even though it owns everything else in the
-- contract. Applied by the one-shot `analytics-views` compose service
-- (deploy/analytics-views.sh, baked into the app image), which runs AFTER
-- umami has started and grafana_ro already exists (both created by
-- provision.sh) -- website_event, the table this view selects from, does
-- not exist until Umami's own first-boot migrations create it, so this
-- cannot run any earlier. CREATE OR REPLACE + GRANT are idempotent, so
-- re-running this on every deploy is a no-op, same contract migrate.sh
-- gives the pressroom chain. Versioned here (not hand-applied) so replacing
-- Umami later means rewriting this one file, not any dashboard.
--
-- Column shapes follow Umami v2's postgresql schema (website_event, with
-- event_type = 1 meaning pageview and session_id identifying a visitor). If
-- an Umami upgrade changes them, this view is the only thing to fix.
-- UmamiViewScriptTest (app/src/test/.../business/startup) mirrors this
-- shape in a scratch table and applies this exact file, so the repo's only
-- knowledge of Umami's schema lives here plus that test's minimal copy.
--
-- Grafana joins this view's website_id against pressroom's
-- analytics_weblog_sites view (V017) to resolve a weblog handle -- a
-- cross-database join Grafana performs itself, since Postgres cannot.
--
-- GRANT CONNECT ON DATABASE is deliberately NOT in this file. A migration
-- (or a plain script like this one) cannot portably state its own
-- database's name -- current_database() would need dynamic SQL to use
-- inside a GRANT -- so provision.sh issues
-- `GRANT CONNECT ON DATABASE ... TO grafana_ro` for BOTH pressroom and
-- umami already, earlier in the same `docker compose up -d`, where the real
-- database names are already known as env vars (see provision.sh's grant
-- step and V017's header for the same decision, restated on that side of
-- the split). This file's grants stay schema/table-level, which is all it
-- can state without knowing its own database's name.

CREATE OR REPLACE VIEW analytics_traffic AS
SELECT we.website_id                                   AS website_id,
       we.url_path                                     AS path,
       CASE WHEN we.url_path LIKE '%/entry/%'
            THEN split_part(we.url_path, '/entry/', 2)
            ELSE NULL END                              AS entry_anchor,
       CAST(date_trunc('day', we.created_at) AS date)  AS day,
       count(DISTINCT we.session_id)                   AS sessions,
       count(*) FILTER (WHERE we.event_type = 1)       AS views
FROM website_event we
GROUP BY we.website_id, we.url_path,
         CASE WHEN we.url_path LIKE '%/entry/%'
              THEN split_part(we.url_path, '/entry/', 2)
              ELSE NULL END,
         CAST(date_trunc('day', we.created_at) AS date);

GRANT USAGE ON SCHEMA public TO grafana_ro;
GRANT SELECT ON analytics_traffic TO grafana_ro;
