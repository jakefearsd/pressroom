# API gap resources and the session-only policy (2026-11-20)

Tier 4 (items 7 and 8) of the React-readiness groundwork
(`.superpowers/react-readiness-tiers.md`; memory: React readiness). The JSP
admin UI can do several things the API cannot, and an agent or a future
browser client has no way to reach them: the shared business profiles, the
site-wide traffic page, the image crop, the weblog's member list, the
caller's own profile, and the facts a client needs before it draws anything
(the version, which features are switched on, the site's address). This spec
adds the ones that are safe to hand to a bearer token, as thin wrappers over
the business tier and over the shared rule classes the JSP controllers
already use (or that this work moves out of them). For the rest it records,
as policy, that they are **session-only**: built when session login on
`/api` exists, never reachable by a token.

**Lands after** Tier 2 (caller kind `TOKEN`/`PASSWORD`/`SESSION`, HTTP Basic
only on `/v1/tokens`, the API authorization matrix:
`2026-11-20-caller-kind-and-auth-matrix-design.md`) and Tier 5 (stable
`code` on problem+json, typed OpenAPI with no `Map` responses, list
endpoints carrying `total` beside `hasMore`). Every criterion below is
written against that state. Release: the next minor after those land; not
tagged by this work.

## Global constraints

- Test-driven, per CLAUDE.md: each acceptance criterion gets a test written
  first and seen to fail for the expected reason.
- Every CLAUDE.md gate applies, including the security-boundary JaCoCo
  package rule (every new controller here is in `ui.restapi.v1`: L 0.93 /
  B 0.89). Finish each task with `mvn clean install` green.
- **Error contract.** problem+json under `/api/**`; 404 (never 403) for
  what the caller may not see (a pin-excluded weblog, a weblog the caller
  holds no role on, the site space, a foreign or missing id); 403 for a
  member below the required level or a token below the required role. Every
  new refusal carries a Tier 5 `code`. Where the JSP already has a message
  key for the same refusal, that key is the code (for example
  `businesses.error.inUse`); a refusal with no JSP key follows Tier 5's rule
  for new codes.
- **Same permission as the JSP screen.** Each new route declares, through
  `UISecurityEnforced`, exactly what the JSP controller it mirrors declares
  (named per task). No second permission check is added anywhere.
  Declarations are per controller class, so a route needing different
  declarations gets its own controller.
- **Pins.** A route that names a weblog takes it only from `{handle}`. A
  route with no `{handle}` is denied to a weblog-pinned token (the
  `ApiScopeInterceptor` default) unless this spec marks it
  `@WeblogScopeExempt`. Any route mapped under `/v1/admin/` is
  `@AdminScoped` (`AdminScopedCoverageTest` enforces it).
- **Shapes.** Every response is a record (no `Map`, no `Object`), with
  `@Schema` descriptions on enums and required fields, so Tier 5's OpenAPI
  test passes on the new operations. Every new list route uses the house
  list envelope as Tier 5 settles it (at least `items`, `offset`, `limit`,
  `hasMore`, `total`), accepts `offset`/`limit` (default 50; above 200 is
  capped at 200; `limit` < 1 or `offset` < 0 is a 400 through
  `Paging.requireRange`), and has a stated, stable sort order. If Tier 5
  names the envelope's members differently, Tier 5 wins.
- **Authorization matrix.** Every new route gets a row in the Tier 2/6
  matrix. Each task names a *reference route*: the new route's row must be
  cell-for-cell equal to the reference route's row (same declarations, same
  answers for every caller). The matrix test asserts this equality
  explicitly, so a later change to either side surfaces.
- **MCP.** Every new route is decided in the same change: an `ActionDecl`
  in `McpToolCatalog` or an `Exclusion` with a reason (`McpCoverageTest`).
  The pinned surfaces move with it: `ApiRouteTableTest` (72 routes before
  this work; each task raises the count by exactly its routes, 11 in all),
  `ToolSchemasTest`, `McpToolCatalogTest`, `McpDocsTest` (README tool
  table). `McpInstructions.TEXT` stays under 3,000 characters; no task needs
  a new line in it, and one added must fit.
- **Docs move with behaviour.** `docs/api/README.md` (a section per
  resource, the Pagination list of list endpoints, the MCP table),
  `docs/dev/api.md` (anything non-obvious a later change must know),
  `@Schema`/`@Parameter` texts (shared by `openapi.json` and the tools).
  `bin/pressroom-api` gains no commands in this work.
- **Order and parallelism.** Tasks 1-5 all edit `McpToolCatalog`,
  `ApiRouteTableTest`, the matrix test and the README's MCP table, so run
  them serially in one tree, or in worktrees only with the base and overlap
  check in `docs/dev/build-and-ci.md` and a merge of those four files
  expected. Task 6 is docs only and can run alongside any of them.
- Commit on master; never push.

### Callers used in the criteria

`ANON` (no credentials); `PWD` (Basic, correct site-admin password);
`SA-R`, `SA-P`, `SA-A` (a site admin's unpinned token of role `READ`,
`POST`, `ADMIN`); `SA-A@W` (a site admin's `ADMIN` token pinned to the
route's weblog `W`); `OWN-A`, `OWN-R` (the non-site-admin owner of `W`, an
unpinned `ADMIN` / `READ` token); `OWN-A@W`, `OWN-A@Y` (that owner's `ADMIN`
token pinned to `W` / to another weblog `Y`); `MEM-P`, `MEM-D` (`ADMIN`
tokens of a `POST`-level and an `EDIT_DRAFT`-level member of `W`); `NONE`
(an `ADMIN` token of a user with no role on `W`); `AGENT` (an agent
account's token, with the agent's memberships). These are the Tier 2 matrix
callers.

- **Amendment (2026-11-21, controller, after Tiers 2 and 5 landed).** Every
  new route: raises errors only through the keyed `ApiException` factories
  with a `ProblemCode` (new codes get a bundle entry, a README code-table row
  and a `ProblemCodeCatalogTest` pin; reuse an existing code where the
  meaning is the same); gets an `ApiAuthorizationMatrixTest` row
  (hand-written expectations) and an operationId in `OpenApiContractTest`'s
  pinned list; returns records, never `Map`; a paged list answers
  `{items, offset, limit, hasMore}` (`ListShapeOpenApiTest` rule, now inside
  `OpenApiContractTest`). Caller kind is asked of `Caller` only
  (`CallerKindConfinementTest`). `McpInstructions.TEXT` is at ~2995/3000: a
  new tool's guidance must trim the same amount elsewhere.

## Approaches considered

1. **Wrap the JSP controllers' model data as JSON.** Fast, but the model
   carries display strings (formatted numbers, dashes for "no data",
   message keys) and the controllers hold validation inline. Rejected:
   it would freeze display formatting into the API contract.
2. **Build every gap now, including the session-only ones, behind a
   caller-kind check.** The caller kind exists after Tier 2, but no
   `SESSION` caller can reach `/api` yet, so the routes would be dead code
   whose authorization nothing exercises. Rejected (Tier 2 rejects session
   login on `/api` before it has a user for the same reason).
3. **Thin wrappers over the business tier and shared rule classes, typed
   records out, and a written policy for the rest** (chosen). Where a rule
   is still inline in a JSP controller (the business form's validation),
   the task moves it into a shared class both surfaces call, the Tier 3
   pattern (`WeblogSettingsRules`), so the two surfaces cannot drift.

## Task 1: Client config and the caller's profile

Two read-only routes on `MetaApi`, beside `/v1/me`.

**`GET /v1/meta`** answers the site facts a client needs before it draws a
screen. It is the same for every authenticated caller, so a client can
fetch it once per session. It is a new route rather than more fields on
`/v1/me`, which stays about the caller (and stays small for `whoami`).

`MetaView` record:

- `version`: the Pressroom version, the value the footer shows
  (`Colophon.version`, from `Weblogger.getVersion`); null when unknown.
- `siteUrl`: the site's absolute URL: `site.absoluteurl` with the
  configuration fallback (`getPropertyWithConfigFallback`), trailing `/`
  stripped, null when blank. Reuse `SsoConfig.configuredAbsoluteUrl`'s
  derivation rather than writing a second one.
- `features`: a `Features` record of booleans, each read from the setting
  the JSP reads: `customThemes` (`themes.customtheme.allowed`),
  `groupBlogging` (`groupblogging.enabled`), `userWeblogCreation`
  (`site.allowUserWeblogCreation`), `uploads` (`uploads.enabled`),
  `readerSignIn` (`SsoSettings.enabled`), `analytics` (the site has an
  analytics service configured: the same check that makes `traffic get`
  answer `NOT_CONFIGURED`).
- `limits`: a `Limits` record: `maxBodyBytes`
  (`ApiBodyLimitFilter.MAX_BODY_BYTES`), `maxListLimit` (200),
  `entryTextMaxChars` (`ColumnLimits.ENTRY_TEXT`), each read from the
  constant, never repeated as a literal.

**`GET /v1/me/profile`** answers the caller's own account, read-only:
`ProfileView(userName, screenName, fullName, emailAddress, locale,
timeZone, kind)`, the fields the Profile page shows minus every password
field.

Both are `@WeblogScopeExempt` (a pinned token must still be able to ask),
need an authenticated user (401 otherwise, as `/v1/me`), and take any token
role. Reference route for both: `GET /v1/me`.

**Acceptance criteria.**
1. `GET /v1/meta` with any token (`SA-R`, `OWN-A@W`, `MEM-D`, `AGENT`) is
   200 with every member above present; `ANON` and `PWD` are 401.
2. Each `features` flag follows its setting: turning the runtime property
   (or SSO configuration) on and off between two requests flips the flag
   in the next response, with no restart (nothing is cached).
3. `siteUrl` is the absolute URL without a trailing slash, the
   configuration fallback when the runtime setting is blank, and null when
   both are blank.
4. Each `limits` member equals its constant (a test reads the constant, so
   a change to the constant cannot leave the response behind).
5. `GET /v1/me/profile` returns the token owner's values, for a person and
   for an agent account (`kind` `AGENT`); no member of the response holds a
   password, a hash or a token. A test asserts the record's component set,
   so a field added later is a deliberate change.
6. The matrix rows of both routes equal `GET /v1/me`'s.
7. MCP: a new read-only tool `site_info` (no action, like `whoami`)
   calls `GET /v1/meta`. `GET /v1/me/profile` is an `Exclusion`: "The
   person's own contact details; nothing an agent acts on, and whoami
   names the account."
8. README: a "Site facts and your profile" section documents both routes
   and every member; the MCP table gains `site_info`.

## Task 2: A weblog's members (read)

**`GET /v1/weblogs/{handle}/members`** lists who holds a role on the
weblog, as the Members page shows it. It mirrors `MembersController`'s
declarations (inherited from `BaseController`: `WeblogPermission.ADMIN`,
`GlobalPermission.LOGIN`, weblog required) in its own controller,
`MembersApi`. It is not `@AdminScoped`: like `TrafficApi`, a `READ` token
held by the weblog's admin may read it. Reference route:
`GET /v1/weblogs/{handle}/traffic`.

`MemberView(userName, screenName, kind, permission)`; `permission` is
`ADMIN`, `POST` or `EDIT_DRAFT` (the strongest action the row grants, named
as `/v1/me`'s `weblogs[].permission`; share the one mapping, do not write a
second). No email address: the Members page does not show one. Sorted by
`userName`, case-insensitive. A membership whose account no longer resolves
(deleted or disabled) is left out, as the Members page shows no control for
it; a lookup that cannot run is a 500, never a shorter list.

**Acceptance criteria.**
1. `OWN-A`, `OWN-R`, `OWN-A@W`, `SA-R` and `SA-A@W` get the list; `MEM-P`
   and `MEM-D` get 403; `NONE` and `OWN-A@Y` get 404; `ANON`/`PWD` 401.
   The matrix row equals the reference route's.
2. The site space's handle is 404 for every caller, a site admin included
   (the API never offers the site space).
3. Each item carries exactly the four members; `permission` is right for
   one member at each level; an agent member shows `kind` `AGENT`.
4. A membership whose account does not resolve is absent and the others
   are listed; a failing permission lookup answers 500 (test with a
   throwing manager).
5. The envelope and paging follow Global constraints; `total` counts every
   listed member, not the page.
6. MCP: `weblogs` gains action `list_members` (read). README: a "Members"
   section, stating that membership changes are made in the admin UI (see
   the session-only policy below), and the Pagination list.

## Task 3: Site-wide traffic

**`GET /v1/admin/traffic?period=`** answers what the Site Traffic page
shows, as numbers rather than display strings. Declarations mirror
`SiteTrafficController` (`GlobalPermission.ADMIN`, no weblog); the route is
`@AdminScoped` because it is under `/v1/admin/`. It goes in `AdminApi` or a
controller of its own with the same declarations (builder's call).
Reference route: `GET /v1/admin/users`.

`SiteTrafficView(state, asOf, period, totalViews, totalVisits, blogs,
posts, sources)`, from `AnalyticsManager.getSiteTraffic`:

- `state` as `TrafficDtos.TrafficView.state` (same `@Schema` text);
  `asOf` absent when there is no snapshot.
- `blogs`: one `SiteBlogRow(handle, name, siteSpace, standing, visitors,
  views, visits, change, topPostTitle)` per blog, in the order the manager
  returns. `standing` is `READ`, `NO_DATA` or `NOT_REGISTERED`. When
  `standing` is not `READ` the four numbers are null, never 0 (the page's
  "dashes, never zeros" rule). `change` is null when the previous period
  had no views (the manager's NaN). `topPostTitle` is plain text, through
  `EntryFieldRules.plainTitle`.
- `posts`: `TrafficDtos.Post` rows; `sources`: `TrafficDtos.Row` rows.
  Reuse those records, so one post row has one shape across both traffic
  routes. No `Map`.

`period` is one of `TrafficView.PERIODS` (default `30d`), refused with
the 400 `TrafficApi` gives for the same mistake.

**Acceptance criteria.**
1. `SA-A` gets 200; `SA-R`/`SA-P` 403 (not scoped for administration);
   `SA-A@W` 404 (a pinned token, a route that is not part of one weblog);
   `OWN-A` 403; `ANON`/`PWD` 401. The matrix row equals the reference
   route's.
2. A blog with standing `NO_DATA` and one with `NOT_REGISTERED` each have
   null numbers and null `change`; a `READ` blog with a previous period of
   zero views has numbers and null `change`.
3. A top post title stored as `Tom &amp; Jerry` is returned as
   `Tom & Jerry`, in `blogs[].topPostTitle` and in `posts[].title`.
4. `state` `NOT_CONFIGURED` answers 200 with empty lists and no `asOf`,
   not an error.
5. A bad `period` is a 400 on `period` whose detail lists the periods.
6. MCP: `traffic` gains action `site` ("Every blog's views and visits,
   and the site's top posts and sources; needs a site admin and an ADMIN
   token"); the tool's description stops saying it needs a weblog admin
   only. README: the Traffic section documents the route.

## Task 4: Crop an image

**`POST /v1/weblogs/{handle}/media/{id}/crop`** crops an image destructively,
as the media editor's crop does (`MediaFileEditController.crop` over
`MediaFileManager.cropMediaFile`): the stored original is re-encoded to the
rectangle and every derivative rebuilt. It lives in `MediaApi`, whose
declarations (`WeblogPermission.POST`) are `MediaFileBase`'s, the crop
screen's. The file is looked up through the same ownership check as every
other media route (`MediaApi.requireMedia`, over `WeblogOwnership.mediaFile`), before anything
reaches the manager. Reference route: `PATCH /v1/weblogs/{handle}/media/{id}`.

Body `MediaCrop(x, y, width, height, expectedWidth, expectedHeight)`:
`x`, `y`, `width`, `height` are required integers in pixels of the
displayed (orientation-corrected) image, as the manager's javadoc defines
them; a rectangle that overshoots the image is clamped, as in the editor.
`expectedWidth`/`expectedHeight` are optional: when sent, they must equal
the stored `width`/`height` the file's `MediaView` reports, so a retried or
stale crop cannot crop an already-cropped image a second time. Response:
200 with the file's `MediaView` after the crop (new dimensions, blurhash and
focal point).

The manager today throws one `WebloggerException` for "not croppable",
"rectangle outside the image" and "storage failed". The API must tell the
first two (caller mistakes) from the third (a 500), so the manager signals
them with a distinct exception (for example a `CropRefusedException` with a
reason) and the JSP controller keeps its current message for all of them.
Never infer the reason from a message string.

**Acceptance criteria.**
1. A JPEG and a PNG crop: 200; the stored file's dimensions, the `MediaView`
   and the rebuilt renditions match the clamped rectangle (reuse the
   manager's existing crop test fixtures).
2. A GIF, a WebP and a non-image file: 409 naming the file's type and that
   only JPEG and PNG images can be cropped; nothing on disk changes.
3. Missing `x`/`y`/`width`/`height`: 400 naming each missing field;
   `width` or `height` below 1: 400 on that field; a rectangle that does
   not intersect the image: 400 naming the rectangle and the image's size.
   None reaches the encoder.
4. `expectedWidth`/`expectedHeight` that do not match: 409 whose detail
   gives the current size; nothing changes. Matching values, or none sent:
   the crop runs.
5. A storage failure during the crop is a 500 (opaque internal-error
   problem), never a 400 or a 409.
6. Another weblog's file id and an unknown id: 404, indistinguishable, and
   the manager is never called (verify with a mock).
7. `READ` token: 403; `MEM-D`: 403; `NONE` and `OWN-A@Y`: 404; `MEM-P`,
   `OWN-A`, `OWN-A@W`: reached. The matrix row equals the reference
   route's.
8. The JSP crop's behaviour is unchanged (its existing tests pass
   unchanged; add one for the new exception's message mapping).
9. MCP: `media` gains action `crop`, whose description says it cannot be
   undone and to send `expectedWidth`/`expectedHeight` from `media get`.
   README: a "Cropping an image" subsection under Media saying the same.

## Task 5: Business profiles

The shared business records (`Business`, `BusinessManager`) that blogs
point at with `businessId`. Today a weblog owner can set `businessId` over
the API but has no way to learn an id, and nothing can create or change a
business.

**Shared rules first.** `BusinessesController.validate` and `apply` are
inline. Move them to one shared class (for example `BusinessRecordRules` in
`ui.controllers`, beside `BusinessRules`) returning violations shaped like
`WeblogSettingsRules.Violation` (field, message key, English detail), and
move `invalidateUsers` (drop `SiteWideCache` for every blog using the
business, logged not reported after the save) beside them. The JSP
controller and the API both call them. Write characterisation tests of the
JSP save first (they pass on the old code; say so in their javadoc). If
Tier 3 has already moved these, use its class.

**Routes.**

| Route | Controller and declarations |
|---|---|
| `GET /v1/admin/businesses` | `BusinessesApi`: `@AdminScoped`, `GlobalPermission.ADMIN`, no weblog (as `BusinessesController`) |
| `GET /v1/admin/businesses/{id}` | same |
| `POST /v1/admin/businesses` | same |
| `PATCH /v1/admin/businesses/{id}` | same |
| `DELETE /v1/admin/businesses/{id}` | same |
| `GET /v1/weblogs/{handle}/businesses` | `WeblogBusinessesApi`: `@AdminScoped`, `WeblogPermission.ADMIN` (as `WeblogSettingsApi`, whose settings form offers the same list) |

Reference routes: the admin `GET`s equal `GET /v1/admin/users`; `POST`,
`PATCH`, `DELETE` equal `POST /v1/admin/users` /
`PATCH /v1/admin/users/{userName}`; the weblog route equals
`GET /v1/weblogs/{handle}`.

**Shapes.** `BusinessView(id, name, businessType, websiteUrl, bookingUrl,
telephone, email, logoUrl, sameAs, areaServed, description, weblogCount,
created, lastModified)`: `businessType` is `Organization`,
`LocalBusiness` or `ProfessionalService`; `sameAs` is a list of URLs (the
entity stores newline-separated lines); `weblogCount` is
`countWeblogsUsing`, as the Businesses list shows. The weblog route returns
`BusinessChoice(id, name, businessType)` items, what the settings form's
picker shows. Both lists are sorted by `name`, case-insensitive, then `id`.
`BusinessWrite(name, businessType, websiteUrl, bookingUrl, telephone,
email, logoUrl, sameAs, areaServed, description, clear)`: null is absent,
never a value; `clear` removes any optional field (every field but `name`
and `businessType`), through `ClearFields`. On create `name` is required
and `businessType` defaults to `Organization` (the entity's default).
Values are stored raw, as the form stores them.

**Acceptance criteria.**
1. Create, get, list, patch and delete round-trip every field; a `PATCH`
   changes only the fields sent; `clear` removes an optional field and is
   refused (400) for `name` and `businessType`.
2. Every rule the Businesses form enforces is enforced over the API through
   the shared class, with the field named in `errors`: name required and
   at most 255; unknown `businessType`; `websiteUrl`, `bookingUrl`,
   `logoUrl` not an absolute http(s) URL or over 255; telephone pattern;
   email invalid or over 255; `areaServed` over 255; more than 10 `sameAs`
   entries; a `sameAs` entry not http(s) or over 255. One test per rule
   per surface; all violations of one request are reported together.
3. Saving a business bumps `lastModified` of every weblog using it and
   drops their site-wide cache entries (the shared method, tested once,
   called by both surfaces).
4. `DELETE` of a business some weblog uses: 409 whose detail names how
   many weblogs use it (code `businesses.error.inUse`); nothing removed.
   An unused business: 204. An unknown id on get, patch or delete: 404.
5. Admin routes: `SA-A` reached; `SA-R`/`SA-P` 403; `SA-A@W` 404;
   `OWN-A` 403; `ANON`/`PWD` 401. Weblog route: `OWN-A`, `OWN-A@W`,
   `SA-A` reached; `OWN-R` 403 (not scoped for administration); `MEM-P`
   403; `NONE`, `OWN-A@Y` 404; the site space 404. Each matrix row equals
   its reference route's.
6. The id a weblog route item carries is accepted by
   `PATCH /v1/weblogs/{handle}` `businessId` (an end-to-end test: list,
   pick, set, get).
7. `FormBodyParityTest` gains `BusinessBean -> BusinessWrite`, with `id`
   excluded (the path names it) and nothing else renamed.
8. The JSP Businesses screen's existing tests pass unchanged after the
   move.
9. MCP: a new tool `businesses` (`list`, `get`, `create`, `update`,
   `delete`; "Site-wide business profiles that blogs show as schema.org
   data. Needs a site admin and an ADMIN token."), and `weblogs` gains
   `list_businesses`. README: a "Business profiles" section under Site
   administration, a pointer from the weblog `businessId` field to the
   weblog route, and the Pagination list.

## Task 6: Record the session-only policy

Docs only; no route, no code.

**Acceptance criteria.**
1. The section "Session-only resources (policy)" below is pasted into the
   Simple Agility decision log (`SimpleAgilityDecisionLog` on Wikantik)
   under the next free number, and that number replaces "SA-next" in this
   spec and in `docs/dev/api.md`. If the wiki cannot be written from the
   session, the report says so and the paste is left to the top-level
   session.
2. `docs/dev/api.md` gains a short "Session-only resources" entry: the list,
   the rule that each is built with a caller-kind check refusing `TOKEN`
   and `PASSWORD`, and a pointer to the decision.
3. `docs/api/README.md` gains a short "Not available through the API"
   list naming each session-only resource and that it is done in the admin
   UI. The MCP section's existing sentence on what is not available stays
   consistent with it.

## Session-only resources (policy)

*Written to be pasted into the decision log as is.*

**SA-next: Pressroom API resources that only an interactive session may
use (2026-11-20).**

**Context.** Pressroom's `/api/v1` and its MCP façade authenticate with
bearer tokens; a token can be pinned to one weblog and limited to a role,
and the API knows the caller's kind (`TOKEN`, `PASSWORD`, `SESSION`) in one
place. A browser admin UI will later call the same API with a signed-in
session. Some admin operations must not be reachable by a token at all,
because a stolen or over-shared token is the API's most likely compromise,
and agents act on text written by other people.

**Decision.** The following are built only for `SESSION` callers, when
session login on `/api` exists. Until then they stay in the JSP admin UI and
have no API route.

| Resource | Why a token may never use it |
|---|---|
| Agent accounts (create, change, site-admin switch, their tokens) | Creates new identities with their own credentials: a stolen token could outlive its own revocation. |
| Admin accounts and the admin role (any change) | Already refused to tokens (`AdminAccountGuard`). Taking over or adding an admin is the whole site. |
| Weblog memberships (grant, change level, remove) | Granting a role to another account gives lasting access that revoking the token does not remove; removal can lock the owner out. |
| Profile changes (email, password, names, locale) | The email address is where password-reset links go: changing it is an account takeover. One rule for the whole profile keeps it simple. |
| The site-wide token list (every user's tokens, revoke any) | Shows who holds access and lets one token revoke everyone else's. A person's own tokens stay on `/v1/tokens` under a password, as today. |
| Weblog deletion | Irreversible and total (no trash). |
| Custom templates and stylesheets | Template output is not sanitized: it bypasses `HTMLSanitizer`, the one content boundary, so a template write is script on every page of the blog. |
| The contact-form inbox (read or delete) | Readers' personal details and messages: third-party personal data, and text written by strangers handed to agents. |

Also unchanged and token-refused: minting, listing and revoking tokens with
a token, and writing `site.absoluteurl`.

**Rules for building them.**

1. Each route refuses `TOKEN` and `PASSWORD` callers with 403, through the
   one caller-kind resolver; `ANONYMOUS` and any unrecognised caller are
   refused (fail closed). The visibility rules run first: a caller who may
   not see the weblog still gets 404.
2. Session callers on `/api` need CSRF protection on every write before
   any of these routes ships (the API chains are CSRF-free today because
   they are stateless and bearer-only).
3. Each route is an MCP `Exclusion` with the reason "session-only", and
   its row in the API authorization matrix shows 403 for every token
   caller.
4. Moving a resource off this list, in either direction, is a new decision
   that names its threat, not a code change alone.

**Consequences.** Agents cannot manage people, access or templates; their
person does it in the admin UI (the MCP instructions already say so for
accounts, memberships and tokens). The React admin needs session login on
`/api` before it can replace these screens; every other screen it can build
on token-safe routes.

## Decisions made while drafting

Ruled without the owner; each with the cost if wrong.

1. **Tag rename/merge is not built.** The JSP UI offers neither (no
   controller, form or manager method; `WeblogEntryManager` only lists
   tags), so the condition in item 7 is false. If wanted, it is token-safe
   (weblog `POST`, like categories). Cost if wrong: one more task later.
2. **`/v1/meta` is new; `/v1/me` is not extended.** Site facts are the same
   for every caller and cacheable; `/me` is the caller and is what `whoami`
   returns on every agent session. Cost if wrong: a client makes two calls
   at start instead of one.
3. **`/v1/meta` needs authentication.** The version is public in the
   footer, but nothing yet needs these facts before sign-in; session login
   can open a public subset when it has a login page to serve. Cost if
   wrong: a one-line `permitAll` and a matrix row change.
4. **No site-wide "comments" flag in `features`.** Comments are switched
   per weblog (`commentsEnabled` on the weblog view) and need reader
   sign-in, which `features.readerSignIn` reports. Cost if wrong: one
   boolean added later.
5. **The profile route is excluded from MCP.** It is the person's contact
   details and an agent acts on none of it. Cost if wrong: one
   `ActionDecl`.
6. **Member writes are session-only, not "later, token-safe".** Item 7 says
   "read first"; I ruled the writes out for tokens because a grant
   outlives the token that made it. Cost if wrong: a later decision moves
   them off the list; nothing built here changes.
7. **Members read is not `@AdminScoped`** (a weblog admin's `READ` token
   may list members), following `GET .../traffic`; the weblog business
   picker *is* `@AdminScoped`, following `GET /v1/weblogs/{handle}`, the
   only route its ids are used on. Cost if wrong: one annotation and the
   matrix rows each.
8. **Owners get a business picker route under their weblog**
   (`/v1/weblogs/{handle}/businesses`) rather than read access to
   `/v1/admin/businesses`, so a pinned owner token reaches it and the admin
   resource keeps one permission. Cost if wrong: one route to retire.
9. **Crop guard is on dimensions, not a content hash.** The crop clears the
   stored content hash, so a hash cannot guard the next crop; width and
   height always change unless the crop is a no-op, which is harmless to
   repeat. Optional rather than required, so a client that already checked
   need not send it. Cost if wrong: a retrying client without the guard can
   crop twice (the editor has the same exposure today).
10. **A non-croppable file is 409, a bad rectangle 400.** The file's type is
    the resource's state (like a trashed page's 409); the rectangle is the
    request. Cost if wrong: two status codes and their tests.
11. **`businessType` defaults to `Organization` on create** (the entity's
    default) rather than being required; the form always posts one. Cost if
    wrong: agents create businesses with an unintended type until someone
    notices; one validation change.
12. **Every new list route pages**, even though businesses and members are
    small, so the list contract has no exceptions. Cost if wrong: two
    unused parameters.
13. **Crop does not invalidate the rendered-page cache**, matching the JSP
    crop. Rendered pages carry the old rendition sizes until
    `weblog.lastModified` moves. Cost if wrong: stale `width`/`height` in
    cached HTML on both surfaces; a separate fix for both.
14. **No `bin/pressroom-api` commands.** The CLI is a convenience and no
    test holds it to the route table. Cost if wrong: a CLI follow-up.

## Out of scope

- Session login on `/api`, CSRF for it, and every resource in the
  session-only policy.
- Member writes, profile writes, tag rename/merge.
- Changing the shape of existing routes (the bare-array lists, the `Map`
  responses): Tier 5.
- Moving JSP controller logic other than the business form's (Tier 3), and
  re-anchoring `FormBodyParityTest` (Tier 3/6).
- The crop's rendered-page cache expiry on either surface (Decision 13).
