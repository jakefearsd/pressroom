# First-class Pages (Release 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A blog can choose a published page as its front door (the post list moves to `/<handle>/posts`), authors and agents can order pages in the blog's nav, and published pages are found by search in their own "Pages" group.

**Architecture:** One migration adds `weblog.front_page_id`. The front door is decided once, in `WeblogPageRequest`: a plain request for the blog root whose blog has a front page is turned into a request for that page (same render path and cache entry as `/<handle>/<slug>`); `posts` is a new first segment that always means the post listing. URL builders that mean "this blog's post listing" go through the URL strategy's collection URL, which answers `/posts` when a front door is set. Search gains a `kind` field on every document (schema version 3); post queries filter to `entry`, and a separate small query returns page hits.

**Tech Stack:** Java 25, Spring MVC, JSP admin, Velocity themes, JPA/EclipseLink, PostgreSQL 18, embedded Lucene, JUnit 5 + Testcontainers, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-11-15-ordering-strands-pages-design.md`, section "Release 3: pages made first class" (criteria 19-26). Read it with this plan. Releases 1 and 2 are shipped on master (0.11.0); their patterns are the precedents: V047/V048 and `EntryOrderAndPinsMigrationTest`, `entryOrder` end to end (entity, wrapper, `WeblogConfigBean`, `WeblogSettingsRules`, `AdminDtos.WeblogPatch`), the strand Parts panel (`theme/scripts/pressroom-strand-parts.js`, `StrandPartsIT`).

## Global Constraints

- Migration `V049`, idempotent: `weblog.front_page_id varchar(48) NULL REFERENCES pressroom_weblogpage(id) ON DELETE SET NULL`. Null means today's behaviour.
- **A front door is active exactly when `weblog.frontPageId` is non-null.** It is kept truthful at the write: saving the front page with any status other than PUBLISHED, or removing it, clears `frontPageId` in the same save (republishing does not restore it). The request path still falls back to the post list if the id does not resolve to a PUBLISHED page of this blog. **Never a 404 at a blog root.**
- The front page must be a PUBLISHED page of the same blog. The site-space blog has no front-door setting (the site root stays with `FrontDoor`).
- `/<handle>/posts` **always** renders the post listing, with or without a front door, with the same query parameters the root accepts (`page`, `cat`, `date`). It is not a new `context`: the request's context stays null, so everything that tests "home listing" (pins, "All" current, the default template) holds.
- A root request carrying a listing parameter (`page` > 0, `cat`, `date`) renders the listing even with a front door: old paging links keep working. Only the plain root renders the front page.
- The canonical URL of the post listing is the URL strategy's collection URL: the blog root with no front door, `/<handle>/posts` with one (paging kept). The front page's URL (`getWeblogStaticPageURL`) is the blog root while it is the front door, as the site front door already does; `/<handle>/<slug>` keeps rendering it.
- `$url.home` means the blog (its root). The post listing is `$url.posts`. Category, tag, date, post and feed URLs do not change.
- `posts` joins `ReservedSlugs.RESERVED` (and the mapper's `FORWARD_TARGETS`, whose comment says every reserved word is a key).
- Every write that changes what a reader sees bumps `weblog.lastModified` (front door set/cleared, nav reorder).
- Search: every indexed document carries `kind` (`entry` or `page`); `SCHEMA_VERSION` becomes 3 so the existing rebuild-on-schema-change path reindexes. Indexed pages: PUBLISHED and not `noindex`, blog pages and site-space pages. Post results (ranking, counts, "similar", per-blog counts) must not change: every existing query is restricted to `kind:entry`.
- Page results: at most 3, shown above the post results on the first results page only. Blog search: that blog's pages. Site-wide search: pages of the blogs the site search already covers, plus site-space pages. Site-space pages appear only site-wide.
- API: field errors are **400** with an `errors` entry naming the field. `frontPage` is removed with `clear: ["frontPage"]` (the API never reads null as a value). What the caller may not see answers as unknown (404 policy): another blog's page id reads as an unknown page.
- `McpInstructions.TEXT` stays under 3,000 characters (about 2,600 now).
- Project gates (CLAUDE.md): TDD (watch each test fail first), JaCoCo floors, diff coverage ~90%, PMD/CPD/SpotBugs zero (suppress one-offs at the site with a reason), `-Xlint:all` zero warnings, name every `@RequestParam`, Velocity hazards (never write a zero-argument macro name with a leading `#` in a comment), escaping (page titles through `$utils.escapeHTML`; JSP EL through `fn:escapeXml`), `data-confirm` only, i18n ratchets (new keys in all 8 bundles), `Routes` CSS markers unchanged.
- Run one class: `pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || mvn -o -q -pl app test -Dtest=<Class>` (if BUSY, wait; never start a second build).
- Commit per task (local only). **Never push.**

## Review Focus

1. **Front page set, then made a draft or deleted** (editor, API, and a row changed behind the app's back): the root lists posts, the "Posts" link disappears, no 404. Tests in Tasks 1 and 2.
2. **Old listing links on a blog that gained a front door** (`/<handle>/?page=2`, `?cat=...`): still list posts; their canonical is the `/posts` form. Test in Task 2.
3. **A blog served at a virtual host's root, and the app under a context path**: the front door and `/posts` work at both; no link assumes `/<handle>/`. Tests in Task 2 (mapper, strategy) and Task 7 (browser suite at both paths).
4. **A page that matches a search where no post does**: the "Pages" group shows (today the whole results section hides with zero post hits); a draft or `noindex` page never shows, including right after it was published then unpublished. Tests in Tasks 5 and 6.
5. **Reorder with a stale or tampered id list** (a page deleted in another tab, another blog's page id, a duplicate): nothing of another blog moves; pages not named keep their relative order after the named ones. Test in Task 4.

---

### Task 1: Schema, model, and the front-page rule

**Files:**
- Create: `bin/db/migrations/V049__weblog_front_page.sql`
- Modify: `app/src/main/java/com/wikantik/pressroom/pojos/Weblog.java` (+ `setData`), `app/src/main/resources/com/wikantik/pressroom/pojos/Weblog.orm.xml` (a `<basic>` id column, as `WeblogEntry.strandPageId`), `.../pojos/wrapper/WeblogWrapper.java`
- Modify: `.../pojos/ReservedSlugs.java`
- Modify: `.../business/jpa/JPAWeblogPageManagerImpl.java` (`savePage` ~:100-117, `removePage` ~:163, `removePages`)
- Test: `app/src/test/java/com/wikantik/pressroom/business/startup/FrontPageMigrationTest.java` (pattern: `EntryOrderAndPinsMigrationTest`), `pojos/ReservedSlugsTest`, the weblog entity test, the page manager test (find with `grep -rln "removePage" app/src/test`)

**Interfaces:**
- Produces: `Weblog.getFrontPageId(): String` / `setFrontPageId(String)`; `WeblogWrapper.getFrontPageId(): String` and `WeblogWrapper.hasFrontDoor(): boolean` (id non-null).
- Produces: the manager invariant "saving the front page non-PUBLISHED, or removing it, clears `frontPageId` and saves the weblog".

- [ ] **Step 1: Write the failing tests**: migration (existing rows null; deleting the page row nulls the weblog column; V049 twice succeeds); entity round-trip and `setData` copies the field; `ReservedSlugs.isReserved("posts")` and `"Posts"`; manager: front page saved as DRAFT clears the id and bumps `lastModified`; `removePage` of the front page clears it (the in-memory weblog too, not only the row); saving a different page leaves it; a page saved PUBLISHED again does not restore it.
- [ ] **Step 2: Run; expect compile failure, then the failures above.**
- [ ] **Step 3: Implement** (migration comment in the style of V048).
- [ ] **Step 4: Run those classes plus `EqualsContractTest` and `IndexManagerTest` (Instancio fills new id fields with random text; null it in the fixture if the FK trips); expect PASS.**
- [ ] **Step 5: Commit**: `Front door: schema (V049), Weblog.frontPageId, posts reserved, front page cleared on unpublish and delete`.

---

### Task 2: Routing and rendering

**Files:**
- Modify: `.../ui/rendering/util/WeblogPageRequest.java` (`parseSinglePathElement` ~:222: recognise `posts` before the reserved check, beside `tags`; query-parameter parsing ~:252 treats `/posts` as it treats the root)
- Modify: `.../ui/rendering/WeblogRequestMapper.java` (`FORWARD_TARGETS` ~:100; `posts` forwards as HOME does, with the segment kept)
- Modify: `.../ui/rendering/servlets/PageServlet.java` (resolve the front door before the cache key ~:199; `selectTemplate` unchanged in shape)
- Modify: `.../business/MultiWeblogURLStrategy.java` (`getWeblogCollectionURL` else-branch ~:246; `getWeblogStaticPageURL` ~:316), `PreviewURLStrategy` if it overrides either
- Modify: `.../ui/rendering/model/URLModel.java` (`getHome` ~:152 → the blog root; new `getPosts()`), `PageModel.java` (`getCanonicalUrl` ~:208-231)
- Modify: `.../ui/controllers/SeoController.java` (`buildWeblogSitemap` ~:325, `appendPages` ~:358)
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (macro showPageLinks ~:2037 gains "Posts"; category "All" ~:2060; any other `$url.home` that means the listing), themes `frontpage/_header.vm` "Recent posts" ~:83; i18n key `weblog.nav.posts` in 8 bundles
- Test: `WeblogPageRequestTest`, `WeblogRequestMapperTest`, `PageRoutingTest`, `PageServletDecisionTest`, `PageServletRenderingTest`, `PageServletCachingTest`, `MultiWeblogURLStrategyTest`, `PreviewURLStrategyTest`, `PageModelTest`, `AbstractWeblogEntriesPagerTest`, `SeoControllerSitemapTest`, `ThemeReferenceLeakTest`, `WeblogEntriesLatestPagerTest` (pins on `/posts`)

**Interfaces:**
- Consumes: `Weblog.getFrontPageId()`, `WeblogWrapper.hasFrontDoor()` (Task 1).
- Produces: `WeblogPageRequest.isPostsPath(): boolean`; `WeblogPageRequest.resolveFrontDoor(): boolean` (true when it turned this request into the front page's: sets `pageSlug` and the page content; false, changing nothing, for `/posts`, for a root with a listing parameter, for a blog without a front page, or when the id does not resolve to a PUBLISHED page of this blog).
- Produces: `$url.posts` (`URLModel.getPosts(): String`), the listing URL; `$url.home` is the blog root.

- [ ] **Step 1: Write the failing tests**, one per Global Constraint that names a URL: `/h/posts` parses as the home listing (`context` null, `isPostsPath`), with `?page=2`, `?cat=x`; `/h/posts/` stays a 404; with a front page, `/h/` renders it and shares the cache key of `/h/<slug>`; `/h/?page=2` lists; an unresolvable or DRAFT front page id (set the row directly) lists at the root; no front door: `/h/` byte-for-byte as before and `/h/posts` lists with canonical at the root; strategy: collection URL is `/h/posts` (and `/h/posts?page=2` from the pager's next link) with a front door, the static-page URL of the front page is the root, both under a context path and for a virtual-host weblog; `PageModel.getCanonicalUrl` for the three cases; sitemap lists the root once and not the front page's slug URL; rendered nav shows "Posts" (linking `$url.posts`) only with a front door; pins still head `/h/posts` page 1.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement.** The front-page lookup on a root hit must not add an uncached query: use what the page manager already caches for published pages.
- [ ] **Step 4: Grep `app/src/main/webapp` and the Java rendering packages for remaining `url.home` / `getHome()` uses and decide each as "the blog" (leave) or "the listing" (`$url.posts`); list them in the report.**
- [ ] **Step 5: Run the test classes above plus `FrontpageRenderingTest` and `WeblogEntriesPagerCharacterisationTest`; expect PASS.**
- [ ] **Step 6: Commit**: `Front door: blog root renders the front page, posts list at /posts, listing URLs follow`.

---

### Task 3: Blog setting and API

**Files:**
- Modify: `.../ui/controllers/editor/WeblogConfigBean.java`, `WeblogConfigController.java` (load the blog's published pages for the select, as `loadBusinesses` ~:151), `.../ui/controllers/WeblogSettingsRules.java` (`Context` record gains what the check needs; pattern `checkBusiness` ~:129)
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp` (select under "Order posts": "Post list" + each published page; not rendered for the site space)
- Modify: `.../ui/restapi/dto/AdminDtos.java` (`WeblogView`, `WeblogPatch`, `WEBLOG_CLEARABLE`, `toView`, `applyPatch`, `clearOnBean`)
- Modify: i18n `weblogSettings.frontDoor`, `.postList`, `.help`, `weblogSettings.error.frontDoor` in 8 bundles; `docs/api/README.md`
- Test: `WeblogConfigControllerTest`, `WeblogConfigBeanTest`, the `WeblogSettingsRules` test, `WeblogsApiTest`, `FormBodyParityTest` (rename entry `frontPageId` → `frontPage`), `ToolSchemasTest`/`McpToolCatalogTest` if they pin the weblog fields
- Create: `it-selenium/src/test/java/com/wikantik/pressroom/it/FrontDoorIT.java`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: bean field `frontPageId` (blank = post list); API field `frontPage` (page id, or absent; clearable) on weblog get and update.

- [ ] **Step 1: Write the failing tests**: bean copy both ways; rules refuse a DRAFT page, another blog's page, an unknown id (`weblogSettings.error.frontDoor`), and any value on the site space; saving bumps `lastModified`; API get shows `frontPage`; PATCH sets it; `clear: ["frontPage"]` removes it; DRAFT/other-blog/unknown id → 400 with `errors[].field == "frontPage"`; JSP escapes page titles in the select.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Write `FrontDoorIT`**: create a page, publish, choose it as front door in blog settings; the blog root shows the page's title and a "Posts" nav link; `/posts` lists a post; set the page to draft; the root lists posts again. (Harness conventions: `docs/dev/browser-its.md`; it runs in Task 7.)
- [ ] **Step 5: Run the unit classes; expect PASS. Compile the IT module (`mvn -o -q -pl it-selenium test-compile`).**
- [ ] **Step 6: Commit**: `Front door: blog setting, API frontPage`.

---

### Task 4: Nav order

**Files:**
- Modify: `.../business/WeblogPageManager.java` + `JPAWeblogPageManagerImpl.java`
- Modify: `.../ui/controllers/editor/PagesController.java` (new POST route `pagesOrder.rol`), `app/src/main/webapp/WEB-INF/jsps/editor/Pages.jsp` (up/down buttons and a drag handle per row, hidden ordered ids, "Save order" shown once the order changes; keep `#pageRemoveForm`, the `Routes` marker)
- Modify: `app/src/main/webapp/theme/scripts/pressroom-strand-parts.js`: move its up/down and drag logic into a shared `pressroom-reorder.js` that both screens load; parameterise by selector, do not copy. `PageEditJspTest` ~:342-398 pins the handlers.
- Modify: `.../ui/restapi/dto/PageDtos.java` (~:177: validate `navOrder`), `docs/api/README.md`; i18n for the buttons and the saved message in 8 bundles
- Test: page manager test, `PagesControllerTest`, `PageDtosTest`, `PagesApiTest`, `PageEditJspTest`, a JSP test for `Pages.jsp` (pattern: `PageEditJspTest`); extend `it-selenium/.../PageIT` (or a new `PageOrderIT`)

**Interfaces:**
- Produces: `WeblogPageManager.setNavOrder(Weblog weblog, List<String> pageIds)`: the named pages of **this weblog** get `navOrder` 0..n-1 in the given order (ids of other weblogs, unknown ids and repeats are skipped); pages not named follow, keeping their current relative order; bumps `lastModified`.
- Produces: `pagesOrder.rol` (POST, parameter `pageIds`, repeated), needing the permission the Pages screen already needs.

- [ ] **Step 1: Write the failing tests**: manager (reorder three pages; stale id skipped; other blog's id moves nothing there; duplicate counted once; unnamed pages follow in prior order; `lastModified` bumped; `getPages` returns the new order); controller (POST reorders and redirects with the saved message; GET of the route is refused); API (`navOrder` -1 → 400 naming `navOrder`; 2 accepted and read back); `Pages.jsp` renders the controls and escapes titles; the strand Parts panel tests still pass against the shared script.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Browser IT**: three published pages in the nav; move the last one up twice with the buttons, save; the blog's nav lists them in the new order (criterion 23). Buttons only, no drag.
- [ ] **Step 5: Run the unit classes; compile the IT module; expect PASS.**
- [ ] **Step 6: Commit**: `Pages: reorder the nav from the Pages screen; API validates navOrder`.

---

### Task 5: Pages in the search index

**Files:**
- Modify: `.../business/search/lucene/FieldConstants.java` (`KIND`, values `entry`, `page`), `IndexOperation.java` (`getDocument(WeblogEntry)` adds the kind; new `getDocument(WeblogPage)`), `LuceneIndexManager.java` (`SCHEMA_VERSION` 3 ~:118; page add/remove entry points), `RebuildWebsiteIndexOperation.java` (~:154: re-add the weblog's indexable pages), `SearchOperation.java` (~:366-427: every existing query filtered to `kind:entry`, `similar` ~:475 included), `.../business/search/IndexManager.java`
- Create: page index operations beside `ReIndexEntryOperation` / `RemoveEntryOperation`
- Modify: the page write paths so every save and removal reindexes: one place if the page manager can own it (`savePage`, `removePage`, `removePages`), otherwise each caller (`PageEditController`, `PagesController`, `PagesApi`)
- Test: `IndexManagerTest`, `LuceneIndexManagerTest`, `LuceneIndexManagerSearchTest`, `LuceneIndexManagerStartupTest` (a version-2 index rebuilds), `SearchIndexQueryTest`

**Interfaces:**
- Produces: `IndexManager.reindexPage(WeblogPage)` (indexes it when PUBLISHED and not `noindex`, else removes it) and `IndexManager.removePage(WeblogPage)`.
- Produces: `IndexManager.findPages(SearchSpec spec, int max): List<String>` (page ids, best first; BLOG scope: that blog; SITE scope: the blogs the site search covers plus the site space; OTHER_BLOGS: empty).
- Page document fields: id, `kind=page`, handle, title, content (the page's text as posts index theirs), and whatever the scope clauses need. No publish-time field is required of a page: the page query does not use the entries' not-future clause.

- [ ] **Step 1: Write the failing tests**: a published page is found by `findPages` for a word in its title and for one in its body; DRAFT and `noindex` pages are not; publish → found, then set DRAFT → not found, then delete → not found; blog scope excludes other blogs' pages and site pages; site scope includes site pages and excludes pages of a blog the site search excludes; **post searches return exactly what they did** with pages in the index (hits, total, per-blog counts, `similar`); a full rebuild and a one-weblog rebuild both restore pages; an index stamped version 2 is rebuilt at startup.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the search test classes plus the page manager/controller/API tests touched by the write-path change; expect PASS.**
- [ ] **Step 5: Commit**: `Search: published pages are indexed as their own kind (schema 3)`.

---

### Task 6: The "Pages" group in search results

**Files:**
- Modify: `.../ui/rendering/model/SearchResultsModel.java` (page hits beside `findPaged` ~:144 and `findInBlogView` ~:166), `.../ui/rendering/pagers/SearchResultsPager.java` if it carries the hits
- Create: `PageHit` record beside `SearchHit`: `(WeblogPageWrapper page, String snippet)`
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (macro showSearchResults ~:2741: the group, and the section no longer hides when only pages match), `templates/weblog/site-search.vm` if it prints results outside the macro; i18n `search.pages` ("Pages") in 8 bundles
- Test: `SearchResultsModelTest`, `SearchResultsModelScopesTest`, `SearchResultsMacroTest`, `ThemeReferenceLeakTest`; extend `it-selenium/.../SearchIT`

**Interfaces:**
- Consumes: `IndexManager.findPages` (Task 5).
- Produces: `$model.pageHits` (`List<PageHit>`, at most 3, empty beyond the first results page and for the other-blogs scope). Each hit links with the page's static-page URL (so a front page links to its blog root); in the site-wide search each hit names its blog.

- [ ] **Step 1: Write the failing tests**: model returns at most 3, only on page 1, only PUBLISHED pages the index still names (an id the database no longer has is dropped, not an error); macro prints the group above the post results with titles through `$utils.escapeHTML`; a query matching a page and no post shows the group and the "no posts" wording, not an empty section; a query matching no page prints no group and the post results markup is unchanged.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement** (group styling within the existing search result classes; no new theme files).
- [ ] **Step 4: `SearchIT`**: a page with a distinctive word appears under "Pages" in its blog's search and in `/search`; a `noindex` page does not (criterion 24).
- [ ] **Step 5: Run the unit classes; compile the IT module; expect PASS.**
- [ ] **Step 6: Commit**: `Search: matching pages show in a Pages group above the posts`.

---

### Task 7: MCP hint, docs, whole-release verification

**Files:**
- Modify: `.../ui/restapi/mcp/McpInstructions.java` (one line: `weblogs update` `frontPage` makes a published page the blog's root, posts then list at `/posts`; `pages update` `navOrder` orders the nav), `McpInstructionsTest` (points `frontPage`, `navOrder`; length still < 3000), `McpDocsTest` if it pins the docs
- Modify: `docs/dev/routing.md` (front door, `/posts`), `docs/dev/entries.md` (Pages: front door, nav order), `docs/dev/api.md`, `docs/api/README.md`, `docs/dev/architecture.md` or `discovery.md` search section (document kinds, schema 3), `docs/dev/themes-and-templates.md` (`$url.posts`, `$model.pageHits`)

- [ ] **Step 1: Failing test first** (`McpInstructionsTest` points), then the text, then docs.
- [ ] **Step 2: `mvn -o -pl app verify -DskipITs`**: all tests, coverage floors, PMD/CPD/SpotBugs, then `bin/check-diff-coverage.sh` against the plan's start commit (≥ 90%).
- [ ] **Step 3: `mvn -o verify -Pit`** at `/`, then again with `-Dit.context.path=pressroom` (criterion 26). Both must pass; report counts.
- [ ] **Step 4: Commit**: `Front door, page order, page search: MCP hint and docs`.
