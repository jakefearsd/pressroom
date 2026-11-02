# Analytics Without Admin Steps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every blog is registered with Umami and tracked automatically, its traffic shows in Pressroom's admin, discovery ranks from the same local numbers, and Umami's admin surface leaves the public internet.

**Architecture:**
- **One seam.** A new `business/analytics` package hides Umami behind one interface, `AnalyticsService`. Its only implementation, `UmamiAnalyticsService`, speaks Umami 3.3.0's HTTP API with a dedicated non-admin account.
- **The manager.** `AnalyticsManager` runs three background channels:
  - reconcile: register or update every blog's website;
  - batch read: write snapshots into Pressroom's own tables;
  - a request-sync trigger.

  It answers every admin page, the tracker tag and discovery from those tables and an in-memory registration set. No page request reaches Umami.
- **Deploy.** Caddy narrows `/analytics/*` to the tracker endpoints, and the dashboard moves to a LAN-bound port behind zero-trust.

**Tech Stack:** Java 25, Spring Boot 4.1, JPA/EclipseLink, PostgreSQL 18 (Testcontainers), `java.net.http.HttpClient` plus `tools.jackson` (as `ListmonkClient`), Velocity, the JSP admin UI, JUnit 5, Selenium ITs, Caddy and Compose.

**Spec:** `docs/superpowers/specs/2026-11-01-analytics-without-admin-steps-design.md`. Read it first; "AC n" below means its acceptance criterion n.

## Global Constraints

- **No request path calls Umami.** That covers blog pages, admin pages, the front page and the API. Only the scheduled task, the request-sync executor and the startup reconcile do (AC 8).
- **Blog lifecycle.** Creating or saving a blog never waits on Umami. Deleting a blog never touches Umami (AC 2, AC 5).
- **Website identity.** Website ID = weblog ID, unless Task 1 proves Umami ignores a supplied `id`. Then the ID Umami returns is stored in `analytics_site.website_id`. Every caller reads the ID through `AnalyticsManager.websiteIdFor`, never `weblog.getId()` directly.
- **Periods.** Exactly `24h`, `7d`, `30d` and `90d`, plus `<N>d` for the discovery window when it isn't one of them. Each comes with the immediately preceding period of the same length. Day boundaries are in the site time zone.
- **Snapshots.** Each (weblog, period) snapshot is replaced whole. Visitor counts are never summed across days, periods or blogs (AC 12). The Global page totals views and visits only.
- **Defaults.** Refresh interval 15 minutes (`tasks.AnalyticsSyncTask.interval=15`). HTTP connect timeout 5 s, request timeout 20 s.
- **Configuration keys**, declared in `pressroom.properties`:
  - `analytics.umami.apiUrl` (blank means analytics is off; compose sets `http://umami:3000`);
  - `analytics.umami.username` (default `pressroom`);
  - `analytics.umami.password`;
  - `analytics.umami.dashboardUrl` (blank means no "Open in Umami" link);
  - the existing `analytics.umami.basePath` and `analytics.umami.scriptName`.
- **Removed.** `analytics.jdbc.*` and `PRESSROOM_ANALYTICS_JDBC_*`. `Weblog.analyticsSiteId` and `analyticsShareUrl` and their columns. The `UMAMI_DOMAIN` mode.
- **The credential** never appears in logs, exceptions, admin pages or API output (AC 15).
- **The tracker.** Same-origin `$config.analyticsBasePath/$config.analyticsScriptName`, with:
  - `data-website-id`;
  - `data-host-url="$config.analyticsBasePath"`;
  - `data-domains=<the blog's host>`;
  - `data-performance` (attribute name and value confirmed in Task 1).

  Theme CSPs stay unchanged. Replay and heatmaps stay off; their Caddy lines are present but commented.
- **The Traffic tabs**, in this order, with these `tab` values: Posts `posts`, Sources `sources`, Places `places`, Devices `devices`, Events `events`, Campaigns `campaigns`, Speed `speed`.
- **Repo rules.**
  - TDD: watch each test fail first.
  - `@Lazy Weblogger` in constructors. Name every `@RequestParam`. SLF4J throwable last.
  - No bare `--` in XML comments. The i18n ratchets stay empty.
  - No context-path assumptions. `fn:escapeXml` on author-controlled EL. Controllers escape message arguments.
  - Only one build at a time per worktree (CLAUDE.md busy check).
  - Each schema change is a new idempotent `V<NNN>` migration.
  - `Routes` markers change in the same commit as the page they pin.

## Review Focus

1. **A cached page rendered before its blog was registered.** It must gain the tracker tag once registration lands, not stay tagless for the page cache's lifetime: rendered pages expire only through `weblog.lastModified` (CLAUDE.md hazard). Test: Task 4 `registrationExpiresTheBlogsRenderedPages`.
2. **Umami up but answering 401 after a restart wiped its sessions, or 5xx mid-read.** The client must log in again once and retry. A failed read keeps the previous snapshot and marks it *stale*. Tests: Task 2 `reloginsOnceOn401`, Task 5 `failedReadKeepsPreviousSnapshotAndMarksStale`.
3. **Two syncs at once:** the scheduled tick, "Sync now" and a blog save in the same minute. They must coalesce into one run, never two concurrent writers of one snapshot. Test: Task 4 `concurrentRequestsCoalesceIntoOneRun`.
4. **A path that is not an entry**, such as the blog home, a category or tag page, a query string, a trailing slash, a custom-domain path without the handle, or a context path. Entries map correctly; other paths count toward totals and show raw. Test: Task 5 `pathMappingCoversEveryUrlShape`.
5. **A blog title or host carrying markup or quotes, and Umami's domain regex rejecting `localhost`.** Registration still works in dev and the admin pages stay escaped. Tests: Task 4 `devHostStillRegisters`, Task 9 `blogNameIsEscapedEverywhere`.

---

## File map

New, under `app/src/main/java/com/wikantik/pressroom/`:
- **`business/analytics/`**
  - Contracts: `AnalyticsService.java`, `AnalyticsException.java`, `SiteSpec.java`, `Period.java`, `AnalyticsReport.java`.
  - Umami client: `UmamiAnalyticsService.java`, `UmamiClient.java`.
  - Manager: `AnalyticsManager.java`, `JPAAnalyticsManagerImpl.java`, `AnalyticsStatus.java`, `BlogTraffic.java`, `SiteTraffic.java`, `TrafficState.java`, `EntryPaths.java`.
  - Entities: `AnalyticsSite.java`, `AnalyticsSummary.java`, `AnalyticsPathViews.java`, `AnalyticsBreakdown.java`.
- **`business/runnable/AnalyticsSyncTask.java`**
- **`business/discovery/CachedTrafficSource.java`**, which replaces `UmamiTrafficSource.java`.
- **`ui/rendering/model/AnalyticsModel.java`** (`$analytics`)
- **`ui/controllers/editor/TrafficController.java`** and **`ui/controllers/admin/SiteTrafficController.java`**

New, elsewhere:
- Migrations: `bin/db/migrations/V043__analytics_cache.sql` and `V044__drop_weblog_analytics_fields.sql`.
- Deploy: `deploy/analytics/umami-pressroom-account.sql`.
- JSPs: `WEB-INF/jsps/editor/Traffic.jsp`, `WEB-INF/jsps/admin/SiteTraffic.jsp`, and the shared tab and chart fragment `WEB-INF/jsps/editor/TrafficBody.jsp`.
- Docs: `docs/dev/umami-api.md`.
- Tests: `app/src/test/java/com/wikantik/pressroom/testing/UmamiContainer.java`, and the new `AnalyticsIT.java` alongside the rewritten `AnalyticsInjectionIT.java`.

Modified:
- **Business tier:** `Weblogger`, `WebloggerImpl`, `JPAWebloggerImpl`, `WebloggerBeanConfig`, `JPAWeblogManagerImpl` (the request-sync hook), `JPADiscoveryManagerImpl` (score by entry ID), `TrafficSource`, `TrafficRow`.
- **Persistence and config:** `persistence.xml` / `orm.xml`, `pressroom.properties`.
- **Templates:** `WEB-INF/velocity/weblog.vm` (the tracker macro).
- **Admin pages:**
  - `MaintenanceController` + `Maintenance.jsp`;
  - `EntriesController` + `Entries.jsp`;
  - `editor-menu.xml` and `admin-menu.xml`;
  - `PressroomViewResolver`;
  - `ApplicationResources.properties`.
- **Removed fields:** `WeblogConfig.jsp`, `WeblogConfigBean`, `WeblogSettingsRules`, `AdminDtos`, `WeblogWrapper`, `Weblog` + `Weblog.orm.xml`.
- **Deploy:** `deploy/caddy/Caddyfile`, `docker-compose.prod.yml`, `deploy/.env.example`, `deploy/analytics-views.sh`, `deploy/deploy.sh`.
- **Tests:** `ProductionComposeTest`, `it-selenium/.../Routes.java`.
- **Docs:** `docker_deployment.md`, `docs/dev/audience-and-analytics.md`, `docs/dev/discovery.md`, `docs/api/README.md`, `docs/legal/blog-privacy-policy.md`.

Deleted: `UmamiTrafficSource.java`, `UmamiTrafficSourceTest.java`, `AnalyticsJdbcEnvOverrideTest.java`. Keep `UmamiTestDatabase` (still used by `UmamiViewScriptTest`).

---

### Task 1: Prove Umami 3.3.0's API, and the dedicated account

**Files:**
- Create: `app/src/test/java/com/wikantik/pressroom/testing/UmamiContainer.java`. It is a Testcontainers helper that starts the **same digest-pinned image** as `docker-compose.prod.yml` (read the digest from that file, so the two can't drift) against a scratch Postgres database, and exposes `baseUrl()`, `loginAdmin()` (the image's default admin, used only in this test) and `jdbc()`.
- Create: `deploy/analytics/umami-pressroom-account.sql`. It is idempotent and creates `pgcrypto` and the Umami user named by psql variable `:username`, with role `user` and a bcrypt (`crypt(:'pw', gen_salt('bf'))`) password. On re-run it updates the password.
- Create: `app/src/test/java/com/wikantik/pressroom/business/analytics/UmamiApiContractTest.java`
- Create: `docs/dev/umami-api.md`. It records the confirmed endpoints, parameters, response shapes and the ID finding, and is linked from `docs/dev/audience-and-analytics.md`.

**Interfaces:**
- Produces: `UmamiContainer` (used by Tasks 2 and 5 contract checks), the account SQL (Task 12), and the endpoint table in `docs/dev/umami-api.md` (Tasks 2 and 5 build against it).

- [ ] **Step 1: Write the contract tests** (they fail until the SQL file exists and the expectations match):
  - `accountSqlCreatesALoginThatCanCreateWebsites` applies the SQL. `POST /api/auth/login` as `pressroom` returns a token, and that token can `POST /api/websites`.
  - `accountSqlIsIdempotentAndRotatesPassword`
  - `suppliedIdIsHonoured` creates a website with `id` = a fresh UUID and asserts `GET /api/websites/{id}` returns 200 with that ID. **If this fails against the real image, the fallback is chosen:** record it in `docs/dev/umami-api.md` and in the task report, keep the test as `suppliedIdIsIgnored` pinning the observed behaviour, and Task 3's `analytics_site.website_id` carries Umami's ID.
  - `adminSeesPressroomOwnedWebsites` (spec: the owner still sees every blog).
  - `nonAdminCannotCreateUsers` (least privilege).
  - `localhostDomainIsRejectedOrAccepted` pins whatever the domain regex does with `localhost` and `localhost:8083`, for Task 4.
  - `readEndpointsAnswer`: stats, pageviews (`unit=day`, `timezone`), metrics for each type used by the seven tabs, `reports/performance` and `reports/utm`. Each returns 200 for an empty website, and the test records the shapes.
  - `trackerPerformanceAttribute` reads `script.js` and asserts the attribute name that turns on performance collection.
- [ ] **Step 2: Run** `mvn -pl app test -Dtest=UmamiApiContractTest`. **Expect FAIL** (no SQL file yet).
- [ ] **Step 3: Write the SQL and adjust the expectations to the observed truth.** Never weaken an assertion to pass: a surprise is a finding. Write it up in `docs/dev/umami-api.md`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `test(analytics): pin Umami 3.3.0's API contract and the pressroom account`

---

### Task 2: The seam and the Umami client

**Files:**
- Create: `business/analytics/AnalyticsService.java`, `AnalyticsException.java`, `SiteSpec.java`, `Period.java`, `AnalyticsReport.java`, `UmamiClient.java`, `UmamiAnalyticsService.java`
- Modify: `pressroom.properties`, adding the four `analytics.umami.*` keys with comments that point at `docker_deployment.md`.
- Test: `UmamiClientTest` (stubbed with `com.sun.net.httpserver.HttpServer`, the `ListmonkClientTest` pattern) and `UmamiAnalyticsServiceContractTest` (against `UmamiContainer`).

**Interfaces:**
- Produces:
  - `record SiteSpec(String websiteId, String name, String domain)`. `websiteId` may be null only on the fallback path.
  - `enum`-like `record Period(String key, Duration length)` with `static Period of(String key)` (accepts `24h` and `<N>d` for 1 ≤ N ≤ 365, rejects anything else) and `Instant startBefore(Instant end)`.
  - `record AnalyticsReport(Instant asOf, Summary current, Summary previous, List<SeriesPoint> series, List<PathCount> paths, Map<String, List<KeyCount>> breakdowns)`. It carries nested records `Summary(long visitors, long visits, long views, long bounces, long totalTimeSeconds)`, `SeriesPoint(LocalDate day, long visitors, long views)` (hour buckets for `24h`), `PathCount(String path, long views, long visitors)` (visitors may be −1 when the API can't report it per path) and `KeyCount(String key, long value)`. The breakdown keys are exactly `sources, places, devices, events, campaigns, speed`.
  - `interface AnalyticsService { boolean isConfigured(); String ensureSite(SiteSpec spec) throws AnalyticsException; AnalyticsReport read(String websiteId, Period period, ZoneId zone) throws AnalyticsException; boolean ping(); }`. `ensureSite` creates the website, or updates its name and domain, and returns the website ID.
  - `static AnalyticsService UmamiAnalyticsService.fromConfig()`. A blank `apiUrl` returns an unconfigured service whose methods throw `AnalyticsException("analytics not configured")` and whose `isConfigured()` is false.
  - `AnalyticsException extends Exception`. Its message never includes the password or token.

- [ ] **Step 1: Write failing tests:**
  - `logsInOnceAndReusesToken`;
  - `reloginsOnceOn401` (a second 401 throws);
  - `ensureSiteCreatesThenUpdates`: a 404 on GET leads to POST create with `id`, `name` and `domain`; an existing website with a different name leads to POST update;
  - `ensureSiteTreatsAlreadyExistsAsSuccess`;
  - `timeoutsAreSet` (a package-private accessor gives 5 s and 20 s);
  - `hangingServerTimesOut` (a stub that never answers throws `AnalyticsException` within about 21 s; use a short test timeout via a package-private constructor);
  - `passwordNeverInExceptionOrLog` (a 500 whose body echoes the request; assert the message and captured log lines don't contain the password);
  - `unconfiguredServiceIsInert`;
  - `periodParsing` (`24h`, `7d`, `365d` accepted; `0d`, `366d`, `1w` and blank rejected);
  - `readMapsEveryTabFromTheDocumentedEndpoints`, using JSON fixtures copied from Task 1's recorded shapes.

  Contract: `ensureSiteAndReadAgainstRealUmami`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Use the token header Umami documents (`Authorization: Bearer`). Use millisecond `startAt`/`endAt` and the `timezone` parameter. The previous period comes from a second stats call over `[start − length, start)`. Map the documented metric types to the six breakdown keys. Sources are referrers, then UTM source where present. Places are countries. Devices are device, browser and OS, prefixed `device:`, `browser:` and `os:`. Events are custom event names. Campaigns come from `reports/utm`. Speed comes from `reports/performance` p75 per metric.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `feat(analytics): an AnalyticsService seam and its Umami client`

---

### Task 3: Cache tables

**Files:**
- Create: `bin/db/migrations/V043__analytics_cache.sql`. It is idempotent (`CREATE TABLE IF NOT EXISTS`) and creates:
  - `analytics_site(weblog_id varchar(48) PRIMARY KEY REFERENCES weblog(id) ON DELETE CASCADE, website_id varchar(48) NOT NULL, synced_name varchar(255), synced_domain varchar(255), registered_at timestamptz, last_attempt timestamptz, last_error text)`. `website_id` stays NULL-free; until registration succeeds there is simply no row with a `registered_at`.
  - `analytics_summary(weblog_id, period varchar(8), as_of timestamptz NOT NULL, visitors, visits, views, bounces, total_time, prev_visitors, prev_visits, prev_views, prev_bounces, prev_total_time` (all `bigint NOT NULL`)`, PRIMARY KEY (weblog_id, period))`.
  - `analytics_path_views(weblog_id, period, path varchar(1024), entry_id varchar(48) NULL, views bigint NOT NULL, visitors bigint NOT NULL, PRIMARY KEY (weblog_id, period, path))`.
  - `analytics_breakdown(weblog_id, period, kind varchar(16), payload text NOT NULL, PRIMARY KEY (weblog_id, period, kind))`. The payload is JSON: a series, or a list of key/count pairs.
  - All cascade on weblog delete.
  - It also redefines `analytics_weblog_sites` (`CREATE OR REPLACE VIEW`, same three columns in the same order and types) as `SELECT w.handle, s.website_id, w.custom_domain FROM weblog w LEFT JOIN analytics_site s ON s.weblog_id = w.id WHERE s.website_id IS NOT NULL OR w.custom_domain IS NOT NULL`. Grafana keeps working, and the columns can be dropped later.
- Create: entities `AnalyticsSite`, `AnalyticsSummary`, `AnalyticsPathViews`, `AnalyticsBreakdown`, mapped in orm.xml and listed in `persistence.xml` the way `Weblog` is (none imports a business type).
- Test: a migration test in the existing harness style (`SqlScriptRunnerMigrationTest` runs the real chain), plus `AnalyticsEntitiesTest`.

- [ ] **Step 1: Write failing tests:**
  - `migrationIsIdempotent` (apply twice);
  - `weblogSitesViewKeepsItsColumns` (names and types unchanged from V027);
  - `deletingAWeblogCascadesItsAnalyticsRows`;
  - `entitiesRoundTrip`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS** (the whole migration chain test included).
- [ ] **Step 5: Commit:** `feat(analytics): local cache tables and the Grafana view on them`

---

### Task 4: AnalyticsManager, registration and sync triggering

**Files:**
- Create: `business/analytics/AnalyticsManager.java`, `JPAAnalyticsManagerImpl.java`, `AnalyticsStatus.java`, `TrafficState.java`
- Modify: `Weblogger` (`AnalyticsManager getAnalyticsManager()`), `WebloggerImpl`, `JPAWebloggerImpl`, `WebloggerBeanConfig` (beans `analyticsService()` from `UmamiAnalyticsService.fromConfig()` and `analyticsManager(@Lazy Weblogger, AnalyticsService)`), and `JPAWeblogManagerImpl` (after `addWeblog` / `saveWeblog` commit, call `getAnalyticsManager().requestSync()`; nothing on `removeWeblog`).
- Test: `JPAAnalyticsManagerImplTest` (Testcontainers DB plus a fake `AnalyticsService`) and `WeblogLifecycleAnalyticsTest`.

**Interfaces:**
- Produces:
  - `interface AnalyticsManager`, with these methods:
    - `void requestSync()` is non-blocking. It coalesces: at most one run in flight plus one queued.
    - `void sync()` reconciles, then reads (Task 5 fills in the read). The task and the executor call it, never a request thread.
    - `void reconcile()`
    - `boolean isRegistered(String weblogId)` and `String websiteIdFor(String weblogId)` come from an in-memory map loaded at startup and refreshed after each reconcile, with no DB hit per page.
    - `AnalyticsStatus getStatus()`
  - `record AnalyticsStatus(boolean configured, Boolean reachable, int registered, int total, List<SiteFailure> failures, Instant lastRead, String lastReadError)`, with `record SiteFailure(String handle, String error, Instant at)`.
  - `enum TrafficState { NOT_CONFIGURED, NO_DATA, STALE, OK }`.
- Behaviour:
  - Reconcile builds `SiteSpec(websiteId = existing row's or weblog ID, name = weblog.getName(), domain = custom domain, else the host of the site's absolute URL)` for every **active** weblog. It calls `ensureSite` only when nothing is registered yet or the name or domain differ from the synced values. It records success or failure per blog.
  - When registration state changes, it advances that weblog's `lastModified` through the weblog manager, so rendered pages re-render with the tag.
  - At startup (bean init) it schedules one `requestSync()`. That makes it the backfill.

- [ ] **Step 1: Write failing tests:**
  - `newBlogIsRegisteredOnNextSync` (AC 1);
  - `createBlogWithUmamiDownSucceedsFastAndRegistersLater`: the fake throws, then recovers; `addWeblog` returns in under 200 ms; the failure is visible in `getStatus()` (AC 2);
  - `backfillRegistersEveryExistingBlog` (AC 3);
  - `renameAndDomainChangeUpdateTheSite` (AC 4);
  - `deletingABlogNeverCallsUmami` (AC 5);
  - `unchangedBlogsAreNotResent`;
  - `concurrentRequestsCoalesceIntoOneRun` (Review Focus 3);
  - `registrationExpiresTheBlogsRenderedPages` (Review Focus 1: `lastModified` advances exactly when the state flips);
  - `devHostStillRegisters`, per Task 1's `localhost` finding. If Umami rejects it, use a stable placeholder domain such as `<handle>.localhost.invalid`, documented, so dev registration works;
  - `unconfiguredManagerNeverCallsTheService` (AC 14);
  - `requestSyncNeverRunsOnTheCallingThread`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** The executor is a single-thread daemon from `ThreadManager` or the weblogger's background pool, as discovery uses (`JPADiscoveryManagerImpl.inBackgroundOf`).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `feat(analytics): blogs register themselves with Umami in the background`

---

### Task 5: The batch reader and the scheduled task

**Files:**
- Modify: `JPAAnalyticsManagerImpl`. `sync()` now reads after reconciling.
- Create: `business/analytics/EntryPaths.java` (path to entry ID), `BlogTraffic.java`, `SiteTraffic.java`, `business/runnable/AnalyticsSyncTask.java` (modelled on `PopularityRefreshTask`, leased, fixed interval from `tasks.AnalyticsSyncTask.interval`).
- Modify: `pressroom.properties` (add `AnalyticsSyncTask` to `tasks.enabled`; `class`, `startTime=immediate`, `interval=15`, `leaseTime=10`).
- Test: `AnalyticsReadTest`, `EntryPathsTest`, `AnalyticsSyncTaskTest`.

**Interfaces:**
- Consumes: Task 2 `AnalyticsService.read`, `Period`; Task 3 entities; Task 4 manager.
- Produces, on `AnalyticsManager`:
  - `BlogTraffic getBlogTraffic(String weblogId, String periodKey)`
  - `SiteTraffic getSiteTraffic(String periodKey)`
  - `Map<String, Long> entryViews(String weblogId, String periodKey)`
  - `Map<String, Long> entryViewsAllBlogs(int windowDays)` (for discovery)
  - `List<String> periodKeys()` (`24h, 7d, 30d, 90d`, plus the discovery window's key when different)
- Records:
  - `record BlogTraffic(TrafficState state, Instant asOf, Summary current, Summary previous, List<SeriesPoint> series, List<PostRow> posts, Map<String, List<KeyCount>> breakdowns, String lastError)`, with `PostRow(String entryId, String title, String path, long views, long visitors, double trend)`. `entryId` and `title` are null for non-entry paths.
  - `record SiteTraffic(TrafficState state, Instant asOf, List<BlogRow> blogs, long totalViews, long totalVisits, List<PostRow> topPosts, List<KeyCount> topSources)`, with `BlogRow(String weblogId, String handle, String name, long visitors, long views, double change, String topPostTitle)`.
- Behaviour:
  - For each registered blog and each period key, the reader replaces that (weblog, period)'s summary, path views and breakdown rows in one transaction, stamped `as_of`.
  - A failure leaves all of them untouched and records `lastReadError`.
  - `STALE` means the last attempt for that blog failed after a success. `NO_DATA` means registered but no snapshot yet, or a snapshot with zero views and registration under 24 h old. `NOT_CONFIGURED` comes from the service.

- [ ] **Step 1: Write failing tests:**
  - `readReplacesSnapshotWhole` (an old path absent from the new read disappears);
  - `failedReadKeepsPreviousSnapshotAndMarksStale` (AC 11, Review Focus 2);
  - `visitorsAreNeverSummed` (AC 12: the site total has no visitor figure, and a period's visitors equal the service's number even when the daily series sums higher);
  - `stateDistinguishesNoDataStaleNotConfiguredAndZero` (AC 9 states);
  - `pathMappingCoversEveryUrlShape` (Review Focus 4). Cases:
    - `/sandiego/entry/tide-pools` and `/sandiego/entry/tide-pools/` map to the entry;
    - `/pressroom/sandiego/entry/tide-pools` maps under a context path;
    - `/entry/tide-pools` maps on a custom domain;
    - `/sandiego/`, `/sandiego/category/x` and `/sandiego/tags/y` don't map but count in totals;
    - an unknown anchor doesn't map;
  - `discoveryWindowIsReadWhenNotAStandardPeriod`;
  - `taskRunsSyncUnderLease`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** `EntryPaths` derives the shapes from `URLStrategy` and the weblog's custom domain rather than hard-coding `/entry/`. If `URLStrategy` can't produce a pattern, document the shapes it supports in `docs/dev/umami-api.md`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `feat(analytics): batch-read traffic into local snapshots every 15 minutes`

---

### Task 6: The tracker tag, automatic

**Files:**
- Create: `ui/rendering/model/AnalyticsModel.java` (`$analytics`), registered in `pressroom.properties` on every page-model list `DiscoveryModel` is on (lines near 433 and 453).
- Modify: `WEB-INF/velocity/weblog.vm`. The tracker macro keeps its name and its single `$weblog` parameter, so every theme and every forked template keeps working. It renders only when `$analytics.websiteId($weblog)` is non-empty, and emits the attributes listed in Global Constraints. The macro's comment describes the macro without naming it with `#` (CLAUDE.md hazard).
- Test: `AnalyticsTrackingRenderingTest` (render through each bundled theme: travel, journal, portfolio, frontpage), plus the existing CSP pin tests unchanged.

**Interfaces:**
- Produces: `AnalyticsModel`, with `String websiteId(WeblogWrapper weblog)` (null when unregistered or not configured) and `String trackedHost(WeblogWrapper weblog)`. It reads only `AnalyticsManager.isRegistered` / `websiteIdFor` (in-memory).

- [ ] **Step 1: Write failing tests:**
  - `registeredBlogCarriesFullTracker` (AC 6: ID, host URL, `data-domains`, performance attribute);
  - `unregisteredBlogCarriesNone`;
  - `notConfiguredCarriesNone`;
  - `everyBundledThemeEmitsTheTag`;
  - `cspUnchanged` (assert the existing pinned policies byte for byte);
  - `customDomainBlogTracksItsOwnHost`;
  - `trackerAttributesAreEscaped`;
  - `renderingNeverCallsAnalyticsService` (AC 8, for blog pages and the front page: the `AnalyticsService` is a fake that fails the test if called, and a hanging-stub configuration still renders within the normal time).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `feat(analytics): every registered blog carries the tracker automatically`

---

### Task 7: Discovery reads the local cache

**Files:**
- Create: `business/discovery/CachedTrafficSource.java`
- Modify: `TrafficSource` (`boolean isConfigured(); Map<String, Long> viewsByEntry(int windowDays) throws WebloggerException;`), `TrafficRow` (delete if unused), `JPADiscoveryManagerImpl.score(...)` (score by entry ID; drop the `getAnalyticsSiteId` lookup), `WebloggerBeanConfig.trafficSource()`, and `pressroom.properties` (remove `analytics.jdbc.*`).
- Delete: `UmamiTrafficSource.java`, `UmamiTrafficSourceTest.java`, `AnalyticsJdbcEnvOverrideTest.java`.
- Test: `CachedTrafficSourceTest`, updated `JPADiscoveryManagerImpl*Test`.

**Interfaces:**
- Consumes: Task 5 `entryViewsAllBlogs(int)`.

- [ ] **Step 1: Write failing tests:**
  - `scoresComeFromTheCache` (AC 13);
  - `windowMatchesDiscoverySetting`;
  - `unconfiguredAnalyticsMeansRandomOnly` (AC 14);
  - `refreshNeverCallsAnalyticsService` (a fake that fails if called).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS** (the whole discovery test package).
- [ ] **Step 5: Commit:** `refactor(discovery): popularity from the analytics cache, not Umami's database`

---

### Task 8: Remove the per-blog analytics fields

**Files:**
- Create: `bin/db/migrations/V044__drop_weblog_analytics_fields.sql`. It is idempotent: `ALTER TABLE weblog DROP COLUMN IF EXISTS analytics_site_id, DROP COLUMN IF EXISTS analytics_share_url`. The view no longer depends on them after V043.
- Modify:
  - `Weblog` + `Weblog.orm.xml`, `WeblogWrapper`, `WeblogConfigBean`, `WeblogSettingsRules`, `WeblogConfig.jsp`;
  - `AdminDtos` (the record components, field lists, setters and clear cases);
  - `ApplicationResources.properties` (delete the six `websiteSettings.analytics*` keys);
  - `docs/api/README.md`.
- Modify: `it-selenium/.../AnalyticsInjectionIT.java`. Rewrite it for the IT app, where analytics is unconfigured: the Settings page has **no** analytics fields and blog pages carry **no** tracker tag. The tagged case is covered by Task 6's rendering tests.
- Test: `WeblogSettingsRulesTest`, `AdminDtosTest`, the API v1 weblog tests, `EditorJspEscapingTest` if it lists the fields.

- [ ] **Step 1: Write failing tests:**
  - `settingsFormHasNoAnalyticsFields`;
  - `apiNoLongerReturnsAnalyticsFields`;
  - `apiPatchWithAnalyticsFieldIsRefused`, with whatever the API already does for an unknown field (AC 16);
  - `columnsAreGoneAfterMigration`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Grep `app/src/main/webapp/themes` and `WEB-INF/velocity` for `analyticsSiteId` / `analyticsShareUrl` (Velocity prints a deleted member as text).
- [ ] **Step 4: Run, expect PASS**, plus the i18n ratchets.
- [ ] **Step 5: Commit:** `feat(analytics): no analytics fields on blog settings or the API`

---

### Task 9: Blog › Traffic (layout B)

**Files:**
- Create:
  - `ui/controllers/editor/TrafficController.java`: `@RequestMapping("/pressroom-ui/authoring")`, `@GetMapping("/traffic.rol")`, `@RequestParam(name = "period", required = false, defaultValue = "30d")` and `@RequestParam(name = "tab", required = false, defaultValue = "posts")`. `requiredWeblogPermissionActions()` returns `WeblogPermission.ADMIN`. Menu `editor`, action `traffic`, view `.Traffic`.
  - `WEB-INF/jsps/editor/Traffic.jsp` with the root `<div id="trafficPage">`.
  - `WEB-INF/jsps/editor/TrafficBody.jsp`: headline tiles, an inline-SVG daily bar chart rendered server-side (no chart library, no inline script), tabs, the freshness line, and the "Open in Umami" link only when `analytics.umami.dashboardUrl` is set.
- Modify: `PressroomViewResolver` (`.Traffic`, the `.Submissions` shape), `editor-menu.xml` (`<menu-item action="traffic" name="tabbedmenu.weblog.traffic" weblogPerms="admin"/>`), `ApplicationResources.properties` (all copy), and `Routes.java` (`/pressroom-ui/authoring/traffic.rol`, `Role.EDITOR`, `WEBLOG`, marker `#trafficPage`).
- Test: `TrafficControllerTest`, `TrafficJspTest`, `TrafficControllerRequestBindingTest`.

**Interfaces:**
- Consumes: Task 5 `getBlogTraffic`, `periodKeys()`.

- [ ] **Step 1: Write failing tests:**
  - `showsFiveHeadlinesWithChange` (AC 9);
  - `rendersEachTab`, covering all seven `tab` values;
  - `unknownTabFallsBackToPosts`;
  - `unknownPeriodFallsBackTo30d`;
  - `postsTabLinksEntriesAndShowsRawPaths`;
  - `stateMessagesAreDistinct` (`NOT_CONFIGURED` / `NO_DATA` / `STALE` with age / zero);
  - `asOfLineShown`;
  - `umamiLinkOnlyWhenConfigured`;
  - `requiresWeblogAdmin` (a post-only member is refused; AC 10 half);
  - `blogNameIsEscapedEverywhere` (Review Focus 5);
  - `pageNeverCallsAnalyticsService` (AC 8).
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Copy is plain and short. The columns a tab shows are exactly the fields its `KeyCount`/`PostRow` carry. A field Task 1 found unsupported is absent, not zero.
- [ ] **Step 4: Run, expect PASS**, plus the i18n ratchets.
- [ ] **Step 5: Commit:** `feat(analytics): a Traffic page for every blog`

---

### Task 10: Global › Traffic, and views on the entries list

**Files:**
- Create: `ui/controllers/admin/SiteTrafficController.java` (`/pressroom-ui/admin/siteTraffic.rol`, global admin, `period` param as Task 9) and `WEB-INF/jsps/admin/SiteTraffic.jsp` (root `<div id="siteTrafficPage">`).
- Modify: `admin-menu.xml`, `PressroomViewResolver` (`.SiteTraffic`), `ApplicationResources.properties`, `Routes.java` (`Role.ADMIN`, marker `#siteTrafficPage`), and `EntriesController` + `Entries.jsp` (a "Views (30d)" column from `entryViews(weblogId, "30d")`, shown only when analytics is configured; the `#delete-entry-modal` marker untouched).
- Test: `SiteTrafficControllerTest`, `SiteTrafficJspTest`, `EntriesViewsColumnTest`.

- [ ] **Step 1: Write failing tests:**
  - `listsEveryBlogWithTotalsForViewsAndVisitsOnly` (AC 10: no visitor total; the hover explanation present);
  - `rowsLinkToEachBlogsTrafficPage`;
  - `globalAdminOnly`;
  - `entriesListShowsViews`;
  - `entriesListHidesColumnWhenUnconfigured`;
  - `neitherPageCallsAnalyticsService`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `feat(analytics): site-wide traffic and per-post views in the entries list`

---

### Task 11: Maintenance › Analytics

**Files:**
- Modify: `MaintenanceController` (GET adds `analyticsStatus`; new `@PostMapping("/maintenance!syncAnalytics.rol")` calls `requestSync()` and reports "Sync requested", without waiting), `Maintenance.jsp` (a new `div.maintenance-op` block with `<h2 class="section-head">`, as the discovery block), and `ApplicationResources.properties`.
- Test: `MaintenanceControllerTest`, `MaintenanceJspTest`, `MaintenanceControllerRequestBindingTest`.

- [ ] **Step 1: Write failing tests:**
  - `showsRegisteredCountAndNamesFailures` (AC 2's visibility half; errors `fn:escapeXml`'d);
  - `showsLastReadAndReachability`;
  - `notConfiguredExplainsItself`;
  - `syncNowIsPostOnlyAdminOnlyAndDoesNotWait`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS**, plus the i18n ratchets.
- [ ] **Step 5: Commit:** `feat(analytics): registration and read status on Maintenance`

---

### Task 12: Deploy: public allowlist, internal dashboard, the account

**Files:**
- Modify `deploy/caddy/Caddyfile`:
  - Replace both `handle_path /analytics/*` blocks with an allowlist: `@tracker path /analytics/script.js` (honouring `UMAMI_SCRIPT_NAME`) and `@send { path /analytics/api/send; method POST }` go to `umami:3000` with the prefix stripped. Everything else under `/analytics/` gets `respond 404`.
  - The replay lines are commented, with a one-line note.
  - Replace the `{$UMAMI_DOMAIN}` site with a fixed `:8081` dashboard site.
  - Update the block comments.
- Modify `docker-compose.prod.yml`:
  - The dashboard port becomes `"${UMAMI_CONSOLE_BIND:-127.0.0.1}:${UMAMI_CONSOLE_PORT:-8081}:8081"`, and `UMAMI_DOMAIN` is removed.
  - The app gets `PRESSROOM_ANALYTICS_UMAMI_APIURL: ${PRESSROOM_ANALYTICS_UMAMI_APIURL:-http://umami:3000}` and `PRESSROOM_ANALYTICS_UMAMI_PASSWORD: ${UMAMI_PRESSROOM_PASSWORD:-}`.
  - `analytics-views` gets `UMAMI_PRESSROOM_PASSWORD`.
- Modify `deploy/analytics-views.sh`: after the views, apply `umami-pressroom-account.sql` when `UMAMI_PRESSROOM_PASSWORD` is set. Without it, it logs that analytics registration is off. It never echoes the value.
- Modify `deploy/deploy.sh`: when `.env` has no `UMAMI_PRESSROOM_PASSWORD`, append one from `openssl rand -hex 24`, mode unchanged, never printed.
- Modify `deploy/.env.example`: remove `UMAMI_DOMAIN` and `PRESSROOM_ANALYTICS_JDBC_*`; document `UMAMI_CONSOLE_BIND` (LAN address for zero-trust), `UMAMI_PRESSROOM_PASSWORD` (generated) and `PRESSROOM_ANALYTICS_UMAMI_DASHBOARDURL`.
- Test: `ProductionComposeTest` (new pins: no `UMAMI_DOMAIN` anywhere; the dashboard bind defaults to loopback; the app has the API URL; nothing depends on `analytics-views`), `CaddyfileTest` if one exists (else add the assertions to `ProductionComposeTest`), and an `AnalyticsViewsScriptTest` that runs the account step against Testcontainers Postgres plus `UmamiContainer` and asserts idempotence.

- [ ] **Step 1: Write failing tests:**
  - `caddyAllowsOnlyTrackerPaths` (parse the Caddyfile text: both site blocks carry the same allowlist and no bare `reverse_proxy umami` outside it; AC 7, AC 19);
  - `noUmamiDomainAnywhere`;
  - `dashboardBindDefaultsToLoopback`;
  - `deployGeneratesPasswordOnceAndNeverPrints` (run `deploy.sh`'s helper function in a temp dir);
  - `accountStepIsIdempotent`.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** Then run the real stack locally (`./pressroom` or `docker compose` with a local `.env`) and check:
  - `curl` of `/analytics/script.js` gives 200;
  - `POST /analytics/api/send` gives 200 or 400 from Umami, not 404;
  - `/analytics/login`, `/analytics/api/auth/login` and `/analytics/api/websites` give 404 (AC 7);
  - the dashboard answers on `:8081`.
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit:** `feat(deploy): Umami's public face is the tracker only; the dashboard is internal`

---

### Task 13: Docs, privacy text, browser coverage, full verify

**Files:**
- Modify `docker_deployment.md`: rewrite the Umami sections. Analytics is automatic, the dashboard is reached through zero-trust (`UMAMI_CONSOLE_BIND` plus a Cloudflare Access hostname), and the upgrade notes for 0.7.0 are:
  - remove `PRESSROOM_ANALYTICS_JDBC_*`;
  - `UMAMI_DOMAIN` is gone;
  - the password is generated on deploy.
- Modify `docs/dev/audience-and-analytics.md` (the seam, the three channels, snapshots selected not summed, the cache-expiry hook) and `docs/dev/discovery.md` (the traffic source is now the cache).
- Modify `docs/legal/blog-privacy-policy.md`. **Draft** the analytics paragraph from what Task 1 found Umami 3.3.0 stores, and flag it in the task report for the owner's approval (AC 18). Don't publish the live page.
- Create `it-selenium/.../AnalyticsIT.java`, run against the IT app with analytics unconfigured, since the IT harness has no Umami:
  - `trafficPageSaysNotConfigured`;
  - `siteTrafficPageSaysNotConfigured`;
  - `blogPagesCarryNoTracker`;
  - `maintenanceShowsAnalyticsBlock`.
- Test: `RouteCoverageTest` stays green with the two new routes.

- [ ] **Step 1: Write the IT and run it.** Expect PASS, since Tasks 9 to 11 built the behaviour. This is browser coverage written after the fact, so say so in the class javadoc. Prove each test can fail: break its marker once, watch it fail, then restore it.
- [ ] **Step 2: Write the docs and the privacy draft.**
- [ ] **Step 3: Run the whole unit suite with gates** (`mvn -pl app verify`: JaCoCo floors, diff coverage, PMD, CPD, SpotBugs, `-Xlint`). Then run `mvn verify -Pit` at `/` and with `-Dit.context.path=pressroom`. Everything must be green.
- [ ] **Step 4: Commit:** `docs(analytics): deploy notes, dev docs, privacy draft and browser coverage`

---

## Execution notes

- **Order.**
  - Task 1 first: it decides the website-ID path and pins the shapes everything else builds on.
  - Then 2 and 3, which are independent (worktrees allowed; pin and verify bases per CLAUDE.md).
  - 4 needs 2 and 3. 5 needs 4. 6, 7, 9, 10 and 11 need 5.
  - 8 needs 6 and 7, because the tracker and discovery must stop reading the fields before they're dropped.
  - 12 needs 1, and is otherwise independent of 4 to 11.
  - 13 is last.
- **Release** is the owner's call: version 0.7.0 (schema change, removed settings and removed env vars).
- **After a release is deployed** (outside this plan, each step with the owner's approval):
  - set `UMAMI_CONSOLE_BIND` and the Cloudflare Access hostname;
  - set `PRESSROOM_ANALYTICS_UMAMI_DASHBOARDURL`;
  - remove the old `PRESSROOM_ANALYTICS_JDBC_*` lines;
  - publish the approved privacy wording;
  - confirm all seven docker1 blogs show as registered on Maintenance.
- **No push.** Commits are local; the owner pushes and releases.
