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
--
-- Migration: repair the tag aggregates and make the database guarantee one
-- site-wide row per tag.
--
-- roller_weblogentrytagagg holds, per (weblog, tag) and per tag site-wide
-- (websiteid IS NULL), the number of PUBLISHED entries carrying the tag. It
-- was maintained by an unlocked read-modify-write, so concurrent saves lost
-- increments, and could insert a second site-wide row because
-- UNIQUE (websiteid, name) treats NULLs as distinct. TagAggregateStore now
-- writes it with one INSERT ... ON CONFLICT per row; this migration
--   1. merges duplicate site-wide rows (keeping the most recently used),
--   2. rebuilds weta_weblog_tag_uq as UNIQUE NULLS NOT DISTINCT, which then
--      covers both kinds of row and is the upsert's single conflict target
--      (PostgreSQL 15+; Roller runs on 18 only),
--   3. recomputes every total from the entries themselves: rows that should
--      not exist are deleted, wrong totals corrected, missing rows added.
-- "Count" here is exactly what the code maintains: distinct entries with
-- status PUBLISHED carrying the tag, attributed to the entry's weblog.
--
-- Idempotent: a second run finds nothing to merge, the constraint already
-- in its new form, and every total already true.

DELETE FROM roller_weblogentrytagagg older
 USING roller_weblogentrytagagg newer
 WHERE older.websiteid IS NULL
   AND newer.websiteid IS NULL
   AND older.name = newer.name
   AND (older.lastused, older.id) < (newer.lastused, newer.id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_index i
          JOIN pg_class c ON c.oid = i.indexrelid
         WHERE c.relname = 'weta_weblog_tag_uq'
           AND i.indnullsnotdistinct
    ) THEN
        ALTER TABLE roller_weblogentrytagagg DROP CONSTRAINT IF EXISTS weta_weblog_tag_uq;
        ALTER TABLE roller_weblogentrytagagg
            ADD CONSTRAINT weta_weblog_tag_uq UNIQUE NULLS NOT DISTINCT (websiteid, name);
    END IF;
END
$$;

WITH truth AS (
    SELECT e.websiteid, t.name, count(DISTINCT t.entryid) AS total
      FROM roller_weblogentrytag t
      JOIN weblogentry e ON e.id = t.entryid
     WHERE e.status = 'PUBLISHED'
     GROUP BY GROUPING SETS ((e.websiteid, t.name), (t.name))
)
DELETE FROM roller_weblogentrytagagg a
 WHERE NOT EXISTS (
        SELECT 1 FROM truth
         WHERE truth.websiteid IS NOT DISTINCT FROM a.websiteid
           AND truth.name = a.name);

WITH truth AS (
    SELECT e.websiteid, t.name, count(DISTINCT t.entryid) AS total
      FROM roller_weblogentrytag t
      JOIN weblogentry e ON e.id = t.entryid
     WHERE e.status = 'PUBLISHED'
     GROUP BY GROUPING SETS ((e.websiteid, t.name), (t.name))
)
UPDATE roller_weblogentrytagagg a
   SET total = truth.total
  FROM truth
 WHERE truth.websiteid IS NOT DISTINCT FROM a.websiteid
   AND truth.name = a.name
   AND a.total <> truth.total;

WITH truth AS (
    SELECT e.websiteid, t.name, count(DISTINCT t.entryid) AS total, max(t.time) AS lastused
      FROM roller_weblogentrytag t
      JOIN weblogentry e ON e.id = t.entryid
     WHERE e.status = 'PUBLISHED'
     GROUP BY GROUPING SETS ((e.websiteid, t.name), (t.name))
)
INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused)
SELECT gen_random_uuid()::text, truth.websiteid, truth.name, truth.total, truth.lastused
  FROM truth
    ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO NOTHING;
