# Page Trash Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deleting a page moves it to a trash it can be restored from for 7 days, instead of removing the row for good.

**Architecture:** A `TRASHED` page status plus a `trashed_at` column, mirroring the entry trash (W5). Trashing and restoring go through the tail of `savePage`, so the front-door, cache-stamp and search rules apply unchanged; the existing `removePage` becomes the permanent delete, reached only from the trash and from the scheduled purge. One ownership lookup refuses trashed pages for every existing route; a second serves the three trash routes.

**Tech Stack:** Java 25, Spring MVC, JPA/EclipseLink, PostgreSQL 18, JSP/JSTL, JUnit 5 + Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-15-page-revisions-and-trash-design.md`, Part 1 and acceptance criteria 1 to 9 and 25. This plan is the first of three; Parts 2 (revisions) and 3 (retention) get their own plans. Where criteria 6 and 8 mention revisions, this plan covers the non-revision half.

## Global Constraints

- Baseline 0.12.1 (`da0aaa1ab`). Work on `master`. Commits are local; **never push**. In an inline session, commit only if Jake has said to.
- Test-first: every test is run and seen to fail for the stated reason before the code is written.
- Never two `mvn -pl app test` at once. Every test command in this plan is run as:
  `pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || mvn -pl app test -Dtest=<Class>`
- Migration is `bin/db/migrations/V050__page_trash.sql`, idempotent (`ADD COLUMN IF NOT EXISTS`).
- Name every `@RequestParam` and `@PathVariable`.
- Confirmations are `data-confirm`. User-typed message arguments are escaped in the controller (`StringEscapeUtils.escapeHtml4`); page titles are raw and are `fn:escapeXml`'d in JSPs.
- No bare double hyphen inside an XML comment in `runtimeConfigDefs.xml`.
- New message keys go into `ApplicationResources.properties` and all seven locale bundles; the i18n ratchet sets stay empty.
- A new admin route gets its CSS marker in `it-selenium/src/test/java/com/wikantik/pressroom/it/support/Routes.java` in the same commit as its JSP.
- The API answers 404, never 403, for what the caller may not see.
- `McpInstructions.TEXT` stays under 3,000 characters (`McpInstructionsTest`).
- Rendered pages expire only through `weblog.lastModified`.
- Retention property: `page.trash.retention.days`, default **7**; -1 keeps forever; 0 purges on the next sweep.
- Restore always yields `DRAFT` and never sets the front door.
- A trashed page keeps its slug, its strand parts and its `nav_order`. No redirect is minted.

## Review Focus

1. **A page whose slug no longer passes today's slug rules** (a legacy slug since reserved, or a site page whose slug now equals a blog handle). Trash and restore must still work: they must not re-run slug validation. Test in Task 1.
2. **A page trashed between an editor's GET and its save** (two tabs, or an agent and a person). The save must be refused as "not found" and write nothing. Test in Task 2.
3. **A post whose current strand is in the trash, saved from the entry editor with the strand re-sent.** The save must succeed and leave membership alone. Test in Task 4.
4. **A retention value that is negative other than -1, or a trashed row with a null `trashed_at`.** Both purge nothing. Tests in Tasks 1 and 3.
5. **Trashing the same page twice** (double submit, or a retried API call). The UI answers "not found"; the API answers 409; neither moves `trashed_at`. Tests in Tasks 4 and 5.

## Files

| File | Change |
|---|---|
| `bin/db/migrations/V050__page_trash.sql` | create |
| `app/src/main/java/com/wikantik/pressroom/pojos/WeblogPage.java` | `TRASHED`, `trashedAt` |
| `app/src/main/resources/com/wikantik/pressroom/pojos/WeblogPage.orm.xml` | column, queries |
| `app/src/main/java/com/wikantik/pressroom/business/WeblogPageManager.java`, `business/jpa/JPAWeblogPageManagerImpl.java` | trash, restore, list, purge |
| `app/src/main/java/com/wikantik/pressroom/ui/controllers/WeblogOwnership.java`, `BaseController.java` | refuse trashed; `trashedPage` |
| `app/src/main/java/com/wikantik/pressroom/business/PageReference.java` | `absoluteUrl` status check |
| `app/src/main/java/com/wikantik/pressroom/ui/controllers/WeblogCreationRules.java` | trash-inclusive read |
| `app/src/main/java/com/wikantik/pressroom/business/runnable/TrashPurgeTask.java` | page pass |
| `app/src/main/resources/com/wikantik/pressroom/config/runtimeConfigDefs.xml` | property |
| `app/src/main/java/com/wikantik/pressroom/ui/controllers/editor/PagesController.java`, `PageEditController.java`, `EntryEditController.java` | trash UI, slug-in-trash, strand select |
| `app/src/main/java/com/wikantik/pressroom/ui/controllers/editor/PagesTrashController.java`, `app/src/main/webapp/WEB-INF/jsps/editor/PagesTrash.jsp` | create |
| `app/src/main/webapp/WEB-INF/jsps/editor/Pages.jsp` | copy, trash link |
| `app/src/main/java/com/wikantik/pressroom/ui/restapi/v1/PagesApi.java`, `ui/restapi/dto/PageDtos.java`, `ui/restapi/mcp/McpToolCatalog.java` | API and MCP |
| `docs/dev/entries.md`, `docs/dev/api.md`, `docs/api/README.md`, `docs/dev/database.md` | docs |

---

### Task 1: Schema, entity and manager

**Files:**
- Create: `bin/db/migrations/V050__page_trash.sql`
- Modify: `WeblogPage.java`, `WeblogPage.orm.xml`, `WeblogPageManager.java`, `JPAWeblogPageManagerImpl.java`, `docs/dev/database.md`
- Modify if it implements the interface: `app/src/test/java/com/wikantik/pressroom/business/MockWeblogger.java`
- Test: create `app/src/test/java/com/wikantik/pressroom/business/WeblogPageTrashTest.java` (real database; copy the fixture of `WeblogPageManagerTest`: `TestUtils.setupWeblogger/setupUser/setupWeblog`, `endSession(true)` between steps)

**Interfaces — produces:**

```java
// WeblogPage
public enum PubStatus { DRAFT, PUBLISHED, TRASHED }
public Timestamp getTrashedAt();  public void setTrashedAt(Timestamp t);

// WeblogPageManager
/** Moves the page to the trash. Throws WebloggerException if it is already TRASHED. */
void trashPage(WeblogPage page) throws WebloggerException;
/** Brings a TRASHED page back as DRAFT, trashedAt null. Throws WebloggerException if it is not TRASHED. */
void restorePage(WeblogPage page) throws WebloggerException;
/** This weblog's trashed pages, most recently trashed first. */
List<WeblogPage> getTrashedPages(Weblog weblog) throws WebloggerException;
/** Permanently removes pages trashed more than retentionDays ago; returns how many. Negative: none. 0: all trashed. */
int purgeTrash(Weblog weblog, int retentionDays) throws WebloggerException;
```

`getPages(weblog)` now excludes `TRASHED`. `getPage(id)`, `getPageBySlug` and `removePage` are unchanged.

- [ ] **Step 1: Write the migration.** One statement, with a comment in the style of `V025`:
  `ALTER TABLE pressroom_weblogpage ADD COLUMN IF NOT EXISTS trashed_at timestamp(3) with time zone;`

- [ ] **Step 2: Write the failing tests** in `WeblogPageTrashTest`. Each saves pages through the manager, ends the session, and re-reads.

  | Test | Asserts |
  |---|---|
  | `trashingSetsStatusAndStamp` | after `trashPage`: status `TRASHED`, `trashedAt` within 5 s of now |
  | `trashedPageLeavesGetPagesAndJoinsTrashList` | `getPages` lacks it, `getPublishedPages` lacks it, `getTrashedPages` holds exactly it |
  | `trashedPageIsStillFoundBySlugAndId` | `getPageBySlug` and `getPage` both return it |
  | `trashingTheFrontPageClearsTheFrontDoor` | weblog `frontPageId` set to a published page; after trash, re-read weblog has `frontPageId == null` (AC2) |
  | `trashingMovesTheWeblogStamp` | weblog `lastModified` after trash is later than before (AC1) |
  | `trashingAStrandKeepsItsParts` | strand page with two parts via `setStrandParts`; after trash each entry still has the page's id as `strandPageId` and its position (AC3) |
  | `trashingMintsNoRedirect` | `getWeblogRedirectManager()` holds no rule for `/<slug>` |
  | `trashingTwiceIsRefused` | second `trashPage` throws `WebloggerException`; `trashedAt` unchanged |
  | `restoreYieldsDraftAndNoFrontDoor` | trash a published front page, `restorePage`: status `DRAFT`, `trashedAt` null, weblog `frontPageId` still null, `navOrder` as before (AC5) |
  | `restoringALivePageIsRefused` | `restorePage` on a `DRAFT` throws `WebloggerException` |
  | `aSlugThatNoLongerPassesTheRulesStillTrashesAndRestores` | page saved with slug `legacy`, then its slug column set to `feed` by a native update; `trashPage` then `restorePage` both succeed (Review Focus 1) |
  | `purgeRemovesOnlyPagesPastRetention` | two trashed pages, `trashed_at` set by native update to 8 and 6 days ago; `purgeTrash(weblog, 7)` returns 1, the 6-day page remains (AC7) |
  | `purgeAtZeroRemovesAllTrash` | returns the trashed count; a live page survives |
  | `purgeAtAnyNegativeRemovesNothing` | `-1` and `-5` both return 0 (Review Focus 4) |
  | `purgeSkipsATrashedRowWithNoStamp` | `trashed_at` nulled by native update; `purgeTrash(weblog, 0)` returns 0 and the page remains |
  | `purgedStrandDetachesItsParts` | after purge the former parts have `strandPageId == null` and are still `PUBLISHED` (AC8) |
  | `removingAWeblogTakesItsTrashedPages` | `removeWeblog` on a weblog with one trashed page completes; no row with that page id remains (AC9) |

- [ ] **Step 3: Run and watch them fail.** `-Dtest=WeblogPageTrashTest`. Expected: compilation failure (`trashPage` undefined).

- [ ] **Step 4: Implement.**
  - `WeblogPage`: add the enum constant and the `trashedAt` field with accessors.
  - `WeblogPage.orm.xml`: map `trashedAt` to `trashed_at` (nullable). Change `WeblogPage.getByWeblog` to add `AND p.status <> com.wikantik.pressroom.pojos.WeblogPage.PubStatus.TRASHED` (or a `?2` parameter if the enum literal form is not used elsewhere in this file). Add `WeblogPage.getTrashedByWeblog`: `SELECT p FROM WeblogPage p WHERE p.weblog = ?1 AND p.status = ?2 ORDER BY p.trashedAt DESC, p.title ASC`.
  - `JPAWeblogPageManagerImpl`: split `savePage` so the slug checks stay in `savePage` and everything from the timestamp lines to the after-commit reindex becomes a private `persist(WeblogPage page, boolean recordRename)`. `trashPage` and `restorePage` set their fields and call `persist(page, false)`; they never run the slug checks.
  - `purgeTrash`: read `getTrashedPages`, keep those with a non-null `trashedAt` before `now - retentionDays` days, call `removePage` on each. Never a bulk delete.
  - If `MockWeblogger` or any test double implements `WeblogPageManager`, add the four methods there.

- [ ] **Step 5: Run.** `-Dtest=WeblogPageTrashTest`, then `-Dtest='WeblogPageManagerTest,FrontPageInvariantTest,StrandMembershipTest,EqualsContractTest'`. Expected: all pass.

- [ ] **Step 6: Document.** In `docs/dev/database.md` add V050 to the migration list in the form the neighbouring entries use.

- [ ] **Step 7: Commit.** `Page trash: TRASHED status, trashed_at (V050), trash, restore and purge in the page manager`

---

### Task 2: Who may reach a trashed page

**Files:**
- Modify: `WeblogOwnership.java` (`page`, line ~164), `BaseController.java` (after `lookupPage`, line ~514), `PageReference.java` (`absoluteUrl`, line ~74), `WeblogCreationRules.java` (`holdsSitePage`, line ~152)
- Test: `WeblogOwnershipRefusalsTest`, `PageReferenceTest`, `WeblogCreationRulesTest`, `WeblogPageRequestTest`, `PageEditControllerTest`, `StrandRenderingTest`, `SeoControllerPagesTest`, `PageNavRenderingTest`; create `app/src/test/java/com/wikantik/pressroom/business/search/lucene/ReIndexPageOperationTest.java` if none pins `isIndexable`

**Interfaces — consumes:** Task 1's `PubStatus.TRASHED`, `getTrashedPages`.
**Produces:**

```java
// WeblogOwnership
/** As before, and null for a TRASHED page: it answers like an unknown id. */
public static WeblogPage page(Weblogger weblogger, String id, Weblog weblog);
/** The page with this id when it belongs to weblog AND is TRASHED; null otherwise. */
public static WeblogPage trashedPage(Weblogger weblogger, String id, Weblog weblog);

// BaseController
public WeblogPage lookupTrashedPage(String id, HttpServletRequest request);
```

- [ ] **Step 1: Write the failing tests.**

  | Test (class) | Asserts |
  |---|---|
  | `pageRefusesATrashedPage` (`WeblogOwnershipRefusalsTest`) | `page(...)` returns null for an owned `TRASHED` page |
  | `trashedPageReturnsOnlyOwnedTrash` (same) | `trashedPage(...)` returns the page when owned and trashed; null when live, when another weblog's, when the id is blank, and when the manager throws |
  | `absoluteUrlOfATrashedPageIsEmpty` (`PageReferenceTest`) | `absoluteUrl` returns `Optional.empty()` for a `TRASHED` page and for a `DRAFT` page that exists; unchanged for `PUBLISHED` and for a slug naming no page |
  | `resolvePublishedIgnoresTrash` (same) | `Optional.empty()` |
  | `aTrashedSitePageStillBlocksTheHandle` (`WeblogCreationRulesTest`) | site space has a trashed page `travel`; creating blog `travel` is refused with the existing site-page violation |
  | `aTrashedPageIsNotServed` (`WeblogPageRequestTest`) | the page lookup returns null for a `TRASHED` page's slug |
  | `trashedIsNotIndexable` (`ReIndexPageOperationTest`) | `isIndexable` is false for `TRASHED`, true for `PUBLISHED` without `noindex` |
  | `editingATrashedPageIsNotFound` (`PageEditControllerTest`) | GET `pageEdit.rol` with a trashed id adds `pageEdit.notFound`; POST `pageEdit!save.rol` with a trashed id adds the same error and never calls `savePage` (AC6, Review Focus 2) |
  | `previewOfATrashedPageIsNotFound` (same) | `pageEdit!preview.rol` behaves as for an unknown id |
  | `partsOfATrashedStrandShowNoStrandBar` (`StrandRenderingTest`) | a published post whose strand page is `TRASHED` renders with no "Part" bar; after `restorePage` and publishing, "Part 1 of 2" is back (AC3) |
  | `trashedPageIsOutOfSitemapAndNav` (`SeoControllerPagesTest`, `PageNavRenderingTest`) | the page's URL is absent from the page sitemap and its title from the nav (AC1) |

- [ ] **Step 2: Run and watch them fail.** `-Dtest='WeblogOwnershipRefusalsTest,PageReferenceTest,WeblogCreationRulesTest,PageEditControllerTest'`. Expected: the ownership, `absoluteUrl`, creation-rules and editor tests fail; `WeblogPageRequest`, `isIndexable`, strand bar, sitemap and nav tests may already pass because those paths name `PUBLISHED`. Say so in each such test's javadoc (a characterisation test).

- [ ] **Step 3: Implement.**
  - `WeblogOwnership.page`: add the `TRASHED` refusal to the existing null condition. `trashedPage`: the same lookup with the opposite status test, same catch-and-null.
  - `BaseController.lookupTrashedPage`: one line, as `lookupPage`.
  - `PageReference.absoluteUrl`: a page that exists but is not `PUBLISHED` yields `Optional.empty()`. Check its callers (`grep -rn "absoluteUrl(" app/src/main`) render nothing for empty.
  - `WeblogCreationRules.holdsSitePage`: iterate `getPages(site)` and then `getTrashedPages(site)`.

- [ ] **Step 4: Run.** The classes in Step 1, plus `-Dtest='PageEditControllerStrandTest,PagesApiTest,SitePageRoutingTest,PageRoutingTest'`. Expected: all pass.

- [ ] **Step 5: Commit.** `Page trash: one ownership lookup refuses trashed pages; a second serves the trash`

---

### Task 3: Scheduled purge

**Files:**
- Modify: `TrashPurgeTask.java` (`runTask`, line ~187), `runtimeConfigDefs.xml` (beside `entry.trash.retention.days`, line ~157), `ApplicationResources*.properties` (8 files)
- Test: `TrashPurgeTaskTest` (mocks; follow `oneWeblogsPurgeThrowingDoesNotStopTheOthers` and `eachWeblogsPurgeIsCommittedOnItsOwn`), `GlobalConfigControllerTest` if it pins the property list

**Interfaces — consumes:** `WeblogPageManager.purgeTrash(Weblog, int)`.
**Produces:** `public static final String PAGE_RETENTION_PROPERTY = "page.trash.retention.days";` on `TrashPurgeTask`.

- [ ] **Step 1: Write the failing tests** in `TrashPurgeTaskTest`.

  | Test | Asserts |
  |---|---|
  | `thePagePassUsesItsOwnProperty` | entry property 30, page property 7: `pageManager.purgeTrash(weblog, 7)` and `entryManager.purgeTrash(weblog, 30)` |
  | `thePagePropertyIsReReadOnEverySweep` | two `runTask` calls with the property changed between them pass 7 then 3 |
  | `thePagePassIncludesTheSiteSpace` | `weblogManager.getSiteSpace()` returns a weblog absent from `getWeblogs`; `pageManager.purgeTrash(site, 7)` is called once and `entryManager.purgeTrash(site, ...)` never |
  | `theSiteSpaceIsNotSweptTwice` | when `getWeblogs` already holds the site space, the page purge runs once for it |
  | `onePagePurgeThrowingDoesNotStopTheOthers` | `doThrow` on the first weblog's page purge; the second weblog's page purge still runs (AC7) |
  | `aPagePurgeFailureDoesNotUndoThatWeblogsEntryPurge` | entry purge flushed before the page purge runs: `flush` is called between the two for one weblog |
  | `eachWeblogsPagePurgeIsCommittedOnItsOwn` | `flush` once per weblog in the page pass; `release` in a `finally` after each |
  | `aPagePropertyThatCannotBeReadPurgesNoPages` | `getIntProperty` throwing for the page property: no `pageManager.purgeTrash` call, and the entry pass still runs |

- [ ] **Step 2: Run and watch them fail.** `-Dtest=TrashPurgeTaskTest`. Expected: `PAGE_RETENTION_PROPERTY` undefined.

- [ ] **Step 3: Implement.** After the existing entry loop, a second loop over the same weblogs plus the site space (added when not already present, matched by id), each iteration: `purgeTrash`, `flush`, the same two catches, `release` in `finally`. The page property is read once per sweep inside its own try so a failure skips the page pass only. Log `purged {} trashed pages from weblog {}` at INFO when above zero.
  Add the property-def with default `7`, key `configForm.pageTrashRetentionDays`, directly after `entry.trash.retention.days`, with a comment free of double hyphens.
  Message (English; translate for the seven locales): `configForm.pageTrashRetentionDays=Days a deleted page stays in the trash (-1 keeps it until deleted by hand)`.

- [ ] **Step 4: Run.** `-Dtest='TrashPurgeTaskTest,GlobalConfigControllerTest,MessageKeyTest,MessagePlaceholderContractTest'`. Expected: all pass.

- [ ] **Step 5: Commit.** `Page trash: the purge sweep takes pages past page.trash.retention.days, site space included`

---

### Task 4: Admin screens

**Files:**
- Modify: `PagesController.java` (`remove`, line ~97; `strandPartCounts` javadoc), `Pages.jsp` (delete button, line ~137 to 160), `PageEditController.java` (before `bean.copyTo(page)`, line ~386), `EntryEditController.java` (`strandPages`, line ~834), `ApplicationResources*.properties` (8 files), `it-selenium/.../support/Routes.java`
- Create: `PagesTrashController.java`, `PagesTrash.jsp`; register the `.PagesTrash` view where `.Pages` and `.Trash` are registered (`grep -rn '\.Trash\b' app/src/main`)
- Test: `PagesControllerTest`; create `PagesTrashControllerTest` (model on `TrashControllerTest`); `PageEditControllerTest`, `EntryEditControllerTest`, `EditorJspMarkupHygieneTest` if it enumerates JSPs; extend `it-selenium/.../SitePagesIT.java`

**Interfaces — consumes:** `trashPage`, `restorePage`, `getTrashedPages`, `removePage`, `lookupPage`, `lookupTrashedPage`, `TrashPurgeTask.PAGE_RETENTION_PROPERTY`.

**Routes (all under `/pressroom-ui/authoring`, permission `WeblogPermission.POST`, `allowsSiteSpace()` true):**

| Route | Method | Does |
|---|---|---|
| `pageRemove.rol` (kept; form id `pageRemoveForm` kept) | POST `removeId` | `trashPage`; message `pages.trash.moved` |
| `pagesTrash.rol` | GET | view `.PagesTrash`; model `trashedPages`, `pageTrashRetentionDays`, `purgeDates` (page id to date, absent when retention is negative) |
| `pagesTrash!restore.rol` | POST `pageId` | `restorePage`; flash `pages.trash.restored`; redirect to `pagesTrash.rol?weblog=<handle>` |
| `pagesTrash!delete.rol` | POST `pageId` | `removePage`; flash `pages.trash.deletedForever`; same redirect |

**Copy (English; translate for the seven locales):**

```
pages.trash.moved=Page "{0}" moved to the trash.
pages.trash.restored=Page "{0}" restored as a draft. If it was the home page, choose it again in the blog settings.
pages.trash.deletedForever=Page "{0}" deleted permanently.
pages.trash.title=Page trash
pages.trash.link=Trash ({0})
pages.trash.empty=No pages in the trash.
pages.trash.purgedOn=Deleted permanently on {0}
pages.trash.keptForever=Kept until deleted by hand
pages.trash.restore=Restore
pages.trash.deleteForever=Delete forever
pages.trash.deleteForeverConfirm=Delete this page permanently? This cannot be undone
pages.trash.deleteForeverStrandConfirm=Its {0} posts stay published, outside any strand.
pageEdit.error.slugInTrash=A page in the trash still holds that address. Restore it or delete it permanently first.
strand.inTrash={0} (in the trash)
```

Reword the existing delete confirmations on `Pages.jsp` to say "Move to the trash"; the strand line (`pages.deleteStrandConfirm`) becomes: `Its {0} posts stay attached. The strand bar disappears until the page is restored and published.` Keep the front-page line.

- [ ] **Step 1: Write the failing tests.**

  | Test (class) | Asserts |
  |---|---|
  | `removeMovesThePageToTheTrash` (`PagesControllerTest`) | `trashPage` called once, `removePage` never; message key `pages.trash.moved` with the escaped slug |
  | `removingATrashedIdIsNotFound` (same) | `lookupPage` yields null for a trashed id: `pageEdit.notFound`, no manager write (Review Focus 5) |
  | `listShowsTheTrashCount` (same) | model `trashedPageCount` equals `getTrashedPages` size; a failed read leaves it 0 and the list still renders |
  | `trashViewListsTrashedPagesWithPurgeDates` (`PagesTrashControllerTest`) | retention 7: `purgeDates` maps the page to `trashedAt` + 7 days; retention -1: map empty |
  | `restoreBringsBackADraft` (same) | `restorePage` called; flash `pages.trash.restored`; redirect carries the handle |
  | `restoreOfALivePageIsNotFound` (same) | a live page's id: `pageEdit.notFound`, `restorePage` never called (AC25) |
  | `restoreOfAnotherBlogsTrashIsNotFound` (same) | same outcome |
  | `deleteForeverRemovesThePage` (same) | `removePage` called; flash `pages.trash.deletedForever` |
  | `deleteForeverOfALivePageIsNotFound` (same) | `removePage` never called |
  | `getOnAPostRouteIsRefused` (same) | GET of either POST route is a 405, as `PagesController.reorder` |
  | `aSlugHeldByATrashedPageIsAFieldError` (`PageEditControllerTest`) | new page with slug `about` while a trashed page holds it: field error `pageEdit.error.slugInTrash` on `page_bean_slug`, `savePage` never called; same for a rename; same for a blank slug derived from the title (AC4) |
  | `afterDeleteForeverTheSlugIsFree` (`WeblogPageTrashTest`) | trash, `removePage`, then a new page with that slug saves (AC4) |
  | `theCurrentStrandIsListedEvenWhenTrashed` (`EntryEditControllerTest`) | entry whose strand page is trashed: the select model holds that page, marked trashed; another entry's select does not hold it |
  | `savingAPostWithItsTrashedStrandReSentChangesNothing` (same) | save succeeds; `strandPageId` and position unchanged (Review Focus 3) |
  | `choosingATrashedStrandAfreshIsRefused` (same) | an entry not in the strand names the trashed page's id: the existing unknown-page strand error |

- [ ] **Step 2: Run and watch them fail.** `-Dtest='PagesControllerTest,PagesTrashControllerTest,PageEditControllerTest,EntryEditControllerTest'`. Expected: `PagesTrashController` undefined.

- [ ] **Step 3: Implement.**
  - `PagesController.remove`: `trashPage` in place of `removePage`. `loadPagesList` adds `trashedPageCount`.
  - `PagesTrashController extends BaseController`: `getActionName()` `"pagesTrash"`, `getDesiredMenu()` `"editor"`, title `pages.trash.title`; both POSTs resolve through `lookupTrashedPage` only. Retention read per request, not latched (see `TrashController.execute`).
  - `PagesTrash.jsp`: a table of title, slug, purge date or `pages.trash.keptForever`, and two POST forms per row; Delete forever carries `data-confirm`, with the strand sentence when the page has parts. Titles through `fn:escapeXml`. Declare every tag prefix used.
  - `Pages.jsp`: the reworded confirmations, and a link to `pagesTrash.rol` labelled `pages.trash.link` shown when the count is above zero.
  - `PageEditController`: before `bean.copyTo(page)`, compute the wanted slug (the bean's, or `WeblogPage.slugFromTitle` for a new page with a blank one), read `getPageBySlug`; a holder that is `TRASHED` and is not this page adds the field error and returns false. A lookup that throws is reported as `generic.error.check.logs` and refuses the save.
  - `EntryEditController.strandPages`: when the entry's `strandPageId` names a page not in the list, read it with `getPage`; if it belongs to this weblog and is `TRASHED`, append it. The JSP labels it with `strand.inTrash`.
  - `Routes.java`: add `pagesTrash.rol` with a marker class that `PagesTrash.jsp` carries.

- [ ] **Step 4: Run.** The classes in Step 1 plus `-Dtest='PageEditJspTest,EditorJspMarkupHygieneTest,EditorJspEscapingTest,MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest,MessageArgumentEscapingTest'`. Expected: all pass.

- [ ] **Step 5: Browser test.** In `SitePagesIT` (or a new `PageTrashIT` beside `TrashIT`): create a page, publish it, move it to the trash from the Pages screen, see its public URL answer 404, open the trash, restore it, see it listed as a draft; trash it again and delete it forever; create a page with the same slug. Run `mvn verify -Pit -Dit.test=PageTrashIT` at `/` and again with `-Dit.context.path=pressroom`. Expected: pass at both.

- [ ] **Step 6: Commit.** `Page trash: Pages screen moves to the trash; a trash view restores and deletes forever`

---

### Task 5: API and MCP

**Files:**
- Modify: `PagesApi.java` (`list` line ~57, `delete` line ~253, `requirePage` line ~268, `requireSlugAvailable` line ~285), `PageDtos.java` (`PageView`, `parseStatus` line ~223, `toView`), `McpToolCatalog.java` (line ~80 to 87), `McpInstructions.java` only if the test fails
- Modify docs: `docs/api/README.md`, `docs/dev/api.md`, `docs/dev/entries.md`
- Test: `PagesApiTest`, `PageDtosTest`, the MCP catalog and route-table tests (`grep -rln "McpToolCatalog" app/src/test`), `McpInstructionsTest`

**Interfaces — consumes:** `WeblogOwnership.page`, `WeblogOwnership.trashedPage`, `trashPage`, `restorePage`, `removePage`, `getTrashedPages`.

**Behaviour:**

| Request | Live page | Trashed page |
|---|---|---|
| `GET /pages` | listed | not listed |
| `GET /pages?status=TRASHED` | not listed | listed; any other `status` value is a 400 on field `status` |
| `GET /pages/{id}` | 200 | 404 |
| `PATCH /pages/{id}`, `PUT /pages/{id}/parts` | as today | 409 `Page is in the trash; restore it first.` |
| `DELETE /pages/{id}` | 204, trashed | 409 `Page is already in the trash.` |
| `POST /pages/{id}/restore` | 409 `Page is not trashed.` | 200, the page view, `status` `DRAFT` |
| `POST /pages/{id}/delete-forever` | 409 `Page is not trashed; trash it first.` | 204 |
| `POST /pages` or `PATCH` with `"status": "TRASHED"` | 400 on field `status` | |
| create or rename to a slug a trashed page holds | 409 on field `slug`: `A page in the trash holds slug '<slug>'. Restore it or delete it forever first.` | |

`PageView` gains `trashedAt` (ISO instant, null when live). Another weblog's id is a 404 on every route, trashed or not.

This table settles one thing the spec leaves loose: reading a single trashed page by id is a 404 (the trash list already returns full views), while a write to one is a 409 that names the trash. That is this plan's call.

- [ ] **Step 1: Write the failing tests** in `PagesApiTest`, one per table cell, named for the cell (`deleteTrashesALivePage`, `deleteOfATrashedPageIsA409`, `getOfATrashedPageIsA404`, `patchOfATrashedPageIsA409AndSavesNothing`, `partsOfATrashedPageIsA409`, `restoreReturnsADraft`, `restoreOfALivePageIsA409`, `deleteForeverRemovesATrashedPage`, `deleteForeverOfALivePageIsA409`, `listOmitsTrash`, `listWithStatusTrashedReturnsOnlyTrash`, `listWithAnotherStatusIsA400`, `writingStatusTrashedIsA400`, `aSlugHeldByTrashIsA409NamingTheTrash`, `anotherBlogsTrashedPageIsA404OnEveryRoute`). `deleteTrashesALivePage` asserts `trashPage` once and `removePage` never. In `PageDtosTest`: `parseStatusRefusesTrashed`, `viewCarriesTrashedAt`. In the MCP catalog test: `pages` has actions `restore` and `delete_forever` resolving to the two POST routes, and `delete` resolves to `DELETE`.

- [ ] **Step 2: Run and watch them fail.** `-Dtest='PagesApiTest,PageDtosTest'`. Expected: `deleteTrashesALivePage` fails because `removePage` was called.

- [ ] **Step 3: Implement.**
  - `PagesApi`: `requirePage` (live only, 404) stays as the lookup for `get`. Add `requireLivePageForWrite` (live, else 409 when `trashedPage` resolves, else 404) for `update`, `replaceParts` and `delete`; add `requireTrashedPage` (trashed, else 409 when `page` resolves, else 404) for the two new routes. `list` takes `@RequestParam(value = "status", required = false) String status`.
  - `PageDtos.parseStatus`: refuse `TRASHED` with `invalidField("status", ...)`, as `EntryDtos.parseWritableStatus` does.
  - `McpToolCatalog`: tool description becomes `A weblog's standalone pages (DRAFT or PUBLISHED); delete moves one to the trash.`; `delete` text `Move a page to the trash.`; add `action("restore", "Bring a page back from the trash, as a DRAFT.", "POST " + W + "/pages/{id}/restore")` and `action("delete_forever", "Permanently delete a page that is already in the trash.", "POST " + W + "/pages/{id}/delete-forever")`. If `McpInstructionsTest` fails, shorten existing sentences; do not drop a rule.

- [ ] **Step 4: Run.** `-Dtest='PagesApiTest,PagesApiSeoTest,PagesApiRenderTest,PageDtosTest,McpInstructionsTest'` plus the MCP catalog and route-table tests, and any OpenAPI or `FormBodyParityTest` that enumerates routes. Expected: all pass.

- [ ] **Step 5: Document.**
  - `docs/api/README.md`: the table above in the document's own form, with a clearly marked note that `DELETE /pages/{id}` no longer deletes permanently as of this release.
  - `docs/dev/api.md`: the three lookups and when each applies.
  - `docs/dev/entries.md`: in "Trash", replace the note that pages are deliberately not in the trash with how the page trash works (status, what is kept, restore to draft, the two ownership lookups, the purge property); in "Pages", note that `getPages` excludes trash and which callers read trash.

- [ ] **Step 6: Commit.** `Page trash: DELETE on a page trashes; restore and delete-forever in the API and MCP`

---

### Task 6: Whole-build verification

- [ ] **Step 1:** `pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || mvn clean install`. Expected: BUILD SUCCESS with JaCoCo floors, PMD, CPD, SpotBugs and javac lint at zero violations. A CPD hit between the entry and page loops of `TrashPurgeTask` is fixed by extracting the shared per-weblog step, not by widening an exclusion.
- [ ] **Step 2:** `bin/check-diff-coverage.sh`. Expected: changed lines at or above the threshold; a shortfall gets a test that asserts something.
- [ ] **Step 3:** `mvn verify -Pit`, then again with `-Dit.context.path=pressroom`. Expected: pass at both.
- [ ] **Step 4:** Grep the themes for anything that reads page status: `grep -rn "status" app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity | grep -i page`. Expected: no template branches on a page status in a way `TRASHED` would break; record what was checked in the commit message.
- [ ] **Step 5:** Report to Jake: what passed, the API behaviour change for the release notes, and that nothing was pushed.
