-- Migration: drop the hand-entered per-blog analytics settings.
--
-- Pressroom registers every weblog with Umami itself (analytics_site, V043),
-- so the analytics site ID and share URL are no longer read or written. The
-- analytics_weblog_sites view stopped depending on weblog.analytics_site_id in
-- V043, so the columns can go. Idempotent.

ALTER TABLE weblog
    DROP COLUMN IF EXISTS analytics_site_id,
    DROP COLUMN IF EXISTS analytics_share_url;
