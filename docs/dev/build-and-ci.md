# Build, testing and CI

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Git blame

Run `git config blame.ignoreRevsFile .git-blame-ignore-revs` once per
checkout: the Phase 1 package move (to `com.wikantik.pressroom`) is recorded
there, so blame reaches the real authors instead of the move commit.

## Frontend build

- Maven builds the editor's CodeMirror bundle: `frontend-maven-plugin`
  fetches a pinned Node `v24.22.0` into `app/frontend/node/`, then `npm ci`
  + esbuild at `generate-resources` (before every compile); never run `npm`
  by hand.
- Both bundle URLs carry `?v=<application version>` (head.jsp via `Colophon`;
  weblog.vm via `$config.rollerVersion`): the names are unversioned and no
  Cache-Control is sent, so a release must change the URL.
- Output `app/src/main/webapp/roller-ui/scripts/roller-editor.js`
  (git-ignored, regenerated) lives under `src/main/webapp`, not
  `target/classes/static`: with `DispatcherServlet` on `*.rol` only, neither
  classpath static resources nor `WEB-INF/classes` nesting can serve it; only
  that path both `maven-war-plugin` and `spring-boot:run` serve.
- `-DskipTests` skips the bundle's `npm test` (`test` phase, `skipTests`
  property), not the build.
- **After bumping `nodeVersion`, `rm -rf app/frontend/node` on any existing
  checkout.** The plugin unpacks the new tarball over the old one without
  clearing it, leaving npm's own `node_modules` mixed across versions;
  the symptom is `npm ci` dying with "Class extends value undefined is not
  a constructor". CI and the Docker build start empty and never see it.
- `mvn -pl app -DskipTests clean generate-resources`: cold ~8s, warm ~1.6s
  — negligible against `verify`.
- `docker build` starts from a fresh `maven` image (no `app/frontend/node/`
  or `node_modules/`) so always pays the cold cost and needs
  `nodejs.org`/`registry.npmjs.org` (docker_deployment.md).
- **Bundle size ruling**: `roller-editor.js` is ~575 KB minified / ~192 KB
  gzipped, served only to the two login-gated editors; accepted — dropping
  fenced-code highlighting (the bulk) costs a feature for bytes nobody else
  pays.
- **A second bundle, `roller-content.js`, serves readers** (entry
  `app/frontend/src/content.js`, same directory, same git-ignore, pinned by
  `EditorBundlePomTest`): highlight.js `11.12.0` core with 20 explicitly
  registered grammars, copy buttons, line numbers, heading anchors. ~83 KB
  minified / ~27 KB gzipped, injected by the content-asset macro only on a
  page with a code block or content heading to enhance. Its pure halves
  (`content-core.js`, `highlighter.js`) are covered by
  `test/content.test.mjs`. Being public, it is the one file under
  `/roller-ui` that `ControlPlaneHostFilter` leaves on a custom domain. See
  `themes-and-templates.md`, "Content assets".
- Adding a dependency means regenerating `package-lock.json` with the
  plugin's own Node, not a system one:
  `cd app/frontend && node/node node/node_modules/npm/bin/npm-cli.js install <pkg>@<x.y.z> --save-exact`
  (after one `generate-resources` has downloaded it).

## Testing Commands
```bash
mvn test                        # all tests
mvn test -Dtest=TestClassName   # one class
mvn clean test && mvn jacoco:report -pl app  # coverage: app/target/site/jacoco/index.html
```

- Tests need Docker: `PressroomTestBootstrap` (JUnit `LauncherSessionListener`)
  starts one PostgreSQL container per JVM, schema from the real
  `bin/db/migrations` chain.
- Fixtures: `TestUtils.setupX(...)`, removed in `@AfterEach`
  (`teardownWeblog`/`teardownUser` + `endSession(true)`) — nothing truncates
  tables.
- Render caches are per-JVM singletons: rendering tests call
  `CacheManager.clear()` in `@BeforeEach` (`RenderingTestSupport`).
  **That does not reach `WeblogPageCache`**, and
  `RenderingTestSupport.anonymousGet` sets `skipCache=true` on every request.
  A test about the page cache must therefore:
  - remove that attribute;
  - clear `RenderCaches.forPage(false)` and `forPage(true)` itself;
  - plant a sentinel only after the clock has passed the weblog's stored
    `lastModified`. `saveWeblog` stamps "now", and a same-millisecond change
    counts as newer (`PageServletCachingTest`, whose cache tests once passed
    without touching the cache).
- A servlet over a faulty collaborator: mock `Weblogger`, delegate only the
  managers the servlet uses to `TestUtils.weblogger()`, and build the servlet
  directly (`new XServlet(weblogger)` plus
  `init(new MockServletConfig(PressroomContext.getServletContext()))`). No
  production seam is needed.
- One `WebloggerConfig` key for one test: `StaticConfigOverride.set(key,
  value)` in try-with-resources (it restores on close). Several `var _ = ...`
  resources can share one `try`.
- `new WeblogCategory(weblog, ...)` attaches itself to the weblog's collection
  at once. A test expecting `saveWeblogCategory` to refuse a duplicate must
  discard the session (`TestUtils.endSession(false)`) before the next flush, or
  the refused category is persisted and the test fails far from its cause.
- MockMvc standalone tests have no Hamcrest on the classpath, so
  `status().is(int)` and `content().json(...)` fail with
  `NoClassDefFoundError`. Use `isXxx()` or an `andExpect(r -> assertEquals(...))`
  lambda.
- javac runs with `failOnWarning`, tests included. Write an unused
  try-with-resources variable as `_` (`try (var _ = mockStatic(X.class))`), and
  use no deprecated API.
- `TestUtils.weblogger()` is the real tier (one per JVM;
  `TestUtils.setupWeblogger()` via `SpringWebloggerProvider.standalone()`);
  hand it, or `MockWeblogger.create()`'s mock, to the class under test by
  constructor/`init`/field; no global, no `mockStatic`.
- `MockWeblogger.attached()` also attaches the mock's `PropertiesManager` to
  `WebloggerRuntimeConfig` for runtime-config reads (`RuntimeConfigAttachment`:
  try-with-resources form); both restore the previous attachment
  (DB-backed tests set the real tier).

## Unit-suite flakes root-caused

Each entry: symptom, mechanism, fix, and how it was made to fail on demand.
A flake goes here once it is explained; a rerun that passes explains nothing.

- **`UserQueryAndRoleTest.listingIsPageable` / `listingIsTriStateOnEnabled`**
  ("an offset must move the window", "got: []"), fixed 2026-10-03.
  `JPAUserManagerImpl.getUsers` turned a null `endDate` into "now" and
  compared `dateCreated < now`, so an account stamped in the same
  millisecond as the listing was not listed. Warm, the class's `setUp` runs
  in ~1 ms, so the newest fixture often shared the query's millisecond:
  hidden from one query, visible to the next (which shifts an offset window
  onto the row the first page already returned). A production bug: every
  caller passes null and the interface documents null as "no bound". Now a
  null date adds no predicate, as `JPAWeblogManagerImpl.getWeblogs` already
  did. Reproduced by looping setUp + `listingWalksEveryUser...` + the two
  tests in one JVM (the class's own method order): 100 of 900 failed before,
  0 of 900 after; `aListingWithNoEndDateIncludesAnAccountStampedAtOrAfterNow`
  pins it deterministically with a `dateCreated` just ahead of the clock.

- **`SearchIndexQueryTest.aSearchCanBeNarrowedToACategory`** (CI
  35431937572, "got: []") **and `rebuildingAWeblogsIndexRestoresIt`**,
  fixed 2026-10-03. `addEntryIndexOperation` only schedules the write, and
  `setUp` returned with it still in flight 194-199 times in 200 (measured
  by opening the index directly at the end of `setUp`), so each test's first
  search depended on queueing behind that write. Even queued, it could lose:
  `WriteToIndexOperation` invalidated the shared `IndexReader` *after*
  releasing the write lock, so a search taking the read lock in that window
  answered from the reader cached before the write (here, the previous
  test's). `rebuilding...`'s own failure was not reproduced; the same
  stale or premature empty search would let its "index is empty" wait pass
  before the removal had run, leaving removal and rebuild to race on the
  unordered thread pool. Fixed in production (reset before
  unlock) and in the test (`setUp` waits for its own entries). Reproduced
  naturally once in 240 with the JVM pinned to one core (`taskset -c 15`),
  the test after `rebuilding...` as in CI; `SharedReaderHandoffTest` held
  the window open deterministically (fails every run without the fix). That
  test and the read lock went in 2026-10, when searches stopped waiting for
  writes at all (`SearchDuringWriteTest`; docs/dev/architecture.md, Search).
  Residual: the hit filter dropped an entry with `pubTime == now` (strict
  `before`), so publish-then-search in one millisecond found nothing; now
  `!after(now)` (`anEntryPublishedAtExactlyNowIsFound`, also `SearchResultsModel`).

## CI: three tiers, and nothing publishes on a push

`.github/workflows/main.yml`, split by cost:

- **Every push and PR**: `build-test` — unit suite + diff-coverage gate, ~3
  minutes. It **installs `cwebp`** (`webp` package) first, load-bearing
  because `CwebpEncoder` is feature-detected: without the binary
  `CwebpEncoderTest`, `MediaFileTest` and `MediaResourceServletRenderingTest`
  silently skip their WebP arms (until 2026-09-09 the shipped WebP path had
  no executing coverage); `ItCiWorkflowTest` pins the step. A new
  `assumeTrue` on a binary must make CI provide it in the same commit — an
  assumption that never holds is worse than a missing test.
- **Nightly (04:00 UTC), on a PR, or on demand**: `integration-test`, the
  browser ITs, ~16 minutes — off the push path so a re-runnable flake (the
  GalleryIT upload race) isn't per-commit red mail. Run `mvn verify -Pit`
  before a release.
- **CodeQL** (`codeql-analysis.yml`): weekly + `workflow_dispatch`.
- **Publishing happens only on a `v*.*.*` tag** (`release.yml`): pushes
  `ghcr.io/jakefearsd/roller:<version>` and
  `ghcr.io/jakefearsd/roller-caddy:<version>` (plus `:latest`,
  `:sha-<short>`), extracts the WAR from the `roller` image into a
  GitHub Release with the deploy bundle (`docker-compose.prod.yml`,
  `env.example`, `deploy.sh`). The asset is `deploy/.env.example` renamed: a
  release asset whose name starts with a dot would not download as itself.
- `docker-compose.prod.yml` requires `IMAGE_VERSION` in `.env` (no floating
  default) so a deploy always names a tagged release.
- Local testing of an untagged tree is a `docker build` of
  `Dockerfile`/`deploy/caddy/Dockerfile` (docker_deployment.md);
  `deploy.sh` only pulls — `docker-compose.prod.yml` has no `build:` stanza.

**Releasing and deploying** is two commands; each script's header is its manual.

```bash
bin/release.sh 0.2.3 --push        # verify, bump, commit, tag, push, wait for release.yml
bin/deploy-host.sh docker1 --url https://blog.wikantik.com/wikantik/
```

- `bin/release.sh` refuses a dirty tree, a branch other than master, a tree behind
  origin, an existing tag, or a version not above the current one. It then runs
  `mvn clean install` and the browser suite at both context paths (`--skip-verify`
  when they already passed on this commit). Then it moves the four version strings
  (the first `<version>` in each pom, and `IMAGE_VERSION` in `deploy/.env.example`),
  commits `release: X.Y.Z` and tags. Without `--push` it stops and prints the push;
  no agent passes `--push`.
- `bin/deploy-host.sh HOST [VERSION]` copies `deploy/upgrade.sh` to the host's
  deploy directory and runs it there. `upgrade.sh` downloads the release files first
  and takes a backup; either failing stops it with nothing changed. It then keeps
  `.env` and the replaced files as `*.bak-<UTC stamp>`, sets `IMAGE_VERSION`, runs
  `deploy.sh` and checks that the app runs the new image. An exported
  `IMAGE_VERSION` beats `.env` and would leave the old image up, healthy.
- Tests: `ReleaseScriptTest`, `DeployHostScriptTest`, `UpgradeScriptTest`. They run
  the real scripts against stub `mvn`/`gh`/`ssh`/`docker`, a scratch git repo and a
  `file://` release.

**The 403 on `POST /roller-ui/admin/createUser!save.rol` (mechanism
established 2026-10-04; fixed).** Seen 2026-08-19 and 2026-09-14 in
`ErrorCasesIT.aDuplicateUserNameIsRefused` (via `BrowserHealth`); the same
POST reappeared in disguise in nightlies 35203024997 (09-17) and 36231322274
(09-26), which failed `ErrorCasesIT.anUnknownWeblogIsNotFound` -- a test that
never touches the browser, and the one that runs straight after it -- with
"The browser is on .../createUser!save.rol but this recorder saw no network
traffic at all". The test clicked Save, called `settle()`, asserted that no
success banner showed, and signed out. Neither the click nor `settle()` waits
for a submission that has not started yet, so the assertion read the FORM it
had typed into (an absence, so it passed), `logout()` invalidated the
session, and the POST went out afterwards with none: `CsrfFilter - Invalid
CSRF token found`, 403. Where that 403 surfaced was timing only: in the same
test's health check, or as a navigation committed during the next test,
whose recorder was attached after all of its traffic. Not a server defect:
refusing a POST with no session is correct. Reproduced on demand by
repeating the pair with the submission held back 20-135 ms: 14 of 144
failed, with both CI messages, and the app log held 70 `Invalid CSRF token`
refusals; with `PressroomIT.clickAndAwaitNewPage` (wait for the old document
to go stale), 0 and 0. The `MultiUserJourneyIT` `Element not found {#entry}`
nightlies are the same defect. `docs/dev/browser-its.md`, "A click is not a
barrier", has the family and `IT_LATE_CLICK_MS`.

The general rule stands: on the next unexplained 403, read
`it-selenium/target/it-work/roller-<run id>/roller.log` (the IT app runs
`CsrfFilter`, `ExceptionTranslationFilter` and the interceptor at DEBUG for
this; every named logger also writes to the console, so the run's stdout log
beside it carries the same lines), and capture what else was in flight from the failsafe report
timestamps BEFORE rerunning anything -- an isolated rerun overwrites
`it-selenium/target/failsafe-reports`.
