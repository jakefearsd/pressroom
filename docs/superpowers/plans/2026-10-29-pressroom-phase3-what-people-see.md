# Pressroom Phase 3: What People See (Implementation Plan)

> **For agentic workers:** this plan runs as four parallel implementers in separate worktrees, all pinned to the same base, plus a controller merge task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** remove every remaining old-name occurrence outside the kept files, so that `OldNameInventoryTest.BASELINE` becomes an empty map. The approach:

- UI text, emails, logs, javadoc and comments;
- tests, browser ITs, docs, deploy and build prose;
- `NOTICE.txt` gains the Pressroom derivation line.

**Architecture:** this is a text change only. Every area is disjoint, so Tasks A to D run at once in worktrees pinned to one base.

- Only the controller edits `OldNameInventoryTest.BASELINE`. It does so in Task E, at the merge, when the counts are known.
- An implementer's inventory run therefore fails only with "fell to N; lower BASELINE" messages for its own area's keys. That failure is expected; any other failure is not.

**Spec:** `docs/superpowers/specs/2026-10-29-pressroom-rebrand-design.md` (Phase 3, "What keeps the old name", Anti-goals).

## Global Constraints

- **Display name.** The product is **Pressroom** in prose and `pressroom` wherever a machine reads it. "Apache Roller" and "Roller" in product prose become "Pressroom", in every locale's message file. Brand names are not translated.
- **What stays.** "Weblogger", "weblog" and the `Weblogger*` classes are vocabulary, not brand, and stay.
- **Attribution.** It stays. `LICENSE.txt` is untouched. `NOTICE.txt` keeps the Apache notice verbatim and gains exactly one line, after the first paragraph (Task D):

  `Pressroom is derived from Apache Roller (https://roller.apache.org/), developed at The Apache Software Foundation.`

  Where a doc must say where the code came from (README "Origins", `docs/dev/architecture.md`), one sentence may name Apache Roller. That line ends with the keep marker, written as `<!-- rebrand:keep -->` in Markdown.
- **The keep marker** (`rebrand:keep`) is only for a line that must name the old product:
  - attribution;
  - the `/roller-ui/` redirect and its tests;
  - the `ROLLER_` / `roller.` refusal and its tests;
  - the cutover and rollback scripts;
  - the V041 tests;
  - historical facts such as "0.4.x images were `ghcr.io/jakefearsd/roller`" in the cutover checklist.

  Never mark a line just to make a count go down.
- **Keys and code identifiers stay.** No message key and no Java identifier changes in this phase; text only. If a key's name contains `roller`, leave it and report it. Phase 2 already moved every machine key.
- **i18n ratchets.** `MessageKeyTest`, `MessagePlaceholderContractTest` and `MessageFormatRegressionTest` stay at `Set.of()`.
- **Gates.** No floor moves, no exclusion widens, no assertion-free test.
- **TDD for text.** A test that pins a visible string is updated in the same commit as the string. Where no test pins a user-visible product name on a page, Task A adds one (step A1) before changing the text.
- **Builds.** Each worktree has its own `target/`, so worktree builds may run concurrently. Never run a build in the main checkout while a worktree implementer runs.
- **Commits and pushes.** No pushes. Commit in your worktree with the attribution lines, then write your report to the main checkout path given in your dispatch.

## Review Focus

1. **The admin UI title, login page, colophon and email subjects show "Pressroom"** with no stray "Roller". The ITs and `MessageKeyTest` check this.
2. **A translated message file loses a placeholder or quote** while being edited, so `MessageFormat` breaks. The ratchets catch this.
3. **A browser IT matched on "Roller" text** and silently stops matching, so the test passes vacuously. Each changed IT assertion must still match something; run it.
4. **The docs still point at removed files** (`RollerSSO.md`, `docs/examples`). A grep for each old path shows zero hits.
5. **An attribution line is lost from NOTICE or README.** Task D's test pins NOTICE.

---

### Task A: UI text, templates and browser ITs (areas main-resources and it)

**Files:**
- `app/src/main/resources/**`, `app/src/main/webapp/**` (JSPs, tiles, Velocity, themes, i18n `*.properties` in every locale), `app/frontend/**`
- `it-selenium/**`
- the `app/src/test/**` tests that assert those strings

- [ ] **A1.** Write a failing test, `ProductNameVisibleTest` in `app/src/test/java/com/wikantik/pressroom/build/`. For every `ApplicationResources*.properties` file, every value is checked:
  - no value contains `Roller` (case-sensitive);
  - the default-locale value of each key that previously said "Roller" now contains `Pressroom`. The test pins at least the login page title key, the admin header or site title key, and the email subject keys that named the product.

  Run it and see it fail, listing the offending keys.
- [ ] **A2.** Replace product-name prose in every locale's messages, in JSP and tile text, Velocity templates and themes (`app/src/main/webapp/themes`, `WEB-INF/velocity`), `app/frontend/src`, and the comments in those files. The test from A1 passes.
- [ ] **A3.** Update `it-selenium` prose and assertions. For each assertion that matched old text, run that IT class at least once at `/`.
- [ ] **A4.** Run, in your worktree:
  - `mvn -q -pl app test -Dtest='ProductNameVisibleTest,Message*Test,*Jsp*Test,*Template*Test,*Velocity*Test,*Rendering*Test,OldNameInventoryTest'`;
  - then `mvn -pl app test`.

  The only failures allowed are OldNameInventoryTest's "fell to N" for `PROSE/main-resources`, `PROSE/it`, and any `test`-area key you lowered.
- [ ] **A5.** Commit `feat(rebrand): Pressroom in UI text, templates and browser ITs`.

### Task B: Java prose, logs and test prose (areas main-java and test)

**Files:** `app/src/main/java/**` (log and exception messages, javadoc, comments) and `app/src/test/**`. Do not edit these files, which belong to other tasks:
- message files and their assertion tests (Task A);
- `OldNameInventoryTest` and `ProjectNames` (Task E and kept);
- the keep-marked refusal, redirect and V041 tests.

- [ ] **B1.** Find each test that asserts a log or exception text containing the old name, such as the startup line "Bootstrapping Roller Weblogger business tier". Update the assertion first, run it, and watch it fail.
- [ ] **B2.** Replace the prose in main Java: log text, exception text, javadoc and comments. Those tests now pass.
- [ ] **B3.** Replace prose in `app/src/test/**`: comments, `@DisplayName`, fixture strings that are only labels. A fixture string whose value a test reads back may change only together with its assertion.
- [ ] **B4.** Run `mvn -pl app test` in your worktree. The only failures allowed are OldNameInventoryTest's "fell to N" for `PROSE/main-java` and `PROSE/test`.
- [ ] **B5.** Commit `feat(rebrand): Pressroom in Java prose, logs and tests`.

### Task C: Docs (area docs: every `*.md` outside `docs/superpowers/`, and `docs/**`)

**Files:** `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`, `docker_deployment.md`, `RollerSSO.md` → `PressroomSSO.md` (`git mv`), `docs/README.md`, `docs/api/**`, `docs/design/**`, `docs/dev/**`, `docs/legal/**`, `docs/examples/**` (deleted).

- [ ] **C1.** Delete `docs/examples/` with `git rm -r`. It holds Apache Roller 4-era BSF and Groovy scripting, AtomPub admin-protocol scripts, a mod_jk config and a plugin example against `org.apache.roller`. None of it applies to this codebase.
- [ ] **C2.** Run `git mv RollerSSO.md PressroomSSO.md`, then fix every reference to the old file name anywhere in the tree.
- [ ] **C3.** Rewrite the prose:
  - "Roller" becomes "Pressroom";
  - `roller` paths, commands and names become their Phase 2 replacements (`./pressroom`, `pressroom.war`, `/pressroom-ui/`, `PRESSROOM_*`, `pressroom.properties`, `jakefearsd/pressroom`, `bin/pressroom-api`, `~/.pressroom/credentials`, `prs_`, database and role `pressroom`, compose project `pressroom`).
  - In `CLAUDE.md`, the wiki names become their Phase 4 names: `RollerHub` → `PressroomHub`, cluster `roller` → `pressroom` in `get_briefing(clusters=["pressroom"])`. Phase 4 renames the wiki the same night.
  - The README gains a short "Origins" line naming Apache Roller, keep-marked.
  - In `docs/dev/pressroom-cutover.md`, the 0.4.x facts stay as they are, keep-marked line by line.
  - `docs/legal/blog-privacy-policy.md`: rename the product only. The policy's substance is unchanged.
- [ ] **C4.** Check that `grep -rn "RollerSSO\|docs/examples" -- . ':!docs/superpowers'` finds nothing, then run `mvn -q -pl app test -Dtest='OldNameInventoryTest,*Doc*Test,*Claude*Test'`. The only failures allowed are "fell to N" for the docs keys (`PROSE/docs`, `MACHINE/docs`, `PACKAGE/docs`, `CLASS/docs`, `PATH-*/docs`).
- [ ] **C5.** Commit `docs(rebrand): Pressroom throughout the docs; drop docs/examples`.

### Task D: Build, CI, deploy and NOTICE (areas build, deploy, other)

**Files:**
- `pom.xml`, `app/pom.xml`, `it-selenium/pom.xml` (`<name>`, `<description>` and comments);
- `.github/**` (workflow `name:` "Roller" → "Pressroom", job names, comments), `config/**`;
- `deploy/**`, `bin/**` (except migrations V001-V041), `Dockerfile` (labels, comments), `docker-compose*.yml`, `pressroom`, `NOTICE.txt`.

- [ ] **D1.** Write a failing test, `NoticeAttributionTest` in `app/src/test/java/com/wikantik/pressroom/build/`. It asserts two things:
  - `NOTICE.txt` still contains the original Apache Roller notice lines, byte for byte as at the base commit (copy them into the test);
  - it contains the exact derivation line from Global Constraints.

  Run it and see it fail.
- [ ] **D2.** Add the NOTICE line. Then replace prose in the build, CI and deploy files, keeping the keep-marked cutover and rollback scripts as they are.
  - Workflow `name:` values change. Check that nothing references a workflow by name (`gh run` filters, badges in README): grep `workflow_run`, `badge`.
  - The image label `org.opencontainers.image.title` and its description say Pressroom.
- [ ] **D3.** Run `mvn -q -pl app test -Dtest='NoticeAttributionTest,QualityGatePomTest,ProductionComposeTest,*Deploy*Test,*Cutover*Test,*Upgrade*Test,*Release*Test,OldNameInventoryTest'`. The only failures allowed are "fell to N" for `PROSE/build`, `PROSE/deploy` and `other`.
- [ ] **D4.** Commit `chore(rebrand): Pressroom in build, CI, deploy prose; NOTICE derivation line`.

### Task E: Merge, empty the baseline, full battery (controller)

- [ ] **E1.** Merge A, B, C and D into master one at a time with `git merge --no-ff`. Before each merge, run `git merge-tree --write-tree` to check it is clean. Same-line conflicts in a test file are resolved by hand, and only where both sides edited text.
- [ ] **E2.** Run OldNameInventoryTest and lower BASELINE to the observed counts. The target is `Map.of()`. Each remaining key is either fixed (a missed occurrence) or proven to be a legitimate keep, in which case the line gets the marker and the reason goes in the report.
  - Then change the ratchet so that BASELINE is a `Map.of()`, and turn the test's end-state assertion into "the inventory is empty".
  - The javadoc says Phase 3 emptied it.
- [ ] **E3.** Run the full battery, one build at a time:
  - `mvn clean install`;
  - `mvn verify -Pit` at `/` and at `-Dit.context.path=pressroom`;
  - the WAR smoke: boot `pressroom.war`, then curl the login page and check it says Pressroom;
  - a visual pass with Chrome over the admin login, menu, editor and one weblog page.
- [ ] **E4.** Run the whole-branch review on the most capable model. One fix wave, one re-review.
