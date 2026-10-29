# Admin UI

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Admin UI
- **Maintenance is a Global Admin screen, not a per-weblog one.**
  `MaintenanceController`/`Maintenance.jsp` live under
  `ui/controllers/admin`/`jsps/admin` at `/roller-ui/admin/maintenance.rol`,
  listed in `admin-menu.xml` (`globalPerms="admin"`), not `editor-menu.xml`.
  Its three per-weblog actions (flush cache, rebuild index, regenerate
  renditions) pick a weblog in a `<select>`.
- **Design system**: `docs/design/design-system.md` is the committed spec
  ("Quiet Instrument"). Tokens live in `roller-ui/styles/roller-tokens.css`
  (light under `:root`, dark under `@media (prefers-color-scheme: dark)`,
  self-hosted IBM Plex `@font-face`), linked in `head.jsp` *after*
  `bootstrap.min.css` and *before* `roller.css` (each layer overriding the
  last). `DesignTokenTest` enforces that ordering, every hex literal tracing
  to the spec's 21 values, and light/dark defining the same tokens.
- **Never restyle by renaming a selector.** Every admin route's content tile
  must keep emitting the CSS marker `Routes` pins for it in
  `it-selenium/.../support/Routes.java`; `RouteSweepIT` asserts it on every
  route, because full chrome (banner, nav, footer,
  `<h2 class="roller-page-title">`) renders even with no content tile — the
  `categoryEdit.rol` failure mode: a healthy 200 with no form. Update `Routes`
  in the same commit as the CSS.
- **The tiles system** is homegrown, not Apache Tiles: `ViewDefinition`
  (layout JSP + attribute JSPs such as `content`, `menu`) is resolved by
  `PressroomViewResolver`, which registers eight base layouts in `init()`:
  `.tiles-mainmenupage`, `.tiles-tabbedpage`, `.tiles-simplepage`,
  `.tiles-loginpage`, `.tiles-installpage`, `.tiles-errorpage`,
  `.tiles-popuppage`, `.tiles-barepage`. `.tiles-barepage` (content, no head)
  exists only for `.MediaFileEditSuccess`, which calls
  `parent.onEditSuccess()` and dies milliseconds later, aborting a full head's
  webfont fetch on every rename (the `GalleryIT` "font ERR_ABORTED" flake).
  Never reuse it for a page a human reads.
  `tiles-tabbedpage.jsp`/`tiles-mainmenupage.jsp` render `#adminRail`
  (`navMenu` tool groups under caps-labels, `.rail-active` on the current
  tab), smoke-tested on the Entries route by
  `RouteSweepIT.adminRailIsPresentWithAnActiveSpineOnATabbedPage`.
- **A confirmation prompt is `data-confirm`, never an inline
  `onclick="return confirm('...')"` — the inline form FAILS OPEN.**
  `fn:escapeXml` renders an apostrophe as `&#039;`, which the HTML parser
  decodes to `'` *before* the JS compiles, so one apostrophe
  (`o'brien@example.com`) breaks the handler and the click proceeds
  unconfirmed; an attribute has no second parser, so `dataset.confirm` gets
  the literal text. In `theme/scripts/roller.js`, `click` owns `data-confirm`
  on a *control* (a `formaction` button, a link), `submit` owns it on the
  *form* (catching Enter in a text field), and the click handler's ancestor
  walk stops at the form or both prompt (shipped once on `UserEdit`).
  **`data-confirm` on a `<form>` is the one legitimate case** —
  `Members.jsp`'s removal confirm depends on radios across the whole table,
  beyond any single control. `data-confirm-when` names a CSS selector; the
  submit handler prompts only when `form.querySelector(selector)` matches
  (`!when || matches`, so without it a form-level `data-confirm` prompts
  unconditionally). `data-confirm`(`-when`) on `click` or `submit` is the
  **only** confirm idiom: `JspConsistencyTest.oneConfirmIdiomAndOneModalShape`
  bans `confirm(` and `onsubmit=` in every JSP, so an inline
  `window.confirm()` fails the build.
- **Buttons theme through Bootstrap's `--bs-btn-*` custom properties**
  (`--bs-btn-hover-bg`, `--bs-btn-active-bg`, `--bs-btn-disabled-bg`, …),
  never our own `:hover`/`:active` rules — Bootstrap's
  `:active`/`.active`/`.show` chain reaches `(0,3,0)` specificity and beats a
  classed override. Per-bucket (primary/secondary/destructive) variables cover
  hover→active→disabled in one place.
- **`.form-stacked`** on a `<form>` converts Bootstrap's
  `row.mb-3 > label.col-sm-3 + div.col-sm-9` grid to labels-above block flow
  without touching fields (`WeblogConfig.jsp`, `GlobalConfig.jsp`, ten more).
- **`.empty-state`/`.empty-state-title`/`.empty-state-body`** are the
  "invitations, not shrugs" signature (600/16px title, one `--ink-soft`
  sentence, at most one primary action, icon-free) on Entries/Pages/
  Submissions/MediaFileView. **`Categories.jsp` renders it INSIDE the table**
  as the lone `<tr>` of an empty tbody, making its `<td>` the tbody's
  first-child (the hook the header's caps-label rule keys off), so
  `.empty-state`'s `font-weight`/`text-transform`/`letter-spacing` resets are
  load-bearing. `Pages.jsp`/`Submissions.jsp` render it as a sibling of the
  table in a `<c:otherwise>`; check the caller.
- **Admin → Users is a list, not a search box** (2026-10-13): every account,
  All / People / Agents tabs and a `q` filter the server applies
  (`UserManager.getAccounts`, case-insensitive over username, names and email).
  `ajax-user.js` went with the search box; `UserDataServlet` has no page
  consumer left. Every controller that lands on `.UserAdmin` builds the rows
  through `AccountRows.showList`, or the page renders with none.
- Enabling/disabling an account: the checkbox persists whatever happens, so
  only disable-then-sign-in proves it — `UserAdminIT`.
- **One status pill for every entry/page publication state.**
  `WEB-INF/jsps/editor/StatusPill.jsp` is a `<jsp:include>`; callers set
  `pillStatus` (a `PubStatus` name) and optionally `pillWhen` (a `Date`, for
  `SCHEDULED` rows) as **request**-scoped attributes (`<jsp:include>` runs in
  a fresh `JspContext` blind to page scope). It renders
  `<span class="status-pill status-<lower>">` plus a trailing `.status-when`
  when `pillWhen` is set. In a `c:forEach` (`Entries.jsp`, `Pages.jsp`) a row
  that sets no `pillWhen` must `<c:remove>` it, or a `SCHEDULED` row's
  timestamp leaks onto later rows. Five `PubStatus` variants plus `unsaved`
  for the entry editor's publish rail, where a new entry has no `PubStatus`
  yet (once `bg-info` badges). **The message code is composed in EL**
  (`weblogEdit.${fn:toLowerCase(pillStatus)}`), so `MessageKeyTest` cannot see
  it; only the file's header comment, naming all six literally, keeps them off
  the orphan list (that arm matches WHOLE keys, `.` continuing a key:
  `weblogEdit.unsaved.` with a trailing full stop is not
  `weblogEdit.unsaved`).
- **One selection bar for every list with bulk actions.** `.selection-bar`
  (`data-selection-bar="<form id>"`, `hidden` by default) sits above the table
  on Entries/Submissions/MediaFileView; the header checkbox carries
  `data-select-all` and is excluded from the count. `roller.js`'s delegated
  document-level `change` listener finds the bar whose `data-selection-bar`
  matches the checkbox's `form.id`, counts
  `input[type=checkbox]:checked:not([data-select-all])`, toggles `bar.hidden`,
  and writes the count into `.selection-count` via the `selection.count`
  message (`"{0} selected"`, arity 1, all eight bundles). Trash.jsp
  deliberately has none: `TrashController` has no
  `restoreSelected`/`deleteSelected` pair, only per-row and whole-trash
  endpoints.
- **`<rc:date>`** (`app/.../ui/tags/DateTag.java`, declared in
  `WEB-INF/rollerConfig.tld`) is the admin UI's one timestamp renderer,
  replacing `fmt:formatDate` (server zone) and the translated
  `weblogEntryQuery.date.toStringFormat` key (request-locale zone). **That key
  is deleted from every bundle** (a FORMAT in eight translations could
  disagree — `ja` had `yy/MM/dd HH:mm` vs the base `MM/dd/yy hh:mm a`) and
  `JspConsistencyTest` fails if one declares it again. It emits
  `<time datetime="<UTC ISO-8601 instant>" class="data">yyyy-MM-dd HH:mm</time>`:
  `datetime` is absolute UTC; the text is the **weblog's** wall clock in
  `Locale.ROOT` (tabular cells), as `EntryEditController` already parses
  `bean.pubTimeLocal` against
  `getActionWeblog(request).getTimeZoneInstance()`. Null renders nothing
  (every `<c:if test="${x != null}">` keeps working).
- **Field errors point at the field, not just a banner.** `BaseController`
  adds `addFieldError(model, fieldId, key, request)` (and an `Object[] args`
  overload) beside `addError`: plus the control's DOM id into model attribute
  `invalidFields` (`BaseController.INVALID_FIELDS`, an ordered, deduped
  `LinkedHashSet<String>`) and its space-joined `invalidFieldIds`
  (`INVALID_FIELD_IDS`). All three admin layouts render
  `<body data-invalid-fields="${fn:escapeXml(invalidFieldIds)}">` **only when
  non-empty** (sticky, it would mark every later clean render refused).
  `roller.js` reads `document.body.dataset.invalidFields` on
  `DOMContentLoaded` and, for each id that resolves, adds `.is-invalid`, sets
  `aria-invalid="true"`, and focuses the first — null-guarded (it also loads
  on public pages), an unrendered id skipped silently; the CSS
  (`.form-control.is-invalid` etc.) predates it, so no `DesignTokenTest`
  change.
- **Sidebars render on the rail's own grammar, not a Bootstrap card.**
  `<aside class="sidebar">` replaces `.card`/`.card-body`; each block is a
  `.sidebar-group` (`border-top`, padding) with a `.sidebar-label` heading in
  the rail's caps-label role (12px/600/.08em/uppercase). Every sidebar form
  gets `class="form-stacked"`.
- **Global Config validates `site.privacy.page` at save** as a page
  reference (`PageReference`): `slug` is a *published* site page, `handle/slug`
  a published Page on that blog (`site-space/x` as a blog form resolves to
  nothing). Checked in `GlobalConfigController` only when the value changed.
  Fail closed: an unknown or draft page, or a lookup that throws, adds a field
  error, nothing is saved and the form re-renders what was typed. Because it
  runs only on a change, a page unpublished later (or a deleted blog) never
  blocks an unrelated save (Global Config never blocks), and a front-page
  handle change no longer revalidates it. No per-request check, so that leaves
  a dead link (the field label says so). V040 qualified an existing bare slug
  with the front-page blog's handle so it kept pointing at the same page.
- **The front-page select** (`site.frontpage.weblog.handle`'s field) offers
  "Site directory" (empty), an optgroup of published site pages (value
  `page:<slug>`, which `GlobalConfigController` stores as `site.frontpage.page`
  and blanks the handle) and the blogs (the site space is never offered; a
  posted `site-space` is refused). A blog or the directory blanks the page. A
  draft or missing page is refused (`configForm.error.frontpagePage.notFound`),
  validated only when changed. With no published site page the select shows a
  hint linking the site Pages screen. A change to the slug, the front door, the
  privacy page or `site.absoluteurl` touches every weblog and the site space and
  clears the site-wide cache (see `themes-and-templates.md`, render-cache
  expiry).
- **The Site section (global admins only).** The main menu (`MainMenuController`
  adds `siteSpace`, creating the hidden `site-space` weblog on first use; null
  for everyone else) shows a Site row with Pages, Media and Theme, the only
  three screens that accept the site space. `UISecurityEnforced.allowsSiteSpace()`
  is false by default and true on the Pages, PageEdit, ThemeEdit and media
  controllers; `PressroomHandlerInterceptor.siteSpaceOffered` clears the action
  weblog for any other request (and for a non-admin, an anonymous caller or any
  API/MCP request), so a refusal looks exactly like an unknown handle (redirect
  to access-denied; API 404). `MenuHelper` shows only those three tabs on the
  site space, and `userWeblogs` grants refuse it, so it is never in a weblog
  list. `ThemeEditController.offered` gives the site space `journal`,
  `portfolio`, `travel` and `frontpage` and never a custom theme; other weblogs
  get everything but `frontpage` (unless already on it). Covered by
  `SiteSpaceAccessTest` and `SitePagesIT`.
- **The top bar's weblog switcher appears only for a user on ≥ 2 weblogs.**
  `BaseController.populateCommonModel` resolves the caller's weblog
  permissions to `List<Weblog>` and adds `userWeblogs` only then.
  `switcherAction` is the current admin action when it is one of ten reusable
  ones (`entries`, `trash`, `submissions`, `comments`, `categories`, `pages`,
  `mediaFileView`, `themeEdit`, `weblogConfig`, `members`), else `entries`, so
  switching from Global Config lands on the entry list, not a 403. The
  permission lookup is a log-and-return `catch` — a display path, the page
  being built from `actionWeblog`, which the interceptor resolves separately.
- **`URLUtilities.getQueryString` URL-encodes both key and value** (raw, `&`
  started the next parameter, `#` dropped everything after it, `+` became a
  space; `fn:escapeXml` on the `href` is no repair — its `&amp;` decodes back
  to `&` before the request is built). **Every caller of
  `getQueryString`/`getActionURL` passes RAW values** — pre-encoding
  double-encodes (as `MultiWeblogURLStrategy`'s `cat`/`q` and
  `PreviewURLStrategy`'s `theme` params once did). The one exception,
  `getWeblogSearchPageURLTemplate`, keeps its `{searchTerms}` OpenSearch
  placeholder braces unencoded.
- **A JSP tag prefix with no declared taglib renders as literal text, not an
  error** — `<str:truncateNicely>` shipped in three places with no `str`
  taglib declared anywhere (the JSP twin of Velocity's leniency, see
  Templates). `JspConsistencyTest` scans for undeclared-prefix tags.
- **`JspConsistencyTest` pins all of the above as markup shape** — status
  pills, `.selection-bar`/`.selection-count`, the confirm idiom,
  `data-invalid-fields`, sidebar grammar, no inline `onclick` on the switcher,
  undeclared tag prefixes, balanced `<div>`s, heading levels, the href rule
  below. It covers **every** JSP — the `A_OWNED` exemption is deleted, not
  replaced.
- **Every `href` built from an expression is `fn:escapeXml`'d, a `<c:url>` (in
  a var or inline), or a bare `urls.*` helper call — tree-wide.** The obvious
  `grep 'href="${' | grep -v escapeXml | grep -v 'c:url\|urls\.'` is mostly
  false positives and misses a `urls.*` call with a raw field appended
  (`${urls.weblogAbsolute(w)}${p.slug}` on `Pages.jsp`; a slug is checked
  against `/` and reserved names, never quotes), so the scan strips the three
  permitted forms from the whole attribute value and fails on any `${` left;
  it also sees the inline `<c:url .../>` spelling.
- **Every printed EL expression in every JSP is escaped or named safe**
  (`JspConsistencyTest.everyPrintedExpressionIsEscapedOrKnownSafe`): wrap it
  in `fn:escapeXml`, or add it to `SAFE_EL` with what makes it safe. Its
  shrink-only `KNOWN_RAW_EL` is empty (since 2026-10); do not refill it. A
  `${[a, b]}` list is as safe as its elements. The scan cannot see a
  scriptlet, so `<%= %>` prints nothing but `request.getContextPath()`
  (`noJspPrintsAScriptletExpressionButTheContextPath`). An entry's title is
  printed from the controller's plain label (`EntriesController.plainTitle`
  / `plainTitles`, the `entryTitles` map, the editor's `tabTitle`) through
  `fn:escapeXml` exactly once, never as `displayTitle` (an untitled entry's
  is raw body text) and never as the stored title escaped again. A weblog's
  "about" is plain text, escaped on the Main Menu.
- **A message argument reaches the reader UNESCAPED, so escape it at the call
  site.** `tiles/messages.jsp` renders `${msg}` bare and
  `<c:out value="${error}" escapeXml="false"/>`, deliberately (several
  messages carry a link or a `<strong>`), so the controller is the escaping
  boundary. `StringEscapeUtils.escapeHtml4` wraps every user-typed argument;
  `MessageArgumentEscapingTest` is the ratchet: **any** accessor passed as a
  message argument must be escaped or named in its allowlist with the
  mechanism that makes it safe. Blunt on purpose: inferring which accessors
  return author input is what went wrong at `Weblog.getName()`
  (`WeblogWrapper` escapes for themes) and `EntryBean.getTitle()` (the
  *entity* is stored escaped). `Weblog.setName`'s `Utilities.removeHTML` is
  not a boundary: its no-closing-bracket branch appends the rest verbatim, `<`
  included, so an unterminated `<img src=x onerror=…` survives. Over-escaping
  costs too (hence the allowlist): an entry title, already escaped at save by
  `EntryFieldRules`, renders `&amp;lt;`. Deliberately outside the scan:
  `CreateWeblogController` passes `e.getMessage()` as the message **key**, and
  `getText` returns an unresolvable key verbatim to the raw sink — recorded,
  not fixed.

- **Businesses (Global Admin, `/roller-ui/admin/businesses.rol`).** List,
  `businesses!edit.rol` (create/edit; blank id opens an empty form),
  `businesses!save.rol`, `businesses!delete.rol` (`data-confirm`; refused
  with `businesses.error.inUse` while any weblog uses it). `Routes` pins
  `#businesses-list-marker` and the form's `action$=` selector. Blog owners
  choose a business and place details in Settings, section `#settings-business`.
  Saving a business touches `lastModified` of every weblog using it (see
  Business profiles in content-features.md).
- **Copyright**: Settings carries a per-blog `copyright` (V038, plain text, 255 max, blank stored as null so the footer falls back to "(c) year blog name"); the bean trims it, `WeblogSettingsRules` bounds it, `/api/v1` mirrors `tagline`.
- **Footer (colophon)**: `tiles/footer.jsp` renders one `Colophon.of(weblogger, null, clock)` record (holder = `site.name` on admin screens): `div.colophon` > `p.colophon-copyright`, `p.colophon-meta` (privacy link, `Roller 0.4.0 (abc1234)`, `released yyyy-MM-dd`, each a span, dots from CSS, each omitted when null). Keys are `footer.copyright.default|privacy|version|version.revision|released`; the Velocity footer reuses them and the three class names. The Velocity footer alone also carries `span.colophon-account`, the way into the admin screens from any blog (a blog on `/` replaces the site directory, which had the only such link): Sign in for a visitor, Main menu and Sign out for an author, Sign out for a reader, the frontpage top bar's keys and rules. It is safe in cached pages because page-cache keys carry `/user=<name>` (`ThemeColophonRenderingTest`). Colophon strings are raw: the JSP `fn:escapeXml`s each, and passes message arguments as nested `<spring:argument>` tags, because a string `arguments=` attribute is split on commas and a holder can contain one (the year goes through `escapeXml` too, so MessageFormat prints `2026`, not `2,026`). Every full-page layout includes the footer tile; `tiles-popuppage` (modal/iframe) and `tiles-barepage` (transient script iframe) deliberately do not, and the install/bootstrap pages carry it too: before bootstrap the JSP's Weblogger lookup is caught and `Colophon.of(null, ...)` reads version, revision and build time from the packaged `/pressroom-version.properties` (no copyright line, no privacy link) (`AdminChromeJspTest`, `PressroomViewResolverTest`).

## Categories
- **Ownership-check every id.** `BaseController.lookupEntry`/
  `lookupTemplate`/`lookupCategory`/`lookupPage` are the by-id ownership
  family: the permission interceptor only vouches for the *action* weblog, so
  a global by-id lookup lets any editor rewrite any weblog's data. All four
  treat a blank id as absent. Both `removeId` and `targetCategoryId` need it
  — a foreign move target re-files entries into someone else's blog.
- **Modal JS binds by control NAME, not id.** Struts-generated ids
  (`#categoryEditForm_bean_name`) never survived the JSP migration, so
  add/edit/delete silently did nothing; names are what the server binds.
- **Add and edit are different endpoints** (`categoryAdd!save.rol` /
  `categoryEdit!save.rol`); the shared modal picks by whether `bean.id` is set.
- The Blogger XML-RPC API and `weblog.bloggercatid` (a raw id with no
  cascade; deleting its category left the weblog's settings unsaveable) are
  gone (W2, `V023__drop_w2_fossils.sql`); `removeWeblogCategory` has nothing
  to null out. `CategoryIT.deletingACategoryLeavesTheWeblogSaveable` still
  covers the general shape.

## Agent accounts and API tokens

Spec `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`; cards
`docs/design/tables/tables-users.html`, `forms/forms-agent-page.html`, `forms/forms-token-reveal.html`.

- **The agent page** (`AgentController`, `/roller-ui/admin/agent.rol?userName=`): details, one
  `data-confirm` Disable/Enable, the Weblogs section and the token section. No password field and no
  set-password link; `modifyUser.rol` sends an agent there, and the person editor's save and
  send-link actions refuse one (`agent.error.notAPerson`).
- **The Weblogs section** (`UserWeblogs.jsp` + `UserWeblogsController`) is on a person's page and an
  agent's page alike, with the Members page's rules: a weblog keeps one Admin, nothing is granted
  while `groupblogging.enabled` is off (the section says so), a reader is never granted. Its weblog
  field is `handle` and the token form's is `pin`: a field named `weblog` is read by
  `PressroomHandlerInterceptor` as the action weblog.
- **The token form, reveal and revoke live in `TokenFormController`**, the abstract base of
  `ProfileTokensController`, `AgentController` and `AdminTokensController`. The secret reaches
  `profileTokens!reveal.rol` only as a one-read flash attribute (`REVEALED_TOKEN`), never in a URL;
  the reveal answers `Cache-Control: no-store`, and a reload says "already shown". The logging
  risk is Spring writing the FlashMap's contents at TRACE for these JSP reveal views; the API's JSON
  mint response body is the secret itself and Spring would write it at DEBUG. Keep `org.springframework`
  at INFO in production.
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
