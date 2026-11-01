-- Per-blog switch for the "more from the site" block on the blog's own pages.
-- On by default; turning it off hides the block on that blog only.
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS show_more_from_site boolean DEFAULT true NOT NULL;
