# Pressroom Phase 0: read-only production scan

Date: 2026-10-29. Host: docker1 (compose file `docker-compose.prod.yml`, project in `~/roller`).
Running images: `ghcr.io/jakefearsd/roller:0.4.2` (app, backup), `roller-caddy:0.4.2`;
postgres 18, listmonk and umami run pinned third-party images.

Method: every query ran inside `BEGIN READ ONLY; ... ROLLBACK;` as the compose superuser
(`roller`). Coverage (second pass, after review): every column of every table, partitioned
table, materialized view and view in every non-system schema, cast with `::text` (so
json/jsonb, arrays, enums, uuid and citext-style types are matched as text), excluding only
`bytea`. Matching is `ILIKE '%roller%' OR ILIKE '%rlr\_%'`. Only schema `public` exists in
all three databases (checked with `pg_namespace`, so no other schema was missed).

| Database | Columns scanned | By type (tables/matviews/views) | Excluded |
|---|---|---|---|
| rollerdb | 281 | varchar 189, text 12, char 1, jsonb 1; plus bool 18, date/timestamp 40, numeric types 20 (cast, scanned, cannot match) | none |
| umami | 227 | varchar 68, text 11, char 1, jsonb 5, uuid 59; plus bool, date/timestamp, numeric | 1 `bytea` column (binary, not text) |
| listmonk | 134 | text 33, varchar[] 2, text[] 1, json 2 (matviews), jsonb 9, uuid 5, 15 enum columns; plus bool, date/timestamp, numeric | none |

The first pass covered only `text`, `varchar`, `char`, `json`, `jsonb` in `information_schema`; the second
pass found the same four hits and no new ones, so arrays, enums, uuid, char and views add nothing.

Generator (run with `psql -At -v ON_ERROR_STOP=1`, `\gexec` runs each generated count):

```sql
BEGIN READ ONLY;
SELECT format('SELECT %L AS rel, %L AS col, count(*) AS n FROM %I.%I WHERE %I::text ILIKE %L OR %I::text ILIKE %L HAVING count(*) > 0',
  n.nspname||'.'||c.relname, a.attname, n.nspname, c.relname, a.attname, '%roller%', a.attname, '%rlr\_%')
FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r','p','m','v')
  AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%'
  AND a.atttypid <> 'bytea'::regtype
ORDER BY 1 \gexec
ROLLBACK;
```

Privacy: email addresses (one matched value is the admin contact address, and one context
snippet contained a fragment of one), password hashes and token digests are left out. Rows
are identified by weblog handle and table/id, not by author.

## Per-database hits

### rollerdb

| Table.column | Rows matching | What they are |
|---|---|---|
| `roller_properties.value` | 2 | `site.adminemail` (the admin contact address contains "roller"; value omitted); `site.description` (prose: "this fork of Roller is a part") |
| `roller_weblogpage.content` | 1 (3 matches) | page of weblog `wikantik` (the privacy policy): "The software is Roller", "If you run your own copy of Roller", and the path `/roller-ui/account.rol` |
| `weblogentry.text` | 2 | weblog `wikantik`, entries d8bafce4, f77b146d. **False positives**: the word "controller" (substring "roller") |
| `weblogentry_revision.text` | 4 | weblog `wikantik`, same two entries plus fd236f32, 18cc265d. **False positives**: "controller(s)" |

No hit for the `rlr_` token prefix anywhere (tokens are stored as digests, so the prefix is not
in any column). Zero hits in `roller_user`, `roller_api_token`, `roller_user_token`,
`roller_reader_comment`, `roller_form_submission`, `weblog` (including custom domains and
theme settings), templates and media metadata.

Schema objects, for the Phase 2 rename (not content matches): 18 tables named `roller_*`
(`roller_audit_log`, `_permission`, `_properties`, `_tasklock`, `_weblogentrytagagg`,
`_mediafiledir`, `_event`, `_form_submission`, `_user`, `_api_token`, `_mediafile`,
`_mediafiletag`, `_reader_comment`, `_user_token`, `_weblogpage`, `_weblog_redirect`,
`_weblogentrytag`, `_business`); database `rollerdb`; superuser role `roller`.

### umami

No matches in any column.

### listmonk

No matches in any column.

## Outside consumers of rollerdb

- Grep of `~/source/jakemon` and `~/source/wikantik-main` for `rollerdb|grafana_ro`: no hits.
  A wider grep of all of `~/source` outside the roller repo: no hits.
- On docker1, `grep -rln rollerdb ~` over yml/yaml/env/alloy: only `~/roller/docker-compose.prod.yml`
  (`POSTGRES_DB: ${POSTGRES_DB:-rollerdb}` defaults on four services, the postgres healthcheck,
  a comment) and `~/roller/.env` (`POSTGRES_DB=rollerdb`, and
  `ROLLER_DATABASE_JDBC_CONNECTIONURL=jdbc:postgresql://postgres:5432/rollerdb`).
- Role `grafana_ro`: `NOLOGIN`, not superuser, member of no role, no per-database settings. It has
  `SELECT` on exactly two views in `rollerdb`: `analytics_events` (reads `roller_event` joined to
  `weblog`) and `analytics_weblog_sites` (reads `weblog`). The repo's `V017` migration and
  `deploy/analytics/umami-views.sql` own these; Grafana joins `analytics_traffic` (umami db) to
  `analytics_weblog_sites` on website_id.
- Old class and package names in the host config (final-review I-6, one read-only command,
  key names only: `grep -nE 'org[./]apache[./]roller' ~/roller/.env ~/roller/docker-compose.prod.yml
  | sed -E 's/=.*//'`): one hit, `~/roller/.env` line 92, a **comment** naming the source path
  `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties` (copied from the
  `.env` template). No key or value in either file names a class or package, so the package move
  needs no host-config change; the comment can be reworded with the Phase 2 `.env` migration.
  `docker-compose.prod.yml`: none.
- **No Grafana datasource was found in any file I could reach.** The role is NOLOGIN, so the
  consumer must log in some other way (or is not yet configured). See questions.

## Feeds Phase 2

| Stored reference | Phase 2 action |
|---|---|
| `/roller-ui/account.rol` in the `wikantik` privacy page | A stored page edit (migration or by hand, per the spec's "Stored templates"); the `/roller-ui/` redirect covers readers meanwhile |
| "Roller" prose in that privacy page and in `site.description` | Migrate (reword to Pressroom); content decision for Jake |
| `site.adminemail` (address containing "roller") | Leave unless Jake wants a new address; a mailbox, not code |
| "controller" matches in entries and revisions | Leave: false positives, do not run a blind search-and-replace on `roller` |
| `rlr_` token prefix | Nothing stored to migrate. Old-prefix tokens are refused by design (spec, Phase 2); re-mint the publishing agent's token after the deploy |
| 18 `roller_*` tables | Rename in a migration. `DatabaseInstaller` detects an existing install by `roller_user`, so Phase 2 must handle both before and after the rename. View `analytics_events` (uses `roller_event`) follows a table rename by oid, but `V017` and tests spell the old name |
| `rollerdb` database, `roller` role | Rename the `roller` role from a different superuser session (Postgres refuses renaming the current session's role), and confirm the password survives (renaming clears the password only if it is stored as MD5, which uses the role name as salt; SCRAM-SHA-256 uses a random salt and survives; check which one docker1's `roller` role uses via the `pg_authid.rolpassword` prefix during Phase 2, not now, and verify the login after the rename either way). Umami and Listmonk also log in as `roller` (POSTGRES_USER), so their connection strings follow the rename. Database rename needs no connections open; `.env` (`POSTGRES_DB`, `ROLLER_DATABASE_JDBC_CONNECTIONURL`) changes with it. Privileges, including `grafana_ro`'s grants, follow the object, not the name. Restart-window item |
| `.env` / compose names (`ROLLER_*`, `rollerdb`) | Migrate with the host files; the only two files on docker1 that mention the db |
| umami, listmonk databases | Nothing to do: no stored references |

## Questions for Jake

1. Where does Grafana connect to `rollerdb` from? No datasource was found in jakemon,
   wikantik-main or on docker1, and `grafana_ro` cannot log in. If a Grafana instance exists
   elsewhere with a datasource on `rollerdb`, renaming the database or the views breaks it.
2. Is the `site.adminemail` address something you want changed with the rename?
