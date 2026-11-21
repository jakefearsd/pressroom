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
- **Who may change an entry** (`BaseController.lookupChangeableEntry`):
  saving over it, restoring a revision onto it and trashing it need an entry
  of the action weblog that is not trashed; a contributor (EDIT_DRAFT, no
  POST) additionally may change only their own entries that are still DRAFT
  or PENDING. Anything else answers like an unknown id. Before 2026-11-03
  (code review) a contributor could trash any post by id (ids are in page
  source), restore old revisions onto live posts, and demote other people's
  posts by saving them, and a save from a stale tab republished a trashed
  entry. The rule lives in `ContributorRules` (`mayChange`, and `ceiling` for
  the status a contributor's Publish becomes), which the entries API applies
  too since 0.16.0: a contributor's token writes drafts there, with a 403
  `entry.notChangeable` where the editor answers like an unknown id
  (`docs/dev/api.md`, "Contributors write drafts over the API").
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
- **A picked featured/social image is validated at save** (2026-11):
  `bean.featuredImageId`/`bean.ogImageId` (and the page editor's
  `bean.ogImageId`) must name an image of the action weblog outside every
  private directory — `WeblogOwnership.publicImage`, the same rule
  `EntriesWriteApi` applies, through `BaseController.checkPickedImage`. The
  renderers (`WeblogEntryWrapper.resolveMediaFile`, `PageModel.getPageOgImage`)
  resolve the id globally, so a crafted POST used to make one blog's page
  print another blog's (or a private directory's) media metadata. A blank id
  (cleared) and the value already stored are accepted, so a deleted image
  never blocks an unrelated save; a lookup that throws refuses. The editors'
  thumbnail previews use `BaseController.pickedImageThumbnailURL`
  (`WeblogOwnership.mediaFile`).
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
  stay; `EntryRenderer` is the single render pipeline
  (`applyWeblogEntryPlugins` is deleted) and applies every site-registered
  plugin unconditionally.
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
  - **A key names the user**:
    `pressroom.draft.v1:<context path>:<user id>:<handle>:<action>:<id>`
    (`EditorAutosaveWiringTest`). `localStorage` belongs to the browser, so a
    key without the user offered the next member on a shared machine the last
    one's draft. The Logout link also clears every `pressroom.draft.v1:` key
    (a delegated click handler in `pressroom.js`, which every admin page
    loads); a session that just expires keeps them, for its owner only.
  Known limits (in the spec): two tabs on the same *new* entry share the one
  `…:new` slot (later debounce wins); a draft outlives a session that expires
  without a logout, up to 30 days in that browser profile (offered only to
  the same user); drafts written under the older user-less keys are never
  offered again and age out through the 30-day sweep.
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
  `lookupEntry`; delete goes through `trashEntry` so the Lucene index
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

## The entry list

`entries.rol` and `GET /v1/weblogs/{handle}/entries` build the same query
through `EntryListQuery` (`ui.controllers`): filters in, `WeblogEntrySearchCriteria`
and the trash rule (trash only when `TRASHED` is asked for by name) inside, one
extra row fetched to answer "more?", an `EntryListPage` out. Each surface keeps
its own parameter parsing. `EntryListView` (`ui.view`) is what `Entries.jsp`
shows, as plain values; `EntriesController` only adds the urls. An unknown
`bean.status` on `entries.rol` is no filter (the whole list), and so not counted
as one: `EntryListView.Filters.any()` reads the status through
`EntryListQuery.lenientStatus`, so an empty list under a stale status says "no
entries yet", not "nothing matched". The API answers 400 for the same value.

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
- **`BaseController.trashEntry`** (was `removeEntryWithIndex`) is the
  single authoring-side deletion seam, with `deleteEntryForever`
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
  trap); per-weblog try/catch, and since 2026-11-03 one transaction per
  weblog (it committed once at the end, so a commit-time failure in one
  weblog undid every other's purge, every day). `ScheduledEntriesTask`
  likewise promotes each due entry in its own transaction; a failing entry
  stays SCHEDULED and is retried next minute. Both sweeps skip weblogs with
  `visible = false` (nothing in the UI sets it today) and the trash sweep
  skips the site space (which has no entries screens); whether a hidden
  weblog's schedule and trash should keep running is an open product
  question.
- **Media files are deliberately NOT in the trash**: a soft-deleted
  media file still occupies disk, counts against `uploads.dir.maxsize`, keeps
  its renditions and stays reachable at its media URL unless every such path
  learns about trash — the spreading this design avoids. Pages have their own
  trash since 0.13.0: see "Page trash" below.
- **A bare `--` inside an XML comment in `runtimeConfigDefs.xml` makes the
  parse fail SILENTLY**: `getRuntimeConfigDefs()` returns null and surfaces
  as unrelated NPEs elsewhere (it bit the `pom.xml` coverage comments too).

## Order and pins
- **Blog order** (`weblog.entry_order`, V047, `EntryOrder` NEWEST default |
  OLDEST). It sets the order of the blog's OWN listings: home, category, tag
  and archive pages (Task 2 wired the queries). It does not touch feeds, the
  site front page, site-wide lists, search or sitemaps (all newest first), nor
  the Older/Newer links on a post, which stay chronological in both orders.
- **One validation, two surfaces**: `WeblogSettingsRules` refuses anything
  `EntryOrder.parse` rejects (field `entryOrder`, message
  `weblogSettings.error.entryOrder`), for the settings form and for
  `PATCH /api/v1/weblogs/{handle}` (400 with a field error; the API has no 422).
  The bean stores `name()`; a stored unknown value reads as newest first
  (`Weblog.entryOrder()`), and a form that omits the field resets the blog to NEWEST (the form binds a
  fresh bean whose default is NEWEST).
- **Cache**: saving the settings goes through `saveWeblog`, which stamps
  `lastModified`, so rendered pages expire at once.
- **Pins** (`weblogentry.pinned`, V047; not `pinnedtomain`, the legacy
  site-wide flag, left untouched). `WeblogEntriesLatestPager` puts a blog's
  pinned PUBLISHED posts (pubTime not after now) above page 1 of the home
  listing only, in the blog's order, capped at `EntryPins.MAX_PER_WEBLOG`;
  regular rows leave them out, so page 2+ neither skips nor repeats.
- **The limit is one rule**: `EntryPins.wouldExceed(stored, requested,
  getPinnedEntryCount(weblog))` -- only a NEW pin can exceed 3, and the count
  is every pinned entry not TRASHED (drafts and scheduled count). Checked
  before anything is applied: the editor as a field error on
  `entry_bean_pinned` (`weblogEdit.error.pinLimit`), the API in `checkWrite`
  (400, field `pinned`). A count that cannot be read refuses the pin.
- **Who may pin**: `WeblogPermission.POST`. The editor renders the checkbox
  only for `userAnAuthor` and applies `bean.pinned` only for a POST user, so
  an EDIT_DRAFT author's save (no field) keeps a publisher's pin; never move
  the pin into `EntryBean.copyTo`. The API needs nothing extra: every entry
  write declares POST, so a draft-only member is refused (403).
- **Paths that could make a fourth**: `restoreWeblogEntry` clears the pin when
  3 others are pinned (counted while the entry is still TRASHED);
  `copyAsDraft` (Duplicate) never copies the pin.
- **Cache**: no bump of its own. `saveWeblogEntry` bumps `lastModified` for an
  entry loaded PUBLISHED, which is the only kind whose pin a reader sees.
- **Entries list**: a pinned row says "Pinned" (`weblogEntryQuery.pinned`,
  `.entry-pinned`) in its meta line.

## Strands
Spec `docs/superpowers/specs/2026-11-15-ordering-strands-pages-design.md`,
release 2. A strand is a page with `is_strand` (V048) carrying an ordered list
of its blog's posts: its text frames the topic, its parts are listed beneath,
and each part shows "Part N of M" with steps to the parts either side.
- **Membership lives on the post** (`weblogentry.strand_page_id`,
  `strand_position`; FK `ON DELETE SET NULL` as a backstop only), so "zero or
  one strand per post" holds by construction. Parts are posts of the **same
  blog**. One rule decides who may be named: `StrandRules.refusals` (unknown,
  other blog, in another strand, listed twice), a reason key per refused id,
  shared by the page editor, the entry editor and the API.
- **Positions**: `appendToStrand` puts a post at max + 1 over ALL members of
  any status, the trash included (first part = 1), and from another strand it
  moves without renumbering the old one. `setStrandParts` rewrites 1..n in list
  order and detaches members not listed; it trusts its input, so run
  `StrandRules` first. Positions need not be contiguous: numbering is computed.
  Trashing keeps strand and position, so a restore returns the post to its
  place; `copyAsDraft` never copies the strand (as it never copies the pin).
- **Reader-visible part** = PUBLISHED and `pubTime <= now`
  (`getStrandParts(page, true)`). N is the rank among those by position, M
  their count (`StrandPlace`). A strand page not PUBLISHED, or not a strand:
  its posts get no strand bar and keep the blog's Older/Newer
  (`WeblogEntriesPermalinkPager.getStrandNav` is null). Never a link to a 404.
- **Editor surfaces** (all need `WeblogPermission.POST`): the page editor's
  "This page is a strand" switch and Parts panel (`PageEditController`; every
  member any status, trashed shown; up/down, drag, remove, "Add posts" of this
  blog's live posts in no strand; the server trusts only the posted order of
  `bean.partIds`); the entry editor's Strand select (`EntryEditController`,
  this blog's strand pages, drafts included; a plain page, another blog's page
  or an unknown id is one field error, `weblogEdit.error.notAStrand`; the
  current strand re-sent changes nothing). Site pages have no strand.
- **API surfaces** (`docs/api/README.md`, "Strands"; `api.md`): page `strand`
  and read-only `parts`; `PUT .../pages/{id}/parts` replaces the membership
  (each refused id a 400 on `entries`); entry `strand` appends or moves,
  `clear: ["strand"]` leaves. MCP: `pages set_parts`, `strand` on `pages
  update` and `entries update`.
- **Untick and delete**: unticking `is_strand` while the page has parts (any
  status, trashed included) is refused, in the editor (`pageEdit.error.strandHasParts`)
  and the API (400 on `strand`). Deleting a strand page detaches its parts
  (`removePage` clears them through `WeblogEntry.clearStrandByPage`; posts stay
  published), after a `data-confirm` naming the count; removing a weblog
  clears its posts' strands first, so no FK fails.
- **Cache**: every change a reader sees bumps `weblog.lastModified`:
  `setStrandParts` saves the weblog, an entry save bumps it as usual, and a
  page save goes through `savePage`.

## Pages
Static pages (`WeblogPage`, V014) are a separate entity from `WeblogEntry` on
purpose: merging would touch all 25 of `WeblogEntryManager`'s query paths.
- **Page slug, strand and pin rules have one home** (spec `2026-11-20-jsp-rules-and-view-models-design.md`,
  Task 4): `PageRules` (slug shape, holder, strand switch), `StrandChoice` (the strand an entry joins) and
  `EntryPinRules` (the pin limit; in `ui.controllers`, not `business.EntryPins`, which must not import a
  controller type). The editors and `PagesApi`/`EntriesWriteApi` call them; the page editor judges the slug
  before the save and no longer reads `WebloggerException` text. Rules needing the store throw on a failed
  lookup (the API's opaque 500); the screens catch it and refuse.
- **Page trash** (V050; spec `2026-11-15-page-revisions-and-trash-design.md`,
  Part 1). Deleting a page sets `PubStatus.TRASHED` and `trashed_at`; nothing
  but the trash's own "delete forever" and the purge calls `removePage`.
  - `trashPage`/`restorePage` go through `persist`, the tail of `savePage`,
    so the front door is cleared, `weblog.lastModified` moves and the page
    leaves search by the same code a save uses. They run **no slug check**:
    a slug saved before a rule existed must still trash and restore.
  - A trashed page **keeps** its slug (the unique index is unchanged; the
    editor says `pageEdit.error.slugInTrash`, the API a 409 on `slug`), its
    `nav_order` and a strand's parts. Parts are detached only by
    `removePage`. No redirect is minted.
  - Restore is always `DRAFT` and never re-establishes the front door.
  - **`getPages` excludes trash; `getTrashedPages` is only trash.**
    `getPage(id)` and `getPageBySlug` still see it, so every serving caller
    names `PUBLISHED` (`WeblogPageRequest`, `PageReference`, the strand bar,
    search results, `IndexOperation.isIndexable`). `WeblogCreationRules`
    reads both lists, so a trashed site page still blocks a blog handle.
  - **Two ownership lookups.** `WeblogOwnership.page` (`lookupPage`) answers
    null for a trashed page, which closes the editor, preview, strand parts
    and the API's read and writes at once. `WeblogOwnership.trashedPage`
    (`lookupTrashedPage`) answers only trash and serves three callers: the
    trash view (`PagesTrashController`, `pagesTrash.rol`), restore and
    delete forever. A new by-id page route uses the first unless it is one
    of those three.
  - **Purge**: `TrashPurgeTask` runs a page pass after the entry pass, one
    transaction per weblog, the site space included, reading
    `page.trash.retention.days` (default 7; any negative keeps forever)
    every sweep. `WeblogPageManager.purgeTrash` calls `removePage` per page
    and skips a trashed row with no stamp.
  - The entry editor's Strand select adds the post's own strand when that is
    trashed (one by-id read), so the membership still shows; no other post
    can choose it.
  - Browser tests that reuse a slug must clear the trash too
    (`PressroomIT.deletePageForeverFromTrash`).
- **Page revisions** (V051, `pressroom_weblogpage_revision`,
  `WeblogPageRevision`; spec Part 2). The entry mechanism, repeated:
  - A revision holds the **title and content** a save replaced, never a
    setting, so a restore cannot republish a page or move its URL.
  - `WeblogPage.snapshotLoaded` (JPA post-load) takes the baseline;
    `savePage(page, editor, note)` records `contentBeingReplaced` **after
    the commit, in its own transaction** (`storeRevision`), where a failure
    is a WARN and costs only the revision. `savePage(page)` is the system
    form (null creator). Only `savePage` records: `trashPage`,
    `restorePage` and `setNavOrder` go through `persist` and leave none.
    After recording, the baseline is re-taken, so two saves in one session
    chain.
  - Retention is `page.revisions.retention` (default **50**, 0 none,
    negative all), pruned inline at the write. Part 3 of the spec replaces
    this prune and the entries' with one `RevisionRetention`; the `kept`
    column exists for it and nothing sets it yet.
  - `removePage` removes the page's revisions before the row and
    `removePages(weblog)` by weblog; the FK cascade is the backstop.
  - Revision ids resolve through `WeblogOwnership.pageRevision(id, page)`,
    with the page already found by `lookupPage` (which refuses trash).
  - Editor: the rail's Revisions box and diff modal on `PageEdit.jsp`,
    `pageEdit!revisionDiff.rol` and `pageEdit!restoreRevision.rol`. The
    diff fragment is `RevisionDiffHtml`, shared with the entry editor.
  - A mock-based test of a page save must verify the three-argument
    `savePage`: `never().savePage(any())` on the old form passes vacuously.
- **Revision retention, posts and pages** (V052; spec Part 3). Neither
  manager decides which revisions exist.
  - **At a write** (`RevisionRetention`, static, called from each manager's
    `storeRevision` inside the revision's own after-commit transaction):
    `shouldStore` is coalescing (`revisions.coalesce.minutes`, default 10: no
    revision when the item's newest one is inside the window and by the same
    acting user; never for a null user, a note, or a restore), then
    `pruneAtWrite` is the per-item count over **unkept** rows, removed
    through the entity manager so the shared cache stays right. A coalesced
    save prunes nothing.
  - **Restores** go through `restoreRevision` on the manager, which saves
    with the restore flag. A caller that sets the fields and calls the plain
    save would be coalesced and lose the content it replaced.
  - **The sweep** (`RevisionSweeper`, own EntityManagers, native SQL with
    `row_number()`): pass 1 per weblog, one transaction each, the count then
    `revisions.maxAgeDays`; pass 2 once, `revisions.total.max`, oldest first
    across both tables in batches of 1,000. It runs as the last step of
    `TrashPurgeTask` and from "Sweep now" on the maintenance screen, and is
    reached through `WeblogEntryManager.sweepRevisions(dryRun)` /
    `revisionStats()`. After removing, it evicts both revision classes from
    the shared cache.
  - **Protection**: a `kept` revision is exempt from everything and is
    extra to the count (at most `RevisionRetention.MAX_KEPT` = 10 an item,
    `RevisionKeepLimitException`); an item's newest `FLOOR` = 5 unkept are
    never removed by age or the ceiling. A ceiling below what is protected
    is reported (`ceilingUnmeetable`), never forced.
  - **Fail closed**: a limit that cannot be read is off; a weblog whose pass
    throws rolls back whole and is counted in `weblogsFailed`; a ceiling
    pass that throws removes nothing (`ceilingFailed`).
  - **Saving a setting deletes nothing.** `GlobalConfigController` runs a
    dry run after a save that changed a removing limit and adds
    `configForm.revisionsNextSweep` when it is above zero.
  - The settings sit in the `revisionSettings` display group ("Revisions
    and trash") with the two trash retention properties.
  - A real-database test that saves one item several times as one user sets
    `revisions.coalesce.minutes` to 0 (`TestUtils.setRuntimeProperty`) unless
    coalescing is what it tests.
- **Nav order**: `navOrder` is one 0-based sequence per blog (drafts and
  non-nav pages included). The Pages screen reorders it with up/down buttons
  and a drag handle (`pressroom-reorder.js`, shared with the strand Parts
  panel, parameterised by selector), posting repeated `pageIds` to
  `pagesOrder.rol` -> `WeblogPageManager.setNavOrder`, which numbers only this
  blog's own named pages (stale, foreign, repeated ids skipped), lets unnamed
  pages follow in their prior order, and bumps `lastModified`. The ids ride in
  each row's hidden input with `form="pagesOrderForm"` because the rows sit
  inside `#pageRemoveForm` (forms do not nest). Every row has the controls
  whatever its status or nav flag. After a move the visible `navOrder` cells
  are renumbered by the page script to the rows' positions (what Save order
  stores), and the Save order button shows only once the order differs from
  the loaded one. `pagesOrder.rol` is POST-only (GET is a 405), needs the
  Pages screen's permission, and an empty or missing id list is a no-op with
  no error. Slug and sibling fields are untouched by a reorder. The API
  refuses a negative `navOrder` (400 on the field).
- **Front door**: a blog may make one published page its root
  (`weblog.front_page_id`, V049; chosen in the blog settings under "Order
  posts", `WeblogConfig.jsp`, which offers only this blog's published pages,
  titles through `fn:escapeXml`, never for the site space). Active exactly
  when set. The page manager keeps it truthful at the write: `savePage` with
  any status but PUBLISHED, `removePage` and `removePages` clear it in the
  same save (in the managed weblog too) and bump `lastModified`;
  **republishing does not restore it**, the author sets it again. Routing,
  `/posts`, canonical and sitemap rules: `routing.md`, "A blog's front door";
  the nav's leading "All" reads "Posts" and the front page is not a nav item
  (`themes-and-templates.md`); the API field is `frontPage` (`api.md`). The
  Pages screen marks the front page ("Home page" beside its title) and its
  delete prompt says the post list returns to the root. **Every link to the front page is the blog root**,
  not its slug: the nav, a strand's bar and "All parts" step (a strand page
  may be the front door: one address for a tutorial's "start here"), the
  Pages screen's view link and the privacy link (`themes-and-templates.md`,
  "Every link to a front page is the blog root"). A `noindex` page, or one
  whose canonical points elsewhere, may be chosen (the author's call); the
  setting's help says that its SEO settings then apply to the blog root.
  Removing the whole weblog clears the id with its pages
  (`FrontPageInvariantTest`). Saved pages also enter the search index
  (`architecture.md`, "Document kinds"): PUBLISHED and not `noindex`.
- **A new page with no slug takes it from its title** (0.12.1,
  `WeblogPage.slugFromTitle`: runs of letters and digits, lower-cased, joined
  by hyphens), in the editor and on an API create alike; the derived slug
  meets the rules a typed one does. An existing page's emptied slug is still
  an error, never a silent rename.
- **`Pages.jsp` has a real `<thead>`** (its body rows reorder), so the
  caps-label header rule in `pressroom.css` styles `thead` there and the
  first body row only in tables with no `thead` (`:not(:has(> thead))`).
  0.12.0 shipped the first page styled as a header (`PagesJspTest`).
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
