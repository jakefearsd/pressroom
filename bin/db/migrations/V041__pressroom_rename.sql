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
-- Migration: the Pressroom rename of the schema. Every relation in schema
-- public whose name starts roller_ (tables, views, sequences, then the
-- indexes no constraint renamed) and every constraint whose name starts
-- roller_ gets the prefix pressroom_ instead. Rows, grants, foreign keys and
-- the analytics views all follow a table by its oid, so nothing is copied
-- and nothing is dropped; this is catalog work only.
--
-- Then stored links to the admin UI move with it: /roller-ui/ becomes
-- /pressroom-ui/ in page content and in entry text and summary.
-- weblogentry_revision is history and keeps what was written.
--
-- This file is kept with the old names on purpose (OldNameInventoryTest,
-- LAST_OLD_NAME_MIGRATION): it is the one place that has to spell them.
--
-- Prerequisites: V002 through V040 (the roller_ tables they create).
--
-- Idempotent. A name already renamed is simply not found again, so a second
-- run, or a run after a partial rename by hand, finishes the job and changes
-- nothing else. It refuses, before renaming anything, when an old name and
-- its new name both exist (two tables can never be merged by a rename, and
-- picking one would lose the other's rows) or when the new name would pass
-- PostgreSQL's 63-byte identifier limit (it would be silently truncated).
-- A refusal raises, so the whole migration rolls back.
--
-- SQLScriptRunner joins a statement's lines with spaces, so the DO body
-- below must hold no line comments.

DO $rename$
DECLARE
    r record;
    target text;
BEGIN
    FOR r IN
        SELECT c.relname AS old_name, 'pressroom_' || substr(c.relname, 8) AS new_name
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname LIKE 'roller\_%'
    LOOP
        IF octet_length(r.new_name) > 63 THEN
            RAISE EXCEPTION 'V041: % would become %, longer than 63 bytes; rename it by hand first',
                r.old_name, r.new_name;
        END IF;
        IF to_regclass(format('public.%I', r.new_name)) IS NOT NULL THEN
            RAISE EXCEPTION 'V041: both % and % exist; resolve that by hand, a rename cannot merge them',
                r.old_name, r.new_name;
        END IF;
    END LOOP;

    FOR r IN
        SELECT c.relname AS old_name,
               CASE c.relkind
                   WHEN 'v' THEN 'VIEW'
                   WHEN 'm' THEN 'MATERIALIZED VIEW'
                   WHEN 'f' THEN 'FOREIGN TABLE'
                   WHEN 'S' THEN 'SEQUENCE'
                   ELSE 'TABLE'
               END AS kind
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname LIKE 'roller\_%'
           AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
         ORDER BY CASE WHEN c.relkind = 'S' THEN 1 ELSE 0 END, c.relname
    LOOP
        target := 'pressroom_' || substr(r.old_name, 8);
        EXECUTE format('ALTER %s public.%I RENAME TO %I', r.kind, r.old_name, target);
    END LOOP;

    FOR r IN
        SELECT con.conname AS old_name, cls.relname AS table_name
          FROM pg_constraint con
          JOIN pg_class cls ON cls.oid = con.conrelid
          JOIN pg_namespace n ON n.oid = cls.relnamespace
         WHERE n.nspname = 'public'
           AND con.conname LIKE 'roller\_%'
         ORDER BY cls.relname, con.conname
    LOOP
        target := 'pressroom_' || substr(r.old_name, 8);
        IF octet_length(target) > 63 THEN
            RAISE EXCEPTION 'V041: constraint % would become %, longer than 63 bytes', r.old_name, target;
        END IF;
        EXECUTE format('ALTER TABLE public.%I RENAME CONSTRAINT %I TO %I', r.table_name, r.old_name, target);
    END LOOP;

    FOR r IN
        SELECT c.relname AS old_name
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname LIKE 'roller\_%'
           AND c.relkind IN ('i', 'I')
         ORDER BY c.relname
    LOOP
        target := 'pressroom_' || substr(r.old_name, 8);
        EXECUTE format('ALTER INDEX public.%I RENAME TO %I', r.old_name, target);
    END LOOP;
END
$rename$;

UPDATE pressroom_weblogpage
   SET content = replace(content, '/roller-ui/', '/pressroom-ui/')
 WHERE content LIKE '%/roller-ui/%';

UPDATE weblogentry
   SET text = replace(text, '/roller-ui/', '/pressroom-ui/')
 WHERE text LIKE '%/roller-ui/%';

UPDATE weblogentry
   SET summary = replace(summary, '/roller-ui/', '/pressroom-ui/')
 WHERE summary LIKE '%/roller-ui/%';
