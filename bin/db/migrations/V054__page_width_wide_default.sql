-- Wide becomes the page width of every blog.
-- New blogs default to WIDE, and every existing blog is moved to it.
-- lastmodified is stamped with the move because a blog's cached pages
-- expire only through it.
-- Idempotent: once every blog is WIDE the UPDATE matches nothing, so
-- applying this again touches no row. A blog set back to STANDARD
-- afterwards keeps that, because this migration is applied once.
ALTER TABLE weblog ALTER COLUMN page_width SET DEFAULT 'WIDE';
UPDATE weblog SET page_width = 'WIDE', lastmodified = now() WHERE page_width <> 'WIDE';
