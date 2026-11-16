# Revision Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound the number of stored revisions of posts and pages with mechanisms an operator can see and set, none of which can silently destroy the history it protects.

**Architecture:** One component, `RevisionRetention`, owns every decision about which revisions exist, for both tables. Both managers call it at write (coalescing, per-item count) from inside the revision's own after-commit transaction; a daily sweep applies the per-item count, the age limit and the site-wide ceiling. Removal is set-based SQL over `weblogentry_revision` and `pressroom_weblogpage_revision`, described by a small `Kind` value so no query is written twice.

**Tech Stack:** Java 25, JPA/EclipseLink with native PostgreSQL 18 SQL (window functions) for the sweep, Spring MVC, JSP, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-15-page-revisions-and-trash-design.md`, Part 3 and acceptance criteria 15 to 24. Parts 1 and 2 shipped as `5a4cceb30` and `642a0812e`.

## Global Constraints

- Work on `master`. One commit at the end when the build is green, then **push** (Jake: push finished parts). No release.
- Test-first. Never two `mvn -pl app test` at once (the bracketed `pgrep` check from `CLAUDE.md`).
- Migration `bin/db/migrations/V052__entry_revision_kept.sql`: `ALTER TABLE weblogentry_revision ADD COLUMN IF NOT EXISTS kept boolean NOT NULL DEFAULT false;`
- Settings (runtime properties), with the spec's exact defaults:

  | Property | Default | Meaning |
  |---|---|---|
  | `entry.revisions.retention` | -1 (unchanged) | newest n unkept per post; 0 records none |
  | `page.revisions.retention` | 50 (unchanged) | newest n unkept per page; 0 records none |
  | `revisions.coalesce.minutes` | 10 | 0 off; posts and pages |
  | `revisions.maxAgeDays` | -1 | off |
  | `revisions.total.max` | -1 | off |

- `RevisionRetention.FLOOR = 5`: the newest 5 unkept revisions of an item are never removed by age or by the ceiling. `RevisionRetention.MAX_KEPT = 10` per item; an eleventh is refused.
- A kept revision is exempt from count, age and ceiling, and is extra to the count and the floor.
- Coalescing: a content save writes no revision when ALL hold: the item's newest revision is younger than the window (measured from that revision's `created`); that revision's creator equals the acting user; the acting user is not null; the save carries no note; the save is not a revision restore.
- **Saving a setting deletes nothing.** Only the sweep and an item's own next content save remove.
- **Fail closed:** a limit that cannot be read is "off"; a query that throws removes nothing for that weblog (pass 1) or at all (pass 2), logged at WARN.
- Upgrading removes no existing revision.
- The write-path guarantee stands: retention at write runs inside the revision's own after-commit transaction; its failure costs only the revision.
- Name every `@RequestParam`/`@PathVariable`; `data-confirm` for confirmations; no bare double hyphen in `runtimeConfigDefs.xml` comments; `McpInstructions.TEXT` under 3,000 characters; new message keys in `ApplicationResources.properties` only.

## Review Focus

1. **A save that is coalesced must still leave the earlier revision untouched** and must not prune anything. Test in Task 1.
2. **A user's save after another user's save in the same window** writes a revision (the burst ended). Test in Task 1.
3. **An item with fewer than 5 unkept revisions, all older than the age limit**, keeps every one. Test in Task 2.
4. **A ceiling below what floors and kept revisions require** removes what it may, reports unmeetable, and terminates (no endless loop). Test in Task 2.
5. **Un-keeping a revision on an item already over its count** removes nothing at that moment; the next save or sweep applies the count. Test in Task 3.

## Files

| File | Change |
|---|---|
| `bin/db/migrations/V052__entry_revision_kept.sql` | create |
| `pojos/WeblogEntryRevision.java`, `WeblogEntryRevision.orm.xml` | `kept` |
| `business/jpa/RevisionRetention.java` | create: write rules, sweep, stats, keep |
| `business/jpa/JPAWeblogEntryManagerImpl.java`, `JPAWeblogPageManagerImpl.java`, the two manager interfaces | call it; `restoreRevision`, `setRevisionKept` |
| `business/Weblogger.java` and its implementations | expose `getRevisionRetention()` |
| `business/runnable/TrashPurgeTask.java` | run the sweep after the purges |
| `ui/restapi/v1/EntriesWriteApi.java`, `PagesApi.java`, `AdminApi.java`, `dto/RevisionDtos.java`, `mcp/McpToolCatalog.java` | keep/unkeep, admin routes |
| `ui/controllers/editor/EntryEditController.java`, `PageEditController.java`, `EntryEdit.jsp`, `PageEdit.jsp` | keep/unkeep controls |
| `ui/controllers/admin/MaintenanceController.java`, `Maintenance.jsp`, `GlobalConfigController.java` | panel, Sweep now, settings message |
| `config/runtimeConfigDefs.xml`, `ApplicationResources.properties` | three properties, a "Revisions and trash" group, labels |
| `docs/dev/entries.md`, `docs/dev/api.md`, `docs/api/README.md`, `docs/dev/configuration.md` | docs |

---

### Task 1: Retention at write

**Test:** create `app/src/test/java/com/wikantik/pressroom/business/RevisionRetentionWriteTest.java` (real database; each case runs for a post and for a page through a small `Subject` helper).

**Interfaces — produces:**

```java
public final class RevisionRetention {
    public static final int FLOOR = 5;
    public static final int MAX_KEPT = 10;
    public enum Kind { ENTRY, PAGE }   // carries table, owner column, weblog join and count property
    /** Coalescing: false when this save's revision must not be written. Runs in the revision's own transaction. */
    static boolean shouldStore(EntityManager em, Kind kind, String ownerId, String actor, String note, boolean restore);
    /** Removes the owner's unkept revisions beyond retention-1 (the new row is not yet stored). */
    static void pruneAtWrite(EntityManager em, Kind kind, String ownerId, int retention);
}
// WeblogEntryManager / WeblogPageManager
void restoreRevision(WeblogEntry entry, WeblogEntryRevision revision, User editor) throws WebloggerException;
void restoreRevision(WeblogPage page, WeblogPageRevision revision, User editor) throws WebloggerException;
```

- [ ] **Step 1: Failing tests** (window 10 unless stated; times set by native update of `created`):

  | Test | Asserts (AC) |
  |---|---|
  | `twoSavesByOneUserInsideTheWindowLeaveOne` | saves 3 minutes apart: one revision, holding the content from before the burst (15) |
  | `aSaveAfterTheWindowLeavesAnother` | 11 minutes: two |
  | `aNoteIsNeverCoalesced` | second save carries a note: two |
  | `anotherUsersSaveIsNotCoalescedAndEndsTheBurst` | A, B, A within the window: three (Review Focus 2) |
  | `aSystemSaveIsNeverCoalesced` | null editor twice: two |
  | `aRestoreIsNeverCoalesced` | save, then `restoreRevision` 1 minute later: the restore leaves a revision of what it replaced |
  | `windowZeroTurnsCoalescingOff` | property 0: two |
  | `aCoalescedSaveRemovesNothing` | retention 1, a coalesced save: the existing revision is still there (Review Focus 1) |
  | `theCountIgnoresKeptRevisions` | retention 3, two kept (set by native update), five unkept saves: 3 unkept + 2 kept (17) |
  | `restoringBackAndForthStaysWithinTheCount` | retention 4, ten alternating restores: at most 4 unkept (16) |
  | `existingRevisionsSurviveAnUpgrade` | rows inserted natively with `kept` defaulted, retention -1: all present after a save (23) |

- [ ] **Step 2: Run; expected** compile failure (`restoreRevision`, `RevisionRetention`).
- [ ] **Step 3: Implement.** V052; `kept` on `WeblogEntryRevision`. `storeRevision` in both managers becomes: find owner, `if (!shouldStore(...)) return;`, `pruneAtWrite`, persist. `recordRevision` passes the acting user's name (null for a system save, even where the entry revision's `creator` falls back to the entry's creator) and the restore flag. `restoreRevision` sets the fields and saves with the flag. Property-def `revisions.coalesce.minutes` (10).
- [ ] **Step 4: Run** the new test, `WeblogEntryRevisionTest`, `WeblogPageRevisionTest`. Existing revision tests that save twice in quick succession as one user are updated to turn coalescing off in `setUp` (they test recording, not coalescing); say so in the commit.

---

### Task 2: The sweep and the figures

**Test:** create `app/src/test/java/com/wikantik/pressroom/business/RevisionSweepTest.java` (real database); extend `TrashPurgeTaskTest` (mock).

**Interfaces — produces:**

```java
// RevisionRetention (instance, obtained from Weblogger.getRevisionRetention())
public record SweepReport(Instant at, boolean dryRun, long byCount, long byAge, long byCeiling,
                          boolean ceilingUnmeetable, int weblogsFailed) { public long total(); }
public record ItemCount(Kind kind, String id, String title, long revisions) { }
public record Stats(long entryRevisions, long pageRevisions, long kept, long approximateBytes,
                    Instant oldest, List<ItemCount> largest, SweepReport lastSweep, SweepReport nextSweep) { }
public SweepReport sweep(boolean dryRun);
public Stats stats();
```

- [ ] **Step 1: Failing tests.**

  | Test | Asserts (AC) |
  |---|---|
  | `theSweepAppliesTheCountToItemsNotSavedSince` | retention lowered to 2 after five revisions exist: sweep removes the three oldest unkept; kept survive (17) |
  | `ageRemovesOldRevisionsButNeverAnItemsNewestFive` | `maxAgeDays` 30, eight revisions all 60 days old: three removed, five remain (18) |
  | `ageKeepsEveryRevisionOfAnItemWithFewerThanFive` | three revisions, all old: all remain (Review Focus 3) |
  | `ageNeverRemovesAKeptRevision` | (18) |
  | `theCeilingRemovesOldestFirstAcrossPostsAndPages` | `total.max` N with more stored: total equals N afterwards; the removed ones are the oldest unprotected of either table; the site space's pages are included (19) |
  | `anUnmeetableCeilingRemovesWhatItMayAndSaysSo` | floors and kept exceed N: `ceilingUnmeetable` true, nothing protected removed, the call returns (19, Review Focus 4) |
  | `aDryRunRemovesNothingAndPredictsTheSweep` | `sweep(true)` figures equal what `sweep(false)` then removes; row counts unchanged in between (21) |
  | `withEveryLimitOffTheSweepRemovesNothing` | defaults: zero (23) |
  | `aFailureInOneWeblogLeavesItUntouchedAndSweepsTheOthers` | a failure induced for one weblog (follow the injected-failure pattern of `TrashPurgeTaskTest` / a spy on the per-weblog step): `weblogsFailed` 1, that weblog's rows intact, another weblog swept (20) |
  | `aFailureInTheCeilingPassRemovesNothing` | (20) |
  | `statsEqualTheTablesRowCounts` | `entryRevisions`, `pageRevisions`, `kept`, `oldest`, `largest` (at most ten, most first) match native counts (24) |
  | `theLastSweepIsRemembered` | after a sweep, `stats().lastSweep()` carries its figures; a dry run does not replace it |
  | `thePurgeTaskRunsTheSweepLastAndSurvivesItsFailure` (`TrashPurgeTaskTest`) | `sweep(false)` called once after both purges; a throwing sweep does not escape `runTask` |

- [ ] **Step 2: Run; expected** compile failure.
- [ ] **Step 3: Implement.** Pass 1, one transaction per weblog (site space included): count (`row_number() OVER (PARTITION BY owner ORDER BY created DESC, id DESC)` over unkept rows, delete rank > retention when retention > 0), then age (rank > `FLOOR` and `created` before the cutoff). Pass 2 once: while the total exceeds the ceiling, delete up to 1,000 of the oldest unprotected rows across both tables per transaction; stop when it fits or no candidate is left (then `ceilingUnmeetable`). A dry run counts with the same predicates and deletes nothing. After deleting, evict both revision classes from the shared cache. `lastSweep` is held in memory. `Weblogger.getRevisionRetention()`; `TrashPurgeTask` calls `sweep(false)` after the page pass inside its own try/catch. Property-defs `revisions.maxAgeDays` (-1) and `revisions.total.max` (-1).
- [ ] **Step 4: Run** `RevisionSweepTest,TrashPurgeTaskTest,RevisionRetentionWriteTest`.

---

### Task 3: Keeping a revision (managers, API, MCP)

**Interfaces — produces:**

```java
// both managers
/** Marks or unmarks a revision as kept. Throws RevisionRetention.TooManyKeptException on an eleventh. */
void setRevisionKept(WeblogEntryRevision revision, boolean kept) throws WebloggerException;
void setRevisionKept(WeblogPageRevision revision, boolean kept) throws WebloggerException;
```

Routes, on entries and on pages alike: `POST .../revisions/{revisionId}/keep` and `.../unkeep`, answering the revision summary, which gains `kept`. An eleventh keep is a 409 `At most 10 kept revisions for one item.`; another item's revision id is a 404; a trashed item is closed as its other revision routes are. MCP `entries` and `pages` gain `keep_revision` and `unkeep_revision`.

- [ ] **Step 1: Failing tests:** manager (`RevisionRetentionWriteTest`): `anEleventhKeptRevisionIsRefused`, `unkeepingOnAnItemOverItsCountRemovesNothingUntilTheNextSave` (Review Focus 5), `keepingIsIdempotent`. API (`EntriesWriteApiControllerTest`, `PagesApiRevisionsTest`): keep, unkeep, eleventh is 409, foreign revision 404, `kept` in summary and view. Pinned surfaces: route count 66 to 70, `McpToolCatalogTest`, `ToolSchemasTest`, README tool table.
- [ ] **Step 2: Run; expected** compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the named classes plus `Mcp*Test,ApiRouteTableTest,FormBodyParityTest`.

---

### Task 4: Keep controls in both editors

Routes: `entryEdit!keepRevision.rol` and `pageEdit!keepRevision.rol` (POST `id` or `bean.id` as each editor's restore route names it, `revisionId`, `kept` true or false), redirecting back to the editor with `weblogEdit.revisionKept` / `weblogEdit.revisionUnkept`, or `weblogEdit.revisionKeepLimit` on the eleventh. Each revision row shows a Keep or "Kept (release)" button in its own form with a CSRF token, and a kept row is marked.

- [ ] **Step 1: Failing tests** in `EntryEditControllerTest` and `PageEditControllerTest`: keep, unkeep, the eleventh reports the limit, another item's revision is not found. `PageEditJspTest`: both JSPs carry the keep form.
- [ ] **Step 2 to 4:** run red, implement, run green with the JSP and message ratchets.

---

### Task 5: The operator's view

- Maintenance screen (`MaintenanceController`, `Maintenance.jsp`): a "Revisions" section showing `Stats`, with "Sweep now" (`maintenance!sweepRevisions.rol`, POST, `data-confirm` stating the predicted removals). Figures are computed per request; a failure to compute shows an error line and leaves the rest of the screen working.
- `AdminApi`: `GET /v1/admin/revisions` (the stats) and `POST /v1/admin/revisions/sweep` with body `{"dryRun": true|false}` (default true when the body is absent), answering the report. Global admin only, as the rest of `AdminApi`. Both routes are listed in `McpToolCatalog.EXCLUDED` with the reason "operator maintenance: admin screen and admin API only".
- Settings: a display group `revisionSettings` ("Revisions and trash") holding the five revision properties and the two trash-retention properties, moved out of `weblogSettings`. After a settings save that changed `entry.revisions.retention`, `page.revisions.retention`, `revisions.maxAgeDays` or `revisions.total.max`, when `sweep(true).total()` is above zero the confirmation adds `configForm.revisionsNextSweep` ("The next sweep will remove {0} revisions. Nothing has been removed yet."). A dry run that fails adds nothing and logs.

- [ ] **Step 1: Failing tests:** `MaintenanceControllerTest` (stats in the model; sweep now calls `sweep(false)` and reports; a stats failure is an error line); `AdminApiTest` (both routes; non-admin 403 or 404 as the class's other routes answer; `dryRun` default); `GlobalConfigControllerTest` (lowering a limit removes no row and adds the message when the dry run is above zero; an unrelated save adds none) (21); route count 70 to 72; `McpCoverageTest`.
- [ ] **Step 2 to 4:** red, implement, green, with `JspConsistencyTest` and the message ratchets.
- [ ] **Step 5: Docs.** `docs/dev/entries.md` (retention: the five mechanisms, where each runs, the safety properties, `RevisionRetention`), `docs/dev/configuration.md` (the group), `docs/dev/api.md`, `docs/api/README.md` (keep/unkeep, `kept`, the admin routes, the settings).

---

### Task 6: Whole-build verification

- [ ] `mvn clean install` green with every gate; `bin/check-diff-coverage.sh HEAD`; `mvn verify -Pit` at `/` and with `-Dit.context.path=pressroom`.
- [ ] One fresh reviewer over the whole change; fix Critical and Important test-first; rerun.
- [ ] Commit, push, report with rulings and deferred minors.
