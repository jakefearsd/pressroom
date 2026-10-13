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
--
-- Migration: a content hash on media files, so an image import whose bytes
-- are already in the target directory returns that file instead of a copy.
--
-- content_hash is the SHA-256 (lower-case hex) of the bytes the file was
-- created with. It is NULL for files from before this migration, and is
-- cleared when a file's content is replaced or cropped: such files never
-- match. Matching by URL was rejected -- a stored URL would keep a signed
-- URL's credentials, and what a URL serves can change. See
-- docs/superpowers/specs/2026-10-13-publishing-agent-api-refinements-design.md.
--
-- Idempotent: safe to run on a database that already has it.

ALTER TABLE roller_mediafile ADD COLUMN IF NOT EXISTS content_hash varchar(64);

CREATE INDEX IF NOT EXISTS mf_dir_content_hash_idx
    ON roller_mediafile (directoryid, content_hash)
    WHERE content_hash IS NOT NULL;
