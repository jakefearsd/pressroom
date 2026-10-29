# Pressroom Phase 1: Code Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every Java package to `com.wikantik.pressroom`, rename every `Roller*` code identifier to `Pressroom*`, and change the Maven coordinates. Nothing a running system depends on outside the code changes.

**Architecture:** A committed, reviewed shell script makes the whole move in one pass: `git mv` per file plus `perl` substitutions over an explicit file set and an explicit identifier list. Its output is committed as a single mechanical commit, recorded in `.git-blame-ignore-revs`. The script is never hand-patched after it runs: if the result is wrong, fix the script, reset, and run it again. The Phase 0 scanners decide whether the move is complete.

**Tech Stack:** bash, `git mv`, `perl -pi`, Maven, JUnit 5, the Phase 0 tests in `app/src/test/java/org/apache/roller/weblogger/build/`, and the browser ITs (`mvn verify -Pit`).

**Spec:** `docs/superpowers/specs/2026-10-29-pressroom-rebrand-design.md`, sections "Layer 1" and "Phase 1: code identity". Phase 0 plan, for the safety net: `docs/superpowers/plans/2026-10-29-pressroom-phase0-safety-net.md`.

## Global Constraints

- Package mapping: `org.apache.roller.weblogger.X` → `com.wikantik.pressroom.X`, and `org.apache.roller.Y` → `com.wikantik.pressroom.Y` for everything else (`util`, `testing`, `it`, and the root `RollerException`). The same mapping applies to slashed paths.
- Maven: `groupId` `com.wikantik.pressroom`. Parent artifact `pressroom-project`, webapp artifact `pressroom`, IT artifact `pressroom-selenium-tests`. **`<finalName>roller</finalName>` stays.** `roller.war` is a Layer 2 name and is renamed in Phase 2.
- `Roller*` classes become `Pressroom*`. `Weblogger*` classes and the word "weblog" keep their names.
- **Phase 1 changes no Layer 2 or Layer 3 name.** That means lowercase `roller` in property keys, file names (`roller.properties`, `roller-custom.vm`, `rollerConfig.tld`), CSS classes, Velocity context names, logger names `roller.*`, env vars, URLs `/roller-ui/`, table names, `rlr_`, `finalName`, and UI text. Wiki page names (`RollerHub`, `RollerMcp`, …) and `RollerSSO.md` are Phase 3/4.
- Never rewritten: `LICENSE.txt`, `NOTICE.txt`, `bin/db/migrations/**`, `docs/superpowers/**`, `docs/examples/**`, and any line containing `rebrand:keep`.
- Spec, Phase 1 done-criterion, verbatim: "full unit suite, all gates, and the browser ITs at both `/` and `-Dit.context.path=roller` pass; the Phase 0 tests pass unchanged except for the inventory, whose Layer 1 group is empty; coverage floors unchanged."
- **Serial, one tree.** The move touches nearly every file, so no worktrees and no parallel implementers. One build at a time: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`.
- Always `mvn clean` before verifying a moved tree. Otherwise stale `target/classes/org/apache/roller/**` copies can mask a leftover reference.
- TDD and all CLAUDE.md gates as usual: JaCoCo floors LINE 0.9590 / BRANCH 0.9100 and the package rules, PMD / CPD / SpotBugs at zero, `-Xlint:all`. Commit per task. Never push.

## Review Focus

1. **Component scan widens.** `scanBasePackageClasses = WebloggerException.class` used to scan only `org.apache.roller.weblogger`. After the move it scans `com.wikantik.pressroom`, which now also holds the old `util`, `testing` and `RollerException`. A Spring stereotype or `@Configuration` in those classes would silently join the context. Covered by Task 3 Step 6.
2. **A legacy literal in a Phase 0 test gets rewritten.** `ProjectNames.LEGACY_ROOT`, or the inventory's own patterns and fixtures. The scanners would then go blind to leftovers. Covered by Task 1's `rebrand:keep` markers and Task 2's dry-run `KEPT_LINES` section, checked against Task 1's list.
3. **Wiki and doc names that look like classes.** `RollerHub`, `RollerMcp`, `RollerSSO` must not be renamed, or links break before Phase 4. Covered by Task 2's explicit identifier list and the dry-run's `NOT_RENAMED` report.
4. **Browser caches the old JS global.** `window.RollerContent` / `RollerEditor` become `PressroomContent` / `PressroomEditor`. A page from the new release must load the new bundle. Covered by Task 4's IT run, which loads real pages and fails on a script error.
5. **JUnit launcher listener.** `META-INF/services/org.junit.platform.launcher.LauncherSessionListener` names `RollerTestBootstrap` by FQN. A miss here means tests run without the Postgres bootstrap and fail confusingly, not on a scanner. Covered by the full suite in Task 3, and by `StringClassReferenceTest`, which scans test resources.
6. **JPA entity rename.** `RollerEvent` becomes `PressroomEvent` as an entity name and in its JPQL named queries. The table stays `roller_event`, because `RollerEvent.orm.xml` maps it explicitly with `<table name="roller_event"/>`. Covered by `PersistenceMappingTest` and the full suite.

---

### Task 1: Prepare the tree (nothing moves yet)

**Files:**
- Modify: `app/src/test/java/org/apache/roller/weblogger/build/OldNameInventoryTest.java`
- Modify: `app/src/test/java/org/apache/roller/weblogger/build/ProjectNames.java` (marker comment only)
- Modify: `app/src/test/java/org/apache/roller/weblogger/build/StringClassReferenceTest.java` (remove two legacy-only allowlist entries)
- Delete: `app/src/main/webapp/WEB-INF/jboss-web.xml`, `app/src/main/webapp/WEB-INF/jboss-deployment-structure.xml`, `app/src/test/resources/adminapi-testdata/`

**Interfaces:**
- Produces:
  - `OldNameInventoryTest.PHASE_ONE_KINDS = Set.of("PACKAGE", "CLASS", "PATH-PACKAGE", "PATH-CLASS")`
  - `PHASE_ONE_AREAS = Set.of("main-java", "main-resources", "test", "it", "build")`
  - `static Map<String, Integer> phaseOneLeftovers(Map<String, Integer> counts)`: the entries of `counts` whose key is `<kind>/<area>` with kind and area in those sets, and whose value is greater than 0.
  - The line marker `rebrand:keep`, which Task 2's script honours.

- [ ] **Step 1: Write the failing tests** in `OldNameInventoryTest`:
  - `phaseOneLeftoversPicksOnlyLayerOneKeys`: `phaseOneLeftovers(Map.of("PACKAGE/main-java", 3, "OTHER/main-java", 9, "CLASS/docs", 2, "PATH-CLASS/test", 0))` equals `Map.of("PACKAGE/main-java", 3)`.
  - `layerOneIsEmptyOnceMoved`: if `!ProjectNames.currentRoot().equals(ProjectNames.LEGACY_ROOT)`, assert `phaseOneLeftovers(<the actual counts the ratchet computes>)` is empty. The failure message lists every key and the five files that contribute most to it. Before the move, the guard makes it pass, and the javadoc says so: it arms itself when the root changes.
- [ ] **Step 2: Run, and confirm it fails because `phaseOneLeftovers` doesn't exist yet.** Then implement it and run again.

  Run: `mvn -q -pl app test -Dtest=OldNameInventoryTest`
  Expected: PASS.
- [ ] **Step 3: Add `// rebrand:keep` to every line that must keep the legacy name through the move.** If a line can't take a trailing comment (inside a string concatenation, say), put `// rebrand:keep` on the line itself after the statement.
  - In `ProjectNames.java`: the `LEGACY_ROOT = "org.apache.roller"` line.
  - In `OldNameInventoryTest.java`: the `PACKAGE`, `CLASS` and `OTHER` pattern lines, and every self-test line whose fixture text contains `Roller`, `ROLLER` or `org/apache/roller`. For example `count("RollerSession")`, the `RollerSession.java` path fixture, and the `"Roller"` probe.

  List each marked line in the report.
- [ ] **Step 4: Delete the dead files.**
  - **The two JBoss descriptors.** The app runs only on embedded Tomcat, and Spring Boot ignores `jboss-*.xml`. They hold `org.apache.roller.configuration`, `RollerDS` and `RollerMail`, which are not classes.
  - **`adminapi-testdata/`.** Confirm first that `grep -rn adminapi-testdata app it-selenium` finds nothing. This folder holds the only `purl.org/apache/roller` namespace.
  - **The two allowlist entries in `StringClassReferenceTest`.** Remove the `org.apache.roller.configuration` and `org/apache/roller/aapp` entries, because `everyAllowlistEntryIsStillPresent` now requires it. This is the one Phase 0 test edit beyond the inventory (controller ruling).
- [ ] **Step 5: Rebaseline and verify.** Rebaseline `OldNameInventoryTest.BASELINE` from the failure output; only decreases are expected. Then run the Phase 0 tests:

  Run: `mvn -q -pl app test -Dtest='OldNameInventoryTest,StringClassReferenceTest,ClassNameResolverTest,MainJavaStringNameTest'`
  Expected: PASS.
- [ ] **Step 6: Commit**

  ```bash
  git commit -m "test(rebrand): phase 1 preparation — layer-one leftovers check, keep markers, dead JBoss files removed"
  ```

### Task 2: The move script, reviewed before it runs

**Files:**
- Create: `docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh` (it sits under `docs/superpowers/**`, so the inventory never counts it)

**Interfaces:**
- Produces: `docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh [--dry-run]`, run from the repo root.
  - **Exit status:** 0 on success, non-zero on any refusal.
  - **Refuses:**
    - when the working tree is dirty (`git status --porcelain` is not empty);
    - when any destination path already exists;
    - when the identifier list collides: a `Pressroom*` name that already exists as a tracked file or identifier.
  - **`--dry-run`** prints these sections and changes nothing:
    - `MOVES <n>`, followed by the first 20 `old -> new` lines;
    - `IDENTIFIERS`, the full rename map;
    - `SUBSTITUTED_FILES <n>`;
    - `KEPT_LINES` (`file:line`);
    - `NOT_RENAMED`, every distinct `\bRoller[A-Z]\w*` token in the file set that is not on the identifier list, with its count.

The script, rule by rule (the implementer writes the bash):

1. **File set.** Take every path from `git ls-files` under `app/`, `it-selenium/`, `.github/` and `config/`, plus these files:
   - `pom.xml`, `docker-compose.yml`, `nbactions.xml`, `version-rules.xml`
   - `CLAUDE.md`, `README.md`, `docker_deployment.md`, `RollerSSO.md`
   - `docs/dev/*.md`, `deploy/.env.example`

   Remove every excluded path in Global Constraints. Skip binary files (any NUL byte in the first 8 KB).
2. **Moves.** For each tracked file whose path contains `/org/apache/roller/`, compute the new path:
   - first `/org/apache/roller/weblogger/` → `/com/wikantik/pressroom/`;
   - otherwise `/org/apache/roller/` → `/com/wikantik/pressroom/`.

   Then rename the basename by the identifier map (rule 3), so for example `RollerSession.java` → `PressroomSession.java` and `RollerEvent.orm.xml` → `PressroomEvent.orm.xml`. Run `mkdir -p` on the target directory, then `git mv` the file individually. Moving file by file merges `util` into the existing `pressroom/util`; there is no class-name collision, which was checked in Phase 0. Afterwards remove the now-empty `org/` directories.
3. **Identifier map.** Collect the basename (without its extension) of every tracked file matching `Roller*.java` under `app/` and `it-selenium/`, plus the fixed extras `RollerEditor`, `RollerContent` and `RollerPU`. Map each `RollerX` to `PressroomX`. Wiki names (`RollerHub`, `RollerMcp`, `RollerOnDocker`, `RollerAdminAccountRunbook`, `RollerTelemetryHandoffSpec`, `RollerBlogPlatform`, `RollerW3tPlugins`) and `RollerSSO` are never in it.
4. **Substitutions.** These run with `perl -pi` over the file set, skipping lines that contain `rebrand:keep`, in this order:
   1. `org\.apache\.roller\.weblogger` → `com.wikantik.pressroom`
   2. `org/apache/roller/weblogger` → `com/wikantik/pressroom`
   3. `org\.apache\.roller` → `com.wikantik.pressroom`
   4. `org/apache/roller` → `com/wikantik/pressroom`
   5. Each identifier in the map as a whole word: `\bRollerX\b` → `PressroomX`. Apply the longest names first, so `RollerViewResolverTest` is matched before `RollerViewResolver`.
5. **Maven coordinates** (exact-string edits):
   - `<artifactId>roller-project</artifactId>` → `pressroom-project` (all three poms)
   - `<artifactId>roller-webapp</artifactId>` → `pressroom` (app pom, and the IT pom's dependency)
   - `<artifactId>roller-selenium-tests</artifactId>` → `pressroom-selenium-tests`

   `groupId` is covered by substitution 3.
6. **Staging.** End with `git add -A -- <file set>`. The script does not commit.

- [ ] **Step 1: Write the script** to the rules above, with `set -euo pipefail`.
- [ ] **Step 2: Do a dry run on the clean tree.** Run `bash docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh --dry-run`, then check that:
  - `git status --porcelain` is still empty afterwards;
  - `MOVES` is about 1,300: the inventory's PATH-PACKAGE counts for main-java (573) and test (678), plus the main resources and the it-selenium files;
  - `IDENTIFIERS` has every `Roller*.java` basename plus the three extras;
  - `NOT_RENAMED` contains only wiki/doc names and `RollerSSO`;
  - `KEPT_LINES` lists exactly the lines marked in Task 1.

  Paste the dry-run output into the report.
- [ ] **Step 3: Test the refusals.** Append a line to `README.md`, run the script, and see it refuse because the tree is dirty. Then run `git checkout -- README.md`.
- [ ] **Step 4: Commit the script**

  ```bash
  git add docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh
  git commit -m "chore(rebrand): phase 1 package-move script (dry-run reviewed)"
  ```

### Task 3: Run the move

**Interfaces:**
- Consumes: the Task 2 script and the Task 1 keep markers.
- Produces:
  - one commit, `refactor(rebrand): move packages to com.wikantik.pressroom and rename Roller* identifiers` (MOVE_SHA), containing only the script's output;
  - one follow-up commit with the inventory rebaseline and `.git-blame-ignore-revs`.

- [ ] **Step 1: Record the starting state.** Check the tree is clean and record `git rev-parse --short HEAD` as PRE_MOVE. Then run `bash docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh`.
- [ ] **Step 2: Compile.** Run `mvn -q clean test-compile`. Expected: success.

  If it fails, do not hand-edit. Run `git reset --hard PRE_MOVE && git clean -fd -- app it-selenium`, fix the script (and commit that fix on its own), then go back to Step 1. Hand edits are allowed only after the move commit, in Step 5, and only for problems the script cannot express. Each one gets its own commit and a line in the report.
- [ ] **Step 3: Run the Phase 0 tests.**

  Run: `mvn -q -pl app test -Dtest='ClassNameResolverTest,StringClassReferenceTest,MainJavaStringNameTest,PressroomHandlerInterceptorApiPackageTest,ConfiguredClassListTest,GatePackagePatternTest,PersistenceMappingTest,LogRoutingTest,RenderedTemplateCoverageTest,ThemeReferenceLeakTest,OldNameInventoryTest,MenuHelperTest'`
  Expected: everything passes except `OldNameInventoryTest.oldNameCountsMatchTheBaseline`, which reports decreases ("fell to N"). `layerOneIsEmptyOnceMoved` must pass.

  If any other Phase 0 test fails, the move is incomplete. Go back to Step 2's reset-and-fix-the-script loop.
- [ ] **Step 4: Commit the move.** Stage nothing but the script's output, and commit it with the subject above. Its body says it was produced by the script at its commit SHA, and that the commit is mechanical. Record the result as MOVE_SHA.
- [ ] **Step 5: Follow-up commit.**
  - Rebaseline `OldNameInventoryTest.BASELINE` from the failure output.
  - Create `.git-blame-ignore-revs` with a comment line and MOVE_SHA in full.
  - Make any hand edits Step 2 allowed.

  Commit as `test(rebrand): rebaseline the old-name inventory after the package move; ignore the move in blame`.
- [ ] **Step 6: Check the widened component scan (Review Focus 1).** Run `grep -rlE '@(Component|Service|Repository|Controller|RestController|Configuration|ControllerAdvice|RestControllerAdvice)\b'` over:
  - `app/src/main/java/com/wikantik/pressroom/util/`, limited to the files that came from the old `org/apache/roller/util` (take the list from `git diff --name-status -M PRE_MOVE MOVE_SHA`);
  - `app/src/main/java/com/wikantik/pressroom/PressroomException.java`;
  - `app/src/test/java/com/wikantik/pressroom/testing/` and the test `util/` files that came from the old `org/apache/roller/util`.

  Expected: no hits. If there is any hit, report it as BLOCKED with the file. That is a controller decision (an exclusion filter vs. a move), not a hand fix.

### Task 4: Verify the moved tree end to end

- [ ] **Step 1: Run the full build.** Run `mvn clean install` after the BUSY check. Expected: BUILD SUCCESS, every test passing, `All coverage checks have been met.`, `BugInstance size is 0`, PMD and CPD clean. The JaCoCo floors in the parent `pom.xml` must read exactly `0.9590` / `0.9100`.
- [ ] **Step 2: Run the browser ITs at the root context path.**

  Run: `mvn verify -Pit`
  Expected: BUILD SUCCESS.
- [ ] **Step 3: Run the browser ITs at the `/roller` context path.**

  Run: `mvn verify -Pit -Dit.context.path=roller`
  Expected: BUILD SUCCESS. See `docs/dev/browser-its.md` for the harness, including how it fails on page script errors (Review Focus 4).
- [ ] **Step 4: Confirm the Phase 0 tests are unchanged.** Run `git diff --stat PRE_MOVE..HEAD -M -- $(git ls-files 'app/src/test/java/com/wikantik/pressroom/build/*Test.java' app/src/test/java/com/wikantik/pressroom/build/ProjectNames.java app/src/test/java/com/wikantik/pressroom/config/LogRoutingTest.java app/src/test/java/com/wikantik/pressroom/ui/rendering/RenderedTemplateCoverageTest.java app/src/test/java/com/wikantik/pressroom/business/jpa/PersistenceMappingTest.java)`, then read the diff. Apart from OldNameInventoryTest, every change must be one of these:
  - package and import lines;
  - renamed identifiers (for example `RollerConstants` → `PressroomConstants`);
  - root strings in comments.

  No logic, allowlist or assertion may change. List anything else in the report.
- [ ] **Step 5: Smoke-test the packaged WAR against the dev database.**
  1. Run `./roller db`.
  2. Run `java -Droller.custom.config=app/target/test-classes/roller-boot-dev.properties -jar app/target/roller.war --server.port=8083` in the background.
  3. Check that `curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/roller-ui/login.rol` gives `200`.
  4. Check that `curl -s http://localhost:8090/actuator/health` contains `"UP"`.
  5. Stop the app with `pkill -f "[r]oller.war --server.port=8083"`, then run `./roller stop`.
- [ ] **Step 6: Write the report.** List PRE_MOVE, MOVE_SHA, the follow-up SHA, the inventory before and after (Layer 1 keys at zero), the full-build and IT results, and each hand edit made after the move commit along with its reason.
