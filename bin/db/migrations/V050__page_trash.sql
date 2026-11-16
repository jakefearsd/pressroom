-- Migration: soft delete for pages.
--
-- trashed_at is nullable and carries no default: NULL means "not in the
-- trash", and the stamp exists only so the purge sweep and the trash list can
-- order and expire. The trash state itself lives in
-- pressroom_weblogpage.status as PubStatus.TRASHED, as V025 does for
-- entries: a query that names a status then excludes trash by construction.
-- A trashed row with no stamp is never purged.

ALTER TABLE pressroom_weblogpage
    ADD COLUMN IF NOT EXISTS trashed_at timestamp(3) with time zone;
