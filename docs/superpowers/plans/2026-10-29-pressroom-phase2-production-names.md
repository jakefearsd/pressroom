# Pressroom Phase 2: Production and Contract Names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename every machine-read `roller` name (layer 2 of the spec) to its `pressroom` form, with a fail-closed guard for old configuration names, a permanent `/roller-ui/` redirect, a table-renaming migration, and a scripted, rehearsed docker1 cutover with a rollback.

**Architecture:** Strict Velocity reference checking lands first, then the old-name inventory learns to tell layer 2 (machine names) from layer 3 (prose), so each later task is a mechanical rename whose completeness the inventory proves. The rename runs one name family per task, serially in the main tree. The host move (`~/roller` → `~/pressroom`, copied volumes, renamed database and role) is a new one-shot script, `deploy/cutover-pressroom.sh`. It is tested with stubs and then rehearsed for real on a restored copy of production data. Nothing is released or deployed in this phase. The single 0.5.0 release and the docker1 deploy follow Phase 3.

**Tech Stack:** Java 25, Spring Boot 4.1, Velocity 2, EclipseLink/JPA, PostgreSQL 18, Bash, Docker Compose, JUnit 5, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-10-29-pressroom-rebrand-design.md` (Phase 2, and "What keeps the old name").

## Global Constraints

- Everything in CLAUDE.md applies:
  - test-first;
  - JaCoCo LINE 0.9590 / BRANCH 0.9100 plus the two package rules;
  - PMD/CPD/SpotBugs at zero;
  - `-Xlint:all` with `failOnWarning`;
  - one build at a time (`pgrep -f "[s]urefirebooter.*source/roller"`);
  - agents may commit, never push.
- Serial, in the main tree on `master`, with no worktrees. Every task lowers the inventory `BASELINE`, so parallel branches would conflict on that one map.
- The name mapping. Unless a task says otherwise, each rename follows it exactly.

| Old | New |
|---|---|
| env prefix `ROLLER_` | `PRESSROOM_` |
| system properties `roller.*` (`roller.custom.config`, `roller.log.dir`, `roller.lifecycle.enabled`, `roller.rendition.cwebp.force`, ...) | `pressroom.*` |
| `roller.properties`, `roller-custom.properties`, `roller-boot-dev.properties`, `roller-version.properties` | `pressroom.properties`, `pressroom-custom.properties`, `pressroom-boot-dev.properties`, `pressroom-version.properties` |
| config keys `rendering.rollerRequestMappers`, `rendering.rollerRendererFactories`, `rendering.weblogMapper.rollerProtectedUrls` | `rendering.pressroomRequestMappers`, `rendering.pressroomRendererFactories`, `rendering.weblogMapper.pressroomProtectedUrls` |
| database `rollerdb`, role `roller`, JNDI `jdbc/rollerdb` | `pressroom`, `pressroom`, `jdbc/pressroom` |
| tables, constraints, indexes, sequences `roller_*` | `pressroom_*` |
| `/roller-ui/` and webapp dir `roller-ui/` | `/pressroom-ui/`, `pressroom-ui/` |
| API token prefix `rlr_` | `prs_` |
| MCP `serverInfo("roller", ...)` | `serverInfo("pressroom", ...)` |
| loggers `roller.*`, file `roller.log`, appenders `roller`/`asyncRoller` | `pressroom.*`, `pressroom.log`, `pressroom`/`asyncPressroom` |
| CSS `roller-*`, `data-roller-*`, JS `roller*`/`window.roller*`, localStorage `roller.draft.v*` | `pressroom-*`, `data-pressroom-*`, `pressroom*`, `pressroom.draft.v*` |
| Velocity `$roller*`, `resource.loader.roller`, `roller-custom.vm`, `rollerConfig.tld` | `$pressroom*`, `resource.loader.pressroom`, `pressroom-custom.vm`, `pressroomConfig.tld` |
| `roller.war`, `bin/roller-api`, `./roller`, `.roller-dev-secret` | `pressroom.war`, `bin/pressroom-api`, `./pressroom`, `.pressroom-dev-secret` |
| images `ghcr.io/jakefearsd/roller`, `roller-caddy` | `ghcr.io/jakefearsd/pressroom`, `pressroom-caddy` |
| compose volumes `roller-*`, host dir and project `roller` | `pressroom-*`, `pressroom` |
| IT alternate context path `roller` (`-Dit.context.path=roller`) | `pressroom` |
| `getRollerSession`, `ROLLER_SESSION` and every other lowerCamel/UPPER identifier | the same with `Pressroom`/`PRESSROOM`/`pressroom` |

  Any other machine form follows the case rule: `roller` becomes `pressroom`, `ROLLER` becomes `PRESSROOM`, and `Roller` inside an identifier becomes `Pressroom`. The separator stays the same.
- The prose word "Roller" (UI text, javadoc sentences, messages) is Phase 3. Do not reword prose in this phase, except where a rename changes a name the prose quotes.
- What keeps the old name. Each kept line carries the marker `rebrand:keep` in a comment, or is in a kept file:
  - `RollerUiRedirectFilter`'s `/roller-ui` prefix (Task 5);
  - `LegacyConfigNames`' `ROLLER_` and `roller.` (Task 3);
  - `DatabaseInstaller`'s upstream-schema probe for `roller_user`/`userrole`;
  - migration `V041__pressroom_rename.sql`, a kept file;
  - `deploy/cutover-pressroom.sh` and `deploy/rollback-pressroom.sh`, which must name the old host layout;
  - tests that feed those old names in.
- Each rename task also applies its mapping to the tracked docs (`CLAUDE.md`, `README.md`, `docker_deployment.md`, `docs/dev/**`, `docs/api/**`), so commands and names in docs stay true. It never touches `docs/superpowers/**`, migrations `V001`-`V040`, `LICENSE.txt` or `NOTICE.txt`.
- Do not search-and-replace `roller` blindly. The tree is full of `controller`. Every pattern uses the inventory's boundary: `(?<![A-Za-z])roller`, `ROLLER`, `(?<=[a-z])Roller`.
- Do not bump the version. The 0.5.0 release is cut after Phase 3.

## Review Focus

1. **A `/roller-ui/` request at a non-root context path, with a query string, or with POST.**
   - Expected: a permanent redirect to the same path and query under `/pressroom-ui/`, at the same context path.
   - Status codes: 301 for GET/HEAD and 308 for every other method, so a form POST is not turned into a GET.
   - Tested in Task 5 (unit and browser IT, at `/` and `/pressroom`).
2. **A docker1 `.env` with comments, blank lines, quoted values, `export` prefixes, or a value that itself contains "roller".**
   - Expected: only key names and the three database values change. Comments and values are otherwise byte-identical.
   - No value is ever printed: keys whose value still mentions roller are named, not shown.
   - Tested in Task 10.
3. **A `ROLLER_*` variable with an empty value, or a lookalike (`MY_ROLLER_X`, `ROLLERX`, `x.roller.y`).**
   - Expected: the empty one still refuses startup; the lookalikes are ignored.
   - The refusal names each variable and never shows its value.
   - Tested in Task 3.
4. **The `roller` role's password stored as MD5.**
   - Postgres clears an MD5 password on rename, because the role name is the salt.
   - Expected: the cutover detects the `md5` prefix and resets the password from `.env`. The login is then verified either way before anything else starts.
   - Tested in Task 10 and checked for real in Task 12.
5. **V041 run twice, or on a database where only some tables were already renamed.**
   - Expected: idempotent, no error, ends with no `roller_*` relation or constraint, and every row intact.
   - Tested in Task 4.

---

### Task 1: Strict Velocity reference checking

Spec Phase 0 item 6, strict form (deferred to here by the Phase 0 plan). It must land before any Velocity context name changes (Task 8b).

**Files:**
- Create: `app/src/main/java/com/wikantik/pressroom/ui/rendering/velocity/InvalidReferenceRecorder.java`
- Modify: `app/src/main/java/com/wikantik/pressroom/ui/rendering/velocity/PressroomVelocity.java` (`buildEngine`)
- Create: `app/src/test/java/com/wikantik/pressroom/ui/rendering/velocity/InvalidReferenceRecorderTest.java`
- Create: `app/src/test/java/com/wikantik/pressroom/ui/rendering/StrictReferences.java` (test helper)
- Modify: `ThemeReferenceLeakTest`, `servlets/FeedServletRenderingTest`, `servlets/PreviewShellRenderingTest`, `FallbackPageTitleTest`, `servlets/RenderErrorStatusTest`, `RenderedTemplateCoverageTest` (all under `app/src/test/java/com/wikantik/pressroom/ui/rendering/`)
- Delete: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/noop_decorator.vm`, and the dead `runtime.log.logsystem.class` line in `app/src/main/webapp/WEB-INF/velocity.properties`

**Interfaces:**
- Produces:
  - `public final class InvalidReferenceRecorder implements org.apache.velocity.app.event.InvalidReferenceEventHandler`
  - `public static SortedSet<String> recorded()`, which returns a copy;
  - `public static void clear()`;
  - `static final int CAP = 1000`.
  - Each entry is `templateName + ": " + reference`, the reference as Velocity reports it (`$foo`, `$!foo`, `$obj.missing`).
  - Test helper: `StrictReferences.assertRecordedExactly(String label, Set<String> expected)`. It fails with the sorted difference both ways.

- [ ] **Step 1: Write the failing unit test** `InvalidReferenceRecorderTest`:
  - Build a bare `VelocityEngine` with `event_handler.invalid_references.class` set to the recorder, and `event_handler.invalid_references.quiet`, `.null` and `.tested` all `true`.
  - Evaluate a template named `t.vm`: `$nope #if($tested)x#end $!quiet $obj.missing`, with `obj` set to `new Object()` in the context.
  - Tests:
    - `recordsLiteralQuietTestedAndMissingMemberReferences`: `recorded()` contains `t.vm: $nope`, `t.vm: $tested`, `t.vm: $!quiet` and `t.vm: $obj.missing`.
    - `clearEmpties`.
    - `stopsGrowingAtTheCap`: evaluate 1,001 distinct undefined references, then `recorded().size() == CAP`.
    - `recordsEachDistinctEntryOnce`: the same template evaluated twice gives the same set.
- [ ] **Step 2: Run it and watch it fail.** Run `mvn -pl app test -Dtest=InvalidReferenceRecorderTest`. Expected: compilation failure, because `InvalidReferenceRecorder` does not exist.
- [ ] **Step 3: Implement the recorder.**
  - A `ConcurrentSkipListSet`, filled only while `size() < CAP`.
  - Log at DEBUG on first sight.
  - Return `null` from every callback, so rendering is unchanged.
  - In `buildEngine`, set the four properties from Step 1 on `velocityProps` before `init()`.
- [ ] **Step 4: Run the unit test.** It should pass.
- [ ] **Step 5: Wire the strict assertion into every rendering test named in `RenderedTemplateCoverageTest.RENDERED_BY`.**
  - Call `InvalidReferenceRecorder.clear()` before the renders.
  - Call `StrictReferences.assertRecordedExactly(label, EXPECTED)` after them.
  - `EXPECTED` is a `Set.of(...)` per test, or a `Map` keyed by theme in `ThemeReferenceLeakTest`, holding what today's shipped templates legitimately leave unresolved (optional model values tested in `#if`, `$!` quiet refs).
  - Give each entry a short comment saying why it is legitimate.
  - An entry that would print as literal text is a bug to fix, not an allowlist entry, but `ThemeReferenceLeakTest` already proves there are none.
  - Run each class twice and confirm the sets are identical both times. Template names must be stable; if a stored-template name embeds a random id, normalise it in the helper.
- [ ] **Step 6: Prove it bites.**
  - Append `$pressroomNoSuch #if($pressroomNoSuchTested)x#end` to `themes/journal/weblog.vm` and run `ThemeReferenceLeakTest`.
  - Expected: FAIL naming both entries for the journal theme.
  - Revert with `git checkout`, and record the failure text in the `RenderedTemplateCoverageTest` javadoc "Proven to bite" paragraph. Its "Phase 2 adds a strict invalid-reference render pass" sentence becomes a statement that it exists.
- [ ] **Step 7: Delete `noop_decorator.vm`.** Set `KNOWN_UNRENDERED` to `Set.of()` and delete the dead velocity key.
- [ ] **Step 8: Run all rendering tests.** Run `mvn -pl app test -Dtest='*Rendering*Test,ThemeReferenceLeakTest,FallbackPageTitleTest,RenderErrorStatusTest,RenderedTemplateCoverageTest,InvalidReferenceRecorderTest'`. Expected: PASS.
- [ ] **Step 9: Commit:** `test(rebrand): strict Velocity reference pass over every shipped template`.

### Task 2: Inventory tells layer 2 from layer 3

**Files:**
- Modify: `app/src/test/java/com/wikantik/pressroom/build/OldNameInventoryTest.java`

**Interfaces:**
- Produces:
  - Kinds, tried in this order: `PACKAGE`, `CLASS`, `TOKEN_PREFIX`, `MACHINE`, `PROSE`.
    - `MACHINE = ROLLER|(?<![A-Za-z])roller|(?<=[a-z])Roller`
    - `PROSE = Roller`, whatever `Roller` is left after `MACHINE` is masked.
    - `OTHER` is removed.
  - `static final int LAST_OLD_NAME_MIGRATION = 41`: migrations `V001`-`V041` are kept files.
  - A line containing `rebrand:keep` contributes nothing to any count. Path counting is unchanged.
  - `static final Set<String> LAYER_TWO_KINDS = Set.of("TOKEN_PREFIX", "MACHINE", "PATH-TOKEN_PREFIX", "PATH-MACHINE")`.
  - `static final Set<String> LAYER_TWO_AREAS = Set.of("main-java", "main-resources", "test", "it", "build", "deploy", "other")`.
  - `static Map<String, Integer> layerTwoLeftovers(Map<String, Integer> counts)`. Task 11 uses it.

- [ ] **Step 1: Update and add the failing tests.**
  - `countsOnlyTheBrand` asserts `Map.of("MACHINE", 3)` for `asyncRoller ROLLER_DB $rollerContentScope`, and `Map.of("PROSE", 1)` for `"The Roller project"`.
  - `keepMarkedLinesAreNotCounted`: `count("a roller // rebrand:keep\nb roller")` is `Map.of("MACHINE", 1)`.
  - `layerTwoLeftoversPicksOnlyLayerTwoKeys`: `layerTwoLeftovers(Map.of("MACHINE/main-java", 2, "PROSE/main-java", 5, "MACHINE/docs", 3, "PATH-MACHINE/deploy", 1, "TOKEN_PREFIX/test", 0))` equals `Map.of("MACHINE/main-java", 2, "PATH-MACHINE/deploy", 1)`.
  - `migrationsUpToTheRenameAreKept`: `keepsOldName(Path.of("bin/db/migrations/V041__pressroom_rename.sql"))` is true; for `V042__x.sql` it is false.
- [ ] **Step 2: Run them and watch them fail.** Run `mvn -pl app test -Dtest=OldNameInventoryTest`. Expected: FAIL. The helpers do not exist, and `OTHER` is still counted.
- [ ] **Step 3: Implement the changes.**
  - Apply the line mask before counting kinds.
  - Raise the migration threshold to `LAST_OLD_NAME_MIGRATION`.
  - Update the class javadoc. Layer 2 is `MACHINE`/`TOKEN_PREFIX` outside `docs`; layer 3 is `PROSE` everywhere plus `MACHINE/docs`. Phase 2 must empty `layerTwoLeftovers`.
- [ ] **Step 4: Rebaseline.** Run the test, paste the "Current counts" block into `BASELINE`, run it again, and expect PASS.
- [ ] **Step 5: Commit:** `test(rebrand): inventory separates machine names (layer 2) from prose (layer 3)`.

### Task 3: Configuration names, with old ones refused

**Files:**
- Create: `app/src/main/java/com/wikantik/pressroom/config/LegacyConfigNames.java`
- Create: `app/src/test/java/com/wikantik/pressroom/config/LegacyConfigNamesTest.java`
- Modify: `WebloggerConfig.java`, which calls the guard first in `loadLayers`, before any file is read. It also gets the new `env_prefix`, `custom_jvm_param` and file-name constants.
- Rename:
  - `app/src/main/resources/com/wikantik/pressroom/config/roller.properties` to `pressroom.properties`;
  - `app/src/test/resources/roller-custom.properties` to `pressroom-custom.properties`;
  - `app/src/test/resources/roller-boot-dev.properties` to `pressroom-boot-dev.properties`;
  - `roller-version.properties` to `pressroom-version.properties` (`app/pom.xml`, `Colophon`, `McpServerConfig`, `WebloggerImpl`, `RemoteImageFetcher`).
- Modify:
  - every `System.getProperty("roller.` site;
  - the `roller.*` config keys in the mapping table;
  - `ROLLER_` names in compose files, `deploy/.env.example`, scripts, poms, workflows, tests, ITs and docs;
  - `mediafiles.storage.dir`/`search.index.dir` defaults, `roller_data` to `pressroom_data`.

**Interfaces:**
- Produces:
  - `static List<String> found(Map<String, String> env, Properties systemProperties)`. Sorted. It returns names with the exact prefix `ROLLER_` (env) or `roller.` (system property) and never values.
  - `static void refuse(Map<String, String> env, Properties systemProperties)`. It throws `IllegalStateException` with the message `"Pressroom no longer reads Roller configuration names. Rename each to its PRESSROOM_ / pressroom. form and restart: " + String.join(", ", found)`. The quoted message is Pressroom-facing copy; it carries `rebrand:keep`.

- [ ] **Step 1: Write the failing tests** in `LegacyConfigNamesTest`:
  - `namesEveryOldEnvironmentVariable`: env `{ROLLER_DATABASE_JDBC_PASSWORD="s3cret", ROLLER_EMPTY="", PRESSROOM_X="y", PATH="/bin"}` gives `["ROLLER_DATABASE_JDBC_PASSWORD", "ROLLER_EMPTY"]`.
  - `namesEveryOldSystemProperty`: `{roller.custom.config=/a, pressroom.custom.config=/b, java.version=25}` gives `["roller.custom.config"]`.
  - `ignoresLookalikes`: env `ROLLERX`, `MY_ROLLER_X` and system properties `rollerx.a`, `x.roller.y` give `[]`.
  - `refusalNamesEveryOldNameAndNoValue`: the message contains `ROLLER_DATABASE_JDBC_PASSWORD` and `roller.custom.config`, and does not contain `s3cret`.
  - `passesWhenNoOldNameIsPresent`: `refuse` returns normally.
  - In `WebloggerConfigTest`, the environment-override tests use `PRESSROOM_` names. Add `oldPrefixIsNotAnOverride`: `applyEnvironmentOverrides` with `ROLLER_SITE_NAME=x` leaves `site.name` unset. The guard, not the overlay, owns refusal.
- [ ] **Step 2: Run them and watch them fail.** Run `mvn -pl app test -Dtest='LegacyConfigNamesTest,WebloggerConfigTest'`. Expected: compilation failure, then assertion failures on the prefix.
- [ ] **Step 3: Implement the guard and switch the prefixes.** The guard's `RuntimeException` escalates through `runAtClassLoad`, so startup fails. Rename the files and keys from the Files list, using `git mv` for files.
- [ ] **Step 4: Run the full suite.** Run `mvn -pl app test`. Expected: PASS. Then lower `BASELINE` to the paste-ready counts.
- [ ] **Step 5: Smoke-test the real WAR** (`-DskipTests install` first):
  - `java -Dpressroom.custom.config=app/target/test-classes/pressroom-boot-dev.properties -jar app/target/roller.war --server.port=8093` reaches health UP on 8090. The dev DB comes from `./roller db`; Task 9 renames the script.
  - The same command with `ROLLER_FOO=1` in the environment exits non-zero, and its log names `ROLLER_FOO`.
  - Stop the app with `pkill -f "[r]oller.war --server.port=8093"`.
- [ ] **Step 6: Commit:** `feat(rebrand)!: PRESSROOM_ and pressroom.* configuration; old names refuse startup`.

### Task 4: Database, role and table names

**Files:**
- Create: `bin/db/migrations/V041__pressroom_rename.sql`
- Create: `app/src/test/java/com/wikantik/pressroom/db/PressroomRenameMigrationTest.java`, next to the existing migration tests in `app/src/test/java/com/wikantik/pressroom/db/`. Reuse their harness for "database at version N".
- Modify:
  - every `*.orm.xml` `<table name="roller_...">`;
  - native SQL in main and test;
  - `deploy/analytics/umami-views.sql` and `bin/db/check-stored-templates.sql`;
  - `DatabaseInstaller`'s upstream probe, which keeps `roller_user` with `rebrand:keep` and a comment saying it detects Apache Roller schemas;
  - `rollerdb`/`roller` database and role defaults in `docker-compose.yml`, `docker-compose.prod.yml`, `roller` (the dev script), `bin/db/*.sh`, the Testcontainers harness, `deploy/backup/backup.sh` (dump names `pressroom-<ts>.dump`) and `database.jndi.name`.

**Interfaces:**
- Produces:
  - Table names `pressroom_*`, one for each of the 18 listed in the Phase 0 scan report.
  - `V041` is one transaction-safe, idempotent script:
    - a `DO` block that renames every relation in schema `public` named `roller\_%` (tables, then sequences, then remaining indexes) and every constraint named `roller\_%`, replacing the `roller_` prefix with `pressroom_`;
    - it skips a rename when the target name already exists and the source does not;
    - then `UPDATE pressroom_weblogpage SET content = replace(content, '/roller-ui/', '/pressroom-ui/') WHERE content LIKE '%/roller-ui/%'`, and the same for `weblogentry.text` and `weblogentry.summary`. `weblogentry_revision` is history and stays.

- [ ] **Step 1: Write the failing tests** in `PressroomRenameMigrationTest`:
  - `renamesEveryOldRelationAndConstraint`: migrate to V040 and insert a user, a weblog, an entry and an API token. Apply V041. Expect no row in `pg_class` (namespace `public`) with `relname LIKE 'roller\_%'`, and none in `pg_constraint` with `conname LIKE 'roller\_%'`. Each of the 18 `pressroom_*` tables exists, and the inserted rows are readable through the new names.
  - `isIdempotent`: applying V041 a second time raises no error and changes nothing.
  - `finishesAPartialRename`: at V040, rename `roller_user` by hand to `pressroom_user`, then apply V041. Same end state as above.
  - `rewritesStoredUiLinks`: a weblog page whose content holds `<a href="/roller-ui/account.rol">` reads `/pressroom-ui/account.rol` after V041. An entry with the text `controller` is unchanged.
  - `analyticsViewsStillAnswer`: `SELECT count(*) FROM analytics_events` succeeds after V041.
  - `freshInstallEndsRenamed`: a fresh install through `DatabaseInstaller` has `pressroom_user` and no `roller_user`.
- [ ] **Step 2: Run them and watch them fail.** Run `mvn -pl app test -Dtest=PressroomRenameMigrationTest`. Expected: FAIL, because V041 is absent.
- [ ] **Step 3: Write V041 and update the ORM mappings, native SQL and database defaults.** `PersistenceMappingTest` (Phase 0) proves every mapping still loads.
- [ ] **Step 4: Run the full suite.** Run `mvn -pl app test`. Expected: PASS. Lower `BASELINE`.
- [ ] **Step 5: Check the dev environment.** The dev DB is disposable. Run `./roller reset` (the dev script; Task 9 renames it) and `./roller status`. Expected: V041 applied, and the database is named `pressroom`.
- [ ] **Step 6: Commit:** `feat(rebrand)!: pressroom database, role and tables (V041)`.

### Task 5: `/pressroom-ui/` with a permanent redirect from `/roller-ui/`

**Files:**
- Rename: `app/src/main/webapp/roller-ui/` to `app/src/main/webapp/pressroom-ui/` (`git mv`).
- Create: `app/src/main/java/com/wikantik/pressroom/ui/core/filters/RollerUiRedirectFilter.java` (the class name carries the old name on purpose; `rebrand:keep` on its declaration).
- Create: `app/src/test/java/com/wikantik/pressroom/ui/core/filters/RollerUiRedirectFilterTest.java`
- Create: `it-selenium/src/test/java/com/wikantik/pressroom/it/OldUiPathRedirectIT.java`
- Modify:
  - every `/roller-ui/` reference in main, test, ITs, docs and `ApplicationResources*.properties`;
  - `SsoPaths.CALLBACK` (`/pressroom-ui/sso/callback`) and `LazyOidcClientRegistrationRepository`;
  - the Spring Security matchers;
  - `Routes`.
- Register the filter ahead of security, so the redirect needs no session and no authentication.

**Interfaces:**
- Produces: `RollerUiRedirectFilter` handles any request whose path within the application (`request.getRequestURI()` minus `getContextPath()`) starts with `/roller-ui/` or equals `/roller-ui`.
  - It answers 301 for GET/HEAD and 308 otherwise.
  - `Location` is `contextPath + "/pressroom-ui" + rest + ("?" + query, if any)`, relative to the host.

- [ ] **Step 1: Write the failing unit tests** in `RollerUiRedirectFilterTest`, using MockHttpServletRequest:
  - `getAtRootRedirectsPermanently`: `GET /roller-ui/login.rol` gives 301 with `Location: /pressroom-ui/login.rol`.
  - `keepsTheQueryString`: `/roller-ui/entries.rol?weblog=a&page=2` gives `/pressroom-ui/entries.rol?weblog=a&page=2`.
  - `keepsTheContextPath`: context `/pressroom`, URI `/pressroom/roller-ui/x.rol`, gives `/pressroom/pressroom-ui/x.rol`.
  - `postKeepsItsMethod`: POST gives 308.
  - `bareDirectory`: `/roller-ui` gives `/pressroom-ui`.
  - `leavesEverythingElseAlone`: `/pressroom-ui/x.rol`, `/rollerblog/x` and `/weblog/roller-ui/x` (a weblog page path) pass through the chain untouched.
- [ ] **Step 2: Run them and watch them fail.** Run `mvn -pl app test -Dtest=RollerUiRedirectFilterTest`. Expected: compilation failure.
- [ ] **Step 3: Implement the filter and register it.**
- [ ] **Step 4: Do the mechanical rename.**
  - Use `git mv` for the directory.
  - Replace `/roller-ui` with `/pressroom-ui` in every tracked file outside the kept set. Also replace `roller-ui/` where it appears as a relative path (JSP includes, esbuild output paths in `app/frontend`).
  - Update the `Routes` CSS markers only where they embed the path.
- [ ] **Step 5: Write the browser IT** `OldUiPathRedirectIT`:
  - Open `<base>/roller-ui/login.rol?x=1` and assert that the final URL is `<base>/pressroom-ui/login.rol?x=1` and the login form is present.
  - `<base>` is the IT context base, so the same test covers both context paths.
- [ ] **Step 6: Run the unit suite and lower `BASELINE`.** Run `mvn -pl app test`. Expected: PASS.
- [ ] **Step 7: Run the browser suite at both context paths:** `mvn verify -Pit`, then `mvn verify -Pit -Dit.context.path=roller`. Task 9 renames that path. Expected: 0 failures in both runs. This is a routing change, so CLAUDE.md requires both.
- [ ] **Step 8: Commit:** `feat(rebrand)!: admin UI under /pressroom-ui with permanent redirects from /roller-ui`.

### Task 6: Automation API contract (token prefix, MCP name, CLI)

**Files:**
- Modify:
  - `ApiTokenManager.TOKEN_PREFIX` = `"prs_"`;
  - `JPAApiTokenManagerImpl.authenticate`, which returns `null` before any lookup when `rawToken` does not start with `TOKEN_PREFIX`;
  - `McpServerConfig` (`serverInfo("pressroom", ...)`).
- Rename: `bin/roller-api` to `bin/pressroom-api`. Its help text and env (`PRESSROOM_API_URL`, `PRESSROOM_API_TOKEN`, already renamed in Task 3) follow.
- Modify: the `rlr_` sites in tests and ITs, and `docs/api/**`.
- Test: `JPAApiTokenManagerImplTest` (or the existing token test class), `McpServerConfigTest` or the existing MCP initialize test, and the CLI's existing test.

- [ ] **Step 1: Write the failing tests.**
  - `oldPrefixTokenIsRefusedEvenWithAStoredDigest`: store a token row whose digest is that of `rlr_` + 40 chars (`rebrand:keep`). `authenticate` of that string returns `null`.
  - `issuedTokensCarryTheNewPrefix`: the issued raw starts with `prs_`.
  - MCP `initialize` answers `serverInfo.name == "pressroom"`.
  - An `/api/v1` request with an old-prefix bearer answers 401, the same as an unknown token.
- [ ] **Step 2: Run them and watch them fail.** Run `mvn -pl app test -Dtest='*ApiToken*Test,*Mcp*Test'`. Expected: FAIL.
- [ ] **Step 3: Implement, rename the CLI and sweep `rlr_`.**
- [ ] **Step 4: Run the full suite and lower `BASELINE`.** Run `mvn -pl app test`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(rebrand)!: prs_ API tokens (old prefix refused), MCP server name pressroom, bin/pressroom-api`.

### Task 7: Loggers, log file and Java-side runtime names

**Files:**
- Modify:
  - `app/src/main/resources/log4j2.xml` and `app/src/test/resources/log4j2.xml`: logger names, appender names, the file name and the `pressroom.log.dir` property;
  - every `LoggerFactory.getLogger("roller.` site;
  - `LogRoutingTest` (Phase 0 extended it);
  - the session and request attribute keys (`PressroomSession.ROLLER_SESSION` becomes `PRESSROOM_SESSION`, with value `com.wikantik.pressroom.session`);
  - cookie names, thread names, temp-file prefixes and Spring bean names;
  - every lowerCamel or UPPER Java identifier carrying the old name (`getRollerSession`, `rollerHandlerInterceptor`, `rollerTier`, ...);
  - the Java string literals that are machine names in main and test.

**Interfaces:**
- Produces: logger names `pressroom.sso`, `pressroom.comments`, `pressroom.admin`, `pressroom.mcp`, `pressroom.mcp.request`, `pressroom.media.import`, `pressroom.redirects`, `pressroom.sso.return`, and every other `roller.X` as `pressroom.X`. Each keeps the routing its old name had. Loggers that went to `asyncRoller` now go to `asyncPressroom`, which writes to `${logging.dir}/pressroom.log`.

- [ ] **Step 1: Update `LogRoutingTest` first so it expects the new names.** It enumerates each named logger and its appender.
- [ ] **Step 2: Watch it fail.** Run `mvn -pl app test -Dtest=LogRoutingTest`. Expected: FAIL. The new names are unconfigured and fall to root.
- [ ] **Step 3: Rename the loggers in `log4j2.xml` and the code.** Then rename the identifiers and keys. Phase 1's `PressroomSession` tests (`testGetRollerSession*`) become `testGetPressroomSession*`.
- [ ] **Step 4: Run the full suite and lower `BASELINE`.** Run `mvn -pl app test`. Expected: PASS. `MACHINE/main-java` should now be close to zero. Whatever remains is listed in the commit message, for Task 11.
- [ ] **Step 5: Commit:** `refactor(rebrand)!: pressroom.* loggers, pressroom.log, Java runtime names`.

### Task 8a: Admin UI and editor front end (CSS, JS, data attributes)

**Files:**
- Rename:
  - `pressroom-ui/styles/roller.css` to `pressroom.css` and `roller-tokens.css` to `pressroom-tokens.css`;
  - the `roller.js` and `roller-draft.js` sources and outputs to `pressroom.js` and `pressroom-draft.js`;
  - the entry names in `app/frontend/package.json` and the esbuild config.
- Modify:
  - CSS classes `roller-*`, `data-roller-*`, JS identifiers `roller*`/`window.roller*` and localStorage keys, across JSPs, JS, CSS, Java that emits HTML (the content pipeline's `roller-code`, `roller-heading-anchor`, `roller-alert-icon` and so on) and theme CSS (`themes/*/**.css`);
  - the `Routes` CSS markers in the same commit;
  - the unit tests and ITs that assert those names.

- [ ] **Step 1: Change the failing assertions first.**
  - Update the tests that pin emitted HTML (content pipeline, code blocks, heading anchors, alerts) and the `Routes` markers to the `pressroom-` names.
  - Run `mvn -pl app test -Dtest='*Markdown*Test,*CodeBlock*Test,*Heading*Test,*Alert*Test'`. Expected: FAIL on the class names.
- [ ] **Step 2: Rename across the front end.** Rename files with `git mv`. Rebuild the bundles through Maven, not by hand.
- [ ] **Step 3: Run the full suite and lower `BASELINE`.** Run `mvn -pl app test`. Expected: PASS.
- [ ] **Step 4: Run the browser suite at the root context:** `mvn verify -Pit`. Expected: 0 failures. Editor, preview and lightbox are driven by these names. Task 11 runs both paths.
- [ ] **Step 5: Commit:** `refactor(rebrand)!: pressroom- CSS classes, data attributes, JS globals and bundle names`.

### Task 8b: Velocity context names, resource loader, macro library and TLD

**Files:**
- Modify:
  - `$rollerContentScope` and every other `$roller*` context name, both where it is put into the context (Java) and in templates (`themes/**`, `WEB-INF/velocity/**`);
  - `$config.rollerVersion`, by renaming the getter;
  - `resource.loader.roller` in `velocity.properties` and `PressroomVelocity`.
- Rename:
  - `WEB-INF/velocity/roller-custom.vm` to `pressroom-custom.vm` (and `velocimacro.library.path`);
  - `WEB-INF/rollerConfig.tld` to `pressroomConfig.tld` (and its `<uri>` and every JSP `taglib` directive).
- Delete: `WEB-INF/sun-web.xml` (dead, GlassFish).

**Interfaces:**
- Consumes: Task 1's `StrictReferences` expectations. A context name renamed on one side only shows up as a new recorded entry.

- [ ] **Step 1: Rename one side only (Java puts) and run the rendering tests to watch them fail.** Run `mvn -pl app test -Dtest='ThemeReferenceLeakTest,*Rendering*Test'`. Expected: FAIL with the new `$pressroom...`/old `$roller...` entries named. This proves Task 1 guards this task.
- [ ] **Step 2: Rename the template side, the loader, the macro library and the TLD.** `RenderedTemplateCoverageTest`'s `RENDERED_BY` key changes to `pressroom-custom.vm`.
- [ ] **Step 3: Run the full suite and lower `BASELINE`.** Run `mvn -pl app test`. Expected: PASS. The strict sets are unchanged from Task 1, apart from renamed entries.
- [ ] **Step 4: Commit:** `refactor(rebrand)!: pressroom Velocity context names, loader, macro library and TLD`.

### Task 9: Packaging, dev tooling, IT harness and CI

**Files:**
- Modify: `app/pom.xml` (`<finalName>pressroom</finalName>`), `Dockerfile`, `bin/release.sh` and `.github/workflows/{main,release}.yml`.
  - `release.yml` sets `IMAGE_REPO=${{ github.repository_owner }}/pressroom` explicitly, plus the caddy image `.../pressroom-caddy`. It must not derive the name from the repository name (see the ruling at the end).
- Rename: `roller` (the dev script) to `pressroom`, and `.roller-dev-secret` to `.pressroom-dev-secret` (in `.gitignore` too).
- Modify the dev compose project and volume names in `docker-compose.yml`.
- Modify `it-selenium/pom.xml` and the IT support:
  - `roller-it*` names become `pressroom-it*`, and the Keycloak realm `roller-it-realm` becomes `pressroom-it-realm`;
  - the alternate context path becomes `pressroom`, and `IT_CONTEXT_PATH` docs follow;
  - CLAUDE.md's `-Dit.context.path=roller` and `./roller` commands are updated.
- Modify the test fixtures that use `/roller` as an example context path (`//example.com/roller`, `//localhost/roller`) to `/pressroom`.
- Modify the existing tests that pin these: `QualityGatePomTest`, `DeployScriptTest` and any dev-script test (`theRollerScriptSeeds...`).

- [ ] **Step 1: Update the pinning tests to the new names and watch them fail.** Run `mvn -pl app test -Dtest='QualityGatePomTest,*Script*Test,ProductionComposeTest'`. Expected: FAIL.
- [ ] **Step 2: Rename and modify the files listed above.**
- [ ] **Step 3: Run `mvn clean install`.** Expected: PASS, and `app/target/pressroom.war` exists.
- [ ] **Step 4: Check the dev script.** Run `./pressroom reset && ./pressroom status`. Expected: migrations through V041 on database `pressroom`.
- [ ] **Step 5: Commit:** `build(rebrand)!: pressroom.war, ./pressroom, IT harness and CI image names`.

### Task 10: Production compose and the scripted cutover

**Files:**
- Modify `docker-compose.prod.yml`:
  - images `ghcr.io/jakefearsd/pressroom` and `pressroom-caddy`;
  - volumes `pressroom-pgdata`, `pressroom-mediafiles`, `pressroom-search-index`, `pressroom-uploads`, `pressroom-backups`;
  - `caddy-data`/`caddy-config` unchanged;
  - `POSTGRES_USER`/`POSTGRES_DB` defaults `pressroom`.
- Modify:
  - `deploy/{deploy,upgrade,provision,admin-account,analytics-views}.sh` and `deploy/backup/*`;
  - `deploy/caddy/*`;
  - `deploy/.env.example`, including rewording its comment naming the properties path;
  - `upgrade.sh` `RELEASE_URL` to `https://github.com/jakefearsd/pressroom/releases/download`.
- Modify `bin/deploy-host.sh`:
  - default `--dir pressroom`;
  - a new `--cutover` flag, which copies `deploy/cutover-pressroom.sh` and `deploy/rollback-pressroom.sh` into `~/roller` on the host and runs the cutover there.
- Create: `deploy/cutover-pressroom.sh`, `deploy/rollback-pressroom.sh` and `app/src/test/java/com/wikantik/pressroom/business/startup/CutoverScriptTest.java`, built in the style of the existing `UpgradeScriptTest`: a stub `docker` on `PATH` records each call, and the script runs in a temp directory.
- Create: `docs/dev/pressroom-cutover.md` (the deploy checklist).
- Modify: `ProductionComposeTest`, `DeployHostScriptTest` and `UpgradeScriptTest`.

**Interfaces:**
- Produces: `deploy/cutover-pressroom.sh VERSION [--files-from DIR] [--no-pull]`, run from the old deploy directory.
  - Environment: `OLD_PROJECT` (default `roller`), `NEW_PROJECT` (default `pressroom`) and `NEW_DIR` (default `../pressroom`). The rehearsal sets these so it cannot touch other projects' volumes.
  - Steps, in this order. Each one stops the script on failure, and nothing in the old directory or old volumes is modified at any point.
    1. Refuse if `NEW_DIR` exists, or if `.env`'s `IMAGE_VERSION` is not `0.4.x`.
    2. Fetch the release's compose, `deploy.sh`, `env.example` and `upgrade.sh` into `NEW_DIR`, or copy them from `--files-from`.
    3. Back up through the old stack's backup service. Record row counts into `NEW_DIR/cutover-counts-before.txt`: `pressroom` app tables (`roller_user`, `weblog`, `weblogentry`, `roller_mediafile`, `roller_reader_comment`), umami `website` and `website_event`, and listmonk `subscribers`.
    4. Write `NEW_DIR/.env` from `.env`:
       - a key `ROLLER_X` becomes `PRESSROOM_X`;
       - `POSTGRES_USER=roller` becomes `pressroom`, and `POSTGRES_DB=rollerdb` becomes `pressroom`;
       - the JDBC URL's `/rollerdb` becomes `/pressroom`;
       - `IMAGE_VERSION=VERSION`;
       - every other byte is unchanged;
       - then print the *names* of keys whose value still contains `roller`, never the values.
    5. Run `docker compose -p OLD_PROJECT down`, which keeps the volumes.
    6. Copy each volume `OLD_PROJECT_roller-X` to `NEW_PROJECT_pressroom-X`, and `OLD_PROJECT_caddy-*` to `NEW_PROJECT_caddy-*`, with `cp -a` in a throwaway container from the pinned postgres image. Refuse if a target volume already exists.
    7. In `NEW_DIR`, run `docker compose up -d postgres` and wait for `pg_isready`.
    8. Rename:
       - as `roller`, `CREATE ROLE pressroom_cutover SUPERUSER LOGIN`;
       - as `pressroom_cutover` on database `postgres`, `ALTER DATABASE rollerdb RENAME TO pressroom; ALTER ROLE roller RENAME TO pressroom`;
       - if `pg_authid.rolpassword` for `pressroom` now starts with `md5` or is null, `ALTER ROLE pressroom PASSWORD` with the value from `NEW_DIR/.env`, fed through stdin and never argv;
       - as `pressroom`, `DROP ROLE pressroom_cutover`;
       - verify a password login as `pressroom` over TCP inside the container.
    9. Run `./deploy.sh` in `NEW_DIR` (`--no-pull` skips the pull). Its provision step applies V041.
    10. Recount into `cutover-counts-after.txt`, using the renamed tables. Fail if any count differs.
    11. Print the checklist's manual steps.
- Produces: `deploy/rollback-pressroom.sh`, run from the old directory. It runs `docker compose -p NEW_PROJECT -f NEW_DIR/docker-compose.prod.yml down`, then the old directory's `./deploy.sh` on the untouched old `.env` (`IMAGE_VERSION` 0.4.x) and old volumes, then waits for health.

- [ ] **Step 1: Write the failing tests.**
  - `CutoverScriptTest`:
    - `rewritesOnlyKeysAndTheDatabaseValues`, using a fixture `.env` with comments, blank lines, `export ROLLER_A=1`, a quoted value, `ROLLER_MAIL_FROM=roller@example.com`, `POSTGRES_PASSWORD=s3cret` and a JDBC URL. The expected output file is pinned exactly.
    - `printsKeyNamesNeverValues`: the output contains `PRESSROOM_MAIL_FROM` and contains neither `roller@example.com` nor `s3cret`.
    - `refusesWhenTheNewDirectoryExists`.
    - `refusesWhenNotOnZeroFour`.
    - `copiesEveryVolumeAndNeverRemovesOne`: the stub log has one copy per volume and no `volume rm`, `down -v` or `rm -rf` on the old project.
    - `renamesTheRoleFromAnotherSuperuserSession`: in the stub log, `ALTER ROLE roller RENAME` runs as user `pressroom_cutover`, after `CREATE ROLE pressroom_cutover`, and `DROP ROLE pressroom_cutover` comes after it.
    - `resetsAnMd5Password`: the stub makes `rolpassword` start with `md5`; the log shows `ALTER ROLE pressroom PASSWORD`, and the password is absent from every recorded argv.
    - `stopsWhenCountsDiffer`.
  - `RollbackScriptTest.downsTheNewProjectAndDeploysTheOldDirectory`.
  - `ProductionComposeTest`: new image and volume names, and no `roller` anywhere in the file.
  - `DeployHostScriptTest`: the default dir is `pressroom`, and `--cutover` copies both scripts to `roller/` and runs the cutover there.
- [ ] **Step 2: Run them and watch them fail.** Run `mvn -pl app test -Dtest='CutoverScriptTest,RollbackScriptTest,ProductionComposeTest,DeployHostScriptTest,UpgradeScriptTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the scripts and the compose/deploy renames.** Run `shellcheck` on both new scripts, if it is installed.
- [ ] **Step 4: Write `docs/dev/pressroom-cutover.md`.** It covers:
  - the pre-flight: the 0.5.0 release published; the GitHub repo renamed (see the ruling); jakemon alerts silenced; Jake told about the gap;
  - `bin/deploy-host.sh docker1 0.5.0 --cutover --url https://blog.wikantik.com/`;
  - the post-deploy checks: health; a weblog page; admin login; `/roller-ui/login.rol` redirects; an MCP call with a newly minted `prs_` token; umami and listmonk answer and hold their data;
  - the manual steps:
    - the Google console redirect URI `/pressroom-ui/sso/callback`;
    - revoke the old agent token row;
    - mint a new token for blogger-agent;
    - save or publish any open editor drafts before the deploy, because the localStorage keys change;
    - the privacy page and `site.description` prose are Jake's call (Phase 3);
  - rollback: `ssh docker1 'cd roller && bash rollback-pressroom.sh'`;
  - the 30-day retention of the old directory, the old volumes and the 0.4.2 images, with the cleanup commands.
- [ ] **Step 5: Run the full suite and lower `BASELINE`.** Run `mvn -pl app test`. Expected: PASS.
- [ ] **Step 6: Commit:** `feat(rebrand)!: pressroom production compose, scripted cutover and rollback`.

### Task 11: Residue sweep and the layer 2 finish line

**Files:**
- Modify: whatever `layerTwoLeftovers` still lists.
- Modify: `OldNameInventoryTest`, adding `layerTwoIsEmpty`.

- [ ] **Step 1: Add the failing test** `layerTwoIsEmpty`, modelled on `layerOneIsEmptyOnceMoved`: `layerTwoLeftovers(inventory().totals())` is empty, and the failure lists the top five files per key. Run `mvn -pl app test -Dtest=OldNameInventoryTest#layerTwoIsEmpty`. Expected: FAIL listing the residue, unless Tasks 3-10 left none.
- [ ] **Step 2: Rename each residue site by the mapping.**
  - Where a site must keep the old name, give it a `rebrand:keep` marker and justify it in the commit message.
  - Remaining `PROSE` is Phase 3 and stays.
- [ ] **Step 3: Run `mvn clean install` and lower `BASELINE`.** Expected: PASS, with the gates clean and the floors unchanged.
- [ ] **Step 4: Run the browser suite at both context paths:** `mvn verify -Pit`, then `mvn verify -Pit -Dit.context.path=pressroom`. Expected: 0 failures in both.
- [ ] **Step 5: Smoke-test the WAR** with `PRESSROOM_*` env only: health UP, login page 200 at `/pressroom-ui/login.rol`, and `/roller-ui/login.rol` answers 301. The same run with one `ROLLER_*` variable added refuses to start and names it.
- [ ] **Step 6: Commit:** `test(rebrand): layer 2 old names are gone`.

### Task 12: Cutover and rollback rehearsal on a production copy (controller-run)

The top-level session runs this task, not a subagent, because it reads from docker1. It is read-only there: the rehearsal copies an existing backup file and changes nothing on the host.

- [ ] **Step 1: Copy the latest backup set from docker1.** The newest `rollerdb-*.dump`, `umami-*.dump`, `listmonk-*.dump` and `volumes-*.tar.gz` come out of the `roller-backups` volume via `docker compose cp` into `~/cutover-rehearsal/backup/`. Delete them when the rehearsal ends.
- [ ] **Step 2: Build the old stack.**
  - In `~/cutover-rehearsal/old/`, place the 0.4.2 release's compose, `deploy.sh` and `.env`, generated from `deploy/.env.example` at tag `v0.4.2` with local ports, `ROLLER_*` names and `IMAGE_VERSION=0.4.2`.
  - Use `COMPOSE_PROJECT_NAME=rehearsal-roller`, so the volumes cannot collide with the dev stack.
  - Restore the three dumps and the volumes tarball, and start the stack. Expected: 0.4.2 serves the restored weblogs.
- [ ] **Step 3: Build the new images locally.** Tag them `ghcr.io/jakefearsd/pressroom:0.5.0` and `pressroom-caddy:0.5.0` from this tree (`mvn -DskipTests install`, then the Dockerfiles).
- [ ] **Step 4: Run the cutover.**
  - Command: `OLD_PROJECT=rehearsal-roller NEW_PROJECT=rehearsal-pressroom NEW_DIR=../new bash cutover-pressroom.sh 0.5.0 --files-from <checkout> --no-pull`, from `old/`.
  - Expected: counts match. Health is UP, a restored weblog page renders, admin login works, and `/roller-ui/login.rol` redirects.
  - Umami and listmonk answer and show their restored data.
  - A newly minted `prs_` token works over MCP, and an old `rlr_` one does not.
  - Note which password type the role had.
- [ ] **Step 5: Rehearse the rollback.** Run `rollback-pressroom.sh` with the same environment. Expected: the 0.4.2 stack serves the restored data again from the untouched old volumes.
- [ ] **Step 6: Write the report** at `docs/superpowers/reports/2026-10-29-pressroom-phase2-rehearsal.md`. It records timings (the length of the gap), the counts, the password type and anything the checklist must change. Fix any defect found as a new task before this phase is called done.
- [ ] **Step 7: Clean up.** Run `docker compose down -v` for both rehearsal projects only, remove `~/cutover-rehearsal`, then commit the report.

---

## Rulings made in this plan (for Jake's review)

1. **The GitHub repo rename moves ahead of the 0.5.0 release** (spec Phase 4 lists it after).
   - `upgrade.sh` downloads from `github.com/jakefearsd/pressroom/releases`, which only exists after the rename. GitHub redirects the old URL, so nothing else breaks.
   - It is outward-facing, so the cutover checklist asks Jake for the go-ahead at release time.
   - `release.yml` names the image `pressroom` explicitly, so publishing works both before and after the rename.
2. **The spec's Phase 2 "done" includes the docker1 deploy, but the spec also says there is one release after Phase 3.**
   - This plan ends at the rehearsal.
   - The deploy runs from `docs/dev/pressroom-cutover.md` after Phase 3.
3. **There are no `roller_` metrics in this repo.**
   - Container names are what jakemon relabels: `roller-.+`.
   - The rename to project `pressroom` changes them, and jakemon's rules follow in Phase 4, inside the accepted gap.
4. **Old tokens are refused by prefix, not deleted.** Jake revokes the old row when re-minting. No credential data is deleted by migration.
5. **No fallback for renamed browser state.**
   - localStorage draft keys, session attributes and cookies are not migrated.
   - Users sign in once more, and an unsaved local draft at cutover is lost; the checklist says to save first.
   - The spec allows exactly one dual-name period, `/roller-ui/`.
6. **The dev database and media dir start fresh** (`./pressroom reset`, `~/pressroom_data`). They are disposable seeds.
7. **The Velocity recorder runs in production too** (DEBUG, capped at 1,000 entries), rather than behind a test-only switch. The engine is a JVM singleton, so a switch would depend on test order.
8. **`docs/examples` and the remaining prose stay for Phase 3.**
