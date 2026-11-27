# Admin UI

Developer notes moved out of `CLAUDE.md` (which is sent with every request) so they are read only when this
area is being changed. Section names the older "see CLAUDE.md, <section>" pointers use are kept.

The admin is a React app (`app/frontend/src/admin`) served by one JSP shell. Spec:
`docs/superpowers/specs/2026-11-25-react-admin-migration-design.md`. Every admin screen is React since the wave 5
retirement; what is still server-rendered is listed under "What stays JSP, and why".

## Admin UI

### Structure

- **The shell** is `WEB-INF/jsps/app/Shell.jsp`, served by `AppShellController` for any path under
  `/pressroom-ui/app/` (so a React route needs no server mapping). It loads the stylesheets in this order:
  Bootstrap, Bootstrap Icons, `pressroom-tokens.css`, `pressroom.css`, then `pressroom-editor.css` (the editor
  surface's layout, which the JSP-era `head.jsp` loaded only on the editor screens; the shell is one page for all of
  them, and `AppShellJspTest` pins the order), one JSON bootstrap block (`AppBootstrap`, escaped for a script
  element in Java) and `pressroom-ui/app/admin.js?v=<version>`. No Bootstrap JS: dropdowns, collapses and modals are
  React state.
- **Source layout** (`app/frontend/src/admin`): `router.tsx` (the route table, `createBrowserRouter` under
  `appBasename(contextPath)`, so no code assumes a context path), `routes/` (`railTable.ts`, `visibility.ts`,
  `RequireAdmin`, `siteRoutes.tsx`), `frame/` (banner, rail, footer, flash and session-expiry bars), `screens/`
  (one file or folder per screen; `people/`, `pages/`, `media/`, `traffic/` are groups), `components/` (shared
  widgets), `editor/` (the writing surface), `api/` (the typed client and TanStack Query hooks; `schema.d.ts` is
  generated from `openapi.json`), `lib/`, `forms/`, `i18n/`, `session/`.
- **Routes** (all under `/pressroom-ui/app`): `/` (main menu), `/profile`, `/profile/tokens`, `/weblogs/new`,
  `/weblogs/:handle/{entries,entries/new,entries/:id,trash,comments,categories,media,media/upload,media/:id,pages,
  pages/new,pages/trash,pages/:id,settings,theme,remove,templates,templates/:id,stylesheet,members,inbox,traffic}`
  and `/admin/{config,setup,businesses,businesses/new,businesses/:id,maintenance,traffic,users,users/new,
  users/:userName,agents/new,agents/:userName,tokens}`. The site space is `weblogs/site-space/{pages,media,theme}`.
  Scopes (`none`, `weblog`, `admin`) say what the frame loads and shows.
- **One route table for the rail**: `routes/railTable.ts` holds the groups and entries (copied from
  `editor-menu.xml` / `admin-menu.xml`; `rail.test.ts` parses the XML and fails on drift, so the XML stays while the
  Velocity theme API `showAuthorMenu` / `MenuModel` still reads it). `routes/visibility.ts` applies the menu rules
  from `/v1/me` (`globalAdmin`, membership level) and `/v1/meta` (`features`).
- **Not on `/api`, so in the bootstrap block**: `site.name`, `site.shortName`, `site.siteSpaceHandle` (the API never
  serves the site space; on that handle the frame fetches no weblog and the rail shows only Pages, Media files and
  Theme), `sessionTimeoutSeconds` and `colophon` (what `footer.jsp` prints, from `Colophon.of`).
- **`t(key, ...args)`** (`i18n/`): no arguments returns the value verbatim, arguments run MessageFormat, exactly
  like the server's `alwaysUseMessageFormat=false`. Bundle values may carry HTML entities (`tabbedmenu.weblog` is
  `Create &amp; edit`): decode with `plainText`. New message keys go into all eight bundles (English until
  translated); a key composed at run time is listed whole in a header comment of its file (`MessageKeyTest`).
- **Drafts** live under `pressroom.appdraft.v1:`; signing out clears them (`clearAllDrafts`, and the
  `pressroom.js` logout handler for the pages that stay JSP).
- `dangerouslySetInnerHTML` is lint-forbidden outside `DANGEROUS_HTML_ALLOWLIST` (`eslint.config.js`, empty).

### Shared pieces (use these, do not re-derive)

- **`components/Markup`**: the one renderer of a bundle message that carries its own light markup (`b`, `i`,
  `span`, `p`, and `a` with an http(s) or path `href`; everything else is text). No `dangerouslySetInnerHTML`; a
  `<script>` in an argument stays text. Escaping is therefore the renderer's job, not the caller's.
- **`session/flash`** (`useFlash`, `flashState`): a screen hands the next one a one-line message through router
  state (`navigate(to, { state: flashState('...') })`); the receiving screen shows `useFlash()`.
- **`lib/bulk`** (`runBulk`, `BULK_CONCURRENCY = 4`): a bulk action is one API call per item, four at a time,
  never stopping at a failure; the failures come back with their errors so the screen says which and why in place.
  `components/BulkProgress` and `components/SelectionBar` are its UI (the `.selection-bar` grammar: hidden until a
  row is checked, the header checkbox excluded from the count).
- **`lib/permission`** (`useWeblogRights`, `RequireLevel`) and **`routes/RequireAdmin`**: what the server's
  `requiredWeblogPermissionActions` / `requiredGlobalPermissionActions` did for the JSP controllers. A screen needing
  a weblog level wraps itself in `RequireLevel level handle`; a site-admin screen sits behind `RequireAdmin`. Both
  render the not-found treatment (or nothing and fetch nothing) for anyone else, as the API answers 404 for what the
  caller may not see. The server is still the authority; this is what the person is shown.
- **`components/`**: `ConfirmDialog` and `FormDialog` (every confirmation; there is no `confirm()`), `ProblemBanner`
  (an API problem in place, with `forms/useFieldErrors` marking the fields it names), `EmptyState`
  (`.empty-state`: one sentence, at most one primary action), `StatusPill` (`.status-pill.status-<state>`, the one
  pill for every entry and page state, plus the `unsaved` state of a new entry), `DateTime`, `Pager`,
  `PasswordFields` (show/hide and the "match" line).
- **`screens/media/MediaChooser`**: `<MediaChooser handle imagesOnly? onSelect(choice) onCancel />`,
  `choice = {id, name, url, isImage, file}`; `imagesOnly` lists images outside private folders (what featured and
  share images accept). It does not close itself; the host unmounts it in `onSelect` or `onCancel`.
- **`editor/CodeMirrorField` + `editor/EditorSurface`**: the Markdown surface the entry and page editors share
  (`kind="page"` picks the page preview routes): toolbar, write/split/preview with the weblog's theme in the frame,
  media insert, shortcodes (`shortcodeCards.ts`), word count and reading time. A page has one save, so Ctrl+S and
  Ctrl+Enter both save. `CodeMirrorField` wraps `src/editor.js`, which also builds the highlighter; there is no
  separate editor bundle any more. **Test seams** the browser suite names (`it/support/Editor.java`):
  `window.pressroomGetEntryText`, `window.pressroomSetEntryText` (`CodeMirrorField`) and
  `window.pressroomUploadImages` (`EditorSurface`). They are production code; do not remove or rename them.
- **`api/upload`**: one multipart XHR per file (fetch has no upload progress), two at a time, each with its own
  progress bar and its own refusal.

### Writing a screen

- **The server says what the screen shows**: the weblog address is `WeblogView.absoluteUrl` (never site url +
  handle), page addresses come from the page view, the front-door note from `frontDoor`. A gap the API cannot
  fill is an ordinary API change (test, OpenAPI snapshot, matrix row, MCP decision or `Exclusion`,
  `docs/api/README.md`), never a `.rol` call.
- **Test first, in Vitest** (MSW + the real bundle; `test/admin/support.tsx`, and for the people screens the one
  harness `test/admin/people/harness.tsx`): each action's success path, its refused path (problem in place), and
  one test per role difference. Vitest runs jsdom at the app's own origin (`vitest.config.ts`).
- **A session read of `/api` needs no CSRF header, a write does** (the client sets it). An unauthenticated call is
  a 401 the session-expired bar handles; the signed-in session's warning bar (`#sessionExpiringBar`) appears two
  minutes before the container's timeout and never for a session of three minutes or less.
- **A token's secret lives in component state only** (`people/tokens.tsx`, `TokenReveal`); writes go through
  `useAction`, not a TanStack mutation (whose result stays in the cache for minutes). Tests assert the secret is in
  no storage and no cache after Done.
- **Never restyle by renaming a selector.** Every route's content must keep emitting the CSS marker `Routes` pins
  for it (`it-selenium/.../support/Routes.java`); `RouteSweepIT` asserts it on every route. Update both in one
  commit.
- **Design system**: `docs/design/design-system.md` ("Quiet Instrument"). Tokens live in
  `pressroom-ui/styles/pressroom-tokens.css` (light under `:root`, dark under `prefers-color-scheme`, self-hosted
  IBM Plex), loaded after Bootstrap and before `pressroom.css`; `DesignTokenTest` enforces the ordering, that every
  hex literal traces to the spec's values, and that light and dark define the same tokens.
  Buttons theme through Bootstrap's `--bs-btn-*` custom properties, never our own `:hover`/`:active` rules.
  `.form-stacked` on a form puts labels above the fields. Sidebar blocks are `.sidebar-group` with a
  `.sidebar-label`, not a Bootstrap card.

### Cutover checklist (migrating a screen; wave 5 retired the server UI with it)

1. **Read the old screen, its controller and view model first.** The React screen keeps the ids, classes and
   message keys of the markup it replaces (ITs, CSS and `Routes` select on them). Logic the view model did
   server-side is ported to a pure TS module with unit tests (`screens/traffic/model.ts`).
2. **Test first in Vitest**, then the route (`router.tsx`, scope) and the rail entry's `path` in `railTable.ts`.
3. **`LegacyAdminRedirects`**: add a row for each old GET route (legacy path, React route under
   `/pressroom-ui/app`, `{placeholder}` to request-parameter map, carried query parameters; `old>new` renames one;
   `otherwise` gives the route when a path parameter is missing) and a test per row in `LegacyAdminRedirectsTest`.
   `LegacyAdminRedirectFilter` (order 74, after Spring Security) answers 302, so a signed-out bookmark signs in and
   arrives at the new screen. **These rows stay for good: old bookmarks, emails and the legacy `.rol` links keep
   working.** A route is redirected or handled by a controller, never both
   (`noRedirectedRouteIsStillHandledByAController`).
4. **Delete after grepping every reference** (JSPs, Java, `.vm` under `themes` and `WEB-INF/velocity`, tests, CSS
   comments): the JSP, controller, view model, `PressroomViewResolver` definitions, `view-definitions.golden`
   blocks, JSP-scanning tests, and the message keys `MessageKeyTest` reports.
5. **Browser ITs at both context paths**: add the screen to `Routes.APP` (a marker only the screen emits), remove
   the old `.rol` entries (`RouteCoverageTest` demands it), port the ITs that drove the old screen, extend
   `LegacyAdminRedirectIT`, run `RouteSweepIT` and the touched classes at `/` and with
   `-Dit.context.path=pressroom` (`docs/dev/browser-its.md`).

### LegacyAdminRedirects

`ui/core/filters/LegacyAdminRedirects` (the table), `LegacyAdminRedirectFilter` (the 302), `MenuHelper.appPath`
(reads the same table). Covers every former admin `.rol` GET: menu, traffic, site traffic, weblog settings, theme,
templates, stylesheet, members, submissions, remove, pages, media, entries, trash, comments, categories, global
config, setup, businesses, maintenance, tokens, users, agents, profile. The redirect carries the query parameters
the table names and drops the rest (the old `page` number is dropped: first page).

### What stays JSP, and why

Login, forgot/reset password, the installer and database pages (`InstallStart`, `CreateDatabase`,
`UpgradeDatabase`, `Bootstrap`, `DatabaseError`: they run before the business tier exists, so there is no API to
call), `GenericError`, `SiteDirectory` (public), the reader `Account` page (a signed-in reader holds no admin
authority, so the admin API refuses them), the shell, and the tiles they render with: `.tiles-loginpage`,
`.tiles-installpage`, `.tiles-errorpage`, `.tiles-simplepage` and the fragments `head`, `bannerStatus`,
`bannerInstallation`, `messages`, `footer`, `empty`, `passwordFields`. `PressroomViewResolver` registers exactly
those. `theme/scripts/pressroom.js` keeps the cookie helpers the sign-in page uses, the `data-confirm` handler the
Account page uses, and the sign-out draft clear. The rules that apply to them are in `CLAUDE.md` (escaping,
`data-confirm`, undeclared taglib prefixes) and in `JspConsistencyTest`, `AdminJspHygieneTest` and
`MessageArgumentEscapingTest`, which scan the JSPs that remain.

**Password fields** (sign-in, reset, installer; the React `PasswordFields` does the same for the admin): a field
opts in by markup, `data-reveal` on the input (it needs an `id`; on `<form:password>` write
`data-reveal="true"`, as Spring drops an empty dynamic attribute), a confirm field adds
`data-match="<id of the new-password field>"`, and the page includes `tiles/passwordFields.jsp`, the one place
`theme/scripts/pressroom-password.js` and its four `passwordField.*` labels are written. Wrap the input in
`<div class="input-group">`; the script never moves it (moving it drops focus). A revealed field goes back to
`type="password"` on submit. Tests: `AdminJspHygieneTest`, `frontend/test/password-fields.test.mjs`,
`PasswordFieldsIT`.

### Rules that outlived the JSPs

- **Ownership-check every id.** The permission interceptor only vouches for the *action* weblog, so a global by-id
  lookup lets any editor rewrite any weblog's data. Every by-id lookup goes through `WeblogOwnership` (`entry`,
  `page`, `trashedPage`, `template`, `comment`, `formSubmission`, `mediaFile` (a file's weblog is its directory's),
  `publicImage`) or `BaseController.lookup*`'s successors in the API's base classes; a blank id is absent, a lookup
  that throws reads as absent, and the raw id is never logged. A foreign `moveTo` or `targetCategoryId` re-files
  entries into someone else's blog, so it needs the check too.
- **An admin cannot lock themselves out**: on their own account the person editor refuses unticking Administrator
  (`userAdmin.cantChangeOwnRole`) and Enabled (`userAdmin.error.cantDisableSelf`), checked in `AdminApi.patchUser`
  (`AccountRules.selfLockoutProblems`) before anything on the form is applied.
- **Admin > Users is a list**: every account, All / People / Agents tabs and a `q` filter the server applies
  (`UserManager.getAccounts`). `UserDataServlet` is gone (it let any editor list every account).
- **Global Config validates `site.privacy.page`** as a page reference (`PageReference`): `slug` is a published site
  page, `handle/slug` a published page on that blog. Checked by `SiteSettingsRules` (shared with
  `PATCH /api/v1/admin/config`) only when the value changed; an unknown or draft page, or a lookup that throws,
  refuses the save (fail closed). A page unpublished later never blocks an unrelated save. A change to the slug,
  the front door, the privacy page or `site.absoluteurl` touches every weblog and the site space and clears the
  site-wide cache (`themes-and-templates.md`).
- **The front-page select** (`site.frontpage.weblog.handle`) offers the site directory (empty), an optgroup of
  published site pages (`page:<slug>`, stored as `site.frontpage.page`, blanking the handle) and the blogs (never
  the site space). With no published site page it shows a hint linking the site space's React Pages route. Setting
  both is refused (`configForm.error.frontpage.bothSet`).
- **Site settings rules live in `ui.controllers`**: `SiteSettingsRules` judges values (type and range via
  `PropertyType.check`, the front door, the privacy page) and returns `RuleViolation`s; `SiteSettingsEffects.afterSave`
  touches every weblog when a `RENDERED_PAGE_INPUTS` value changed. Error order is pinned by
  `AdminConfigCharacterisationTest`.
- **The Site section (global admins only).** `GET /v1/me/weblogs` returns `siteSpace`, creating the hidden
  `site-space` weblog on first read (absent for everyone else); the main menu shows a Site row with Pages, Media and
  Theme, the only three screens that accept the site space. `UISecurityEnforced.allowsSiteSpace()` is false by
  default and true on `PagesApi` and the media API; `PressroomHandlerInterceptor.siteSpaceOffered` clears the action
  weblog for anything else, so a refusal looks exactly like an unknown handle (API 404). It offers the site space
  only to a **global admin's interactive session** (form login, remember-me or reader SSO: `Caller.Kind.SESSION`),
  reads and writes alike, never to a token, a password caller, an anonymous or an unrecognised caller.
  `SharedThemeRules.offered` gives it `journal`, `portfolio`, `travel` and `frontpage`, never a custom theme. The
  React Theme screen reaches it through `GET`/`PUT /v1/admin/site-theme` (session only), Global Config reads its
  pages through `GET /v1/admin/site-pages` (admin-scoped, token-readable, read-only), its traffic through
  `GET /v1/admin/traffic/{handle}`. Covered by `SiteSpaceAccessTest`, `AdminSiteThemeApiTest`, `SitePagesIT`.
- **A message argument reaches the reader UNESCAPED on the JSPs that remain**: `tiles/messages.jsp` renders the
  message bare, so the controller is the escaping boundary (`StringEscapeUtils.escapeHtml4` on every user-typed
  argument; `MessageArgumentEscapingTest` is the ratchet). The React screens render messages through `Markup`,
  which escapes.
- **Businesses** (`/admin/businesses`): list, create/edit (`/new`, `/:id`) and delete behind a confirmation
  (refused with `business.inUse` while any weblog uses it); `Routes` pins `#businesses-list-marker` and the create
  form's `#bean_name`. Blog owners choose a business in Settings, section `#settings-business`. Saving a business
  touches `lastModified` of every weblog using it (content-features.md).
- **Copyright**: Settings carries a per-blog `copyright` (V038, plain text, 255 max, blank stored as null so the
  footer falls back to "(c) year blog name"); `WeblogSettingsRules` bounds it; `/api/v1` mirrors `tagline`.
- **Footer (colophon)**: `tiles/footer.jsp` (and the React frame) render one `Colophon.of(...)` record (holder =
  `site.name` on admin screens): `div.colophon` > `p.colophon-copyright`, `p.colophon-meta` (privacy link,
  `Pressroom <version> (<revision>)`, `released <date>`, each a span, each omitted when null).
- **Maintenance** is a Global Admin screen: its three per-weblog actions (flush cache, rebuild index, regenerate
  renditions) pick a weblog; Rebuild index is absent while `search.enabled` is off (`/v1/meta`
  `features.searchEnabled`).
- **`URLUtilities.getQueryString` URL-encodes both key and value**; every caller passes RAW values (pre-encoding
  double-encodes).
- The Blogger XML-RPC API and `weblog.bloggercatid` are gone (`V023__drop_w2_fossils.sql`).

### Per-wave notes

- **Wave 1 (entries, editor, trash, comments, categories)**: `/weblogs/:handle/entries` filters `status category
  tags text startDate endDate sortBy offset`; `entries/new?mediaId=` seeds an entry from media files; comments
  take `?status=PENDING|PUBLISHED|HIDDEN`, `?entry=<id>`, `offset`, and `GET .../comments` returns `total` and takes
  `topLevel=true`. Emptying the publish time sends `clear: ["pubTime"]`. The category dialogs are one
  `CategoryDialog` (POST or PATCH) and `RemoveDialog` (a category in use needs a `moveTo`). `editor.mode.*` labels
  are composed; the keys are listed in `EditorSurface.tsx`'s header.
- **Wave 2 (pages and media)**: the strand "Add posts" control is a search over the entries `text` search (20
  results); the page trash shows the retention from `meta.pageTrashRetentionDays`; uploads share description,
  copyright and tags by PATCH afterwards; search sends `q`, `type`, `largerThan` (bytes), `tags`; Delete folder is
  offered for an empty, non-default folder; Replace file (`PUT .../media/{id}/content`) is sent first on Save;
  Crop loads the Cropper.js v2 webjar on demand (`crop.ts`).
- **Wave 3 (settings, create, theme, templates, stylesheet, members, inbox, remove)**: `subscribeForm` is
  `on`/`offNoList`/`offNotConfigured`, `customDomainOutsideCertZone` is the "no wildcard certificate" warning,
  `GET /v1/meta` carries `maxEntriesPerPage`; create-weblog prefills from `GET /v1/me/profile`. The theme preview is
  not an admin screen and stays (`/pressroom-ui/authoring/preview/{handle}?theme=`, `ServletRegistrationConfigTest`).
  Template action labels are `ComponentType`'s names copied into `Templates.tsx` (`TemplateActionNamesDriftTest`).
- **Wave 4 (people and site screens)**: `RequireAdmin` guards the admin routes; `UserView` carries `weblogCount` and
  `lastActive`; the create form offers the emailed link only while `features.mailConfigured`; Profile hides the
  password fields for a single-sign-on account (`ProfileView.sso`); Global Config saves only the keys that
  changed; Setup counts users and weblogs over every page of the list routes; the ITs reach Global Config through
  `PressroomIT.openGlobalConfig` and `clickAndAwaitSavedMessage` (a React save is an API call, so the barrier is the
  success message, not a new page).
- **Wave 5 (retirement)**: deleted the tabbed and main-menu layouts and their menu-model include, `userStatus`,
  the editor JSPs (`EditorSurface`, `EditorScript`, `StatusPill`, `SessionExpiryBar`), the standalone editor bundle
  and the admin-only scripts (`pressroom-draft.js`, `-guard-submit`, `-reorder`, `-strand-parts`), the weblog
  switcher and menu plumbing in `BaseController`, and the JSP-only tests. The shell now loads
  `pressroom-editor.css`.

## Agent accounts and API tokens

Spec `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`; cards
`docs/design/tables/tables-users.html`, `forms/forms-agent-page.html`, `forms/forms-token-reveal.html`.

- **The agent page** (`/pressroom-ui/app/admin/agents/:userName`): details, one confirmed Disable/Enable,
  the Weblogs section and the token section. No password field and no set-password link; the person page
  sends an agent there, and the API refuses a person-only change on one (`user.notAPerson`).
- **The Weblogs section** (`screens/people/UserWeblogs.tsx` over `UserWeblogsApi`) is on a person's page and an
  agent's page alike, with the Members page's rules: a weblog keeps one Admin, nothing is granted
  while `groupblogging.enabled` is off (the section says so), a reader is never granted. Its weblog
  field is `handle` and the token form's is `pin`: a field named `weblog` is read by
  `PressroomHandlerInterceptor` as the action weblog.
- **Both membership screens share `Memberships`** (2026-11): the weblog's Members page
  (now the React Members screen over `MembersApi`) accepts only `Memberships.LEVELS` (Admin, Author, Limited; `-1`
  removes) from `perm-<id>` and `permissionString`, refusing the whole save otherwise
  (`userWeblogs.error.level`), and records `membership.granted|changed|removed` through
  `AdminLog` exactly as `UserWeblogsApi` does. A permission row whose account no
  longer resolves (deleted or disabled; the page shows it no control) is skipped, not an
  NPE that failed every other change in the submission.
- **The membership decisions live in `Memberships` too** (2026-11-21, spec
  `2026-11-20-jsp-rules-and-view-models-design.md`, Task 2): `levelProblem(value, removalAllowed)`
  (`REMOVE` is `-1`), `mayHold(users, account)` (a reader holds no login action), `groupBloggingOff()`,
  `selfChangeProblem` (Members page) and `leavesNoAdmin` in two shapes: the Members table's posted
  levels (a row that posted nothing is not counted) and one change on a person's page (counts from
  the store only when the change takes Admin from an admin; a count that throws refuses). They answer
  in message keys and booleans, each screen keeping its own wording (`memberPermissions.error.
  groupBloggingDisabled` vs `userWeblogs.groupBloggingOff`); no problem codes until Tier 4 gives
  members an API. Neither controller counts admins itself.
- **The account rules live in `AccountRules`** (same task; `admin.UserNames` is gone): username
  (present, 255, `username.allowedChars`, taken in any case; `newUserNameProblem` throws when the
  taken-check cannot run; both create screens log it and refuse with a page-level
  `generic.error.check.logs`, no field marked), email (`EmailValidator`, trimmed, 255;
  `EmailUse` REQUIRED / REPLACEMENT / OPTIONAL), screen name (not blank, 255) and the password key
  (`PasswordRules`). The screens show a violation with `BaseController.addFieldError(model, id,
  violation, request)` (arguments escaped; these carry none). The full-name requirement and the
  agent's name/notes checks stay in their controllers.
- **Column limits on the create screens** (2026-11-21, spec
  `2026-11-20-jsp-rules-and-view-models-design.md`, Task 2): the new-person and new-agent forms
  refuse a username, screen name or email address over 255 characters on its own field
  (`personForm.error.userNameTooLong`, `screenNameTooLong`, `emailTooLong`), the answer the API
  already gave; an over-long value used to reach the store and fail there as a generic error. The
  username's length is judged before its characters, as the API judges it. The Weblogs section's
  grant and change answer a missing `level` with `userWeblogs.error.level`; they used to throw
  (`List.contains(null)`) and fail as a 500. The edit-person form still judges neither the address
  nor the screen name (pinned in `AccountScreensCharacterisationTest`; a follow-up).
- **Category, folder and file names** (2026-11-21, spec `2026-11-20-jsp-rules-and-view-models-design.md`,
  Task 3): a category name that is blank or over 255 characters is refused on `category_bean_name`
  (`categoryForm.error.invalidName`, `categoryForm.error.nameTooLong`; both were saved before). A folder
  name is trimmed and loses one leading slash (it used to be refused), then `default`
  (`mediaFile.error.view.dirNameReserved`) and over 255 characters (`dirNameTooLong`) are refused on
  `newDirectoryName`; an inner slash and a taken name keep their unmarked messages. A file rename that is
  blank or over 255 characters is refused on `mfedit_bean_name` (`MediaFile.error.nameBlank`,
  `nameTooLong`); a taken name keeps `MediaFile.error.duplicateName`, unmarked. New keys go into the
  bundles that carry their neighbours (English until translated). `escapeHtml4` leaves an apostrophe
  alone, so a category name may hold one on both surfaces. The rules run in the spec's order (blank,
  length, escaping, taken), so a name both over 255 characters and holding `&` answers
  `categoryForm.error.nameTooLong`, where the screen used to answer `invalidName`. A whitespace-only
  folder name is `mediaFile.error.view.dirNameEmpty` (it used to create a folder named by the spaces).
  The category screens also refuse a description over 255 characters on `category_bean_description`
  (`categoryForm.error.descriptionTooLong`, `CategoryRules.descriptionProblem`, as the API does; final
  review M4); it used to overflow the column and answer "check the logs".
  A category edit that sends back the category's stored name is not judged again
  (`CategoryRules.keepsItsName`, the same predicate as the API's PATCH), so a pre-0.16.0 name holding `&`
  stays editable; a changed name, a case-only change included, meets every rule. An edit of an id this
  weblog does not own answers `categoryForm.error.notFound` before the name is judged.
- **A category name in a flash is escaped** (final review, 0.16.0): the created, saved and removed
  messages and the duplicate-name error pass the name through `escapeHtml4`, because
  `tiles/messages.jsp` renders messages bare and the API accepted any name before 0.16.0.
  `WeblogCategory.setName` strips whole tags but keeps a `<` never closed, so a stored
  `<img src=x onerror=alert(1) ` became an image with a handler in the "deleted" banner. A valid name is
  a fixed point of `escapeHtml4`, so nothing visible changes for one; `MessageArgumentEscapingTest` no
  longer allowlists `category.getName()`. The set-password-link banner escapes the stored address the
  same way.
- **Page slugs** (2026-11-21, spec `2026-11-20-jsp-rules-and-view-models-design.md`, Task 4): the page
  editor refuses a slug a live page of the same weblog holds on `page_bean_slug` (`pageEdit.error.slugTaken`;
  it used to reach the unique index and answer "check the logs") and a slug over 255 characters
  (`pageEdit.error.slugTooLong`; likewise). A slug held by a trashed page keeps `slugInTrash`. The slug is
  judged before the save, so a bad slug beside another field error shows both (the manager's refusal used to
  surface only when nothing else had erred).
- **The token form, reveal and revoke are `screens/people/tokens.tsx`**, shared by the agent page, Profile ->
  API tokens and (the list only) Admin -> API tokens. The secret is a prop of the reveal component and lives
  in component state alone: no URL, no flash, no storage, no query cache (the mint runs through `useAction`,
  not a TanStack mutation); Done, navigating away or a reload forgets it, and a reload shows the token list.
  The API's JSON mint response body is the secret itself and Spring would write it at DEBUG. Keep
  `org.springframework` at INFO in production.
- **`revokeToken` answers a `RevokeOutcome`, never a boolean**: `REVOKED` (now, or already was),
  `NOT_FOUND` (an unknown id, or a token outside the required owner) and `FAILED` (the store threw).
  Revoke is the safety action, so a store failure is never reported as "no such token": the admin is
  told to check the logs while the token may still be live. A new caller keeps the three cases apart.
- **The last sign-in** is stamped by `LastLoginRecorder`, an `InteractiveAuthenticationSuccessEvent`
  listener: form login, remember-me and SSO publish that event and HTTP Basic does not, so an API call
  is not a sign-in. An agent's "last active" is its newest token use instead.
- **New message keys go into all eight bundles** (English until translated);
  `AgentAccountMessagesTest` checks the `userList.`, `personForm.`, `userWeblogs.`, `apiTokens.` and
  `agent.` prefixes.

## Needs-attention counts (0.18.0)

`GET /v1/me/weblogs` rows carry `pendingComments`, `newInquiries`, `drafts`, `scheduled` and `lastPublished`. The home
(`Home.tsx`, ordered by `homeOrder.ts`) and the rail badges (`Frame` hands `Rail` the row for the current handle; no
extra request, same `['me','weblogs']` cache entry) read them. Whatever changes a count invalidates that key:
comment moderation (`Comments.tsx` `refresh`) and `useMarkSubmissionsRead`. The inbox marks the page's unread rows
read on load (shared `read_at`) and invalidates only `['me','weblogs']`, never the open list, so the "New" pills stay
until the next visit. A test that renders `Frame` must stub `/v1/me/weblogs` (the people harness does).

### The comment queue (0.18.0)

`/comments` (`CommentQueue.tsx`, scope `none`, `HomeSidebar`) lists PENDING comments across every blog the caller may
moderate (`GET /v1/me/comments`, session-only; `useCommentQueue`, key `['commentQueue', status, offset]`). Each row
names its blog and acts through the per-blog `PATCH`/`DELETE` with `weblog.handle`; afterwards it invalidates
`['commentQueue']`, `['comments', handle]` and `['me','weblogs']`. `Routes` pins `#comment-queue-marker`. The home
sidebar's Comments link and the home summary line's "Review all" lead here; the sidebar link shows only when some
row has `canModerate`. Approve, Hide and Delete are per row (no bulk, no reply: those stay on the blog's own screen).
