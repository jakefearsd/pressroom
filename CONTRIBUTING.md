# Contributing to Pressroom

This guide gets a new developer from a fresh clone to a change that passes
the build. Pressroom is maintained independently. It is a fork of
[Apache Roller](https://roller.apache.org), and is not affiliated with or <!-- rebrand:keep -->
endorsed by the Apache Software Foundation.

The project's working rules are in [`CLAUDE.md`](CLAUDE.md). It is written for
AI coding agents, but the rules apply to everyone, and its "Hazards that fail
silently" section is worth reading before your first change. The subsystem
notes are indexed in [`docs/dev/README.md`](docs/dev/README.md).

---

## 1. Set up

| You need | Notes |
|---|---|
| JDK 25 | Exactly 25: the enforcer allows `[25,26)`. The project stays on 25 (LTS) until Java 29. |
| Maven 3.9+ | |
| Docker | Tests start PostgreSQL 18 in Testcontainers; `./pressroom dev` runs it in compose. |
| `cwebp` (optional) | WebP renditions. Leave it off `PATH` when you measure coverage (see section 5). |

You do not install Node. Maven downloads a pinned Node into `app/frontend/node/`
and builds the JavaScript bundles itself. Never run `npm` by hand
([build-and-ci.md](docs/dev/build-and-ci.md), "Frontend build").

```bash
mvn -DskipTests=true install   # build once
./pressroom dev                # dev server at http://localhost:8083/, user admin
cat .pressroom-dev-secret      # the generated admin password
```

The dev server runs at the root context path. No code may assume a context
path, so to reproduce a prefixed deployment use
`DEV_CONTEXT_PATH=/pressroom ./pressroom dev`.

## 2. Find your way around

```
app/src/main/java/com/wikantik/pressroom/
├── boot/            Spring Boot entry point and Java config (servlets, security, MVC)
├── business/        managers behind the Weblogger facade; jpa/, search/ (Lucene), themes/, plugins/
├── pojos/           JPA entities; data and invariants only, no business-tier imports
├── config/          static and runtime configuration
├── ui/controllers/  Spring MVC controllers for the admin UI (*.rol)
├── ui/core/         filters, security (incl. Google sign-in), session
├── ui/rendering/    blog rendering: servlets, Velocity models, caches
├── ui/restapi/      /api/v1 and the MCP endpoint
└── util/
app/src/main/webapp/
├── WEB-INF/jsps/    admin UI (JSP/JSTL, homegrown tiles)
├── WEB-INF/velocity/ shared Velocity macros and feed templates
└── themes/          journal, portfolio, travel, frontpage
bin/db/migrations/   V<NNN>__*.sql schema migrations
```

Four ideas explain most of the code:

1. **One facade.** `Weblogger` gives access to every manager (`WeblogManager`,
   `WeblogEntryManager`, `UserManager`, `MediaFileManager`, `IndexManager`, ...).
   Container-managed classes take `@Lazy Weblogger` in their constructor, and the
   `@Lazy` matters. There is no static service locator.
2. **One authorization path.** `PressroomHandlerInterceptor` serves both the JSP
   UI and `/api/v1`, and it checks only the action weblog. Every by-id lookup
   therefore goes through `WeblogOwnership` / `BaseController.lookup*`. The API
   answers 404, never 403, for anything the caller may not see.
3. **One content boundary.** Entry text goes through shortcodes, then
   commonmark, then `HTMLSanitizer`. The sanitizer is the only defence against
   hostile HTML.
4. **One schema history.** The schema changes only through a new
   `bin/db/migrations/V<NNN>__*.sql`. `migrate.sh`, the install wizard and the
   test harness all apply the same files.

More detail: [architecture.md](docs/dev/architecture.md).

## 3. Make a change

**Read the area's notes first.** Each subsystem has a file under `docs/dev/`
that records its rules and the bugs it has already had. The table in
[`docs/dev/README.md`](docs/dev/README.md) says which file covers what.

**Test first.** All development is test-driven:

1. Write the test and run it: `mvn -pl app test -Dtest=MyThingTest`.
2. Watch it fail for the reason you expect. A test you never saw fail has not
   been shown to test anything.
3. Write the least code that makes it pass, then refactor.

There is one exception: a characterisation test written before a refactoring
passes immediately against the old code. Say so in its javadoc.

Testing conventions: tests use the real tier (`TestUtils.weblogger()`) on a
real database. Fixtures are created with `TestUtils.setupX(...)` and removed
in `@AfterEach`, because nothing truncates tables. Rendering tests clear the
render caches. The full list is in
[build-and-ci.md](docs/dev/build-and-ci.md), "Testing Commands".

**Larger work gets a spec.** A feature big enough to need design goes in
`docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`. The spec states the
acceptance criteria concretely enough to write a test against. The plan that
implements it goes in `docs/superpowers/plans/`.

### Recipes

| To... | Do this | Read |
|---|---|---|
| Change the schema | Add the next `bin/db/migrations/V<NNN>__name.sql`. It must be idempotent. Never edit a migration that has run outside local dev; fix it with a follow-up migration. | [database.md](docs/dev/database.md), `bin/db/migrations/README.md` |
| Add an admin page | Controller in `ui/controllers/`, JSP in `WEB-INF/jsps/`, a route with its CSS marker in the browser suite's `it-selenium/.../support/Routes.java`, and message keys in `ApplicationResources.properties` | [admin-ui.md](docs/dev/admin-ui.md), [design-system.md](docs/design/design-system.md) |
| Change a theme | Edit `app/src/main/webapp/themes/<theme>/`. Velocity prints a reference to a deleted member as literal text, without an error. | [themes-and-templates.md](docs/dev/themes-and-templates.md) |
| Add an API operation | `ui/restapi/`, plus the OpenAPI document and the public reference in `docs/api/README.md`. Form/API parity is tested. | [api.md](docs/dev/api.md) |
| Add a runtime setting | `runtimeConfigDefs.xml` (a bare `--` in an XML comment breaks it silently) | [configuration.md](docs/dev/configuration.md) |
| Add a UI string | English key in `ApplicationResources.properties`. The i18n ratchet tests must stay empty. | [quality-gates.md](docs/dev/quality-gates.md) |

### Mistakes that compile and pass tests

These have all shipped at least once. The full list is in `CLAUDE.md`.

- `@RequestParam` / `@PathVariable` without an explicit name: the build has no
  `-parameters`, so it passes unit tests and fails at runtime.
- Entry titles are stored escaped (emit `$entry.title` bare); page titles are
  raw (always `$utils.escapeHTML`). Author-controlled EL in JSPs goes through
  `fn:escapeXml`.
- A rendered blog page expires only when `weblog.lastModified` changes.
- Confirmations use `data-confirm`, never an inline `onclick="return confirm(...)"`.
- A `catch` that only logs is a bug in a validation path, because its caller
  reads "no errors" as "passed". Fail closed.
- SLF4J: a `Throwable` goes last, with no `{}` placeholder for it.

## 4. Run the checks

```bash
mvn -pl app test -Dtest=MyThingTest   # one class while you work
mvn clean install                     # unit suite and every gate, before you call it done
mvn verify -Pit                       # browser suite against the packaged WAR
```

Two builds must never run in one working tree at the same time, because they
share `app/target/`.

Routing or URL changes also run the browser suite under a context path:
`mvn verify -Pit -Dit.context.path=pressroom`. See
[browser-its.md](docs/dev/browser-its.md).

## 5. The gates

`mvn clean install` fails on any of these:

| Gate | Rule |
|---|---|
| JaCoCo | Line and branch floors for the whole build, plus stricter floors for the rendering and security packages. Floors only move up, and are measured without `cwebp` on `PATH`. |
| Diff coverage | About 90% of changed lines (`bin/check-diff-coverage.sh`), checked in CI |
| PMD 7, CPD, SpotBugs + find-sec-bugs | Zero findings. Suppress a one-off at the site with a reason. Rule families are excluded only in `config/`, with a reason. |
| javac `-Xlint:all` | Zero warnings, in test code too |
| i18n ratchets | Offender sets stay empty |

`bin/quality-report.sh [Rule]` lists the sites behind a static-analysis
finding. When a gate fails, fix the code: don't lower a floor, widen an
exclusion, or add a test with no assertions just to raise coverage. Details:
[quality-gates.md](docs/dev/quality-gates.md).

## 6. Commit, release, deploy

- Work on `master`. This is a solo-developer repository with no feature
  branches unless asked for.
- Commit messages say what changed and why, in plain sentences
  (`git log --oneline` shows the house style).
- CI (`.github/workflows/main.yml`) runs the unit suite and diff coverage on
  every push. Browser tests run nightly and on demand. Pushing to `master`
  publishes nothing.
- **Release:** `bin/release.sh X.Y.Z` verifies, bumps the versions, commits and
  tags. Pushing the `vX.Y.Z` tag publishes the container images. Only push it
  on purpose.
- **Deploy:** `bin/deploy-host.sh <host> [VERSION]` backs up, upgrades and checks
  the running image. The operator runbook is
  [docker_deployment.md](docker_deployment.md).

## Upstream

If you want to contribute to Apache Roller itself rather than this fork, see <!-- rebrand:keep -->
[How to contribute to Apache Roller](https://cwiki.apache.org/confluence/x/2hsB). <!-- rebrand:keep -->
