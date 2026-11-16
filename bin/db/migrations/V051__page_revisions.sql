-- Migration: page revisions.
--
-- One row per content-changing save of a page, holding the title and content
-- the save replaced: full snapshots, as weblogentry_revision (V010) and for
-- the reason given there. Settings (slug, status, nav, SEO) are not prose and
-- are not versioned, so restoring a revision can never republish a page or
-- move its URL.
--
-- created is microseconds, as V010: ordering decides which revision a prune
-- drops. kept marks a revision an author has protected from pruning; nothing
-- sets it before the retention work that follows this migration.

CREATE TABLE IF NOT EXISTS pressroom_weblogpage_revision (
    id           varchar(48) NOT NULL PRIMARY KEY,
    pageid       varchar(48) NOT NULL
                 CONSTRAINT wpr_pageid_fk REFERENCES pressroom_weblogpage(id) ON DELETE CASCADE,
    created      timestamp(6) with time zone NOT NULL,
    creator      varchar(255),
    title        varchar(255),
    content      text,
    note         varchar(255),
    kept         boolean NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS wpr_page_created_idx
    ON pressroom_weblogpage_revision(pageid, created DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON pressroom_weblogpage_revision TO :app_user;
