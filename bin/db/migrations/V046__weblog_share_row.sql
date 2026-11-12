-- Per-blog switch for the share row under a post (the showAfterEntry macro).
-- On by default, so every existing blog keeps its row; turning it off removes
-- the row and its style and script on that blog only. Nearby and Keep reading
-- do not depend on it.
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS share_row_enabled boolean DEFAULT true NOT NULL;
