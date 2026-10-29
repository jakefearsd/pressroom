# Pressroom: renaming the Roller fork

Date: 2026-10-29. Status: approved by Jake 2026-10-29.

## Intent

This repository started as a fork of Apache Roller 6.2 and no longer
resembles it: Spring Boot 4, Java 25, PostgreSQL only, Markdown-native
authoring, agent publishing over `/api/mcp`, the Quiet Instrument admin UI.
Jake wants it to carry its own name. The product becomes **Pressroom**: in
the Simple Agility stack it sits beside **Stockroom**, which holds the
goods, and Pressroom is where the business tells people about them.

**Why now.** The business is in early pilot mode. The site is live on docker1
(blog.wikantik.com), but disruption costs little today and will cost more
every month. Jake's ruling: be aggressive. Every name the product carries is
renamed now, including names production and other systems depend on. No
period where both names work, except the one exception below.

## Decisions Jake made

1. The name is **Pressroom**. Lowercase form `pressroom` is used wherever a
   machine reads it.
2. Java packages move to **`com.wikantik.pressroom`**, accepting that this
   touches nearly every file.
3. Production-facing names (layer 2 below) are renamed **aggressively**, as a
   clean break, now.
4. Claude makes the cross-repo changes itself: the Wikantik wiki pages,
   jakemon's config, and GitHub (repo name, images). Jake then directs each
   repo's own agent to check and incorporate those changes and run whatever
   testing is more than content.
5. **First step: before any renaming, make sure the test suite covers the
   configuration surface robustly enough to catch what the rename breaks.**

## The three layers

**Layer 1: code identity.** Java packages, Maven coordinates, classes named
`Roller*` (46 of them), and every place a class name is written as a string
outside Java: 24 `*.orm.xml` files, `META-INF/persistence.xml`, 28 class
names in `roller.properties`, `velocity.properties`, `log4j2.xml` (main and
test), `rollerConfig.tld`, JSP imports (`index.jsp`, `login-redirect.jsp`,
`logout-redirect.jsp`, `footer.jsp`, `menu-model.jsp`),
`jboss-deployment-structure.xml`, `application.properties`, and the JaCoCo
package rules, SpotBugs excludes and PMD ruleset in the build. The compiler
sees none of the string references.

**Layer 2: production and contract names.**

| Today | Becomes |
|---|---|
| `ROLLER_*` env vars, `roller.custom.config`, `roller.*` system properties | `PRESSROOM_*`, `pressroom.custom.config`, `pressroom.*` |
| `roller.properties`, `roller-custom.properties` | `pressroom.properties`, `pressroom-custom.properties` |
| database `rollerdb`, user `roller` | `pressroom`, `pressroom` |
| tables `roller_*` (and their constraint/index names) | `pressroom_*` |
| `ghcr.io/jakefearsd/roller`, `roller-caddy` | `ghcr.io/jakefearsd/pressroom`, `pressroom-caddy` |
| compose service, container and volume names | `pressroom*` |
| `/roller-ui/...` (389 references in main) | `/pressroom-ui/...` |
| API token prefix `rlr_` | a Pressroom prefix (builder picks, e.g. `prs_`) |
| MCP `serverInfo("roller", ...)` | `pressroom` |
| logger names `roller.sso`, `roller.mcp`, `roller.admin`, ... | `pressroom.*` |
| metric prefix `roller_`, `service` label `roller`, instance `roller-prod` | `pressroom_`, `pressroom`, `pressroom-prod` |
| session/request attribute keys, thread names, temp-file prefixes `roller*` | `pressroom*` |
| CSS classes `roller-*` (about 30) | `pressroom-*`, updated together with the `Routes` CSS markers |
| Velocity context names such as `$rollerContentScope` | `pressroom` equivalents |
| `roller.war`, `bin/roller-api` CLI, `./roller` dev script | `pressroom.war`, `bin/pressroom-api`, `./pressroom` |

**Layer 3: what people see.** UI text, page titles, email text, README, docs
under `docs/`, `docker_deployment.md`, `RollerSSO.md`, release titles, CI
workflow names.

## Goals

- After the work, the old name survives only where it must (see "What keeps
  the old name"), and a test proves it.
- Production on docker1 runs as Pressroom with all its data: weblogs,
  entries, media, comments, users, tokens table, search index.
- Every quality gate still measures what it measured before, at the same
  floors.
- The wiki, jakemon and GitHub describe and address the product as
  Pressroom.

## Non-goals

- No functional change. Behaviour after the rename is the behaviour before.
- Renaming `Weblogger*` classes (`WebloggerConfig`, `WebloggerException`)
  or the domain word "weblog". Those are vocabulary, not brand.
- Rewriting history: past specs and plans under `docs/superpowers/` and
  already-applied migrations keep their text.
- Changing the blog URLs readers use. Weblogs already serve from the root;
  nothing in a reader-facing blog URL says "roller".

## Anti-goals: failure even if it "works"

- A rename that compiles, passes the suite, and silently stops loading a
  plugin, page model, entity mapping or log route because a string class
  name no longer resolves.
- A quality gate that turns green because its package pattern no longer
  matches anything (JaCoCo package rules for rendering and the security
  boundary are written as `org.apache.roller...` includes).
- Any production data loss, or an upgrade with no verified way back.
- A stray `ROLLER_*` variable in production `.env` silently ignored, so the
  app runs on a default (say, a wrong DB URL or storage dir) without anyone
  noticing.
- A blog template stored in the database that references an old name and
  now prints as literal text (Velocity is lenient).
- Removing the Apache attribution. `LICENSE.txt` and `NOTICE.txt` must keep
  crediting Apache Roller; the license requires it.
- Lowering a floor, widening an exclusion or adding an assertion-free test
  to get the build green.

## Phases

Each phase is finished, on `master` and verified locally before the next
starts. **Only one production release (Claude's call): 0.5.0, after Phases
0-3**, so that Jake authorises one push and docker1 sees one break, not three.
Phase 1's package move is proved against live data by Phase 2's dry run on a
restored production copy (a builder check found no migration or runtime
config key that embeds a class name or "roller", so the risk there is low).

### Phase 0: safety net (first)

Evaluate the current tests around configuration and fill the gaps so that
the rename's typical mistakes fail the build. Existing tests to start from:
`app/src/test/java/org/apache/roller/weblogger/config/` (env override,
directories, runtime config, log routing/pattern, boot report),
`InitFilterTest`, `QualityGatePomTest`, `ProductionComposeTest`,
`PostgresMajorPinTest`, `Routes` CSS markers, the browser ITs.

Acceptance criteria, each a test:

1. **Every class name written as a string resolves.** For every
   non-Java resource that names a class (the files listed under Layer 1),
   every fully-qualified name in it loads. Introducing a typo in any one of
   those files makes this test fail.
2. **Configured lists load completely.** For each configured list of
   classes (plugins, page models, renderers, any others in
   `roller.properties`), the number loaded at startup equals the number
   configured. A class that fails to load fails the test, never just logs.
3. **No gate matches nothing.** Every package named in a JaCoCo rule, the
   SpotBugs exclude file and the PMD ruleset matches at least one existing
   package or class. Renaming a package without updating its rule fails
   the test. (Extends `QualityGatePomTest` or sits beside it.)
4. **Persistence mapping is complete.** Every `@Entity`/ORM-mapped class is
   listed in `persistence.xml` and every listed mapping file loads; the
   entity set at startup equals the expected set.
5. **Log routing by name.** Every named logger the code uses
   (`roller.sso`, `roller.mcp`, ...) lands in the appender `log4j2.xml`
   intends. (`LogRoutingTest` may already cover part of this; extend it.)
6. **Shipped templates have no unresolved references.** Rendering every
   shipped theme and `WEB-INF/velocity` template under strict reference
   checking reports no reference that fails to resolve.
7. **Old-name inventory.** A test lists every remaining occurrence of the
   old name in main code, resources, webapp, build files, scripts and
   deploy files, grouped by layer, against an allowlist. In Phase 0 it
   records the baseline; each later phase shrinks it; at the end its
   offender set is `Set.of()` in the style of the i18n ratchets.
8. **Production template scan (one-off, not a test).** Read-only scan of
   docker1's stored templates (`weblog_custom_template` / renditions) and
   runtime properties for any old-name reference. The same pass locates any
   Grafana datasource or other outside consumer pointed at `rollerdb`.
   Findings feed Phase 2's migration.

Phase 0 changes no names. It is done when 1-7 are green on today's code, each
was seen to fail against a deliberately broken input, and 8 is reported.

### Phase 1: code identity

- Package mapping (Claude's call): `org.apache.roller.weblogger.X` →
  `com.wikantik.pressroom.X`, and `org.apache.roller.util` →
  `com.wikantik.pressroom.util` (checked: no class-name collision with
  `weblogger.util`). Test sources move the same way. Resource directories
  that mirror packages (`org/apache/roller/weblogger/pojos/*.orm.xml`,
  `.../config/roller.properties`) move with them.
- Maven `groupId` `com.wikantik.pressroom`, artifact `pressroom-project` /
  `pressroom`.
- `Roller*` classes renamed to `Pressroom*`.
- Moves are done with `git mv` in a commit that is pure move plus mechanical
  substitution, recorded in `.git-blame-ignore-revs`, so `git log --follow`
  and blame survive.
- **Serial, not parallel.** This phase touches nearly every file; running it
  in parallel worktrees guarantees overlapping changes (see CLAUDE.md on
  stale worktree bases). One implementer, one tree.
- Done when: full unit suite, all gates, and the browser ITs at both `/` and
  `-Dit.context.path=roller` pass; the Phase 0 tests pass unchanged except
  for the inventory, whose Layer 1 group is empty; coverage floors unchanged.

### Phase 2: production and contract names

Everything in the Layer 2 table, plus:

- **Database.** A new migration renames `roller_*` tables, constraints and
  indexes. `DatabaseInstaller` (which detects an existing install by
  `roller_user`) handles a database from before and after the rename.
  The DB and role rename (`rollerdb`/`roller` → `pressroom`) is a one-time
  deploy step, after a verified backup.
- **The Postgres cluster is shared.** Umami and Listmonk keep their
  databases in the same `postgres` service and log in as
  `${POSTGRES_USER:-roller}`, the cluster's bootstrap superuser; V017 grants
  `grafana_ro` read access to views in `rollerdb`. Claude's calls: treat
  Umami's and Listmonk's data as live and in scope for the backup, the dry
  run and the post-deploy check (each answers and still holds its data);
  rename the `roller` role to `pressroom` from a different superuser session
  (Postgres refuses a self-rename) and confirm the password survives the
  rename; `POSTGRES_USER`/`POSTGRES_DB` defaults and every connection string
  in the compose file follow. `grafana_ro` keeps its name (not a brand) but
  its grants follow the renamed database. Wherever a Grafana datasource
  points at `rollerdb` is found in Phase 0 and updated with Phase 4.
- **docker1 layout.** `~/roller` becomes `~/pressroom` and the compose
  project name changes with it, which renames every container and volume,
  Umami's and Listmonk's included. Volumes are copied, as above.
- **Way back (Claude's call).** Rollback is the 0.4.2 image on the retained
  old volumes and old `~/roller` directory, accepting the loss of anything
  written after cutover. That is acceptable in the pilot because the
  post-deploy check runs immediately. The rollback is rehearsed once in the
  local dry run, not just written down. Old volumes, directory and ghcr
  images are kept for 30 days.
- **Env and properties: fail closed.** At startup, if any `ROLLER_*`
  environment variable or `roller.*` system property such as
  `roller.custom.config` is present, Pressroom refuses to start and names
  each one (Claude's call, from the anti-goal above). `bin/deploy-host.sh`
  rewrites docker1's `.env` from `ROLLER_*` to `PRESSROOM_*` during the
  upgrade.
- **The one exception: `/roller-ui/*` redirects.** Every `/roller-ui/...`
  request answers a permanent redirect to the same path under
  `/pressroom-ui/`, query string kept, at any context path (Claude's call:
  cached pages' footer sign-in links and bookmarks keep working, at almost
  no cost).
- **Tokens.** Old-prefix tokens are refused. The publishing agent's token is
  minted again after the deploy.
- **Volumes.** Copied to new names, not renamed in place; the old volumes
  stay until the new deployment is verified.
- **Stored templates.** Any old-name reference found by Phase 0 item 8 is
  rewritten by migration or by hand on deploy, decided by what the scan
  finds.
- **Google sign-in callback.** Jake updates the redirect URI in the Google
  console to `/pressroom-ui/sso/callback`; the deploy checklist says so.
- Done when: the unit suite, gates and browser ITs pass; a dry run of
  `deploy-host.sh` against a copy of production data (restored from a
  docker1 backup into a local stack) upgrades cleanly and every weblog,
  entry, media file and user is present afterwards; the inventory's Layer 2
  group is empty; then the release is deployed to docker1 and checked
  (health, a weblog page, admin login, an MCP call with a new token, a
  redirect from `/roller-ui/`).

### Phase 3: what people see

UI text, emails, README, `docs/`, deploy docs, CI and release names,
`RollerSSO.md` → `PressroomSSO.md`, CLAUDE.md and its `docs/dev/` pointers.
`NOTICE.txt` gains a line that Pressroom is derived from Apache Roller and
keeps the Apache notice. Done when the inventory test's offender set is
`Set.of()` and the i18n ratchets stay empty.

### Phase 4: other repos and systems

**Cutover gap (Jake's ruling, 2026-10-29).** The docker1 deploy does not
wait for other repos. Claude prepares the jakemon and wiki changes before
the deploy; a gap of minutes or even hours afterwards is acceptable, during
which jakemon's dashboards and alerts for Pressroom are dark and
blogger-agent cannot publish until its token, MCP server name and skill are
updated. Claude silences the affected jakemon alerts for the window and
names the gap in the deploy checklist; Jake keeps it short by directing the
other repos' agents.

Claude makes these changes; each repo's own agent then checks, incorporates
and tests them under Jake's direction. Claude never pushes another repo's
work on its own initiative.

- **GitHub:** rename `jakefearsd/roller` to `jakefearsd/pressroom` (GitHub
  keeps a redirect from the old URL), update remotes, `pom.xml` SCM URLs and
  workflows; images publish to the new ghcr names.
- **Wikantik wiki:** pages renamed and rewritten (`RollerHub` →
  `PressroomHub`, `RollerMcp`, `RollerOnDocker`, `RollerAdminAccountRunbook`,
  `RollerTelemetryHandoffSpec`, `RollerBlogPlatform`, `RollerW3tPlugins`),
  the `roller` cluster renamed `pressroom`, references in Stack Hub,
  Telemetry Contract (the `roller_` prefix and `roller-prod` example),
  Decision Log and Agent Coordination updated, and a new decision entry
  recording the rename and why. Old page names should keep resolving if the
  wiki supports it.
- **jakemon:** `hosts/*/apps.alloy` relabel rules (`roller-.+` → `roller`,
  `roller-prod`), `central/prometheus/rules/tests/resources_test.yml`,
  jakemon's `CLAUDE.md`.
- **Consumers Claude does not change:** `blogger-agent` (its `roller` MCP
  server entry, `publish_to_roller` skill, and its token) is listed in a
  Coordination ledger entry for its own agent.
- **Local checkout:** `~/source/roller` → `~/source/pressroom`. Claude
  Code's project memory is keyed by that path
  (`~/.claude/projects/-home-jakefear-source-roller/`), so it is copied to
  the new key in the same step.
- This repo's `CLAUDE.md` header and `get_briefing(clusters=[...])` call
  change to the new cluster name.

## What keeps the old name

- `LICENSE.txt`, `NOTICE.txt` (Apache attribution).
- Already-applied migrations `bin/db/migrations/V001`-`V040`.
- Historical specs and plans under `docs/superpowers/`.
- The `/roller-ui/*` redirect and the fail-closed check for `ROLLER_*`
  variables, which must name what they look for.
- The migration that renames the tables.

## Constraints

- Everything in CLAUDE.md applies: test-first, every gate, `-Xlint:all`, the
  one-build-at-a-time rule, no push except by the top-level session when Jake
  asks in so many words.
- Velocity leniency, undeclared JSP prefixes and unnamed
  `@RequestParam`s all fail silently; the Phase 0 tests exist to catch the
  first, the browser ITs the rest.
- No code may assume a context path.

## Left to the builder

The token prefix spelling, version numbers, how the inventory test scans,
the exact mechanics of the move commit, the order of steps inside each phase,
and how each Phase 0 test is built.
