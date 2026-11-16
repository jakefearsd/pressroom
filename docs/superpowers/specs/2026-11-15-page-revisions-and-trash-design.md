# Page revisions, page trash and revision retention

Date: 2026-11-15. Status: draft for approval. Baseline: 0.12.1 (`da0aaa1ab`).

## Intent

Since 0.12.0 a page can be a blog's front door, a strand's frame and a nav
item. A bad save or a delete of such a page is now the most damaging mistake
an author or an agent can make, and it is the one place Pressroom keeps no
history: entries have revisions and a trash, pages have neither, and
`removePage` deletes the row for good.

Jake's requirements, in his words: page revisions are "system level work
that needs a robust design"; there must be "a robust set of mechanisms to
control the total number of revisions"; revisions that exist must be
recoverable; and "a recovery period for deletes is needed", which "doesn't
need to be more than a week".

A finding that shapes the design: **nothing prunes revisions today.**
`entry.revisions.retention` ships as -1 (keep everything), it is the only
control, it is a per-entry count, and nothing reports how many revisions
exist. Adding a second revision table on the same terms would double an
unbounded store.

## Goals

1. Every content-changing save of a page leaves a revision an author or an
   agent can list, read, diff and restore.
2. A deleted page is recoverable for a period (default 7 days), with its
   revisions, its strand and its slug intact.
3. The total number of stored revisions, of posts and pages together, is
   bounded by mechanisms an operator can see and set, and no mechanism can
   silently destroy the history it exists to protect.
4. The editor, `/api/v1` and MCP offer the same capabilities (the existing
   parity rule).

## Non-goals

- Server-side autosave. It stays browser-local (`pressroom-draft.js`).
- Versioning page settings: slug, status, nav flag, nav order, SEO fields,
  strand flag and parts. A slug rename already leaves a redirect; the rest
  are settings, not prose.
- A trash for media files (the reasons in `docs/dev/entries.md`, "Trash",
  still hold).
- Remembering the pre-trash status or front-door role (see "Restore").
- Bringing the CLI to parity. It already lags the API.
- Diff storage. Revisions stay full snapshots, for the reason V010 gives.

## Anti-goals

- A retention setting that deletes history the moment it is saved.
- A prune that fails open: a limit that cannot be evaluated must delete
  nothing.
- A trashed page that is still served, still in nav, the sitemap or search,
  or still the front door.
- A restore that republishes anything without a second deliberate step.
- A change to the entry revision write path's guarantee that a refused
  revision never costs the author their save.

## Constraints

- Pages stay a separate entity from entries (`docs/dev/entries.md`,
  "Pages").
- Schema changes are new idempotent migrations, V050 onward; three appliers
  read them.
- Rendered pages expire only through `weblog.lastModified`.
- One authorization path; every by-id lookup goes through
  `WeblogOwnership` / `BaseController.lookup*`; the API answers 404 for what
  the caller may not see.
- Test-driven; JaCoCo, PMD, SpotBugs and i18n gates as in `CLAUDE.md`.
- The MCP instruction text is near its 3,000-character ceiling (2,296 at
  0.8.8); new tool actions must fit or the text must be tightened.
- `runtimeConfigDefs.xml` comments must contain no bare double hyphen.

## Approaches considered

**A. A dedicated page-revision table mirroring `weblogentry_revision`, a
`TRASHED` page status, and one retention engine serving both tables.**
Chosen. It repeats two designs that have already survived production (V010
revisions, W5 trash), keeps the foreign-key cascade, and leaves the entry
write path untouched except where the shared retention engine replaces its
inline prune.

**B. One generic revision table for posts and pages** (`owner_kind`,
`owner_id`). Rejected: it cannot carry a foreign key to two parents, so
orphan cleanup becomes application code; it forces a data migration of every
existing entry revision; and it puts the working entry path at risk for no
reader-visible gain. The single place the two kinds genuinely share logic,
retention, is shared in A without sharing storage.

**C. Whole-row JSON snapshots of the page**, settings included. Rejected:
restoring a snapshot would put back a status, slug or nav flag, which means a
restore could republish a page, move its URL or re-open a front door. Those
are exactly the side effects the entry trash design refuses. It also makes
diffs and the content hash awkward.

For deletes, **a `TRASHED` status** was chosen over a `deleted_at` column for
the reason W5 gives: queries that name a status exclude trash by
construction. Pages have fewer status-naming queries than entries, so the
status-less ones are enumerated below and each gets an explicit decision.

## Design

Three parts, shippable in this order. Each is useful alone.

### Part 1: page trash

**Schema (V050).**
`pressroom_weblogpage.trashed_at timestamp(3) with time zone NULL`. Status
stays a string column with no CHECK constraint; `WeblogPage.PubStatus` gains
`TRASHED`.

**Trashing** (`WeblogPageManager.trashPage(page)`):

- Sets status `TRASHED` and `trashed_at`, in one save through `savePage`'s
  existing tail, so the existing rules apply unchanged: the front door is
  cleared when its page is no longer `PUBLISHED`, `weblog.lastModified`
  moves, and the after-commit reindex takes the page out of search.
- **Strand parts are kept.** Today `removePage` detaches them. A trashed
  strand page keeps its members and their positions so a restore returns the
  strand whole. Readers are already protected: a strand page that is not
  `PUBLISHED` gives its posts no strand bar. Detaching moves to permanent
  deletion only.
- **The slug stays held.** The unique index on `(weblogid, slug)` is
  unchanged. Creating or renaming a page to a slug held by a trashed page is
  refused with a message that names the trash (`pageEdit.error.slugInTrash`;
  API 409, as for a duplicate slug). For the site space, a trashed site page
  still blocks a blog handle of the same name
  (`WeblogCreationRules`). Deleting forever frees it.
- No redirect is minted. The URL answers 404 for the week, as an unpublished
  page's does.
- `nav_order` is left as it is.

**Restore** (`restorePage(page)`): status becomes `DRAFT`, `trashed_at`
null. Never `PUBLISHED`, and the front door is not re-established: the
existing rule is that republishing does not restore it. The restore flash
message says the page is a draft and, generically, that a home page must be
chosen again in blog settings. The page keeps its old `nav_order`; a tie
with a page that took its number sorts by title until the author reorders.

**Permanent deletion** is the existing `removePage`, unchanged in effect
(detach strand parts, delete the row, clear a stale front-door id, bump the
stamp, remove from search) plus removing the page's revisions first (Part
2). It is reached two ways: "Delete forever" on a trashed page, and the
purge.

**Purge.** Runtime property `page.trash.retention.days`, default **7**; -1
keeps forever; 0 purges on the next sweep. `TrashPurgeTask` gains a page
pass with the same discipline as the entry pass: the property re-read every
sweep, one transaction per weblog, per-weblog try/catch. Two differences
from the entry pass: the page pass **includes the site space** (site pages
are pages), and it calls `removePage` per page, never a bulk delete.

**Status-less page lookups, each decided:**

| Lookup                                            | Sees trash?       | Why                                                                                                                                                                                                                                 |
| ------------------------------------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getPages(weblog)`                                | No (changed)      | Pages screen, API list, nav reorder, the entry editor's strand select                                                                                                                                                               |
| `getTrashedPages(weblog)` (new)                   | Only trash        | Trash view, purge                                                                                                                                                                                                                   |
| `getPageBySlug`                                   | Yes               | Slug clash checks must see it. Its serving callers must treat anything not `PUBLISHED` as missing: `WeblogPageRequest`, and both uses in `PageReference` (`absoluteUrl` has no status check today and gains one). A test per caller |
| `getPage(id)`                                     | Yes               | Restore and delete-forever need it; every other by-id path is closed by the ownership rule below                                                                                                                                    |
| `WeblogCreationRules` (site pages vs. new handle) | Yes (must change) | It reads `getPages(site)` today, which now hides trash; it switches to a trash-inclusive read so a trashed site page still blocks the handle                                                                                        |
| `ReIndexPageOperation.isIndexable`                | No                | Indexable means `PUBLISHED` and not `noindex`; a test pins that `TRASHED` is not indexed                                                                                                                                            |
| `WeblogPage.removeByWeblog`                       | Yes               | Removing a weblog takes its trash                                                                                                                                                                                                   |
| `getPublishedPages`                               | No (already)      | Names a status                                                                                                                                                                                                                      |

**Who may reach a trashed page.** `WeblogOwnership.page` (behind
`BaseController.lookupPage`) returns trashed pages today because it reads
`getPage`. It changes to refuse them, answering like an unknown id, so every
existing route that uses it (the editor's GET and save, strand parts, the
API's read and PATCH) is closed at once instead of route by route. A
separate `WeblogOwnership.trashedPage` serves exactly three callers: the
trash view, restore and delete-forever. The API refuses a PATCH of a trashed page with
409 and a create or PATCH carrying `"status": "TRASHED"` with 400, matching
entries.

**Entries that belong to a trashed strand.** Membership is preserved. The
entry editor's Strand select shows the current strand even when it is
trashed, labelled as in the trash (one extra by-id read of the entry's own
strand, since `getPages` no longer returns it); re-sending it changes
nothing (existing rule); choosing it afresh from another entry is refused by `StrandRules` as
an unknown page.

**Surfaces.**

- Pages screen: Delete becomes "Move to trash" (`data-confirm`; for a front
  page the prompt still says the post list returns to the root; for a strand
  it says the parts stay attached and the strand bar disappears until the
  page is restored and published). A "Trash" view lists trashed pages with
  the date each will be purged, Restore, and Delete forever
  (`data-confirm`). Both the blog Pages screen and the Site section's pages.
- API, mirroring entries: `DELETE /pages/{id}` trashes (was: permanent);
  `POST /pages/{id}/restore`; `POST /pages/{id}/delete-forever`;
  `GET /pages?status=TRASHED` is the only way to list trash. 409 for
  trashing a trashed page or restoring one that is not.
- MCP `pages`: `delete` trashes; new `restore`, `delete_forever`.

**This is a behaviour change to a published API**: `DELETE` on a page stops
being permanent. It goes in the release notes and `docs/api/README.md`.

### Part 2: page revisions

**Schema (V051).** `pressroom_weblogpage_revision`:

| Column    | Type                                 | Notes                                              |
| --------- | ------------------------------------ | -------------------------------------------------- |
| `id`      | varchar(48) PK                       |                                                    |
| `pageid`  | varchar(48) NOT NULL                 | FK to `pressroom_weblogpage(id)` ON DELETE CASCADE |
| `created` | timestamp(6) with time zone NOT NULL | microseconds, as V010                              |
| `creator` | varchar(255)                         | the user whose save replaced this content          |
| `title`   | varchar(255)                         |                                                    |
| `content` | text                                 |                                                    |
| `note`    | varchar(255)                         | as V037                                            |
| `kept`    | boolean NOT NULL DEFAULT false       | Part 3                                             |

Index `(pageid, created DESC)`. Entity `WeblogPageRevision` (orm.xml,
`persistence.xml`, an explicit `eclipselink.cache.size.WeblogPageRevision=100`
line, because an unlisted key cannot be overridden by environment variable).

**What a revision holds**: the title and content as they were before the
save. Not settings (see Non-goals).

**Recording** follows the entry mechanism exactly, because its failure
properties were paid for:

- A JPA post-load callback snapshots the loaded title and content (extending
  today's `snapshotLoadedSlug`).
- `savePage(page, editor, revisionNote)` is the full form.
  `savePage(page)` remains for system callers and records a null creator
  (pages have no creator column to fall back to).
- A revision is recorded only when title or content changed. A save that
  changes only settings, a nav reorder, a trash or a restore leaves none.
- The row is written after the save commits, in its own transaction on its
  own EntityManager. A failure is logged at WARN and costs only the
  revision. A save that rolls back records none.
- `created` is stamped when the save runs.

**Restore** puts a revision's title and content back through the normal save
path, crediting the acting user. The restore itself leaves a revision of
what it replaced, so a restore can be undone; a restore is never coalesced
(Part 3), or a save followed by a restore would lose the content the restore
replaced. The same exemption applies to entry restores. It is refused on a
trashed page.

**A stale-write guard for pages.** The page API has none today, and restore
needs one. `PageView` gains `contentHash` (SHA-256 of the stored content, as
entries); `PATCH` and revision restore accept an optional
`expectedContentHash` and answer 409 `page-changed` with
`currentContentHash` on a mismatch. Optional, as it is for entries, so
existing callers are unaffected.

**Surfaces.**

- Page editor: a Revisions box in the rail, the entry editor's (list newest
  first, creator, time, note via `fn:escapeXml`, diff, a restore button with
  its own form and CSRF token). `revision.note` on pages joins
  `EditorJspEscapingTest`'s list.
- API: `GET /pages/{id}/revisions` (no text),
  `GET /pages/{id}/revisions/{revisionId}` (content and `diff`, optional
  `against`), `POST /pages/{id}/revisions/{revisionId}/restore`. `PATCH`
  accepts `revisionNote`. Every revision id, `against` included, resolves
  through `WeblogOwnership`; another page's revision is a 404.
- MCP `pages`: `revisions`, `revision`, `restore_revision`.
- Site pages get all of it through the same editor.

**Cleanup paths.** `removePage` removes the page's revisions before the row
(named update, as entries do, so the persistence context holds no stale
rows; the FK cascade is the backstop). `removePages(weblog)` and weblog
removal remove page revisions by weblog first.

### Part 3: revision retention, for posts and pages

One component, `RevisionRetention`, owns every decision about which
revisions exist. Both managers call it; neither prunes inline any more. It
has two entry points: **at write** (should this revision be stored, and what
does storing it push out) and **the sweep** (a daily task beside
`TrashPurgeTask`).

Five mechanisms. The first limits what is written; the next three remove;
the last protects.

1. **Coalescing (at write).** A content save writes no revision when all of
   these hold: the item's newest revision was created less than
   `revisions.coalesce.minutes` ago (default **10**, 0 off); that revision's
   creator is the user making this save; that user is not null; this save
   carries no `revisionNote`; and this save is not a revision restore. The
   existing revision already holds the content from before this burst.
   Consequences, all intended: the window runs from the revision's own
   `created` and is not extended by coalesced saves, so one user's unbroken
   session leaves at most one revision per window; intermediate states
   inside a window are not kept; a save by a second user in between ends the
   burst, so the first user's next save writes a revision; a system save
   (null creator) is never coalesced. This is the main brake on growth from
   repeated Ctrl+S and from an agent issuing many small edits.
2. **Per-item count (at write and in the sweep).**
   `entry.revisions.retention` (existing, default -1) and
   `page.revisions.retention` (new, default **50**): keep the n newest
   unkept revisions per item; 0 records none; -1 unlimited.
3. **Age (sweep only).** `revisions.maxAgeDays`, default -1 (off): revisions
   older than this are removed, subject to protection.
4. **Site-wide ceiling (sweep only).** `revisions.total.max`, default -1
   (off): when posts' and pages' revisions together (the site space's
   included) exceed it, the sweep removes the oldest unprotected revisions
   site-wide until the total fits.
5. **Protection.** Two rules that age and the ceiling never override:
   - **The floor**: the newest 5 unkept revisions of any item are never
     removed by age or by the ceiling (a constant,
     `RevisionRetention.FLOOR`). The per-item count may be set below 5
     deliberately and is honoured.
   - **Kept revisions**: a revision marked `kept` (V052 adds the column to
     `weblogentry_revision`; V051 creates it on the page table) is exempt
     from count, age and ceiling, and is extra to both the per-item count
     and the floor. At most 10 kept per item; marking an eleventh is
     refused. So an item holds at most its count plus 10. Un-keeping
     releases a revision to the ordinary rules; deleting the item for good
     (delete-forever or the trash purge) removes its kept revisions with it.

An identical-content skip was considered and dropped: a revision holds the
content a save replaced, which by construction differs from the newest
stored revision, so the rule would never fire. Restoring back and forth is
bounded by the per-item count.

**The sweep runs two passes.** Pass 1, per weblog, one transaction each:
per-item count, then age. Pass 2, once, site-wide: the ceiling, removing
oldest-first across both tables in batches of 1,000 rows, each batch its own
transaction, stopping when the total fits or nothing unprotected remains;
the next sweep continues an unfinished pass.

**Safety properties.**

- **Saving a setting deletes nothing.** Lowering a limit takes effect at
  the next sweep, or for the per-item count at that item's next content save
  (today's behaviour). When a save of the settings form changes a removing
  limit and the next sweep would then remove revisions, the confirmation
  message states how many (one step; no second submit). The maintenance
  panel shows the same figure at any time.
- **Fail closed.** If a limit cannot be read or a query throws, pass 1
  removes nothing for that weblog and pass 2 removes nothing at all, each
  logged at WARN. Removing is the decision path; "could not evaluate" is
  never "nothing to keep".
- **Per-weblog try/catch in pass 1**, properties re-read every sweep, as
  `TrashPurgeTask`.
- **An unmeetable ceiling is reported, not forced.** If floors and kept
  revisions alone exceed `revisions.total.max`, the sweep removes what it
  may, logs at WARN, and the screen below says so. Saves are never blocked
  by retention.
- **The write-path guarantee is unchanged**: retention at write runs in the
  revision's own after-commit transaction.

**Visibility.** A "Revisions" panel on the admin maintenance screen (global
admin): total revisions of posts and of pages, approximate stored size, the
oldest revision's date, the ten items holding the most, kept count, what the
last sweep removed and when, what the next sweep would remove at current
settings, and whether the ceiling is unmeetable. Figures are computed when
the screen is requested, not cached. The panel joins the existing
maintenance screen (`/pressroom-ui/admin/maintenance.rol`) with a "Sweep
now" action in that screen's own POST pattern, behind a `data-confirm`. The
same figures and action through the admin API (`GET
/api/v1/admin/revisions`, `POST /api/v1/admin/revisions/sweep` with
`dryRun`; both are new routes on the existing `AdminApi`), global-admin
token only.

**Keeping a revision.** A keep/unkeep control per revision in both editors'
rails; `POST .../revisions/{revisionId}/keep` and `/unkeep` on entries and
pages; MCP `keep_revision` / `unkeep_revision` on `entries` and `pages`.

**Settings**, in a "Revisions and trash" group of the site settings form
(the existing two entry properties move into it):

| Property                     | Default        | Meaning                |
| ---------------------------- | -------------- | ---------------------- |
| `entry.revisions.retention`  | -1 (unchanged) | newest n per post      |
| `page.revisions.retention`   | 50             | newest n per page      |
| `revisions.coalesce.minutes` | 10             | 0 off; posts and pages |
| `revisions.maxAgeDays`       | -1             | off                    |
| `revisions.total.max`        | -1             | off                    |
| `entry.trash.retention.days` | 30 (unchanged) |                        |
| `page.trash.retention.days`  | 7              |                        |

**Upgrade behaviour**: installing this removes no existing revision. The
only default that changes what is written for posts is coalescing, which
affects new saves only. Bounding the existing post history is an explicit
operator act: read the panel, then set a limit.

## Adjustments to existing mechanisms

- `WeblogPageManager`: `trashPage`, `restorePage`, `getTrashedPages`,
  `purgeTrash`, `savePage(page, editor, note)`, `getRevisions`,
  `getRevision`; `getPages` excludes trash; `removePage` clears revisions.
- `JPAWeblogEntryManagerImpl.storeRevision`: its inline prune is replaced by
  a call to `RevisionRetention`; entry behaviour at default settings changes
  only by coalescing.
- `WeblogPage`: `TRASHED`, `trashedAt`, loaded title/content snapshot,
  `contentBeingReplaced`.
- `BaseController` / `WeblogOwnership`: `page` refuses trash,
  `trashedPage`, `pageRevision`. `PageReference`, `WeblogCreationRules`,
  `EntryEditController` as in the lookup table.
- `TrashPurgeTask`: page pass. New `RevisionSweepTask` on the same base
  class.
- `PagesApi`, `PageDtos`, `RevisionDtos`, OpenAPI document,
  `McpToolCatalog`, `McpInstructions` (within the character ceiling),
  `FormBodyParityTest`.
- `PageEditController`, `PagesController`, `PageEdit.jsp`, `Pages.jsp`, the
  Site section's page screens, `Routes` CSS markers for any new route.
- `runtimeConfigDefs.xml`, `ApplicationResources.properties` and the seven
  bundled locales, under the i18n ratchets.
- `docs/dev/entries.md` (Pages, Trash, Revisions: the "pages are
  deliberately not in the trash" note is superseded), `docs/dev/api.md`,
  `docs/api/README.md`, `docs/dev/database.md`.
- The autosave note in `docs/dev/entries.md` stays true: autosave remains
  browser-local.

## Acceptance criteria

Page trash:

1. Trashing a published page: its URL answers 404; it is absent from nav,
   sitemap and search; the blog's rendered pages expire.
2. Trashing the front page clears `front_page_id` and `/` lists posts.
3. Trashing a strand page keeps every part's strand id and position;
   restoring and publishing it shows "Part N of M" as before.
4. Creating a page with a trashed page's slug is refused, naming the trash;
   after delete-forever it succeeds.
5. Restore yields `DRAFT`, never `PUBLISHED`, and does not set the front
   door.
6. A trashed page cannot be saved, re-parted or revision-restored by id,
   from the editor or the API.
7. With `page.trash.retention.days` = 7, a page trashed 8 days ago is gone
   after a sweep and one trashed 6 days ago remains; -1 purges nothing; one
   weblog's failure does not undo another's purge; the site space is swept.
8. Permanent deletion detaches strand parts and removes the page's
   revisions.
9. Removing a weblog with trashed pages and page revisions leaves no row and
   no FK failure.

Page revisions:

10. A save changing title or content leaves one revision holding the prior
    title and content, credited to the acting user; a settings-only save, a
    reorder, a trash and a restore leave none.
11. A revision row the database refuses does not fail the page save.
12. Restoring a revision replaces title and content, leaves a revision of
    what it replaced, and with a stale `expectedContentHash` answers 409 and
    saves nothing.
13. Another page's or another blog's revision id is a 404 everywhere,
    `against` included.
14. Editor, REST and MCP each list, read, diff and restore.

Retention:

15. Coalescing, window 10: two content saves by one user 3 minutes apart
    leave one revision; 11 minutes apart, two; with a `revisionNote` on the
    second, two; by different users, two; a third save by the first user
    after the second user's, three; a system save, always one more; a
    restore 1 minute after a save leaves a revision of what it replaced;
    window 0 disables it. The same cases hold for entries.
16. Restoring back and forth between two states never leaves an item with
    more unkept revisions than its per-item count.
17. `page.revisions.retention` = 3: a fourth unkept revision removes the
    oldest unkept one; kept revisions survive and are not counted, so the
    item may hold 3 plus its kept ones.
18. `revisions.maxAgeDays` = 30: the sweep removes older revisions except
    each item's newest 5 unkept and any kept.
19. `revisions.total.max` = N with more than N stored: the sweep removes
    oldest-first across posts and pages (the site space's included) to N,
    never an item's newest 5 unkept, never a kept one; when N is unmeetable
    it removes what it may and the panel and the admin API report it.
20. A query that throws in pass 1 removes nothing for that weblog and other
    weblogs are still swept; one that throws in pass 2 removes nothing.
21. Saving a lower limit in settings removes no row, and the confirmation
    message states the number the next sweep would remove when it is above
    zero.
22. An eleventh kept revision on one item is refused, in the editor and the
    API.
23. Upgrading a database with existing entry revisions removes none.
24. The maintenance panel's totals, computed on request, equal the tables'
    row counts.
25. The trash view, restore and delete-forever are the only routes that
    resolve a trashed page by id; every other page route answers an unknown
    id for it.

Criteria 7, 11 and 20 need an injected failure; the existing tests for
`TrashPurgeTask` and for the entry revision write show how each is induced
and the new tests follow them.

## Left to the builder

- Exact route names, JSP layout and copy, within the design system and the
  `Routes` marker rule.
- Whether `RevisionRetention` reaches the two tables through one small
  interface or two thin adapters.
- How the approximate stored size is computed (`pg_total_relation_size` or
  a sum of lengths).
- Batch size and sweep time of day, defaulting to the values above and to
  `TrashPurgeTask`'s schedule.
- How `McpInstructions` is tightened if the new actions do not fit.
- The release split. Suggested: Part 1 as one release, Part 2 as the next,
  Part 3 as a third, since Part 2 is safe on its own with the per-page count
  of 50.
