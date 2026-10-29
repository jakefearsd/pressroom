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

-- Seed data for the browser integration tests.
--
-- Runs after bin/db/migrate.sh and before Tomcat starts, because Roller reads
-- much of this at bootstrap and caches it in-process. Creating it afterwards
-- would leave the running app with a stale view.
--
-- Integration tests never delete: each test creates its own uniquely-named
-- it_<test>_<n> entities on top of this baseline. The database is disposable,
-- recreated from scratch on every run.
--
-- Idempotent so a re-run against a surviving container is harmless.

-- The password hash is bcrypt with Spring Security's DelegatingPasswordEncoder
-- {id} prefix, matching passwds.encryption.algorithm=bcrypt in pressroom.properties.
-- Plaintext is 'it-admin-password'. Deliberately a real bcrypt hash rather than
-- {noop}, so the tests exercise the same login path production uses.
INSERT INTO pressroom_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled)
VALUES ('it-admin-0000-0000-0000-000000000001',
        'it_admin',
        '{bcrypt}$2a$10$hZG/myoZ4z70o1csg5pw7eGtSiViWPsKRz05QD0Qqz41vE5dFFrfO',
        'IT Admin', 'Integration Test Admin', 'it-admin@example.invalid',
        NOW(), 'en_US', 'UTC', true)
ON CONFLICT (username) DO NOTHING;

-- Roller's authorities come from userrole; 'admin' also implies editor access.
INSERT INTO userrole (id, rolename, username)
VALUES ('it-role-0000-0000-0000-000000000001', 'admin', 'it_admin')
ON CONFLICT (id) DO NOTHING;

INSERT INTO userrole (id, rolename, username)
VALUES ('it-role-0000-0000-0000-000000000002', 'editor', 'it_admin')
ON CONFLICT (id) DO NOTHING;

-- An agent account (V033), so the route sweep can open an agent's page. An
-- agent has no password and never signs in (spec 2026-10-13).
INSERT INTO pressroom_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled, kind, notes)
VALUES ('it-agent-0000-0000-0000-000000000001',
        'it_agent', NULL, 'IT Agent', 'IT Agent', 'it_agent@agents.invalid',
        NOW(), 'en_US', 'UTC', true, 'AGENT', 'Seeded for the route sweep.')
ON CONFLICT (username) DO NOTHING;

INSERT INTO userrole (id, rolename, username)
VALUES ('it-role-0000-0000-0000-000000000003', 'editor', 'it_agent')
ON CONFLICT (id) DO NOTHING;

-- One weblog: the ~20 /pressroom-ui/authoring/ routes all require a resolvable
-- weblog= request parameter (see PressroomHandlerInterceptor), so without this the
-- reachability sweep could only reach the handful of global pages.
INSERT INTO weblog (id, name, handle, tagline, creator,
                    emailaddress, editortheme,
                    locale, timezone, visible, isactive, datecreated,
                    displaycnt)
VALUES ('it-weblog-0000-0000-0000-00000000001',
        'IT Weblog', 'it_weblog', 'Integration test weblog', 'it_admin',
        'it-admin@example.invalid', 'journal',
        'en_US', 'UTC', true, true, NOW(),
        15)
ON CONFLICT (handle) DO NOTHING;

-- Weblog ADMIN permission: the authoring routes check WeblogPermission, and
-- several (templates, theme, config, members) require ADMIN specifically.
--
-- objectid holds the weblog HANDLE, not its id: WeblogPermission sets
-- objectId = weblog.getHandle() and resolves it with getWeblogByHandle().
-- Seeding the uuid here makes the main menu throw
-- "WebloggerException: Invalid handle".
INSERT INTO pressroom_permission (id, username, actions, objectid, objecttype,
                               datecreated)
VALUES ('it-perm-0000-0000-0000-000000000001',
        'it_admin', 'admin,post,edit_draft',
        'it_weblog', 'Weblog',
        NOW())
ON CONFLICT (id) DO NOTHING;

-- Entries require a category, and the weblog needs at least one for the
-- entry-add page to render its category selector.
INSERT INTO weblogcategory (id, name, description, websiteid, position)
VALUES ('it-cat-0000-0000-0000-000000000001',
        'General', 'Integration test category',
        'it-weblog-0000-0000-0000-00000000001', 0)
ON CONFLICT (id) DO NOTHING;

-- A published entry so anonymous-surface tests have real content to render.
INSERT INTO weblogentry (id, anchor, creator, title, text, pubtime, updatetime,
                         websiteid, categoryid, publishentry, link,
                         righttoleft, pinnedtomain,
                         locale, status, summary, search_description)
VALUES ('it-entry-0000-0000-0000-000000000001', 'it-seeded-entry', 'it_admin',
        'IT Seeded Entry', 'Seeded entry body for public rendering checks.',
        now() - interval '1 hour', now() - interval '1 hour',
        'it-weblog-0000-0000-0000-00000000001',
        'it-cat-0000-0000-0000-000000000001',
        true, NULL, false, false,
        'en_US', 'PUBLISHED', NULL, NULL)
ON CONFLICT (id) DO NOTHING;
