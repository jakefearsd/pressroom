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
- **`pressroom_audit_log.comment_text`** — the audit log's own change note; a
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
  (category/tags; locale is a hidden input), SEO drawer ("SEO and
  sharing", `weblogEdit.seoSettings`, collapsed; PageEdit shares the
  label), newsletter/revisions boxes
  (`.editor-box`/`.rail-group-label`; no Bootstrap `.card` in the rail),
  Delete as a quiet text link, last. **The submit buttons follow the
  entry's state**: on an entry that is already published, an author sees
  **Update** (primary, re-publishes) and **Unpublish** (back to draft),
  not Publish / Save draft, and Ctrl+S (Cmd+S) clicks Update — the button
  carrying `data-save-shortcut`. Before that, Ctrl+S on a live entry
  clicked Save draft and silently unpublished it. A new, never-saved entry's
  status line reads "Not saved" (`editorNotYetSaved`).
  The `#entry` form is `display:contents`
  so the newsletter/revisions boxes' own `<form>`s (own CSRF token and POST
  target) sit in the rail's grid column without nesting.
- **The Summary drawer** (under the writing surface, `bean.summary`) is the
  line under the title in entry lists and the Atom `<summary>`: its tip
  (`weblogEdit.summary.tip`) says so and that, left empty, lists show the
  post's first couple of sentences (`$entry.excerpt`, see
  `themes-and-templates.md`). Three rows, not ten, and the drawer opens on
  an entry that already has one. The API documents the field the same way
  (`EntryWrite.summary`), which reaches the MCP entries tool.
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
  `PressroomViewResolver`'s `.EntryEdit` layout maps `"sidebar"` to
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
  build"); one global, `PressroomEditor.create(options)`, called from
  `EditorScript.jsp`. `EditorSurface.jsp` (markup) and `EditorScript.jsp`
  (script) are shared verbatim by the entry and page editors. `options`
  carries `onSave`/`onPublish`/`onHelp` and `onBold`/`onItalic`/`onLink`,
  bound to `Mod-s`/`Mod-Enter`/`Mod-/` and `Mod-b`/`Mod-i`/`Mod-k`
  (`app/frontend/src/keys.js`, `hostBindings`; Mod is Ctrl, Cmd on macOS)
  at `Prec.highest`, ahead of CM6's keymap — so Mod-i is italic, not
  `selectParentSyntax`, and Ctrl+B/K never reach the browser's bookmarks
  or search bar (unsupplied: CM6's default). The bold/italic/link bindings
  run the toolbar's own commands. The title is a one-row
  `textarea[name='bean.title']` that wraps and grows (`pressroomFitTitle`);
  Enter moves to the body and a pasted line break becomes a space, so ITs
  select it as `[name='bean.title']`, never `input[...]`. Exactly three seam
  functions — `insertMediaFile`, `pressroomSetEntryText`, `pressroomGetEntryText` —
  so replacing the editor is one file; Browser ITs use those and the `Editor`
  test helper, never CM6's API.
  `pressroomEditorChangeListeners` is the fourth seam: every change-driven
  feature (autosave, live preview, word count) pushes a listener onto it.
  **Live preview is theme-true**: an iframe onto `PreviewServlet?shell=true`,
  which resolves the theme's own `_preview` template (or a shared shell) so
  the pane carries the weblog's real stylesheet; the editor posts rendered
  fragments over `postMessage` after each debounced render, and
  `#showPreviewShellScript` (`weblog.vm`) swaps `#previewArticle`'s content
  and calls `window.pressroomPreviewInit` so galleries/maps/embeds initialise.
  Paste/drop of an image posts to `mediaFileAdd!upload.rol` (the REST API's
  `MediaUploads` helper) and inserts an `[image]` shortcode. The writing
  guide (offcanvas, `Ctrl+/` or the toolbar help control) and status line
  (word count/reading time; Unsaved/Draft-saved-locally/Saved) live there
  too.
- **Autosave is LOCAL ONLY; there is no server endpoint** —
  `theme/scripts/pressroom-draft.js` writing to `localStorage`, installed from
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
  `beforeunload.pressroomLeaveWarning` namespace (not a fresh `beforeunload`
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
  pruner orders by it. A revision names the user whose save replaced it:
  callers pass the acting user to `saveWeblogEntry(entry, editor)` (the
  editor save/restore, the API's revision restore); the one-argument form is
  for system callers and falls back to the entry's creator. Rendered as a rail box below the publish rail, own
  form/CSRF per restore button.
- **Revision notes** (V037, spec `docs/superpowers/specs/2026-10-24-agent-editing-and-seo-design.md`
  section 5): `weblogentry_revision.note` (nullable, 255) says why the save that left the
  revision was made. Only an API update sends one (`EntryWrite.revisionNote`, trimmed, blank is
  none, over 255 a 400, on create a 400), carried explicitly by
  `saveWeblogEntry(entry, editor, revisionNote)`, which `/api/v1` PATCH (and so MCP update) always
  calls; `recordRevision` sets it on the snapshot, null included, so a note never outlives its
  save. A save that changes no title/text/summary leaves no revision and its note is dropped. The
  rail shows a note on its own row under its revision (`fn:escapeXml`; `revision.note` is in
  `EditorJspEscapingTest`'s list). The JSP editor has no note input. **Mockito trap:** an API
  update saves through the three-argument form, a revision restore through the two-argument one;
  a test's "nothing saved" must `never()` all three forms, or it passes vacuously.
- **Revisions in the API** (`EntriesApi.revisions`/`revision`, `EntriesWriteApi.restoreRevision`;
  MCP `entries` `revisions`, `revision`, `restore_revision`): the list is newest first with no
  text, a bare array like the other unpaged lists; one revision carries its content and `diff`,
  `TextDiff.unified` (3 lines of context) from its text to the current text or to `against`. The
  restore is the editor's (title/text/summary back through the two-argument save), refuses a
  trashed entry (409) and a stale `expectedContentHash` (409 `entry-changed`, the update's own
  `requireUnchanged`), and honours `fields`. Every revision id, `against` included, goes through
  `WeblogOwnership.revision` (the editor's `lookupRevision` delegates there too): another
  entry's revision is the unknown id's 404.

**Controllers: always name `@RequestParam`/`@PathVariable` explicitly.** The
build does not pass `-parameters`, so a bare `@RequestParam String id` throws
at runtime while unit tests (which call the method directly) pass;
`ControllerMetadataTest` fails on any unnamed one.

## Tag counts

A tag's count (`pressroom_weblogentrytagagg`, see `database.md`) is the number
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
  list of reserved slugs (`entry`/`category`/`tags`/`feed`/…). `savePage`
  (and the API's `PageDtos.requireUsableSlug`) also refuses a locale-shaped
  slug (`LocaleSegment.isLocale`: `xx` or `xx_YY`): the request parser reads a
  leading such segment as a locale before it can be a slug, so the page could
  never be served. Pages saved before this rule are not migrated; `FrontDoor`
  shows the directory if the front door's slug is locale-shaped.
  `WeblogRequestMapper` forwards any unknown single-segment path to the page
  servlet.
- **Site pages**: the hidden `site-space` weblog (`Weblog.SITE_SPACE_HANDLE`,
  V039 `site_space`, one row at most; the hyphen is outside
  `username.allowedChars` so `WeblogCreationRules` can never create the handle)
  holds the site's own pages. They use this same entity, editor and
  Markdown/shortcode pipeline; the page URL strategy puts them at `/<slug>` on
  the site host (the chosen front door at `/`), not `/site-space/<slug>`.
  `getOrCreateSiteSpace` creates it lazily with no permission row; it is left
  out of every weblog listing, the directory and the API. Slugs and blog
  handles share one namespace, compared lower-cased both ways: `savePage`
  refuses a slug equal to a blog handle (`pageEdit.error.slugIsWeblog`) or a
  protected path (`slugIsReservedPath`; a site slug starting `sitemap-` counts, as
  `sitemap-<handle>.xml` and `sitemap-site-pages.xml` are root routes outside
  the protected set), and creating a blog refuses a handle
  held by a site page of any status (`createWeblog.error.handleIsSitePage`).
  Only PUBLISHED pages are served; a draft slug is a 404. Routing:
  `routing.md`, "The site root". Rendering: `themes-and-templates.md`.
  Access: `admin-ui.md`, "The Site section".
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
