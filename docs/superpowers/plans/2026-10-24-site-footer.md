# Site Footer (Colophon) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every blog page and admin screen ends with one colophon: copyright, privacy-policy link, `Roller <version>` and its Pacific release date.

**Architecture:** A per-blog `copyright` column and a site-wide `site.privacy.page` runtime property feed one Java record, `Colophon`, built by a single factory. The admin `tiles/footer.jsp` and a Velocity macro (`showColophon`, called from every shared theme's footer) both render that record with the same `footer.*` message keys. The privacy policy itself is an ordinary Page on the front-page blog; a draft of its text is written to `docs/legal/`.

**Tech Stack:** Java 25, Spring MVC + JSP/JSTL, Velocity, JPA/EclipseLink, PostgreSQL 18 migrations, JUnit 5 + Mockito, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-10-24-site-footer-design.md` (read it first; acceptance criteria are numbered there and cited below as AC1-AC12).

## Global Constraints

- Release-date zone: `America/Los_Angeles`, one constant; format `yyyy-MM-dd`.
- Default copyright: `© <year> <holder>. All rights reserved.`; year = current year in `America/Los_Angeles`; holder = blog name, or `site.name` on admin screens.
- Copyright column: `copyright`, nullable, max 255 chars, plain text; blank/whitespace stored as null.
- Migration file: `bin/db/migrations/V038__weblog_copyright.sql`, idempotent (`ADD COLUMN IF NOT EXISTS`).
- Runtime property: `site.privacy.page` = slug of a **published** Page on the `site.frontpage.weblog.handle` blog; blank = no link.
- Admin footer keeps the 7-char short revision; reader pages omit revision.
- All visible words via `footer.*` message keys (JSP: `spring:message`; Velocity: `$text.get`). i18n ratchets assert `Set.of()`: add keys to `ApplicationResources.properties`; never add to an offender set.
- Add CSS selectors, never rename one `Routes` pins.
- CLAUDE.md hazards apply: name every `@RequestParam`; SLF4J throwable last; never write a bare macro invocation in a Velocity comment ("the showColophon macro"); `fn:escapeXml` author-controlled EL; no context-path assumptions.
- Builds serialise in one working tree: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR` before every `mvn`.
- TDD: each test is run and seen failing for the expected reason before the code.
- Commit per task; never push.

## Review Focus

1. **Blog on a custom domain (virtual host)**: the privacy link must reach the front-page blog on the *site* host, so it is built with `absolute=true`. Test in Task 3 (`privacyUrlIsAbsoluteForAVirtualHostBlog`).
2. **Front-page blog renamed or unset after the setting was saved**: the factory must not throw; it yields `privacyUrl == null`. Test in Task 3 (`privacyUrlNullWhenFrontpageHandleNoLongerResolves`).
3. **Error page while the DB is down / install page before tables exist**: the footer renders version and date only. Test in Task 4 (`footerRendersWhenRuntimeConfigThrows`, via `Colophon.of` with a throwing config, AC9).
4. **Copyright text containing `&`, `<`, quotes, or an already-typed `©`**: rendered escaped exactly once in both JSP and Velocity, never double-escaped (`&amp;amp;`). Tests in Tasks 4 and 5.
5. **Year rollover / setting change with warm caches**: changing `site.privacy.page` must reach cached pages (AC6, Task 2). Year rollover on a cached page is accepted staleness (pages expire on the next blog save) — documented in `docs/dev/themes-and-templates.md`, not tested.

---

### Task 1: Per-blog copyright field (schema, entity, Settings screen, API)

**Files:**
- Create: `bin/db/migrations/V038__weblog_copyright.sql`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/Weblog.java` (field beside `tagline`), `app/src/main/resources/org/apache/roller/weblogger/pojos/Weblog.orm.xml` (basic mapping beside `tagline`), `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/WeblogConfigBean.java` (copyFrom/copyTo, as `tagline`), the Settings JSP rendered by `WeblogConfigController` (field + hint `websiteSettings.copyright.tip` showing the fallback), `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java` (`WeblogView`, `WeblogCreate` if it carries tagline, `WeblogPatch`, `WEBLOG_CLEARABLE`, length check), `ColumnLimits` (add `COPYRIGHT = 255`), `ApplicationResources.properties`, `docs/api/README.md` (field), `docs/dev/entries.md` or `admin-ui.md` (one line).
- Test: alongside the existing tests for each class (find with `grep -rl "setTagline\|tagline" app/src/test`), plus the migration harness test that applies migrations twice.

**Interfaces:**
- Produces: `String Weblog.getCopyright()` / `void setCopyright(String)`; `ColumnLimits.COPYRIGHT = 255`; API JSON field `copyright` on weblog view/patch.

- [ ] **Step 1: Write failing tests**
  - `WeblogConfigBean`: `copyrightRoundTrips` (bean→weblog→bean keeps `"© 2024–2026 Maiia"`); `blankCopyrightStoresNull` (`"   "` → `weblog.getCopyright() == null`).
  - Settings controller: `overlongCopyrightIsAFieldError` (256 chars → field error, weblog not saved).
  - API (`AdminDtos`/controller test): `patchSetsCopyright`, `patchCopyrightOver255IsFieldError` (error names field `copyright`), `clearRemovesCopyright` (`"copyright"` in clear list → null), `viewIncludesCopyright`.
  - Migration: V038 applied twice is a no-op (follow the existing idempotency test pattern for V037).
- [ ] **Step 2: Run them; each fails (missing accessor / field / column).**
  `mvn -pl app test -Dtest='WeblogConfigBean*Test,AdminDtos*Test,<settings controller test>,<migration test>'`
- [ ] **Step 3: Implement** the column (`ALTER TABLE weblog ADD COLUMN IF NOT EXISTS copyright varchar(255)`; check the real table name in V001), the mapping, accessors, bean copy with blank→null, Settings field + hint, API fields mirroring `tagline` exactly.
- [ ] **Step 4: Run the tests; all pass.** Then `mvn -pl app test -Dtest='MessageKey*Test,Message*ContractTest'` passes.
- [ ] **Step 5: Commit** `feat(weblog): a per-blog copyright notice, on Settings and /api/v1`.

---

### Task 2: `site.privacy.page` runtime property, validated, expiring caches

**Files:**
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml` (property-def `site.privacy.page`, key `configForm.privacyPage`, beside `site.frontpage.weblog.handle`; no bare `--` in comments), `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/GlobalConfigController.java`, `ApplicationResources.properties` (`configForm.privacyPage`, its tip saying an unpublished page leaves a dead link, `configForm.error.privacyPage.notFound`, `configForm.error.privacyPage.noFrontpage`), `docs/dev/admin-ui.md`, `docs/dev/themes-and-templates.md` (render-cache expiry: this setting expires every blog).
- Test: `GlobalConfigController`'s existing test class.

**Interfaces:**
- Consumes: `WeblogPageManager.getPageBySlug(Weblog, String)`, `WeblogManager.getWeblogByHandle(String)`, `Weblog.touchLastModified()` + `saveWeblog`.
- Produces: runtime property `site.privacy.page` (read via `WebloggerRuntimeConfig.getProperty("site.privacy.page")`).

- [ ] **Step 1: Write failing tests**
  - `privacyPageBlankIsAccepted` → saved, no error.
  - `privacyPagePublishedSlugIsAccepted` → saved.
  - `privacyPageUnknownSlugIsRefused` / `privacyPageDraftSlugIsRefused` / `privacyPageWithoutFrontpageBlogIsRefused` → field error, `saveProperties` never called, the previous value is what the form re-renders.
  - `privacyPageLookupFailureIsRefused` → `getPageBySlug` throws `WebloggerException` → error, not saved (fail closed).
  - `changingPrivacyPageTouchesEveryWeblog` → every weblog's `lastModified` moves forward and is saved; `unchangedPrivacyPageTouchesNothing`.
  - If `MessageKeyTest` treats a `configForm.*` key used only in `runtimeConfigDefs.xml` as an orphan, follow how `configForm.frontpageWeblogHandle` satisfies it.
- [ ] **Step 2: Run; each fails for the expected reason.**
- [ ] **Step 3: Implement** validation in the existing accept/validate loop (validate against the front-page handle *as submitted in the same form* if present, else as stored); after a successful save, if the value changed, touch and save every weblog via `touchLastModified()` (never `setLastModified(new Date())`).
- [ ] **Step 4: Run; all pass, plus the i18n ratchet tests.**
- [ ] **Step 5: Commit** `feat(config): site.privacy.page names the privacy-policy page`.

---

### Task 3: The `Colophon` record and its factory

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/Colophon.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/ConfigModel.java` (one accessor)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/ColophonTest.java`, `ConfigModel`'s existing test.

**Interfaces:**
- Consumes: Task 1 `Weblog.getCopyright()`; Task 2 `site.privacy.page`; `Weblogger.getVersion()/getRevision()/getBuildTime()`; `URLStrategy.getWeblogPageURL(weblog, null, slug, null, null, null, null, 0, true)` — verify against the page servlet route `/<handle>/<slug>` (docs/dev/entries.md, Pages) and use whichever `URLStrategy` method yields exactly that; if none does, add `getWeblogStaticPageURL(Weblog, String slug, boolean absolute)` with its own test.
- Produces:
  ```java
  public record Colophon(String version, String shortRevision, String releaseDate,
                         String copyrightText, String copyrightHolder, int copyrightYear,
                         String privacyUrl) {
      public static final ZoneId RELEASE_ZONE = ZoneId.of("America/Los_Angeles");
      /** null when buildTime is not epoch millis */
      public static String releaseDate(String buildTime);
      /** weblog may be null (admin screens: holder = site.name). Never throws. */
      public static Colophon of(Weblogger weblogger, Weblog weblog, Clock clock);
      public boolean hasCustomCopyright();
  }
  ```
  `releaseDate`, `copyrightText` (custom, may be null) and `privacyUrl` may be null; `shortRevision` is the first 7 chars of `getRevision()` (what `footer.jsp` shows today). Raw (unescaped) strings: escaping is the templates' job.
  `ConfigModel.getColophon(Object weblog)` → accepts the Velocity `$model.weblog` wrapper (unwrap to `Weblog`; check `pojos/wrapper`) or null.

- [ ] **Step 1: Write failing tests** (`ColophonTest`):
  - `releaseDateIsPacificAcrossUtcMidnight`: `releaseDate(String.valueOf(Instant.parse("2026-10-25T03:00:00Z").toEpochMilli()))` → `"2026-10-24"` (AC1).
  - `releaseDateNullForUnknown` (`"UNKNOWN"`), `releaseDateNullForUnfilteredPlaceholder` (`"${timestamp}"`), `releaseDateNullForNull`.
  - `customCopyrightIsKept`: weblog copyright `"© 2024–2026 Maiia"` → `copyrightText` equals it, `hasCustomCopyright()`.
  - `defaultCopyrightUsesBlogNameAndPacificYear`: clock at `2027-01-01T05:00:00Z` → year `2026`, holder = blog name.
  - `adminColophonUsesSiteName`: weblog null → holder = `site.name`.
  - `privacyUrlNullWhenSettingBlank`; `privacyUrlBuiltFromFrontpageBlog` (absolute URL from the strategy); `privacyUrlIsAbsoluteForAVirtualHostBlog` (blog with a custom domain → link still on the site host); `privacyUrlNullWhenFrontpageHandleNoLongerResolves`.
  - `ofNeverThrowsWhenRuntimeConfigThrows` → version/date present, `privacyUrl` null, holder = the weblog name if a weblog was given, else `""` (templates omit the copyright line when the holder is empty and there is no custom text).
- [ ] **Step 2: Run** `mvn -pl app test -Dtest=ColophonTest` → fails (class missing).
- [ ] **Step 3: Implement** `Colophon` (`catch` only to degrade — this is a display path) and `ConfigModel.getColophon`.
- [ ] **Step 4: Run; pass. Add a `ConfigModel` test `colophonForWrappedWeblog`.**
- [ ] **Step 5: Commit** `feat(footer): one Colophon record behind every footer`.

---

### Task 4: Admin footer

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/jsps/tiles/footer.jsp`, the admin stylesheet that styles `.footer` today (grep `roller-ui/styles`), `ApplicationResources.properties` (replace `footer.productName`/`footer.productNameNoVersion` with: `footer.copyright.default=© {0} {1}. All rights reserved.`, `footer.privacy=Privacy policy`, `footer.version=Roller {0}`, `footer.version.revision=Roller {0} ({1})`, `footer.released=released {0}`; remove dead keys from every `ApplicationResources_*.properties` too), `docs/dev/admin-ui.md`.
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/AdminChromeJspTest.java`, a new `FooterJspRenderTest` if the project has a JSP-render harness (else source scans as `AdminChromeJspTest` does).

**Interfaces:**
- Consumes: Task 3 `Colophon.of(weblogger, null, Clock.systemDefaultZone())`.
- Produces: markup `<div class="colophon">` with `colophon-copyright`, `colophon-meta` children (the Velocity macro in Task 5 emits the same classes).

- [ ] **Step 1: Write failing tests**
  - `everyFullPageLayoutIncludesTheFooterTile`: enumerate `tiles-*.jsp`; every one except `tiles-popuppage.jsp` (and `tiles-barepage.jsp` only if it is not a full page — check its users) includes `${tile_footer}`, and every tile definition for those layouts maps `footer` to `tiles/footer.jsp` (AC4).
  - `footerRendersColophon`: footer source uses `footer.version.revision`, `footer.released`, `footer.privacy`, `footer.copyright.default` and `fn:escapeXml` on copyright text/holder and the privacy URL.
  - Keep the existing short-revision assertion green.
  - `footerRendersWhenRuntimeConfigThrows` (AC9): whatever the footer calls must be `Colophon.of`, which Task 3 proved never throws; assert the JSP has no other business-tier call.
- [ ] **Step 2: Run; fail.**
- [ ] **Step 3: Implement** the footer (copyright line; then privacy link · version (revision) · released date, each clause omitted when its value is null) and its CSS (small, muted, existing tokens).
- [ ] **Step 4: Run** the tests plus `MessageKey*Test`, `MessagePlaceholderContractTest`, `MessageFormatRegressionTest`, `RollerViewResolverTest`; pass.
- [ ] **Step 5: Commit** `feat(footer): the admin footer carries the colophon on every screen`.

---

### Task 5: Blog-theme footer

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (new macro `showColophon($weblog)`), every `<footer>` in `app/src/main/webapp/themes/{journal,travel,portfolio}/*.vm` and `themes/frontpage/_footer.vm` (replace "Powered by Roller"), each theme's `*-custom.css`, `docs/dev/themes-and-templates.md`.
- Test: a new `ThemeColophonTest` beside the existing theme template tests (grep `theme.xml` in `app/src/test`), and the existing Velocity render test harness for `weblog.vm` macros (grep `showBusinessCard` in tests).

**Interfaces:**
- Consumes: Task 3 `$config.getColophon($model.weblog)`; Task 4 message keys and class names (`colophon`, `colophon-copyright`, `colophon-meta`).

- [ ] **Step 1: Write failing tests**
  - `everyHtmlTemplateOfEverySharedThemeShowsTheColophon` (AC3): parse each `themes/*/theme.xml`; for every template whose content type is HTML (not stylesheets/feeds), the `.vm` contains `#showColophon(` or `#parse`s a file that does.
  - Macro render tests: `colophonEscapesCustomCopyrightOnce` (`Maiia & <b>` → `Maiia &amp; &lt;b&gt;`, never `&amp;amp;`) (AC2); `colophonDefaultUsesBlogName`; `colophonOmitsPrivacyLinkWhenUnset`; `colophonOmitsReleasedWhenDateNull`; `colophonOmitsRevision`.
- [ ] **Step 2: Run; fail.**
- [ ] **Step 3: Implement** the macro (escape with `$utils.escapeHTML`; check how `$model.weblog.name` is stored before escaping it, so it is escaped exactly once) and insert it last inside each theme footer; style per theme.
- [ ] **Step 4: Run** the tests plus all theme/Velocity tests (`mvn -pl app test -Dtest='*Theme*Test,*Velocity*Test,*Macro*Test'`); pass.
- [ ] **Step 5: Commit** `feat(themes): every shared theme ends with the colophon`.

---

### Task 6: Browser IT

**Files:**
- Modify/Create: under `it-selenium/` beside the existing admin-chrome / theme ITs (read `docs/dev/browser-its.md` first: cleanup by identity, class-parallel, BrowserHealth).
- Test: `ColophonIT`.

- [ ] **Step 1: Write the IT** (AC5, AC10): admin sets a published page `privacy` on the front-page blog and `site.privacy.page=privacy`; a blog page and an admin page each show `Roller <version>` and a privacy link whose `href` loads that page (200, page title present). Cleans up the setting and page it created.
- [ ] **Step 2: Run against the current build before Tasks 4-5 are merged, or with the colophon markup temporarily absent, and see it fail** (if Tasks 4-5 are already in, note that in the commit and show the failure by pointing the IT at a slug that is not set).
- [ ] **Step 3: Run** `mvn verify -Pit -Dit.test=ColophonIT` and `mvn verify -Pit -Dit.test=ColophonIT -Dit.context.path=roller`; both pass.
- [ ] **Step 4: Commit** `test(it): the colophon and privacy link at both context paths`.

---

### Task 7: Blog privacy-policy draft (no build; runs in parallel with Tasks 1-6)

**Files:**
- Create: `docs/legal/blog-privacy-policy.md`

- [ ] **Step 1: Read the baseline** with the wikantik MCP: `read_pages(["PrivacyPolicy"])` (effective 2026-10-14). Keep its voice, headings and "short version".
- [ ] **Step 2: Audit Roller** and note each fact with its source file: cookies (grep `new Cookie(`, `setCookie`, `JSESSIONID`, remember-me, `server.servlet.session` in `app/src/main/resources` and `docs/dev/configuration.md`) with name/attributes/lifetime; Umami (`docs/dev/audience-and-analytics.md`); Google reader sign-in (`docs/dev/sso.md`); comments (`docs/dev/comments.md`: what is stored, shown, deletable); newsletter via listmonk (`/newsletter/*`); outgoing mail provider (`docker_deployment.md`, `.env.example`); third-party fetches by the four themes (fonts, OSM tiles, video embeds — grep `https://` in `themes/` and `WEB-INF/velocity`); Cloudflare tunnel; logs and retention; backups (`deploy/` backup config); account/comment deletion paths.
- [ ] **Step 3: Write the policy**: scope = blog.wikantik.com and every blog it hosts; sections as the wiki's, with an "Advertising" section: no ads today; when AdSense is enabled this section must name Google as a third-party vendor using cookies, link Google's ad-settings opt-out and "how Google uses data from partner sites". Any claim not verifiable from the repo is written `TODO(Jake): …`, never guessed.
- [ ] **Step 4: Verify** (AC11): every cookie name in the draft is found by `grep -rn "<name>" app/src deploy` — list the hits in the commit message body.
- [ ] **Step 5: Commit** `docs(legal): blog privacy policy, forked from the wiki's`.

---

## Finish

- Whole-branch: `mvn clean install` (unit + all gates: JaCoCo floors, PMD/CPD/SpotBugs, `-Xlint`), `bin/check-diff-coverage.sh`, then `mvn verify -Pit` at `/` and at `-Dit.context.path=roller` (AC12).
- Grep for any `.vm` reference to removed members/keys (`footer.productName`) across `themes/` and `WEB-INF/velocity` (Velocity leniency hazard).
- No release, push or docker1 change; the Page `privacy` is created on docker1 only after Jake approves the text and a release ships.
