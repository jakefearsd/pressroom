-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  The ASF licenses this file to You
-- under the Apache License, Version 2.0 (the "License"); you may not
-- use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- Migration: comments for signed-in readers (docs/superpowers/specs/2026-10-12-reader-comments-design.md).
-- A fresh table, deliberately not named roller_comment: V022 drops that name. Idempotent.

CREATE TABLE IF NOT EXISTS roller_reader_comment (
    id              varchar(48)  NOT NULL PRIMARY KEY,
    entry_id        varchar(48)  NOT NULL REFERENCES weblogentry(id) ON DELETE CASCADE,
    author_id       varchar(48)  NOT NULL REFERENCES roller_user(id) ON DELETE CASCADE,
    reply_to_id     varchar(48)  REFERENCES roller_reader_comment(id) ON DELETE CASCADE,
    body            text         NOT NULL,
    status          varchar(16)  NOT NULL,
    author_is_owner boolean      DEFAULT false NOT NULL,
    created         timestamp(3) with time zone NOT NULL,
    status_changed  timestamp(3) with time zone NOT NULL
);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'rrc_status_ck') THEN
        ALTER TABLE roller_reader_comment ADD CONSTRAINT rrc_status_ck
            CHECK (status IN ('PENDING', 'PUBLISHED', 'HIDDEN'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS rrc_entry_status_created_idx ON roller_reader_comment (entry_id, status, created);
CREATE INDEX IF NOT EXISTS rrc_author_idx ON roller_reader_comment (author_id);
-- Unique: a comment has at most one reply (the owner's), even under a double submit.
CREATE UNIQUE INDEX IF NOT EXISTS rrc_reply_to_uq ON roller_reader_comment (reply_to_id) WHERE reply_to_id IS NOT NULL;

ALTER TABLE weblog      ADD COLUMN IF NOT EXISTS comments_enabled boolean DEFAULT false NOT NULL;
ALTER TABLE weblog      ADD COLUMN IF NOT EXISTS comment_notify   boolean DEFAULT true  NOT NULL;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS comments_closed  boolean DEFAULT false NOT NULL;
