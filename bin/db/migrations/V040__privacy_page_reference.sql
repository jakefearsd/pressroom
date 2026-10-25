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
-- Migration: site.privacy.page becomes a page reference. It used to be a slug
-- on the front-page blog; it is now either "slug" (a site page) or
-- "handle/slug" (a blog's page). An existing bare slug is qualified by the
-- front-page blog's handle so it keeps pointing at the same page. Values that
-- already contain "/", blank values, and installs with no front-page blog
-- (where a bare slug now simply means a site page) are left alone.
--
-- Prerequisites: V002__baseline_schema (the roller_properties table).
--
-- Idempotent: once qualified, the value contains "/" and no longer matches.

UPDATE roller_properties p
   SET value = trim(f.value) || '/' || trim(p.value)
  FROM roller_properties f
 WHERE p.name = 'site.privacy.page'
   AND f.name = 'site.frontpage.weblog.handle'
   AND trim(coalesce(p.value, '')) <> ''
   AND position('/' in p.value) = 0
   AND trim(coalesce(f.value, '')) <> '';
