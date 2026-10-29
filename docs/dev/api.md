# Automation API (developer notes)

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Automation API
`com.wikantik.pressroom.ui.restapi` (`/api/v1`) is a REST surface for
scripts and agents alongside the JSP admin UI. Reference and error contract:
`docs/api/README.md`, the front door. Tokens are minted in the admin UI
(Profile → API tokens, or an agent's page) or with `bin/roller-api auth login`.
`/api/v1` is unstable while Roller is 0.x.

- **The `/api` prefix is a servlet-spec prefix mapping, not part of any
  `@RequestMapping`.** `ServletRegistrationConfig.API_URL_PATTERNS` (`/api/*`)
  shares the `DispatcherServlet` with `*.rol`, the SEO patterns and
  `/newsletter/*`; Spring strips the matched prefix before routing, so
  controllers under `ui.restapi.v1` are written relative to `/v1/...`
  (`TokensApi` is `@RequestMapping("/v1/tokens")`, not `/api/v1/tokens`;
  `NewsletterController` likewise `/newsletter`, not `/newsletter/subscribe`).
  Getting it wrong compiles and 404s; no test catches it. `api` and
  `newsletter` are reserved roots in
  `rendering.weblogMapper.pressroomProtectedUrls` so no weblog handle can shadow
  them.
- **The API and the JSP admin UI share exactly one authorization path:
  `PressroomHandlerInterceptor`.** Every `*Api` controller implements
  `UISecurityEnforced` and declares `GlobalPermission`/`WeblogPermission` like
  `CategoryEditController`, `WeblogConfigController` and the rest; no API-side
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
- **`EntryFieldRules` and `WeblogOwnership`
  (`com.wikantik.pressroom.ui.controllers`) are shared by JSP editor and
  API — one home each for entry-field rules and by-id ownership checks.**
  `EntryBean` and `EntryDtos.applyWrite` both call
  `EntryFieldRules.escapeTitle` and `EntryFieldRules.parsePubTime`
  (weblog-timezone), so the surfaces cannot drift. `WeblogOwnership` is the one
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
  below the required level stays 403; `ApiSecurityRefusalTest`). `ApiScopeInterceptor.checkWeblogScope` and every `requireX` helper in
  `BaseApiController` follow this.
- **The OpenAPI document is machine-readable, not a browser explorer.**
  `springdoc-openapi-starter-webmvc-api` serves `GET /api/v1/openapi.json`
  (`/v1/openapi.json` in `application.properties`, per the prefix point),
  scanning only `ui.restapi.v1` so the JSP surface never leaks. The springdoc
  UI is not a dependency; `springdoc.swagger-ui.enabled=false` is defence in
  depth. It sits behind `apiSecurityFilterChain` like all of `/api/**` (Basic
  or Bearer), not exempted like `GET /api/v1/ping`. `OpenApiDocumentTest` pins
  two `docs/api/README.md` claims the document cannot convey — v1 is unstable,
  and `roller-api auth login` is the bootstrap path.
- **`PressroomHandlerInterceptor`'s `import ...ui.restapi.ApiException` is the ONE
  permitted import from `ui.restapi` into `ui.controllers`, and must stay the
  only one.** A second interceptor for `/api/**` would be exactly the parallel
  authorization path avoided here.
  `grep -r "import com.wikantik.pressroom.ui.restapi" app/src/main/java/com/wikantik/pressroom/ui/controllers/`
  must return exactly one line.

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
  self-edit, role grants or revocations. Admin accounts and the admin role are read-only through the
  API (403 `Admin accounts are managed in the admin UI.`).
  `AdminAccountGuard.UI_ONLY_SETTINGS` (today `site.absoluteurl`, the base of every password-reset
  link) are likewise never written through `PATCH /admin/config` (403, nothing applied); add a
  setting there to protect it. Agent accounts likewise: `requireNotAgent` answers 403 `Agent accounts are managed in the admin UI.`, and `patchUser` calls it before `requireNotAdmin`.
- `McpEndpointServlet` refuses non-token callers (Basic is accepted chain-wide for `/v1/tokens`).
  `McpOriginValidator` refuses a foreign `Origin`. Each call logs one `roller.mcp` INFO line, with no
  argument values; the weblog appears only when it is handle-shaped (else `?`). That logger goes to
  the console appender, like `roller.redirects`, not to `roller.log`.

## New-weblog needs (step 2)

- **`WeblogSettingsRules` and `WeblogCreationRules`** are the one home for those checks. Both the JSP
  controllers and the API call them. The settings PATCH lays the request over `WeblogConfigBean` and
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
- **Import logging:** `roller.media.import` logs the host only.

## Entry writes: check, import, apply

- **`checkWrite` runs on `EntryDtos.scratchOf`**, a copy of the entry. A refused write changes
  nothing, and `persistNewMediaFile` flushes mid-request, so nothing may be applied to the entry
  before the import step has succeeded.
- **`FieldErrors` collects only 400s.** A 404, a 409 or an unexpected failure is not a field problem
  and propagates at once, so a clash found during a write is never merged into the 400 list.
- **`expectedContentHash` is checked first** (spec `docs/superpowers/specs/2026-10-24-agent-editing-and-seo-design.md`, section 4):
  right after the live-entry lookup, before `checkWrite` and so before the import step can fetch.
  A mismatch is `ApiException.entryChanged`, a 409 whose `currentContentHash` is the one RFC 9457
  extension member `ApiProblem` has (null, so omitted, on every other problem). `contentHash` is
  `EntryDtos.contentHash`: SHA-256 of the UTF-8 text, lower-case hex, Wikantik's scheme; keep the two
  stacks on one.
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

## Agent accounts (2026-10-13)

Spec `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`.

- **An agent is a `roller_user` with `kind = AGENT` (V033)**: no password (`passphrase` is nullable
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
  INFO line through `AdminLog`** (logger `roller.admin`, `by=` the actor; the weblog Members page does
  not, and that is out of scope). A token is named by `AdminLog.tokenDetails`, which leaves the
  secret out by construction; a controller never holds a second logger (`MoreThanOneLogger`).
- **The API never changes an agent** (`AdminAccountGuard.requireNotAgent`); `kind` is reported by
  `/me` and the admin users list.
