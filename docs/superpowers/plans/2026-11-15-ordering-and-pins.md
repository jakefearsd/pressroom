# Blog Ordering and Pins (Release 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each blog can list its posts newest first (default) or oldest first, and can pin up to 3 posts to the top of its home listing, from the admin UI, `/api/v1` and MCP.

**Architecture:** One migration adds `weblog.entry_order` and `weblogentry.pinned`. All three blog listing pagers share `AbstractWeblogEntriesPager.queryEntries`, which takes its sort direction from the blog; the day map's key order follows it. The latest pager additionally exposes the pinned posts (home page 1 only) and leaves them out of the regular home listing; the shared `showWeblogEntriesPager` macro renders them first through each theme's `_day` template with a "Pinned" mark. A business-tier `EntryPins` rule owns the limit for both the editor and the API.

**Tech Stack:** Java 25, Spring Boot 4.1 / Spring MVC, JPA/EclipseLink (`*.orm.xml`), PostgreSQL 18, Velocity themes, JSP admin, JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-15-ordering-strands-pages-design.md`, section "Release 1: ordering and pins" (acceptance criteria 1-8). Read it with this plan.

## Global Constraints

- Every new column defaults to today's behaviour; existing blogs render the same after migration. No backfill.
- `weblog.entry_order varchar(10) NOT NULL DEFAULT 'NEWEST'`, values `NEWEST` | `OLDEST`. `weblogentry.pinned boolean NOT NULL DEFAULT false`.
- `pinnedtomain` (site-wide pin) is left exactly as it is: no read, write or label change.
- Pin limit: **3** per blog, counting pinned posts that are not trashed (drafts and scheduled included).
- Pinning needs `WeblogPermission.POST` on the blog (the level publishing needs).
- Feeds, site front page, site-wide lists, site search, sitemaps: unaffected by order and pins.
- Post Older/Newer links are unchanged in both orders (spec criterion 3; existing permalink tests must keep passing untouched).
- Every change that alters what a reader sees bumps `weblog.lastModified` (render cache).
- Project gates (CLAUDE.md): TDD (watch each test fail for the expected reason), JaCoCo floors, diff coverage ~90%, PMD/CPD/SpotBugs zero, `-Xlint:all` zero warnings, name every `@RequestParam`, `{}`-free PMD ruleset, never two `mvn -pl app test` at once in one tree.
- Run one class: `pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || mvn -o -q -pl app test -Dtest=<Class>` (if BUSY, wait for the other build; never start a second).
- Commit per task (local only). **Never push.**

## Review Focus

1. **A pinned post scheduled for the future** must not appear in the pinned rows before its time (the regular listing already caps at `endDate = now`). Test in Task 3.
2. **Home page 2+** shows no pinned post and neither skips nor repeats a regular post (offsets count regular posts only). Test in Task 3.
3. **An author with only `EDIT_DRAFT` saving a post a publisher pinned** must not unpin it (the checkbox is not rendered for them, so an absent field must mean "keep", not "false"). Test in Task 5.
4. **Restoring a trashed pinned post when 3 others are pinned** would make 4: restore clears its pin in that case (plan's call, keeps the spec's "never past 3"). Test in Task 5.
5. **An unknown or lower-case `entryOrder`** (`"oldest"`, `"RANDOM"`, a hand-edited row): API accepts `NEWEST`/`OLDEST` case-insensitively and refuses anything else with a 400 field error; a stored unknown value reads as newest first. Test in Tasks 1 and 4.

---

### Task 1: Schema and model

**Files:**
- Create: `bin/db/migrations/V047__entry_order_and_pins.sql`
- Create: `app/src/main/java/com/wikantik/pressroom/pojos/EntryOrder.java`
- Modify: `app/src/main/java/com/wikantik/pressroom/pojos/Weblog.java`, `.../pojos/Weblog.orm.xml` (resources), `.../pojos/wrapper/WeblogWrapper.java`
- Modify: `app/src/main/java/com/wikantik/pressroom/pojos/WeblogEntry.java` (field, accessor, copy constructor beside `pinnedToMain` at ~line 177), `.../pojos/WeblogEntry.orm.xml`, `.../pojos/wrapper/WeblogEntryWrapper.java`
- Test: `app/src/test/java/com/wikantik/pressroom/business/startup/EntryOrderAndPinsMigrationTest.java` (pattern: `ShareRowMigrationTest`), `app/src/test/java/com/wikantik/pressroom/pojos/EntryOrderTest.java`

**Interfaces:**
- Produces: `enum EntryOrder { NEWEST, OLDEST; static EntryOrder of(String stored) /* null, blank, unknown -> NEWEST; case-insensitive */; static Optional<EntryOrder> parse(String input) /* case-insensitive; empty for anything else */ }`
- Produces: `Weblog.getEntryOrder(): String`, `setEntryOrder(String)`, `Weblog.entryOrder(): EntryOrder` (= `EntryOrder.of(getEntryOrder())`); `WeblogWrapper.getEntryOrder(): String`.
- Produces: `WeblogEntry.getPinned(): Boolean` (never null; default `FALSE`), `setPinned(Boolean)`; `WeblogEntryWrapper.getPinned(): Boolean`.

- [ ] **Step 1: Write the failing tests**
  - `EntryOrderTest`: `of(null)`, `of("")`, `of("RANDOM")` -> `NEWEST`; `of("oldest")` -> `OLDEST`; `parse("Oldest")` -> `Optional.of(OLDEST)`; `parse("random")` and `parse(null)` -> empty.
  - `EntryOrderAndPinsMigrationTest`: after migrations, a weblog row inserted without the column reads `entry_order = 'NEWEST'`; a weblogentry row inserted without it reads `pinned = false`; running V047 twice succeeds (idempotent: `ADD COLUMN IF NOT EXISTS`).
  - Extend the entity round-trip test that covers `shareRowEnabled` / `commentsClosed` (find with `grep -rln shareRowEnabled app/src/test`) so a saved weblog with `setEntryOrder("OLDEST")` and an entry with `setPinned(true)` read back the same, and `new WeblogEntry(other)` copies `pinned`.
- [ ] **Step 2: Run them; expect compile failure (`EntryOrder` missing) then migration failures (column missing).**
- [ ] **Step 3: Implement.** Migration comment in the style of V046 says what each column is and that defaults keep every blog as it was. Map both columns in the `orm.xml` files beside `share_row_enabled` / `pinnedtomain`.
- [ ] **Step 4: Run the three test classes; expect PASS.** Also run `EqualsContractTest` (it covers entity fields).
- [ ] **Step 5: Commit** — `Ordering and pins: schema (V047), EntryOrder, Weblog.entryOrder, WeblogEntry.pinned`.

---

### Task 2: Listings follow the blog's order

**Files:**
- Modify: `app/src/main/java/com/wikantik/pressroom/ui/rendering/pagers/AbstractWeblogEntriesPager.java` (`queryEntries`, ~line 262; new helper)
- Modify: `.../pagers/WeblogEntriesLatestPager.java:72`, `.../pagers/WeblogEntriesDayPager.java:124`, `.../pagers/WeblogEntriesMonthPager.java:121` (each `new TreeMap<>(Collections.reverseOrder())`)
- Modify: `app/src/main/java/com/wikantik/pressroom/business/jpa/JPAWeblogEntryManagerImpl.java:1237` (`getWeblogEntryObjectMap`)
- Test: `app/src/test/java/com/wikantik/pressroom/ui/rendering/pagers/WeblogEntriesLatestPagerTest.java`, `.../WeblogEntriesArchivePagerTest.java`, `app/src/test/java/com/wikantik/pressroom/business/WeblogEntryManagerQueryTest.java`

**Interfaces:**
- Consumes: `Weblog.entryOrder()` (Task 1).
- Produces: `protected <V> Map<Date, V> newDayMap()` in `AbstractWeblogEntriesPager`: reverse key order for `NEWEST`, natural for `OLDEST`. `queryEntries` sets `wesc.setSortOrder(weblog.entryOrder() == OLDEST ? ASCENDING : DESCENDING)`.
- `getWeblogEntryObjectMap` orders its keys by `wesc.getSortOrder()` (ascending -> natural, otherwise reverse) instead of always reverse.

- [ ] **Step 1: Write the failing tests** (fixtures via `EntriesPagerTestSupport`; three published posts on three different days, A oldest, C newest)
  - `LatestPagerTest.anOldestFirstBlogListsItsOldestPostFirst`: weblog `OLDEST` -> day keys ascending and the flattened order is A, B, C.
  - `LatestPagerTest.aBlogWithNoOrderSetListsNewestFirst`: default -> C, B, A (guard; passes before and after).
  - `LatestPagerTest.anOldestFirstCategoryListingRunsOldestFirst` and `...TagListing...`: same with `catName` / `tags` set.
  - `ArchivePagerTest.anOldestFirstMonthArchiveRunsOldestFirst`: month pager, two posts in one month on different days -> ascending.
  - `WeblogEntryManagerQueryTest.theObjectMapFollowsTheCriteriaSortOrder`: `ASCENDING` criteria -> keys ascending; default -> descending.
  - Paging guard: `anOldestFirstBlogPagesForward`: with page length 2, page 0 is A, B and `hasMoreEntries()`; page 1 is C.
- [ ] **Step 2: Run; expect the oldest-first tests to FAIL with newest-first order, guards PASS.**
- [ ] **Step 3: Implement** as in Interfaces. No change to feeds (`FeedModel.FeedEntriesPager` extends `WeblogEntriesListPager`, untouched) or `getNextPrevEntries`.
- [ ] **Step 4: Run the three test classes plus `WeblogEntriesPagerCharacterisationTest` and `WeblogEntriesPermalinkPagerTest`; expect PASS with the last two unchanged.** Add `feedStaysNewestFirstOnAnOldestFirstBlog` to the feed model test (find with `grep -rln FeedEntriesPager app/src/test`), asserting C, B, A.
- [ ] **Step 5: Commit** — `Ordering: blog listings follow weblog.entry_order; feeds stay newest first`.

---

### Task 3: Pinned posts lead the home listing

**Files:**
- Modify: `app/src/main/java/com/wikantik/pressroom/pojos/WeblogEntrySearchCriteria.java` (new filter), `.../business/jpa/JPAWeblogEntryManagerImpl.java` (where-clause, beside the category condition ~line 799)
- Modify: `.../ui/rendering/pagers/AbstractWeblogEntriesPager.java`, `.../pagers/WeblogEntriesLatestPager.java`
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (macro `showWeblogEntriesPager`, ~line 1966)
- Modify: `app/src/main/webapp/themes/{journal,portfolio,travel}/_day.vm` (list branch) and each theme's custom CSS (one small rule for the mark)
- Modify: `app/src/main/resources/ApplicationResources.properties` and the 7 locale bundles (`_de _es _fr _ja _ko _ru _zh_CN`)
- Test: `WeblogEntriesLatestPagerTest`, `WeblogEntryCriteriaTest`, a new `app/src/test/java/com/wikantik/pressroom/ui/rendering/servlets/PinnedRowsRenderingTest.java` (pattern: `AfterEntryRenderingTest`), `ReaderMessagesTranslatedTest` (unchanged; must pass)

**Interfaces:**
- Consumes: `WeblogEntry.getPinned()` (Task 1); `newDayMap()` and sort order (Task 2).
- Produces: `WeblogEntrySearchCriteria.setPinned(Boolean)` / `getPinned()`: null = any, `TRUE` = only pinned, `FALSE` = only unpinned.
- Produces: `public List<WeblogEntryWrapper> getPinnedEntries()` on `AbstractWeblogEntriesPager`, returning an empty list; overridden in `WeblogEntriesLatestPager` to return, **only when** `catName == null`, tags empty, and `page == 0`, the blog's published pinned posts with `pubTime <= now`, in the blog's order, at most `EntryPins.MAX_PER_WEBLOG` (3; Task 5 defines the constant: until then use a local constant and switch in Task 5). On the same condition minus `page == 0` (that is, the home listing on every page), the regular query sets `wesc.setPinned(FALSE)`.
- Message key: `entryMeta.pinned=Pinned` (the `entryMeta.` prefix puts it under `ReaderMessagesTranslatedTest`: translate in all 7 locales).

- [ ] **Step 1: Write the failing tests**
  - `WeblogEntryCriteriaTest.thePinnedFilterKeepsOnlyPinnedOrOnlyUnpinned`: TRUE / FALSE / null over one pinned and one unpinned published post.
  - `LatestPagerTest.aPinnedPostLeadsPageOneOfTheHomeListing`: posts A, B, C, with A pinned -> `getPinnedEntries()` = [A]; `getEntries()` flattened = [C, B] (no A).
  - `LatestPagerTest.homePageTwoShowsNoPinnedPostAndSkipsNone` (Review Focus 2): 5 unpinned + 1 pinned, length 2 -> page 1's pinned list empty; pages 0-2 together list the 5 unpinned exactly once.
  - `LatestPagerTest.aCategoryListingShowsAPinnedPostInItsPlace`: `catName` set -> `getPinnedEntries()` empty, A appears in `getEntries()`.
  - `LatestPagerTest.aPinnedPostScheduledForLaterIsNotShownYet` (Review Focus 1): A pinned, status PUBLISHED, `pubTime` tomorrow -> not in `getPinnedEntries()`.
  - `LatestPagerTest.aPinnedDraftIsNotShown` (criterion 6): A pinned DRAFT -> not shown anywhere.
  - `LatestPagerTest.pinsFollowTheBlogsOrder`: two pinned on an `OLDEST` blog -> older first.
  - `PinnedRowsRenderingTest.theJournalHomeShowsAPinnedPostFirstWithItsMark`: render a journal blog home with one pinned post -> the pinned title appears before the newest unpinned title and the rendered HTML contains the "Pinned" mark once; repeat for portfolio and travel (parameterized).
  - `PinnedRowsRenderingTest.theFeedListsAPinnedPostInItsDatePlace`: feed order unaffected.
- [ ] **Step 2: Run; expect FAIL (no `setPinned`, no `getPinnedEntries`, no mark).**
- [ ] **Step 3: Implement.** Macro: before the day loop, if `$pager.pinnedEntries` is non-empty, `#set($entries = $pager.pinnedEntries)`, `#set($pinnedRows = true)`, parse the `_day` template once, then `#set($pinnedRows = false)` (Velocity keeps a `#set` value, so reset it explicitly). Each theme's `_day.vm` list branch shows `$text.get("entryMeta.pinned")` in a small element next to the date when `$pinnedRows`. Do not write the macro's name with a `#` in any comment (CLAUDE.md hazard).
- [ ] **Step 4: Run the four test classes and `ReaderMessagesTranslatedTest`, `MessageKeyTest`; expect PASS.**
- [ ] **Step 5: Commit** — `Pins: pinned posts lead page 1 of a blog's home listing, marked Pinned`.

---

### Task 4: Blog order setting (admin UI, API, MCP)

**Files:**
- Modify: `app/src/main/java/com/wikantik/pressroom/ui/controllers/editor/WeblogConfigBean.java`, `.../editor/WeblogConfigController.java`, `app/src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp`
- Modify: `app/src/main/java/com/wikantik/pressroom/ui/restapi/dto/AdminDtos.java` (weblog view and patch records, beside `shareRowEnabled`), `.../restapi/v1/WeblogsApi.java` (patch -> bean, beside `shareRowEnabled`)
- Modify: `ApplicationResources*.properties` (8 bundles, as the share-row commit `0eef07056` did)
- Modify docs: `docs/api/README.md` (weblog fields), `docs/dev/entries.md` (new "Order and pins" section, written here and extended in Task 5)
- Test: `WeblogConfigBeanTest`, `WeblogConfigControllerTest`, `app/src/test/java/com/wikantik/pressroom/ui/restapi/v1/WeblogsApiTest.java`, a JSP test beside `WeblogConfigBusinessJspTest`

**Interfaces:**
- Consumes: `EntryOrder`, `Weblog.get/setEntryOrder` (Task 1).
- Produces: `WeblogConfigBean.getEntryOrder(): String` / `setEntryOrder(String)`, copied to and from `Weblog` like `shareRowEnabled`. Weblog view record component `String entryOrder` (`@Schema(description = "NEWEST (the default) or OLDEST: the order of this blog's listings. Feeds stay newest first.")`); patch component `String entryOrder` (null = absent).
- Message keys: `weblogSettings.entryOrder=Order posts`, `weblogSettings.entryOrder.newest=Newest first`, `weblogSettings.entryOrder.oldest=Oldest first`, `weblogSettings.entryOrder.help=Applies to this blog's home, category, tag and archive lists. Feeds always list newest first.`, `weblogSettings.error.entryOrder=Choose newest first or oldest first.`

- [ ] **Step 1: Write the failing tests**
  - `WeblogConfigBeanTest.entryOrderCopiesBothWays`.
  - `WeblogConfigControllerTest.savingOldestFirstStoresItAndBumpsLastModified` (criterion 7): POST the settings form with `bean.entryOrder=OLDEST` -> weblog reads `OLDEST` and `lastModified` is later than before.
  - `WeblogConfigControllerTest.anUnknownEntryOrderIsRefused`: `bean.entryOrder=RANDOM` -> field error, stored value unchanged.
  - JSP test: `WeblogConfig.jsp` has a select named `bean.entryOrder` with options `NEWEST` and `OLDEST`.
  - `WeblogsApiTest.entryOrderRoundTrips` (criterion 8): PATCH `{"entryOrder":"oldest"}` -> 200, GET shows `"OLDEST"`.
  - `WeblogsApiTest.anUnknownEntryOrderIs400` (Review Focus 5): PATCH `{"entryOrder":"RANDOM"}` -> 400 with a field error on `entryOrder`; stored value unchanged.
- [ ] **Step 2: Run; expect FAIL (no field).**
- [ ] **Step 3: Implement.** Validation uses `EntryOrder.parse`; store `name()`. The MCP `weblogs.update` action passes the body through to `PATCH /v1/weblogs/{handle}`: no new tool, and no change to `McpToolCatalog` text unless a field list there names weblog fields (check; keep within the instructions budget, about 2,300 of 3,000 characters).
- [ ] **Step 4: Run the four test classes plus `MessageKeyTest`, `MessagePlaceholderContractTest`, `QualityGatePomTest`; expect PASS.**
- [ ] **Step 5: Commit** — `Ordering: blog setting Order posts (newest/oldest), API entryOrder`.

---

### Task 5: Pinning (rule, editor, API, entries list)

**Files:**
- Create: `app/src/main/java/com/wikantik/pressroom/business/EntryPins.java`
- Modify: `app/src/main/java/com/wikantik/pressroom/business/WeblogEntryManager.java` + `.../jpa/JPAWeblogEntryManagerImpl.java` (count query; `restoreWeblogEntry` ~line 562)
- Modify: `.../ui/controllers/editor/EntryBean.java`, `.../editor/EntryEditController.java` (beside the `pinnedToMain` block ~line 726), `app/src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp` (beside the `pinnedToMain` checkbox ~line 295), `.../jsps/editor/Entries.jsp` (pin marker)
- Modify: `.../ui/restapi/dto/EntryDtos.java` (`EntryWrite`, the entry view, `applyWrite`), `.../restapi/v1/EntriesWriteApi.java` (`checkWrite` ~line 516)
- Modify: `ApplicationResources*.properties` (8 bundles); docs `docs/api/README.md` (entry `pinned`), `docs/dev/entries.md` (finish "Order and pins")
- Test: new `app/src/test/java/com/wikantik/pressroom/business/EntryPinsTest.java`, `EntryEditControllerTest`, `EntryBeanTest`, `EntriesWriteApiControllerTest`, `WeblogEntryTrashOperationsTest`, `ui/EntryEditJspTest`

**Interfaces:**
- Consumes: `WeblogEntry.get/setPinned` (Task 1). Task 3's pager switches to `EntryPins.MAX_PER_WEBLOG`.
- Produces: `WeblogEntryManager.getPinnedEntryCount(Weblog weblog): long` (pinned, status not `TRASHED`).
- Produces: `final class EntryPins { static final int MAX_PER_WEBLOG = 3; static boolean wouldExceed(boolean storedPinned, boolean requestedPinned, long pinnedInWeblog) /* requested && !stored && count >= MAX */ }`.
- Produces: `EntryWrite` component `Boolean pinned` (`@Schema(description = "True to pin this entry to the top of its blog's home listing; at most 3 per blog. Needs publish rights.")`); entry view component `Boolean pinned`.
- Message keys: `weblogEdit.pinned=Pin to the top of this blog`, `weblogEdit.error.pinLimit=This blog already has {0} pinned posts, the most it allows. Unpin one first.`, `weblogEntryQuery.pinned=Pinned` (entries list marker; reuse an existing list-label prefix if `Entries.jsp` uses another).

- [ ] **Step 1: Write the failing tests**
  - `EntryPinsTest`: `wouldExceed(false, true, 3)` true; `(false, true, 2)` false; `(true, true, 3)` false (already pinned, re-saving); `(true, false, 3)` false.
  - `WeblogEntryManagerQueryTest.thePinnedCountLeavesOutTrashedPosts`: pinned published + pinned draft + pinned trashed -> 2.
  - `EntryEditControllerTest.aPublisherPinsAPost`: user with `POST` saves with `bean.pinned=true` -> stored true, `weblog.lastModified` bumped.
  - `EntryEditControllerTest.aFourthPinIsRefused` (criterion 5): three pinned -> saving a fourth with `bean.pinned=true` shows the `weblogEdit.error.pinLimit` field error with `3`, nothing saved, the three stay pinned.
  - `EntryEditControllerTest.aDraftOnlyAuthorKeepsAPinTheyCannotSee` (Review Focus 3): stored pinned, user with only `EDIT_DRAFT` saves without the field -> still pinned.
  - `EntryEditControllerTest.aPublisherUnpins`: stored pinned, publisher saves without the field (unchecked) -> false.
  - `EntryEditJspTest.thePinCheckboxShowsOnlyToPublishers`: checkbox `bean.pinned` rendered for `POST`, absent for `EDIT_DRAFT`.
  - `EntriesWriteApiControllerTest.pinnedRoundTrips` (criterion 8): PATCH `{"pinned":true}` -> 200 and GET shows `"pinned":true`.
  - `EntriesWriteApiControllerTest.aFourthPinIs400`: field error on `pinned`; entry unchanged.
  - `EntriesWriteApiControllerTest.aDraftOnlyTokenCannotPin`: refused the same way the API refuses `status: PUBLISHED` for that caller (match that test's expected status).
  - `WeblogEntryTrashOperationsTest.restoringAPinnedPostWhenThreeArePinnedUnpinsIt` (Review Focus 4); and `...restoringAPinnedPostUnderTheLimitKeepsItsPin`.
- [ ] **Step 2: Run; expect FAIL.**
- [ ] **Step 3: Implement.** The editor applies `bean.pinned` only for a user with `POST` on the action weblog (otherwise the stored value stands), then checks `EntryPins.wouldExceed` against `getPinnedEntryCount`; the API does the same check in `checkWrite` as `errors.check("pinned", ...)` so a refused write changes nothing. Saving an entry already bumps `lastModified`; verify rather than add. `restoreWeblogEntry` clears `pinned` when the count of other pinned posts is already 3. `Entries.jsp` shows the marker for pinned rows. Switch Task 3's local constant to `EntryPins.MAX_PER_WEBLOG`.
- [ ] **Step 4: Run the six test classes plus `FormBodyParityTest`, `MessageKeyTest`, `MessageArgumentEscapingTest`; expect PASS.** If `FormBodyParityTest` lists form/API field pairs, add `pinned`.
- [ ] **Step 5: Commit** — `Pins: pin up to 3 posts per blog from the editor and the API`.

---

### Task 6: Whole-release verification

- [ ] **Step 1:** `pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || mvn -o -pl app verify -DskipITs` -> `BUILD SUCCESS`, `Tests run: ... Failures: 0, Errors: 0`, SpotBugs `BugInstance size is 0`.
- [ ] **Step 2:** `mvn -o -pl app jacoco:report && bin/check-diff-coverage.sh HEAD~5` -> every changed file at or above ~90%.
- [ ] **Step 3:** `mvn verify -Pit` (browser ITs) -> pass; this release touches listings every theme renders.
- [ ] **Step 4:** Report to the top-level session: commits made, test totals, anything skipped. Do not push or release.
