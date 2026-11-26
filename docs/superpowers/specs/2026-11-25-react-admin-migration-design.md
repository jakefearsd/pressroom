# React admin: migrating the JSP admin UI at feature parity

Date: 2026-11-25. Owner direction (Jake, 2026-11-25): "the target is generally
feature parity for now, so it's a straight technology migration. Use subagents
and just go get it done, asking me questions if problems come up along the way."
Builds on the React-readiness groundwork (0.15.0 to 0.16.3) and SA-18.

## Intent

Replace the JSP/tiles admin UI with a React single-page app that runs on
`/api/v1`, one screen group at a time, until no admin JSP is left. Parity, not
redesign: every action a screen offers today is offered by its React
replacement, with the same look (Quiet Instrument), the same messages, the same
permissions. New features, layout changes and redesigns are later, separate
work.

**Goals**
- Every admin screen in the inventory below is served by React and talks only
  to `/api` (no `.rol` form posts).
- The JSP, controller, view-model and tiles code behind each migrated screen is
  deleted when its React replacement ships (no two UIs for one screen, no
  dangling backlog).
- A browser session is an API credential, with CSRF protection on every session
  write; the SA-18 session-only resources get their routes, under SA-18's rules.

**Non-goals**
- No redesign, new features or new screens. Where a JSP behaviour is clearly a
  bug, fix it and say so in the commit; otherwise reproduce it.
- No change to bearer-token callers: agents, the CLI and MCP see the same API
  (new routes are added; none changes shape).
- Reader-facing and pre-login pages stay server-rendered (see "Out of scope").

**Anti-goals** (failure even if it "works")
- A session reaching `/api/mcp`, or a token reaching an SA-18 route.
- A session write that succeeds without a valid CSRF token.
- An SSO reader session (no `admin`/`editor` authority) getting anything from
  `/api` that it cannot get anonymously.
- Any code that assumes a context path.
- Losing an author's unsaved text when the session expires mid-edit.

## Out of scope: stays server-rendered

Login, forgot/reset password, installer and database pages
(`InstallStart`, `CreateDatabase`, `UpgradeDatabase`, `Bootstrap`,
`DatabaseError`), `GenericError`, `SiteDirectory` (public), the reader
`Account` page (SSO readers, public styling), and all public rendering. These
keep their JSPs and the tiles layouts they use (`.tiles-loginpage`,
`.tiles-installpage`, `.tiles-errorpage`, `.tiles-simplepage` as far as they
still need it). Wave 5 deletes whatever tiles code nothing uses any more.

## Approaches considered

1. **Screen-by-screen replacement (chosen).** React app beside the JSP admin;
   each wave moves a group of screens, redirects their old GET routes, deletes
   the JSPs. Production keeps working after every wave; each wave can be
   released on its own. Cost: for a while the rail links between two apps.
2. **Big-bang rewrite on a branch.** One cutover at the end. Rejected: weeks of
   unreleasable work drifting from master in a solo repo that releases weekly,
   and the browser suite can't verify a half-built app against production
   behaviour.
3. **React islands inside JSPs.** Mount components into existing pages.
   Rejected: keeps tiles, `.rol` forms and the JSP CSRF path alive indefinitely
   and doubles the state model per screen; it is a mix, not a migration.

## Architecture decisions (builder's calls, stated so they can be overridden)

### A1. Where the app lives
- URL space: `/pressroom-ui/app/` and below (history routing). Under
  `/pressroom-ui`, so `ControlPlaneHostFilter` keeps the admin off custom weblog
  domains as it does today.
- `AppShellController` serves `GET /pressroom-ui/app.rol`: a minimal HTML shell
  (JSP or a static template rendered by the controller) carrying one JSON
  bootstrap block: context path, application version, CSRF header name and
  token, the user (userName, screenName, locale, global permission: `admin` or
  `editor`), and the resolved UI locale. It links the existing CSS stack
  (A4) and `pressroom-ui/app/admin.js?v=<version>` and `admin.css?v=<version>`
  if any. Every URL in the shell is built from the request's context path.
- `AppShellForwardFilter` forwards any `GET /pressroom-ui/app/**` that is not a
  static asset (`/pressroom-ui/app/assets/**`, `admin.js`, `admin.css`) to
  `/pressroom-ui/app.rol`, so a deep link or reload works.
- Security: `/pressroom-ui/app/**` and `app.rol` need `admin` or `editor`
  (same as `/pressroom-ui/authoring/**`); unauthenticated → the login page with
  a return path to the requested URL (existing `ReturnPathLoginSuccessHandler`).
- Build output goes to `app/src/main/webapp/pressroom-ui/app/` (the one static
  location this deployment serves; see `app/frontend/build.mjs` header),
  git-ignored, with unhashed file names and `?v=` cache busting, like the
  editor bundle.

### A2. Toolchain
- Same npm package as the editor bundle (`app/frontend`, one `package.json`,
  one `npm ci`, the pinned Node via `frontend-maven-plugin`). Source in
  `app/frontend/src/admin/`, tests in `app/frontend/test/admin/`.
- TypeScript (strict), React 19, React Router (data router), TanStack Query.
  esbuild bundles (already pinned, already in the pipeline); `tsc --noEmit`
  type-checks in `npm run build`, so a type error fails the Maven build.
- Typed API: `openapi-typescript` generates types from a committed snapshot
  `app/frontend/src/admin/api/openapi.json`; `openapi-fetch` is the client. A
  Java test (`OpenApiSnapshotTest`) fetches the live `/api/v1/openapi.json` and
  fails when it differs from the committed snapshot, printing how to refresh it
  (`-Dopenapi.snapshot.update=true` rewrites the file). So API drift breaks the
  build, not the browser.
- Tests: Vitest + React Testing Library + jsdom, with MSW for HTTP. `npm test`
  runs the existing `node --test` suite and `vitest run`. Coverage gate on
  `src/admin` (v8): lines 90, branches 80, ratchet-up-only like JaCoCo.
- Lint: ESLint with `typescript-eslint` (strict) and `react-hooks`, zero
  warnings, run in `npm test`.
- Dev loop: `./pressroom dev` serves `src/main/webapp` directly, so
  `npm run watch` (esbuild watch) plus a browser reload works against the real
  session and CSRF with no proxy. Document it in `docs/dev/build-and-ci.md`.

### A3. Session access to `/api` (SA-18 rules 1 and 2)
- A request **with** an `Authorization` header is handled exactly as today
  (Bearer on `/api/**`, Basic only on the token resource, stateless, no CSRF).
- A request **without** one may authenticate from the existing HTTP session:
  read the `SecurityContext` from the session, never create a session, never
  run remember-me on `/api`, never set a cookie. The session's principal must
  hold `admin` or `editor`; otherwise the request is anonymous (an SSO reader
  gets 401, as with no credential).
- A session write (any method but GET, HEAD, OPTIONS) needs the session's CSRF
  token in the header named in the bootstrap block (Spring's
  `HttpSessionCsrfTokenRepository`, the one the UI chain uses, so the token the
  shell embeds is valid for both). Missing or wrong → 403 problem+json with a
  stable code (`csrf.invalid`), localised detail.
- `/api/mcp` never accepts a session: bearer only, as today.
- `Caller.current()` reports `SESSION` for these requests (details are
  `WebAuthenticationDetails` today; verify for remember-me-restored sessions and
  SSO sign-in, and make the rule positive if the class differs).
- A session caller has the signed-in user's own permissions: the same as an
  unpinned token of role ADMIN held by that user. The API authorization matrix
  test gains a SESSION column; the SA-18 rows read 200/204 for SESSION and 403
  for every token.
- Expired or absent session on a call from the app: 401 problem+json. The app
  shows the existing session-expired treatment ("signed out, sign in again" with
  a link to the login page returning to the current URL); the entry and page
  editors keep unsaved text in `localStorage` and offer it back after sign-in.
- SA decision log: this binds no other repository (token callers unchanged), so
  no new SA entry; SA-18 already anticipates it. If a wave changes that, it adds
  one.

### A4. Look and feel
- The shell links the same stylesheets the JSP admin does, in the same order
  (`bootstrap.min.css`, `pressroom-tokens.css`, `pressroom.css`;
  `DesignTokenTest` keeps holding). React components emit the same class names
  and markup idioms, so parity is visual as well as functional. No CSS framework
  or CSS-in-JS is added.
- Shared components replace the JSP idioms one-for-one: `StatusPill`,
  `EmptyState`, `SelectionBar`, `DateTime` (weblog time zone,
  `yyyy-MM-dd HH:mm`, `<time datetime=UTC>`, replacing `<rc:date>`),
  `ConfirmDialog` (replacing `data-confirm`: one modal shape), field errors
  from problem+json (`is-invalid`, `aria-invalid`, focus first, as
  `data-invalid-fields` does), password fields (show/hide, match hint).
- The app frame reproduces the admin rail (`#adminRail`, tool groups under
  caps-labels, `.rail-active`), banner, user status and weblog context. Rail
  entries come from one route table; until a screen migrates its entry links to
  the `.rol` route, and the JSP rail links to React routes for migrated ones
  (the menu XML is edited at each cutover).
- Emphasis by weight, never size (design-system rule).

### A5. Messages
- `GET /pressroom-ui/app-messages.rol?locale=<l>&v=<version>` returns the
  resolved `ApplicationResources` bundle for that locale as JSON (base keys plus
  the locale's overrides), cacheable because the URL changes per release.
- The app formats Java `MessageFormat` patterns client-side (`{n}`, `''`
  escaping), with tests mirroring `MessageFormatRegressionTest`'s cases. React
  escapes interpolated text, so the "message arguments are not escaped" hazard
  does not carry over; `dangerouslySetInnerHTML` is allowed only for
  server-sanitised HTML (previews), enforced by a lint rule with an allowlist.
- `MessageKeyTest` scans `app/frontend/src/admin/**/*.{ts,tsx}` for `t('key')`
  literals, so a key used only by React is not an orphan and a missing key fails
  the build. Dynamically composed keys need the header-comment listing, as
  `StatusPill.jsp` does today.

### A6. Bulk actions
No bulk API routes are added. The app runs per-item calls (concurrency 4),
shows progress, and reports per-item failures in place. One transaction becomes
N; at this site's scale (10 to 50 blogs) that is acceptable. A wave may add a
bulk route if a screen proves unusable without one, as an ordinary API change.

### A7. New API routes
Each gap below is filled as an ordinary `/api/v1` change, test-first, with its
OpenAPI typing, problem codes, authorization-matrix row and an MCP decision
(tool or `Exclusion` with a reason; SA-18 routes are always `Exclusion`
"session-only"). `FormBodyParityTest`/`EditableFieldManifest` stay the field
contract. The public reference `docs/api/README.md` is updated in the same
commit.

### A8. Cutover per screen
When a screen's React version ships, in the same wave:
1. its old GET `.rol` routes answer 302 to the React route (weblog handle and
   ids carried over), pinned by a test like `OldUiPathRedirectIT`;
2. its POST `.rol` actions, controller, JSP, view models and now-unused
   messages are deleted;
3. `Routes.java` points at the React route with a CSS marker the React screen
   emits, and `RouteSweepIT` covers it;
4. every browser IT that drove the JSP screen drives the React screen, at both
   context paths.

## Screen inventory and API gaps

From the gap map of 2026-11-25 (`.rol` routes under `/pressroom-ui/...`, API
under `/api/v1`). "S" = SA-18 session-only.

| Wave | Screen | API gaps to fill |
|---|---|---|
| 0 | Main menu (`menu.rol`) → app home | pending-comment count per weblog (use `GET comments?status=` if cheap enough, else add a count to the weblog list) |
| 0 | Traffic (weblog) | none |
| 0 | Site traffic | none |
| 1 | Entries list | date-range filter (`EntryListQuery` start/end, not wired); duplicate entry |
| 1 | Entry editor (+ revisions, preview, autosave) | send newsletter; entry `locale` and `pinnedToMain` on `EntryWrite` |
| 1 | Entry trash | none (empty = loop, A6) |
| 1 | Comments | none |
| 1 | Categories | category image on `CategoryWrite` |
| 2 | Pages, page editor | page preview |
| 2 | Pages trash | none |
| 2 | Media library, edit, crop, image chooser, entry from media | delete folder; folder private toggle; list search, sort, type filter; media description, copyright, tags in patch and view |
| 3 | Weblog settings | none |
| 3 | Create weblog | theme, locale and time-zone lists (on `/v1/meta`) |
| 3 | Theme | shared-theme list; convert to custom (S, template territory) |
| 3 | Templates, stylesheet | all (S) |
| 3 | Members | grant, change, remove (S) |
| 3 | Submissions (contact inbox) | list, delete (S) |
| 3 | Remove weblog | delete (S) |
| 4 | Global config | `site.absoluteurl` write for SESSION (S) |
| 4 | Setup (front page) | none (`PATCH /v1/admin/config`) |
| 4 | Businesses | none |
| 4 | Maintenance | discovery and analytics status, refresh discovery, sync analytics |
| 4 | Users list, user edit | `q` and kind filters; fullName, locale, time zone, writer flag; send password link; a user's weblogs; admin flag, memberships, others' email (S) |
| 4 | Agents | all (S) |
| 4 | Site tokens | all (S) |
| 4 | Profile | writes (S) |
| 4 | My tokens | none once A3 lands (session allowed by `TokensApi`) |
| 5 | Retirement | delete leftover tiles layouts, menu XML, admin-only parts of `pressroom.js`, JSP-only tests; rewrite `docs/dev/admin-ui.md`; update `CLAUDE.md` hazards and the wiki's PressroomHub |

Each wave is releasable on its own. Releasing and deploying stay Jake's call.

## Acceptance criteria

### Per screen (every wave)
1. Every action in the screen's inventory row works in React through `/api`;
   a Vitest test covers each action's success path and its refused path
   (problem+json shown in place, field errors on the field).
2. Permissions match the JSP screen: an author/editor/owner/admin sees the
   actions they saw before, and no more (a test per role difference).
3. A8 steps 1 to 4 are done.
4. All gates green: Java suite and floors, PMD/CPD/SpotBugs, `-Xlint`,
   i18n ratchets, `tsc`, ESLint, Vitest with its coverage floor,
   `mvn verify -Pit` at `/` and `/pressroom`.

### Wave 0 (foundation)
1. **Session on `/api`:** with a signed-in editor's session cookie and no
   `Authorization` header, `GET /api/v1/me` → 200 for that user; the same
   request after logout → 401 problem+json; no response from `/api` sets a
   `JSESSIONID` or remember-me cookie.
2. **CSRF:** a session `PATCH` with the right `X-CSRF-TOKEN` (or the header the
   bootstrap names) → applied; missing or wrong token → 403 `csrf.invalid`, and
   nothing applied. A Bearer `PATCH` with no CSRF header → applied as today.
3. **Boundaries:** a session on `POST /api/mcp` → 401; an SSO reader session on
   `GET /api/v1/me` → 401; a session plus a bad Bearer header → 401 (the header
   wins; no fallback to the session).
4. **Caller kind:** `Caller.current()` is `SESSION` for a form-login session, a
   remember-me-restored session and an SSO-signed-in editor; the authorization
   matrix test has a SESSION column and passes.
5. **Tokens:** a session caller can list, mint and revoke its own tokens on
   `/api/v1/tokens` (with CSRF on the writes).
6. **Shell:** `GET /pressroom-ui/app/` and a deep link
   (`/pressroom-ui/app/weblogs/x/traffic`) return the shell for an editor,
   redirect to login (returning to the deep link) when signed out, and 403 for an
   SSO reader; the shell's URLs are correct at `/` and `/pressroom`.
7. **Toolchain:** `mvn -pl app test` runs `tsc`, ESLint, Vitest (with its
   coverage floor) and the existing `node --test` suite; `-DskipTests` skips
   the tests but not the build; a type error fails the build.
8. **OpenAPI snapshot:** `OpenApiSnapshotTest` fails on a changed API and passes
   after `-Dopenapi.snapshot.update=true`.
9. **Messages:** the messages endpoint returns the `de` bundle merged over base;
   the client formatter passes `MessageFormat` parity cases; `MessageKeyTest`
   sees a key used only in a `.tsx`.
10. **Frame:** the React rail shows the same tool groups and entries as the JSP
    rail for an editor and for an admin (one test per), marks the active entry,
    and links unmigrated screens to their `.rol` routes.
11. **Expiry:** a 401 from any call shows the session-expired treatment with a
    working sign-in link back to the current URL.
12. **First screens:** app home (main menu), weblog traffic and site traffic
    meet the per-screen criteria, including the A8 cutover: after sign-in a user
    lands on the app home; `menu.rol`, `traffic.rol` and `siteTraffic.rol`
    redirect; `MainMenu.jsp`, `MainMenuSidebar.jsp`, `Traffic*.jsp`,
    `SiteTraffic.jsp` and their controllers are gone.

## Left to the builder
- Component structure, state layout and hooks inside the app.
- Exact route paths under `/pressroom-ui/app/` (stable, readable, carrying the
  weblog handle: e.g. `/weblogs/{handle}/entries/{id}`).
- How the CM6 editor bundle is wrapped for React (reuse `src/editor.js`'s
  setup; don't fork it).
- Problem-code names for new routes, following `docs/dev/api.md`.
- Whether a wave splits into more than one release.
