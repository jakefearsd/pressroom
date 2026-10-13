# CLAUDE.md

Guidance for Claude Code in this repository. **This file is sent with every
request and to every subagent, so it holds only rules, commands, the hazards
that fail silently, and pointers.** Subsystem knowledge lives in `docs/dev/`
and is read on demand: before changing an area, read its file (index at the
end). New lessons go into the matching `docs/dev/` file, not here — keep this
file under ~20 KB. (It was 115 KB on 2026-09-20: ~55K tokens re-sent on every
request, ~73% of a session's input.)

## Important Rules

- **Never commit or push unless explicitly asked.**
- **A dispatched agent may commit its own work. No agent may ever push.**
  A local commit is how a background agent *finishes* (undone by one
  `git reset`). A push is outward-facing (CI, other people; on a `v*.*.*`
  tag it publishes container images), so only the top-level session pushes,
  only when asked in so many words — never a subagent, reviewer, or agent
  that believes it is finishing up. Written down because a read-only agent
  once pushed to `master`, noticed only when `HEAD` had moved. An agent that
  believes the work needs pushing says so in its report and stops.
- **Work directly on `master`.** Solo-developer repo; no feature branch unless explicitly asked.
- **Ship work; don't let agents sit idle.** Dispatch the next piece rather
  than waiting for the current one to wrap up: reviews are read-only, so
  the next implementer runs alongside the previous reviewer. Prepare briefs
  ahead so a dispatch is instant. Nothing in flight is a planning failure, not a pause.
  **The one hard serialisation is the build.** Implementers share
  `app/target/`, so two concurrent `mvn -pl app test` runs clobber each
  other's output — never run two builds at once in one working tree;
  everything else (reviews, briefs, greps) overlaps freely.
  The busy check must be **bracketed and scoped to this repo**:

  ```bash
  pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR
  ```

  An unbracketed `pgrep -f surefirebooter` matches the checking command
  itself, so a `while pgrep …; do sleep; done` loop waits on itself forever
  (the same self-match makes `pkill -f "spring-boot:run"` kill its own
  shell; use `[s]pring-boot` there too). An unscoped pattern matches other
  checkouts' builds. Never poll for a lock as a separate background step —
  inline the wait in the build command.
- **Parallelising implementers means git worktrees — and a worktree's base
  MUST be pinned and verified before you dispatch into it.** Each worktree
  has its own `app/target/` and git index, so builds need no serialising.
  But a worktree branched from a stale commit silently *reverts* everything
  else that changed in those files since the branch point — and the result
  compiles, passes tests and gates. This has happened here (two agents ~22
  commits behind master, files carrying a prior wave's charset,
  resource-handling, `volatile` and suppression fixes).

  Before dispatching into a worktree:

  ```bash
  git -C <worktree> rev-parse --short HEAD        # is this the base you meant?

  # Does the agent's scope overlap anything changed on master since that
  # base? LC_ALL=C on BOTH sides: a locale-sensitive sort makes comm print
  # "input is not in sorted order" and a still-empty (reassuring) result.
  git diff --name-only <base>..<branch> | LC_ALL=C sort > /tmp/a
  git diff --name-only <base>..master   | LC_ALL=C sort > /tmp/b
  LC_ALL=C comm -12 /tmp/a /tmp/b

  # Cross-check with git itself (no sorting needed, authoritative):
  git merge-tree --write-tree <branch> master >/dev/null && echo clean
  ```

  A non-empty overlap means a merge can revert real work. **Do not resolve
  a large overlap by cherry-picking or rebasing** — a bad resolution reverts
  a fix silently. Discard and redo on the correct base; the discarded
  agent's report (written to the main checkout, never inside the worktree)
  survives as a checklist. Corollary: **a worktree on a stale base may not
  even contain the quality gates**, so its `mvn verify` proves less than it
  looks (missing plugins in its `pom.xml` caught the case above).
- **All development is test-driven.** Write the failing test first, run it
  and watch it fail for the reason you expect, then write the minimum code
  that makes it pass. A test never seen to fail has not been shown to test
  anything — "wrote the code, then added a passing test" is not TDD; the
  order is what makes the test a specification.
- **Acceptance criteria live in the spec, not in the code review.** Work
  large enough for a spec under `docs/superpowers/specs/` states there what
  "done" means, concretely enough to write a test against; a plan's tasks
  derive their tests from it. A requirement no test could check is not yet
  a criterion — sharpen it or drop it.
- **Characterisation tests are the exception that proves the rule.** When
  extracting or refactoring existing behaviour, the test is written first
  and expected to pass immediately against the old code, proving the
  refactor changed nothing. Say so in the test's javadoc, or a later reader
  takes it for one written backwards.

- **A `catch` that logs and falls through is fine in a display path and a
  bug in a decision path; the difference is what the caller does with the
  answer.** ~170 in this tree, mostly correct (a sidebar that cannot load
  renders empty). The wrong ones share a shape:

  ```java
  private void myValidate(...) {
      try {
          if (weblogManager.getTemplateByName(weblog, bean.getName()) != null) {
              addError(model, "pagesForm.error.alreadyExists", ...);
          }
      } catch (WebloggerException ex) {
          log.error("Error checking page name uniqueness", ex);   // <-- no error added
      }
  }
  // caller:
  myValidate(bean, template, request, model);
  if (!hasErrors(model)) { ...save... }
  ```

  The caller reads an empty error list as "passed", so an unreachable store
  let the save through unchecked. **A check that could not run is not a
  check that passed.** `TemplateEditController` and `TemplatesController`
  both did this and now add an error; their tests pin it.

  When adding a `catch` that only logs, ask what the caller does next; if it
  proceeds as though nothing was wrong, fail closed. Two sweeps to repeat:
  methods named `*validate*`/`check*` with a log-only catch, and
  `boolean` methods that swallow — what matters is the value they fall
  through to (every one today falls to `false`, i.e. deny or degrade;
  `RollerHandlerInterceptor.preHandle` is fail-closed because a
  swallowed weblog lookup leaves `actionWeblog` null and `enforceSecurity`
  then refuses).

## Build and Development Commands

### Basic Build Commands
```bash
# Full build; tests need Docker (PostgreSQL)
mvn clean install

mvn -DskipTests=true install   # without tests

# Dev server (PostgreSQL + migrations + spring-boot:run with
# roller-boot-dev.properties) at http://localhost:8083, from the ROOT
# (server.servlet.context-path=/). No code may assume a context path;
# DEV_CONTEXT_PATH=/roller reproduces one (see Virtual hosts).
./roller dev

# Or run the packaged WAR (default port 8080). -Droller.custom.config is a JVM
# system property, so it goes BEFORE -jar -- after it, it is an ignored program
# argument and bootstrap fails ("No custom properties file found"). It must
# name a real DB config:
java -Droller.custom.config=app/target/test-classes/roller-boot-dev.properties \
    -jar app/target/roller.war --server.port=8083
# Health check (works before the tier bootstraps). Actuator is on its own
# port, management.server.port=8090 (DispatcherServlet is *.rol only, so 8083
# has no "/" catch-all). Never expose 8090 beyond the deploy host.
# curl http://localhost:8090/actuator/health

# Database-only helpers
./roller db       # PostgreSQL + migrate, no app
./roller migrate  # apply pending migrations
./roller status   # show applied migrations
./roller stop     # stop dev DB (data kept)
./roller reset    # DESTROY dev DB volume and rebuild
```

### Testing
```bash
mvn test                        # all tests (need Docker: Testcontainers PostgreSQL 18)
mvn test -Dtest=TestClassName   # one class
mvn clean test && mvn jacoco:report -pl app  # coverage: app/target/site/jacoco/index.html
mvn verify -Pit                 # browser ITs against the packaged WAR (docs/dev/browser-its.md)
```
Fixture, mock and render-cache conventions for tests: `docs/dev/build-and-ci.md`.

## Gates (each one fails the build)

- **JaCoCo floors** LINE 0.9330 / BRANCH 0.8670 / PACKAGE 0.80 (parent
  `pom.xml`). Floors only move up, sit a few tenths under measured, and are
  measured **without** `cwebp` on `PATH`.
- **Diff coverage ~90%** on changed lines (`bin/check-diff-coverage.sh`),
  judged over the pushed range. A mechanical sweep failing it is expected:
  accept the red run, never add assertion-free tests to satisfy it.
- **PMD 7.27.0 (pinned — the count depends on it; quickstart + security + 21
  named rules), CPD @110 tokens, SpotBugs 4.10.4 with find-sec-bugs 1.14.0:
  zero violations.** A rule is excluded only as a whole family that is
  systematically not a defect here, with a reason in `config/pmd/ruleset.xml`
  / `config/spotbugs/exclude.xml` (`QualityGatePomTest` pins the set);
  one-offs are suppressed at the site with a reason. `bin/quality-report.sh
  [Rule]` lists sites. A tool bump that finds things gets fixes, not wider
  exclusions.
- **javac `-Xlint:all` with `failOnWarning`**: zero warnings in main and test;
  the disabled lints are exactly `options`, `processing`, `path` and
  `this-escape` (`QualityGatePomTest`).
- **SLF4J**: a `Throwable` stays the LAST argument and no `{}` consumes it,
  or the stack trace is silently dropped.
- **i18n ratchets** (`MessageKeyTest`, `MessagePlaceholderContractTest`,
  `MessageFormatRegressionTest`) assert against `Set.of()` — empty an
  offender set, never add to it.
- **CI** (`.github/workflows/main.yml`): unit suite + diff coverage on every
  push (~3 min, installs `cwebp`); browser ITs nightly 04:00 UTC / on PR / on
  demand; CodeQL weekly. **Publishing happens only on a `v*.*.*` tag**, and
  `IMAGE_VERSION` has no floating default. Run `mvn verify -Pit` before a
  release.

Details and history: `docs/dev/quality-gates.md`, `docs/dev/build-and-ci.md`.

## Architecture in one screen

- Spring Boot 4.1 executable WAR, embedded Tomcat 11, **Java 25 LTS, held
  until Java 29** — never bump to 26/27/28 (the seven pins that move
  together: `docs/dev/architecture.md`).
- Spring MVC `@Controller`s on `*.rol` (plus `/api/*`, SEO patterns,
  `/newsletter/*`); JSP/JSTL admin UI with a homegrown tiles resolver;
  Velocity for blog rendering; JPA/EclipseLink on **PostgreSQL 18 only**;
  embedded Lucene.
- **No static service locator.** Container-managed classes take
  `@Lazy Weblogger` in the constructor (the `@Lazy` is load-bearing);
  entities under `pojos/` import no business-tier type.
- **One authorization path**: `RollerHandlerInterceptor`, shared by the JSP
  UI and `/api/v1`; it vouches only for the action weblog, so every by-id
  lookup goes through `WeblogOwnership` / `BaseController.lookup*`. The API
  answers 404, never 403, for anything the caller may not see.
- **Content**: shortcodes expand, then commonmark, then `HTMLSanitizer` —
  the only content boundary.
- **Schema**: every schema change adds `bin/db/migrations/V<NNN>__*.sql`
  (idempotent; never edit one applied beyond local dev). Three appliers read
  the same files (`migrate.sh`, `DatabaseInstaller`, the test harness).
- **Production is image-only**: the host holds `docker-compose.prod.yml` and
  `.env`; nothing is bind-mounted (`ProductionComposeTest`). Runbook:
  `docker_deployment.md`.

## Hazards that fail silently

Each has shipped at least once. Check the relevant ones before calling work
done.

- **Velocity is lenient**: a reference to a deleted Java member prints as
  literal text, no error. Deleting any member a `.vm` can reach means
  grepping `app/src/main/webapp/themes` and `app/src/main/webapp/WEB-INF/velocity`.
- **A zero-argument macro written bare in a comment is invoked** (`//` and
  `/* */` alike): write "the showX macro", never `#showX`.
- **A JSP tag with an undeclared prefix renders as literal text.**
- **Escaping**: entry titles are stored escaped (emit `$entry.title` bare);
  page titles are raw (always `$utils.escapeHTML`). `<spring:message
  arguments>` and `tiles/messages.jsp` do **not** escape, so controllers
  escape user-typed arguments (`MessageArgumentEscapingTest`). Author-controlled
  EL in JSPs goes through `fn:escapeXml`, `bean.*` form fields included
  (`EditorJspEscapingTest`). An expression-built `href` is `fn:escapeXml`'d,
  a `<c:url>`, or a bare `urls.*` call.
- **Name every `@RequestParam`/`@PathVariable`**: the build has no
  `-parameters`, so a bare one passes unit tests and throws at runtime.
- **Rendered pages expire only through `weblog.lastModified`**;
  `CacheManager.invalidate` never reaches `WeblogPageCache`/`WeblogFeedCache`.
- **Confirmations are `data-confirm`**, never inline `onclick="return
  confirm(...)"` (one apostrophe and it fails open).
- **Never restyle an admin page by renaming a selector**: `Routes` pins a CSS
  marker per route; update both in one commit.
- **XML/ruleset traps**: a bare `--` in an XML comment breaks
  `runtimeConfigDefs.xml` silently; a literal `{}` anywhere in
  `config/pmd/ruleset.xml` breaks PMD's ruleset merge.
- **No code may assume a context path**: routing changes run the browser
  suite at both `/` and `-Dit.context.path=roller`.

## Module Organization

- **`app/`** - The web application (executable WAR)
- **`bin/db/`** - Schema migrations and the migrate/install scripts
- **`deploy/`** - Production deploy script and Caddy/backup config for
  `docker-compose.prod.yml` (see `docker_deployment.md`)
- **`it-selenium/`** - Browser integration tests (Selenium, `mvn verify -Pit`
  against the packaged executable WAR; docs/dev/browser-its.md)

## Where the rest lives

Read the file before changing its area. Section names are the ones this file
used to carry, so a code comment saying "see CLAUDE.md, <section>" resolves
here.

| Former CLAUDE.md section(s) | Read |
|---|---|
| Frontend build; Testing Commands; CI: three tiers (incl. the `createUser!save.rol` 403, root-caused: `docs/dev/browser-its.md`, "A click is not a barrier") | `docs/dev/build-and-ci.md` |
| Coverage gates; Static-analysis gates | `docs/dev/quality-gates.md` |
| The IT harness cleans up by identity; Permutation coverage; Browser ITs run class-parallel; BrowserHealth; Run the browser suite at BOTH context paths | `docs/dev/browser-its.md` |
| Database; Schema changes | `docs/dev/database.md` |
| Architecture Overview (DI rules, packages, patterns, Security Architecture, Database Schema, Search) | `docs/dev/architecture.md` |
| Theme System; Themes; Templates (render-cache expiry, loader caching, Velocity leniency) | `docs/dev/themes-and-templates.md` |
| Media Pipeline | `docs/dev/media.md` |
| SEO; Travel; Plugin System; Shortcodes | `docs/dev/content-features.md` |
| Configuration Files; Development vs Production; Configuration scope | `docs/dev/configuration.md` |
| Passwords; The admin account tool (first admin, lost password) | `docs/dev/passwords.md` |
| Admin UI; Categories | `docs/dev/admin-ui.md` |
| Comments; Entry editing; Trash; Pages | `docs/dev/entries.md` |
| Redirects; Virtual hosts | `docs/dev/routing.md` |
| Comments (reader) | `docs/dev/comments.md` |
| Audience; Analytics | `docs/dev/audience-and-analytics.md` |
| Automation API; MCP endpoint; New-weblog needs | `docs/dev/api.md` (public reference: `docs/api/README.md`) |
| Reader sign-in (SSO) | `docs/dev/sso.md` |
