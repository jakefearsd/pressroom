# Configuration

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Configuration Files

### Key Configuration Locations
- **Boot Config**: `app/src/main/resources/application.properties` (server
  port/context-path, filter ordering, actuator exposure)
- **Dev Properties**: `app/src/test/resources/pressroom-boot-dev.properties`
  (loaded via `-Dpressroom.custom.config` by `./pressroom dev` and NetBeans
  run/debug actions)
- **Servlets/Filters**: `app/src/main/java/.../boot/ServletRegistrationConfig.java`
  (Java-config transcription of the retired `web.xml`)
- **Security Config**: `app/src/main/java/.../boot/SecurityConfig.java`
  (Java-config transcription of the retired `WEB-INF/security.xml`)
- **JPA Mappings**: `app/src/main/resources/com/wikantik/pressroom/pojos/*.orm.xml`
- **Velocity Templates**: `app/src/main/webapp/WEB-INF/velocity/templates/`
- **Logging**: `app/src/main/resources/log4j2.xml`. Its `logging.pattern`
  writes every message through `%encode{%m}{CRLF}`, so a CR or LF in a logged
  value comes out as `\r` or `\n`. One log call is one line, and a
  reader-supplied value cannot forge a line in `pressroom.log`. Stack traces
  stay multi-line and are not encoded. Every `PatternLayout` must keep that
  property (`LogPatternTest`); grepping `pressroom.log` line by line works as
  before. **Every logger reaches the console (stdout)**, the named ones
  through `asyncPressroom`, which feeds both the file and the console
  (`LogRoutingTest`): in the container `pressroom.log` is not on a volume, so
  stdout (`docker compose logs app`) is the copy operators read. The layout
  is the pattern above, not ECS JSON: SA-6's ECS layout needs
  `log4j-layout-template-json`, which is not a dependency yet.
  `WebloggerConfig` loads before logging exists, so it keeps what it found
  and logs it once the logger is up; it never prints to stdout.

### Development vs Production
- **Development**: PostgreSQL via `docker-compose.yml` (postgres only; the
  app runs via `./pressroom dev` / `spring-boot:run`, not in a container),
  theme reload enabled, caching disabled.
- **Production**: containerized end-to-end and **image-only** — the deploy
  host holds `docker-compose.prod.yml` and `.env`, nothing else. Two images
  per release tag: `ghcr.io/jakefearsd/pressroom` (WAR, themes, migrations,
  `provision.sh`, `analytics-views.sh`, `umami-views.sql`, `migrate.sh`, backup
  scripts, a PostgreSQL client) and `ghcr.io/jakefearsd/pressroom-caddy`
  (Caddy, Caddyfile baked in). A one-shot `provision` service creates the
  umami and listmonk databases, applies the migration chain, and grants
  `grafana_ro`; `app`, `umami` and `listmonk` declare
  `depends_on: { provision: { condition: service_completed_successfully } }`,
  so ordering is compose's job, not a bash script's.
  `analytics_traffic` is installed by a separate one-shot, `analytics-views`
  (`analytics-views.sh`), after `umami` starts — see Analytics for why it
  cannot live in `provision`. `deploy/deploy.sh` is just pull/up/wait.
  **Nothing may be bind-mounted from a checkout** — `ProductionComposeTest`
  fails the build if a bind mount, a `build:` stanza, or a non-loopback
  published port other than 80/443 reappears. Runbook:
  `docker_deployment.md`.

## Configuration scope
- **Runtime** (`runtimeConfigDefs.xml` → `pressroom_properties` → Admin
  Settings): `WebloggerRuntimeConfig`, DB row first, else `WebloggerConfig`.
  Hot. `JPAPropertiesManagerImpl` remembers every row's committed value
  (`RuntimeConfigSnapshot`, one projection query) and drops them whenever a
  commit writes a `pressroom_properties` row (an EclipseLink row-write listener,
  `JPAPersistenceStrategy.onRowWrite`, once the commit succeeds: a read
  between the row write and the commit loads the old rows, so only a drop
  after the commit is enough; `RuntimeConfigCacheTest` races one), so the next request reads any change, saved through
  `saveProperty`/`saveProperties` or edited in place on the managed map
  (`RuntimeConfigCacheTest`). `getProperty` returns a new unmanaged copy, which
  saves because the save merges. `getProperties` hands out managed entities to
  edit, so it begins the unit of work's transaction, and while a unit of work
  has uncommitted work `getProperty` reads through its EntityManager: its own
  edits are visible to it and nothing uncommitted is remembered. Only until
  that transaction ends, though: a managed property edited in place *after* a
  commit in the same unit of work, with no new transaction begun, is not seen
  by `getProperty` (the remembered values answer) until a later commit writes
  it. Call `getProperties` again, or save, to edit after a commit. A row written
  with SQL while the app runs is not seen until a restart (as with
  EclipseLink's shared cache before).
- **Site front door and privacy page**: `site.frontpage.page` (string, default
  empty) is the slug of the site page served at `/`; non-blank it wins over
  `site.frontpage.weblog.handle`, and a draft or deleted page falls back to
  the site directory. `site.privacy.page` is a page reference, `slug` (a site
  page) or `handle/slug` (a blog's page). Both are set from Global Config
  (`admin-ui.md`) and touch every weblog on change.
- **Startup** (`pressroom.properties` / `pressroom-custom.properties`): read once
  via `WebloggerConfig`; needs a restart.
- **Per-weblog** (`Weblog` columns, Weblog Settings) and **per-entry**.
- **Old names are refused.** <!-- rebrand:keep -->
  `LegacyConfigNames.refuse` runs first in `WebloggerConfig.loadLayers`: any <!-- rebrand:keep -->
  `ROLLER_*` environment variable or `roller.*` system property aborts <!-- rebrand:keep -->
  startup, naming each (never values). The overlay itself ignores `ROLLER_`. <!-- rebrand:keep -->
- **Environment** (`PRESSROOM_*`): highest precedence
  (`WebloggerConfig.applyEnvironmentOverrides`): strip `PRESSROOM_`, lowercase,
  `_` → `.`. A case-insensitive match on an existing key writes to its
  spelling (`PRESSROOM_DATABASE_JDBC_DRIVERCLASS` →
  `database.jdbc.driverClass`); an unmatched name is used as derived —
  required, since `mail.port` has no entry in `pressroom.properties` and
  `uploads.dir` is commented out. Production is configured this way.
- **A `PRESSROOM_*` variable for a *runtime* property reaches only the static
  layer.** `WebloggerRuntimeConfig.getProperty`/`getIntProperty` read the DB
  row alone, and a runtime row exists with its (often blank) default, so the
  variable is silently ignored unless the reader uses
  `getPropertyWithConfigFallback` (non-blank row, else static) or
  `getBooleanProperty`. A saved non-blank row beats the variable. Shipped
  once: `getAbsoluteContextURL()` read only the row, so
  `PRESSROOM_SITE_ABSOLUTEURL` never reached generated urls and they took the
  first request's host (fixed 2026-10-10, `WebloggerRuntimeConfigTest`,
  `SeoControllerTest.robotsAdvertisesTheEnvironmentSiteUrl...`). Tests set
  the static layer with `StaticConfigOverride` (restores on close).

**Promoting a startup property to runtime** (add a `<property-def>`, read via
`WebloggerRuntimeConfig`) has three traps, pinned by
`PromotedRuntimePropertyTest`:
1. The defaults in both files must match.
2. The DB row wins once it exists, so seeding must take the *startup* value
   (`JPAPropertiesManagerImpl.initialValueFor`) or an upgrade discards the
   deployer's value.
3. The call site must genuinely re-read it: not a `static final`
   (`WeblogEntry`'s anchor separator was one) or a value latched in `init()`.

Promoted: `groupblogging.enabled`, `user.hideUserNames`,
`weblogentry.title.useUnderscoreSeparator` (`comment.throttle.enabled` went
with comments, W1). Throttle *sizing* (threshold/interval/maxentries)
sizes a fixed cache and stays startup-scoped; only the switch is hot.

**Deliberately NOT promoted** (decide first):
- `weblogAdminsUntrusted` — would put "disable HTML sanitization" on a
  form. (`passwds.encryption.enabled` is gone entirely; see Passwords.)
- `rememberme.enabled`, `themes.reload.mode`, `users.firstUserAdmin` —
  structurally boot-scoped.
- `search.enabled` — gates whether a Lucene index is built.
