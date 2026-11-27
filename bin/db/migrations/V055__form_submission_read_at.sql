-- Unread state for the contact-form inbox. read_at is NULL until a member of the weblog opens
-- the inbox with the message on screen; it is shared, so one member handling an inquiry clears
-- it for everyone on the blog. Existing rows stay NULL (unread) -- the first visit clears them.
-- Idempotent.
ALTER TABLE pressroom_form_submission ADD COLUMN IF NOT EXISTS read_at timestamptz;
