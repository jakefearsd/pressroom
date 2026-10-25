# Site Pages and the Front Door Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the site its own Pages, theme and front door: site pages served at `/<slug>`, one of them at `/`, held by a hidden system-owned weblog.

**Architecture:** The "site space" is an ordinary `Weblog` row with a new `site_space` flag. Every weblog listing leaves it out by default; it has no permission rows, so only global admins reach it (through the implied-permission rule), and the interceptor lets it into the Pages, Media and Theme screens only. Public routing maps a single unclaimed segment to a site page; `/` goes through a new `FrontDoor` resolver; every page URL is built by `URLStrategy.getWeblogStaticPageURL`, which knows the site space lives at the context root. The privacy setting becomes a `PageReference` (`slug` = site page, `handle/slug` = blog Page).

**Tech Stack:** Java 25, Spring Boot 4.1 / Spring MVC, JPA/EclipseLink (`*.orm.xml`, `metadata-complete`), PostgreSQL 18, Velocity themes, JSP admin UI, JUnit 5 + Testcontainers, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-10-25-site-pages-front-door-design.md` (acceptance criteria AC1–AC14 there).

## Global Constraints

- TDD per CLAUDE.md: write the failing test, run it, watch it fail for the expected reason, then the minimum code.
- One build at a time: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR` before any `mvn`.
- Each task ends in one local commit by the implementer. **No agent pushes.**
- Site space handle: `site-space` (the `-` is outside `username.allowedChars=A-Za-z0-9`, so `WeblogCreationRules` can never create it). Constant `Weblog.SITE_SPACE_HANDLE`.
- New runtime property: `site.frontpage.page` (string, default empty): the front door's site page slug. When non-blank it wins over `site.frontpage.weblog.handle`.
- Site themes: `journal`, `portfolio`, `travel`, `frontpage`; a new site space starts on `frontpage`. Site space never takes a custom theme.
- Migrations: `bin/db/migrations/V039__weblog_site_space.sql`, `V040__privacy_page_reference.sql`. Idempotent (`IF NOT EXISTS`, guarded `UPDATE`).
- Name every `@RequestParam`/`@PathVariable`. Escape author-controlled EL (`fn:escapeXml`). Page titles are raw; `$model.weblog.name` comes pre-escaped from the wrapper.
- No code may assume a context path: tests that assert a URL run with context `/roller` (as `RenderingTestSupport.anonymousGet` does) and the IT runs at both paths.
- Velocity is lenient: after renaming/removing any member a `.vm` reaches, grep `app/src/main/webapp/themes` and `WEB-INF/velocity`. Never write a zero-arg macro name with `#` in a comment.
- i18n ratchets (`MessageKeyTest`, `MessagePlaceholderContractTest`, `MessageFormatRegressionTest`): every new key in `ApplicationResources.properties`; a key that falls out of use is deleted.
- Gates must stay green at the end: JaCoCo floors, diff coverage ~90%, PMD/CPD/SpotBugs zero, `-Xlint:all` zero warnings.

## Review Focus

1. **Custom-domain hosts.** `/about` on a blog's own domain is that blog's Page, never a site page; the site-slug branch runs only when `vhostHandle == null`. Test in Task 5.
2. **Case and legacy handles.** A site slug `Travel` must clash with blog handle `travel`; compare lower-cased both ways. Test in Task 4.
3. **The front door's page goes away.** Deleted or unpublished front-door page: `/` shows the directory (200), `/<slug>` of a draft is 404, never a 500. Test in Task 5.
4. **A non-admin with a crafted link.** `pages.rol?weblog=site-space` as a blog author, and `/api/v1/weblogs/site-space/pages` with any token, are refused (redirect / 404), never served. Test in Task 3.
5. **A stale cached `/`.** Switching the front door to another page or back to a blog shows the new choice on the next request, and editing the front-door page shows on `/` at once. Test in Task 6.

---

### Task 1: The site space exists and is hidden

**Files:**
- Create: `bin/db/migrations/V039__weblog_site_space.sql`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/Weblog.java`, `app/src/main/resources/org/apache/roller/weblogger/pojos/Weblog.orm.xml`, `app/src/main/java/org/apache/roller/weblogger/business/WeblogManager.java`, `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogManagerImpl.java`, `app/src/main/java/org/apache/roller/weblogger/pojos/wrapper/WeblogWrapper.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/SiteSpaceTest.java`

**Interfaces:**
- Produces: `Weblog.SITE_SPACE_HANDLE = "site-space"`; `boolean Weblog.isSiteSpace()` / `setSiteSpace(boolean)`; `Weblog WeblogManager.getSiteSpace() throws WebloggerException` (null when not yet created); `Weblog WeblogManager.getOrCreateSiteSpace(User creator) throws WebloggerException`; `boolean WeblogWrapper.isSiteSpace()`.

- [ ] **Step 1: Write the failing tests** in `SiteSpaceTest` (real tier, `TestUtils`):
  - `getOrCreateIsIdempotent`: two calls return the same id; `isSiteSpace()` true; handle `site-space`; editor theme `frontpage`.
  - `theSiteSpaceHasNoPermissionRows`: `userManager.getWeblogPermissions(siteSpace)` is empty after creation by an admin user.
  - `everyListingLeavesItOut`: with one ordinary weblog and the site space, `getWeblogs(null,null,null,null,0,-1)`, `getWeblogs(TRUE,TRUE,…)`, `getWeblogsByLetter('S',0,-1)`, `getWeblogHandleLetterMap()` and `getWeblogCount()` never include or count it.
  - `itStillResolvesByHandle`: `getWeblogByHandle("site-space")` returns it (the interceptor needs this).
  - `theWrapperSpeaksForTheSite`: `WeblogWrapper.wrap(siteSpace, …).getName()` equals `site.name` escaped once, `getDescription()`/tagline equals `site.description`; for an ordinary weblog both are unchanged.
  - `aSecondSiteSpaceIsRefused`: persisting another weblog with `siteSpace=true` fails (the partial unique index).
- [ ] **Step 2: Run** `mvn -pl app test -Dtest=SiteSpaceTest` — expect compile failure / FAIL.
- [ ] **Step 3: Implement.**
  - V039: `ALTER TABLE weblog ADD COLUMN IF NOT EXISTS site_space boolean NOT NULL DEFAULT false;` and `CREATE UNIQUE INDEX IF NOT EXISTS weblog_one_site_space ON weblog (site_space) WHERE site_space;`.
  - Map `siteSpace` in `Weblog.orm.xml` (`metadata-complete`, so the mapping is required).
  - Add `w.siteSpace = false` to the dynamic `getWeblogs` query and to the named queries `Weblog.getByLetterOrderByHandle`, `Weblog.getCountAllDistinct`, `Weblog.getCountByHandleLike`. `getWeblogByHandle` stays unfiltered by the flag.
  - `getOrCreateSiteSpace`: find by flag; else build `new Weblog(SITE_SPACE_HANDLE, creator, "Site", …, "frontpage", …)` with `siteSpace=true` and persist **without** granting the creator a permission (do not route through the grant in `addWeblogContents`, `JPAWeblogManagerImpl.java:354`; if reusing it, revoke in the same transaction). If a non-site weblog already holds the handle, throw `WebloggerException` naming it.
  - `WeblogWrapper`: `getName()`/`getDescription()`/`getTagline()` return the site's `site.name`/`site.description` (escaped as the existing getters escape) when `isSiteSpace()`.
- [ ] **Step 4: Run** the test class, then `mvn -pl app test -Dtest='*WeblogManager*Test,WeblogCreationRules*Test,SiteDirectory*Test'` — PASS.
- [ ] **Step 5: Commit** `feat(site): a hidden site-space weblog, left out of every listing`.

### Task 2: One URL rule for pages, site space at the root

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/MultiWeblogURLStrategy.java` (`weblogRoot` :87, `getWeblogStaticPageURL` :290), `app/src/main/resources/org/apache/roller/weblogger/config/runtimeConfigDefs.xml`, `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/PageModel.java` (:205-215), `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/URLModel.java` (`staticPage` :265), `app/src/main/java/org/apache/roller/weblogger/ui/controllers/AdminUrls.java`, `app/src/main/webapp/WEB-INF/jsps/editor/Pages.jsp` (:80), `app/src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp` (:138, :149, :196)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/SiteSpaceUrlTest.java`; extend `PageEditJspTest`

**Interfaces:**
- Consumes: `Weblog.isSiteSpace()` (Task 1).
- Produces: `getWeblogURL(siteSpace, null, abs)` = context root + `/`; `getWeblogStaticPageURL(siteSpace, slug, abs)` = root + slug, or root alone when `slug` equals `site.frontpage.page`; `String AdminUrls.staticPage(Weblog, String slug)` (absolute). All page links in rendering and admin go through `getWeblogStaticPageURL`.

- [ ] **Step 1: Write the failing tests.** `SiteSpaceUrlTest`: relative and absolute forms under context `/roller` — site page `about` → `/roller/about`; front-door slug `home` (property set) → `/roller/`; ordinary weblog unchanged (`/roller/blog/about`). `PageModel.getCanonicalUrl()` for a site page request equals the strategy's absolute URL; `URLModel.staticPage("about")` on the site space equals the strategy's relative URL. `PageEditJspTest`: `Pages.jsp` and `PageEdit.jsp` build the view link with `urls.staticPage(actionWeblog, …)`, not `weblogAbsolute(...)` + slug.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Define `site.frontpage.page` in `runtimeConfigDefs.xml` beside `site.frontpage.weblog.handle` (no `--` in the comment). Make `PageModel` and `URLModel.staticPage` delegate to the strategy. Add `AdminUrls.staticPage` and switch the three JSP sites. The `site.frontpage.page` field must not appear as a free-text field on Global Config (Task 6 owns its control); check how `GlobalConfig.jsp` iterates definitions and exclude it the way `site.frontpage.weblog.handle` is special-cased.
- [ ] **Step 4: Run** the new tests plus `-Dtest='PageModel*Test,URLModel*Test,PageEditJspTest,ColophonTest*'` — PASS.
- [ ] **Step 5: Commit** `feat(site): page URLs come from one strategy rule; the site space lives at the root`.

### Task 3: The access boundary and the admin entry points

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/UISecurityEnforced.java`, `.../ui/controllers/RollerHandlerInterceptor.java` (`enforceSecurity` :103-166), `.../ui/controllers/editor/PagesController.java`, `.../editor/PageEditController.java`, `.../editor/ThemeEditController.java` (`loadThemeData` :258-264, save :91), the media manager controllers under `.../ui/controllers/editor/` (every controller behind `MediaFile*.jsp`, including the image-chooser overlay), `.../ui/controllers/core/MainMenuController.java`, the main menu JSP, `ApplicationResources.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/SiteSpaceAccessTest.java` (MockMvc with the production `WebMvcConfig`, as `ui/restapi/v1/WeblogParameterDispatchTest` builds it); extend `ThemeEditControllerTest`

**Interfaces:**
- Consumes: `getOrCreateSiteSpace`, `getSiteSpace`, `isSiteSpace` (Task 1).
- Produces: `default boolean UISecurityEnforced.allowsSiteSpace()` (false); true on Pages, PageEdit, ThemeEdit and the media controllers. Model attribute `siteSpace` (the `Weblog`, global admins only) on the main menu.

- [ ] **Step 1: Write the failing tests** (`SiteSpaceAccessTest`):
  - global admin: `GET /roller-ui/authoring/pages.rol?weblog=site-space` 200; `themeEdit.rol` 200; media view 200.
  - global admin: `entries.rol`, `comments.rol`, `categories.rol`, `weblogConfig.rol`, `members.rol` with `weblog=site-space` refused the way a null action weblog is (same status/redirect as today's unknown weblog).
  - blog author (no admin role): `pages.rol?weblog=site-space` refused.
  - any API token: `GET /api/v1/weblogs/site-space/pages` 404; `GET /api/v1/weblogs` (admin token) does not list it.
  - main menu as global admin shows a Site section linking Pages, Media and Theme for `site-space` and creates the site space if absent; as a blog author shows no Site section; the user's blog list never includes it.
  - `ThemeEditControllerTest`: on the site space the offered themes are exactly the four site themes; on an ordinary weblog `frontpage` is not offered; a POST of `selectedThemeId=frontpage` for an ordinary weblog (not already on it) is refused; a POST of `themeType=custom` for the site space is refused.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** In `enforceSecurity`, after the action weblog resolves: if it `isSiteSpace()` and (`!handler.allowsSiteSpace()` or the handler is an API handler or the user lacks `GlobalPermission.ADMIN`), deny exactly as for a null weblog. Main menu: for a global admin call `getOrCreateSiteSpace(user)` and expose it; render the Site section with `<c:url>` links (`fn:escapeXml` for EL). Theme picker: site-space list and the save-path filter (the existing gap where `frontpage` is accepted on POST). Message keys for the section heading and links.
- [ ] **Step 4: Run** the new tests plus `-Dtest='*Interceptor*Test,MainMenu*Test,ThemeEdit*Test,Media*ControllerTest,MessageKeyTest'` — PASS.
- [ ] **Step 5: Commit** `feat(site): only global admins reach the site space, and only its Pages, Media and Theme`.

### Task 4: One namespace for site slugs and blog handles

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAWeblogPageManagerImpl.java` (`savePage` :65), `.../ui/controllers/WeblogCreationRules.java` (`Context.current` :50-69, `checkHandle` :127-145), `.../ui/rendering/WeblogRequestMapper.java` (`isWeblog` :509), `.../ui/controllers/editor/PageEditController.java` (error mapping), `ApplicationResources.properties`
- Create: `app/src/main/java/org/apache/roller/weblogger/config/ProtectedPaths.java` (the parsed `rollerProtectedUrls` + `userProtectedUrls` set, used by both `WeblogCreationRules` and the page check)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/SiteSlugNamespaceTest.java`; extend `WeblogCreationRulesTest`, `WeblogRequestMapperTest`

**Interfaces:**
- Consumes: Task 1.
- Produces: `static Set<String> ProtectedPaths.site()` (lower-cased). Site-page save errors carry message keys `pageForm.error.slugIsWeblog` (arg: the handle) and `pageForm.error.slugIsReservedPath` (arg: the path). Weblog creation error `createWeblog.error.handleIsSitePage` (409).

- [ ] **Step 1: Write the failing tests.**
  - Site page slug `travel` refused when blog `travel` exists; `Travel` refused too; `roller-ui`, `api`, `sitemap.xml` refused; the editor shows the key naming the clash.
  - The same slug on an ordinary blog's Page is still allowed (the rule is site-space only).
  - `WeblogCreationRules`: handle `about` refused with `handleIsSitePage` when a site page `about` exists **as a draft**; `ABOUT` refused too.
  - `WeblogRequestMapperTest`: `/site-space/` and `/site-space/about` are not handled (`isWeblog` false for the site space), so they fall through to 404.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Extract `ProtectedPaths`; in `savePage`, when the page's weblog is the site space, check `getWeblogByHandle(slug, null)` (excluding the site space itself) and `ProtectedPaths.site()` case-insensitively, throwing a `WebloggerException` the controller maps to the two keys. In `checkHandle`, after the existing checks, look up a site page by slug any status. `isWeblog` returns false when the weblog `isSiteSpace()`.
- [ ] **Step 4: Run** the tests plus `-Dtest='*PageManager*Test,PageEdit*Test,WeblogCreationRules*Test,WeblogRequestMapper*Test,MessageKeyTest,MessagePlaceholderContractTest'` — PASS.
- [ ] **Step 5: Commit** `feat(site): site page slugs and blog handles share one namespace`.

### Task 5: Public routing — `/<slug>` and the front door at `/`

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/FrontDoor.java`
- Modify: `app/src/main/webapp/index.jsp`, `.../ui/rendering/WeblogRequestMapper.java` (after the reserved/weblog checks, :249-257), `.../ui/rendering/servlets/PageServlet.java` (`doGet` :141)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/FrontDoorTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/rendering/SitePageRoutingTest.java` (pattern: `PageRoutingTest`); extend `WeblogRequestMapperTest`

**Interfaces:**
- Consumes: Tasks 1, 2, 4.
- Produces: `static String FrontDoor.forwardPath(Weblogger)` — the dispatcher path for `/`: `/roller-ui/rendering/page/site-space/<slug>` when `site.frontpage.page` names a published site page; `/roller-ui/siteDirectory.rol` when it names anything else; otherwise today's handle/directory logic unchanged.

- [ ] **Step 1: Write the failing tests.**
  - `FrontDoorTest`: page set and published → page path; page set but draft or missing → directory; page blank + handle set → `/roller-ui/rendering/page/<handle>` (unchanged); both blank → directory; a lookup that throws → directory (never an exception out of `/`).
  - `WeblogRequestMapperTest`: on the site host, `/about` with a site page `about` (any status) forwards to `/roller-ui/rendering/page/site-space/about`; `/about` with no such page is not handled; `/home` where `home` is the front-door slug answers 301 to `<ctx>/`; a blog handle `about` wins over a site page `about` (forced in the DB); **on a custom domain `/about` stays the blog's page**; POST `/about` not forwarded.
  - `SitePageRoutingTest` (through the page servlet, forwarded dispatch): published site page 200 with its content in the `frontpage` theme; draft 404; a direct REQUEST-dispatched `GET /roller-ui/rendering/page/site-space/about` is 404.
  - Cache: render `/about`, save the page with new content (spin past `lastModified` as `RenderErrorStatusTest.saveCustomTemplate` does), render again — new content.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** `index.jsp` becomes a one-line forward to `FrontDoor.forwardPath(...)` (keep `session="false"`). Mapper branch runs only when `vhostHandle == null`, the path is a single segment, the segment is not a weblog, and the site space has a page with that slug. `PageServlet` refuses site-space requests whose dispatcher type is not `FORWARD`.
- [ ] **Step 4: Run** the tests plus `-Dtest='WeblogRequestMapper*Test,PageRoutingTest,PageServlet*Test,SiteDirectory*Test,RedirectServing*Test'` — PASS.
- [ ] **Step 5: Commit** `feat(site): site pages answer at /<slug> and the chosen one at /`.

### Task 6: Global Config — the front-door choice and the privacy page reference

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/PageReference.java`, `bin/db/migrations/V040__privacy_page_reference.sql`
- Modify: `.../ui/controllers/admin/GlobalConfigController.java` (`loadWeblogs` :307, `save` :112, `validatePrivacyPage` :231, `privacyLinkChanged` :193, `touchEveryWeblog` :269), `app/src/main/webapp/WEB-INF/jsps/admin/GlobalConfig.jsp` (:50-86), `.../business/Colophon.java` (:77, :137-152), `ApplicationResources.properties` (:104-109)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/PageReferenceTest.java`, `app/src/test/java/org/apache/roller/weblogger/db/PrivacyPageReferenceMigrationTest.java` (follow the existing migration-test pattern in that package; if none, apply V040 to a seeded `roller_properties` through the test harness); extend `GlobalConfigControllerTest`, `ColophonTest`

**Interfaces:**
- Consumes: Tasks 1, 2, 5.
- Produces: `record PageReference(String handle /* null = site */, String slug)`; `static Optional<PageReference> parse(String)` (blank → empty; more than one `/` or a blank part → empty); `Optional<WeblogPage> resolvePublished(Weblogger)`; `Optional<String> absoluteUrl(Weblogger)` (via `getWeblogStaticPageURL`).

- [ ] **Step 1: Write the failing tests.**
  - `PageReferenceTest`: `privacy` → site; `wikantik/privacy` → blog; `a/b/c`, `/x`, `x/` → empty; resolve only PUBLISHED.
  - Migration: `site.frontpage.weblog.handle=wikantik`, `site.privacy.page=privacy` → `wikantik/privacy`; already-qualified, blank, or no-handle values untouched; running twice changes nothing (AC8).
  - `ColophonTest`: `privacy` → `<abs ctx>/privacy` from the site space; `wikantik/privacy` → `<abs ctx>/wikantik/privacy`; unresolvable → no link; copyright holder on the site space is `site.name` (AC9).
  - `GlobalConfigControllerTest`: the front-door select lists "Site directory" (`""`), an optgroup of published site pages with values `page:<slug>`, and the blogs (never the site space; AC6); saving `page:home` stores `site.frontpage.page=home` and blanks the handle; saving a handle blanks `site.frontpage.page`; a missing/draft privacy reference is refused with `configForm.error.privacyPage.notFound`; changing the front door or privacy reference touches every weblog **and the site space**.
  - Cache (Review Focus 5): render `/` with front door `home`, switch to `about`, render `/` again → `about`'s content.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** V040 is a guarded `UPDATE roller_properties … WHERE name='site.privacy.page' AND value <> '' AND position('/' in value) = 0 AND EXISTS (non-blank front-page handle)`. Keep the select's `name="site.frontpage.weblog.handle"` and the blog option values as they are (ColophonIT and GlobalConfigMatrixIT drive it); translate `page:` values in the controller. With no published site pages, the optgroup is replaced by a hint linking to the site's Pages screen. Colophon resolves through `PageReference`. Delete `configForm.error.privacyPage.noFrontpage` if nothing uses it any more; update the privacy field's help text to describe both forms.
- [ ] **Step 4: Run** the tests plus `-Dtest='GlobalConfig*Test,Colophon*Test,ThemeColophon*Test,Migration*Test,MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest'` — PASS.
- [ ] **Step 5: Commit** `feat(site): choose a site page as the front door; the privacy setting is a page reference`.

### Task 7: The four themes on the site space

**Files:**
- Modify: `app/src/main/webapp/themes/{journal,portfolio,travel}/page.vm`, `app/src/main/webapp/themes/frontpage/{_header.vm,page.vm}`, the theme CSS where a removed element needs no orphan rule
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/SiteSpaceThemeRenderingTest.java` (pattern: `ThemeColophonRenderingTest`)

**Interfaces:**
- Consumes: `$model.weblog.siteSpace` (Task 1 wrapper), Tasks 2 and 5.

- [ ] **Step 1: Write the failing tests.** For each of the four themes, render a published site page (with a second site page marked show-in-nav) and assert:
  - no `href` containing `/feed/`, `/category/`, `/search`, `/page/directory` or `site-space`; no `<form` posting to a search URL; no autodiscovery `<link rel="alternate">`;
  - the nav lists the second site page at `<ctx>/<slug>`;
  - the header shows `site.name`; the canonical link is the page's `/<slug>` URL (front-door page: `<ctx>/`);
  - frontpage theme: no "Recent posts" tab; its Directory link is `<ctx>/roller-ui/siteDirectory.rol`;
  - the colophon is present with the Sign in link (AC10);
  - an ordinary weblog rendered in the same theme still has its feed link, category list and search form (the control);
  - switching the site space's theme changes the site page's markup and leaves a blog's rendering byte-identical (AC3).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Guard the feed autodiscovery, category list and search form with `#if(!$model.weblog.siteSpace)` in each `page.vm`; frontpage `_header.vm` swaps its tabs for the site nav plus the Directory link on the site space.
- [ ] **Step 4: Run** the test plus `-Dtest='*ThemeRendering*Test,ThemeColophon*Test,ThemeCsp*Test,FrontpageHeaderRenderingTest'` — PASS.
- [ ] **Step 5: Commit** `feat(site): site pages render with no blog-only chrome in all four themes`.

### Task 8: Site pages in the sitemap

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/SeoController.java` (`sitemapIndex` :157, `buildWeblogSitemap` :266)
- Test: extend `SeoControllerTest` (or the existing sitemap test class)

**Interfaces:**
- Consumes: Tasks 1, 2.
- Produces: `GET /sitemap-site-pages.xml` (the `-` keeps it from ever matching a blog handle).

- [ ] **Step 1: Write the failing tests.** The index lists `<abs ctx>/sitemap-site-pages.xml` when the site space has a published page, and omits it otherwise; that sitemap lists each published site page at `<abs ctx>/<slug>`, the front-door page at `<abs ctx>/`, and skips drafts and `noindex` pages; no URL in either file contains `site-space`; on a custom-domain host the index is unchanged (the blog's own sitemap) (AC11).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Reuse `buildWeblogSitemap`'s page loop with the strategy URL; the literal mapping must win over `/sitemap-{handle}.xml`.
- [ ] **Step 4: Run** `-Dtest='SeoController*Test,Sitemap*Test'` — PASS.
- [ ] **Step 5: Commit** `feat(site): site pages are in the sitemap`.

### Task 9: Browser test, docs, and the full gates

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/SitePagesIT.java`
- Modify: `docs/dev/routing.md` ("The site root"), `docs/dev/entries.md` ("Pages"), `docs/dev/themes-and-templates.md`, `docs/dev/admin-ui.md`, `docs/dev/database.md` (V039, V040), `docs/dev/configuration.md` (`site.frontpage.page`, the privacy reference forms); `it-selenium/.../support/Routes.java` if it needs the new routes

- [ ] **Step 1: Write the IT.** As the IT admin: open the main menu, follow Site → Pages, create and publish site page `it-front` with a heading, choose it as the front door in Global Config; anonymous `GET /` shows the heading; `GET /it-front` lands on `/`; unpublish it → `/` shows the site directory. Clean up by identity (docs/dev/browser-its.md) and restore the front door in `finally`.
- [ ] **Step 2: Watch it fail, then pass.** The behaviour already exists by now, so first run `mvn verify -Pit -Dit.test=SitePagesIT` with the "choose it as the front door" step removed and see the `GET /` assertion fail; restore the step and run it at both context paths (`-Dit.context.path=roller`) — PASS. Say so in the IT's javadoc.
- [ ] **Step 3: Write the docs** for every behaviour above, each in the file the CLAUDE.md index names.
- [ ] **Step 4: Run the gates** (one build at a time): `mvn clean install`, `bin/check-diff-coverage.sh`, `mvn verify -Pit` at both context paths. Record results in the report; a red gate is fixed, not suppressed.
- [ ] **Step 5: Commit** `test(site): browser test for site pages and the front door; docs`.
