# Automation API (developer notes)

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Automation API
`org.apache.roller.weblogger.ui.restapi` (`/api/v1`) is a REST surface for
scripts and agents alongside the JSP admin UI. Reference and error contract:
`docs/api/README.md`, the front door, since no UI mints a token
(`bin/roller-api auth login` is the only route in). `/api/v1` is unstable while
Roller is 0.x.

- **The `/api` prefix is a servlet-spec prefix mapping, not part of any
  `@RequestMapping`.** `ServletRegistrationConfig.API_URL_PATTERNS` (`/api/*`)
  shares the `DispatcherServlet` with `*.rol`, the SEO patterns and
  `/newsletter/*`; Spring strips the matched prefix before routing, so
  controllers under `ui.restapi.v1` are written relative to `/v1/...`
  (`TokensApi` is `@RequestMapping("/v1/tokens")`, not `/api/v1/tokens`;
  `NewsletterController` likewise `/newsletter`, not `/newsletter/subscribe`).
  Getting it wrong compiles and 404s; no test catches it. `api` and
  `newsletter` are reserved roots in
  `rendering.weblogMapper.rollerProtectedUrls` so no weblog handle can shadow
  them.
- **The API and the JSP admin UI share exactly one authorization path:
  `RollerHandlerInterceptor`.** Every `*Api` controller implements
  `UISecurityEnforced` and declares `GlobalPermission`/`WeblogPermission` like
  `CategoryEditController`, `WeblogConfigController` and the rest; no API-side
  permission system exists. `ApiScopeInterceptor` is registered *after* it in
  `WebMvcConfig` (order is load-bearing, see its javadoc) and only narrows by
  token scope.
- **`ApiToken.Role` (`READ`/`POST`/`ADMIN`) and the optional weblog pin are a
  ceiling, never a grant.** `ApiScopeInterceptor` refuses what the scope
  disallows and never approves what `RollerHandlerInterceptor`'s
  `GlobalPermission`/`WeblogPermission` check would refuse: a `READ` token held
  by a `GlobalPermission.ADMIN` user cannot POST; a pinned token cannot act on
  another weblog its owner could edit.
- **`EntryFieldRules` and `WeblogOwnership`
  (`org.apache.roller.weblogger.ui.controllers`) are shared by JSP editor and
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
  exists. `ApiScopeInterceptor.checkWeblogScope` and every `requireX` helper in
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
- **`RollerHandlerInterceptor`'s `import ...ui.restapi.ApiException` is the ONE
  permitted import from `ui.restapi` into `ui.controllers`, and must stay the
  only one.** A second interceptor for `/api/**` would be exactly the parallel
  authorization path avoided here.
  `grep -r "import org.apache.roller.weblogger.ui.restapi" app/src/main/java/org/apache/roller/weblogger/ui/controllers/`
  must return exactly one line.

## MCP endpoint
`/api/mcp` (spec `docs/superpowers/specs/2026-10-11-mcp-endpoint-design.md`) is a facade over
`/api/v1`, never a second API.

- **A tool call is forwarded in process** (`InProcessApiDispatcher`, `RequestDispatcher.forward`)
  to its `/api/v1` route, on the request thread (the SDK server runs with immediate execution). So
  `RollerHandlerInterceptor`/`ApiScopeInterceptor` decide exactly as for REST. The Spring Security
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
  (`McpIT` compares `EntryWrite.pubTime`). A body record gaining a field gains a tool argument with
  no MCP change. An unknown parameter type stops startup.
- **`AdminAccountGuard` is mandatory for any API route that changes a user**: deletion, profile
  self-edit, role grants or revocations. Admin accounts and the admin role are read-only through the
  API (403 `Admin accounts are managed in the admin UI.`).
- `McpEndpointServlet` refuses non-token callers (Basic is accepted chain-wide for `/v1/tokens`).
  `McpOriginValidator` refuses a foreign `Origin`. Each call logs one `roller.mcp` INFO line, with no
  argument values; the weblog appears only when it is handle-shaped (else `?`). That logger goes to
  the console appender, like `roller.redirects`, not to `roller.log`.
