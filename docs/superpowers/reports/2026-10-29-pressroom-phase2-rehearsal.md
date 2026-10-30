# Pressroom Phase 2: cutover and rollback rehearsal

Date: 2026-10-29. Plan: `docs/superpowers/plans/2026-10-29-pressroom-phase2-production-names.md`, Task 12.
Tree: `76d6ce822`, images built locally as `ghcr.io/jakefearsd/pressroom:0.5.0` and `pressroom-caddy:0.5.0`.

## What was rehearsed

- **Data.** docker1's backup set of 2026-10-29 03:22 UTC (`rollerdb`, `umami`, `listmonk` dumps and the volumes archive), copied to the workstation, restored into a 0.4.2 stack, and deleted when the rehearsal ended.
- **Old stack.** The v0.4.2 `docker-compose.prod.yml`, `deploy.sh` and `upgrade.sh`, with a `.env` holding docker1's exact key set. Its values were generated locally: a SCRAM password, and `SITE_DOMAIN=:80` on port 18084. The compose project was `rehearsal-roller`, so nothing could collide.
- **The cutover.** From `old/`:

  ```
  OLD_PROJECT=rehearsal-roller NEW_PROJECT=rehearsal-pressroom NEW_DIR=../rehearsal-pressroom \
      bash cutover-pressroom.sh 0.5.0 --files-from <tree files> --no-pull
  ```

  `--check` ran first and passed.
- **The rollback.** `rollback-pressroom.sh --yes`, with the same environment.
- **"Trying again".** Afterwards: `down -v` on the new project, remove the new directory, and run the cutover a second time.

## Results

| Check | Result |
|---|---|
| `--check` (read only) | passed; listed 16 renamed keys and 5 rewritten values |
| Cutover, first run | exit 0 in 33 s |
| Site down (old stack stopping until the new app is healthy) | **28 s** (10 s of it is the old stack's `down`) |
| Row counts, before → after | equal for all 8: 3 users, 1 weblog, 14 entries, 0 media, 0 comments, 0 umami websites and 0 events, 2 listmonk subscribers |
| Role password type | `SCR` (SCRAM), so the rename kept it; the MD5 reset path did not run |
| Password login as `pressroom` over `-h postgres` | works |
| Volume copies (`cp -a` + `diff -rq`), 7 volumes | identical; owner 999:999 kept; app (uid 999) can write to media, index and uploads |
| `ALTER DATABASE … RENAME`, `ALTER ROLE … RENAME` | not blocked (only the temporary superuser connected) |
| V041 on real data | applied (`schema_migrations` max is `V041__pressroom_rename`); 38 `pressroom*` relations, 0 `roller*` |
| Health, home page, privacy page, an entry page, the Atom feed | 200. The Atom feed is byte-identical. HTML differs from 0.4.2 only by the renamed JS identifiers (`pressroomInitGalleries`, `data-pressroom-embed`, …) and the per-start `?v=` on the custom stylesheet |
| Unresolved Velocity references in the pages | none (`$name` count 0) |
| Search on the restored index | answers (`q=the`: 10 results, `q=pgbouncer`: 1) |
| `/roller-ui/login.rol?x=1` | 301 to `/pressroom-ui/login.rol?x=1` |
| Admin form login (`/pressroom_j_security_check`) | 302, then the menu page renders |
| `prs_` token: `/api/v1/me`, MCP `initialize` | 200; `serverInfo.name` is `pressroom` |
| `rlr_` token after the cutover: `/api/v1/me`, MCP | 401, 401. On 0.4.2 before it: 200 |
| umami and listmonk consoles | 200 |
| analytics-views, provision | exited 0; `analytics_traffic` view present in umami |
| grafana_ro CONNECT | granted on pressroom, umami and listmonk |
| App log | one WARN (mail server `smtp.invalid` unreachable, expected); no errors |
| Rollback | exit 0 in 26 s. 0.4.2 serves the original data again: the pages match the pre-cutover ones apart from `?v=`, `/roller-ui/` serves, and the `rlr_` token works |
| Second cutover after the documented reset | exit 0 in 32 s, same counts; the `rlr_` token is refused again |

## Findings

1. **The production search index holds `.index-inconsistent`.** It is not a defect. `LuceneIndexManager` creates the marker at startup and removes it on a clean shutdown, so a live backup always carries it, and a restore rebuilds the index on first start. The cutover copies the volumes after `down`, so it copies a cleanly closed index.
2. **MCP `serverInfo.version` read `0.4.2`.** The image was built from a tree still at pom version 0.4.2; `bin/release.sh 0.5.0` bumps it. Nothing to change.
3. **The startup log still says "Bootstrapping Roller Weblogger business tier".** That is prose, and it belongs to Phase 3.
4. **The `?v=` on the custom stylesheet changes on every app start.** It is existing behaviour, already parked as "no `?v=` on stylesheets".
5. **Production data has no `/roller-ui/` links in entries** (0 before, 0 after), so V041's link rewrite ran on nothing real. `PressroomRenameMigrationTest` covers it.

No defect was found, and the checklist needs no change.

## What only docker1 can prove

Run `bin/deploy-host.sh docker1 0.5.0 --cutover --check` first. It proves the real `.env` rewrites and the old volumes exist under project `roller`. The real run then proves:

- the host's Compose version;
- that `flock` and `df` behave;
- GHCR visibility (the packages must be public);
- the detached `deploy-host` path;
- the cloudflared origin on port 8084.

## Phase 2 rulings (from the execution ledger)

- **R1.** The redirect filter is `LegacyUiRedirectFilter`.
- **R2.** The cutover and rollback scripts are kept files in the inventory.
- **R3.** `area()` counts `pressroom` as deploy code.
- **R4.** Task 2 overlapped Task 1's review.
- **R5.** The Velocity recorder is always on and cheap.
- **R6.** The MACHINE pattern is widened.
- **R7.** The three template bugs were fixed inside Task 1.
- **R8.** Implementers ran strictly one at a time after a build collision.
- **R9.** CLI credentials have no fallback.
- **R10.** `upgrade.sh` refuses 0.4 to 0.5, and the URL renames sit in Task 10.
- **R11.** The recount fails only on a drop.
- **R12.** The rollback uses `--pull never`, not `deploy.sh`.
- **R13.** The cutover runs detached.
- **R14.** The rollback needs `--yes`.
- **R15.** The counter was fixed rather than renaming `CONTROLLER` constants.
