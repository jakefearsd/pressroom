# Front Page Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the site directory at `/` with a discovery front page (popular from Umami, a configurable share always random), add a "more from the site" block plus "places you've been" to every blog, and give admins the settings and status to run it.

**Architecture:** A new business-tier `DiscoveryManager` holds an immutable snapshot (candidate entries from Pressroom's DB + view scores read from Umami's `analytics_traffic` view over an optional read-only JDBC connection), rebuilt by a leased background task. A pure `DiscoverySlots` function fills each surface (ranked, then a seeded random share, gap-filled randomly). Each rebuild advances a static generation that the render caches fold into their freshness check, so cached pages reshuffle with the snapshot. A Velocity `DiscoveryModel` (`$discovery`) feeds a `discovery` template in the site space and two macros emitted from `showColophon`, so every bundled and forked theme gets the block.

**Tech Stack:** Java 25, Spring Boot 4.1, JPA/EclipseLink, PostgreSQL 18 (Testcontainers), Velocity, JSP admin UI, JUnit 5, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-11-01-front-page-discovery-design.md` — read it first; "AC n" below means its acceptance criterion n.

## Global Constraints

- Popularity window default **30** days; discovery share default **20**%; refresh interval default **3600** seconds, stored in **seconds**, edited in **whole minutes** in the UI.
- Ranges (my call, enforced in Global Config and `PATCH /v1/admin/config`): window 1–365 days; share 0–100; refresh 1–1440 minutes (60–86400 seconds).
- Per-blog "show more from the site" default **on**; opting out hides the block on that blog only, never removes the blog from `/` or other blogs' blocks.
- Eligible content: `PUBLISHED`, `noindex` not true, on weblogs that are `visible` and `active` and not the site space. Never pages, drafts, scheduled, pending or trashed.
- At least one random slot per row of ≥3 **when share > 0**; at 0% only gap-fill is random.
- Headline: top-scoring post; with no scores, newest eligible post. Never random.
- Random picks are stable for one generation (seeded) and reshuffle only when the snapshot rebuilds.
- `pinnedToMain` is ignored. Leave a single obvious seam ("promoted first", a comment and an empty step in `DiscoverySlots.fill`'s caller) for the next project; build nothing for it.
- "Places you've been": `localStorage` only, last **5** blogs, newest first, per origin; renders nothing without storage/JS.
- Umami is optional: blank `analytics.jdbc.connectionURL` means all-random, no startup error.
- Repo rules: TDD (watch each test fail first); `@Lazy Weblogger` in constructors; name every `@RequestParam`; SLF4J throwable last; no bare `--` in XML comments; no `{}` in `config/pmd/ruleset.xml`; i18n ratchets stay empty; no context-path assumptions; entry titles emitted bare, blog names via the wrapper (already escaped), page titles via `$utils.escapeHTML`; one build at a time per worktree (CLAUDE.md busy check).

## Review Focus

1. **A configured front-door site page that is deleted/unpublished** — must fall through to the discovery page, not the directory, not a 404 (Task 7 test `brokenFrontDoorPageShowsDiscovery`).
2. **Umami reachable but slow or hanging** — the refresh must time out (connect 5 s, query 15 s) and keep the last snapshot; a page request must never wait on Umami except the one-time first build (Task 4 `queryTimeoutIsSet`, Task 5 `refreshFailureKeepsLastScores`).
3. **An entry unpublished, trashed or set `noindex` between rebuilds** — must not render even though the selection still names it (Task 5 `selectionDropsEntriesNoLongerEligible`).
4. **A blog/entry name carrying `<script>` or quotes** — must not break the front page, the block, or the inline "places" script's data attributes (Task 7 `frontPageEscapesBlogNames`, Task 8 `placesDataAttributesAreEscaped`).
5. **Fewer eligible posts than slots (a brand-new install with one blog)** — sections shrink without duplicates or errors; the block on the only blog renders nothing rather than an empty box (Task 5 `smallPoolNeverDuplicates`, Task 8 `noOtherBlogsRendersNothing`).

---

## File map

New, `app/src/main/java/com/wikantik/pressroom/`:
- `business/discovery/DiscoverySlots.java` — pure slot-filling.
- `business/discovery/DiscoverySettings.java` — reads + validates the three runtime properties.
- `business/discovery/TrafficRow.java`, `TrafficSource.java`, `UmamiTrafficSource.java` — Umami read.
- `business/discovery/DiscoveryManager.java`, `DiscoveryStatus.java`, `FrontPageSelection.java`, `BlogCard.java` — API.
- `business/discovery/JPADiscoveryManagerImpl.java` — snapshot + selection.
- `business/runnable/PopularityRefreshTask.java` — scheduled rebuild.
- `util/DiscoveryGeneration.java` — static generation clock (mirrors `util/ServingSince.java`).
- `ui/rendering/model/DiscoveryModel.java` — `$discovery`.

New, elsewhere: `bin/db/migrations/V042__weblog_more_from_site.sql`; `app/src/main/webapp/themes/frontpage/discovery.vm`; `app/src/main/webapp/WEB-INF/velocity/templates/weblog/discovery.vm`; `docs/dev/discovery.md`; `it-selenium/.../DiscoveryIT.java`; `app/src/test/java/com/wikantik/pressroom/testing/UmamiTestDatabase.java`.

Modified: `runtimeConfigDefs.xml`, `ApplicationResources.properties`, `GlobalConfigController`, `GlobalConfig.jsp`, `AdminApi`, `Weblog.java` + `Weblog.orm.xml`, `WeblogWrapper`, `WeblogConfigBean`, `WeblogConfig.jsp`, `AdminDtos`, `Weblogger`/`WebloggerImpl`/`JPAWebloggerImpl`/`WebloggerBeanConfig`, `pressroom.properties`, `RenderCaches`, `PageServlet`, `FrontDoor`, `themes/frontpage/theme.xml`, `WEB-INF/velocity/weblog.vm`, `MaintenanceController` + `Maintenance.jsp`, `UmamiViewScriptTest`, `deploy/.env.example`, `docker_deployment.md`, `docs/dev/routing.md`, `docs/dev/audience-and-analytics.md`, `docs/api/README.md`, `CLAUDE.md` (index row only).

---

### Task 1: Slot filling (pure)

**Files:**
- Create: `app/src/main/java/com/wikantik/pressroom/business/discovery/DiscoverySlots.java`
- Test: `app/src/test/java/com/wikantik/pressroom/business/discovery/DiscoverySlotsTest.java`

**Interfaces:**
- Produces:
  - `static int randomSlots(int slots, int sharePercent)` — `0` when share is 0; otherwise `max(slots >= 3 ? 1 : 0, Math.round(slots * share / 100f))`, capped at `slots`.
  - `static <T> List<T> fill(List<T> ranked, List<T> pool, int slots, int sharePercent, Set<T> taken, Random random, ToDoubleFunction<T> weight)` — `ranked` is score-descending, holds only scored items; `pool` is every candidate. Takes `slots - randomSlots(...)` from `ranked` skipping `taken`, then fills every remaining slot by weighted random sampling without replacement from `pool` minus `taken` minus already chosen. Adds every chosen item to `taken` (the caller's cross-section dedupe set). Returns ranked picks first, then random picks. Returns fewer than `slots` only when the pool is exhausted. Weight ≤ 0 is treated as a tiny positive weight, never excluded.

- [ ] **Step 1: Write the failing tests**

```java
@Test void zeroShareHasNoRandomSlots()      { assertEquals(0, DiscoverySlots.randomSlots(4, 0)); }
@Test void twentyPercentOfFourIsOne()       { assertEquals(1, DiscoverySlots.randomSlots(4, 20)); }
@Test void floorAppliesOnlyFromThreeSlots() { assertEquals(1, DiscoverySlots.randomSlots(3, 5));
                                              assertEquals(0, DiscoverySlots.randomSlots(2, 5)); }
@Test void fullShareIsAllRandom()           { assertEquals(4, DiscoverySlots.randomSlots(4, 100)); }

@Test void rankedSlotsFollowScoreOrder() {
    List<String> got = fill(List.of("a","b","c","d","e"), pool("a".."j"), 4, 20, new HashSet<>(), new Random(1), x -> 1);
    assertEquals(List.of("a","b","c"), got.subList(0, 3));
    assertFalse(List.of("a","b","c").contains(got.get(3)));      // AC 3
}
@Test void gapsFillRandomlyWhenFewRanked() {                       // AC 4
    List<String> got = fill(List.of("a"), pool("a".."j"), 4, 0, new HashSet<>(), new Random(1), x -> 1);
    assertEquals(4, got.size()); assertEquals("a", got.get(0)); assertEquals(4, new HashSet<>(got).size());
}
@Test void takenItemsAreSkippedAndRecorded() {                     // AC 5
    Set<String> taken = new HashSet<>(Set.of("a"));
    List<String> got = fill(List.of("a","b"), pool("a".."e"), 3, 0, taken, new Random(1), x -> 1);
    assertFalse(got.contains("a")); assertTrue(taken.containsAll(got));
}
@Test void smallPoolNeverDuplicates() {
    List<String> got = fill(List.of(), List.of("a","b"), 4, 20, new HashSet<>(), new Random(1), x -> 1);
    assertEquals(Set.of("a","b"), new HashSet<>(got)); assertEquals(2, got.size());
}
@Test void sameSeedSamePicks() {
    assertEquals(fill(List.of(), pool("a".."j"), 3, 100, new HashSet<>(), new Random(7), x -> 1),
                 fill(List.of(), pool("a".."j"), 3, 100, new HashSet<>(), new Random(7), x -> 1));
}
@Test void weightingFavoursHeavierItems() {
    // over 2000 seeds, item "light" (weight 1) is picked < 1/3 as often as "heavy" (weight 10) for 1 slot from {heavy, light}
}
```

(`pool("a".."j")` is a test helper building `List.of("a",…,"j")`.)

- [ ] **Step 2: Run, expect FAIL (class missing)** — `mvn -pl app test -Dtest=DiscoverySlotsTest`
- [ ] **Step 3: Implement `DiscoverySlots`** (final class, private constructor). Weighted sampling: cumulative-sum draw over the remaining list per slot; the sizes involved (≤ a few thousand) need nothing cleverer.
- [ ] **Step 4: Run, expect PASS**
- [ ] **Step 5: Commit** — `feat(discovery): slot filling, ranked then a seeded random share`

---

### Task 2: Site-wide settings and Global Config

**Files:**
- Modify: `app/src/main/resources/com/wikantik/pressroom/config/runtimeConfigDefs.xml` (new `display-group name="discoverySettings" key="configForm.discoverySettings"` after the site group)
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Create: `app/src/main/java/com/wikantik/pressroom/business/discovery/DiscoverySettings.java`
- Modify: `app/src/main/java/com/wikantik/pressroom/ui/controllers/admin/GlobalConfigController.java` (`save`, beside `applyFrontDoorChoice`)
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/GlobalConfig.jsp` (special case beside the front-door one at :50)
- Modify: `app/src/main/java/com/wikantik/pressroom/ui/restapi/v1/AdminApi.java` (`PATCH /v1/admin/config`, after the type check at ~:357)
- Test: `DiscoverySettingsTest`, extend `GlobalConfigControllerTest`, `GlobalConfigJspTest`, `AdminApiTest` (whichever class covers `PATCH /v1/admin/config`)

**Interfaces:**
- Produces (`DiscoverySettings`, final, static):
  - `String WINDOW_DAYS = "site.discovery.windowDays"` (integer, default 30), `SHARE_PERCENT = "site.discovery.sharePercent"` (integer, 20), `REFRESH_SECONDS = "site.discovery.refreshSeconds"` (integer, 3600).
  - `static int windowDays()`, `static int sharePercent()`, `static int refreshSeconds()` — read via `WebloggerRuntimeConfig.getIntProperty`; a missing/invalid/out-of-range stored value returns the default (display-safe).
  - `static Optional<String> rangeError(String name, int value)` — message key `configForm.error.discoveryRange` when out of range, empty otherwise; empty for unrelated names.
  - `static String REFRESH_MINUTES_FIELD = "discoveryRefreshMinutes"` — the form field the JSP posts instead of the seconds property.

- [ ] **Step 1: Write failing tests**
  - `DiscoverySettingsTest`: `defaultsWhenUnset` (30/20/3600); `outOfRangeStoredValueReadsAsDefault` (share 150 → 20); `rangeErrorBounds` (window 0 and 366 error, 1 and 365 ok; share -1/101 error, 0/100 ok; refreshSeconds 59/86401 error, 60/86400 ok).
  - `GlobalConfigControllerTest`: `refreshIsEditedInMinutesStoredInSeconds` (post `discoveryRefreshMinutes=90` → stored `site.discovery.refreshSeconds=5400`); `outOfRangeShareIsRefusedAndNothingSaved` (share=101 plus a changed site name → error `configForm.error.discoveryRange`, site name unchanged — the controller validates all before changing any); `nonIntegerMinutesRefused`.
  - `GlobalConfigJspTest`: `discoverySectionHasRailLink` (existing rail-link check covers it once the group exists — assert the heading text key `configForm.discoverySettings` renders); `refreshShownInMinutes` (stored 3600 renders an input `name="discoveryRefreshMinutes"` with `value="60"`, and no input named `properties[site.discovery.refreshSeconds]...`).
  - Admin API: `patchRejectsShareOutOfRange` (422/400 as the existing integer error path answers, field named); `patchAcceptsRefreshSeconds` (API speaks stored seconds: `"site.discovery.refreshSeconds": "120"` saves 120).
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement.** Property defs (no bare `--` in any comment). Keys: `configForm.discoverySettings=Front page discovery`, `configForm.discoveryWindowDays=Popularity window (days of traffic counted)`, `configForm.discoverySharePercent=Share of front-page slots filled at random (percent)`, `configForm.discoveryRefreshMinutes=How often popularity refreshes and random picks reshuffle (minutes)`, `configForm.error.discoveryRange={0} must be between {1} and {2}.` The seconds property's `key` is the minutes label. Controller: read `discoveryRefreshMinutes`, validate integer and 1–1440, store `× 60`; run `rangeError` for the other two inside the existing validate-everything pass. JSP: render the seconds property as a `type="number" min="1" max="1440"` minutes input; others via the generic integer widget.
- [ ] **Step 4: Also retitle the front-door copy now that "None" is the discovery page:** `configForm.frontpageWeblogHandle=What to show at the site front: the discovery page, a site page, or a weblog`, `configForm.frontpageWeblogHandle.none=Discovery page (popular and new posts from every blog)`, `configForm.frontpageStalePage={0} (unpublished: the discovery page shows until it is published again)`. Update any test pinning the old strings.
- [ ] **Step 5: Run the task's tests + `MessageKeyTest`, `MessagePlaceholderContractTest`, `MessageFormatRegressionTest`, `QualityGatePomTest`; expect PASS**
- [ ] **Step 6: Commit** — `feat(discovery): front page discovery settings in Global Config`

---

### Task 3: Per-blog "show more from the site" setting

**Files:**
- Create: `bin/db/migrations/V042__weblog_more_from_site.sql` — `ALTER TABLE weblog ADD COLUMN IF NOT EXISTS show_more_from_site boolean DEFAULT true NOT NULL;`
- Modify: `pojos/Weblog.java` (field `Boolean showMoreFromSite = Boolean.TRUE`, accessors, copy method beside `commentNotify` :294), `Weblog.orm.xml` (`<basic name="showMoreFromSite"><column name="show_more_from_site"/></basic>`), `pojos/wrapper/WeblogWrapper.java` (`getShowMoreFromSite()`), `ui/controllers/editor/WeblogConfigBean.java` (field default `false`, copyFrom `!Boolean.FALSE.equals(...)`, copyTo), `WeblogConfig.jsp` (checkbox `name="bean.showMoreFromSite"`, in the section holding display/reader settings), `ui/restapi/dto/AdminDtos.java` (`WeblogView` + `WeblogPatch` + `applyPatch`), `docs/api/README.md` (field listed beside `commentNotify`).
- Test: mirror every `commentNotify` test: `WeblogConfigBeanTest`, `WeblogConfigBusinessJspTest`, a migration test like `CommentsMigrationTest` (`MoreFromSiteMigrationTest`), `WeblogsApiTest`.

**Interfaces:**
- Produces: `Weblog.getShowMoreFromSite(): Boolean` (null reads as true everywhere: callers use `!Boolean.FALSE.equals(...)`); wrapper `WeblogWrapper.getShowMoreFromSite(): boolean`.

- [ ] **Step 1: Write failing tests** — `columnDefaultsTrue` (insert a weblog row without the column → true); bean `uncheckedBoxSavesFalse`, `nullReadsAsTrue`; JSP `checkboxNamedAndLabelled` (`bean.showMoreFromSite`, code `weblogSettings.showMoreFromSite`); API `patchShowMoreFromSiteFalse` and `viewIncludesShowMoreFromSite`.
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement.** Keys: `weblogSettings.showMoreFromSite=Show popular posts from the site''s other blogs on this blog` (MessageFormat: doubled apostrophe, per `MessageFormatRegressionTest`) and `weblogSettings.showMoreFromSite.help=Turning this off hides the block on this blog only. Its posts still appear on the front page and on other blogs.`
- [ ] **Step 4: `./pressroom migrate` against the dev DB, then run the task's tests; expect PASS**
- [ ] **Step 5: Commit** — `feat(discovery): per-blog switch for the more-from-the-site block`

---

### Task 4: Reading traffic from Umami

**Files:**
- Create: `business/discovery/TrafficRow.java`, `TrafficSource.java`, `UmamiTrafficSource.java`
- Modify: `app/src/main/resources/com/wikantik/pressroom/config/pressroom.properties` — commented-blank keys `analytics.jdbc.connectionURL=`, `analytics.jdbc.username=`, `analytics.jdbc.password=` with a comment pointing at docker_deployment.md
- Create: `app/src/test/java/com/wikantik/pressroom/testing/UmamiTestDatabase.java` — the scratch-DB helpers moved out of `UmamiViewScriptTest` (create DB, minimal `website_event`, apply `deploy/analytics/umami-views.sql`, insert events, drop)
- Modify: `UmamiViewScriptTest` to use it (characterisation: its assertions unchanged and passing before and after the move)
- Test: `UmamiTrafficSourceTest`

**Interfaces:**
- Produces:
  - `record TrafficRow(String websiteId, String entryAnchor, long views)`
  - `interface TrafficSource { boolean isConfigured(); List<TrafficRow> viewsSince(LocalDate firstDay) throws WebloggerException; }`
  - `final class UmamiTrafficSource implements TrafficSource` — ctor `(String url, String username, String password)`; `static TrafficSource fromConfig()` reads the three keys via `WebloggerConfig.getProperty` (blank URL → a source whose `isConfigured()` is false and `viewsSince` returns `List.of()`). Query: `SELECT CAST(website_id AS text), entry_anchor, SUM(views) FROM analytics_traffic WHERE day >= ? AND entry_anchor IS NOT NULL GROUP BY 1, 2`. Connection via `DriverManager` with `connectTimeout=5`, `setReadOnly(true)`, statement `setQueryTimeout(15)`. Any `SQLException` → `WebloggerException` with the cause.

- [ ] **Step 1: Refactor `UmamiViewScriptTest` onto `UmamiTestDatabase`; run it, expect PASS (characterisation — say so in `UmamiTestDatabase`'s javadoc)**
- [ ] **Step 2: Write failing tests** (scratch DB via `UmamiTestDatabase`): `sumsViewsPerEntryInWindow` (two days of events for one anchor → summed); `excludesDaysBeforeWindow` (AC 6: events only before `firstDay` → no row); `ignoresNonEntryPaths` (`/weblog/` home path → no row); `unconfiguredReturnsNothing`; `unreachableThrowsWebloggerException` (URL to a closed port); `queryTimeoutIsSet` (a `pg_sleep` view swap is overkill — assert via a test seam: `UmamiTrafficSource` exposes package-private `int queryTimeoutSeconds()` = 15 and `Properties connectionProperties()` containing `connectTimeout=5`).
- [ ] **Step 3: Run, expect FAIL**
- [ ] **Step 4: Implement**
- [ ] **Step 5: Run, expect PASS**
- [ ] **Step 6: Commit** — `feat(discovery): read entry views from Umami's analytics_traffic view`

---

### Task 5: DiscoveryManager — snapshot and selection

**Files:**
- Create: `business/discovery/DiscoveryManager.java`, `DiscoveryStatus.java`, `FrontPageSelection.java`, `BlogCard.java`, `JPADiscoveryManagerImpl.java`
- Create: `util/DiscoveryGeneration.java`
- Modify: `business/Weblogger.java` (`DiscoveryManager getDiscoveryManager()`), `WebloggerImpl`, `JPAWebloggerImpl`, `business/jpa/WebloggerBeanConfig.java` (`@Bean discoveryManager(@Lazy Weblogger, TrafficSource)`, `@Bean trafficSource()` returning `UmamiTrafficSource.fromConfig()`)
- Test: `JPADiscoveryManagerImplTest` (real test tier via `TestUtils.setupWeblogger()`, fake `TrafficSource`), `DiscoveryGenerationTest`

**Interfaces:**
- Consumes: Task 1 `DiscoverySlots`, Task 2 `DiscoverySettings`, Task 4 `TrafficSource`/`TrafficRow`.
- Produces:
  - `DiscoveryGeneration` (final, static, like `ServingSince`): `static long current()` (0 until first rebuild), `static void advance(long millis)` (monotonic: ignores a value ≤ current).
  - `record DiscoveryStatus(State state, Instant lastAttempt, Instant lastSuccess, int scoredEntries, int candidates, String lastError)` with `enum State { DISABLED, NEVER, OK, FAILED }` (DISABLED = Umami not configured; still random).
  - `record BlogCard(Weblog weblog, List<WeblogEntry> topEntries)`
  - `record FrontPageSelection(WeblogEntry headline, List<WeblogEntry> popular, List<BlogCard> blogs, List<WeblogEntry> different, List<Weblog> allBlogs)` — `headline` null only when nothing is eligible.
  - `interface DiscoveryManager`:
    - `void refresh()` — never throws; rebuilds candidates from Pressroom (all eligible entries: id, weblog id, pubTime), reads `trafficSource.viewsSince(today - windowDays + 1)`, maps `websiteId → weblog` via each candidate weblog's `analyticsSiteId` (case-insensitive) and `anchor → entry`; on `WebloggerException` keeps the previous scores, records FAILED + message (logged with the throwable last); always swaps in the new candidate list and calls `DiscoveryGeneration.advance(now)` so picks reshuffle even while Umami is down; clears `SiteWideCache`.
    - `DiscoveryStatus getStatus()`
    - `FrontPageSelection selectFrontPage()` — sizes: headline 1, popular 4, blogs 3 × 3 entries, different 3 (constants in the impl). One shared `taken` set across headline, popular, every card's entries and different (AC 5). `different` uses share 100. Blog cards: weblogs ranked by summed entry score, random share applied to the card list; each card's entries filled with the same rule scoped to that weblog. `allBlogs` = eligible weblogs by name, case-insensitive.
    - `List<WeblogEntry> selectMoreFromSite(Weblog current, int count)` — candidates from other weblogs only.
  - Selections are computed as **entry/weblog ids**, memoized per generation (front page once; more-from-site per weblog id), with `new Random(generation * 31 + salt)` (salt: a fixed constant per section; the weblog id's hash for more-from-site). Each call resolves ids through `WeblogEntryManager.getWeblogEntry(id)` / `WeblogManager.getWeblog(id)` and **drops any that are no longer eligible** (status, `noindex`, weblog visible/active). Random weight: `1.0 / (1 + score)`, × 2 when published within 30 days.
  - First call to any `select*` with no snapshot runs `refresh()` synchronously, single-flight (`synchronized`).
  - Seam: in `selectFrontPage`, a comment `// promoted first: the promotion project fills slots here, before ranking` with nothing else.

- [ ] **Step 1: Write failing tests** (seed weblogs/entries with existing `TestUtils` helpers; fake `TrafficSource` returning given rows or throwing):
  - `noTrafficStillFillsEverySection` (AC 1: 3 weblogs × 5 entries, source unconfigured → headline is the newest entry, popular has 4, blogs 3 with entries, different 3, status DISABLED).
  - `headlineIsTopScored` and `popularFollowsScoreOrder` (AC 2; share 0 via `DiscoverySettings`).
  - `blogCardsFollowBlogScore` (AC 2).
  - `noEntryAppearsTwiceOnTheFrontPage` (AC 5).
  - `moreFromSiteExcludesCurrentBlog` (AC 5).
  - `refreshFailureKeepsLastScores` (AC 7: success then throwing source → same ranking, status FAILED with message, generation advanced).
  - `ineligibleContentNeverAppears` (AC 8: a draft, a scheduled, a trashed, a `noindex` entry, an inactive weblog's entry, a site-space page → never in any selection).
  - `selectionDropsEntriesNoLongerEligible` (select, then trash a selected entry without refreshing → absent from the next call's result).
  - `optedOutBlogStillAppears` (AC 9 business half: `showMoreFromSite=false` weblog's entries still selectable on the front page and in another blog's block).
  - `samePicksWithinAGeneration` / `picksCanChangeAcrossGenerations`.
  - `unmappedTrafficIsIgnored` (rows for an unknown website id and an unknown anchor → no error, no score).
  - `DiscoveryGenerationTest.advanceIsMonotonic`.
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement; wire the beans** (every `Weblogger` parameter `@Lazy`).
- [ ] **Step 4: Run, expect PASS; also run `WebloggerBeanConfig`-related wiring tests (whatever boots the tier, e.g. `TestUtils`-based suites in `business/`)**
- [ ] **Step 5: Commit** — `feat(discovery): popularity snapshot and front-page selection`

---

### Task 6: Scheduled refresh and cache expiry

**Files:**
- Create: `business/runnable/PopularityRefreshTask.java` (extends `PressroomTaskWithLeasing`; model on `TrashPurgeTask`)
- Modify: `pressroom.properties` — `tasks.enabled=ScheduledEntriesTask,TrashPurgeTask,PopularityRefreshTask`; `tasks.PopularityRefreshTask.class=…`, `startTime=immediate`, `interval=60`, `leaseTime=10`
- Modify: `ui/rendering/util/cache/RenderCaches.java:60` (`lastModifiedOf`)
- Test: `PopularityRefreshTaskTest`, `RenderCachesTest` (or the existing test covering `lastModifiedOf`), `TaskConfigTest` if one pins `tasks.enabled`

**Interfaces:**
- Consumes: Task 5 `DiscoveryManager.refresh()`, `DiscoveryGeneration.current()`.
- Produces: `PopularityRefreshTask.getInterval()` = `max(1, ceil(DiscoverySettings.refreshSeconds() / 60.0))` minutes, re-read on every call (TaskScheduler asks each tick), so a Global Config change takes effect without restart. `runTask()` calls `getDiscoveryManager().refresh()`.

- [ ] **Step 1: Write failing tests** — `intervalFollowsRuntimeSetting` (5400 s → 90; 90 s → 2); `runCallsRefresh`; `lastModifiedIncludesGeneration` (AC 10: weblog lastModified T, `DiscoveryGeneration.advance(T+1000)` → `lastModifiedOf` ≥ T+1000, so an entry cached at T+500 is stale); `taskIsEnabledByDefault` (properties list contains it).
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement.** `lastModifiedOf` returns `Math.max(<current value>, DiscoveryGeneration.current())` in both branches' sense (the no-lastModified branch already returns now).
- [ ] **Step 4: Run the task's tests plus every test under `ui/rendering/util/cache/` and `ui/rendering/servlets/` (304 / Last-Modified behaviour), expect PASS**
- [ ] **Step 5: Commit** — `feat(discovery): hourly refresh; cached pages expire with each reshuffle`

---

### Task 7: The front page

**Files:**
- Create: `ui/rendering/model/DiscoveryModel.java` (model name `discovery`; `init` uses `ModelLoader.requireWeblogger` / `requireUrlStrategy`)
- Modify: `pressroom.properties` `rendering.pageModels` (:411) — append `DiscoveryModel`
- Modify: `ui/rendering/servlets/PageServlet.java` `selectTemplate` (:433-512) — in the site space, page context `discovery` resolves to the site space theme's template named `discovery` when present, else `new StaticThemeTemplate("templates/weblog/discovery.vm", VELOCITY)`; every other non-stylesheet name still 404s
- Create: `app/src/main/webapp/themes/frontpage/discovery.vm` (+ hidden template entry in `themes/frontpage/theme.xml`, `<link>discovery</link>`) — `fd-*` design, built on `_header`/`_footer`, CSP meta as in `_header.vm:9`
- Create: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/discovery.vm` — plain fallback for a site space on journal/travel/portfolio, own CSP meta like `templates/weblog/page.vm:16`
- Modify: `ui/rendering/FrontDoor.java` — new `static final String DISCOVERY = "/pressroom-ui/rendering/page/" + Weblog.SITE_SPACE_HANDLE + "/page/discovery"`; choice 3 and the broken/unpublished/locale-shaped site-page cases return `DISCOVERY` when the tier is bootstrapped and the site space exists (`getSiteSpace() != null`), else `DIRECTORY`
- Modify: `docs/dev/routing.md` (The site root), `themes-and-templates.md` (the `discovery` template exception to `isStylesheetLink`)
- Test: `FrontDoorTest`, `FrontDoorSwitchTest`, `PageRoutingTest`/`SitePageRoutingTest` (direct request to `/pressroom-ui/rendering/page/site-space/page/discovery` still 404s: FORWARD-only gate), new `DiscoveryFrontPageRenderingTest`, `ThemeCspCoverageTest` (passes unchanged), `RenderedTemplateCoverageTest`/`StrictReferences` (new template rendered under strict references)

**Interfaces:**
- Consumes: Task 5 `selectFrontPage()`.
- Produces (`DiscoveryModel`, used by Tasks 7–8):
  - `DiscoveryModel.FrontPage getFrontPage()` — nested `record FrontPage(WeblogEntryWrapper headline, List<WeblogEntryWrapper> popular, List<Card> blogs, List<WeblogEntryWrapper> different, List<WeblogWrapper> allBlogs)` with `record Card(WeblogWrapper weblog, List<WeblogEntryWrapper> topEntries)`: `FrontPageSelection` with every entry wrapped by `WeblogEntryWrapper.wrap(entry, urlStrategy)` and every weblog by `WeblogWrapper.wrap(weblog, urlStrategy)` (the wrappers escape names; titles stay stored-escaped).
  - `List<WeblogEntryWrapper> moreFromSite(WeblogWrapper current)` — empty when `current` is the site space, when its `showMoreFromSite` is false, or when nothing is eligible.
  - `String getHomeUrl()` — the site root from the URL strategy (context-path safe).

- [ ] **Step 1: Write failing tests**
  - `FrontDoorTest`: `unconfiguredForwardsToDiscovery`; `brokenFrontDoorPageShowsDiscovery` (configured slug unpublished → `DISCOVERY`); `beforeBootstrapIsDirectory`; `noSiteSpaceIsDirectory`; `configuredPublishedPageStillWins` and `configuredWeblogStillWins` (AC 12).
  - `DiscoveryFrontPageRenderingTest` (render `/` through the mapper as `SiteSpaceThemeRenderingTest` does, for the `frontpage` theme and for `journal` as the fallback): `rendersAllSections` (headline, 4 popular, 3 blog cards, 3 different, all-blogs list; markers `fd-discovery-headline`, `fd-discovery-popular`, `fd-discovery-blogs`, `fd-discovery-different`, `fd-discovery-all`); `emptySiteRendersWithoutError` (no weblogs → the empty-directory message key `siteDirectory.empty`, HTTP 200); `frontPageEscapesBlogNames` (weblog named `<script>x</script>"` → no raw `<script>x`); `entryTitlesNotDoubleEscaped` (title `A & B` → `A &amp; B` once, never `&amp;amp;`); `canonicalIsSiteRoot`; `linksCarryContextPath` (render under context `/pressroom` → every blog/entry href starts `/pressroom/`).
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement.** Section headings as message keys: `discovery.headline=Most read lately`, `discovery.popular=Popular right now`, `discovery.blogs=Explore the blogs`, `discovery.different=Something different`, `discovery.allBlogs=All blogs`, `discovery.placesBeen=Places you''ve been`. The "places" container on this page comes from Task 8's macro — call it as "the showPlacesYouveBeen macro" in a comment, never with a bare hash.
- [ ] **Step 4: Run, expect PASS; run all of `ui/rendering/` tests**
- [ ] **Step 5: Commit** — `feat(discovery): the discovery front page at /`

---

### Task 8: "More from the site" and "places you've been" on every blog

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` — new macros `showMoreFromSite $weblog` and `showPlacesYouveBeen $weblog`; `showColophon` (:2377) calls both first (so every bundled theme and forked custom theme gets them)
- Test: new `MoreFromSiteRenderingTest` (journal, travel, portfolio + a forked custom theme imported from journal), `ThemeColophonRenderingTest` (extend), `ThemeCspCoverageTest` (unchanged, passing)

**Interfaces:**
- Consumes: Task 7 `$discovery.moreFromSite($weblog)`, `$discovery.homeUrl`; Task 3 `$weblog.showMoreFromSite`.
- Produces: markup contract — `<aside class="more-from-site">` holding up to 3 links (entry title + its blog's name) and a link home; `<nav class="places-been" hidden data-places>` filled by script; recording element `<span hidden data-places-record data-handle="…" data-name="…" data-url="…">` on every non-site-space blog page (opt-out does **not** stop recording). Inline script (CSP already allows `'unsafe-inline'`): on load, read `localStorage["pressroom.places"]` (JSON array of `{handle,name,url}`) in try/catch, move the current blog to the front, cap at 5, save; render other entries into `[data-places]` with `textContent`/`href` only (never `innerHTML`); unhide only when non-empty. Minimal styles per theme via existing theme CSS hooks or a small shared block in the macro output; must not restyle any existing selector (`Routes` markers).

- [ ] **Step 1: Write failing tests** — `blockRendersOnEntryAndListPages` (all three themes + forked); `optedOutBlogRendersNoBlock` (AC 9: no `more-from-site`, recording element still present); `blockNeverShowsOwnBlog`; `noOtherBlogsRendersNothing` (single-blog site → no `<aside>`); `siteSpaceHasNoBlock` (the discovery page and site pages carry no `more-from-site`); `homeLinkCarriesContextPath`; `placesDataAttributesAreEscaped` (blog name `"><script>` → attribute-safe); `placesScriptUsesNoInnerHtml` (template text check); the cap-at-5 and newest-first behaviour (AC 11) is pinned in the browser by Task 10's `placesRemembersVisitedBlogs`; here, `placesScriptCapsAtFive` asserts the template's script carries the cap constant `5` and the storage key `pressroom.places`.
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement** (no bare-hash macro names in comments)
- [ ] **Step 4: Run, expect PASS; run all `ui/rendering/` tests (colophon/theme consistency suites included)**
- [ ] **Step 5: Commit** — `feat(discovery): more from the site and places you've been on every blog`

---

### Task 9: Admin status and "Refresh now"

**Files:**
- Modify: `ui/controllers/admin/MaintenanceController.java` — GET (:105) adds `discoveryStatus` (`getDiscoveryManager().getStatus()`); new `@PostMapping("/maintenance!refreshDiscovery.rol")` runs `refresh()` synchronously and reports the resulting status as a success or error message
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/Maintenance.jsp` — a new `div.maintenance-op` block: state, last attempt, last success, scored entries / candidates, last error (`fn:escapeXml`), and a "Refresh now" form; the existing `Routes` marker (`form[action$='/pressroom-ui/admin/maintenance.rol']`) untouched
- Modify: `ApplicationResources.properties`
- Test: `MaintenanceControllerTest`, `MaintenanceJspTest`, `MaintenanceControllerRequestBindingTest`

**Interfaces:**
- Consumes: Task 5 `DiscoveryStatus`, `refresh()`.

- [ ] **Step 1: Write failing tests** — `getExposesDiscoveryStatus`; `refreshNowCallsRefreshAndReportsOk`; `refreshNowReportsFailureMessage` (status FAILED → error message carries the escaped `lastError`); `disabledStateExplainsUmamiNotConfigured`; JSP `statusBlockRendersEachState` and `lastErrorIsEscaped`; binding test `refreshDiscoveryIsPostOnly` and admin-only (non-admin refused like the other maintenance ops).
- [ ] **Step 2: Run, expect FAIL**
- [ ] **Step 3: Implement.** Keys: `maintenance.discovery.title=Front page discovery`, `maintenance.discovery.state.DISABLED=Umami is not connected; the front page uses random picks only.`, `.NEVER=Not refreshed yet.`, `.OK=Popularity is current.`, `.FAILED=The last refresh could not read Umami; the previous popularity is still in use.`, `maintenance.discovery.lastAttempt`, `.lastSuccess`, `.counts={0} posts with traffic out of {1} eligible`, `.lastError`, `.refresh=Refresh now`, `.refreshed=Popularity refreshed.`, `.refreshFailed=Refresh failed: {0}` (controller escapes the argument, per `MessageArgumentEscapingTest`).
- [ ] **Step 4: Run, expect PASS + i18n ratchets**
- [ ] **Step 5: Commit** — `feat(discovery): popularity status and refresh on the Maintenance page`

---

### Task 10: Deploy notes, docs, browser IT

**Files:**
- Modify: `deploy/.env.example` — commented block: `PRESSROOM_ANALYTICS_JDBC_CONNECTIONURL=jdbc:postgresql://postgres:5432/umami`, `PRESSROOM_ANALYTICS_JDBC_USERNAME=` / `_PASSWORD=` (the app's own DB user works: Umami's DB lives on the same server under `POSTGRES_USER`; the connection is opened read-only; or use `grafana_ro` once its LOGIN is enabled)
- Modify: `docker_deployment.md` — "Front page popularity (Umami)" section: what the three variables do, that blank means random-only, how to verify on Maintenance
- Create: `docs/dev/discovery.md` (snapshot, generation/cache hook, seeding, eligibility, the promotion seam, the custom-domain gap); add its row to the table at the end of `CLAUDE.md`
- Modify: `docs/dev/audience-and-analytics.md` (a second reader of `analytics_traffic`: changing that view now affects the front page too)
- Create: `it-selenium/src/test/java/com/wikantik/pressroom/it/DiscoveryIT.java` (+ a `Routes` entry for `/` with marker `.fd-discovery-popular` if `/` is not already routed; check `RouteCoverageTest`)
- Test: `ProductionComposeTest` / `.env.example` tests if any pin variable lists

- [ ] **Step 1: Write the failing IT** — `frontPageShowsSections` (seeded IT weblog's posts appear); `blogPageLinksHome` (the block's home link returns to `/`); `placesRemembersVisitedBlogs` (visit two blogs, return to `/` → both listed, newest first; AC 11 browser half); `pageWorksWithStorageBlocked` (stub `localStorage` to throw via JS before load → no console error, places hidden).
- [ ] **Step 2: Run** `mvn verify -Pit -Dit.test=DiscoveryIT`; **expect PASS** (Tasks 7–8 built the behaviour, so this is browser coverage written after the fact, like a characterisation test; say so in the class javadoc). Prove each test can fail: break its marker once, watch it fail, restore.
- [ ] **Step 3: Write the docs and deploy notes**
- [ ] **Step 4: Run the whole unit suite (`mvn -pl app verify` — gates included), then `mvn verify -Pit` at `/` and with `-Dit.context.path=pressroom`; all green**
- [ ] **Step 5: Commit** — `docs(discovery): deploy notes, dev docs and browser coverage`

---

## Execution notes

- Order: 1, 2, 3, 4 are independent (worktrees allowed — pin and verify each base per CLAUDE.md). 5 needs 1, 2, 4. 6, 7, 9 need 5. 8 needs 3 and 7. 10 last.
- No push. Commits are local; the owner pushes and releases.
