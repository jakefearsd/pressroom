-- Front door: a blog may name one of its own PUBLISHED pages as the page its
-- root shows instead of the post list.
-- weblog.front_page_id is that page's id (null: the root lists posts, exactly
-- as before, so no existing blog changes). The application keeps it truthful
-- at the write: unpublishing or removing the page clears it in the same save.
-- Deleting the page row nulls it as the backstop (ON DELETE SET NULL). Same-blog
-- membership is enforced in the application.
ALTER TABLE weblog ADD COLUMN IF NOT EXISTS front_page_id varchar(48) NULL REFERENCES pressroom_weblogpage(id) ON DELETE SET NULL;
