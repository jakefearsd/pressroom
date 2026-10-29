# Pressroom Phase 0: read-only production scan

Date: 2026-10-29. Host: docker1 (compose file `docker-compose.prod.yml`, project in `~/roller`).
Running images: `ghcr.io/jakefearsd/roller:0.4.2` (app, backup), `roller-caddy:0.4.2`;
postgres 18, listmonk and umami run pinned third-party images.

Method: every query ran inside `BEGIN READ ONLY; ... ROLLBACK;` as the compose superuser
(`roller`). One `count(*)` per `text`/`varchar`/`char`/`json`/`jsonb` column of every base table in
schema `public`, matching `ILIKE '%roller%' OR ILIKE '%rlr\_%'`, generated with `\gexec`.
Nothing on docker1 was written or restarted.

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
- **No Grafana datasource was found in any file I could reach.** The role is NOLOGIN, so the
  consumer must log in some other way (or is not yet configured). See questions.

## Feeds Phase 2

| Stored reference | Phase 2 action |
|---|---|
| `/roller-ui/account.rol` in the `wikantik` privacy page | Migrate (edit the page text to the new path); a redirect from `/roller-ui/` would also cover it, but the visible text should be fixed |
| "Roller" prose in that privacy page and in `site.description` | Migrate (reword to Pressroom); content decision for Jake |
| `site.adminemail` (address containing "roller") | Leave unless Jake wants a new address; a mailbox, not code |
| "controller" matches in entries and revisions | Leave: false positives, do not run a blind search-and-replace on `roller` |
| `rlr_` token prefix | Nothing stored to migrate; digests only. Old tokens keep working only if the verifier accepts both prefixes (code decision) |
| 18 `roller_*` tables | Rename in a migration; views `analytics_events` (uses `roller_event`) must be re-checked, since Postgres views follow renames by oid but `V017` and tests spell the name |
| `rollerdb` database, `roller` role | Rename only with a connection-string change in `.env` (`POSTGRES_DB`, `ROLLER_DATABASE_JDBC_CONNECTIONURL`) and the `grafana_ro` CONNECT grants re-applied; shared Postgres also hosts umami and listmonk, so this is a restart-window item |
| `.env` / compose names (`ROLLER_*`, `rollerdb`) | Migrate with the host files; the only two files on docker1 that mention the db |
| umami, listmonk databases | Nothing to do: no stored references |

## Questions for Jake

1. Where does Grafana connect to `rollerdb` from? No datasource was found in jakemon,
   wikantik-main or on docker1, and `grafana_ro` cannot log in. If a Grafana instance exists
   elsewhere with a datasource on `rollerdb`, renaming the database or the views breaks it.
2. Is the `site.adminemail` address something you want changed with the rename?
