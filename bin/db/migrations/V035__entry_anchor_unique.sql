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
-- Migration: an entry's anchor (its permalink slug) is unique within its
-- weblog, and the database says so.
--
-- createAnchor checked "does an entry with this anchor exist?" and the
-- INSERT followed at commit, with nothing enforcing the answer, so
-- concurrent creates of one title (a retried API call, a double-submitted
-- form) committed several entries with one anchor. The permalink lookup
-- (getSingleResult) then threw NonUniqueResultException for that anchor, so
-- no duplicate was reachable at its permalink. This migration
--   1. resolves existing duplicates. The entry that keeps the anchor is the
--      one the lookup's own query puts first,
--      WeblogEntry.getByWebsite&AnchorOrderByPubTimeDesc: ORDER BY pubtime
--      DESC, which in PostgreSQL puts a NULL pubtime first; equal pubtimes
--      fall back to the lower id. Every other duplicate takes the first free
--      <anchor>N (N = 1, 2, ...), createAnchor's own suffix scheme, cut to
--      fit varchar(255). No redirect is added: the old anchor serves the
--      entry that kept it, and a redirect rule is consulted only on a 404.
--   2. adds the unique index we_website_anchor_uq (websiteid, anchor), which
--      also serves every lookup by anchor (it was a scan of the weblog's
--      entries).
--
-- Idempotent: a second run finds no duplicate, and the index exists.

DO $$
DECLARE
    dup       record;
    n         integer;
    candidate text;
BEGIN
    FOR dup IN
        SELECT id, websiteid, anchor
          FROM (SELECT id, websiteid, anchor,
                       row_number() OVER (PARTITION BY websiteid, anchor
                                          ORDER BY pubtime DESC, id) AS rank
                  FROM weblogentry) ranked
         WHERE rank > 1
         ORDER BY websiteid, anchor, rank
    LOOP
        n := 1;
        LOOP
            candidate := left(dup.anchor, 255 - length(n::text)) || n;
            EXIT WHEN NOT EXISTS (
                SELECT 1 FROM weblogentry
                 WHERE websiteid = dup.websiteid AND anchor = candidate);
            n := n + 1;
        END LOOP;
        UPDATE weblogentry SET anchor = candidate WHERE id = dup.id;
    END LOOP;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS we_website_anchor_uq ON weblogentry (websiteid, anchor);
