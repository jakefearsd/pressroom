# Pressroom Phase 0: Safety Net Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before anything is renamed, make the test suite fail on the mistakes a mass rename makes. Those mistakes include a class name written as a string that no longer resolves, a configured class list that silently loads short, a gate whose package pattern matches nothing, an incomplete entity mapping, a logger that loses its route, a template no rendering test reaches, and old-name occurrences that creep back. Also scan production for stored old-name references.

**Architecture:** These are test-only static scanners under a new test package `org.apache.roller.weblogger.build`, next to `QualityGatePomTest`, which share three small helpers. One main-code change (Task 2) turns the class and package names that main Java writes as string literals into compile-checked class references. Every scanner works out the project root from a class reference, not a literal, so after Phase 1 it checks `com.wikantik.pressroom` with no edit. It also checks the legacy root `org.apache.roller`, so a leftover old name fails instead of going unchecked.

**Tech Stack:** JUnit 5 (Jupiter, plain `Assertions`, matching `QualityGatePomTest`), JDK XML DOM, `git ls-files`, Testcontainers PostgreSQL via `TestUtils.setupWeblogger()` (Task 5 only), and `ssh`/`psql` read-only against docker1 (Task 9).

**Spec:** `docs/superpowers/specs/2026-10-29-pressroom-rebrand-design.md`, section "Phase 0: safety net". Acceptance items 1-8 there map to Tasks 1-9 here.

## Global Constraints

- **Phase 0 changes no names.** No package, class, property, path, table, env var or UI string is renamed. Task 2 changes how names are *referenced* (class literal instead of string), never what they are.
- Every test is seen to fail first, for the reason expected. For a scanner, that means two things: a permanent self-test that feeds it a synthetic bad input, and a one-time manual break of a real file, run, then restored with `git checkout -- <file>`. The javadoc of each new test records the manual break under "Proven to bite:", in the style of `ThemeReferenceLeakTest`.
- A test written to pass immediately against today's code is a characterisation test and says so in its javadoc (CLAUDE.md).
- No gate is loosened: JaCoCo floors LINE 0.9590 / BRANCH 0.9100, the package rules, PMD 7.28.0 / CPD @110 / SpotBugs 4.10.5 + find-sec-bugs 1.14.0 with zero violations, javac `-Xlint:all` with `failOnWarning`.
- Repo-relative paths are resolved the way `QualityGatePomTest` does: `Path.of(System.getProperty("user.dir")).getParent()`. Surefire runs in `app/`.
- One build at a time in this tree. Before any `mvn` run: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`.
- Commit after each task. Never push.

## Review Focus

1. **A token that is a class *member*, not a class:** for example `...WebloggerRuntimeConfig.getProperty` in a TLD `function-signature`, or `Outer$Inner`. Expected behaviour: resolved, not reported. The test is `ClassNameResolverTest.resolvesMembersAndNestedClasses` (Task 1).
2. **Old-name lookalikes:** `Controller`, `controller`, `scroller`. Expected behaviour: not counted as an old name. `asyncRoller`, `ROLLER_DB` and `rollerContentScope` are counted. The test is `OldNameInventoryTest.countsOnlyTheBrand` (Task 8).
3. **A JaCoCo `<include>` that names a parent package with no classes of its own:** JaCoCo's PACKAGE element matches exact packages, so the rule would cover nothing. Expected behaviour: reported. The test is `GatePackagePatternTest.aPackageWithOnlySubpackagesIsReported` (Task 4).
4. **A configured list with stray whitespace, a trailing `\` continuation or an empty value** (`rendering.userRendererFactories=`). Expected behaviour: an empty list is allowed, and blanks are trimmed rather than looked up. The test is `ConfiguredClassListTest.parsesContinuationsAndEmptyValues` (Task 3).
5. **An untracked local file** (logs, scratch) in the working tree. Expected behaviour: the inventory counts are the same on every machine and in CI. The test is `OldNameInventoryTest.scansOnlyTrackedFiles` (Task 8).

---

### Task 1: Class names written as strings outside main Java all resolve (spec item 1)

**Files:**
- Create: `app/src/test/java/org/apache/roller/weblogger/build/RepoFiles.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/build/ProjectNames.java`
- Create: `app/src/test/java/org/apache/roller/weblogger/build/ClassNameResolver.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/build/ClassNameResolverTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/build/StringClassReferenceTest.java`

**Interfaces:**
- Produces:
  - `RepoFiles.repoRoot(): Path`
  - `RepoFiles.trackedFiles(): List<Path>`: repo-relative paths from `git ls-files -z`, run in `repoRoot()`. The test fails if git exits non-zero.
  - `RepoFiles.read(Path repoRelative): String`
  - `RepoFiles.xml(Path repoRelative): org.w3c.dom.Document`: a namespace-aware parser with external entities and DTD loading disabled.
  - `ProjectNames.LEGACY_ROOT = "org.apache.roller"`: the only literal of the old root in this package.
  - `ProjectNames.currentRoot(): String`: the parent package of `org.apache.roller.util.RollerConstants`, written as `RollerConstants.class.getPackageName()` minus its last segment.
  - `ProjectNames.roots(): Set<String>`: `{currentRoot(), LEGACY_ROOT}`. The two are equal today.
  - `ProjectNames.QUALIFIED_NAME: Pattern`: a dotted token (`<root>(\.[A-Za-z_$][\w$]*)+`) or a slashed token (`<root-with-slashes>(/[\w$.-]+)+`) for each root in `roots()`.
  - `ClassNameResolver.unresolved(String token): Optional<String>`: empty when the token resolves. Otherwise it holds a reason. A dotted token resolves if any of these holds, tried in order:
    - It loads with `Class.forName(token, false, loader)`.
    - It is a package containing at least one class under `app/target/classes` or `app/target/test-classes`.
    - Its last segment is lowercase-initial and the rest resolves as a class (a member reference).
    - Replacing its last `.` with `$` loads (a nested class).

    A slashed token resolves if it is a classpath resource or a package directory under those two class roots.

- [ ] **Step 1: Write the failing resolver self-tests** in `ClassNameResolverTest`:
  - `resolvesARealClassPackageAndResource`: `unresolved("org.apache.roller.weblogger.config.WebloggerConfig")`, `unresolved("org.apache.roller.weblogger.config")` and `unresolved("org/apache/roller/weblogger/pojos/Weblog.orm.xml")` are all empty. Build the strings from `ProjectNames.currentRoot()` plus a suffix, not as literals.
  - `resolvesMembersAndNestedClasses`: `<root>.weblogger.config.WebloggerRuntimeConfig.getProperty` is empty.
  - `reportsATypo`: `<root>.weblogger.config.WebloggerConfg` is present.
  - `reportsAMissingResource`: `<root-slashed>/weblogger/pojos/Nope.orm.xml` is present.
  - `qualifiedNamePatternFindsBothRoots`: `QUALIFIED_NAME` finds exactly the two tokens in `"a org.apache.roller.x.Y, b <currentRoot>.z.W;"` and stops at `,` and `;`.

- [ ] **Step 2: Run, and confirm it fails because the classes don't exist yet**

  Run: `mvn -q -pl app test -Dtest=ClassNameResolverTest`
  Expected: a compilation failure naming `ClassNameResolver` / `ProjectNames`.

- [ ] **Step 3: Implement `RepoFiles`, `ProjectNames` and `ClassNameResolver`** with the signatures above. In each class's javadoc, say why the root is derived from a class: after the Phase 1 move the scanners follow the code with no edit, and `LEGACY_ROOT` keeps old tokens in scope.

- [ ] **Step 4: Run, and confirm it passes**

  Run: `mvn -q -pl app test -Dtest=ClassNameResolverTest`
  Expected: PASS.

- [ ] **Step 5: Write `StringClassReferenceTest.everyQualifiedNameOutsideMainJavaResolves`**. It is a characterisation test.
  - **Scope:** every tracked file under:
    - `app/src/main/resources/`
    - `app/src/main/webapp/`, only `*.jsp`, `*.jspf`, `*.tld`, `*.xml`, `*.properties` and `*.vm`
    - `app/src/test/resources/`
    - `config/`
    - the files `pom.xml` and `app/pom.xml`
    - inside double-quoted string literals only: `app/src/test/java/**/*.java`
  - **Excluded:** `it-selenium/`. Its classes are not on this classpath, and Task 8 counts it.
  - **Behaviour:** every `QUALIFIED_NAME` match must have an empty `unresolved()`, or be a key of `ALLOWED_UNRESOLVED: Map<String, String>` (token to reason). The failure message lists `file:line token reason` for each offender.
  - **Starting allowlist:** the implementer fills it from the first run. Each entry gets a reason saying why it is not a class. Expected entries are the JBoss module name `org.apache.roller.configuration` in `WEB-INF/jboss-deployment-structure.xml`, and the Maven `groupId` if it does not resolve as a package (it does today). Any other entry is reported back to the controller before it is accepted.
  - **Second test:** `everyAllowlistEntryIsStillPresent`. An allowlisted token that no longer occurs anywhere in scope fails, so the allowlist shrinks as the names go.

- [ ] **Step 6: Run, and confirm it passes on today's tree**

  Run: `mvn -q -pl app test -Dtest=StringClassReferenceTest`
  Expected: PASS.

- [ ] **Step 7: Prove it bites.** In `app/src/main/webapp/WEB-INF/velocity.properties`, change `ThemeResourceLoader` to `ThemeResourceLoadr`. Run the test and see it fail, naming that file, the line and the token. Restore the file with `git checkout`. Record this in the javadoc.

- [ ] **Step 8: Commit**

  ```bash
  git add app/src/test/java/org/apache/roller/weblogger/build/
  git commit -m "test(rebrand): every class name written as a string outside main Java resolves"
  ```

### Task 2: Main Java names its own classes and packages by reference, not by string (spec item 1, Java side)

**Files:**
- Modify:
  - `app/src/main/java/org/apache/roller/weblogger/config/WebloggerConfig.java:121`
  - `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/ApiRouteTable.java:19`
  - `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptor.java:346`
  - `app/src/main/java/org/apache/roller/weblogger/util/cache/CacheManager.java:54`
  - `app/src/main/java/org/apache/roller/weblogger/ui/restapi/ApiExceptionHandler.java:18`
  - `app/src/main/java/org/apache/roller/weblogger/boot/RollerApplication.java:52`
- Test: `app/src/test/java/org/apache/roller/weblogger/build/MainJavaStringNameTest.java`
- Test, if no existing test pins API-handler detection: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerHandlerInterceptorApiPackageTest.java`

**Interfaces:**
- Consumes: `RepoFiles.trackedFiles()`, `ProjectNames.QUALIFIED_NAME` (Task 1).

- [ ] **Step 1: Pin the security-relevant behaviour before touching it.** `RollerHandlerInterceptor`'s API-package check decides which authorization path a handler takes. Check `ApiRouteTableTest` and the interceptor's tests for an assertion of this exact behaviour:
  - a v1 controller, the MCP endpoint's package and `ui.restapi` itself count as API;
  - a JSP `@Controller` under `ui.controllers` does not;
  - a package that merely *starts with* the prefix text, say `ui.restapiX`, does not.

  If any of these is missing, write it as a characterisation test that passes now.

- [ ] **Step 2: Write `MainJavaStringNameTest.noMainJavaStringLiteralNamesAProjectClassOrPackage`.**
  - **Scope:** every double-quoted literal in tracked `app/src/main/java/**/*.java` files that matches `QUALIFIED_NAME` (code only, not comments).
  - **Behaviour:** each match fails the test, unless it is a key of `NOT_A_CLASS_NAME: Map<String, String>`. That map has exactly one entry: `org.apache.roller.weblogger.rollersession`, with the reason "session attribute key that only looks like a package; renamed with the production names".
  - **Expected first run:** FAIL, listing the six sites in the Files list.

- [ ] **Step 3: Run, and confirm it fails listing those six sites**

  Run: `mvn -q -pl app test -Dtest=MainJavaStringNameTest`
  Expected: FAIL naming the six sites.

- [ ] **Step 4: Replace each literal with a reference the compiler checks**, without changing its value:
  - `WebloggerConfig`: use `WebloggerConfig.class` in place of the `Class.forName` string.
  - `ApiRouteTable.API_PACKAGE`: set it to `<a v1 controller>.class.getPackageName()`.
  - `RollerHandlerInterceptor.API_PACKAGE_PREFIX`: set it to `ApiExceptionHandler.class.getPackageName()`.
  - `CacheManager`'s default: use `ExpiringLRUCacheFactoryImpl.class.getName()`.
  - `ApiExceptionHandler`: use `@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)`.
  - `RollerApplication`: use `scanBasePackageClasses = WebloggerException.class`, with a one-line comment saying it stands for the root package.
  - Javadoc and comments that quote the old literal (`WebMvcConfig`, `RollerLifecycle`) are text, not literals, so leave them alone.

- [ ] **Step 5: Run, and confirm it passes, along with the neighbours**

  Run: `mvn -q -pl app test -Dtest='MainJavaStringNameTest,ApiRouteTable*,RollerHandlerInterceptor*,CacheManager*,WebloggerConfig*'`
  Expected: PASS.

- [ ] **Step 6: Commit**

  ```bash
  git commit -am "refactor(rebrand): main Java names its classes and packages by reference, not by string"
  ```
  Also `git add` the new test files before committing.

### Task 3: Configured class lists load completely and with the right type (spec item 2)

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/build/ConfiguredClassListTest.java`

**Interfaces:**
- Consumes: `RepoFiles` (Task 1).

- [ ] **Step 1: Write the tests.** Read `roller.properties` with `WebloggerConfig.class.getResourceAsStream("roller.properties")`, which follows the package after the move. Read `WEB-INF/velocity.properties` with `RepoFiles.read`. Also read every `*.properties` file under `app/src/main/resources` and `app/src/test/resources` that overrides one of the keys below. Then:
  - `everyConfiguredClassLoadsAndHasTheExpectedType`: for each entry of `EXPECTED_TYPES: Map<String, Class<?>>`, every comma-separated, trimmed, non-blank class name in the value:
    - loads;
    - is concrete;
    - is assignable to the expected type;
    - has a public no-argument constructor (what `Reflection.newInstance` needs).

    The `EXPECTED_TYPES` entries:
    - `tasks.<name>.class`, for each name in `tasks.enabled`: `RollerTask`
    - `cache.defaultFactory`: `CacheFactory`
    - `rendering.rollerRendererFactories`, `rendering.userRendererFactories`: `RendererFactory`
    - `rendering.pageModels`, `feedModels`, `searchModels`, `previewModels`, `siteModels`: `Model`
    - `lucene.analyzer.class`: `org.apache.lucene.analysis.Analyzer`
    - `eclipselink.logging.logger`: `org.eclipse.persistence.logging.SessionLog`
    - from `velocity.properties`, `resource.loader.<n>.class` for each `<n>` in `resource.loaders` that has one: `org.apache.velocity.runtime.resource.loader.ResourceLoader`
  - `everyEnabledTaskAndLoaderHasAClass`: each name in `tasks.enabled` has a `tasks.<name>.class`. Each custom loader named in `resource.loaders` has a `resource.loader.<n>.class`. Velocity's built-in `class` loader may have none.
  - `everyClassValuedPropertyIsChecked`: any property in those files whose value contains a token shaped like a Java class name (`[a-z]\w*(\.[a-z]\w*)+\.[A-Z][\w$]*`) has its key in `EXPECTED_TYPES`. Keys are compared after replacing the `<name>` / `<n>` parts. This means a new class-valued key cannot go unchecked. If the first run finds a key not listed above, add it with its true type and report it.
  - `parsesContinuationsAndEmptyValues` (Review Focus 4): the list splitter, given `"a.B,\\\n  c.D ,"` and `""`, yields `[a.B, c.D]` and `[]`.

  These are characterisation tests, apart from the splitter test.

- [ ] **Step 2: Run, and confirm it fails because the splitter does not exist yet.** Write the splitter test first so it fails. Then implement `static List<String> classList(String value)` in the test class. Run: `mvn -q -pl app test -Dtest=ConfiguredClassListTest`. Expected: PASS.

- [ ] **Step 3: Prove it bites.** First, in `roller.properties`, change `model.MenuModel` in `rendering.pageModels` to `model.MenuModl`. Run, see the failure, and restore. Second, point `cache.defaultFactory` at `org.apache.roller.weblogger.util.cache.CacheManager` (which loads but has the wrong type). Run, see the failure, and restore. Record both in the javadoc.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/test/java/org/apache/roller/weblogger/build/ConfiguredClassListTest.java
  git commit -m "test(rebrand): configured class lists load completely and with the right type"
  ```

### Task 4: No quality gate's package pattern matches nothing (spec item 3)

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/build/GatePackagePatternTest.java`

**Interfaces:**
- Consumes: `RepoFiles.xml`, `RepoFiles.repoRoot` (Task 1).

- [ ] **Step 1: Write the tests**:
  - `everyJacocoPackageIncludeNamesAPackageWithClasses`:
    - **Scope:** every `<include>` inside a `<rule>` whose `<element>` is `PACKAGE` in the parent `pom.xml`. Today these are the three rendering packages and the five security-boundary packages.
    - **Behaviour:** each must name a directory under `app/src/main/java` that holds at least one `.java` file directly. JaCoCo matches PACKAGE elements exactly, not subpackages. An include containing `*` or `?` must match at least one such package. The assertion message names the rule and the include.
    - Also assert the total is exactly 8, so a dropped include is visible.
  - `everySpotBugsAndPmdLocationNamesSomethingThatExists`: every `Class`, `Package` and `Source` `name` in `config/spotbugs/exclude.xml` must match at least one main class, package or source file. A leading `~` means a regex. Every `exclude-pattern` and `include-pattern` in `config/pmd/ruleset.xml` must match at least one tracked file. If there are none today, assert that the list is empty. A future one then gets checked rather than silently passing.
  - `aPackageWithOnlySubpackagesIsReported` (Review Focus 3): the checker, given `<root>.weblogger.ui` (only subpackages, no direct `.java` files), reports it. Given `<root>.weblogger.ui.rendering`, it does not.

  The first two are characterisation tests. The third is a self-test.

- [ ] **Step 2: Run, and confirm the self-test fails before the checker exists.** Then implement `static Optional<String> packageProblem(String include)` in the test and run again: `mvn -q -pl app test -Dtest=GatePackagePatternTest`. Expected: PASS.

- [ ] **Step 3: Prove it bites.** In the parent `pom.xml`, change the `ui.restapi.mcp` include to `ui.restapi.mcpx`. Run, see the failure, and restore. Record it.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/test/java/org/apache/roller/weblogger/build/GatePackagePatternTest.java
  git commit -m "test(rebrand): no coverage or analysis gate names a package that does not exist"
  ```

### Task 5: The persistence mapping is complete (spec item 4)

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/business/jpa/PersistenceMappingTest.java`

**Interfaces:**
- Consumes: `RepoFiles.xml` (Task 1). For the live entity manager, use the route `EntryCacheSizeTest.java:302-306` already uses: reflect `strategy` off the entries manager, then `getEntityManager(false).getEntityManagerFactory()`.

- [ ] **Step 1: Write the tests**:
  - `persistenceXmlListsExactlyTheMappingFilesThatExist`: the set of `<mapping-file>` values in `META-INF/persistence.xml` (26 today) equals the set of `*.orm.xml` files in the directory of `Weblog.class`'s package resources. Locate that directory from `Weblog.class.getResource("Weblog.orm.xml")`. Each value loads as a classpath resource.
  - `everyMappedClassIsAnEntityAtRuntime`: this one needs `TestUtils.setupWeblogger()`, so it runs against Testcontainers PostgreSQL. The set of `class` attributes on `<entity>`, `<embeddable>` and `<mapped-superclass>` across all mapping files equals the Java types in `emf.getMetamodel().getManagedTypes()`. Compare as sorted class names. The message prints both differences.

  Both are characterisation tests.

- [ ] **Step 2: Run, and confirm it passes**

  Run: `mvn -q -pl app test -Dtest=PersistenceMappingTest`
  Expected: PASS.

- [ ] **Step 3: Prove it bites.** Comment out the `ApiToken.orm.xml` line in `persistence.xml`. Run and see both tests fail. Restore the file and record it.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/test/java/org/apache/roller/weblogger/business/jpa/PersistenceMappingTest.java
  git commit -m "test(rebrand): persistence.xml lists every mapping file and every mapped class is managed"
  ```

### Task 6: Every logger the code names has a decided route (spec item 5)

**Files:**
- Modify (tests only): `app/src/test/java/org/apache/roller/weblogger/config/LogRoutingTest.java`

**Interfaces:**
- Consumes: `ProjectNames.currentRoot()`, `RepoFiles` (Task 1). The named-logger constants it references by symbol:
  - `AdminLog.NAME` (`roller.admin`)
  - `McpToolExecutor.LOG_NAME` (`roller.mcp`)
  - `WeblogRedirectManager.LOG_NAME` (`roller.redirects`)

- [ ] **Step 1: Add the tests**:
  - `theProjectRootPackageHasItsOwnLoggerToFileAndConsole`: main `log4j2.xml` has a `<Logger>` whose `name` equals `ProjectNames.currentRoot()`. It must reach both the `roller` file appender and `console`; use the existing `reaches` helper. If a rename misses this file, the whole app falls through to Root and loses its file log and level.
  - `everyNamedLoggerHasADecidedRoute`:
    - **Inputs:** every name passed as a string literal to `LoggerFactory.getLogger("...")` in tracked main Java, plus the three constants above.
    - **Behaviour:** each name must either have its own `<Logger>` in main `log4j2.xml`, or be a key of `ROOT_ONLY_BY_DESIGN: Map<String, String>`.
    - **Starting map:** `roller.mcp`, `roller.media.import` and `roller.redirects`, if they have no entry today. The reason for each is "characterised 2026-10-29: reaches the console through Root". Confirm each one against the file. Routing is not changed in this phase.
  - `everyNonLiteralGetLoggerArgumentIsAKnownConstant`: in tracked main Java, every `getLogger(<arg>)` whose argument is neither `X.class` nor a string literal must be one of `NAME`, `LOG_NAME`, `WeblogRedirectManager.LOG_NAME` or `AdminLog.NAME`. A new constant-named logger therefore cannot escape the check.

  These are characterisation tests.

- [ ] **Step 2: Run, and confirm it passes**

  Run: `mvn -q -pl app test -Dtest=LogRoutingTest`
  Expected: PASS.

- [ ] **Step 3: Prove it bites.** In main `log4j2.xml`, change the `org.apache.roller` Logger's name to `org.apache.rollerx`. Separately, delete the `roller.sso` Logger. Run each, see the failure, restore, and record.

- [ ] **Step 4: Commit**

  ```bash
  git commit -am "test(rebrand): the root package and every named logger keep a decided route"
  ```

### Task 7: Every shipped template is reached by a rendering test (spec item 6)

`ThemeReferenceLeakTest` already renders the four bundled themes through the real servlets and fails on any unresolved Velocity reference. This task makes sure that coverage cannot silently miss a theme or a `WEB-INF/velocity` template.

**Files:**
- Modify: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/ThemeReferenceLeakTest.java`. Replace the two `{"journal", "portfolio", "travel"}` literals (around lines 157 and 195) with one `static final List<String> CONTENT_THEMES` that both use. `@ValueSource` needs a constant expression, so switch it to `@MethodSource`.
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/RenderedTemplateCoverageTest.java`

- [ ] **Step 1: Write `RenderedTemplateCoverageTest`**:
  - `everyBundledThemeIsRendered`: the set of directories under `app/src/main/webapp/themes` equals `ThemeReferenceLeakTest.CONTENT_THEMES` plus `"frontpage"`.
  - `everyVelocityTemplateHasANamedRenderingTest`: the set of `*.vm` files under `app/src/main/webapp/WEB-INF/velocity` (recursive, repo-relative) equals the keys of `RENDERED_BY: Map<String, Class<?>>`. Each value is a test class that renders that file through a real servlet or a `VelocityRenderer`.
  - For the first version of `RENDERED_BY`, the implementer reads which test reaches each file: `weblog.vm`, `feeds.vm`, `roller-custom.vm`, `templates/error-page.vm`, `templates/error-parse.vm`, and the `templates/feeds/*` and `templates/weblog/*` files. Each mapping is confirmed by reading the test.
  - A file that no test renders is reported back to the controller, not mapped to a test that doesn't render it.

- [ ] **Step 2: Run, and confirm it fails before `CONTENT_THEMES` exists.** Then make the `ThemeReferenceLeakTest` change and run: `mvn -q -pl app test -Dtest='RenderedTemplateCoverageTest,ThemeReferenceLeakTest'`. Expected: PASS.

- [ ] **Step 3: Prove it bites.** Create an empty `app/src/main/webapp/themes/scratch/` with one file. Run, see `everyBundledThemeIsRendered` fail, delete the directory, and record it.

- [ ] **Step 4: Commit**

  ```bash
  git add app/src/test/java/org/apache/roller/weblogger/ui/rendering/
  git commit -m "test(rebrand): every bundled theme and Velocity template is reached by a rendering test"
  ```

### Task 8: Old-name inventory ratchet (spec item 7)

**Files:**
- Test: `app/src/test/java/org/apache/roller/weblogger/build/OldNameInventoryTest.java`

**Interfaces:**
- Consumes: `RepoFiles.trackedFiles()`, `RepoFiles.read` (Task 1).
- Produces:
  - `static Map<String, Integer> count(String text)`, keyed by kind.
  - `static String area(Path repoRelative)`.
  - `BASELINE: Map<String, Integer>`, keyed `"<KIND>/<area>"`. Later phases edit this map.

- [ ] **Step 1: Write the counting self-tests**:
  - `countsOnlyTheBrand` (Review Focus 2): `count(...)` of `"Controller controller scroller"` is empty. `count("asyncRoller ROLLER_DB $rollerContentScope")` gives `OTHER=3`.
  - `classifiesEachKindOnce`:
    - `count("org.apache.roller.x org/apache/roller/y")` gives `PACKAGE=2`;
    - `count("RollerSession")` gives `CLASS=1`;
    - `count("rlr_abc")` gives `TOKEN_PREFIX=1`.

    Each occurrence is counted under exactly one kind, tried in this order: `PACKAGE` (`org\.apache\.roller|org/apache/roller`), `CLASS` (`\bRoller[A-Z]\w*`), `TOKEN_PREFIX` (`rlr_`), then `OTHER` (`Roller|ROLLER|(?<![A-Za-z])roller`). Each match is masked before the next kind is tried.
  - `scansOnlyTrackedFiles` (Review Focus 5): `RepoFiles.trackedFiles()` does not contain a file the test writes into an ignored path (`app/target/old-name-probe.txt`).
  - `areasCoverEveryPath`: `area` maps each of these paths, in order, to the area shown:

    | Path | Area |
    |---|---|
    | `app/src/main/java/...` | `main-java` |
    | `app/src/main/...` | `main-resources` |
    | `app/src/test/...` | `test` |
    | `it-selenium/...` | `it` |
    | `pom.xml`, `*/pom.xml`, `config/...`, `.github/...`, `version-rules.xml`, `nbactions.xml` | `build` |
    | `deploy/...`, `bin/...`, `Dockerfile`, `docker-compose*.yml`, `roller` | `deploy` |
    | any other `*.md` or `docs/...` | `docs` |
    | anything else | `other` |

- [ ] **Step 2: Run, and confirm it fails because `count` and `area` do not exist yet.** Implement them and run: `mvn -q -pl app test -Dtest=OldNameInventoryTest`. The self-tests pass.

- [ ] **Step 3: Add the ratchet, `oldNameCountsMatchTheBaseline`**:
  - **Scope:** every tracked text file, skipping any file whose first 8 KB contains a NUL byte.
  - **Excluded forever, as `KEEPS_OLD_NAME`:**
    - `LICENSE.txt`, `NOTICE.txt`
    - `bin/db/migrations/V001__*` through `V040__*`, matched by version number ≤ 40
    - `docs/superpowers/**`
    - this test file and `ProjectNames.java`
  - **Behaviour:** sum the counts per `"<KIND>/<area>"`.
    - A count above `BASELINE` fails: "old name added in <key>: N > baseline M", followed by the five files that contribute most to that key.
    - A count below `BASELINE` also fails: "<key> fell to N; lower BASELINE to N". This keeps the baseline exact as the phases shrink it.
    - A key missing from `BASELINE` counts as baseline 0.
  - **Javadoc:** say that this ratchet replaces the spec's "grouped by layer". `PACKAGE` and `CLASS` are layer 1. `TOKEN_PREFIX` and `OTHER` in the non-docs areas are layers 2 and 3. `OTHER/docs` is layer 3. Say also that its end state is an empty map, in the style of the i18n ratchets.

- [ ] **Step 4: Fill `BASELINE` from the first run's failure output, then run again.** Run: `mvn -q -pl app test -Dtest=OldNameInventoryTest`. Expected: PASS. Sanity check: `PACKAGE/main-java` should be in the thousands, and the grand total near 25,000 minus the exclusions.

- [ ] **Step 5: Prove it bites.** Add the line `// roller` to any main Java file. Run and see the "added" failure. Restore. Record it.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/test/java/org/apache/roller/weblogger/build/OldNameInventoryTest.java
  git commit -m "test(rebrand): old-name inventory ratchet, baseline before any renaming"
  ```

### Task 9: Read-only production scan (spec item 8)

This is not a test, and it is not code. It produces a report. Nothing on docker1 is written, restarted or changed.

**Files:**
- Create: `docs/superpowers/reports/2026-10-29-pressroom-phase0-production-scan.md`

- [ ] **Step 1: Scan every text column in all three databases.** On docker1 (`ssh docker1.lan`, compose project in `~/roller`), run this for each database (`rollerdb`, `umami`, `listmonk`):

  ```bash
  docker compose exec -T postgres psql -U roller -d <db> -v ON_ERROR_STOP=1
  ```

  Use a read-only transaction (`BEGIN READ ONLY;`). Generate one `count(*) ... WHERE col ILIKE '%roller%' OR col ILIKE '%rlr\_%'` query per `text`/`varchar`/`json`/`jsonb` column in `information_schema.columns` (schema `public`), using `\gexec`. Report every column with a non-zero count.

- [ ] **Step 2: For each non-zero column in `rollerdb`, record the distinct matched contexts.** Take up to 20 per column, each at most 30 characters either side of the match, using `regexp_matches`. Name the row's weblog handle or template name, not its author. Leave out email addresses, password hashes and token digests, and say that they were left out.

- [ ] **Step 3: Look for outside consumers of `rollerdb`.** Check each of these and record every hit:
  - `grep -rn "rollerdb\|grafana_ro"` over `~/source/jakemon` and `~/source/wikantik-main`;
  - `ssh docker1.lan 'grep -rln rollerdb ~ --include=*.yml --include=*.yaml --include=*.env --include=*.alloy 2>/dev/null'`;
  - in `rollerdb`, the `grafana_ro` role's grants (`\dp` on its views).

  If no Grafana datasource is found in files, say so, and list it as a question for Jake.

- [ ] **Step 4: Write the report.** It needs:
  - the date, and the docker1 image version from `docker compose ps`;
  - the per-database tables of hits;
  - the consumers found;
  - a short "Feeds Phase 2" list: each stored reference and what Phase 2 must do about it (migrate, redirect covers it, or leave).

- [ ] **Step 5: Commit**

  ```bash
  git add docs/superpowers/reports/2026-10-29-pressroom-phase0-production-scan.md
  git commit -m "docs(rebrand): phase 0 read-only production scan of stored old-name references"
  ```

### Task 10: Whole-phase verification

- [ ] **Step 1: Run the full build.** Run `mvn clean install` (unit suite, all gates, JaCoCo floors), after the BUSY check. Expected: BUILD SUCCESS, zero PMD / CPD / SpotBugs violations, no javac warnings, floors unchanged in `pom.xml`.
- [ ] **Step 2: Check diff coverage.** Run `bin/check-diff-coverage.sh` over the phase's range (`git merge-base HEAD origin/master`..HEAD). Expected: Task 2's main-code lines are covered.
- [ ] **Step 3: Confirm nothing was renamed.** Run `git diff --stat origin/master..HEAD -- app/src/main bin deploy config pom.xml app/pom.xml`. Expected: only the six Task 2 files.
- [ ] **Step 4: Report** each spec item 1-8 with its test or report, and each "Proven to bite" break. List every allowlist entry added (Tasks 1, 2 and 6) with its reason, and every `RENDERED_BY` file left unmapped (Task 7).
