# Automation API (developer notes)

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Automation API
`com.wikantik.pressroom.ui.restapi` (`/api/v1`) is a REST surface for
scripts and agents alongside the JSP admin UI. Reference and error contract:
`docs/api/README.md`, the front door. Tokens are minted in the admin UI
(Profile → API tokens, or an agent's page) or with `bin/pressroom-api auth login`.
`/api/v1` is unstable while Pressroom is 0.x.

- **The `/api` prefix is a servlet-spec prefix mapping, not part of any
  `@RequestMapping`.** `ServletRegistrationConfig.API_URL_PATTERNS` (`/api/*`)
  shares the `DispatcherServlet` with `*.rol`, the SEO patterns and
  `/newsletter/*`; Spring strips the matched prefix before routing, so
  controllers under `ui.restapi.v1` are written relative to `/v1/...`
  (`TokensApi` is `@RequestMapping("/v1/tokens")`, not `/api/v1/tokens`;
  `NewsletterController` likewise `/newsletter`, not `/newsletter/subscribe`).
  Getting it wrong compiles and 404s; no test catches it.
- **Each mount serves only its own handlers.** Because the prefix is
  stripped, `/api/pressroom-ui/...` once resolved to a JSP controller behind
  the API's chain (Bearer, then also Basic, no CSRF, no token-scope check off
  `/v1/**`): a READ token pinned to one weblog minted an unpinned ADMIN token
  through the old `profileTokens!create.rol` (code review, 2026-11-03). Now
  `apiSecurityFilterChain` admits only `/api/v1/**` and `/api/mcp` and denies
  the rest, and `PressroomHandlerInterceptor` answers 404 for a non-API
  handler under the `/api` servlet path or an API handler under any other
  (`SecurityConfigTest`, `PressroomHandlerInterceptorTest`). The newsletter
  mount had the same hole: `/newsletter/pressroom-ui/setup!save.rol`
  reached the (since deleted) `SetupController` anonymously, since the UI chain's admin-only
  rule matches the full path and the controller asks for no user. Under
  `/newsletter` only `NewsletterController` proceeds. `api` and
  `newsletter` are reserved roots in
  `rendering.weblogMapper.pressroomProtectedUrls` so no weblog handle can shadow
  them.
- **HTTP Basic authenticates only the token resource (2026-11-20, 0.16.0).**
  Two chains serve `/api/**`, both stateless (they store no security context;
  a session is read only as the next bullet says): `apiTokenResourceSecurityFilterChain`
  (`@Order(0)`, `SecurityConfig.TOKEN_RESOURCE`: `/api/v1/tokens` and
  `/api/v1/tokens/*`) carries `httpBasic` with the `PasswordCallerDetails`
  marker, and `apiSecurityFilterChain` (`@Order(1)`, the rest) has no Basic
  filter, so a Basic header there leaves the request anonymous and every route
  but `/api/v1/ping` answers 401. That chain's entry point
  (`ApiAuthenticationEntryPoint.refusingPasswords`) reads only the header's
  scheme and says a password is accepted only on `/api/v1/tokens`, with no
  challenge. On the token chain every 401 is problem+json with
  `WWW-Authenticate: Basic realm="Pressroom"`: the chain's entry point
  (`challengingForPassword`, `Authentication required.`) and the Basic
  filter's (`rejectingPassword`, `The username or password was not accepted.`
  for a wrong password, disabled account, agent or unknown user alike), so a
  challenge-driven client still mints. Never leave the Basic filter on
  Spring's `BasicAuthenticationEntryPoint`: its `sendError(401)` is Boot's
  `/error` JSON, not problem+json (`ApiPasswordScopeTest`). Both chains share the one `ApiTokenAuthFilter` bean
  (one throttle, keys unchanged; still not container-registered, see
  `apiTokenAuthFilterRegistration`) and each builds an `ApiBodyLimitFilter`.
  A Bearer caller on the token resource still reaches `TokensApi`, which
  refuses it (403). The patterns meet the decoded path as MVC routes it, and
  the firewall refuses `..`, `;` and an encoded slash first, so no spelling
  widens Basic (`ApiPasswordScopeTest`, `SecurityConfigTest`, `ApiIT`). A new
  route is Bearer-only by default; adding one that takes a password means
  adding it to `TOKEN_RESOURCE`, which is a decision, not a convenience.
  Browser ITs call the API with `it.support.AdminToken` (minted over Basic,
  revoked by id), never with the admin's password.
- **Session access to `/api` (2026-11-25, SA-18 rules 1 and 2; spec
  `docs/superpowers/specs/2026-11-25-react-admin-migration-design.md`, A3).**
  A request with **no `Authorization` header** may authenticate from the admin
  UI's HTTP session: `ApiSessionAuthFilter` (first filter on both API chains,
  after `SecurityContextHolderFilter`) copies the session's `Authentication`
  into the request when `Caller` calls it `SESSION` and it holds `admin` or
  `editor`; anyone else (an SSO reader, an unrecognised kind) stays anonymous
  and gets the anonymous 401. It uses `getSession(false)` and writes nothing,
  so no `/api` response sets `JSESSIONID` or a remember-me cookie
  (remember-me is off on both chains). A request **with** an `Authorization`
  header of any kind never falls back to the session (session plus a bad
  Bearer is 401). `/api/mcp` never takes a session. `CredentialStampFilter`
  runs for an accepted session, so a changed password ends it on `/api` too.
  A session **write** (any method but GET, HEAD, OPTIONS) needs the session's
  CSRF token in the `SecurityConfig.CSRF_HEADER_NAME` header (`X-CSRF-TOKEN`),
  checked by the chain's own `CsrfFilter` over the same
  `HttpSessionCsrfTokenRepository` the UI chain uses, so the token the shell
  embeds serves both; missing or wrong is 403 `csrf.invalid` (problem+json,
  `ApiAccessDeniedHandler`). The check applies only when the caller is a
  session (`SecurityConfig.isSessionWrite`); Bearer and Basic are
  authenticated after it and stay CSRF-free, and an unauthenticated write is
  still 401. Gotcha: the API chains replace `CsrfAuthenticationStrategy` with a
  no-op, or `SessionManagementFilter` (it sees a session caller as a fresh
  sign-in on a chain that stores no context) clears the session's token after
  every call. The throttle keys a session caller by user, not address
  (`ApiTokenAuthFilter`). Tests: `ApiSessionAccessTest`, `ApiSessionAuthFilterTest`,
  the SESSION callers in `ApiAuthorizationMatrixTest`.
- **How a request authenticated is decided only by `Caller`
  (`ui.restapi.auth.Caller`, 2026-11-20).** `Caller.current().kind()` is one of
  `TOKEN` (Bearer; carries the `ApiPrincipal` ceiling), `PASSWORD` (HTTP Basic,
  recognised positively by the `PasswordCallerDetails` marker the token chain
  sets), `SESSION` (form login, remember-me, reader SSO: exactly
  `WebAuthenticationDetails`), `ANONYMOUS`, and `UNRECOGNISED`. Authentication
  details `Caller` does not recognise are `UNRECOGNISED`, never `SESSION`, so a
  login mechanism added later gains no session powers until it is recognised
  there; the guards refuse it as they refuse a token. Decide caller kind only by
  asking `Caller`: no `instanceof ApiPrincipal`, no reading
  `Authentication.getDetails()` for it anywhere else
  (`CallerKindConfinementTest`; the one other `getDetails()` read allowed is
  `instanceof WeblogPinnedCredential`, a token's pin, in
  `PressroomHandlerInterceptor`).
- **The API and the JSP admin UI share exactly one authorization path:
  `PressroomHandlerInterceptor`.** Every `*Api` controller implements
  `UISecurityEnforced` and declares `GlobalPermission`/`WeblogPermission` like
  `CategoryEditController`, `MediaFileEditController` and the rest; no API-side
  permission system exists. `ApiScopeInterceptor` is registered *after* it in
  `WebMvcConfig` (order is load-bearing, see its javadoc) and only narrows by
  token scope.
- **An API route names its weblog only by its `{handle}` path variable.** The
  `weblog` request parameter is JSP vocabulary and `PressroomHandlerInterceptor`
  ignores it for an API handler (one under `ui.restapi`). Reading it let a
  limited token satisfy its own ceiling on a route with no `{handle}`
  (`POST /v1/weblogs?weblog=<its handle>` created a weblog);
  `WeblogParameterDispatchTest` pins the refusal and that JSP routes still
  read the parameter.
- **`ApiToken.Role` (`READ`/`POST`/`ADMIN`) and the optional weblog pin are a
  ceiling, never a grant.** `ApiScopeInterceptor` refuses what the scope
  disallows and never approves what `PressroomHandlerInterceptor`'s
  `GlobalPermission`/`WeblogPermission` check would refuse: a `READ` token held
  by a `GlobalPermission.ADMIN` user cannot POST; a pinned token cannot act on
  another weblog its owner could edit.
- **`ApiAuthorizationMatrixTest` is the oracle for who may call what (2026-11-20).** One
  hand-written row per route of the API route table states what it requires (least token role,
  global permission, weblog permission; flags for no credential, a password too, and pin-exempt),
  and a few-line rule (`expected`) turns each row into the answer for each of 14 callers:
  anonymous, a password, site-admin tokens of each role and one pinned, an owner's token unpinned,
  pinned here and pinned elsewhere, members below the level (one pinned elsewhere), no role, and an
  agent. Requests go through the real chains and the real `WebMvcConfig` interceptors; a test-only
  terminal interceptor answers 299 where the handler would run, so each cell is reached, 401, 403
  or 404 (problem+json) and no business code runs. Refusals a handler makes itself (`TokensApi`
  refusing every token, `AdminAccountGuard`) stay pinned in their own tests. One MCP tool action
  per controller must answer as its route's cell. **Adding a route means adding its row**, written
  from the README and the JSP page the route mirrors, never copied from its annotations; a route
  with no row fails by method and path, and a cell that disagrees means either the row or the
  route is wrong: deciding which is the point. Entry writes take `EDIT_DRAFT` (see the next point);
  what a contributor's write then saves is decided in the handler, past the sentinel, so
  `EntriesWriteApiDispatchTest` carries those cells.
- **Contributors write drafts over the API (spec 2026-11-21 owner decisions, Task 3, 0.16.0).**
  `EntriesWriteApi` declares `EDIT_DRAFT`, the entry editor's level, and holds a caller without `POST`
  on the weblog to the editor's rules in the handler, through `ContributorRules` (`ui.controllers`),
  the one home the editor uses too (`SaveIntent.status` calls `ceiling`,
  `BaseController.lookupChangeableEntry` calls `mayChange`): a write's status is capped at `PENDING`
  (`holdToCeiling`, after `applyWrite` and before the publish-now and schedule checks, on the
  scratch copy and again on the entry); update, trash and revision restore/keep need the caller's own
  `DRAFT`/`PENDING` entry (`requireChangeable`, a 403 `entry.notChangeable`: the caller can read the
  entry, so a 404 would hide nothing); changing `pinned` or `strand` is a 403
  `entry.needsPublishRights` (`refusePublishersFields`, thrown before the import step), and so is
  asking to import an image (`featuredImageUrl`, `importExternalImages: true`: adding media takes
  POST on the JSP side); the default import is off for them (`EntryImports.plan`'s `defaultImport`),
  so their external images stay linked. Preview needs
  only the weblog, as the editor's preview. `mayPublish` asks `UserManager` for `POST` and reads a
  failed check as no (fail closed). **One controller, not a draft-only twin**: the editor is one
  controller gated on `EDIT_DRAFT` deciding the status from the caller's permission, and a second
  controller would duplicate the write path. Restore and delete-forever are the exception, moved to
  `EntriesTrashApi` (`POST`), because the Trash page (`TrashController`) takes `POST` and the
  declaration is per controller: their matrix rows stay as they were. The token role stays the
  ceiling (`ApiScopeInterceptor`, by HTTP method): a `READ` token writes nothing. The reviewers'
  notice goes out only when a write moves the entry into `PENDING` (`notifyReviewers` takes the
  status before the write; final review I1: a contributor's agent patching its pending entry mailed
  them on every PATCH).
- **Entry actions (React admin migration, Wave 1).** `EntryActionsApi` holds the two POST-level actions on an
  existing entry, `POST .../entries/{id}/duplicate` and `.../send-newsletter`, in a controller of their own for the
  same per-controller-permission reason: the entries page's Duplicate (`EntriesController`) and the editor's "Send as
  newsletter" both need `POST`, where the rest of the entry writes take `EDIT_DRAFT`. Each calls the code the JSP
  controller calls (`EntryDuplication`, `EntryNewsletter`: the newsletter's outcomes are an enum each screen words
  its own way, and its "stamp only after the send" rule lives there). Both are MCP `Exclusion`s: duplicate is an
  editing-screen convenience, and a send emails every subscriber, so it stays a person's decision. Send-newsletter
  is `@SessionOnly` (owner decision 2026-11-25, wave 4): irreversible mail to every subscriber, so a token never
  sends it; the decision-log entry is drafted in `docs/superpowers/specs/2026-11-25-sa-draft-session-only-additions.md`.
  `GET .../entries` takes `startDate`/`endDate`, the JSP filter's bounds (`EntryListQuery.startOfDay`/`endOfDay`,
  server time zone), strictly parsed. An entry's `pinnedToMain` is a site administrator's (`ContributorRules.isSiteAdmin`,
  `entry.needsSiteAdmin`, refused only when the value would change); `locale` is anyone's. A category's `image`
  is checked as the Categories page's browser check is (`CategoryRules.isValidImageUrl`).
- **A contributor reads what the editor shows them (owner decisions final review, I3, 0.16.0).**
  The editor lists the categories and tags, and its image chooser (`MediaFileImageChooserController`,
  `EDIT_DRAFT`) the media files and directories, so those five `GET`s live in `CategoriesReadApi`
  and `MediaReadApi`, which declare `EDIT_DRAFT`; every change stays on `CategoriesApi`/`MediaApi`
  (`POST`). Same split as `EntriesApi`/`EntriesWriteApi`, for the same reason: the declared
  permission is per controller. Paths, operationIds and MCP action names did not move; the OpenAPI
  tags did, because each controller owns one (`OpenApiContractTest`): the reads keep "Categories
  and tags" and "Media", the writes are "Category writes" and "Media writes". `MediaApiBase` holds
  the by-id lookups both media controllers use. The directories read is in the split although the
  ruling named four routes, because the chooser lists directories and `GET .../media?directoryId=`
  is blind without them. Pinned by the matrix rows (`ON_DRAFT`) and `ContributorReadsDispatchTest`.
- **`EntryFieldRules` and `WeblogOwnership`
  (`com.wikantik.pressroom.ui.controllers`) are shared by JSP editor and
  API — one home each for entry-field rules and by-id ownership checks.**
  `EntryBean` and `EntryDtos.applyWrite` both call
  `EntryFieldRules.escapeTitle` and `EntryFieldRules.parsePubTime`
  (weblog-timezone), so the surfaces cannot drift. Entry titles are stored escaped but plain text over the API
  both ways: every DTO that carries one goes through
  `EntryFieldRules.plainTitle` (add any new entry-title output to it, with a test). `WeblogOwnership` is the one
  IDOR defense for a by-id lookup (see Categories): the
  `BaseController.lookup*` family and
  `BaseApiController.requireEntry`/`CategoriesApi`/`PagesApi` all delegate to
  `WeblogOwnership.entry`/`category`/`template`/`page`.
- **A resource the caller may not see is 404, never 403**, in every `*Api`
  controller: a pin-excluded weblog, a foreign entry/category/media/page id and
  a missing id are indistinguishable — a 403 would leak that the resource
  exists. So is a weblog the caller holds no role on:
  `PressroomHandlerInterceptor` answers an API caller who fails the weblog
  permission check 404 unless they hold at least `EDIT_DRAFT` there (a member
  below the required level stays 403; `ApiSecurityRefusalTest`). A token pinned to another weblog
  is "not a member" there whatever its owner's role, so it gets the 404 too: that check runs before
  `ApiScopeInterceptor`'s pin check, so it reads the pin through `WeblogPinnedCredential`
  (implemented by `ApiPrincipal`; an interface in `ui.controllers`, not a second import from
  `ui.restapi`). `ApiScopeInterceptor.checkWeblogScope` and every `requireX` helper in
  `BaseApiController` follow this.
- **A weblog's settings take the Weblog Settings page's permission (2026-11-20).**
  `GET/PATCH /v1/weblogs/{handle}` live in `WeblogSettingsApi`, which declares what
  the Weblog Settings and Theme pages inherited from `BaseController`
  (`WeblogPermission.ADMIN`, `GlobalPermission.LOGIN`, weblog required), and stays
  `@AdminScoped`. The list `GET /v1/weblogs` stays in `WeblogsApi` with
  `GlobalPermission.ADMIN`. Declarations are per controller class, so a route that needs a
  different permission needs its own controller; before the split, an owner who was not a site
  admin could not change their own blog over the API. `WeblogSettingsApiDispatchTest` pins
  owner, member, no-role, pin and token-role cases through the real interceptors. The form
  reserves no setting to site admins; a field it ever reserves must be refused here through the
  same rule (`WeblogSettingsRules`), not a second check. **The read is the contributor's
  (2026-11-26):** `GET /v1/weblogs/{handle}` moved to `WeblogReadApi` (`EDIT_DRAFT`, read-role
  token), because the admin frame and the entry editor call it as an Author or Limited member;
  on `ADMIN` their editor stayed blank on a 403 (found by `MembersIT`). `PATCH`/`DELETE` stay
  here, `ADMIN`.
- **List shape (spec 2026-11-20, Task 5; the wraps landed with the owner decisions, 0.16.0).** Every
  operation with a `limit` answers `{items|entries, offset, limit, hasMore}`, all required
  (`OpenApiContractTest`, no exception set). `GET /v1/weblogs`, `GET /v1/admin/users` and
  `GET .../media` answer `WeblogListPage`, `UserPage` and `MediaPage`; users and weblogs read
  `limit + 1` rows from the manager (no count query) and drop the extra. Media without `limit`
  returns every file, `limit` = item count. A new paged list takes the envelope or the test fails.
- **The OpenAPI document is machine-readable, not a browser explorer.**
  `springdoc-openapi-starter-webmvc-api` serves `GET /api/v1/openapi.json`
  (`/v1/openapi.json` in `application.properties`, per the prefix point),
  scanning only `ui.restapi.v1` so the JSP surface never leaks. The springdoc
  UI is not a dependency; `springdoc.swagger-ui.enabled=false` is defence in
  depth. It sits behind `apiSecurityFilterChain` like all of `/api/**` (a
  bearer token), not exempted like `GET /api/v1/ping`. `OpenApiDocumentTest` pins
  two `docs/api/README.md` claims the document cannot convey — v1 is unstable,
  and `pressroom-api auth login` is the bootstrap path. springdoc's controller is outside `ApiExceptionHandler`'s
  package, so a refusal of it cannot be thrown to the advice (it escaped as the container's 500
  HTML page for a pinned token): `ApiScopeInterceptor` writes a refusal of any handler outside
  `ui.restapi` itself, through `ApiProblemWriter` (`OpenApiPinnedTokenDispatchTest`).
- **Request size (2026-11).** `ApiBodyLimitFilter` (built inline in the API
  chain, before token sign-in) refuses a `/api/v1` body over
  `MAX_BODY_BYTES` with a 413 `too-large`, or fails the read past it when no
  length is declared with `BodyTooLargeException`, which `ApiExceptionHandler.handleMalformedBody`
  finds under the reader's failure and answers with the same 413 (0.16.3; it was a 400). The MCP
  transport answers its own 413 for both cases (`McpServerConfigTest`); uploads (`multipart/*`) keep their own limit. The limit
  is `3 * ColumnLimits.ENTRY_TEXT + HEADROOM_BYTES` (4,048,576 bytes: the
  longest entry in 3-byte characters, plus 1 MiB for the other fields), and
  `/api/mcp`'s transport (`McpServerConfig.MAX_REQUEST_BYTES`) uses the same
  constant. The JSP editor posts urlencoded (a 3-byte character is 9 bytes),
  so `server.tomcat.max-http-form-post-size` in `application.properties` is
  `9 * ENTRY_TEXT + HEADROOM_BYTES` (10,048,576); `EntryWriteLimitsTest` pins
  the API limit and the form-post property (`McpServerConfigTest` pins the MCP
  limit to the API's), so raising the column cap means moving that property too.
  `ColumnLimits.ENTRY_TEXT` (1,000,000 characters) caps entry text in
  `EntryDtos.applyWrite`, so edits cannot grow an entry past it a request at a
  time. `TextDiff` caps its table at `MAX_CELLS` after matching the common
  start and end.
- **Category and media-folder names (spec 2026-11-20-jsp-rules-and-view-models-design, Task 3)**: a
  category name must survive `escapeHtml4` on both surfaces (`&`, `<`, `>`, `"`; an apostrophe passes), but a
  PATCH resending the category's own name is never judged again, so an `&` stored before the rule stays
  editable; the Categories screen's edit does the same through `CategoryRules.keepsItsName` (0.16.3). A folder name loses one leading slash, then may hold no slash (`directory.nameInvalid`). A file
  rename is judged against the directory the file will be in (`media.nameInUse`, a 409 raised in
  `MediaDtos.applyPatch` after every 400 and before anything is applied). A move with no `name` is
  judged on the name the file keeps whenever the target folder is not its current one (final review
  I1; it used to pass unjudged and leave two files at one public address).
- **Media folders, listing and metadata, page preview (React migration Wave 2)**: the Media Files
  screen and the API share one copy of each rule. `MediaDirectories` holds the delete rule (not the
  default folder, nothing in it: the screen only ever offered the button there, and the JSP action now
  refuses the same, so a crafted POST can no longer wipe a folder), the delete and the private flag
  (both save the weblog, which is what expires rendered pages). `MediaListing` holds the sort keys
  and the search (a name prefix and a type through `searchMediaFiles`); name order ignores case on both
  surfaces. `MediaMetadata` is the one tag write. `PageRules.previewScratch` builds the page both
  previews render. The API states `isPrivate` rather than toggling it. Limits the JSP never checked
  (description 255, copyright 1023, tag 30 characters: the columns) are 400s here; on the form they are
  still a 500, which is a known gap. MCP: page `preview` and media `update_directory`/`delete_directory`
  are actions (an agent previews shortcodes and tidies folders; a delete is refused unless empty).
- **A category name lost to an overlapping create** is a
  `DuplicateCategoryNameException` from the manager's locked check: the API
  answers 409 on `name`, the category screen its duplicate-name error.
- **`PressroomHandlerInterceptor`'s imports of `ui.restapi.ApiException` and
  `ui.restapi.ApiExceptionHandler` (only for its package name, to tell an API
  handler from a JSP one) are the ONLY permitted imports from `ui.restapi`
  into `ui.controllers`.** A second interceptor for `/api/**` would be exactly
  the parallel authorization path avoided here.
  `grep -r "import com.wikantik.pressroom.ui.restapi" app/src/main/java/com/wikantik/pressroom/ui/controllers/`
  must return exactly those two lines, both in `PressroomHandlerInterceptor`.

## Problem codes (2026-11-20)

Spec `docs/superpowers/specs/2026-11-20-api-contract-design.md`, Task 1. Every problem body and every
field error carries `code`, the last JSON member (so earlier members serialise as before), never null.

- **`ProblemCode` is the catalogue**: the wire string (dotted lowerCamel), its `ProblemType` (status,
  `type` URI, `title`) and its arity. The message key `api.problem.<code>` in
  `ApplicationResources.properties` is an implementation detail; the code is the contract. `http.error`
  (MCP's `about:blank`, `ApiException.httpError`) is the one code with no `ProblemType`.
- **Adding a code**: the constant; its English under its key (plain text, no `<`); its README "Errors"
  table row (`ProblemCodeDocsTest`); its string in `ProblemCodeCatalogTest`'s pin. Never rename or remove
  one. An English with no placeholder is used as written (single apostrophes, as the JSPs); one with
  placeholders goes through `MessageFormat`, so a literal apostrophe is `''` and a literal `{` is quoted
  (`MessageFormatRegressionTest`). Arity is the count of distinct `{n}`, held to the English and every
  translation by the catalogue test. A code names a meaning, not a call site.
- **Naming convention** (final review, 2026-11-21; `ProblemCodeCatalogTest` holds the first two):
  `<resource or field>.<condition>`, exactly two lowerCamel segments. `notFound` (or a condition
  ending in `NotFound`) is a 404 and only a 404 says it; a 400 for text that is not there says what
  did not match (`edits.findNotMatched`). `inUse` is a uniqueness conflict (`slug.inUse`,
  `redirect.sourceInUse`, a 409 since 0.16.0; every `*.inUse` code is, `ProblemCodeCatalogTest`).
  `tooMany` is a count over a cap (`edits.tooMany`, `pinned.tooMany`, `revision.tooMany`, `strand.tooMany`), `tooMany<Thing>` only where one resource has two caps.
  Before the 0.16.0 tag the final review renamed the codes that broke this (old to new:
  `edits.notFound` `edits.findNotMatched`, `strand.notAStrandPage` `strand.idNotStrandPage` (an
  entry's `strand` is not a strand page's id), `strand.pageNotAStrand` `strand.flagNotSet` (parts set
  on a page whose `strand` flag is off), `entry.status.trashNotWritable`
  `entry.trashStatusNotWritable`, `page.status.trashNotWritable` `page.trashStatusNotWritable`,
  `token.cannotMintToken` `token.mintRefused`, `pinned.limit` `pinned.tooMany`,
  `revision.keepLimit` `revision.tooMany`, `strand.tooManyParts` `strand.tooMany`,
  `redirect.sourceExists` `redirect.sourceInUse`), split `comment.replyRefused` into one code per
  `CommentRefusal` (`CommentsApi.code`, an exhaustive switch; the English is unchanged, `The reply
  was refused: <NAME>`) and dropped `redirect.refused`. From the tag on, the rule above stands: never
  rename or remove one.
- **Raising one**: `ApiException.problem(code, args...)`, `ApiException.problemOnField(field, code,
  args...)`, `FieldErrors.add`/`addIfAbsent(field, code, args...)`. Arguments are `String`s: format a
  number at the call site exactly as it should read, or `MessageFormat` would print `1,000`. A wrong
  argument count logs a WARN naming the code and renders with what it has; the status stands (the
  status is the decision, the text is display).
- **Unkeyed problems are gone from the public surface (Task 3)**: `ApiException` has no public factory or
  constructor taking a free-text detail (`ApiExceptionSurfaceTest` pins its public factories by signature),
  so outside `ui.restapi` an unkeyed problem does not compile. The literal factories (`badRequest`,
  `notFound`, ...), `FieldErrors.add(field, message)` and `ApiProblem.FieldError(field, message)` are
  package-private, kept as fixtures of the mechanism's own unit tests (whose assertions the spec forbids
  editing); `ApiProblemSourceScanTest` scans every main file under `ui.restapi` and
  `PressroomHandlerInterceptor` and fails on a call to one, a literal `FieldErrors.add`, or a wire
  `FieldError`; since 0.16.3 also on a `FieldErrors.add` whose code is a bare variable (it must be a
  `ProblemCode` constant or a call answering one, `shape.code()`), a static import of a literal factory,
  a hand-built `ApiProblem`, and `ApiProblemWriter.write(response, problem)` whose problem is not a keyed
  `ApiException` rendered on the spot (`...toProblem(uri)`). `busy(String)` is deleted. A code with no fixed status (`http.error`) asked for as a keyed
  problem is the opaque 500 and a WARN, and a null argument array is no arguments: an error path never
  throws.
- **Codes on the wire**: one field error lends the problem its code; several make it
  `request.invalidFields`, whose `detail` is the rendered field messages joined by one space, as
  `FieldErrors.throwIfAny` always joined them.
- **Localisation**: `getMessage()` is always the English, read from the base bundle with
  `ResourceBundle` at `Locale.ROOT` (`ProblemMessages.english`), for logs and every existing assertion.
  `ApiExceptionHandler` and `ApiProblemWriter` (below) localise: each reads the `Accept-Language` header itself (never
  `request.getLocale()`, which Tomcat answers with the JVM default when the header is absent) and
  `ProblemMessages.localeFor` matches it against the shipped bundles in q order, basic filtering then
  lookup (so `fr-CA` reaches `fr`). English, no match, or no header renders at `Locale.ROOT`, because a
  `ResourceBundleMessageSource` lookup for a locale with no bundle (English has none) falls back to the JVM
  default locale (`ProblemLocalisationTest` runs with German as the default). No `Content-Language` or
  `Vary`: a matched language can fall back to English key by key. `title` is not localised. No
  translations of `api.problem.*` ship yet. A translation `MessageFormat` will not parse (`{name}`)
  makes the source throw `IllegalArgumentException`; `ProblemMessages.renderer` catches it, logs a WARN
  and renders the English, so the advice and the security writers never fail into a non-problem 500
  (`ProblemLocalisationTest`, `SecurityChainProblemCodesTest`). `ProblemCodeCatalogTest` refuses such a
  value in any shipped bundle.
- **Writers outside the advice localise too (Task 3)**: `ApiProblemWriter.write(request, response,
  ApiException)` renders as `ApiExceptionHandler` does (both go through `ApiException.toProblem(request,
  messages)`); the bean takes the application's `messageSource`, and the one-argument constructor builds
  the same source on `ApplicationResources` (`ProblemMessages.applicationResources`). The entry points
  (`auth.unauthorized`, `auth.passwordNotAcceptedHere`, `auth.passwordRejected`), the access-denied
  handler (`auth.forbidden`), the throttle (`request.throttled`), the body limit (`request.tooLarge`)
  and `ApiScopeInterceptor`'s direct write use it (`SecurityChainProblemCodesTest`, French from the test
  bundle). `applicationResources()` pins its bundle loader to `ProblemMessages`' own class loader:
  Spring's default keeps the building thread's context loader, so `ApiScopeInterceptor`'s static
  writer, first initialised on an embedded Tomcat thread by `ContextRefreshDoesNotBootstrapTest`, held
  that Tomcat's web-app loader after it stopped and turned later 404s into 500s (0.16.0 CI, test-order
  dependent). A bundle that will not load (`MissingResourceException`) also renders the English
  (`ApiProblemWriterTest`). English only: `toProblem(instance)` for MCP, its tool errors and the endpoint's own 401
  (`mcp.tokenRequired`, decision 8). MCP is always English: `DispatchedRequest` exposes no
  `Accept-Language` (`DispatchedRequestTest` pins it).
- **`ApiException.authenticationRequired()`** is the keyed 401 `Authentication required.`
  (`auth.unauthorized`), raised by `TokensApi` and `MetaApi` when the caller is not authenticated.
- **Content routes are fully keyed (Task 2)**: `EntriesApi`, `EntriesWriteApi`, `EntryContent`,
  `EntryEdits`, `EntryFields`, `EntryImports`, `RevisionedContent`, `PagesApi`, `PageContent`,
  `BaseApiController`, `ColumnLimits`, `EntryDtos`, `PageDtos`, `SeoFields` and `ClearFields` raise
  every problem and field error with a code. `ContentProblemSourceScanTest` fails on a call to an unkeyed
  `ApiException` factory, or a `FieldErrors.add` with a literal second argument, in those files, so a new
  refusal there needs a code. A code names a meaning, not a call site (`entry.notFound` is five sites;
  `field.required` takes the field as `{0}`). The bundle value is the old text exactly. A literal brace
  in a value with placeholders cannot be quoted (`MessageFormatRegressionTest` bans a lone apostrophe),
  so it is an argument (`edits.empty`'s `{1}` is `{find, replace}`). Text that is a business exception's
  message (an image import's refusal) is a passthrough code whose last placeholder is that text (`image.importFailed` is `Image {0}: {1}`); they are listed in
  `ProblemCode.PASSTHROUGH_CODES`, which `ProblemCodeCatalogTest` pins and which may only shrink. A
  code that has a REST test asserts its `code` (and `errors[].code`): `EntryProblemCodesTest`,
  `EntryImportProblemCodesTest`, `PageProblemCodesTest`.
- **The rest of the API is keyed (Task 3)**: media, categories, comments, redirects, tokens, admin, weblog
  create and settings, audit, traffic, `AdminAccountGuard`, MCP (codes under `mcp.`), the `auth/` writers
  and `PressroomHandlerInterceptor`'s refusals (`ApiException.weblogNotFound()` and `permissionDenied()`,
  so it keeps its two imports). Each has a REST test asserting `code` beside the old English
  (`*ProblemCodesTest`; their English half passed on the code before Task 3). The weblog rules shared with
  the forms (`WeblogCreationRules`, `WeblogSettingsRules`) give each `Violation` a `Reason` and `args`
  beside its form key and English; `RuleProblems` (`ui.restapi` since 0.16.3) maps reason to code with an
  exhaustive switch, so a new rule does not compile without a code, and `RuleProblemsTest` pins each
  violation's status (or conflict flag) to its code's `ProblemType`. `RuleProblems` is the one mapping from
  any broken rule (a `RuleViolation` included) to a problem or field error: nothing unpacks a violation's
  field, code and args inline. A redirect refusal is `RedirectRefusedException` (a
  `WebloggerException` with a `Reason`), every one of the manager's refusals included (a rule with no
  weblog, a blank path); any other `WebloggerException` from `saveRedirect` is a failure, not a
  refusal, and is the opaque 500 (the final review dropped `redirect.refused`, whose detail could carry
  the persistence layer's text). `PASSTHROUGH_CODES` is down to two: `image.importFailed`
  and `media.importRefused` (an import's own failure text); it may only shrink. 0.16.3 keyed
  `image.scanRefused` (the sentence is the bundle's, the tag its `{0}`).
  `auth.notAuthenticated` and `auth.unauthorized` mean the same (README says so); both stay published.
- **Cross-product check** (2026-11-20): `SimpleAgilityDecisionLog` (SA-1 to SA-17) holds no suite-wide
  error-code convention, so the member name `code` and the dotted lowerCamel format are Pressroom's own.
  If one is decided later, it supersedes this.

## Site settings over the API (2026-11-21)

Spec `docs/superpowers/specs/2026-11-20-jsp-rules-and-view-models-design.md`, Task 1.
`AdminApi.patchConfig` applies `SiteSettingsRules` (type and range,
trimming, the front door, the privacy page; a lookup that throws refuses) and
`SiteSettingsEffects.afterSave` (touch every weblog and the site space on a `RENDERED_PAGE_INPUTS`
change, then refresh discovery on a changed `site.discovery.*` value). `patchConfig` keeps, in order:
the empty-body refusal, `AdminAccountGuard.requireApiManageable`, then per name
`AdminDtos.requireRuntimeProperty` and the null-value refusal (each thrown at once); then the rules,
whose violations go out through `RuleProblems.refuseViolations` (one 400 naming each). It reads the
two front-door rows even when the patch names one, because "both non-empty" counts the stored value.
New in 0.16.0 (README change note under `GET/PATCH /api/v1/admin/config`): codes
`config.frontDoorSiteSpace`, `config.frontDoorPageUnpublished`, `config.frontDoorBothSet` (the form
cannot reach it: its select blanks one setting), `config.privacyPageUnpublished`; trimmed strings; the
cache touch; several type errors in one 400. Mixed requests now report an unknown name or a null value
before any type error, where the first bad entry used to win. `AdminConfigCharacterisationTest` pins
the wire bodies; `GlobalConfigCharacterisationTest` the screen's.

## Accounts over the API (2026-11-21)

Spec `docs/superpowers/specs/2026-11-20-jsp-rules-and-view-models-design.md`, Task 2.
`AdminApi.createUser`/`patchUser` call `AccountRules` (`ui.controllers`), the rules the person and
agent screens call, through `RuleProblems.add` (field errors); the 409 for a taken name is
`createUser`'s own `ApiException.problemOnField` from `userNameTaken`'s violation. The API keeps its
trimming, its blank test (`StringUtils.isBlank` before `userNameProblem`, so a name of Unicode
whitespace such as U+2003, which `trim()` keeps, is `field.required` as it always was; the screens
judge what was typed with `isEmpty`), the screen name's default (the username; a blank default is
not judged, since the blank username already is), and the order: field checks, then the taken check
(`userNameTaken`, whose lookup failure is the opaque 500), then the mail check. Agent writes stay
refused by `AdminAccountGuard` before any of this. `emailAddress` is judged with commons
`EmailValidator` (trimmed); `EMAIL_PATTERN` and `requireValidUserName` are gone. Intended in 0.16.0 (README change note under
`POST /api/v1/admin/users`): `a@b.c`, reserved TLDs (`.test`, `.example`, `.invalid`, `.localhost`) and
local parts over 64 characters are refused; quoted local parts are accepted. The codes and details are
the old ones (`user.emailRequired` on create, `user.emailInvalid` on patch). Test fixtures that post an
address use `example.com`: `example.test` is refused now. `AdminUsersCharacterisationTest` pins the wire
bodies.

## Users and Maintenance, token-safe parts (React admin Wave 4, 2026-11-25)

`AccountRules` gained `fullNameProblem`, `localeProblem` and `timeZoneProblem`; the person screen judges a
full name, locale or time zone only when it differs from the stored value (older accounts keep saving), the
API judges whatever is sent. `AccountRoles.sync` is the one grant/revoke of a role (the screen's admin and
editor boxes, the API's `writer`); `writer` is refused on a non-SSO account (`user.writerSsoOnly`), the
screen's own rule. `PasswordLinkService` (`ui.controllers.core`) is who may be mailed a link
(`refusal`: agent, SSO, no mail) and issue-and-mail (`send`); the screen's "send set-password link", `POST
/v1/admin/users` and `POST .../password-link` all call it. `Memberships.of` lists an account's weblogs for
`GET .../users/{userName}/weblogs` (read only; writes are SA-18 session-only). `GET /v1/admin/users` with
`q` or `kind` uses `UserManager.getAccounts` (the screen's search) and pages in memory. `AdminSiteStatusApi`
serves discovery and analytics status, `discovery/refresh` and `analytics/sync` from the same managers
`MaintenanceController` calls. MCP: `users.weblogs` is a tool action; password-link and the four Maintenance
routes are `Exclusion`s (mail to a person; operator maintenance). Not here, by SA-18: admin flag,
memberships, another user's email.

## Site facts and the caller's profile (2026-11-21)

`GET /v1/meta` and `GET /v1/me/profile` live on `MetaApi`, both
`@WeblogScopeExempt`, authenticated-only, matrix rows equal to `GET /v1/me`.
Nothing in `meta` is cached: every flag is read per request through
`WebloggerRuntimeConfig.getBooleanProperty` (and `SsoSettings.fromConfig`,
`AnalyticsManager.getStatus().configured()`), and `siteUrl` reuses
`SsoConfig.configuredAbsoluteUrl` (now public), never `getAbsoluteContextURL`.
`limits.maxListLimit` is `Paging.MAX_LIMIT`, the one constant the list
controllers cap against. `ProfileView` is pinned by `MetaApiTest` to its
component set: a new member is a deliberate change and never a credential.
The profile is excluded from MCP; `site_info` is the tool for `meta`.

## Site-wide traffic (2026-11-21)

`GET /v1/admin/traffic` is `AdminTrafficApi`, a controller of its own (declarations are per
class; `AdminApi` is already large): `GlobalPermission.ADMIN`, no weblog, `@AdminScoped`,
matrix row equal to `GET /v1/admin/users`. It does NOT reuse `SiteTrafficView`
(`ui.controllers.admin`): that record is the page's display strings (formatted numbers, the
em dash). `TrafficDtos.toSiteView` maps `SiteTraffic` itself, sharing `post` with the blog
route. `NOT_CONFIGURED` lists nothing, as the page shows no body; an unread blog's numbers
and a NaN `change` are null. Both traffic reads carry `timeZone` (the zone `asOf` and the day buckets read
in: the weblog's, or the site space's for the site read), and the blog read `umamiUrl` and
`registrationError` (why a weblog has no numbers because registering failed; what the page always told the
weblog's admin) through `TrafficReads`. `GET /v1/admin/traffic/{handle}` is the same blog read for any weblog,
the site space included (the weblog route never offers it): site admins only, MCP `Exclusion`.

## The main menu's rows (Wave 0 screens, 2026-11-25)

`GET /v1/me/weblogs` (`MetaApi`, `@WeblogScopeExempt`, user required, no permission) is what the React home
shows: per weblog the caller can act on, sorted by handle, `about`, `theme`, `absoluteUrl`, the strongest
`permission`, the stored `actions` (the role sentence needs the literal actions: a creator holds `admin`
alone), `canModerate` (implied POST, as the interceptor checks, never the literal action), `entryCount` and
`pendingComments` (0 unless the caller may moderate); plus `siteSpace` (its handle, created on first read) for
a site admin with an unpinned token. One call instead of three reads per weblog, and the settings route is the
weblog owner's while the menu is every member's. A failed count is a 500, not a 0 (the JSP degraded; an API
must not report a wrong number). `WeblogView` (the settings read) gains `absoluteUrl` through
`BaseApiController.weblogView`. MCP: `Exclusion` (`me` lists the weblogs).

## A weblog's members, read (2026-11-21)

`GET /v1/weblogs/{handle}/members` is `MembersApi`, declaring what the Members page did
(`ADMIN`, login); not `@AdminScoped`, matrix row equal to `.../traffic`. The level
is `Memberships.strongestLevel`, shared with `/v1/me`'s weblogs. An account that does
not resolve is skipped (as the Members page does, logging a warning); any lookup that
throws is a 500. The envelope adds `total` to the house four. Membership WRITES stay
session-only on purpose (owner decision 2026-11-21): no token route changes a member.

## Business profiles (2026-11-21)

`/v1/admin/businesses` is `BusinessesApi` (`GlobalPermission.ADMIN`, no weblog,
`@AdminScoped`, as `BusinessesController`); the owner's picker `GET
/v1/weblogs/{handle}/businesses` is `WeblogBusinessesApi` (weblog `ADMIN`, `@AdminScoped`,
as `WeblogSettingsApi`). Matrix rows equal `GET`/`POST /v1/admin/users`, `PATCH
.../users/{userName}` and `GET /v1/weblogs/{handle}` (`businessRowsEqualTheirReferenceRows`).

- **One set of rules**: `BusinessRules.problems/apply/invalidateUsers` over a
  `BusinessRules.Values` record (raw strings; `sameAs` newline-separated). The JSP save maps
  its bean to `Values` and marks `bean_<field>`; the API lays the body over
  `Values.of(business)` (`BusinessDtos.overlay`) and refuses through
  `RuleProblems.refuseViolations`, so every violation is one 400. Nothing touches the
  managed entity before the rules pass. `BusinessesControllerRulesTest` is the
  characterisation pin of the screen's messages.
- **A label argument is a `RuleViolation.Label`**: the shared adapter
  (`BaseController.addError(RuleViolation)`) resolves it with `getText` and does not escape it
  (bundle text); every other argument is escaped. A message whose label used to be
  `getText(labelKey)` in a controller moves to a rule this way.
- **Codes**: the spec asked for `businesses.error.inUse`, but codes are two segments
  (`ProblemCodeCatalogTest`), so it is `business.inUse` (409, arg = the count) beside
  `business.notFound`. The in-use check stays per surface: the screen's message takes a
  `{1,choice}` number, which the adapter's string arguments cannot feed.
- `sameAs` arrives as a list and is joined with newlines before the rules (null entries
  skipped), so an entry holding a line break is judged as two links. `[]` and `clear` both
  remove the list.

## The OpenAPI document (2026-11-20)

Spec `docs/superpowers/specs/2026-11-20-api-contract-design.md`, Task 4. `/api/v1/openapi.json` is
generated by springdoc from the `ui.restapi.v1` controllers; `OpenApiContractTest` generates the whole
document (every `@RestController` there, found by scanning, with the `springdoc.*` lines of
`application.properties`) and holds it to what a client generator needs.

- **Operation ids are `<resource><Verb>`, explicit and pinned.** Every handler carries
  `@Operation(operationId = ...)`: the resource in lowerCamel plural as the path names it (`entries`,
  `pages`, `media`, `users`, `config`, `maintenance`; `ping` and `me` for the two meta routes), then the
  verb: `List`, `Get`, `Create`, `Update`, `Delete` for the plain CRUD routes, and the API's own word
  otherwise (`entriesTrash`, `entriesRestore`, `entriesDeleteForever`, `pagesRestoreRevision`,
  `tokensRevoke`, `mediaImport`). A DELETE that moves to the trash is `Trash`, never `Delete`.
  `OpenApiContractTest.OPERATION_IDS` pins the set by equality: **a new operation adds its id there**,
  and renaming one is a breaking change to every generated client. Without an explicit id springdoc
  names an operation after its Java method and de-duplicates with `_1`, `_2`, in an order that
  depends on scanning; the test refuses a `_<digit>` suffix.
- **Operation ids do not name MCP tools.** `McpToolCatalog` declares each tool and action by name
  with its route, so an operation id can change without touching an agent (`McpToolCatalogTest` pins
  the names). Argument descriptions are shared, though: `ArgumentDeriver` reads the same
  `@Schema`/`@Parameter` text, and a property several actions take keeps the first described one
  (`ToolSchemas`). Adding a description to a query parameter can therefore replace the body's text an
  agent sees; that is why `MediaReadApi.list`'s `directoryId` and the list filters carry none.
- **One tag per controller**: `@Tag(name, description)` on the class.
- **Every operation's `default` response is the problem**: `ApiDocumentCustomizer` (an
  `OpenApiCustomizer`) adds `ApiProblem` and `ProblemFieldError` as component schemas from
  `ApiProblem`'s own `@Schema` text and gives every operation a `default` response of
  `application/problem+json`. `code` is a plain string carrying `x-extensible-enum` (every
  `ProblemCode`), never an `enum`, so a client built before a code existed still reads it.
- **Success responses are `application/json`** (`springdoc.default-produces-media-type`), each a named
  schema or an array of them, or a 204 with no body. Document a status the method sets itself with
  `@ApiResponse(responseCode = ...)` and no content: springdoc fills the schema from the return type.
  No handler returns a `Map`: `ping`, `me` and the maintenance actions answer records
  (`MetaDtos`, `AdminDtos.MaintenanceAccepted`) with the JSON they had (`UntypedResponsesJsonTest`).
  The only bare maps left are `PATCH /admin/config`'s open body and the keyed dictionaries
  `SeoAudit.counts` and `TrafficView.breakdowns`, each listed in the test.
- **Descriptions**: every enum property or parameter, every request-body property (and anything nested
  in one) and every `required` property needs one.
- **A multipart upload documents its parts as the body**: springdoc reads a `@RequestParam
  MultipartFile` as a query parameter, so `MediaApi.upload` hides its two parameters
  (`@Parameter(hidden = true)`) and declares the `multipart/form-data` body on its `@Operation`
  (`file`, an array of binary parts, required; `directoryId`). `OpenApiContractTest` pins it.
- **No two API types share a simple name**: springdoc names a schema by it and silently merges two
  (`PageDtos.RevisionRestore` once gave the page's restore the entry's text; it is
  `PageRevisionRestore` now). The test scans `ui.restapi.dto` and `ui.restapi.v1`.

## MCP endpoint
`/api/mcp` (spec `docs/superpowers/specs/2026-10-11-mcp-endpoint-design.md`) is a facade over
`/api/v1`, never a second API.

- **A tool call is forwarded in process** (`InProcessApiDispatcher`, `RequestDispatcher.forward`)
  to its `/api/v1` route, on the request thread (the SDK server runs with immediate execution). So
  `PressroomHandlerInterceptor`/`ApiScopeInterceptor` decide exactly as for REST. The Spring Security
  filter chain does run again on the FORWARD (`spring.security.filter.dispatcher-types=request,forward`
  in `application.properties`), but authentication and the throttle run once, because
  `ApiTokenAuthFilter` is a `OncePerRequestFilter` and its marker is still on the request. A
  dispatched call sees only its own query parameters, never the MCP request's, and keeps attributes
  it sets itself (an overlay in `DispatchedRequest`); `CapturedResponse` is standalone. Never give a
  tool its own permission check.
- **Adding an `/api/v1` route means deciding its MCP action in the same change**: an `ActionDecl` in
  `McpToolCatalog`, or an `Exclusion` with a reason. `McpCoverageTest` fails until you do, and
  `McpDocsTest` until the README's MCP table matches.
- **Adding an editing field means deciding its API field in the same change, in `EditableFieldManifest`.**
  `FormBodyParityTest` (spec `docs/superpowers/specs/2026-11-08-agent-api-parity-design.md`, G1-G4,
  re-anchored by `2026-11-20-jsp-rules-and-view-models-design.md`, Task 7) is anchored on the manifest
  (test code, `ui.restapi`), which lists per resource (`entry`, `weblogSettings`, `page`) the fields an
  editing screen offers, in API vocabulary, plus the body fields no screen offers, each with a reason
  (`edits`, `expectedContentHash`, `clear`...). It names no form bean. Check A: every manifest field is a
  component of the body record (`EntryWrite`, `WeblogPatch`, `PageWrite`). Check B: every body component
  is a manifest field or an API-only field with a reason, so a new API field cannot arrive undecided.
  Check C, the JSP half: each form bean's properties (getter plus setter) map onto the manifest through
  the test's rename table (`content` -> `text`) or an exclusion with a reason (`pinnedToMain`), and every
  manifest field is reached by a bean property. A new editing field goes into the manifest and the body;
  removing a JSP removes only its C row, and A and B keep running. The manifest is what a future React
  form is held to (no React work exists yet). `McpCoverageTest` holds routes to tools; this holds bodies
  to screens, which is how the page SEO card went missing from the API.
- **Rules and view models stay screen-independent** (`ScreenIndependenceTest`): every class in `ui.view`
  and each named rule class imports no `jakarta.servlet`, `org.springframework.ui`/`web`, `ui.restapi`
  (except `ProblemCode`) or `*Bean` under `ui.controllers`; a class added to the list that does not
  exist fails it. Add a new rule class to the test's list. A view model also imports nothing from
  `ui.controllers` (0.16.3): `WeblogPermissionView`, `EntryListQuery` and `KeyValueObject` moved to
  `ui.view`, and the count format and em dash the traffic screens share are `ui.view.Figures`.
- **One rule per shared field, one home each**: `SeoFields` (meta title, search description,
  canonical URL, og image id length) serves `EntryDtos` and `PageDtos`;
  `BaseApiController.checkImageId` is the public-image check for both; `SharedThemeRules` is the
  Theme page's `offered` rule and switch, shared with `PATCH /weblogs/{handle}` `theme`.
- **Arguments are derived, not declared** (`ArgumentDeriver`). Path variables (`{handle}` becomes
  `weblog`), `@RequestParam`s and the `@RequestBody` record's components become arguments. A path
  value must be one segment (empty, `.`, `..`, or containing `/`, `\` or `;` is refused).
  springdoc `@Schema`/`@Parameter` descriptions are the shared text for `openapi.json` and the tool
  (`McpIT` compares `EntryWrite.pubTime`); a nested record's components (`EntryDtos.TextEdit`) carry
  theirs too. A body record gaining a field gains a tool argument with no MCP change. An unknown
  parameter type stops startup.
- **One property serves every action that takes an argument**, and `ToolSchemas` keeps the first
  description it meets. So an argument two body records share needs one text that holds for both:
  `EntryWrite.edits` and `PreviewRequest.edits` both use `EntryDtos.EDITS_DESCRIPTION`.
- **`AdminAccountGuard` is mandatory for any API route that changes a user**: deletion, profile
  self-edit, role grants or revocations. Its checks take a `Caller` (pass `Caller.current()`) and
  decide by caller kind: a `SESSION` caller (the admin UI) is exempt, and every other kind (`TOKEN`,
  `PASSWORD`, `UNRECOGNISED`) is refused on an admin account, the admin role, an agent or a UI-only
  setting. Admin accounts and the admin
  role are read-only through the API (403 `Admin accounts are managed in the admin UI.`).
  `AdminAccountGuard.UI_ONLY_SETTINGS` (today `site.absoluteurl`, the base of every password-reset
  link) are likewise never written through `PATCH /admin/config` (403, nothing applied); add a
  setting there to protect it. Agent accounts likewise: `requireNotAgent` answers 403 `Agent accounts are managed in the admin UI.`, and `patchUser` calls it before `requireNotAdmin`.
- **The `SESSION` allowance in `AdminAccountGuard` and `TokensApi` is safe only because no UI-chain
  request forwards into `/api/**`.** A forwarded request inherits the security context (the MCP
  in-process forward above is exactly that), so a JSP controller forwarding into `/api/v1` would
  carry an admin-UI session's powers past both guards. Never add such a forward.
- `McpEndpointServlet` refuses non-token callers. The chain already refuses Basic on `/api/mcp` (it
  authenticates only the token resource); the servlet's check does not rely on that.
  `McpOriginValidator` refuses a foreign `Origin`. Each call logs one `pressroom.mcp` INFO line, with no
  argument values; the weblog appears only when it is handle-shaped (else `?`). That logger goes to
  the console appender, like `pressroom.redirects`, not to `pressroom.log`.

## New-weblog needs (step 2)

- **`WeblogSettingsRules` and `WeblogCreationRules`** are the one home for those checks. Both the JSP
  controllers (while any remained) and the API called them; the API and the React screens now do. The settings PATCH lays the request over `WeblogConfigBean` and
  stores it with the form's own `copyTo`. Violations are reported only when they involve a touched
  field.
- **`ClearFields`:** null never removes; `clear` does. A new clearable field goes in the resource's
  `*_CLEARABLE` list and its `apply` switch.
- **`RemoteImageFetcher`:** no bypass. Only a package-private `Policy` constructor, for tests. It must
  never gain a config switch. Any change to `PublicAddresses` updates the table test at the range's
  edges.
- **Event times:** wall-clock times in the weblog's zone, editor and API alike
  (`EntryFieldRules.parseEventTime`/`formatWallClock`). A value with `Z` or an
  offset is the instant it names (`EntryFieldRules.parsePubTime`), because
  `EntryView` reads every time back as an instant and a GET-then-PATCH must
  round-trip (`EntryTimeRoundTripTest`).
- **Import logging:** `pressroom.media.import` logs the host only.

## Entry writes: check, import, apply

- **`checkWrite` runs on `EntryDtos.scratchOf`**, a copy of the entry. A refused write changes
  nothing, and `persistNewMediaFile` flushes mid-request, so nothing may be applied to the entry
  before the import step has succeeded.
- **`FieldErrors` collects only 400s.** A 404, a 409 or an unexpected failure is not a field problem
  and propagates at once, so a clash found during a write is never merged into the 400 list.
- **`expectedContentHash` is checked first** (spec `docs/superpowers/specs/2026-10-24-agent-editing-and-seo-design.md`, section 4):
  right after the live-entry lookup, before `checkWrite` and so before the import step can fetch.
  A mismatch is `ApiException.entryChanged`, a 409 whose `currentContentHash` is an RFC 9457
  extension member of `ApiProblem` (null, so omitted, on every other problem; `code` is the other). `contentHash` is
  `EntryDtos.contentHash`: SHA-256 of the UTF-8 text, lower-case hex, Wikantik's scheme; keep the two
  stacks on one.
- **...and checked again under the row lock, immediately before the save.** The first check is
  unlocked and the import step after it commits and can take a minute, so two agents sending one
  hash both passed it and the second save silently discarded the first (code review, 2026-11-03).
  `requireUnchangedUnderLock` calls `WeblogEntryManager.lockEntryText` (`SELECT text ... FOR
  UPDATE`, held until commit; a concurrent save is waited for and its committed text returned) and
  compares with the text the write was checked or computed against. Taken only by a write that
  names the text it read (a hash, or `edits`); restore too. `EntryRowLockTest` pins the lock against
  PostgreSQL; nothing after the lock may commit before the save, or the lock is gone.
- **`edits` become text before any check.** `EntryEdits.resolve` applies them to the stored text and
  `EntryWrite.withText` puts the result where a sent `text` would be, so validation, the import plan
  (which scans `body.text()`) and the one save treat it as sent text with no edits-specific branch.
  A failing edit is a `FieldErrors` entry on `edits[i]` and is skipped, so it is reported with the
  write's other field problems and the import step never runs. Exact strings by design, never
  patterns (spec, Decisions).
- **`expectedContentHash` serves update and the revision restore**, so its `@Schema` text is
  `EntryDtos.EXPECTED_CONTENT_HASH_DESCRIPTION`, one text that holds for both (the restore takes
  it in an optional body record, `RevisionDtos.RevisionRestore`, so a bare POST still restores).
  `revisionNote` is update-only and its own field; see `entries.md`, "Revision notes".
- **`fields` is `EntryFields`, one projection for every entry route** (spec section 2). It keeps
  the asked record components of `EntryView` and their non-null values, so the converter writes them
  exactly as in the full view; `EntriesApiFieldsTest` pins that asking for every name writes the full
  view, so a `@JsonProperty` rename or a new null rule on the view fails there. A handler taking
  `fields` returns `Object`, and its `@ApiResponse(content = @Content(schema = @Schema(implementation
  = ...)))` is what keeps openapi.json naming `EntryView`/`EntryPage`: `EntriesOpenApiTest` generates
  the document with springdoc and pins it. A write records an unknown name in its `FieldErrors`.
- **`MediaImports` is the one import path**, shared by `media/imports` and the entry writes. It
  commits each file as it is stored (a file on disk must have its row), and logs the host only.
- **Preview `warnings` come from `PreviewLint`, over the sanitizer's output.** Its attributes are
  always double-quoted, so a pattern is exact enough. The warnings are advice, never a refusal: the
  same text saves. `PreviewLint` has two rules: an inline `color`/`background` (the first agent post's
  pale callouts were unreadable in the dark portfolio theme) and a code block naming no language.
  After them come the SEO body checks' messages (`SeoBodies`, the same findings `audits seo_entry`
  reports; `docs/dev/content-features.md`, SEO). The CLI prints them on stderr, so stdout stays the HTML.
- **`EntryImports.BUDGET` (60 s) and `MAX_BODY_IMAGES` (10).** Cloudflare ends a request at 100
  seconds, and the entry still has to save after the last fetch; each fetch has its own 30-second
  deadline, so the budget only decides whether a new fetch starts.
- **An entry's `strand` is resolved twice, never stored between** (`EntriesWriteApi.strandFor`):
  once in `checkWrite`, where a page that is not one of this blog's strands (through
  `WeblogOwnership.page`, so another blog's page and an unknown id are one 400) refuses the write,
  and again in `apply`, which calls `WeblogEntryManager.appendToStrand` (append, or move from
  another strand). The strand the entry is already in resolves to nothing, so re-sending it keeps
  its position, as in the entry editor. Leaving is `clear: ["strand"]`, applied by
  `EntryDtos.clearField` on both fields (the pure twin of `leaveStrand`). `FormBodyParityTest`
  renames the editor's `strandPageId` to `strand`.

## Strands over the API (2026-11-15, release 2)

- **`PUT /v1/weblogs/{handle}/pages/{id}/parts` is the API's only `PUT`**: it replaces the whole
  membership, which is what PUT means, where every other write is a partial `PATCH`.
  `ApiScopeInterceptor.isWrite` treats every method but GET/HEAD as a write, so it needed no change;
  `bin/pressroom-api` has no command for it yet. At most 500 ids (`PagesApi.MAX_PARTS`); more is a
  400 on `entries`, refused before the first lookup, since each id costs one.
- **Parts ids go through `WeblogOwnership.entry`, not the global lookup the page editor uses.** The
  editor resolves ids globally so `StrandRules` can say "another blog's post"; the API turns another
  blog's post into an unknown one before `StrandRules` sees it, so a refusal never tells a token what
  another blog holds (the 404-never-403 rule, applied to a list). Each refused id is its own `errors`
  entry on `entries`, naming the id; `PagesApi.refusalCode` maps the `strand.error.*` keys to the
  `strand.*` problem codes, which render localised like any other (see Localisation above).
- **`PageView.parts` costs one query per strand page** (`getStrandParts(page, false)`, every member,
  any status) and none for a plain page, so a `pages list` stays one query for a blog without strands.
- **Unsetting `strand` reads the part count before anything is applied** (`PagesApi.checkStrand`);
  a count that cannot be read throws (500), never passes. A site page refuses `strand: true`, as
  the page editor forces it off.

## Front page and nav order (2026-11-15, release 3)

- **`frontPage` is a weblog field** (`WeblogView.frontPage`, `WeblogPatch.frontPage`; public
  reference `docs/api/README.md`). It is the page's id, set through the same `WeblogSettingsRules.checkFrontDoor`
  the settings form uses (published page of this blog; a draft, another blog's page and an unknown id are
  one refusal, so a token never learns what another blog holds). A refusal is a **400** with an `errors`
  entry named `frontPage` (the rule's violation field is the bean's `frontPageId`; `WeblogSettingsApi.apiField`
  maps it, and `FormBodyParityTest` records the rename). A lookup that cannot run, and the site space, fail
  closed. It is removed with `clear: ["frontPage"]` (`ClearFields`; null is never a value). The view omits
  it when unset. A save bumps `weblog.lastModified`, so rendered pages expire.
- **Page trash over the API** (0.13.0; public table in `docs/api/README.md`, "Page trash"). `DELETE
  .../pages/{id}` trashes; `POST .../restore` and `POST .../delete-forever` work on trash only. `PagesApi`
  has three lookups: `requirePage(request, id)` for a read (a trashed page is a 404, read it from
  `GET .../pages?status=TRASHED`); `requireLivePageForWrite` for PATCH, parts and DELETE (a trashed page of
  this weblog is a 409 that names the trash); `requireTrashedPage` for the two trash routes (a live page of
  this weblog is a 409). Another weblog's id is a 404 through all three. `PageDtos.parseStatus` refuses
  `TRASHED` on a write (400 on `status`). MCP `pages` gained `restore` and `delete_forever`; the pinned
  surfaces are `ApiRouteTableTest` (route count), `ToolSchemasTest`, `McpToolCatalogTest` and the README
  tool table (`McpDocsTest`).
- **Revision retention over the API** (2026-11-16; public text in `docs/api/README.md`, "Keeping a
  revision, and how many are stored"). `keep`/`unkeep` on `EntriesWriteApi` and `PagesApi` call the
  managers' `setRevisionKept`; `RevisionKeepLimitException` becomes a 409 through `ApiException.conflict(detail,
  cause)`. `RevisionDtos.RevisionSummary` and `RevisionView` carry `kept` (a pinned field set:
  `EntryRevisionsApiTest`). Every restore route calls the manager's `restoreRevision`, never a plain save,
  because a restore must not be coalesced; a mock-based restore test stubs and verifies that method.
  `AdminApi` serves `RevisionSweep.Stats` and `Report` as they are (`total` is a `@JsonProperty` method);
  `POST /v1/admin/revisions/sweep` is a dry run unless `dryRun` is literally false. Both admin routes are in
  `McpToolCatalog.EXCLUDED`. Route count is pinned at 72 (`ApiRouteTableTest`).
- **Page revisions and the page stale-write guard** (2026-11-16; public text in `docs/api/README.md`,
  "Page revisions and the stale-write guard"). `PagesApi` mirrors the entry routes: `revisions`, `revision`
  (`TextDiff.unified`, optional `against`), `restoreRevision`. Revision ids resolve through
  `WeblogOwnership.pageRevision`, so another page's is a 404. `PageView.contentHash` is
  `EntryDtos.contentHash(page.getContent())`; `requireUnchanged` checks `expectedContentHash` before any
  field, `requireUnchangedUnderLock` again under `WeblogPageManager.lockPageContent` just before the write;
  a mismatch is `ApiException.pageChanged` (409 `page-changed`, `currentContentHash`). Every page write
  passes `requireAuthenticatedUser` to `savePage(page, user, note)`: a test of a page write must set the
  `authenticatedUser` request attribute, and must verify the three-argument `savePage` (a `never()` on the
  one-argument form passes vacuously). `RevisionDtos.RevisionView.summary` is null for a page.
- **Making the front page a draft or deleting it clears it by itself** in the same save
  (`JPAWeblogPageManagerImpl`); the API needs no extra call and the next `weblogs get` omits the field.
- **Page `navOrder` must be >= 0** (`PageDtos.applyWrite`, 400 on `navOrder`). The Pages screen's reorder
  (`WeblogPageManager.setNavOrder`) renumbers a blog's pages 0..n-1.
- MCP: the `weblogs update` tool carries `frontPage` and `pages update` carries `navOrder` like any other
  field; `McpInstructions.TEXT` has one line on both (`McpInstructionsTest` requires the points; the text
  stays under 3,000 characters).

## Agent accounts (2026-10-13)

Spec `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`.

- **An agent is a `pressroom_user` with `kind = AGENT` (V033)**: no password (`passphrase` is nullable
  since V033), the `editor` role plus `admin` when its Site administrator switch is on, created only
  by `UserManager.addAgent`. Memberships, permissions and tokens are the people's machinery.
- **Every sign-in gate refuses an agent, explicitly**: `PressroomUserDetailsService` (form login,
  remember-me and HTTP Basic all resolve accounts there), `ReaderAccounts` (SSO),
  `JPAUserTokenManagerImpl.issueToken`/`validate` and `PasswordResetController.findUser` (reset and
  set-password links). `ApiTokenAuthFilter` refuses a token whose owner is disabled, person or agent,
  without relying on the enabled-only user lookup downstream.
- **`TokenPins` is the one pin rule** (a member pins their own weblogs, a site admin any existing
  one), shared by `TokensApi` and the token form; `issueToken` records `issued_by`, the owner for a
  self-mint.
- **Every account, membership and token change made from the Admin pages, Profile and the API writes one
  INFO line through `AdminLog`** (logger `pressroom.admin`, `by=` the actor; the weblog Members page does
  not, and that is out of scope). A token is named by `AdminLog.tokenDetails`, which leaves the
  secret out by construction; a controller never holds a second logger (`MoreThanOneLogger`).
- **The API never changes an agent** (`AdminAccountGuard.requireNotAgent`); `kind` is reported by
  `/me` and the admin users list.

## Cropping an image (2026-11-21)

`POST /v1/weblogs/{handle}/media/{id}/crop` is `MediaApi.crop` (`MediaDtos.MediaCrop`; matrix row equal
to `PATCH .../media/{id}`). The manager signals the two caller mistakes with `CropRefusedException`
(`Reason.NOT_CROPPABLE` -> 409 `media.notCroppable`, `OUTSIDE_IMAGE` -> 400 `media.cropOutside`, which
carries the displayed image size); every other `WebloggerException` stays the opaque 500. Never map by
message. The size guard (`expectedWidth`/`expectedHeight`, 409 `media.cropStale`) runs in the controller
before the manager, as does the rectangle validation. Spec decision 13 was overruled: a crop touches
the weblog (`saveWeblog` -> `lastModified`) so rendered pages expire. `cropMediaFile` already did this for
both surfaces, so the JSP crop needed no change; `MediaFileCropTest.aCropMovesTheWeblogsLastModified...`
pins it (red when the `saveWeblog` call is removed).

## Session-only (not over tokens)

Decision: SA-18 in `SimpleAgilityDecisionLog` (Wikantik); drafting record in
`docs/superpowers/specs/2026-11-21-session-only-policy-decision.md`.

**How a route is session-only (wave 3, 2026-11-25).** One mark and one guard, never a `Caller` check per route:

- `@SessionOnly` (`ui.restapi.auth`) on a controller class or a handler method. `ApiScopeInterceptor` reads it
  off the resolved handler (never the URI) and calls `SessionOnlyGuard.check(Caller.current(), handler)`, which
  lets only `Caller.Kind.SESSION` through (`Caller.isSession()`, the one resolver; `AdminAccountGuard` uses the
  same). TOKEN, PASSWORD, ANONYMOUS and UNRECOGNISED get 403 `auth.sessionOnly`, so a login mechanism added later is
  refused until it is named. The guard runs after `PressroomHandlerInterceptor` (an unseen weblog is 404, a member
  below the page's level 403) and after the token pin (404), and before the role ceiling.
- Each marked route is an MCP `Exclusion` with the reason `McpToolCatalog.SESSION_ONLY` ("session-only") and has a
  `Flag.SESSION_ONLY` row in `ApiAuthorizationMatrixTest.ROWS` (403 for every token caller after the pin's 404; the
  permitted session reaches the handler). `SessionOnlyRoutesTest` sweeps all three both ways and dispatches every
  marked route as each caller kind through the interceptor: a route cannot forget the mark's companions, and a
  future one is swept without editing the test.
- Handlers that need a person (`requireAuthenticatedUser`) and weblog permission follow the page they mirror:
  `WeblogAdminApi` (ADMIN on the weblog, global login) is the base for the template, stylesheet and theme routes;
  `SubmissionsApi` asks POST as the inbox does.
- The rules and writes are shared with the JSP controllers, so the two cannot drift: `Memberships`
  (`grant`/`change`/`remove` beside the existing rules), `CustomTemplates`, `ThemeStylesheets`,
  `SharedThemeRules` (`customViolation`, `importTemplates`, `enableCustom`), `WeblogRemoval`,
  `WeblogCreationRules.offeredThemes`. The API's problem codes are in the README's table
  (`template.*`, `stylesheet.*`, `member.*`, `submission.notFound`, `theme.customisationDisabled`,
  `auth.sessionOnly`). Where the API is stricter than the page, it is because the page's picker made the case
  unreachable (a template `action` the weblog may not add, an `importTheme` that is not an enabled theme); a client
  types what a picker chose.
- API names for membership levels are `ADMIN`/`POST`/`EDIT_DRAFT`; `Memberships.actionFor` maps them to the stored
  actions (`admin`/`post`/`edit_draft`).

Built in wave 3: weblog membership writes, custom templates, the stylesheet, the switch to a custom theme, the
contact-form inbox, weblog deletion.

**Wave 4 (2026-11-25) added the rest.** Whole-controller `@SessionOnly` (class level, plus `@AdminScoped`):
`AgentsApi` (`/v1/admin/agents`: list, create, get, patch, mint and revoke tokens), `SiteTokensApi`
(`/v1/admin/tokens`), `MeProfileApi` (`PATCH /v1/me/profile`, `PUT /v1/me/password`; no `@AdminScoped`, any signed-in
caller) and `UserWeblogsApi` (`/v1/admin/users/{userName}/weblogs`, per method). The shared code is in
`ui.controllers`: `AgentAccounts` (name and notes rules, create, update, enable, with the audit lines), `TokenOps`
(mint and revoke with `token.minted`/`token.revoked`; `TokenFormController` delegates), `AccountRules`
(`passwordViolation`, `ssoAdminProblem`, `selfLockoutProblems`, shared by `UserEditController` and the API),
`PasswordRules.matchesCurrent`, `TokenRow.loadOrThrow`, `Memberships` (unchanged; `UserWeblogsController` already
used its primitives). Three more things share the pattern but are **field-level, not route-level**: on
`/v1/admin/users` the `administrator`, `password` and `writer` fields are refused to a non-session caller through
`AdminAccountGuard.requireSessionToChange` / `requireSessionToSetPassword` (403 `auth.sessionOnly`, before anything is
applied; a token resending the stored value passes), so the routes themselves stay token-reachable for the
token-safe fields. `site.absoluteurl` already passed `AdminAccountGuard.requireApiManageable` for a session; it is
pinned by `AdminApiTest.patchConfigAppliesSiteAbsoluteUrlForASession`. The self-lock-out refusals
(`user.cantDisableSelf`, `user.cantChangeOwnRole`) apply to any caller acting on their own account, before any
field is applied. A renamed account expires the weblogs showing its comments (`CommentService.expireWhereShown`),
for the user patch and the profile route alike.

Test notes. The authorization matrix signs each session request in as `<name>#<n>`: a session is throttled by user
name (120 a minute) and the route table passed 120 in wave 4; the matrix's user lookup strips the suffix. A
membership path variable is `{weblogHandle}`, never `{handle}`: the interceptor reads `{handle}` as the action
weblog. Wave 4 left no SA-18 resource only in the JSP.

Rules for building one: the route's caller-kind check refuses `TOKEN` and `PASSWORD` callers with 403
(`ANONYMOUS` and anything unrecognised fail closed), after the visibility rules (a caller who may not
see the weblog still gets 404); the route is an MCP `Exclusion` with the reason "session-only" and its
authorization-matrix row shows 403 for every token caller. **Session writes on `/api` need CSRF
protection**: they have it (see "Session access to `/api`" above), and a Bearer or Basic call stays
CSRF-free. Moving a resource on or off this list is a new decision naming its threat.

Not on the list: a site-admin token may still create ordinary accounts and enable or disable them, and edit
their screen name, via `/v1/admin/users` (`AdminApi`); the account guard keeps admin and agent accounts out
and creation sends a set-password link.

**Decided (owner, 2026-11-21): another user's email change is session-only.** The address is where reset
links go, so re-addressing an ordinary account is a takeover. `AdminAccountGuard.requireEmailChangeAllowed`
refuses any non-`SESSION` caller (fail closed) whose `PATCH /v1/admin/users/{userName}` body carries an
`emailAddress` that differs from the stored one, 403 `user.emailUiOnly`, before anything is applied (no
partial update; the same body's `screenName` is not saved either). Resending the stored address unchanged
passes. MCP delegates to the route, so it answers the same.
