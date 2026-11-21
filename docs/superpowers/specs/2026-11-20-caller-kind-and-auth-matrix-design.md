# Caller kind, password scope, and the API authorization matrix (2026-11-20)

Tiers 2 and 6 of the React-readiness groundwork (memory: React readiness).
Today the API decides "what kind of caller is this?" in six places, each by
asking whether `Authentication.getDetails()` is an `ApiPrincipal`. Anything
that is not a token is treated as "Basic", and the admin-UI-only refusals
(`AdminAccountGuard`) refuse every caller, so a future browser UI calling the
API with its session would be refused too. HTTP Basic (a password) is accepted
on every `/api/**` route, with no scope ceiling and an IP throttle key.

Jake's ruling (2026-11-20, option A): **HTTP Basic is accepted only on the
token resource, `/api/v1/tokens` and `/api/v1/tokens/{id}`.** Everywhere else
it is a 401. This is a behaviour change for clients; it ships in 0.16.0 (not
tagged by this work).

## Global constraints

- Test-driven, per CLAUDE.md. Refactors that must not change behaviour start
  with characterisation tests that pass on the old code (say so in javadoc).
- Every CLAUDE.md gate applies, including the security-boundary JaCoCo
  package rule (sso, comments, API auth, v1, MCP: L 0.93 / B 0.89). Finish
  with `mvn clean install` green, and the browser suite (`mvn verify -Pit`)
  green at `/` and at `-Dit.context.path=pressroom`.
- The error contract is unchanged: problem+json everywhere under `/api/**`,
  404 (never 403) for what the caller may not see, 403 for a member below the
  required level or a token below the required role.
- Docs move with behaviour: `docs/api/README.md`, `docs/dev/api.md`, the
  javadoc on `SecurityConfig.apiSecurityFilterChain`, MCP descriptions if any
  mention Basic. `McpInstructions.TEXT` stays under 3,000 characters.
- Commit on master; never push.

## Approaches considered

1. **Keep Basic everywhere, only name the caller kind** (option B). Pure
   refactor, zero client risk, but leaves an unscoped, IP-throttled
   password credential on every route, and the guard rewrite would still
   need to decide what a password caller may do. Rejected by Jake.
2. **Restrict Basic in the controller layer** (each non-token controller
   refuses a PASSWORD caller). Many call sites, easy to miss one on a new
   route. Rejected.
3. **Restrict Basic in the security chain** (chosen): a narrow chain for the
   token resource carries `httpBasic`; the general API chain does not. One
   place, enforced before any controller, and a new route is covered by
   default.

## Task 1: One caller model, resolved in one place

**Rule.** A request's caller is a value with a kind: `TOKEN` (an API token;
carries the `ApiPrincipal`), `PASSWORD` (HTTP Basic), `SESSION` (an
interactive login: JSP form login or reader SSO), or `ANONYMOUS`. It is
resolved by one class from the `SecurityContext`, and every place that
currently tests `getDetails() instanceof ApiPrincipal` to learn the caller's
kind asks that class instead. Known sites (sweep for others):
`TokensApi.currentApiPrincipal`, `MetaApi` (line ~76),
`McpToolExecutor` (~224), `McpEndpointServlet` (~50),
`ApiScopeInterceptor.currentPrincipal`, and
`PressroomHandlerInterceptor` (~180, which reads `WeblogPinnedCredential`;
it may keep that interface if the caller value exposes the pin through it).

`PASSWORD` must be marked positively when the Basic filter authenticates
(for example a custom `authenticationDetailsSource` on `httpBasic` that sets
a marker details object), not inferred as "authenticated but not a token":
form login, SSO and Basic all produce `WebAuthenticationDetails` by default
and are otherwise indistinguishable.

**Acceptance criteria.**
1. Characterisation tests, written first and passing on the old code, pin
   today's observable answers at each site above for a token caller and a
   Basic caller (for example: Bearer on `POST /v1/tokens` is 403 with the
   existing detail; MCP with Basic is 401; `GET /v1/me` reports the token
   fields only for a token caller).
2. Unit tests for the resolver: each kind is recognised from the
   authentication each mechanism really produces (Bearer filter, Basic
   filter with the marker, form login, OAuth2 login, anonymous / none), and
   an authenticated `Authentication` with unrecognised details resolves to
   the most restrictive reading (never `SESSION`), so a future mechanism
   cannot silently gain session powers. A test names that rule.
3. After the change, `command grep -rn "instanceof ApiPrincipal"
   app/src/main/java` finds only the resolver.

## Task 2: HTTP Basic only on the token resource

**Rule.** Basic authenticates only `/api/v1/tokens` and `/api/v1/tokens/{id}`.
On any other `/api/**` route a request carrying `Authorization: Basic ...` is
401 problem+json, whether or not the password is right, with a detail that
says password authentication is accepted only on `/api/v1/tokens` and that a
bearer token is needed. Bearer is refused on the token resource exactly as
today (`TokensApi`, 403, existing details). Throttling (`ApiThrottle` keys)
and the body limit apply to both chains unchanged.

**Acceptance criteria.**
1. Correct Basic credentials: `POST`/`GET /api/v1/tokens` and `DELETE
   /api/v1/tokens/{id}` work as today; `GET /api/v1/me`,
   `GET /api/v1/openapi.json`, a weblog read, an entry write, an admin route
   and `POST /api/mcp` are each 401 problem+json with the new detail.
2. Wrong Basic credentials are 401 on the token resource (as today) and 401
   elsewhere; a disabled user and an agent account are 401 on the token
   resource (as today).
3. Path tricks do not widen Basic: a Basic request to
   `/api/v1/tokens/../me`, `/api/v1/%74okens`, `/api/v1/tokens;x=1`,
   `/api/v1/tokensX`, `/api/v1/tokens/` + an id containing an encoded slash
   either is refused by the firewall or reaches no handler other than
   `TokensApi`. Test each.
4. `/api/v1/ping` stays `permitAll` with any or no credentials.
5. A browser session cookie (an authenticated JSP login) on `/api/v1/**` is
   401: the API chains stay stateless and never read the session. (Pin it if
   no test does today; it is what makes Task 3's `SESSION` allowance inert
   until session auth on `/api` is deliberately built.)
6. Every browser IT and app test that used Basic on a non-token route mints
   a token over Basic and uses Bearer instead (known: `CommentsIT`,
   `RedirectIT`, `McpIT`, `AgentAccountsIT`, `ApiIT`); the suite is green at
   both context paths.
7. `docs/api/README.md` states the rule where it describes authentication
   (it now says Basic "is in fact accepted on every `/api/**` request"), and
   its change notes say Basic outside `/v1/tokens` stops working in 0.16.0.
   `docs/dev/api.md` and the `SecurityConfig` javadoc are updated.

## Task 3: Admin-UI-only refusals are about the caller kind

**Rule.** `AdminAccountGuard`'s refusals (admin accounts, agent accounts,
`site.absoluteurl`) and the token resource's "no token may manage tokens"
rule are stated in terms of the caller kind: they refuse `TOKEN` and
`PASSWORD` callers and allow `SESSION`. Today no `SESSION` caller can reach
`/api` (Task 2 AC5), so observable behaviour is unchanged; the point is that
the rule now says what it means.

**Acceptance criteria.**
1. Each guard method takes the caller (or its kind) explicitly; unit tests
   show `TOKEN` and `PASSWORD` refused with the existing details and
   `SESSION` allowed, for all three refusals.
2. `TokensApi` refuses `TOKEN`, allows `PASSWORD`, and allows `SESSION`
   (unit level).
3. `ANONYMOUS` and the unrecognised-details reading from Task 1 AC2 are
   refused by every guard (fail closed).
4. The existing API-level tests of these refusals still pass unchanged.

## Task 4: The API authorization matrix

**Rule.** One test states, for every route in the API route table and every
kind of caller, whether the authorization layers let the request through and,
if not, which refusal it gets. It is the oracle a React migration (or any new
route) is checked against. A route added without a row fails the test.

**Shape (builder's call within these lines).** Dispatch through the real
security chains and the real `DispatcherServlet` built from `WebMvcConfig`
(the recipe of `WeblogSettingsApiDispatchTest` plus `springSecurity()` as in
`SecurityConfigTest`), with a test-only terminal interceptor after the
production interceptors that answers a sentinel status (e.g. 299) when a
request reaches it, so no business code runs. Expected outcomes are a
hand-written table per route (required token role, weblog permission level,
global permission, whether it names a weblog, whether it is pin-exempt), not
derived from the annotations under test.

**Callers (at least):** anonymous; Basic with a correct password (site admin);
tokens of role `READ`, `POST`, `ADMIN` owned by a site admin, unpinned; an
`ADMIN` token owned by a non-site-admin weblog owner, unpinned, pinned to
their weblog, and pinned to another weblog; a token owned by a member below
the route's level; a token owned by a user with no role on the weblog; an
agent account's token.

**Acceptance criteria.**
1. Every route in `ApiRouteTableTest`'s table (72 today) has a row; a route
   missing from the matrix fails with its method and path named.
2. Each (route, caller) cell asserts exactly one of: reached (sentinel), 401,
   403, 404. 404 vs 403 follows the error contract.
3. Writes are exercised with a well-formed minimal body, so a 400 from body
   parsing cannot mask an authorization answer (or the terminal interceptor
   runs before body binding: builder's call, stated in the javadoc).
4. The matrix finds no route whose answer contradicts the hand-written
   table. Any contradiction found while building it is a defect: fix it
   (test first) or, if the table was wrong, say why in the report.
5. MCP: `tools/call` authorization is pinned for the same caller set at the
   tool level for at least one tool per controller family (the MCP layer
   delegates to the API; `McpCoverageTest` already holds tools to routes).

## Out of scope

- Session authentication on `/api` (building it is not no-regrets; it comes
  with its first consumer).
- Tiers 3-5 (separate specs).
- Re-anchoring `FormBodyParityTest` moves to the Tier 3 spec, where the form
  beans it is anchored on are dealt with.
