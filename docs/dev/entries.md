# Entries, trash and pages

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Comments
Comments for signed-in readers are documented in `docs/dev/comments.md`
(data model, `CommentService`, rendering and cache expiry, the page script,
moderation, API, hazards). The W1 subsystem removed by V022 is not restored.

A sweep for "comment" must leave alone:
- **`roller_audit_log.comment_text`** — the audit log's own change note; a
  name collision only.
- **`util/GenericThrottle`** — `CommentServlet` was only its most visible
  caller; it throttles `ContactController` (`contact.throttle.*`),
  `NewsletterController` (`newsletter.subscribe.throttle.*`) and
  `PasswordResetController` (`passwordreset.throttle.*`).

Search index: `IndexOperation` no longer writes `C_CONTENT` (`Field.Store.NO`)
and `SearchOperation.SEARCH_FIELDS` is `{CONTENT, TITLE}`, not
`{CONTENT, TITLE, C_CONTENT}`, so pre-wave comment text is already unsearchable
without a rebuild. `C_NAME`/`C_EMAIL` (`Field.Store.YES`) do linger in a
pre-wave index until the entry's document is replaced (Maintenance rebuild or
incidental re-save) — minor PII residue worth clearing.

## Entry editing
- **Layout**: `EntryEdit.jsp` (approved card:
  `docs/design/editor/editor-writing-surface.html`) is a writing surface plus
  a 252px publish rail. Main column: title (large serif, borderless —
  **emphasis elsewhere is weight, never size**), the permalink as a mono line
  with a copy control, the untouched `EntryEditor.jsp` include. Rail: Publish
  box (status pill, the one visible time field, submit buttons), Organize box
  (category/tags; locale is a hidden input), SEO drawer (SEO & Social
  Sharing card, collapsed), newsletter/revisions boxes
  (`.editor-box`/`.rail-group-label`; no Bootstrap `.card` in the rail),
  Delete as a quiet text link, last. The `#entry` form is `display:contents`
  so the newsletter/revisions boxes' own `<form>`s (own CSRF token and POST
  target) sit in the rail's grid column without nesting.
- **`bean.pubTimeLocal` is the only pubtime field and means the WEBLOG's
  clock.** One `<input type="datetime-local">` replaced the old
  three-`<select>` row. `EntryBean.getPubTime(TimeZone)` parses the
  wall-clock string against the caller's `TimeZone`; `EntryEditController`
  always passes `getActionWeblog(request).getTimeZoneInstance()`. A non-blank
  value that fails to parse **throws**; the save is blocked with
  `entryEdit.pubTimeInvalid` via `addFieldError` (marking and focusing
  `entry_bean_pubTimeLocal`) through the normal `hasErrors` gate — the old
  parser silently published "now". Blank still means publish now.
  **The SEO card's `bean.eventStartLocal`/`eventEndLocal` share those
  semantics**: wall-clock times in the weblog's zone, read and written
  through `EntryFieldRules.parseEventTime`/`formatWallClock`, the same pair
  `EntryDtos` uses for the API's `eventStart`/`eventEnd`, so the editor and
  the API cannot disagree about an event's instant. The editor works to the
  minute (`formatWallClock` drops seconds, so an unchanged editor save
  truncates an API-set event time's seconds; ruled acceptable).
- **The sidebar is retired.** `EntrySidebar.jsp` is deleted;
  `RollerViewResolver`'s `.EntryEdit` layout maps `"sidebar"` to
  `tiles/empty.jsp`; its four recent-entries lists (20 each) are gone from
  `EntryEditController` too.
- **Entry plugins are gone; the shortcode render seam stays.**
  `ConvertLineBreaksPlugin`, the "Plugins to apply" card and
  `weblogentry.plugins`/`weblog.defaultplugins` (dropped V021, idempotent)
  are deleted (see Plugin System). `PluginManagerImpl`/`WeblogEntryPlugin`
  and `WeblogEntry.render()`'s call into `applyWeblogEntryPlugins` stay
  because `ShortcodeExpander` runs through that call (see Shortcodes); both
  call sites now apply every site-registered plugin unconditionally.
- **Editor**: CodeMirror 6, built by Maven from `app/frontend/` (see "Frontend
  build"); one global, `RollerEditor.create(options)`, called from
  `EditorScript.jsp`. `EditorSurface.jsp` (markup) and `EditorScript.jsp`
  (script) are shared verbatim by the entry and page editors. `options`
  carries `onSave`/`onPublish`/`onHelp`, bound to `Mod-s`/`Mod-Enter`/`Mod-/`
  ahead of CM6's keymap (unsupplied: CM6's default). Exactly three seam
  functions — `insertMediaFile`, `rollerSetEntryText`, `rollerGetEntryText` —
  so replacing the editor is one file; Browser ITs use those and the `Editor`
  test helper, never CM6's API.
  `rollerEditorChangeListeners` is the fourth seam: every change-driven
  feature (autosave, live preview, word count) pushes a listener onto it.
  **Live preview is theme-true**: an iframe onto `PreviewServlet?shell=true`,
  which resolves the theme's own `_preview` template (or a shared shell) so
  the pane carries the weblog's real stylesheet; the editor posts rendered
  fragments over `postMessage` after each debounced render, and
  `#showPreviewShellScript` (`weblog.vm`) swaps `#previewArticle`'s content
  and calls `window.rollerPreviewInit` so galleries/maps/embeds initialise.
  Paste/drop of an image posts to `mediaFileAdd!upload.rol` (the REST API's
  `MediaUploads` helper) and inserts an `[image]` shortcode. The writing
  guide (offcanvas, `Ctrl+/` or the toolbar help control) and status line
  (word count/reading time; Unsaved/Draft-saved-locally/Saved) live there
  too.
- **Autosave is LOCAL ONLY; there is no server endpoint** —
  `theme/scripts/roller-draft.js` writing to `localStorage`, installed from
  `EntryEditor.jsp` and `PageEdit.jsp`. A server endpoint would multiply
  `weblogentry_revision` rows against `entry.revisions.retention`'s default
  **-1, keep everything** (see Revisions) and need a real entry row per
  draft; the work that actually gets lost is lost in the browser, which
  `localStorage` covers. Load-bearing:
  - **Recovery compares content, never timestamps** (a check against
    `entry.updateTime` must reconcile browser, server and weblog clocks). A
    snapshot matching what the server just rendered is a completed save and
    is dropped silently.
  - **`staleKeys` compare the editor text ALONE**, unlike the primary key's
    whole-form comparison: saving a new entry redirects to `entryEdit`, where
    `EntryBean.copyFrom` has populated `bean.status` and `bean.pubTimeLocal`
    (empty on the add form), so a whole-form comparison never matches, the
    `entryAdd:new` snapshot survives its own save, and the next blank editor
    is handed the previous entry's text (`EntryAutosaveIT` caught this). The
    consumption test is text **and** title, or reloading an entry's own tab
    would delete a new-entry draft whose body was copied from it.
  - **The field denylist is by NAME, not `type="hidden"`**:
    `bean.featuredImageId`/`bean.ogImageId` are hidden inputs carrying real
    author choices. `bean.status` is deliberately *not* excluded — a visible
    `<select>` on the page editor, and on the entry editor the submit buttons'
    `formaction` decides status regardless. So on a **page**,
    `PageBean.copyTo` writes the submitted status through and Restore can put
    a stored DRAFT/PUBLISHED choice back into that select (the one place
    Restore changes something other than prose).
  - **Submit saves the snapshot rather than clearing it**, so an expired
    session's login redirect does not take the text; the next load drops it
    by content comparison.
  - **Restore re-saves rather than dropping**, so recovered text keeps a
    backup; it never auto-restores (an unreviewed snapshot must not overwrite
    the server's copy).
  Known limits (in the spec): two tabs on the same *new* entry share the one
  `…:new` slot (later debounce wins); a draft outlives logout, up to 30 days
  in that browser profile, so on a shared machine the next author sees it.
  The leave-warning is bound **once** against a dirty flag under the
  `beforeunload.rollerLeaveWarning` namespace (not a fresh `beforeunload`
  *and* `submit` handler per CodeMirror `change`, as before), and stays
  despite drafts.
- **Preview** is server-side (`entryEdit!preview.rol`): only the server can
  expand shortcodes.
- **List actions**: `Entries.jsp` is ONE form around the table — bulk
  checkboxes, per-row duplicate and the action bar post through it, so the
  duplicate control is a submit button carrying `name="duplicateId"`, not a
  nested form. Bulk actions loop per id through `BaseController`'s
  `lookupEntry`; delete goes through `trashEntryWithIndex` so the Lucene index
  cannot be orphaned. **Delete moves an entry to the trash** (W5, see Trash),
  and the flash copy says so.
- **Revisions**: `weblogentry_revision` (V010) keeps the pre-save title/text/
  summary of every content-changing save, snapshotted by a JPA `post-load`
  callback (`WeblogEntry.snapshotLoadedContent`) because `saveWeblogEntry`
  only sees the caller's NEW values. Runtime property
  `entry.revisions.retention`: **-1 (default) keeps everything**, 0 records
  none, n>0 prunes to the n newest. The revision (and its prune) is written
  AFTER the save commits, in its own transaction on its own EntityManager
  (`JPAWeblogEntryManagerImpl.storeRevision`): in the save's transaction a
  refused revision row failed the commit and lost the save. A failure costs
  only the revision (WARN); a save that rolls back records none. `created`
  is stamped when the save runs (`contentBeingReplaced`), not at load: the
  pruner orders by it. Rendered as a rail box below the publish rail, own
  form/CSRF per restore button.

**Controllers: always name `@RequestParam`/`@PathVariable` explicitly.** The
build does not pass `-parameters`, so a bare `@RequestParam String id` throws
at runtime while unit tests (which call the method directly) pass;
`ControllerMetadataTest` fails on any unnamed one.

## Tag counts

A tag's count (`roller_weblogentrytagagg`, see `database.md`) is the number
of PUBLISHED entries carrying it. **`saveWeblogEntry` and
`removeWeblogEntry` only name the tags they touched** (the entry's tags,
removed ones included, plus at commit whatever tags the entry has in the
database); `TagAggregateStore` recounts those from the database at commit
(see `database.md`). Nothing is derived from the entry as loaded, which a
concurrent save of the same entry can have made stale. Callers only set
the status. The `refreshAggregates` flag every
caller had to remember was removed (2026-10-22): the API's PATCH never set
it, so a draft published through the API was never counted and an
unpublished one never uncounted; a tag dropped while unpublishing stayed
counted; a future-dated publish was counted before the switch to SCHEDULED
and again on promotion; and a second save in one session counted its added
tags twice, because `addedTags`/`removedTags` were never reset (now
`snapshotLoadedContent` resets them). `TagAggregateBookkeepingTest` walks
each transition.

## Trash (soft delete, W5)

Deleting an entry moves it to a restorable trash; every design choice keeps
the new dimension from spreading.

- **A fifth `PubStatus` value (`TRASHED`), not a `deleted_at` column**: every
  query that names a status excludes trash by construction, whereas
  `deleted_at IS NULL` would have to be remembered in seven named queries, a
  dynamic query builder and 23 call sites, and fails **open**. `status` is
  stored by name (`<enumerated>STRING</enumerated>`), so no ordinal hazard.
  `weblogentry.trashed_at` (V025) exists only so the trash list can sort and
  the purge can expire.
- **The exclusion lives in exactly one place**: `WeblogEntrySearchCriteria`'s
  `includeTrashed`, default **false**, applied in
  `JPAWeblogEntryManagerImpl.getWeblogEntries` when no explicit status is
  set. The default IS the safety property; never add a status condition to a
  second query.
- **Four status-less queries deliberately still see trash**, each commented
  in `WeblogEntry.orm.xml`: `getByCategory` (a trashed entry must still block
  deleting its category, or there is nothing to restore into), the two anchor
  queries (a trashed entry still occupies its anchor; the permalink lookup is
  safe only because `PageServlet` filters `isPublished()`), and
  `getByWebsite` (the weblog-deletion cascade must take the trash with it).
- **Restore always goes to `DRAFT`, never `PUBLISHED`** — silent republishing
  to feeds, sitemap and subscribers is worse than one extra click; hence no
  column remembers the pre-trash status.
- **`BaseController.trashEntryWithIndex`** (was `removeEntryWithIndex`) is the
  single authoring-side deletion seam, with `deleteEntryForeverWithIndex`
  beside it. `WeblogEntryManager.removeWeblogEntry` remains the one
  permanent-deletion path — `purgeTrash` calls it per entry, never a bulk
  DELETE.
- **The index steps are MORE necessary now the entry survives** — a `TRASHED`
  entry left in Lucene is findable and links to a 404. Two live bugs:
  - `ReIndexEntryOperation` is **asynchronous** and **re-fetches the entry by
    id**, so the old flip-status-to-DRAFT-and-re-index trick only worked
    because the row was gone before the job ran, and re-added trashed entries
    once it survived. It now refuses to add a document for a
    non-`PUBLISHED` entry.
  - **`weblog.lastModified` must move whenever an entry enters or leaves
    `PUBLISHED`.** `CacheManager.invalidate` never reaches `WeblogPageCache`
    or the sitemap cache; only `lastModified` expires them (see Templates).
    `saveWeblogEntry` bumps when the entry is published now **or was loaded
    as published** (the second half was missing until 2026-10: unpublishing
    left the entry on cached pages and in the sitemap). `trashWeblogEntry`
    keeps its own explicit bump from the in-memory status, for an entry
    that was never loaded.
  Trashing a published entry takes its tags out of the tag cloud and
  restoring (to `DRAFT`) puts nothing back, both without help from the
  caller: see Tag counts below.
- **`entry.trash.retention.days`** — runtime property, default **30**, `-1`
  keeps forever. Swept by `TrashPurgeTask` beside `ScheduledEntriesTask`;
  re-read per sweep, not latched in `init()` (the third Configuration-scope
  trap); per-weblog try/catch.
- **Pages and media files are deliberately NOT in the trash**: a soft-deleted
  media file still occupies disk, counts against `uploads.dir.maxsize`, keeps
  its renditions and stays reachable at its media URL unless every such path
  learns about trash — the spreading this design avoids. Pages are few and
  deliberate.
- **A bare `--` inside an XML comment in `runtimeConfigDefs.xml` makes the
  parse fail SILENTLY**: `getRuntimeConfigDefs()` returns null and surfaces
  as unrelated NPEs elsewhere (it bit the `pom.xml` coverage comments too).

## Pages
Static pages (`WeblogPage`, V014) are a separate entity from `WeblogEntry` on
purpose: merging would touch all 25 of `WeblogEntryManager`'s query paths.
- **Routing**: a published page is served at `/<handle>/<slug>`. **Not
  `/<handle>/page/<slug>`**, the CUSTOM-template route (see Templates); both
  admin screens (editor, `Pages.jsp`) once emitted it, so `PageEditJspTest`
  asserts them against each other. `ReservedSlugs` (shared by
  `WeblogPageManager` at save and `WeblogPageRequest` at parse) is the one
  list of reserved slugs (`entry`/`category`/`tags`/`feed`/…).
  `WeblogRequestMapper` forwards any unknown single-segment path to the page
  servlet.
- **Lazy resolution**: parsing sets only `pageSlug` (no DB access; the
  cache key); `getWeblogPageContent()` resolves it memoized, so a cache hit
  never resolves the page. An unknown or draft slug is a 404, never a
  fall-through to the permalink/default-page branches. `WeblogPageCache` and
  `SiteWideCache` keys carry a `/pageslug/<slug>` segment, so a page and a
  same-named context never collide.
- **Rendering**: a theme may override the page template with one named
  `_page`, via the `StaticThemeTemplate`/`VelocityRendererFactory` fallback.
  `savePage`/`removePage` bump `weblog.lastModified`; no explicit eviction
  (see Templates). The bump also expires the remembered published-page list
  behind `PageModel.getNavPages` (see "Remembered lookups ride the same stamp"
  in `themes-and-templates.md`), so a page change that skipped `savePage`
  would leave the nav stale too.
- **Editor**: `PageEditController`/`PagesController` reuse the entry editor's
  shape via `PageBean`. `lookupPage` is the fourth by-id ownership member on
  `BaseController` beside `lookupEntry`/`lookupTemplate`/`lookupCategory`
  (see Categories), over the global `getPage`. The `showInNav` field marker
  is `_showInNav`, **not** `_bean.showInNav` — the `bean.` prefix silently
  breaks it; a unit test reads `PageEdit.jsp` to pin it.
