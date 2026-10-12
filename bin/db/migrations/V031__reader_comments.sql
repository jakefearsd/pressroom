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
-- A fresh table: the W1 comment subsystem (dropped in V022) is not restored. Idempotent.

CREATE TABLE IF NOT EXISTS roller_comment (
    id              varchar(48)  NOT NULL PRIMARY KEY,
    entry_id        varchar(48)  NOT NULL REFERENCES weblogentry(id) ON DELETE CASCADE,
    author_id       varchar(48)  NOT NULL REFERENCES roller_user(id) ON DELETE CASCADE,
    reply_to_id     varchar(48)  REFERENCES roller_comment(id) ON DELETE CASCADE,
    body            text         NOT NULL,
    status          varchar(16)  NOT NULL,
    author_is_owner boolean      DEFAULT false NOT NULL,
    created         timestamp(3) with time zone NOT NULL,
    status_changed  timestamp(3) with time zone NOT NULL
);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'rc_status_ck') THEN
        ALTER TABLE roller_comment ADD CONSTRAINT rc_status_ck
            CHECK (status IN ('PENDING', 'PUBLISHED', 'HIDDEN'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS rc_entry_status_created_idx ON roller_comment (entry_id, status, created);
CREATE INDEX IF NOT EXISTS rc_author_idx ON roller_comment (author_id);
CREATE INDEX IF NOT EXISTS rc_reply_to_idx ON roller_comment (reply_to_id);

ALTER TABLE weblog      ADD COLUMN IF NOT EXISTS comments_enabled boolean DEFAULT false NOT NULL;
ALTER TABLE weblog      ADD COLUMN IF NOT EXISTS comment_notify   boolean DEFAULT true  NOT NULL;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS comments_closed  boolean DEFAULT false NOT NULL;
