# Umami gets its own database server

Date: 2026-11-03. Owner decisions in chat the same day (all four recommended
options): own credentials, automatic move in the deploy, keep the old copy 14
days then drop it, release as 0.7.2 and deploy to docker1 when green.

## Intent

Umami's database lives in Pressroom's PostgreSQL instance (`postgres`, as
database `umami`). Moving Umami out to the back-office suite later is the
goal; this is its first step: Umami's data moves to a PostgreSQL server of its
own, inside the same compose stack, with its own credentials, so that what
remains to move later is a container and an address. Pressroom keeps talking
to Umami only through its API, as since 0.7.0.

## Goals

- A new `umami-db` service (the same digest-pinned PostgreSQL 18 image as
  `postgres`) with its own volume and its own superuser, `UMAMI_DB_USER`
  (default `umami`) / `UMAMI_DB_PASSWORD`. `deploy.sh` generates the password
  into `.env` the first time it finds none, exactly as it does
  `UMAMI_PRESSROOM_PASSWORD`.
- Everything that used `umami` on `postgres` uses it on `umami-db`: the
  `umami` container, the `analytics-views` one-shot (views, `grafana_ro`
  grants, Pressroom's Umami account), the nightly backup's Umami dump, and the
  retention job.
- An existing host's data moves by itself on the first deploy: a one-shot,
  `umami-move`, copies `umami` from `postgres` to `umami-db` when `umami-db`
  is empty and the old database holds Umami's tables, before the new `umami`
  container starts. Idempotent: once `umami-db` holds anything, it does
  nothing.
- The old `umami` database on `postgres` is left untouched (a rollback point:
  0.7.1's compose file points straight back at it). It is dropped by hand 14
  days after the move; nothing in the stack depends on it from 0.7.2.
- `umami-db` sits on a new `analytics` network, joined only by `umami`,
  `umami-move`, `analytics-views` and `backup`. The app is not on it, which
  makes "Pressroom reaches Umami only over the API" structural.

## Non-goals

- Moving Umami out of this compose stack, or off docker1.
- Moving listmonk's database (it stays on `postgres`).
- Any change to the Pressroom application, its schema or its Umami API use.

## Acceptance criteria

1. **Compose.** `umami-db` uses exactly the `postgres` service's image
   reference, mounts its own named volume at `/var/lib/postgresql`, takes
   `POSTGRES_DB=${UMAMI_DB:-umami}`, `POSTGRES_USER=${UMAMI_DB_USER:-umami}`
   and a required `UMAMI_DB_PASSWORD`, has a `pg_isready` healthcheck,
   publishes no port, and is on the `analytics` network only.
2. **Umami's connection.** `umami`'s `DATABASE_URL` names `umami-db` with the
   `UMAMI_DB_*` credentials, never `postgres` or `POSTGRES_PASSWORD`; it
   waits for `umami-db` healthy and `umami-move` completed successfully.
3. **Network boundary.** Exactly `umami`, `umami-move`, `analytics-views` and
   `backup` join `analytics`; `app` does not.
4. **Provision.** `provision.sh` no longer creates `${UMAMI_DB}` on `postgres`
   or grants on it (a grant on a dropped database would fail every deploy
   after the 14-day drop); `provision` gets no `UMAMI_DB`.
5. **Move: copies once.** With `umami-db`'s database empty (no tables in
   `public`) and `postgres` holding `${UMAMI_DB}` with `website_event`, the
   one-shot copies every table's rows, objects end up owned by
   `UMAMI_DB_USER`, the source is unchanged, and it prints the copied
   `website`, `session` and `website_event` counts. A re-run prints that
   `umami-db` already holds Umami's data and changes nothing.
6. **Move: nothing to move.** No `${UMAMI_DB}` on `postgres`, or one without
   `website_event` (Umami never booted there): exit 0, `umami-db` left empty
   for Umami's own first boot.
7. **Move: fails closed, and costs analytics only.** If either server cannot
   be reached, or the restore fails, it exits non-zero, `umami-db` is left as
   it was (single-transaction restore), and `umami` does not start. The blog
   stays up: `caddy` does not depend on `umami` (the rehearsal showed that it
   did, and a failed move left every blog down). A refused `umami-db` login
   names `UMAMI_DB_PASSWORD` as the likely cause. A check that could not run
   is not a pass.
8. **Secrets.** Neither password is printed or put on a command line by
   `umami-move.sh`, `analytics-views.sh`, `backup.sh` or
   `umami-retention.sh`; `deploy.sh` generates `UMAMI_DB_PASSWORD` once
   (`openssl rand -hex 24`), keeps `.env`'s mode, never prints it.
9. **analytics-views** runs against `umami-db` with the `UMAMI_DB_*`
   credentials, and on a server with no `grafana_ro` creates it `NOLOGIN`
   and grants it `CONNECT` on `${UMAMI_DB}` before the views' grants.
10. **Backup** dumps `pressroom` and `listmonk` from `postgres` and `umami`
    from `umami-db`, file names unchanged (`umami-<stamp>.dump`).
11. **Retention** trims `umami` on `umami-db`.
12. **Docs.** The runbook's upgrade notes for 0.7.2 (what moves, the minute
    of visits lost while it does, rollback, dropping the old copy), restore
    procedure, Grafana section (the `umami` datasource's host and the
    `grafana_ro` password now set on `umami-db`) and `.env.example` say so.

## Validation on docker1 (after the deploy)

Before/after row counts of every Umami table match (allowing for visits
recorded while the move ran); the old database is unchanged; Umami answers on
`umami-db` (login page, the `pressroom` account signs in, as the app's next
sync shows by `analytics_site.last_attempt` moving with no `last_error`); a
fresh tracker hit lands in `umami-db`; the nightly backup produces an
`umami-*.dump` from `umami-db` and it restores into a scratch database; the
retention job reports against `umami-db`; the app has no route to `umami-db`.

## Builder's calls

- Single network move rather than a dump-time freeze: the old `umami`
  container keeps writing during the copy and is replaced right after, so the
  visits recorded in those seconds stay in the old copy. Accepted (owner was
  told "roughly a minute").
- The move restores with `--no-owner --no-acl`; `analytics-views` re-creates
  the grants, so the old cluster's roles never need to exist on the new one.
