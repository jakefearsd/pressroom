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
-- Migration: agent accounts (docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md).
--
-- An agent is a roller_user row with kind = 'AGENT'. It never signs in: it has
-- no password and works only through API tokens a site admin mints for it, so
-- the password column becomes nullable. notes holds an agent's purpose for the
-- admin UI; last_login is the last interactive sign-in of a person.
-- roller_api_token.issued_by is the id of the user who minted the token, NULL
-- for tokens minted before this migration. No foreign key: a token's history
-- must survive its issuer.
--
-- Idempotent: safe to run on a database that already has it.

ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS kind varchar(16) NOT NULL DEFAULT 'PERSON';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ru_kind_ck') THEN
        ALTER TABLE roller_user ADD CONSTRAINT ru_kind_ck CHECK (kind IN ('PERSON', 'AGENT'));
    END IF;
END $$;

ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS notes varchar(1000);
ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS last_login timestamp(3) with time zone;
ALTER TABLE roller_user ALTER COLUMN passphrase DROP NOT NULL;

ALTER TABLE roller_api_token ADD COLUMN IF NOT EXISTS issued_by varchar(48);
