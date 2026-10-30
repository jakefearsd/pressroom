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

-- Seed data for the LOCAL DEVELOPMENT database.
--
-- Applied by `./pressroom db|dev|reset` via:
--     psql -v devpw="$PRESSROOM_DEV_ADMIN_PASSWORD" -f bin/db/seed-dev-data.sql
--
-- NOT a migration, and deliberately not under bin/db/migrations/: migrate.sh,
-- DatabaseInstaller and the test harness apply only that directory, so this
-- file can never reach production.
--
-- The password is hashed HERE, inside Postgres, via pgcrypto. That keeps the
-- plaintext out of the repository (it lives in the git-ignored
-- .pressroom-dev-secret) and needs no host-side bcrypt tool -- no htpasswd, no
-- Python bcrypt, no JVM for a database-only command. pgcrypto's
-- gen_salt('bf',10) emits $2a$, which Spring's BCryptPasswordEncoder accepts.
--
-- Unlike the IT seed's ON CONFLICT DO NOTHING, this one CORRECTS the row: it is
-- what repairs a database still holding a pre-bcrypt {noop} password, with no
-- reset and no manual SQL.

-- IF NOT EXISTS still emits a NOTICE when the extension is already there, and
-- this file runs on every ./pressroom db. Warnings and errors still surface.
SET client_min_messages = warning;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO pressroom_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled)
VALUES ('dev-admin-0000-0000-0000-000000000001',
        'admin',
        '{bcrypt}' || crypt(:'devpw', gen_salt('bf', 10)),
        'Dev Admin', 'Development Admin', 'dev-admin@example.invalid',
        NOW(), 'en_US', 'UTC', true)
ON CONFLICT (username) DO UPDATE
   SET passphrase = '{bcrypt}' || crypt(:'devpw', gen_salt('bf', 10))
 WHERE CASE
         -- Verify the stored hash against the configured password. crypt()
         -- raises "invalid salt" on a second argument that is not a usable
         -- salt, and PostgreSQL does NOT guarantee short-circuit evaluation
         -- inside OR -- so this MUST stay a CASE. Flattening it into an OR
         -- chain aborts the seed on exactly the {noop} row it exists to fix.
         WHEN pressroom_user.passphrase LIKE '{bcrypt}$2%'
           THEN substring(pressroom_user.passphrase from 9)
                <> crypt(:'devpw', substring(pressroom_user.passphrase from 9))
         -- null, {noop}, bare plaintext, truncated: rewrite without calling
         -- crypt() on it at all.
         ELSE true
       END;

-- Pressroom's authorities come from userrole; 'admin' also implies editor access.
INSERT INTO userrole (id, rolename, username)
VALUES ('dev-role-0000-0000-0000-000000000001', 'admin', 'admin')
ON CONFLICT (id) DO NOTHING;

INSERT INTO userrole (id, rolename, username)
VALUES ('dev-role-0000-0000-0000-000000000002', 'editor', 'admin')
ON CONFLICT (id) DO NOTHING;
