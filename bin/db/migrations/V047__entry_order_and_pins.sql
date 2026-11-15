-- Per-blog listing order and per-post pins.
-- weblog.entry_order is NEWEST or OLDEST: which end of the archive the front
-- page and archive pages start from. NEWEST is how every blog listed before,
-- so no existing blog changes.
-- weblogentry.pinned marks a post the blog shows above its regular listing
-- (at most three per blog, enforced in the application). Nothing is pinned by
-- default. It is separate from pinnedtomain, the site-wide pin.
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS entry_order varchar(10) DEFAULT 'NEWEST' NOT NULL;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS pinned boolean DEFAULT false NOT NULL;
