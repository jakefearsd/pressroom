ALTER TABLE pressroom_user ADD COLUMN IF NOT EXISTS notify_replies boolean NOT NULL DEFAULT true;
ALTER TABLE pressroom_user ADD COLUMN IF NOT EXISTS keep_history boolean NOT NULL DEFAULT true;
