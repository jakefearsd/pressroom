-- Strands: an ordered list of a blog's posts, carried by a blog page.
-- pressroom_weblogpage.is_strand marks a page as a strand. Nothing is one by
-- default, so no existing page changes.
-- weblogentry.strand_page_id names the strand a post is a part of (null: the
-- post is in none; a post is in zero or one) and strand_position orders it
-- within the strand (ascending; gaps are fine, numbering is computed).
-- Deleting the page detaches its posts: ON DELETE SET NULL is the backstop,
-- the application detaches them first. Same-blog membership is enforced in
-- the application.
ALTER TABLE pressroom_weblogpage ADD COLUMN IF NOT EXISTS is_strand boolean DEFAULT false NOT NULL;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS strand_page_id varchar(48) NULL REFERENCES pressroom_weblogpage(id) ON DELETE SET NULL;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS strand_position integer NULL;
CREATE INDEX IF NOT EXISTS we_strand_idx ON weblogentry(strand_page_id, strand_position);
