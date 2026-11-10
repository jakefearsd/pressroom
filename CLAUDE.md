# CLAUDE.md

Guidance for Claude Code in this repository. **This file is sent with every
request and to every subagent, so it holds only rules, commands, the hazards
that fail silently, and pointers.** Subsystem knowledge lives in `docs/dev/`
and is read on demand: before changing an area, read its file (index at the
end). New lessons go into the matching `docs/dev/` file, not here — keep this
file under ~20 KB. (It was 115 KB on 2026-09-20: ~55K tokens re-sent on every
request, ~73% of a session's input.)

<!-- SIMPLE-AGILITY-AGENT-HEADER-START -->
## Simple Agility Architectural Context
This repository is part of the **Simple Agility** small-business ecosystem:
- **Role in Stack:** Canonical customer communication platform ([SA-7](https://wiki.wikantik.com/SimpleAgilityDecisionLog)), hosting public outreach blogs, the reseller showcase weblog, newsletter integration (listmonk), and native agent publishing via `/api/mcp` and API tokens ([PressroomHub](https://wiki.wikantik.com/PressroomHub)).
- **Architecture Hub:** Refer to `SimpleAgilityStackHub` on Wikantik (`https://wiki.wikantik.com/SimpleAgilityStackHub`).
- **Architectural Decisions:** Consult `SimpleAgilityDecisionLog` before changing cross-repo APIs, logging, or telemetry. Pressroom is adopting ECS JSON logging (SA-6) and `/metrics` (Phase 1).
- **Active Initiatives & Coordination:** Check `SimpleAgilityAgentCoordination` (`https://wiki.wikantik.com/SimpleAgilityAgentCoordination`) for active tasks (`SA-INIT-01`, `SA-INIT-02`, `SA-INIT-03`).
- **Session start:** call `get_briefing(clusters=["pressroom"], prompt="<your first task>")` on the Wikantik knowledge MCP; the briefing carries this project's pages and the contracts it cites ([SA-13](https://wiki.wikantik.com/SimpleAgilityDecisionLog)).
- **Role Boundary:** Implement and optimize Pressroom's core Java/Spring Boot capabilities, themes, and automation API; never break cross-product contracts or telemetry conventions.
<!-- SIMPLE-AGILITY-AGENT-HEADER-END -->

## Important Rules

- **Never commit or push unless explicitly asked.**
- **A dispatched agent may commit its own work. No agent may ever push.**
  A local commit is how a background agent finishes (one `git reset` undoes
  it); a push is outward-facing (CI; on a `v*.*.*` tag it publishes
  container images). Only the top-level session pushes, only when asked in
  so many words. An agent that believes the work needs pushing says so in
  its report and stops. (A read-only agent once pushed to `master`.)
- **Work directly on `master`.** Solo-developer repo; no feature branch unless explicitly asked.
- **Ship work; don't let agents sit idle.** Dispatch the next piece rather
  than waiting; reviews are read-only, so the next implementer runs
  alongside the previous reviewer. **The one hard serialisation is the
  build**: implementers share `app/target/`, so never run two
  `mvn -pl app test` in one working tree. The busy check is bracketed and
  repo-scoped (unbracketed, it matches itself; likewise
  `pkill -f "[s]pring-boot:run"`); inline the wait in the build command,
  never poll as a separate step:

  ```bash
  pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || echo CLEAR
  ```
- **Parallel implementers mean git worktrees, and a worktree's base MUST be
  pinned and verified before dispatch.** One branched from a stale commit
  silently reverts master's later changes to the same files, and still
  compiles and passes the gates. Run the base and overlap check in
  `docs/dev/build-and-ci.md`, "Parallel agents in worktrees", first; a
  non-empty overlap means discard and redo on the right base, never
  cherry-pick or rebase through it.
- **All development is test-driven.** Write the failing test first, run it
  and watch it fail for the reason you expect, then write the minimum code
  that makes it pass. A test never seen to fail has not been shown to test
  anything.
- **Acceptance criteria live in the spec, not in the code review.** Work
  large enough for a spec under `docs/superpowers/specs/` states there what
  "done" means, concretely enough to write a test against. A requirement no
  test could check is not yet a criterion.
- **Characterisation tests are the exception**: when refactoring, the test
  is written first and passes immediately against the old code; say so in
  its javadoc, or a later reader takes it for one written backwards.
- **A check that could not run is not a check that passed.** A `catch` that
  only logs is fine in a display path and a bug in a decision path (a
  `*validate*`/`check*` method whose caller reads "no errors" as "passed").
  Fail closed. Example and the sweeps to repeat: `docs/dev/quality-gates.md`,
  "Log-only catches".

## Build and Development Commands

### Basic Build Commands
```bash
# Full build; tests need Docker (PostgreSQL)
mvn clean install

mvn -DskipTests=true install   # without tests

# Dev server (PostgreSQL + migrations + spring-boot:run with
# pressroom-boot-dev.properties) at http://localhost:8083, from the ROOT
# (server.servlet.context-path=/). No code may assume a context path;
# DEV_CONTEXT_PATH=/pressroom reproduces one (see Virtual hosts).
./pressroom dev

# Or run the packaged WAR (default port 8080). -Dpressroom.custom.config is a JVM
# system property, so it goes BEFORE -jar -- after it, it is an ignored program
# argument and bootstrap fails ("No custom properties file found"). It must
# name a real DB config:
java -Dpressroom.custom.config=app/target/test-classes/pressroom-boot-dev.properties \
    -jar app/target/pressroom.war --server.port=8083
# Health check (works before the tier bootstraps). Actuator is on its own
# port, management.server.port=8090 (DispatcherServlet is *.rol only, so 8083
# has no "/" catch-all). Never expose 8090 beyond the deploy host.
# curl http://localhost:8090/actuator/health

# Database-only helpers
./pressroom db       # PostgreSQL + migrate, no app
./pressroom migrate  # apply pending migrations
./pressroom status   # show applied migrations
./pressroom stop     # stop dev DB (data kept)
./pressroom reset    # DESTROY dev DB volume and rebuild

# Release and deploy (docs/dev/build-and-ci.md, "Releasing and deploying")
bin/release.sh 0.2.3 [--skip-verify] [--push]  # verify, bump, commit, tag; push only when asked
bin/deploy-host.sh docker1 [VERSION]           # back up, upgrade, deploy.sh, check the image
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

- **JaCoCo floors** LINE 0.9590 / BRANCH 0.9100, plus two PACKAGE rules:
  rendering L 0.86 / B 0.78 and the security boundary (sso, comments, API
  auth, v1, MCP) L 0.93 / B 0.89 (parent `pom.xml`). Floors only move up, sit
  a few tenths under measured, and are measured **without** `cwebp` on `PATH`.
- **Diff coverage ~90%** on changed lines (`bin/check-diff-coverage.sh`),
  judged over the pushed range. A mechanical sweep failing it is expected:
  accept the red run, never add assertion-free tests to satisfy it.
- **PMD 7.28.0 (pinned — the count depends on it; quickstart + security + 21
  named rules), CPD @110 tokens, SpotBugs 4.10.5 with find-sec-bugs 1.14.0:
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
- **One authorization path**: `PressroomHandlerInterceptor`, shared by the JSP
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
  suite at both `/` and `-Dit.context.path=pressroom`.

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
| Frontend build; Testing Commands; Parallel agents in worktrees; CI: three tiers (incl. the `createUser!save.rol` 403, root-caused: `docs/dev/browser-its.md`, "A click is not a barrier") | `docs/dev/build-and-ci.md` |
| Coverage gates; Static-analysis gates; Log-only catches | `docs/dev/quality-gates.md` |
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
| Front page discovery (`/`, "more from the site", places) | `docs/dev/discovery.md` |
