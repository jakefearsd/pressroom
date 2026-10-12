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
-- Migration: bind an account to an OpenID Connect identity.
--
-- A reader who signs in with Google (or any OIDC provider) is found by the
-- provider's issuer and subject -- never by username or email, so a local
-- account is never adopted by a sign-in that happens to share its address.
-- Both columns are null on local (password) accounts. See
-- docs/superpowers/specs/2026-10-11-reader-sign-in-design.md.
--
-- Idempotent: safe to run on a database that already has some of it.

ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS sso_issuer  varchar(255);
ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS sso_subject varchar(255);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ru_sso_identity_pair_ck') THEN
        ALTER TABLE roller_user ADD CONSTRAINT ru_sso_identity_pair_ck
            CHECK ((sso_issuer IS NULL) = (sso_subject IS NULL));
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ru_sso_identity_uq
    ON roller_user (sso_issuer, sso_subject)
    WHERE sso_subject IS NOT NULL;
