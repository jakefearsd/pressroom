-- Migration: a kept flag on entry revisions.
--
-- A revision an author marks as kept is exempt from every retention rule
-- (per-item count, age, the site-wide ceiling) until it is released or its
-- entry is deleted for good. The page revision table (V051) was created with
-- the column. Existing rows default to false: installing this keeps and
-- removes nothing.

ALTER TABLE weblogentry_revision
    ADD COLUMN IF NOT EXISTS kept boolean NOT NULL DEFAULT false;
