# Back-office decoupling: Umami and listmonk behind a boundary (light design)

**Status:** steps 1-4 implemented 2026-11-06 (unreleased; ships as
0.8.5); step 5 waits for the 2026-11-17 drop. **Decisions:** SA-15, SA-16
(wiki SimpleAgilityDecisionLog).

## Goal

Before the back-office suite gets its own repository, Pressroom's deploy
bundle holds Umami and listmonk as a **separable unit**. Moving them out
should then mean: copy one compose file and its scripts to the new
project, restore two dumps, change a few addresses in `.env`. That needs
no Pressroom code change and no Pressroom release.

## Where things are coupled today

| Coupling | Umami | listmonk |
|---|---|---|
| App → service address | `analytics.umami.apiUrl`, already a setting; compose defaults it to `http://umami:3000` | `newsletter.listmonk.baseurl`, already a setting; env.example sets `http://listmonk:9000` |
| Caddy → service | `umami:3000` written out in 3 site blocks | `listmonk:9000` written out; caddy `depends_on: listmonk` (the blog waits for the newsletter) |
| Database | own server `umami-db` (0.7.2), done | **on Pressroom's `postgres`, as Pressroom's superuser**; `provision.sh` creates its database |
| Provisioning | `analytics-views` (views, `grafana_ro`, Pressroom's account), from Pressroom's image | none (listmonk installs itself) |
| Backup / retention | `backup.sh` dumps umami-db and runs `umami-retention.sh` | `backup.sh` dumps it as a Pressroom-server "service DB" |
| Compose file | one file holds everything | same |

The app side is already in good shape. Every address, credential and
dashboard link is configuration, and both clients have timeouts. The
coupling is in the deploy bundle.

## Steps

Each step is one test-first commit. The tests are ProductionComposeTest
and the script tests, as for 0.7.2.

1. **Caddy upstreams are settings.**
   - Settings:
     - `ANALYTICS_UPSTREAM` (default `umami:3000`) feeds the tracker and
       send handles in both site blocks and the `:8081` console.
     - `NEWSLETTER_UPSTREAM` (default `listmonk:9000`) feeds the
       `LISTMONK_DOMAIN` site.
   - caddy stops depending on listmonk, the same rule as for Umami: the
     blog never waits on a back-office service.
   - The upstream should be a LAN address (`http://host:port`), not the
     suite's public hostname. Going through Cloudflare would replace the
     reader's IP that Umami counts.
   - *AC:* No service hostname appears literally in the Caddyfile. Caddy
     `depends_on` is `app` only. Both settings are documented in
     env.example.

2. **listmonk gets a database server of its own: `listmonk-db`.**
   - Same pinned `postgres:18`, its own volume, and its own login
     `LISTMONK_DB_USER`/`LISTMONK_DB_PASSWORD`. deploy.sh generates the
     password, with the existing-volume guard umami-db has.
   - It gets its own `newsletter` network, which the app is not on. The
     app reaches listmonk only through its API.
   - `provision.sh` stops creating the listmonk database.
   - Existing data: `umami-move.sh` became a generic `db-move.sh`. It
     takes the database, target server and login, plus a "has data"
     table (`website_event` or `subscribers`), and runs as both
     `umami-move` and `listmonk-move`. It was built without knowing
     whether docker1's listmonk holds data; it is a no-op if it does
     not. The old database stays on `postgres` as the rollback point
     and is dropped by hand later.
   - *AC:* No back-office service uses `POSTGRES_*` credentials. Only
     back-office services reach `listmonk-db`. `provision.sh` names no
     back-office database.

3. **Core plus back-office overlay.**
   - `docker-compose.prod.yml` keeps `postgres`, `provision`, `admin`,
     `app`, `caddy` and `backup`.
   - The new `docker-compose.backoffice.yml` holds the rest:
     - `umami`, `umami-db`, `umami-move` and `analytics-views`;
     - `listmonk`, `listmonk-db` and `listmonk-move`;
     - the back-office backup from step 4;
     - the app's back-office addresses (`PRESSROOM_ANALYTICS_UMAMI_APIURL`,
       `PRESSROOM_NEWSLETTER_LISTMONK_BASEURL`) as defaults.
   - The overlay is the seed of the suite's repository.
   - Selecting the files: `.env` sets
     `COMPOSE_FILE=docker-compose.prod.yml:docker-compose.backoffice.yml`,
     which Compose reads natively. deploy.sh adds that line when it is
     missing.
   - deploy.sh and upgrade.sh call plain `docker compose`, without `-f`.
     deploy.sh exports `.env`'s value, so an inherited `COMPOSE_FILE`
     cannot narrow it. upgrade.sh uses `.env`'s value, or the core file
     alone on a pre-0.8.5 host, whose one file held everything.
   - The rollback recipe restores only the files it backed up; the
     overlay is new on the first upgrade, so it has no `.bak`.
   - upgrade.sh downloads both files. The release workflow publishes
     both.
   - Removing the overlay from `COMPOSE_FILE` is the cut-over switch.
     Pressroom then runs alone, and the addresses in `.env` point at the
     suite.
   - *AC:*
     - The core file alone passes `docker compose config`, and in it the
       app's analytics and newsletter are off by default.
     - No core service `depends_on` an overlay service.
     - Every back-office service is in the overlay, and no core service
       carries back-office credentials.

4. **Back-office backup and retention move into the overlay.**
   - Core `backup` keeps Pressroom's database and media only.
   - A `backoffice-backup` service in the overlay dumps umami-db and
     listmonk-db, each with its own login, into its own volume. It runs
     Umami retention after a successful night.
   - It still runs from Pressroom's image for now; the suite will rebuild
     it on a stock image.
   - upgrade.sh's pre-upgrade backup runs both when the overlay is
     present.
   - *AC:* `backup.sh` names no back-office database. A failed
     back-office dump never marks Pressroom's backup failed.

5. **Retire `umami-move`.** This comes after the old database is dropped
   on 2026-11-17, in a later release. `listmonk-move`, if it exists,
   follows the same path.

**Docs:** docker_deployment.md (overlay, `COMPOSE_FILE`, restore
commands), env.example, and docs/dev/audience-and-analytics.md. The
wiki's PressroomHub is updated once this ships.

## Release

There is one patch release (0.8.5) after step 4, although the deployment
shape changes. On docker1, deploy.sh adds `COMPOSE_FILE` and generates
`LISTMONK_DB_PASSWORD`. The listmonk data moves if there is any. The
smoke test also checks tracker hits, a test subscribe, and that both
backups ran.

## Out of scope

- The suite's repository, its name, and a stock-image rebuild of its
  scripts.
- Moving the services to another host.
- Any Pressroom Java change. None is needed: every setting already
  exists.
