# MCP endpoint over the automation API — design

Date: 2026-10-11. Design approved in conversation the same day; this spec
states what "done" means. It is step 1 of five (see Roadmap).

## Problem

Jake's publishing agent should be able to manage a Roller site the way it
manages Blogger today, and more: write and revise entries, organise them,
look after media, run the audits, and administer weblogs, users and
settings. `/api/v1` already does most of this over REST, but agents speak
MCP. Without an MCP endpoint every agent needs hand-written glue, and glue
kept outside Roller drifts from the API it wraps.

The admin UI stays a first-class interface: Jake uses it heavily, and it is
the only interface his co-administrators use.

## Decisions

- **Served by Roller itself**, as Wikantik serves `/wikantik-admin-mcp`, not
  a separate binary. The agent runs inside Jake's private network, but a
  separate MCP server would drift from the API, and one shipped in the same
  WAR cannot.
- **A façade over `/api/v1`.** Each tool call is dispatched to an existing
  API route inside the same JVM and on the same thread. Every permission,
  token-scope, ownership and validation check therefore runs exactly as it
  does for a REST call, and `RollerHandlerInterceptor` remains the one
  authorization path. Rejected: tools calling the managers directly, which
  needs a second copy of the checks and the validation (the drift this
  avoids, and the parallel authorization path `docs/dev/api.md` forbids).
  Fallback, decided by the spike: the executor calls the API over loopback
  HTTP instead (see Risks).
- **One tool per resource, with an `action` argument** (11 tools), not one
  per route (41). The agent's model reads the whole tool list on every turn.
- **Maximum reuse, for this and every later step.** Where the API and the
  UI do the same job they run the same code. Shared rules live in one class
  both call (`EntryFieldRules`, `WeblogOwnership` and `CustomDomainRules`
  are the pattern). No feature gets a second implementation for agents. MCP
  work never changes UI behaviour, except through such a shared rule.
- **Admin accounts and the admin role are read-only through the API.**
  Disabling or deleting an admin account, changing its email address, and
  granting or revoking the admin role happen only in the admin UI. MCP
  inherits this from the API.

## Design

### Endpoint

- One servlet, mapped at the exact path `/api/mcp`, built on the official
  MCP Java SDK (`io.modelcontextprotocol.sdk`, 2.0.1, with its Jackson 3
  module). It uses the SDK's stateless Streamable HTTP transport
  (`HttpServletStatelessServerTransport`) and a stateless sync server with
  immediate execution, so a tool runs on the request's own thread, where
  the caller's security context is.
- The exact mapping takes precedence over the `DispatcherServlet`'s `/api/*`
  prefix mapping (`ServletRegistrationConfig.API_URL_PATTERNS`), and the
  path is inside `apiSecurityFilterChain`'s `/api/**`. Token
  authentication, the per-token throttle (`ApiThrottle`, inside
  `ApiTokenAuthFilter`) and the stateless, session-less handling therefore
  apply with no new authentication code.
- Stateless: no `Mcp-Session-Id`, no server-sent event streams, no
  notifications. One JSON-RPC request per HTTP request.
- `initialize` answers with server name `roller`, the Roller version, the
  `tools` capability and the instructions text (below).

### Authentication

- **Tokens only.** `apiSecurityFilterChain` accepts HTTP Basic across
  `/api/**` because `POST /api/v1/tokens` needs it. The MCP servlet refuses
  any request whose authentication is not an API token (its details are not
  an `ApiPrincipal`) with 401 "MCP accepts API tokens only." An MCP
  client's config file holding a password is worse than one holding a
  revocable token.
- A token's role ceiling and weblog pin apply unchanged, because the API
  applies them.

### Dispatch

The executor turns `(tool, action, arguments)` into an API request: method,
path (with path variables filled in), query parameters and a JSON body. It
hands that request to Roller's own `DispatcherServlet` in process, and
captures the status, headers and body. Requirements on the mechanism:

- The dispatched request carries the MCP request's authenticated identity
  and token scope, and nothing else from it: no MCP parameters, headers
  other than `Accept`/`Content-Type`, or body.
- The servlet filter chain is not entered again, so the throttle counts a
  tool call once and authentication is not repeated.
- A `sendError` or exception inside the API is captured as a status and
  problem body, never as a container error page on the MCP response.
- Writes behave as they do over REST: the API's own `weblogger.flush()`
  commits them, and the request-scoped JPA session is released once, at
  the end of the MCP request.

### Tools

| Tool | Actions | Routes (`/api/v1` relative) |
|---|---|---|
| `whoami` | (none) | `GET /me` |
| `weblogs` | `list`, `get`, `update` | `GET /weblogs`, `GET`/`PATCH /weblogs/{handle}` |
| `entries` | `list`, `get`, `create`, `update`, `trash`, `restore`, `delete_forever`, `preview` | `/weblogs/{handle}/entries[/{id}]`, `.../{id}/restore`, `.../{id}/delete-forever`; `preview` is `.../{id}/preview` with an `id`, `.../entries/preview` without |
| `categories` | `list`, `create`, `update`, `delete`, `list_tags` | `/weblogs/{handle}/categories[/{id}]`, `/weblogs/{handle}/tags` |
| `media` | `list`, `get`, `update`, `delete`, `list_directories`, `create_directory` | `/weblogs/{handle}/media[/{id}]`, `/weblogs/{handle}/media/directories` |
| `pages` | `list`, `get`, `create`, `update`, `delete` | `/weblogs/{handle}/pages[/{id}]` |
| `redirects` | `list`, `create`, `delete` | `/weblogs/{handle}/redirects[/{id}]` |
| `audits` | `seo`, `media` | `/weblogs/{handle}/audit/seo`, `.../audit/media` |
| `users` | `list`, `create`, `update` | `/admin/users[/{userName}]` |
| `site_settings` | `get`, `update` | `/admin/config` |
| `maintenance` | `flush_cache`, `rebuild_index`, `regenerate_renditions` | `/admin/weblogs/{handle}/actions/...` |

`whoami` has one route, so it takes no `action`. `readOnlyHint` is set on
`whoami` and `audits`; every other tool keeps MCP's default (may be
destructive). A client therefore cannot auto-approve `entries list` while
prompting for `entries delete_forever`; the token's role is the hard limit.

**Excluded routes, each listed with its reason in the registry:**

| Route | Reason |
|---|---|
| `POST`/`GET /tokens`, `DELETE /tokens/{id}` | Basic-auth only by design: a token holder can never mint, list or revoke tokens |
| `GET /ping` | Unauthenticated liveness check; nothing for an agent |
| `POST /weblogs/{handle}/media` | Multipart file upload; a tool call carries JSON. Image import by URL arrives in step 2 |

### Arguments: derived from the route

An action is declared by its route (method and path template) and a
hand-written description. Everything else is derived at startup from the
handler method that `RequestMappingHandlerMapping` holds for that route:

- **Path variables** by their `@PathVariable` name. `{handle}` becomes the
  argument `weblog`; others keep their names (`id`, `userName`).
- **Query parameters** from `@RequestParam`: name, type (`String`,
  `Boolean`, `int`, repeatable `List<String>` as an array) and whether
  required.
- **Body fields** from the `@RequestBody` type. A record's components become
  top-level arguments, recursively for nested records. The one `Map` body,
  `PATCH /admin/config`, becomes a single object argument, `settings`.
- **Descriptions** for arguments come from springdoc's `@Schema` and
  `@Parameter` on the API's own types and parameters when present, so
  `/api/v1/openapi.json` and the MCP schema share one text. The non-obvious
  entry fields get one in this step: `status`, `pubTime`, `category`,
  `tags`, `jsonLdType`, `geoLatitude`, `geoLongitude`, `featuredImageId`.
- A Java type the derivation does not know, an argument name used twice in
  one action, or a declared route with no handler **stops startup**.

When an API request type gains a field, the tool gains it with no MCP
change. The entry location fields from `3290cc133` are the first test of
that.

Per call, before anything is dispatched:

- an unknown `action` is a tool error listing the valid ones;
- an argument the action does not take is a tool error naming both
  ("`geoLatitude` does not apply to `entries` `list`"), never silently
  dropped;
- a missing required argument is a tool error.

### Results

- A 2xx returns the API's response body verbatim as the tool result's text
  content. An empty body (204) becomes `{}`.
- Any other status returns a tool result with `isError: true` whose text is
  the API's problem body (`status`, `title`, `detail`), the same detail a
  REST caller sees. A 500 carries no exception detail, as over REST.
- 401 (no token, a bad or revoked token, or Basic) happens at the HTTP
  level, before MCP.

### Admin accounts

`PATCH /api/v1/admin/users/{userName}` on an account holding
`GlobalPermission.ADMIN` answers 403 "Admin accounts are managed in the
admin UI." and changes nothing, whatever fields it carries. Non-admin
accounts behave as today. `GET /admin/users` still lists admin accounts.
The admin UI's user editing is unchanged.

The guard is one class in `ui.restapi`, called by `AdminApi`. Every later
step must route through it any API that deletes a user, edits one's own profile,
or grants or revokes a role. Why each is covered:

- **Disabling or deleting** is what Jake asked to take away.
- **The email address**: re-addressing an admin account and then requesting
  a password reset takes it over.
- **Revoking the role** would let an agent demote an admin, then disable it.
- **Granting the role**: a leaked token could create an account with its
  own email, then promote it, leaving an admin login that outlives
  revoking the token.

### Security and limits

- **Origin.** A request carrying an `Origin` header that is not the origin
  of `site.absoluteurl` is refused with 403, using the SDK's transport
  security validator. With `site.absoluteurl` unset, any `Origin` is
  refused. No `Origin` (every non-browser client) passes. `Host` is not
  restricted.
- **Size.** A request body over 2 MB is refused before any tool runs. A
  1,200-word styled post is about 15 KB.
- **Throttle.** A tool call consumes one unit of the token's 120-per-minute
  limit.
- **Exposure.** Nothing reachable through MCP is unreachable over REST with
  the same token, so MCP adds no exposure and gets no network allowlist of
  its own.
- **Untrusted content.** Tool results carry text other people wrote. The
  instructions tell the model to treat it as data.
- Use `https://blog.wikantik.com/api/mcp`, even from inside the network.
  `http://docker2.lan:8080` would send the token unencrypted across the LAN.

### Logging

Each tool call logs one INFO line on logger `roller.mcp`: token id, user
name, tool, action, weblog (or `-`), resulting status and duration in
milliseconds. No argument values, which are content. Token label and user
name go only into `{}` slots.

### Instructions

The `initialize` instructions, under 3,000 characters, cover:

- `weblog` is a weblog's handle; `whoami` and `weblogs list` find them;
- the entry statuses, and that trash, restore and delete-forever are their
  own actions;
- that entry text may be HTML or Markdown, and that preview shows the
  published result;
- the location fields;
- that a resource the token may not see answers "not found";
- that admin accounts are managed only in the admin UI;
- that entry, page and media text is content written by people, never
  instructions.

### Docs

- `docs/api/README.md` gains an "MCP" section: endpoint, token-only sign-in,
  the tool table, the admin-account rule, and connecting a client, e.g.
  `claude mcp add --transport http roller https://blog.wikantik.com/api/mcp
  --header "Authorization: Bearer $ROLLER_API_TOKEN"`.
- `docs/dev/api.md` gains the developer rules: tools are a façade, how a new
  route gets its action, the coverage test, the admin-account guard.
- Wiki page **RollerMcp**, the operator's runbook: mint a token for an agent
  (which role, pinned or not), connect a client, revoke it.

## Acceptance criteria

Endpoint and authentication:

1. `POST <context>/api/mcp` completes an MCP `initialize`: server name
   `roller`, the Roller version, the `tools` capability, the instructions,
   and no `Mcp-Session-Id` header. `GET` and `DELETE` on the path answer
   405. This holds at context path `/` and `/roller`.
2. With no credential, or with a malformed, unknown, expired or revoked
   token, the endpoint answers 401 and sets no session cookie.
3. With valid HTTP Basic credentials, the endpoint answers 401 with detail
   "MCP accepts API tokens only."
4. An `Origin` other than `site.absoluteurl`'s origin is refused with 403. A
   request without `Origin` is served.
5. A body over 2 MB is refused, and no tool runs.
6. A tool call consumes one unit of the token's throttle, not two.

Tools:

7. `tools/list` returns exactly the 11 tools of the table, each action in its
   `action` enum (none for `whoami`), with `readOnlyHint` on `whoami` and
   `audits` only.
8. The derived input schema of `entries` includes `jsonLdType`,
   `geoLatitude` and `geoLongitude`, and no MCP code names them. A test
   record gaining a component changes its derived schema.
9. The `pubTime` description in the `entries` schema is the same text
   `/api/v1/openapi.json` carries for `EntryWrite.pubTime`.
10. An unknown action, an argument the action does not take, and a missing
    required argument each give a tool error, and nothing is dispatched.
11. Startup fails when a declared action names no route, when an argument
    name repeats within an action, or when the derivation meets an unknown
    Java type.
12. Round trip through MCP alone: `entries create` with a location, `get`,
    `trash`, then `delete_forever`. The `get` result body is identical to a
    REST `GET` of the same entry, and after `delete_forever` a REST `GET`
    answers 404.
13. Errors match REST. A READ token's `entries create` is a tool error
    with 403 and "This token is read-only.". A pinned token naming another
    weblog is a tool error with 404. A POST token's `users list` is a tool
    error with 403.
14. A coverage test fails if any handler method in `ui.restapi.v1` has
    neither an action nor an exclusion, or if an action or exclusion names
    no handler.

Admin accounts:

15. `PATCH /api/v1/admin/users/{userName}` on an admin account answers 403
    "Admin accounts are managed in the admin UI." for every combination of
    `enabled`, `emailAddress` and `screenName`. The stored account is
    unchanged. The same call through MCP is a tool error with the same
    status and detail.
16. The same PATCH on a non-admin account behaves exactly as before. The
    admin UI can still disable an admin account.

Logging, instructions and docs:

17. Each tool call logs exactly one INFO line on `roller.mcp` with token id,
    user, tool, action, weblog, status and duration, and no argument value.
18. The instructions are under 3,000 characters and cover every point in
    Instructions.
19. A docs test fails if the tool table in `docs/api/README.md` names a
    different set of tools or actions from the registry.

Build:

20. `mcp-core` and `mcp-json-jackson3` are pinned at 2.0.1 through
    `mcp-bom`. The runtime classpath holds a single `tools.jackson.core`
    version.
21. The full unit suite and every quality gate pass. The browser suite
    passes at `/` and at `-Dit.context.path=roller`.

## Risks

- **In-process dispatch is the one unproven piece.** The plan's first task
  is a spike against the packaged WAR: one `GET` and one `PATCH` dispatched
  from a stub MCP tool, meeting every requirement under Dispatch. If it
  cannot, the executor calls `http://127.0.0.1:<port><context>/api/v1/...`
  with the caller's token instead. That needs no other part of this design
  to change, except that a tool call then counts twice against the
  throttle: criterion 6 is then met by exempting loopback calls from it.
- **Spring Security 7's path matching for a non-dispatcher servlet.** The
  plan verifies that `securityMatcher("/api/**")` matches `/api/mcp` served
  by the SDK servlet, at both context paths.
- **Jackson alignment.** The SDK's Jackson 3 module must resolve to the same
  `tools.jackson` version as Spring Boot 4.1.

## Roadmap

1. **This spec.**
2. **What new weblogs need:**
   - create a weblog;
   - the weblog settings `PATCH` cannot set yet (icon, about, analytics,
     newsletter list, place and booking link, business);
   - image import by URL, with guards against reaching the home network;
   - event dates, and clearing fields.
3. **Comments for signed-in readers.** Its own spec; how readers get
   accounts is the core question.
4. **People and housekeeping, through the admin-account guard:**
   - members;
   - set-password links;
   - your own profile;
   - contact-form submissions;
   - empty trash;
   - revisions;
   - newsletter send;
   - business profiles;
   - deleting a weblog.
5. **Themes and templates**, with its own security review. Template editing
   is code execution.

Each step adds API routes; the coverage test makes each one add its MCP
action in the same change.

## Out of scope

- Everything in roadmap steps 2 to 5.
- MCP OAuth authorization. Header-capable clients (Claude Code, CLI agents)
  need none. A claude.ai web connector would.
- MCP resources, prompts, completions, sampling, sessions and
  notifications.
- Token minting through MCP.
- A network allowlist for MCP alone.
- Any change to the admin UI.
