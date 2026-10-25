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
-- Migration: the site space, a hidden system-owned weblog that owns the
-- site's own pages. site_space marks it; the partial unique index allows at
-- most one. See docs/superpowers/specs/2026-10-25-site-pages-front-door-design.md.
--
-- Prerequisites: V002__baseline_schema (the weblog table).
--
-- Idempotent: safe to run on a database that already has it.

ALTER TABLE weblog ADD COLUMN IF NOT EXISTS site_space boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX IF NOT EXISTS weblog_one_site_space ON weblog (site_space) WHERE site_space;
