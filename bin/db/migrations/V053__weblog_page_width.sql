-- Per-blog page width.
-- weblog.page_width is STANDARD or WIDE: whether the blog's theme keeps its
-- reading widths or widens its page column and line length on large screens.
-- STANDARD is how every blog rendered before, so no existing blog changes.
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS page_width varchar(10) DEFAULT 'STANDARD' NOT NULL;
