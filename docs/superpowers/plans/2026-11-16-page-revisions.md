# Page Revisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every save that changes a page's title or content leaves a revision that can be listed, diffed and restored from the editor, the API and MCP.

**Architecture:** A dedicated table and entity mirroring `weblogentry_revision` and `WeblogEntryRevision`. The page snapshots its title and content at load; `savePage` records the displaced content after the save commits, in its own transaction, so a refused revision never costs the save. Restore goes through the ordinary save, so it leaves a revision of what it replaced. Pages also gain the entries' optional stale-write guard (`contentHash` / `expectedContentHash`).

**Tech Stack:** Java 25, Spring MVC, JPA/EclipseLink, PostgreSQL 18, JSP/JSTL, JUnit 5 + Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-15-page-revisions-and-trash-design.md`, Part 2 and acceptance criteria 10 to 14, plus the revision halves of 6 and 8. Part 1 (page trash) shipped as `5a4cceb30`. Part 3 (retention: coalescing, age, site ceiling, kept revisions, the sweep and its panel) is a later plan; this one ships the per-page count only.

## Global Constraints

- Work on `master`. Commit locally at the end of the plan when the build is green; **push only if Jake has said so for this part**.
- Test-first; each test is seen to fail for the stated reason.
- Never two `mvn -pl app test` at once. Test commands run as
  `pgrep -f "[s]urefirebooter.*$(git rev-parse --show-toplevel)/app/target/" >/dev/null && echo BUSY || mvn -q -pl app test -Dtest=<Class>`
- Migration `bin/db/migrations/V051__page_revisions.sql`, idempotent, with the `GRANT ... TO :app_user` line V010 carries.
- Table `pressroom_weblogpage_revision`: `id varchar(48) PK`, `pageid varchar(48) NOT NULL` FK to `pressroom_weblogpage(id) ON DELETE CASCADE`, `created timestamp(6) with time zone NOT NULL`, `creator varchar(255)`, `title varchar(255)`, `content text`, `note varchar(255)`, `kept boolean NOT NULL DEFAULT false`; index `(pageid, created DESC)`. `kept` is mapped but nothing sets it until Part 3.
- A revision holds title and content only. Never slug, status, nav or SEO fields.
- Runtime property `page.revisions.retention`, default **50**: keep the n newest per page; 0 records none; negative keeps all.
- `eclipselink.cache.size.WeblogPageRevision=100` in `pressroom.properties`.
- Revision note limit is the entries': `ColumnLimits.REVISION_NOTE`.
- Name every `@RequestParam`/`@PathVariable`. Confirmations are `data-confirm`. `revision.note` is author-typed and is printed through `fn:escapeXml`.
- The API answers 404 for another page's revision id, `against` included.
- `McpInstructions.TEXT` stays under 3,000 characters.
- New message keys in `ApplicationResources.properties` only (the locale bundles fall back).

## Review Focus

1. **A revision row the database refuses** (for example a title longer than the column) must not fail the page save. Test in Task 1.
2. **Two saves of one page in the same session** (the API creates then patches, the editor saves twice): the second save's revision must hold the first save's content, not the content loaded originally. Test in Task 1.
3. **Restoring a revision whose title or content is null or empty.** The save's own rules apply; a refused restore changes nothing and is reported. Tests in Tasks 3 and 4.
4. **A revision id belonging to another page of the same weblog**, on read, diff (`against`) and restore. 404 everywhere. Tests in Tasks 2 to 4.
5. **A page deleted for good, or its weblog removed, while it has revisions.** No row and no foreign-key failure. Test in Task 1.

## Files

| File | Change |
|---|---|
| `bin/db/migrations/V051__page_revisions.sql` | create |
| `app/src/main/java/com/wikantik/pressroom/pojos/WeblogPageRevision.java`, `app/src/main/resources/com/wikantik/pressroom/pojos/WeblogPageRevision.orm.xml` | create |
| `app/src/main/resources/META-INF/persistence.xml`, `.../config/pressroom.properties`, `.../config/runtimeConfigDefs.xml`, `ApplicationResources.properties` | register, cache size, property, labels |
| `pojos/WeblogPage.java`, `WeblogPage.orm.xml` | content snapshot at load |
| `business/WeblogPageManager.java`, `business/jpa/JPAWeblogPageManagerImpl.java`, `JPAWeblogManagerImpl.java` | record, read, clean up, lock |
| `ui/controllers/WeblogOwnership.java` | `pageRevision` |
| `ui/restapi/v1/PagesApi.java`, `ui/restapi/dto/PageDtos.java`, `ui/restapi/dto/RevisionDtos.java`, `ui/restapi/ApiException.java`, `ui/restapi/mcp/McpToolCatalog.java` | API and MCP |
| `ui/controllers/editor/PageEditController.java`, `webapp/WEB-INF/jsps/editor/PageEdit.jsp` | Revisions box, diff, restore |
| `docs/dev/entries.md`, `docs/dev/api.md`, `docs/api/README.md` | docs |

---

### Task 1: Schema, entity and recording

**Test:** create `app/src/test/java/com/wikantik/pressroom/business/WeblogPageRevisionTest.java` (real database, fixture as `WeblogPageTrashTest`).

**Interfaces — produces:**

```java
// WeblogPageRevision: id, page (WeblogPage), created (Timestamp), creator, title, content, note, kept (boolean)
public static WeblogPageRevision of(WeblogPage page, String creator);
public boolean differsFrom(WeblogPage page);            // title or content differ

// WeblogPage
public void snapshotLoaded();                           // post-load: slug AND content; replaces snapshotLoadedSlug in orm.xml
public WeblogPageRevision contentBeingReplaced(String creator);   // null when new or unchanged

// WeblogPageManager
void savePage(WeblogPage page, User editor, String revisionNote) throws WebloggerException;
void savePage(WeblogPage page) throws WebloggerException;          // system callers: null creator, no note
List<WeblogPageRevision> getRevisions(WeblogPage page) throws WebloggerException;   // newest first
WeblogPageRevision getRevision(String id) throws WebloggerException;
/** The content now committed for this page, read under its row lock; null when the page is gone. */
String lockPageContent(WeblogPage page) throws WebloggerException;
```

- [ ] **Step 1: Migration** as the Global Constraints describe, with a header comment in V010's style.

- [ ] **Step 2: Failing tests.**

  | Test | Asserts |
  |---|---|
  | `aContentSaveLeavesTheDisplacedTitleAndContent` | save, reload, change title and content, `savePage(page, user, null)`: one revision holding the OLD title and content, `creator` = the user's name, `kept` false (AC10) |
  | `aNoteLabelsOnlyTheRevisionItsSaveLeft` | first change with note "why", second without: newest revision's note is null, older is "why" |
  | `aSystemSaveRecordsANullCreator` | `savePage(page)` after a content change: revision with `creator` null |
  | `aSettingsOnlySaveLeavesNone` | change slug, status, showInNav, SEO fields only: no revision (AC10) |
  | `aReorderATrashAndARestoreLeaveNone` | `setNavOrder`, `trashPage`, `restorePage`: no revision (AC10) |
  | `aNewPageLeavesNone` | first save of a new page: no revision |
  | `twoSavesInOneSessionChainTheirRevisions` | without ending the session: save A to B, then B to C: two revisions, holding A then B (Review Focus 2) |
  | `revisionsAreNewestFirst` | three changes: `getRevisions` in reverse order of saving |
  | `aRefusedRevisionRowDoesNotFailTheSave` | old title set to 300 characters by native update, then a content change saved: the save commits (new content readable), no revision stored, no exception (AC11, Review Focus 1) |
  | `aRolledBackSaveRecordsNone` | `savePage` then `TestUtils.endSession(false)`: no revision |
  | `retentionKeepsTheNewestN` | property 3, five changes: three revisions, the three newest |
  | `retentionZeroRecordsNone`, `negativeRetentionKeepsAll` | 0: none; -1: all five |
  | `deletingAPageForeverRemovesItsRevisions` | `removePage`: `getRevision(id)` null for each (AC8) |
  | `purgingATrashedPageRemovesItsRevisions` | trash, age, `purgeTrash`: revisions gone |
  | `removingAWeblogRemovesItsPagesRevisions` | `removeWeblog` completes; revisions gone (AC9, Review Focus 5) |
  | `lockPageContentAnswersTheCommittedContent` | returns the stored content; null for a page id that does not exist |

- [ ] **Step 3: Run; expected** compilation failure (`WeblogPageRevision` undefined).

- [ ] **Step 4: Implement.**
  - Entity and mapping after `WeblogEntryRevision`: named queries `WeblogPageRevision.getByPage` (`ORDER BY r.created DESC, r.id DESC`), `removeByPage`, `removeByWeblog` (subselect on `WeblogPage p WHERE p.weblog = ?1`). Register in `persistence.xml`; add the cache-size line.
  - `WeblogPage`: a transient `loadedContent` built without an id at load (as `WeblogEntry.snapshotLoadedContent` does with `EntityIds.materialise`); `contentBeingReplaced` assigns the id and stamps `created` now.
  - `JPAWeblogPageManagerImpl.savePage(page, editor, note)`: slug checks, `persist`, then `recordRevision`, then re-snapshot so a further save in the session displaces what was just saved. `recordRevision` and `storeRevision` follow `JPAWeblogEntryManagerImpl` line for line (own EntityManager, after commit, WARN on failure, inline prune to `page.revisions.retention`). `trashPage`, `restorePage` and `setNavOrder` do not record.
  - `removePage`: `removeByPage` before the row. `removePages(weblog)`: `removeByWeblog` before the pages.
  - `lockPageContent`: as `lockEntryText`.
  - Property-def `page.revisions.retention` (integer, default 50) beside `entry.revisions.retention`; label `configForm.pageRevisionsRetention=Revisions to keep for each page (-1 for all, 0 for none)`.
  - Update `MockWeblogger` or other doubles if they implement the interface.

- [ ] **Step 5: Run** `WeblogPageRevisionTest`, then `WeblogPageTrashTest,WeblogPageManagerTest,FrontPageInvariantTest,StrandMembershipTest,EqualsContractTest,PageTrashPurgeRaceTest,JPAPersistenceStrategyTest`. Expected: pass.

---

### Task 2: Ownership

**Interfaces — produces:**

```java
// WeblogOwnership
/** The revision with this id, but only when it is a revision of page (already looked up through page()). */
public static WeblogPageRevision pageRevision(Weblogger weblogger, String id, WeblogPage page);
```

- [ ] **Step 1: Failing test** `aPageRevisionIsFoundOnlyForItsOwnPage` in `WeblogOwnershipRefusalsTest`: returns the revision for its page; null for another page of the same weblog, a null page, a blank or unknown id, a revision with no page, and when the manager throws (AC13).
- [ ] **Step 2: Run; expected** `pageRevision` undefined.
- [ ] **Step 3: Implement** after `WeblogOwnership.revision`.
- [ ] **Step 4: Run** `WeblogOwnershipRefusalsTest`. Expected: pass.

---

### Task 3: API and MCP

**Test:** create `app/src/test/java/com/wikantik/pressroom/ui/restapi/v1/PagesApiRevisionsTest.java` (MockMvc, fixture as `PagesApiTrashTest`); update `ApiRouteTableTest` (63 to 66), `ToolSchemasTest`, `McpToolCatalogTest`, the README tool table.

**Behaviour:**

| Request | Answer |
|---|---|
| `GET /pages/{id}` and every other page view | gains `contentHash` (SHA-256 hex of the stored content; absent when content is null) |
| `PATCH /pages/{id}` with `expectedContentHash` not matching | 409, type `.../page-changed`, `currentContentHash` set; nothing saved. A value that is not 64 hex digits: 400 on `expectedContentHash` |
| `PATCH /pages/{id}` with `revisionNote` | labels the revision this update leaves; longer than `ColumnLimits.REVISION_NOTE`: 400 on `revisionNote` |
| `POST /pages` with `revisionNote` or `expectedContentHash` | 400 on that field ("applies only to updates") |
| `GET /pages/{id}/revisions` | summaries newest first: `id`, `created`, `creator`, `note`, `title`; no content |
| `GET /pages/{id}/revisions/{revisionId}` | the revision with `title`, `text` (its content) and `diff` to the page's current content; `?against=` diffs to another revision of the same page |
| `POST /pages/{id}/revisions/{revisionId}/restore` | 200, the page view; title and content replaced through `savePage(page, user, null)`; optional body `{"expectedContentHash": ...}` as PATCH |
| any of the three on a trashed page | read routes 404; restore 409 `Page is in the trash; restore it first.` (AC6) |
| another page's or another weblog's revision id, `against` included | 404 `No such revision.` (AC13) |

`PagesApi` writes pass the authenticated user to `savePage` (create too, where it leaves no revision). MCP `pages` gains `revisions`, `revision`, `restore_revision`, with the entries tool's wording adapted.

- [ ] **Step 1: Failing tests**, one per row, named for the row; `restore` asserts the page's title and content equal the revision's and that `savePage(page, user, null)` was called once; the stale-hash tests assert `savePage` never called.
- [ ] **Step 2: Run; expected** 404s on the new routes and a missing `contentHash`.
- [ ] **Step 3: Implement.** `ApiException.pageChanged(String currentContentHash)` (409, type `page-changed`, title "Page changed"). `RevisionDtos` gains `toSummary(WeblogPageRevision)` and `toView(WeblogPageRevision, String diff)` (no `summary`). `PageWrite` gains `revisionNote` and `expectedContentHash`. The under-lock re-check uses `lockPageContent`, as `requireUnchangedUnderLock` does for entries.
- [ ] **Step 4: Run** `PagesApiRevisionsTest,PagesApiTest,PagesApiTrashTest,PagesApiSeoTest,PageDtosTest,Mcp*Test,ApiRouteTableTest,ToolSchemasTest,FormBodyParityTest`. Expected: pass.
- [ ] **Step 5: Docs.** `docs/api/README.md` (Pages: the three routes, `contentHash`, `expectedContentHash`, `revisionNote`, the tool table); `docs/dev/api.md`.

---

### Task 4: Editor

**Routes (under `/pressroom-ui/authoring`, on `PageEditController`):**

| Route | Method | Does |
|---|---|---|
| `pageEdit!revisionDiff.rol` | POST `id`, `revisionId` | an HTML fragment: title and content diffs between the revision and the page now; 404 for a revision that is not this page's |
| `pageEdit!restoreRevision.rol` | POST `id`, `revisionId` | restores through `savePage(page, user, null)`; flash `pageEdit.revisionRestored`; redirect to `pageEdit.rol?weblog=<handle>&id=<id>` |

`pageEdit!save.rol` passes the signed-in user to `savePage`. `PageEdit.jsp` gains a Revisions box in the rail and the diff modal, copied from `EntryEdit.jsp` (`entryRevisionsCard` becomes `pageRevisionsCard`), shown only for a saved page that has revisions.

- [ ] **Step 1: Failing tests** in `PageEditControllerTest`: `savingCreditsTheSignedInUser`; `theEditorListsThePagesRevisions`; `aRevisionListThatCannotBeReadLeavesTheEditorUsable`; `theDiffIsOfThisPagesRevisionOnly` (another page's revision id: 404); `restoringPutsTheTitleAndContentBack`; `restoringAnotherPagesRevisionIsRefused` (nothing saved); `restoringOnATrashedPageIsNotFound`; `aFailedRestoreIsReported`. In `PageEditJspTest`: the box prints `revision.note` through `fn:escapeXml`, the restore form carries a CSRF token and `data-confirm`-free plain submit as the entry editor's does. Add `PageEdit.jsp`'s `revision.note` to `EditorJspEscapingTest`'s list.
- [ ] **Step 2: Run; expected** missing methods.
- [ ] **Step 3: Implement.** Reuse the entry editor's diff rendering by extracting its fragment builder to a small shared helper only if copying it would trip CPD; say which in the commit. Messages: `pageEdit.revisions=Revisions`, `pageEdit.revisionsTip=Each save that changes the title or content keeps the version it replaced.`, `pageEdit.revisionRestored=Revision restored. The version it replaced is kept as a revision.`
- [ ] **Step 4: Run** `PageEditControllerTest,PageEditControllerStrandTest,PageEditJspTest,EditorJspEscapingTest,EditorJspMarkupHygieneTest,JspConsistencyTest,MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest`. Expected: pass.
- [ ] **Step 5: Browser test** in `PageIT`: save a page, change its content and save, open it, see one row in the Revisions box, restore it, see the old content in the editor and two rows.
- [ ] **Step 6: Docs.** `docs/dev/entries.md`, "Pages": page revisions (what is held, when recorded, where cleaned up, the retention property, that Part 3 replaces the inline prune).

---

### Task 5: Whole-build verification

- [ ] `mvn clean install`: BUILD SUCCESS with every gate. A CPD hit between the two managers' revision code is resolved by extracting the shared after-commit store, not by an exclusion.
- [ ] `bin/check-diff-coverage.sh` against the last commit: at or above the threshold.
- [ ] `mvn verify -Pit`, and again with `-Dit.context.path=pressroom`: pass at both.
- [ ] One fresh reviewer over the whole change; fix Critical and Important findings test-first; rerun the build.
- [ ] Commit locally. Report to Jake, with the rulings made and any deferred minors.
