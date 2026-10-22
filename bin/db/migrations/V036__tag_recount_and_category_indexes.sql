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
-- Migration: two composite indexes for hot queries found by a 2026-10-22
-- profiling run (10 weblogs x ~4.4k entries, 16-word tag vocabulary).
-- EXPLAIN (ANALYZE) timings are from a copy of that dataset.
--
-- 1. wet_weblog_name_entry_idx serves TagAggregateStore's per-weblog
--    recount, run at commit for every (weblog, tag) a save touches. It
--    counts the weblog's published entries carrying the tag; with the tag
--    rows filtered on their own websiteid, this index hands it just that
--    weblog's rows for the tag (index-only), joined to weblogentry by key.
--    Before, it read every row for the tag site-wide and every entry of the
--    weblog and hash-joined them: ~4.8-6.9 ms a call, now ~1.1 ms.
--
-- 2. we_weblog_cat_status_pub_idx serves the category pager
--    (websiteid, categoryid, status equal, pubtime <= now, ORDER BY pubtime
--    DESC LIMIT n): the first page is now read in index order and stops
--    after n rows (~1.4-3.0 ms -> ~0.08 ms), where it had ANDed the weblog
--    and category bitmaps and sorted the whole category.
--
-- Single-column websiteid indexes (we_weblogid_idx, wet_weblogid_idx,
-- weta_weblogid_idx) are each a prefix of a composite index, but stay: the
-- planner prefers the smaller index for weblog-wide scans (a weblog's
-- entry count: 0.32 ms with we_weblogid_idx, 0.48 ms without).
--
-- Idempotent: IF NOT EXISTS. Not CONCURRENTLY: migrations run in a
-- transaction, and both tables build in well under a second at this size.

CREATE INDEX IF NOT EXISTS wet_weblog_name_entry_idx
    ON roller_weblogentrytag (websiteid, name, entryid);

CREATE INDEX IF NOT EXISTS we_weblog_cat_status_pub_idx
    ON weblogentry (websiteid, categoryid, status, pubtime);
