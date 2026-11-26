# Pressroom Automation API

Pressroom's automation API (`/api/v1`) lets a script or an agent create and edit
entries, upload media, run SEO/media audits, and administer weblogs and
users — everything the JSP admin UI can do, callable without a browser.

> **`/api/v1` is unstable while Pressroom is 0.x.** Paths, fields and status
> codes here can change release to release with no deprecation window. Pin a
> Pressroom version if you automate against this; do not assume forward
> compatibility.

Mint a token in the admin UI: **Your profile → Your API tokens** for yourself, or an
agent's page (**Server admin → Users →** the agent) for an agent account. The secret
is shown once. A script can mint one with the CLI instead:

```
bin/pressroom-api auth login --url http://localhost:8083
```

Everything below assumes a Pressroom running at that URL (the default for
`./pressroom dev`, which serves from the root; see the repository's
`CLAUDE.md`, "Build and Development Commands"). In production, substitute
your site's own origin.

### Local development

Two commands, no password to remember:

```bash
./pressroom dev      # starts postgres, seeds the dev admin, runs the app
./pressroom token    # mints a bearer token for that admin
```

The dev admin password lives in `.pressroom-dev-secret`, generated with mode 0600
on the first `./pressroom db|dev|reset` and printed once at that moment. It is
git-ignored and is the source of truth: the seed re-applies it on every run, so
a password changed through the web UI is reverted next time. Edit the file to
choose your own.

### Non-interactive login

`auth login` prompts for a password by default. `--password-stdin` reads it
from stdin instead — never an argv entry, never an environment variable, so it
is safe in CI:

```bash
printf '%s\n' "$PASSWORD" | bin/pressroom-api auth login \
    --url https://example.com/pressroom --user admin --password-stdin
```

## Changes since 0.16.3

Added for the React admin screens, which use the same routes as any other client (additions only; nothing that
worked changes):

- `GET .../entries` takes `startDate` and `endDate` (`yyyy-MM-dd`, the entries page's date range); a malformed
  one is a 400 `parameter.invalidValue` naming it. See [Entries](#entries).
- `POST .../entries/{id}/duplicate` copies an entry into a new draft, and `POST .../entries/{id}/send-newsletter`
  mails it to the weblog's list; both need publish rights. See [Entries](#entries).
- An entry reads and writes `locale` and `pinnedToMain`, and reads `newsletterSentAt`. Changing `pinnedToMain` is
  a site administrator's: 403 `entry.needsSiteAdmin` otherwise.
- A category reads and writes `image`, an absolute `http` or `https` URL; anything else is a 400
  `category.imageInvalid`. See [Categories and tags](#categories-and-tags).
- New problem codes: `entry.needsSiteAdmin`, `newsletter.notPublished`, `newsletter.alreadySent`,
  `newsletter.noList`, `newsletter.notConfigured`, `newsletter.sendFailed`, `newsletter.sentButNotRecorded`,
  `category.imageInvalid`. See [Errors](#errors).
- Neither new entry route is an MCP action: duplicating is an editing-screen convenience and sending a newsletter
  is a person's decision. See [MCP](#mcp).

## Changes in 0.16.3

- A JSON body that declares no length (chunked) and runs past the 4,048,576-byte
  limit is a 413 `request.tooLarge`, as one that declares a length over it
  always was; it was a 400 `request.malformedBody`. See [Errors](#errors).
- `image.scanRefused`'s `detail` is the API's own text now, the refused tag
  quoted in it; the wording, status and code are unchanged.
- `bin/pressroom-api` gains `weblogs get` and `weblogs patch`, the names the API
  gives the owner's own weblog (`GET` and `PATCH /api/v1/weblogs/{handle}`).
  `admin weblogs get` and `admin weblogs patch` keep working as aliases; use
  `weblogs` in new scripts. `admin weblogs list` is unchanged (site admins).
- `openapi.json` is unchanged in shape: media upload is a `multipart/form-data`
  body and the entry routes answer `application/json`, as before; only the
  revision store's `ItemCount.kind` description now comes from the API side.

## Changes in 0.16.0

Three changes can break a client written against 0.15:

- **HTTP Basic works only on `/api/v1/tokens`.** Every other route answers a
  password with a 401: mint a token once and send it as a Bearer. See
  [With the API](#with-the-api).
- **Three lists answer the paged envelope, not a bare array:** `GET
  /api/v1/weblogs`, `GET /api/v1/admin/users` and `GET .../media` return
  `{items, offset, limit, hasMore}`, over MCP too. See
  [Pagination](#pagination).
- **Some statuses changed.** A duplicate redirect source is a 409 (was 400)
  and a failure to store a rule is a 500 (was a 400 carrying the failure's
  text), see [Redirects](#redirects); a rate-limited comment reply is a 429
  (was 400), see [Comments](#comments).

Additive or narrower changes:

- Every problem body, and every entry of its `errors`, carries a stable
  `code`; branch on it, never on `detail`. `detail` follows
  `Accept-Language` where the server holds a translation (none ship yet). See
  [Errors](#errors).
- New: `GET /api/v1/meta` (site facts) and `GET /api/v1/me/profile`, the MCP
  `site_info` tool. See [Site facts and your profile](#site-facts-and-your-profile).
- New: `GET /api/v1/weblogs/{handle}/members`, read only, and the MCP `weblogs`
  `list_members` action. See [Members](#members).
- New: `GET /api/v1/admin/traffic` (every blog's traffic, site admins) and the
  MCP `traffic` `site` action. See [Site traffic](#site-traffic).
- New: business profiles. Site admins create, read, change and delete them at
  `/api/v1/admin/businesses` (the MCP `businesses` tool), and a weblog's owner
  lists the ones a weblog may point at with `GET
  /api/v1/weblogs/{handle}/businesses` (the MCP `weblogs` `list_businesses`
  action). See [Business profiles](#business-profiles).
- A contributor's (`EDIT_DRAFT`) token writes drafts, a publish being
  submitted for review, and reads the categories, tags and media the entry
  editor shows. See [Entries](#entries) and [Media](#media).
- An update of an entry already `PENDING` no longer mails the reviewers
  again. See [Entries](#entries).
- A category name must survive HTML escaping. See
  [Categories and tags](#categories-and-tags).
- Media names are judged more strictly: no slash inside a directory name, no
  two files of one name in a directory. See [Media](#media).
- New: `POST /api/v1/weblogs/{handle}/media/{id}/crop` and the MCP `media`
  `crop` action. It cannot be undone. See [Cropping an image](#cropping-an-image).
- A token can no longer change another user's email address: `PATCH /api/v1/admin/users/{userName}`
  answers 403 `user.emailUiOnly` for a changed `emailAddress`. See [Site administration](#site-administration).
- `emailAddress` on the admin user routes is judged as the admin UI judges
  it, and `PATCH /api/v1/admin/config` applies the Global Config screen's
  rules. See [Site administration](#site-administration).
- The OpenAPI document gained operation ids and tags. See
  [The OpenAPI document](#the-openapi-document).

## Contents

- [Changes in 0.16.3](#changes-in-0163)
- [Changes in 0.16.0](#changes-in-0160)
- [Session-only](#session-only)
- [Getting a token](#getting-a-token)
- [Token scopes](#token-scopes)
- [Making a request](#making-a-request)
- [Removing a value](#removing-a-value)
- [Pagination](#pagination)
- [Errors](#errors)
- [The throttle](#the-throttle)
- [Site facts and your profile](#site-facts-and-your-profile)
- [Entries](#entries)
- [Categories and tags](#categories-and-tags)
- [Media](#media)
- [Pages](#pages)
- [Redirects](#redirects)
- [Comments](#comments)
- [Audits](#audits)
- [Traffic](#traffic)
- [Members](#members)
- [Site administration](#site-administration)
- [Maintenance actions](#maintenance-actions)
- [MCP](#mcp)
- [The OpenAPI document](#the-openapi-document)
- [`bin/pressroom-api`](#binpressroom-api)

## Getting a token

### In the admin UI

**Your profile → Your API tokens** mints, lists and revokes your own tokens. A site
admin does the same for an agent on the agent's page, and sees every token on
the site under **Server admin → API tokens** (owner, kind and issuer; a toggle adds
revoked and expired ones). The form takes a label, a role (Read, Write or
Admin: `READ`, `POST`, `ADMIN`), a weblog (all of the account's weblogs, or
one it is a member of; a site admin may pick any) and an expiry (30, 90 or
365 days, or never; 90 by default). The next page shows the secret once, with
an MCP snippet to copy. It is never cached, and loading it again only says
the token was already shown.

### Agent accounts

An agent is an account for software. It never signs in -- no password, no
password reset, no single sign-on -- and works only through the tokens a site
admin mints for it. A site admin creates it under **Server admin → Users → New
agent**, adds it to weblogs on its page, and may switch on **Site
administrator**, which lets an `ADMIN` token from it use the site-wide admin
API. Disabling an account, agent or person, stops every token it holds on the
next request. Agent accounts, memberships and tokens are managed only in the
admin UI: no API route creates or changes an agent, or mints a token for one.

### With the API

`POST /api/v1/tokens` (mint), plus `GET /api/v1/tokens` (list) and
`DELETE /api/v1/tokens/{id}` (revoke), is where you use HTTP Basic
authentication — your Pressroom username and password — because minting your
first token can't itself require a token to prove who you are. **The token
resource is the only place a password is accepted.** Every other route under
`/api/**` takes a bearer token only: a request there carrying
`Authorization: Basic ...` is a 401 problem whose `detail` is `Password
authentication is accepted only on /api/v1/tokens. Mint a token there and
send it as a bearer token.`, whether or not the password is right (it is
never checked there). `GET /api/v1/ping` needs no credential and ignores
whatever it is sent.

**A Bearer-authenticated caller (an existing API token) is refused on all
three `/v1/tokens` operations**, regardless of that token's own role — a
leaked token can never mint itself a replacement, list its owner's other
tokens, or revoke one, which would otherwise turn a single leaked credential
into a permanent, self-renewing one. The refusal is a 403: `A token cannot be
used to mint another token.` on `POST`, `Token management requires Basic
authentication.` on list and revoke, except that a `READ` token meets the
read-only 403 (`This token is read-only.`) on `POST` and `DELETE` first. A
weblog-pinned token gets a 404 instead (the token resource names no weblog;
see [Token scopes](#token-scopes)).

A password the token resource turns away (wrong, a disabled account, an agent,
an unknown user) is a 401 problem whose `detail` is `The username or password
was not accepted.`. Every 401 on the token resource, that one included,
carries `WWW-Authenticate: Basic realm="Pressroom"`, so a client that sends a
password only when challenged still works; no other route challenges.

> **Changed in 0.16.0: HTTP Basic outside `/api/v1/tokens` stops working.**
> Earlier versions accepted a username and password on every `/api/**` route
> (undocumented, but it worked). From 0.16.0 every route but the token
> resource answers a password with the 401 above. A script that sends `-u
> user:password` to any other route must mint a token once
> (`bin/pressroom-api auth login`, or `POST /api/v1/tokens`) and send
> `Authorization: Bearer <token>` instead. An admin-UI session cookie
> is no credential for scripts: only the admin app itself uses it on `/api`, with a
> CSRF token on writes (`csrf.invalid` below), and `/api/mcp` never accepts one. Smaller edges of the same
> change: `GET /api/v1/ping` with a wrong password is now 200 (it was 401);
> a password on `/api/pressroom-ui/...` is now 401 (it was 403); a wrong
> password on any route but the token resource is the problem+json 401 above,
> with no `WWW-Authenticate` challenge; and on the token resource a wrong
> password is now a problem+json 401 (it was Boot's plain error JSON).

```bash
curl -sS -u alice:hunter2 \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:8083/api/v1/tokens \
  -d '{"label": "seo-agent", "role": "POST", "weblog": "myblog"}'
```

```json
{
  "token": "prs_9f3a1c...",
  "info": {
    "id": "a1b2c3...",
    "label": "seo-agent",
    "scopeWeblog": "myblog",
    "scopeRole": "POST",
    "created": "2026-08-15T12:00:00Z"
  }
}
```

The raw secret is in the **`token`** field, and it is shown exactly once —
neither this endpoint nor `GET /api/v1/tokens` will ever show it again. The
`info` object is a `TokenView`, the same shape each item of
`GET /api/v1/tokens` has. (`DELETE` answers 204 with no body.)

`role` is one of `READ`, `POST`, `ADMIN` (see [Token scopes](#token-scopes)).
`weblog` is optional; omit it for a token that may act on every weblog its
owning user can already reach. `expiresAt` is an optional ISO-8601 instant;
omitted, the token never expires on its own — revoke it instead
(`DELETE /api/v1/tokens/{id}`, Basic-auth only, same as list/issue).

`weblog` must name a weblog you are a member of (a site admin may name any
existing weblog); a foreign or unknown weblog is a 400 whose
`errors[0].field` is `weblog`. `expiresAt` must be in the future, or it is a
400 on `expiresAt`. `GET /api/v1/tokens` lists revoked tokens too, each with
its `revokedAt`.

`bin/pressroom-api auth login` wraps exactly this call (minting an `ADMIN`-role
token under the label `pressroom-api cli`, or the role `--role READ|POST|ADMIN`
names) and writes the URL and token to
`~/.pressroom/credentials` (`chmod 600`). Every other `pressroom-api` subcommand
reads that file, or `PRESSROOM_API_URL`/`PRESSROOM_API_TOKEN` if set, so it never
asks for a password again. It hands curl the `Authorization` header through a
pipe (`-H @<(...)`), never as an argument, so the token is not visible in `ps`.

## Token scopes

A token's scope is **a ceiling, never a grant.** It can only narrow what the
owning user could already do through the ordinary permission system
(`GlobalPermission`/`WeblogPermission`) — it can never let a token reach
something its owner cannot. Two independent things are checked on every
request: the token's `role`, and its `weblog` pin.

**Role** (`ApiToken.Role`):

| Role    | May do |
|---------|--------|
| `READ`  | GET only. |
| `POST`  | GET, plus writes (POST/PATCH/DELETE) on ordinary content endpoints — entries, media, categories, pages. Still only what the owner's weblog permission allows: a contributor's (`EDIT_DRAFT`) token writes entries as the entry editor lets them (drafts; a publish is submitted for review, see [Entries](#entries)) and reads what the editor shows them, the categories, tags and media, but changes none of those. |
| `ADMIN` | Everything `POST` can, plus every `/api/v1/admin/**` endpoint, the site-wide weblog list `GET /api/v1/weblogs`, one weblog's settings and theme (`GET/PATCH /api/v1/weblogs/{handle}`, and its business picker `GET /api/v1/weblogs/{handle}/businesses`; not the per-weblog content APIs) and the Maintenance actions. |

A non-write method (`GET`/`HEAD`) never needs more than `READ`. Anything else
needs `POST` or `ADMIN`; a `READ`-role token gets a 403
(`This token is not scoped for administration.` /
`This token is read-only.`) on a write, never a silent downgrade.

**Weblog pin.** A token minted with `--weblog myblog` (or
`"weblog": "myblog"` in the mint request) may only act on that one weblog.
Naming a *different* weblog's handle in the path answers **404, not 403** —
a 403 would confirm the other weblog exists. A token minted with no weblog
pin may act on every weblog its owning user can reach, subject to that
user's own `WeblogPermission` on each one.

A handful of routes name no weblog at all (`GET /api/v1/ping`,
`GET /api/v1/me`, `GET /api/v1/meta`, `GET /api/v1/me/profile`) and are exempt
from the weblog-pin check by design — a
weblog-scoped token must still be able to ask who it is. The two are not
otherwise equivalent: `GET /api/v1/ping` is the one route in the whole API
that needs **no credential at all** (`permitAll` in `SecurityConfig`, a bare
liveness check), while `GET /api/v1/me` still requires a valid bearer
token — it answers who *that* credential is, so an unauthenticated
call to it is a 401 like anywhere else. `GET /api/v1/me` also lists the
`weblogs` the token can act on, each with its `handle`, `name` and highest
`permission` (`ADMIN`, `POST` or `EDIT_DRAFT`), sorted by handle: the one
weblog a pinned token is limited to, or every weblog its user belongs to.
This is how a token that is not a site admin finds its weblogs, since listing
every weblog (`GET /api/v1/weblogs`) needs the `ADMIN` role; creating one
(`POST /api/v1/weblogs`) needs only a writing token (`POST` or `ADMIN`).
`GET /api/v1/me` also reports the account's `kind`: `PERSON` or `AGENT`. Every
other route
that resolves no weblog defaults to **deny**, not "unlimited because there
was nothing to check": `/api/v1/admin/**` and the site-wide weblog list
`GET /api/v1/weblogs` require `ADMIN` role via a separate, explicit
annotation on the controller (not the weblog-pin exemption above), creating
a weblog (`POST /api/v1/weblogs`) needs a writing token (`POST` or `ADMIN`)
held by a user the Create Weblog page lets create one, and the whole
`/v1/tokens` resource (mint/list/revoke) refuses a Bearer-authenticated
caller outright regardless of role — see [With the API](#with-the-api). A weblog-pinned
token, even an `ADMIN` one held by a site administrator, is therefore refused
on the routes that name no weblog — `/api/v1/admin/users`, `/admin/config`,
`/admin/revisions`, `/admin/traffic`, `/admin/businesses` and `/api/v1/weblogs`
(list and create) — with a 404 whose
detail says so (`This token is limited to the weblog '<handle>', and this
route is not part of one weblog.`). The account's own permission is checked
first, so on the site-admin-only ones a pinned token whose owner is not a
site admin gets the 403 any non-admin gets, not the 404. Site-wide work needs
a second token minted with no weblog. The per-weblog maintenance routes
(`/api/v1/admin/weblogs/{handle}/actions/...`) name their weblog, so a site
administrator's `ADMIN` token pinned to that weblog may run them there (and
gets 404 on any other weblog): the pin is a ceiling on weblogs, and these
routes are inside one.
A route that names a weblog the caller cannot see still answers `No such
weblog.`, whether or not that weblog exists.

## Making a request

Every call outside the token resource (mint, list, revoke) is
Bearer-authenticated:

```bash
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  http://localhost:8083/api/v1/weblogs/myblog/entries
```

`Content-Type: application/json` on every request carrying a JSON body
(everything except the multipart media upload — see [Media](#media)).

## Removing a value

A field left out of a body, or sent as JSON `null`, is **unchanged**. `null`
never removes anything. To remove a value, name its field in `clear` on an
update (`PATCH`):

```json
{"clear": ["featuredImageId", "geoLatitude", "geoLongitude"]}
```

The clearable fields, per resource:

- **Entry:** `summary`, `metaTitle`, `searchDescription`, `canonicalUrl`,
  `featuredImageId`, `ogImageId`, `jsonLdType`, `geoLatitude`, `geoLongitude`,
  `eventStart`, `eventEnd`, `eventLocation` and `strand` (takes the entry out of
  its strand). `geoLatitude` and `geoLongitude` clear together, as a pair.
- **Weblog:** `tagline`, `copyright`, `customDomain`, `icon`, `about`,
  `newsletterListUuid`, `businessId`, `frontPage`, `placeType`,
  `placeLocality`, `placeRegion`, `placeCountry`, `placeLat`, `placeLng` and
  `bookingUrl`. `placeLat` and `placeLng` clear together.
- **Page:** `metaTitle`, `searchDescription`, `canonicalUrl` and `ogImageId`.
- **Media:** `altText`, `focalX` and `focalY`. `focalX` and `focalY` clear together.
- **Business profile:** `websiteUrl`, `bookingUrl`, `telephone`, `email`,
  `logoUrl`, `sameAs`, `areaServed` and `description`; not `name` or
  `businessType`.

An empty `clear` changes nothing, and a repeated name or one with surrounding
spaces is tolerated. Naming a field both with a value and in `clear` is a 400,
except that a `null` value counts as absent (`{"about": null, "clear": ["about"]}`
clears). Four requests are refused with a 400, and nothing is changed:

- a name that is not clearable (the detail lists the ones that are);
- one half of a pair without the other (`geoLatitude and geoLongitude are
  cleared together.`);
- a field that is both set and cleared (`about is both set and cleared.`);
- `clear` on a create (`clear applies only to updates.`).

In the MCP endpoint `clear` is an argument of the `update` actions.

## Pagination

List endpoints (`GET .../entries`, `GET .../comments`, `GET .../audit/seo`,
`GET .../media`, `GET .../members`, `GET .../businesses`, `GET /api/v1/admin/users`,
`GET /api/v1/admin/businesses`, `GET /api/v1/weblogs`) accept
`?offset=` and `?limit=` (`GET .../media` only pages when asked; see
[Media](#media)). **Out-of-range values are a 400, never silently clamped or
wrapped**: `limit` below 1, or a negative `offset`, is rejected outright
before any query runs — a permissive endpoint would otherwise let
`limit=-2` reach the database layer's "no limit" sentinel and read the
entire table. A `limit` above the endpoint's cap (200, uniformly) is
silently capped rather than rejected — the one place this API *does*
clamp instead of refusing.

**Every paged list answers one shape**, `{items, offset, limit, hasMore}`:
`offset` is the offset asked for, `limit` the page size after the cap of 200,
and `hasMore` is true while items remain beyond this page. An unpaged list
(pages, categories, tags, tokens, redirects, revisions, directories) is a bare
array: its length is the total.

- `GET .../entries` returns an `EntryPage`: `{items, offset, limit, hasMore}`.
  `hasMore` is computed by fetching one row past the requested page — page
  until it is `false`, not until a short page arrives. `GET .../comments`
  returns the same envelope.
- `GET .../audit/seo` returns a `SeoAudit`: `{total, counts, entries, offset,
  limit, hasMore}`. `offset` is the requested offset, `limit` the page size
  after the cap of 200, and `hasMore` is true while entries remain beyond this
  page. `total` (and `counts`, the same per-gap-type tally, with
  every code the audit can report, zero or not) cover
  **every** gappy entry the search found, not just the current page, so an
  agent can read the shape of the problem without paging through it —
  `entries` alone is the `offset`/`limit`-sliced page. It keeps `entries`
  rather than `items` because it is a report with a tally.
- `GET /api/v1/admin/users` returns a `UserPage`, `GET /api/v1/weblogs` a
  `WeblogListPage` and `GET .../media` a `MediaPage`, each the same
  `{items, offset, limit, hasMore}`; `items` holds `UserView`, `WeblogView`
  and `MediaView` rows. For users and weblogs `hasMore` comes from one query
  of `limit + 1` rows. `GET .../media` without `limit` returns every file,
  with `limit` equal to the number of items and `hasMore` false.
- `GET .../members` returns a `MemberPage`: `{items, offset, limit, hasMore,
  total}`, `total` counting every member, not the page. See [Members](#members).
- `GET /api/v1/admin/businesses` returns a `BusinessPage` and `GET
  .../businesses` a `BusinessChoicePage`, both `{items, offset, limit, hasMore,
  total}`, `total` counting every business profile. See
  [Business profiles](#business-profiles).

> **Changed in 0.16.0: three lists answer the envelope, not a bare array.**
> `GET /api/v1/weblogs`, `GET /api/v1/admin/users` and `GET .../media` now
> return `{items, offset, limit, hasMore}`. A client that read the response
> as an array reads `items` instead, and may stop when `hasMore` is false
> rather than on a short page. The MCP `weblogs`, `users` and `media` `list`
> actions return the same envelope.

## Errors

Every failure a controller method can see is [RFC
9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.
**Four ordinary failures never reach a controller method at all, so they
are not:** `ApiExceptionHandler` is `@RestControllerAdvice(basePackages =
"com.wikantik.pressroom.ui.restapi")`, and package-scoped advice is
only ever consulted once a request has been dispatched to a handler method
in that package. A request to an unmapped path under `/api/...` (404), the
right path with the wrong HTTP verb (405), a `POST .../media` with no
multipart body (415), or a multipart body exceeding
`spring.servlet.multipart.max-request-size` (1GB; 413) never reaches a
handler method to dispatch to in the first place — Spring answers all four
itself, before `ApiExceptionHandler` is in the picture, and Boot's own
`/error` renders them as plain `application/json`, not
`application/problem+json`. The 413 case is `MaxUploadSizeExceededException`,
raised by `DispatcherServlet.checkMultipart` before handler mapping even
runs, same as the other three. Do not assume a client can branch on
`Content-Type: application/problem+json` to detect an API error uniformly;
check the status code, and expect these four to look different on the wire
from every other failure in this document.

```json
{
  "type": "https://pressroom.invalid/problems/not-found",
  "title": "Not found",
  "status": 404,
  "detail": "No such entry.",
  "instance": "/api/v1/weblogs/myblog/entries/does-not-exist",
  "code": "entry.notFound"
}
```

Every problem body carries a `code`, and so does every `errors` entry: a
stable, case-sensitive string of two dotted lowerCamel segments,
`<resource or field>.<condition>` (`entry.notFound`, `slug.inUse`). `type` is the coarse category;
`code` refines it, and several codes can share a type.

- **Branch on `code`, never on `detail`.** A code is never renamed or
  removed. New codes are added as the API learns to name more of its
  refusals precisely, so treat one you do not know by its `type` and status.
- **Every refusal has a code of its own.** The generic codes
  (`request.invalid`, `resource.notFound`, `resource.conflict`,
  `upstream.failure`) are the category fallbacks: no route answers with them
  today, and a client that meets one treats it by its `type` and status.
  `auth.notAuthenticated` and `auth.unauthorized` both mean 401, with the
  same meaning (the first is an older check nearer the route); a client treats
  them alike and keys on the status, not on which of the two it got.
- **A few `detail`s are not the API's own text** but the message of the step
  that refused (an image import). Their codes are `image.importFailed` and
  `media.importRefused`; such text is English whatever the caller asks for.
- **`detail` is plain text for a person, and may be localised.** Send
  `Accept-Language` and a problem whose text the server holds in that
  language reads in it; anything else reads in English. No translations
  ship yet, so today every `detail` is English. `title` is never localised.
  Over MCP a tool error is always English.
- A 400 or 409 about exactly one field carries that field error's `code` as
  its own. A 400 naming several fields has the code `request.invalidFields`,
  and each `errors` entry has its own. So an `errors[].code` inside that 400
  can be one the table lists as a 409 (`handle.inUse`, `customDomain.inUse`
  beside another bad field of a weblog create or settings change): the
  problem's status is the request's, the entry's code is the field's.
- The bodies Boot's own `/error` writes (the unmapped-path 404, the 405, and
  the multipart 413 and 415, described above) are not problem bodies and
  carry no `code`.

| Code | Status | Type | Meaning |
|------|--------|------|---------|
| `request.invalid` | 400 | `invalid-request` | The request is not valid. The category fallback for a 400; no route answers it today. |
| `request.invalidFields` | 400 | `invalid-request` | More than one field is invalid; each `errors` entry carries its own code. |
| `request.malformedBody` | 400 | `invalid-request` | The body could not be parsed as JSON (or was missing where one is required). |
| `parameter.notWholeNumber` | 400 | `invalid-request` | A query parameter or path variable that must be a whole number is not one. |
| `parameter.notNumber` | 400 | `invalid-request` | A query parameter or path variable that must be a number is not one. |
| `parameter.notBoolean` | 400 | `invalid-request` | A query parameter or path variable that must be `true` or `false` is not. |
| `parameter.invalidValue` | 400 | `invalid-request` | A query parameter or path variable will not convert to its type (a timestamp, say). |
| `paging.invalidRange` | 400 | `invalid-request` | `limit` is below 1 or `offset` is negative; `errors` names which. |
| `auth.unauthorized` | 401 | `unauthorized` | No usable credential: authenticate, or mint a token and send it as a bearer token. |
| `auth.forbidden` | 403 | `forbidden` | Authenticated, but this caller may not make this call. |
| `resource.notFound` | 404 | `not-found` | No such resource, or the caller may not see it. |
| `request.notAcceptable` | 406 | `not-acceptable` | The `Accept` header excludes JSON. |
| `resource.conflict` | 409 | `conflict` | The request conflicts with the resource's current state. |
| `entry.changed` | 409 | `entry-changed` | The entry's text changed after it was read (`currentContentHash`). |
| `page.changed` | 409 | `page-changed` | The page's text changed after it was read (`currentContentHash`). |
| `request.tooLarge` | 413 | `too-large` | The JSON request body is over the size limit. |
| `request.unsupportedMediaType` | 415 | `unsupported-media-type` | The body is not `application/json`. |
| `request.throttled` | 429 | `throttled` | Too many requests in the throttle window (see below); wait `Retry-After` seconds. |
| `server.internalError` | 500 | `internal-error` | An unexpected server error. |
| `upstream.failure` | 502 | `upstream-failure` | A service the request depends on failed; the work it asked for may be done. |
| `server.busy` | 503 | `busy` | Another save of the same weblog is in progress; retry after `Retry-After`. |
| `entry.notFound` | 404 | `not-found` | No entry has this id in the weblog (or the caller may not see it). |
| `page.notFound` | 404 | `not-found` | No live page has this id in the weblog. |
| `revision.notFound` | 404 | `not-found` | The entry or page has no revision with this id. |
| `weblog.notFound` | 404 | `not-found` | No weblog is addressed by this request. |
| `auth.notAuthenticated` | 401 | `unauthorized` | A write reached the controller with no signed-in user. The same meaning as `auth.unauthorized` (an older check, nearer the route, with its own sentence): treat the two alike. |
| `entry.trashed` | 409 | `conflict` | The entry is in the trash; restore it before editing or previewing it. |
| `entry.alreadyTrashed` | 409 | `conflict` | Trash was asked for an entry already in the trash. |
| `entry.notInTrash` | 409 | `conflict` | Restore was asked for an entry that is not in the trash. |
| `entry.deleteForeverNeedsTrash` | 409 | `conflict` | Delete-forever was asked for an entry that is not in the trash. |
| `entry.notChangeable` | 403 | `forbidden` | A member without publish rights (a contributor) asked to change an entry that is not their own draft or pending entry. |
| `entry.needsPublishRights` | 403 | `forbidden` | A member without publish rights asked to change `pinned` or `strand`, or to import an image (`featuredImageUrl`, `importExternalImages: true`); the field is named in `errors`. |
| `entry.needsSiteAdmin` | 403 | `forbidden` | Someone who is not a site administrator asked to change `pinnedToMain`; the field is named in `errors`. |
| `newsletter.notPublished` | 409 | `conflict` | Send-newsletter was asked for an entry that is not published. |
| `newsletter.alreadySent` | 409 | `conflict` | Send-newsletter was asked for an entry already sent; an entry is never sent twice. |
| `newsletter.noList` | 409 | `conflict` | The weblog has no `newsletterListUuid`; set one in its settings first. |
| `newsletter.notConfigured` | 409 | `conflict` | The site has no Listmonk API credentials. |
| `newsletter.sendFailed` | 502 | `upstream-failure` | Listmonk refused the campaign or could not be reached; nothing was stamped, so the send can be retried. |
| `newsletter.sentButNotRecorded` | 409 | `conflict` | The campaign was sent but the entry could not be marked; do not send again. |
| `page.trashed` | 409 | `conflict` | The page is in the trash; restore it before changing it. |
| `page.alreadyTrashed` | 409 | `conflict` | Trash was asked for a page already in the trash. |
| `page.notInTrash` | 409 | `conflict` | Restore was asked for a page that is not in the trash. |
| `page.deleteForeverNeedsTrash` | 409 | `conflict` | Delete-forever was asked for a page that is not in the trash. |
| `revision.tooMany` | 409 | `conflict` | The entry or page already has its 10 kept revisions; release one first. |
| `field.required` | 400 | `invalid-request` | A required field is missing or blank; `{0}` is its name. |
| `field.blank` | 400 | `invalid-request` | A field that may be omitted was sent blank. |
| `field.tooLong` | 400 | `invalid-request` | A value is longer than its column allows. |
| `field.updateOnly` | 400 | `invalid-request` | A field that acts on stored content (`clear`, `expectedContentHash`) was sent on a create. |
| `revisionNote.onCreate` | 400 | `invalid-request` | `revisionNote` was sent on a create. |
| `status.unknown` | 400 | `invalid-request` | `status` is not one of the known statuses. |
| `entry.trashStatusNotWritable` | 400 | `invalid-request` | An entry write set `status` to TRASHED. |
| `page.trashStatusNotWritable` | 400 | `invalid-request` | A page write set `status` to TRASHED. |
| `page.statusFilterInvalid` | 400 | `invalid-request` | The pages list `status` filter is not TRASHED. |
| `entry.expectedContentHashInvalid` | 400 | `invalid-request` | An entry's `expectedContentHash` is not a 64-digit hex hash. |
| `page.expectedContentHashInvalid` | 400 | `invalid-request` | A page's `expectedContentHash` is not a 64-digit hex hash. |
| `fields.unknown` | 400 | `invalid-request` | `fields` names one field the entry view lacks. |
| `fields.unknownMany` | 400 | `invalid-request` | `fields` names several fields the entry view lacks. |
| `edits.withText` | 400 | `invalid-request` | A write sent both `text` and `edits`. |
| `edits.tooMany` | 400 | `invalid-request` | More edits than one request applies. |
| `edits.empty` | 400 | `invalid-request` | An `edits` item is null. |
| `edits.noFind` | 400 | `invalid-request` | An `edits` item has no `find`. |
| `edits.noReplace` | 400 | `invalid-request` | An `edits` item has no `replace`. |
| `edits.findNotMatched` | 400 | `invalid-request` | An edit's `find` is not in the stored text. |
| `edits.ambiguous` | 400 | `invalid-request` | An edit's `find` matches more than once. |
| `edits.onCreate` | 400 | `invalid-request` | `edits` was sent on a create. |
| `edits.previewNeedsEntry` | 400 | `invalid-request` | `edits` was sent to preview without an entry or page id. |
| `anchor.immutable` | 400 | `invalid-request` | An update sent a different `anchor`. |
| `anchor.invalidForm` | 400 | `invalid-request` | `anchor` is not in the allowed form; `{0}` is the length limit. |
| `anchor.heldByTrashedEntry` | 409 | `conflict` | A trashed entry of the weblog holds the `anchor`. |
| `anchor.inUse` | 409 | `conflict` | Another entry of the weblog holds the `anchor`. |
| `pubTime.requiredForScheduled` | 400 | `invalid-request` | `status` SCHEDULED with no `pubTime`. |
| `pubTime.invalid` | 400 | `invalid-request` | `pubTime` does not parse. |
| `eventTime.invalid` | 400 | `invalid-request` | `eventStart` or `eventEnd` (`{0}`) is blank or does not parse. |
| `eventEnd.beforeStart` | 400 | `invalid-request` | `eventEnd` precedes `eventStart`. |
| `jsonLdType.unknown` | 400 | `invalid-request` | `jsonLdType` is not a known type. |
| `geo.outOfRange` | 400 | `invalid-request` | `geoLatitude` or `geoLongitude` is off the globe. |
| `strand.idNotStrandPage` | 400 | `invalid-request` | An entry's `strand` is not the id of one of the blog's strand pages. |
| `pinned.tooMany` | 400 | `invalid-request` | The weblog already has its most pinned posts. |
| `category.unknown` | 400 | `invalid-request` | `category` names no category of the weblog. |
| `image.notPublic` | 400 | `invalid-request` | An image id field does not name a public image of the weblog. |
| `canonicalUrl.notAbsolute` | 400 | `invalid-request` | `canonicalUrl` is not an absolute http(s) URL. |
| `clear.notClearable` | 400 | `invalid-request` | `clear` names something that cannot be cleared. |
| `clear.togetherOnly` | 400 | `invalid-request` | `clear` names only one of a pair that clears together. |
| `clear.setAndCleared` | 400 | `invalid-request` | A field is both sent and named in `clear`. |
| `upload.disabled` | 400 | `invalid-request` | An image import was asked for on a site that takes no uploads. |
| `image.altWithoutUrl` | 400 | `invalid-request` | `featuredImageAlt` was sent without `featuredImageUrl`. |
| `image.urlAndId` | 400 | `invalid-request` | Both `featuredImageUrl` and `featuredImageId` were sent. |
| `image.urlAndClear` | 400 | `invalid-request` | `featuredImageUrl` was sent with `clear: [featuredImageId]`. |
| `image.scanRefused` | 400 | `invalid-request` | The text holds an image the import cannot take (an HTML `srcset`); `detail` says which, quoting the tag. |
| `image.tooManyBodyImages` | 400 | `invalid-request` | More external images in the text than one write imports. |
| `image.directoryPrivate` | 400 | `invalid-request` | An import was asked for while the default media directory is private. |
| `image.importBudgetExhausted` | 400 | `invalid-request` | The import step ran out of time before this image. |
| `image.importFailed` | 400 | `invalid-request` | One image could not be imported; `detail` names it (`{0}`) and carries the import step's own reason (`{1}`, English). |
| `strand.entriesRequired` | 400 | `invalid-request` | `entries` was omitted from a parts replacement. |
| `strand.flagNotSet` | 400 | `invalid-request` | Parts were set on a page whose `strand` flag is not set; set `strand` true on it first. |
| `strand.tooMany` | 400 | `invalid-request` | More parts than a strand holds. |
| `strand.postInAnotherStrand` | 400 | `invalid-request` | A listed post belongs to another strand. |
| `strand.postListedTwice` | 400 | `invalid-request` | A post id is repeated in `entries`. |
| `strand.postUnknown` | 400 | `invalid-request` | A listed post id is not a post of the weblog. |
| `strand.sitePage` | 400 | `invalid-request` | `strand` was set on a site page. |
| `strand.hasParts` | 400 | `invalid-request` | `strand` false on a strand that still has parts. |
| `navOrder.negative` | 400 | `invalid-request` | `navOrder` is below 0. |
| `slug.reserved` | 400 | `invalid-request` | `slug` is blank or a reserved name. |
| `slug.hasSlash` | 400 | `invalid-request` | `slug` contains a `/`. |
| `slug.heldByTrashedPage` | 409 | `conflict` | A trashed page holds the `slug`. |
| `slug.inUse` | 409 | `conflict` | Another page holds the `slug`. |
| `parameter.aliasConflict` | 400 | `invalid-request` | A deprecated parameter alias (`{0}`) was sent with a different value than its current name (`{1}`). |
| `auth.passwordNotAcceptedHere` | 401 | `unauthorized` | A password (HTTP Basic) was sent anywhere but the token resource, which is the only place one is accepted. |
| `auth.passwordRejected` | 401 | `unauthorized` | The token resource turned the password away: wrong, a disabled account, an agent or an unknown user, told alike. |
| `csrf.invalid` | 403 | `forbidden` | A write made with the admin UI's session (no `Authorization` header) carried no CSRF token, or the wrong one. Token callers never meet it. |
| `auth.noPermission` | 403 | `forbidden` | The caller lacks the role this route needs (on the site, or on a weblog it is a member of). |
| `auth.apiNotAllowed` | 403 | `forbidden` | The account (a reader signed in with SSO) holds no login permission, so it may not use the API. |
| `token.notAdminScoped` | 403 | `forbidden` | An administration route was called with a token whose role is not ADMIN. |
| `token.readOnly` | 403 | `forbidden` | A write was sent with a READ token. |
| `token.weblogScopedRoute` | 404 | `not-found` | A token limited to one weblog called a route that belongs to no weblog. |
| `token.mintRefused` | 403 | `forbidden` | A token was used to mint a token; minting takes a password. |
| `token.managementNeedsPassword` | 403 | `forbidden` | A token was used to list or revoke tokens; that takes a password. |
| `token.notFound` | 404 | `not-found` | The caller has no token with this id. |
| `token.weblogNotMember` | 400 | `invalid-request` | A token's `weblog` names a weblog the caller is not a member of. |
| `token.expiryNotInFuture` | 400 | `invalid-request` | A token's `expiresAt` is not in the future. |
| `token.roleUnknown` | 400 | `invalid-request` | A token's `role` is not READ, POST or ADMIN. |
| `mcp.tokenRequired` | 401 | `unauthorized` | The MCP endpoint was called with anything but an API token. |
| `mcp.actionMissing` | 400 | `invalid-request` | MCP only: a tool with several actions was called without `action`. |
| `mcp.actionUnknown` | 400 | `invalid-request` | MCP only: `action` names no action of the tool. |
| `mcp.argumentNotApplicable` | 400 | `invalid-request` | MCP only: an argument the tool or action does not take. |
| `mcp.argumentMissing` | 400 | `invalid-request` | MCP only: a required argument is missing. |
| `mcp.argumentNotObject` | 400 | `invalid-request` | MCP only: an argument that must be a JSON object is not one. |
| `mcp.argumentNotPathSegment` | 400 | `invalid-request` | MCP only: a value that becomes one path segment is empty, a dot segment, or holds `/`, `\` or `;`. |
| `account.adminManagedInAdminUi` | 403 | `forbidden` | The API may not change a site administrator's account. |
| `account.agentManagedInAdminUi` | 403 | `forbidden` | The API may not change an agent account. |
| `setting.managedInAdminUi` | 403 | `forbidden` | A runtime setting the API reads but never writes (`site.absoluteurl`). |
| `user.notFound` | 404 | `not-found` | No user has this user name. |
| `user.nameInUse` | 409 | `conflict` | `userName` is taken (by an account of any status). |
| `user.nameHasDisallowedCharacters` | 400 | `invalid-request` | `userName` holds a character the site does not allow in user names. |
| `user.emailRequired` | 400 | `invalid-request` | A user create has no `emailAddress`, or one that is not an address. |
| `user.emailInvalid` | 400 | `invalid-request` | A user update's `emailAddress` is not an address. |
| `user.emailUiOnly` | 403 | `forbidden` | A user update changes `emailAddress`; another user's email address is changed in the admin UI only. |
| `user.mailNotConfigured` | 400 | `invalid-request` | A user create on a site with no mail set up: the set-password link could not be sent. |
| `user.passwordLinkNotSent` | 502 | `upstream-failure` | The account was created, disabled, but its set-password mail failed. |
| `user.notAPerson` | 400 | `invalid-request` | A set-password link was asked for an agent account, which has no password. |
| `user.ssoBound` | 400 | `invalid-request` | A set-password link was asked for a single-sign-on account, which signs in through its provider. |
| `user.writerSsoOnly` | 400 | `invalid-request` | `writer` was sent for an account that is not single-sign-on: only those have an editable author role. |
| `user.passwordLinkFailed` | 502 | `upstream-failure` | The set-password link could not be mailed (an existing account; nothing changed). |
| `config.propertiesRequired` | 400 | `invalid-request` | A settings PATCH named no setting. |
| `config.propertyNameRequired` | 400 | `invalid-request` | A settings PATCH named a setting with a blank name. |
| `config.propertyUnknown` | 400 | `invalid-request` | A setting that is not runtime-settable. |
| `config.valueNull` | 400 | `invalid-request` | A setting was sent as null. |
| `config.outOfRange` | 400 | `invalid-request` | An integer setting outside the range it allows. |
| `config.notBoolean` | 400 | `invalid-request` | A boolean setting that is not `true` or `false`. |
| `config.notInteger` | 400 | `invalid-request` | An integer setting that is not an integer. |
| `config.notNumber` | 400 | `invalid-request` | A number setting that is not a number. |
| `config.frontDoorSiteSpace` | 400 | `invalid-request` | `site.frontpage.weblog.handle` names the site space, which holds the site's pages and is no blog. |
| `config.frontDoorPageUnpublished` | 400 | `invalid-request` | `site.frontpage.page` changed to a slug that is not a published site page. |
| `config.frontDoorBothSet` | 400 | `invalid-request` | Both front-door settings would be non-empty after the patch. |
| `config.privacyPageUnpublished` | 400 | `invalid-request` | `site.privacy.page` changed to a reference that is not a published page. |
| `field.notFinite` | 400 | `invalid-request` | A number field is not finite. |
| `field.notUuid` | 400 | `invalid-request` | A field that holds a UUID does not. |
| `media.fileRequired` | 400 | `invalid-request` | A media upload carried no file. |
| `media.importRefused` | 400 | `invalid-request` | A media import could not fetch or store the image; `detail` says why. Text from the import step. |
| `media.replaceRefused` | 400 | `invalid-request` | A replacement file failed the upload checks (size, quota, type, uploads switched off); `detail` says which. |
| `media.notFound` | 404 | `not-found` | No media file of the weblog has this id. |
| `media.nameInUse` | 409 | `conflict` | A media file's new `name` is held by another file in the directory it will be in. |
| `media.notCroppable` | 409 | `conflict` | A crop was asked of a file that is not a JPEG or PNG image. |
| `media.cropOutside` | 400 | `invalid-request` | The crop rectangle does not overlap the image. |
| `media.cropSizeInvalid` | 400 | `invalid-request` | A crop's `width` or `height` is below 1. |
| `media.cropStale` | 409 | `conflict` | The `expectedWidth`/`expectedHeight` sent is not the image's size now. |
| `directory.nameInvalid` | 400 | `invalid-request` | A directory `name` contains a slash (one leading slash is dropped). |
| `directory.notFound` | 404 | `not-found` | No media directory of the weblog has this id. |
| `directory.nameReserved` | 400 | `invalid-request` | A directory may not be named `default`. |
| `directory.nameInUse` | 409 | `conflict` | The weblog already has a directory with this `name`. |
| `directory.isDefault` | 409 | `conflict` | The default directory cannot be deleted. |
| `directory.notEmpty` | 409 | `conflict` | The directory still holds files, so it is not deleted. |
| `focal.outOfRange` | 400 | `invalid-request` | `focalX` or `focalY` is outside 0 to 1. |
| `focal.pairOnly` | 400 | `invalid-request` | One focal coordinate was sent for a file with no focal point. |
| `category.notFound` | 404 | `not-found` | No category of the weblog has this id. |
| `category.nameInUse` | 409 | `conflict` | The weblog already has a category with this `name`. |
| `category.imageInvalid` | 400 | `invalid-request` | A category `image` is not an absolute `http` or `https` URL. |
| `category.nameInvalid` | 400 | `invalid-request` | A category `name` contains a character HTML escaping would change (`&`, `<`, `>`, `"`). |
| `category.lastOne` | 409 | `conflict` | The weblog's last category cannot be deleted. |
| `category.moveToSelf` | 400 | `invalid-request` | A category delete's `moveTo` names the category itself. |
| `category.moveTargetNotFound` | 404 | `not-found` | A category delete's `moveTo` names no category of the weblog. |
| `category.hasEntries` | 409 | `conflict` | The category has entries and no `moveTo` was given. |
| `comment.notFound` | 404 | `not-found` | No comment on the weblog's entries has this id. |
| `comment.statusNotSettable` | 400 | `invalid-request` | A comment's `status` may be set only to PUBLISHED or HIDDEN. |
| `comment.statusUnknown` | 400 | `invalid-request` | A comment `status` that is not a status. |
| `comment.deletedByAuthor` | 409 | `conflict` | A comment its author deleted cannot be published. |
| `comment.replyNotSignedIn` | 400 | `invalid-request` | The reply was not stored: the caller has no account (`detail` ends in `NOT_SIGNED_IN`). |
| `comment.replyNoPermission` | 400 | `invalid-request` | The reply was not stored: the account may not comment (`detail` ends in `NO_PERMISSION`). |
| `comment.replyAccountDisabled` | 400 | `invalid-request` | The reply was not stored: the account is disabled or no longer exists (`detail` ends in `DISABLED`). |
| `comment.replyParentUnknown` | 400 | `invalid-request` | The reply was not stored: the comment replied to is unknown (`detail` ends in `NOT_FOUND`). |
| `comment.replyEntryClosed` | 400 | `invalid-request` | The reply was not stored: the entry is closed to comments (`detail` ends in `CLOSED`). The reply route does not refuse for this today (replies keep existing comments moderatable); it is coded so that it can. |
| `comment.replyCommentsOff` | 400 | `invalid-request` | The reply was not stored: the weblog has comments off (`detail` ends in `COMMENTS_OFF`). The reply route does not refuse for this today (replies keep existing comments moderatable); it is coded so that it can. |
| `comment.replyCustomDomain` | 400 | `invalid-request` | The reply was not stored: the weblog has a custom domain (`detail` ends in `CUSTOM_DOMAIN`). The reply route does not refuse for this today (replies keep existing comments moderatable); it is coded so that it can. |
| `comment.replyEmptyBody` | 400 | `invalid-request` | The reply was not stored: the body is empty (`detail` ends in `EMPTY_BODY`). |
| `comment.replyBodyTooLong` | 400 | `invalid-request` | The reply was not stored: the body is over 4000 code points (`detail` ends in `BODY_TOO_LONG`). |
| `comment.replyNotAccepted` | 400 | `invalid-request` | The reply was not stored: not a member's reply to a published, unanswered top-level comment, or the store refused it (`detail` ends in `BAD_REPLY`). |
| `comment.replyRateLimited` | 429 | `throttled` | The reply was not stored: the account has posted its share for now (`detail` ends in `RATE_LIMITED`). Unlike `request.throttled` it carries no `Retry-After`: the comment limiter does not know how long the wait is. |
| `redirect.notFound` | 404 | `not-found` | No redirect of the weblog has this id. |
| `redirect.pathNotRelative` | 400 | `invalid-request` | A redirect's `source` or `target` (`{0}`) is not a path starting with one `/`. |
| `redirect.pathHasQuery` | 400 | `invalid-request` | A redirect path carries a query string. |
| `redirect.pathHasBackslash` | 400 | `invalid-request` | A redirect path contains a backslash. |
| `redirect.pathHasControlCharacters` | 400 | `invalid-request` | A redirect path contains a control character. |
| `redirect.targetIsSource` | 400 | `invalid-request` | A redirect's `target` is its `source`. |
| `redirect.sourceInUse` | 409 | `conflict` | The weblog already redirects this `source`. |
| `redirect.sourceIsTarget` | 400 | `invalid-request` | The `source` is another redirect's target: redirects are one hop. |
| `redirect.targetRedirected` | 400 | `invalid-request` | The `target` is itself redirected: redirects are one hop. |
| `redirect.weblogRequired` | 400 | `invalid-request` | The rule names no weblog. The API always sets one, so no route answers it today. |
| `redirect.pathRequired` | 400 | `invalid-request` | A redirect path is blank. The API refuses a blank `source` or `target` first (`field.required`), so no route answers it today. |
| `audit.trashedNotCovered` | 400 | `invalid-request` | The SEO audit was asked for trashed entries. |
| `traffic.periodUnknown` | 400 | `invalid-request` | `period` is not one of the traffic periods. |
| `theme.customNotAllowed` | 400 | `invalid-request` | `theme` is `custom`, which only the admin UI sets. |
| `theme.notOffered` | 400 | `invalid-request` | `theme` is not one of the themes offered to this weblog. |
| `weblog.creationDisabled` | 403 | `forbidden` | The site does not let users create weblogs. |
| `weblog.oneBlogLimit` | 403 | `forbidden` | The caller already has a weblog, and the site allows one. |
| `weblog.emailRequired` | 400 | `invalid-request` | An agent created a weblog with no `emailAddress` on a site with no address to stand in. |
| `locale.invalid` | 400 | `invalid-request` | `locale` is not one the settings form offers. |
| `timeZone.invalid` | 400 | `invalid-request` | `timeZone` is not a time zone id. |
| `handle.invalid` | 400 | `invalid-request` | `handle` uses a character the site does not allow, or is too long. |
| `handle.reserved` | 400 | `invalid-request` | `handle` is a path the site reserves for its own pages. |
| `handle.inUse` | 409 | `conflict` | Another weblog (of any status) has this `handle`. |
| `handle.heldBySitePage` | 409 | `conflict` | A site page (live or in the trash) is served at this `handle`. |
| `entryDisplayCount.outOfRange` | 400 | `invalid-request` | `entryDisplayCount` is below 1 or above the site's limit. |
| `entryOrder.invalid` | 400 | `invalid-request` | `entryOrder` is not NEWEST or OLDEST. |
| `pageWidth.invalid` | 400 | `invalid-request` | `pageWidth` is not STANDARD or WIDE. |
| `business.unknown` | 400 | `invalid-request` | `businessId` names no business profile. |
| `business.notFound` | 404 | `not-found` | No business profile has this id. |
| `business.inUse` | 409 | `conflict` | A business profile some weblogs use cannot be deleted; `detail` says how many. |
| `businessType.unknown` | 400 | `invalid-request` | `businessType` is not `Organization`, `LocalBusiness` or `ProfessionalService`. |
| `field.notPublicUrl` | 400 | `invalid-request` | A business link (`websiteUrl`, `logoUrl`) is not a public, absolute http(s) URL. |
| `telephone.invalid` | 400 | `invalid-request` | A business's `telephone` is off the telephone pattern. |
| `email.invalid` | 400 | `invalid-request` | A business's `email` is not a valid address. |
| `sameAs.tooMany` | 400 | `invalid-request` | A business has more than 10 `sameAs` links. |
| `sameAs.entryInvalid` | 400 | `invalid-request` | A `sameAs` link is not a public http(s) URL of at most 255 characters. |
| `frontPage.invalid` | 400 | `invalid-request` | `frontPage` is not a published page of this weblog. |
| `placeType.invalid` | 400 | `invalid-request` | `placeType` is not LodgingBusiness. |
| `placeLocality.required` | 400 | `invalid-request` | A `placeType` was set with no `placeLocality`. |
| `placeCountry.invalid` | 400 | `invalid-request` | `placeCountry` is not a two-letter country code. |
| `placeLat.invalid` | 400 | `invalid-request` | `placeLat` is off the globe, or was given without `placeLng`. |
| `placeLng.invalid` | 400 | `invalid-request` | `placeLng` is off the globe, or was given without `placeLat`. |
| `bookingUrl.invalid` | 400 | `invalid-request` | `bookingUrl` is not a public http(s) URL, on a weblog or a business. |
| `customDomain.malformed` | 400 | `invalid-request` | `customDomain` is not a hostname. |
| `customDomain.siteHost` | 400 | `invalid-request` | `customDomain` is the site's own host. |
| `customDomain.inUse` | 409 | `conflict` | Another weblog has this `customDomain`. |
| `customDomain.uncheckable` | 400 | `invalid-request` | Whether `customDomain` is taken could not be checked; retry. |
| `auth.sessionOnly` | 403 | `forbidden` | A session-only route called with anything but an interactive signed-in session (a token, a password, or no session). |
| `template.notFound` | 404 | `not-found` | No template has this id in the weblog. |
| `template.nameInUse` | 409 | `conflict` | Another template of the weblog already has this name or link. |
| `template.required` | 409 | `conflict` | A template a custom theme requires cannot be removed. |
| `template.actionUnavailable` | 400 | `invalid-request` | `action` is not one the weblog may add now; the detail lists the ones it may. |
| `stylesheet.notFound` | 404 | `not-found` | The weblog holds no stylesheet of its own, or its theme has none. |
| `stylesheet.customTheme` | 409 | `conflict` | The action applies only to a weblog on a shared theme. |
| `theme.customisationDisabled` | 403 | `forbidden` | Custom themes are switched off on the site, or the weblog may not take one. |
| `member.notFound` | 404 | `not-found` | The user holds no role on the weblog. |
| `member.userUnknown` | 400 | `invalid-request` | `userName` names no enabled account. |
| `member.readerAccount` | 400 | `invalid-request` | `userName` is a reader account, which cannot hold a role. |
| `member.levelInvalid` | 400 | `invalid-request` | `permission` is not ADMIN, POST or EDIT_DRAFT. |
| `member.groupBloggingOff` | 409 | `conflict` | Group blogging is off: nobody new may be granted a role (changes and removals still work). |
| `member.selfChange` | 409 | `conflict` | A member asked to lower or remove their own role. |
| `member.lastAdmin` | 409 | `conflict` | The change would leave the weblog with no admin. |
| `submission.notFound` | 404 | `not-found` | No contact-form message has this id in the weblog. |
| `agent.notFound` | 404 | `not-found` | No agent account has this user name (a person's account is not one). |
| `password.tooShort` | 400 | `invalid-request` | A password is shorter than the minimum length (`{0}`, at present 8). |
| `profile.currentPasswordWrong` | 400 | `invalid-request` | The `currentPassword` sent to change one is not the signed-in account's. |
| `user.ssoAdmin` | 400 | `invalid-request` | A single-sign-on account must never hold the administrator role. |
| `user.cantDisableSelf` | 409 | `conflict` | An administrator cannot disable their own account. |
| `user.ownPasswordUseProfile` | 409 | `conflict` | `password` was sent for the caller's own account on `PATCH /v1/admin/users/{userName}`; use `PUT /v1/me/password`, which asks for the current password. |
| `user.cantChangeOwnRole` | 409 | `conflict` | An administrator cannot remove their own administrator role. |
| `userWeblogs.weblogUnknown` | 400 | `invalid-request` | `handle` names no existing weblog (the site space is never one). |
| `http.error` | the reply's (4xx) | `about:blank` | MCP only: a REST reply with no problem body of its own; `title` is the status phrase. |

A 400 or 409 about one field carries `errors: [{"field", "message"}]`, where
`field` is the property or query parameter name as the caller sent it (for
example `geoLatitude`, or `status` on a list filter). An entry create or
update lists every bad field at once, and `detail` joins those messages with
one space; a 400 that is not about one field (unparseable JSON, a missing
body) carries no `errors`, so treat the array as optional. A weblog create or
settings PATCH reports one `errors` entry per violated rule, and a single
conflict (a taken handle or custom domain) is a 409 carrying `errors` too. So
is every taken name: a category or directory `name`, a page `slug`, a user's
`userName`, and an entry's `anchor`.

```json
{
  "type": "https://pressroom.invalid/problems/invalid-request",
  "title": "Invalid request",
  "status": 400,
  "detail": "geoLatitude must be between -90 and 90. Unknown category 'Travle'; it is one of: Food, Travel.",
  "instance": "/api/v1/weblogs/myblog/entries",
  "errors": [
    {"field": "geoLatitude", "message": "geoLatitude must be between -90 and 90.", "code": "geo.outOfRange"},
    {"field": "category", "message": "Unknown category 'Travle'; it is one of: Food, Travel.", "code": "category.unknown"}
  ],
  "code": "request.invalidFields"
}
```

| Status | Meaning |
|--------|---------|
| 400 | Malformed request: bad JSON, an out-of-range `limit`/`offset`, an unknown status/role, a missing required field. |
| 401 | No credential, or a Bearer token that is missing, malformed, unknown, expired or revoked (`detail` `Authentication required.`); a wrong password on `/api/v1/tokens` (`detail` `The username or password was not accepted.`); a call to `/api/v1/tokens` with no credential at all (`detail` `Authentication required.`; every 401 on the token resource, this one included, carries `WWW-Authenticate: Basic realm="Pressroom"`); or a password sent to any other route (`detail` `Password authentication is accepted only on /api/v1/tokens. ...`, see [With the API](#with-the-api)). |
| 403 | Authenticated, but the token's own scope refuses this call (wrong role, or an admin-only route with a non-`ADMIN` token), or the caller is a member of the weblog below the role the call needs (with a token that may act on that weblog: one pinned to another weblog gets 404 there, whatever its owner's role). Also the entry write's own refusals of a member without publish rights: `entry.notChangeable` (not their own DRAFT or PENDING entry) and `entry.needsPublishRights` (a field only a publisher may set; see [Entries](#entries)). |
| 404 | The resource does not exist, **or the caller may not see it.** A weblog outside a token's pin, a weblog the caller holds no role on, a foreign entry/category/page/media id, and a genuinely-missing id are all 404 — never 403, because a 403 there would confirm the resource exists under someone else's weblog. Also what an unmapped path under `/api/...` answers — see the note above on why that one is plain `application/json`, not a problem body. |
| 405 | The path exists but not for this HTTP verb (e.g. `DELETE` on a collection endpoint that only supports `GET`/`POST`). Plain `application/json` from Boot's own `/error`, not a problem body — see the note above. |
| 409 | The request conflicts with the resource's current state: trashing an already-trashed entry, restoring one that isn't trashed, PATCHing a trashed entry (restore it first), a duplicate category/directory/page-slug name, deleting a weblog's last category, publishing a comment its author deleted, an `anchor` another entry of the weblog holds (a trashed one included), an entry update whose `expectedContentHash` is not the stored text's (`entry-changed`, see [Changing part of the text](#changing-part-of-the-text)), a redirect whose source another rule holds (`redirect.sourceInUse`), a media file `name` another file of the directory holds (`media.nameInUse`), a crop of a file that is not a JPEG or PNG (`media.notCroppable`) or of a size the file no longer has (`media.cropStale`), deleting a business profile some weblog uses (`business.inUse`). |
| 413 | A JSON request body that declares a length over 4,048,576 bytes (room for a 1,000,000-character entry in 3-byte characters plus 1 MiB for the other fields; `/api/mcp` has the same limit): a problem body whose `type` ends in `/too-large`, sent before anything reads the body. A body that declares no length (chunked) is read up to the same cap and then refused with the same 413 `request.tooLarge` (before 0.16.3, a 400 `request.malformedBody`). Uploads are not held to it. A multipart request body larger than `spring.servlet.multipart.max-request-size` (1GB) is also a 413, but plain `application/json` from Boot's own `/error`, not a problem body — see the note above. Distinct from the per-file media-quota refusal below, which is never a whole-request 413. |
| 406 | An `Accept` the API cannot answer (`text/html`, say). A problem body (`type` ending in `/not-acceptable`), written as `application/problem+json` regardless. |
| 415 | A JSON route sent a body in another media type (`application/x-www-form-urlencoded`, `text/plain`): a problem body whose `type` ends in `/unsupported-media-type`. `POST .../media` without a `multipart/form-data` body is also 415, but that one is plain `application/json`, not a problem body — see the note above. |
| 429 | Throttled: the request throttle (`request.throttled`, with `Retry-After`; see below), or a comment reply over the account's comment rate limit (`comment.replyRateLimited`, with no `Retry-After`). |
| 503 | Busy: the request collided with other work in progress and should be retried. Today only an entry save that claims an anchor: a create (REST or MCP) that waited longer than `weblogentry.anchor.lockTimeoutSeconds` (default 10) for another save of the same weblog to finish claiming its anchor. Clients should retry. The problem's `type` ends in `/busy`, its `detail` is `Another save of this weblog is in progress; retry shortly.`, and the REST response carries `Retry-After` set to that timeout in seconds. Nothing was saved, so retrying the same request is safe. No other failure is a 503. |
| 500 | Unexpected server error. The response body never carries exception detail (message, stack trace, class name) — those are logged server-side only. |
| 502 | An upstream dependency this endpoint depends on failed after the request itself already succeeded — `POST /api/v1/admin/users` when the account was created but its set-password email could not be sent (the account survives and is resendable, so this is not retried as a fresh create), and `POST .../users/{userName}/password-link` when its mail could not be sent. |

A media upload that would exceed a quota is not a whole-request 413: it is a
per-file `"quota_exceeded"` `status` string inside that file's own
`results[]` entry (see [Media](#media)). Check `results[].status`, not the
status code. The only 413 is the framework-level one in the table above.

A query parameter that will not convert to its type (`?limit=abc`) is a 400
naming it in `errors`, with a detail such as `limit has an invalid value; it
must be a whole number.`

## The throttle

Every request to `/api/**` is rate-limited: **120 requests per 60-second
window** by default (`api.throttle.threshold` / `api.throttle.interval` in
`pressroom.properties`; the on/off switch, `api.throttle.enabled`, is a runtime
property changeable from Admin Settings without a restart). The key is the
token's own SHA-256 digest for a request carrying `Authorization: Bearer
...`, or the caller's IP address for any request that does not — in
practice that means Basic-authenticated calls to `/v1/tokens`, the only
route that accepts a password, so throttling is per-credential once you hold
a token, per-source-address before that. A throttled request gets a
429 `application/problem+json` body identical in shape to every other error
here, built and written outside the normal Spring MVC dispatch path (the
throttle check runs in a servlet filter, ahead of authentication) so a
throttled client sees the same error contract as everywhere else, not a
bare connection reset or an HTML error page. The throttle's 429 carries
`Retry-After`, in seconds: the throttle interval (60 by default). A window
opens at the caller's first request and lasts the interval, so waiting that
long always clears it.

## Site facts and your profile

> **New in 0.16.0: `GET /api/v1/meta` and `GET /api/v1/me/profile`.**

Two read-only routes beside `GET /api/v1/me`. Both need a valid bearer token
of any role, pinned or not (a call without one is a 401), and neither names a
weblog.

```
GET /api/v1/meta
```

The facts a client needs before it draws a screen. They are the same for every
caller, so fetch them once per session; nothing is cached on the server, so a
setting an operator changes shows in the next response.

| Member | Meaning |
|---|---|
| `version` | The Pressroom version, as the page footers show it; `null` when unknown. |
| `siteUrl` | The site's absolute URL without a trailing slash (the Global Config value, else the `PRESSROOM_SITE_ABSOLUTEURL` environment setting); `null` when neither is set. |
| `features.customThemes` | Weblog owners may use custom templates and stylesheets. |
| `features.groupBlogging` | A weblog may have more than one member. |
| `features.userWeblogCreation` | Any user, not only a site admin, may create a weblog. |
| `features.uploads` | Media uploads are switched on. |
| `features.readerSignIn` | Readers can sign in (single sign-on is configured). |
| `features.analytics` | An analytics service is configured; `false` is what makes a traffic read answer `NOT_CONFIGURED`. |
| `limits.maxBodyBytes` | The largest JSON request body, in bytes (see Errors, 413). |
| `limits.maxListLimit` | The largest page a list answers (200); a bigger `limit` is capped to it. |
| `limits.entryTextMaxChars` | The most characters an entry's text may hold. |
| `trashRetentionDays` | Days a trashed entry is kept before the sweep deletes it for good: negative keeps it until someone deletes it, 0 deletes it on the next sweep. Read afresh on every request. |
| `pageTrashRetentionDays` | The same for a trashed page (the page trash has its own setting). |

```
GET /api/v1/me/profile
```

The caller's own account, read-only: `userName`, `screenName`, `fullName`,
`emailAddress`, `locale`, `timeZone` and `kind` (`PERSON` or `AGENT`). It never
holds a password, a hash or a token. Changing any of it is done in the admin
UI, not through the API. The MCP tools do not offer it (a person's contact
details are nothing an agent acts on; `whoami` names the account); `site_info`
calls `GET /api/v1/meta`.

```
GET /api/v1/me/weblogs
```

The main menu's rows: `weblogs`, one per weblog the caller can act on (a pinned token sees only its
own), sorted by handle, each with `handle`, `name`, `about` (when set), `theme`, `absoluteUrl`, the strongest
`permission`, `actions` (the stored permission actions: `admin`, `post`, `edit_draft`; a weblog's creator holds
`admin` alone), `canModerate`, `entryCount` and `pendingComments` (0 unless the caller may moderate); and, for
a site admin with an unpinned token, `siteSpace`, the handle of the hidden weblog that holds the site's own
pages (created on the first read). It needs only a valid caller. The MCP tools do not offer it (`whoami`
lists the weblogs an agent can act on). A weblog read (`GET /api/v1/weblogs/{handle}`) and the list also
carry `absoluteUrl`, the address readers reach the weblog at.

## Entries

```
GET    /api/v1/weblogs/{handle}/entries                 list (filterable, paged)
GET    /api/v1/weblogs/{handle}/entries/{id}             get one
GET    /api/v1/weblogs/{handle}/entries/by-anchor/{anchor}   get one, by its URL slug
POST   /api/v1/weblogs/{handle}/entries                  create
PATCH  /api/v1/weblogs/{handle}/entries/{id}              partial update
DELETE /api/v1/weblogs/{handle}/entries/{id}              trash (not delete)
POST   /api/v1/weblogs/{handle}/entries/{id}/restore      restore (always to DRAFT)
POST   /api/v1/weblogs/{handle}/entries/{id}/delete-forever   permanent delete
POST   /api/v1/weblogs/{handle}/entries/{id}/duplicate    copy into a new draft (publishers)
POST   /api/v1/weblogs/{handle}/entries/{id}/send-newsletter   mail the entry to the weblog's list (publishers)
POST   /api/v1/weblogs/{handle}/entries/{id}/preview       preview unsaved text
POST   /api/v1/weblogs/{handle}/entries/preview            preview text for a new entry
GET    /api/v1/weblogs/{handle}/entries/{id}/revisions     list revisions, newest first
GET    /api/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}   one revision, with a diff
POST   /api/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/restore   put a revision back
```

Reads need `READ`; writes need a `POST` or `ADMIN` token. On the weblog the
writes ask what the entry editor asks. A member with publish rights (`POST` or
`ADMIN` on the weblog) writes as described below. A contributor (`EDIT_DRAFT`)
writes drafts, as the editor lets them:

- create and update save what they send, except that `PUBLISHED` or
  `SCHEDULED` is saved as `PENDING`: submitted for review, the weblog's
  reviewers are told, and the response reads `PENDING`. A `DRAFT` with a
  future `pubTime` stays a draft;
- update, trash, and restoring or keeping a revision need an entry that is
  their own and still `DRAFT` or `PENDING`; any other is a 403
  `entry.notChangeable`;
- changing `pinned` or `strand` (joining, or `clear: ["strand"]`) is a 403
  `entry.needsPublishRights` naming the field; sending back the stored value
  changes nothing and passes;
- importing images is a publisher's, as adding media is: `featuredImageUrl`
  or `importExternalImages: true` is a 403 `entry.needsPublishRights` naming
  the field, and the import a text write otherwise gets by default does not
  run, so images from other sites stay linked;
- preview works against any entry of the weblog;
- restore from the trash and delete-forever need publish rights, as the Trash
  page does (403 `auth.noPermission`);
- besides entries, a contributor's token reads what the editor shows them:
  the weblog's categories and tags (`GET .../categories`, `GET .../tags`) and
  its media (`GET .../media`, `GET .../media/{id}`,
  `GET .../media/directories`, as the editor's image chooser lists them).
  Creating, changing or deleting a category or a media file stays a
  publisher's (403 `auth.noPermission`).

The weblog's reviewers are told once, when a write moves an entry into
`PENDING` (a create, or an update from another status). A later update of an
entry that is already `PENDING` tells them nothing new, whoever makes it.

> **Changed in 0.16.0: a contributor's token writes drafts.** Before, every
> entry write needed publish rights on the weblog, so a contributor's `POST`
> token got a 403 `auth.noPermission` on any of them. Now it writes as above.
> A `READ` token still writes nothing, whoever holds it, and nothing changes
> for a member with publish rights. A contributor's token, `READ` or `POST`,
> also reads the categories, tags and media (a 403 before).

> **Changed in 0.16.0: an update of a `PENDING` entry does not tell the
> reviewers again.** Earlier versions mailed them after every write that
> left the entry `PENDING`, so each `PATCH` of an entry waiting for review
> sent another notice. Now only the write that submits it does.

**`locale` and `pinnedToMain`.** `locale` is the entry's locale (for example `en_US`, at most 20 characters;
a new entry without one has its weblog's, and a blank one on update is the same). `pinnedToMain` pins the entry to
the site's main page and is a site administrator's, as in the editor: for anyone else a write that would change it is
a 403 `entry.needsSiteAdmin` naming the field, and sending the stored value back changes nothing and passes. Neither
is clearable. The view carries both, and `newsletterSentAt` (read only; absent until the entry has been sent).

**Duplicate** (`POST .../entries/{id}/duplicate`, publishers only, as the entries page's Duplicate action) copies
an entry into a new `DRAFT` titled "Copy of ..." with a fresh id and no slug of its own, and answers 201 with the
new entry and its `Location`. The copy keeps the text, summary, category, tags, images, search fields and
location; it has no publication time, pin, strand or newsletter stamp, and the original is unchanged. A trashed
entry is a 409 `entry.trashed` (restore is how it comes back).

**Send newsletter** (`POST .../entries/{id}/send-newsletter`, publishers only, as the editor's "Send as
newsletter") emails the entry to the weblog's Listmonk list, synchronously, then stamps `newsletterSentAt` and answers the
entry. It mails every subscriber and cannot be undone, so each precondition is a refusal before anything is sent: the
entry is not `PUBLISHED` (409 `newsletter.notPublished`), has been sent already (409 `newsletter.alreadySent`),
the weblog has no `newsletterListUuid` (409 `newsletter.noList`), or the site has no Listmonk API credentials (409
`newsletter.notConfigured`). A send Listmonk refuses is a 502 `newsletter.sendFailed` with nothing stamped, so a retry
is safe; one that went but could not be recorded is a 409 `newsletter.sentButNotRecorded`, whose `detail` says not
to send again.

**Create:**

```bash
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:8083/api/v1/weblogs/myblog/entries \
  -d '{
        "title": "Hello, automation",
        "text": "Written by a script.",
        "status": "PUBLISHED",
        "category": "General",
        "tags": ["automation", "test"]
      }'
```

**Entry titles are plain text, in and out.** Send `Tom & Jerry`, not
`Tom &amp; Jerry`, and expect `Tom & Jerry` back, in every view that carries
an entry's title (the entry, its revisions, the SEO audit, a comment's
`entryTitle`, traffic posts, a strand's parts). Pressroom stores the title
escaped and does the escaping once, on write, so a `GET` followed by a `PATCH`
of the same `title` changes nothing. **Changed in 0.15.0:** earlier versions
answered the stored, escaped form (`Tom &amp; Jerry`); a client that
unescaped those titles itself should stop, or it will unescape a literal
`&amp;` an author typed.

`title` and `text` are required on create (they are `NOT NULL` columns; an
entry with neither is not a resource this API will create). `text` is at
most 1,000,000 characters, on create and on update alike (what `edits` make
of the stored text included); over that it is a 400 on `text`. `status`
defaults to whatever a brand-new `WeblogEntry` defaults to (`DRAFT`) when
omitted; publishing with no `pubTime` supplied means "now", not an error.
`category` names a category **by name**, not id — the same shape
`EntryView.category` reads back — and an unknown name is a 400. `pubTime` is
a wall-clock string in **the weblog's own timezone**, e.g. `2026-03-01T09:30`
(see [docs/dev/entries.md, "Entry editing"](../dev/entries.md#entry-editing)
for why this is the weblog's zone specifically and not the caller's). **`EntryView` reads every
time back as an instant** (`2026-03-01T08:30:00Z` for 09:30 in Berlin in
winter), and a write accepts that form too: a value ending in `Z` or carrying
an offset (`2026-03-01T09:30+01:00`) names its own moment whatever the
weblog's zone, so a GET followed by a PATCH of the same `pubTime` changes
nothing.

**What `text` may hold.** Write Markdown. Besides CommonMark, Pressroom renders:
- GitHub tables, `~~strikethrough~~` and bare URLs as links.
- GitHub alerts as callouts the theme styles:

  ```markdown
  > [!NOTE]
  > Useful information a skimming reader should still see.
  ```

  The five types are `NOTE`, `TIP`, `IMPORTANT`, `WARNING` and `CAUTION`.
- Fenced code that names its language (```` ```java ````), which readers see
  highlighted, labelled, line-numbered and with a copy button. A block that
  names no language gets no highlighting.
- Headings with an `id` made from their text (`## Storage tier` becomes
  `#storage-tier`, and a repeat becomes `#storage-tier-1`), so a section can
  be linked to.

Do not set a text color or background in an inline `style`. Weblog themes
differ, some are dark, and a color picked for a light page can leave text
unreadable on a dark one. Use an alert for a callout, and let the theme
color everything else; preview warns about inline colors.

Place this weblog's images with `[image id=...]` rather than a hand-written
`<img>`: the shortcode serves a size that fits the reader's screen, where a
plain `<img>` makes every reader download the full-size file; preview warns
about a plain one.

Pictures work as on Blogger. A save that leaves an entry with no featured
image makes the first of this weblog's pictures in the text its featured
image (an `[image id=...]`, or an `<img>` or Markdown image of a
`.../mediaresource/<id>` URL; never one in code). A featured image you set
wins, and an update that sends `clear: ["featuredImageId"]` is left with
none. A theme that prints the featured image above the post (portfolio,
travel) leaves it out when the text already shows that picture, so it is
never seen twice: put the picture in the text where you want it.

HTML still works, mixed with Markdown: HTML blocks pass through Markdown
untouched, and bare paragraphs between them are wrapped in `<p>`.
An HTML block ends at a blank line, so a line indented four or more spaces
just after a blank line becomes a code block.
The sanitizer then removes scripts, event handlers, `<iframe>`/`<object>`
and dangerous CSS, and keeps headings, lists, tables, `<figure>`/
`<figcaption>`, images (from any host) and inline `style`, checked
declaration by declaration. A link's `target` survives only as `_blank`,
and the sanitizer then adds `rel="noopener noreferrer"`. Use preview (below)
to see exactly what will be published.

**Location.** For a post about one place, send `jsonLdType`
and the coordinates:

```json
{"title": "Torrey Pines", "text": "...", "status": "PUBLISHED",
 "jsonLdType": "TOURIST_ATTRACTION",
 "geoLatitude": 32.9228, "geoLongitude": -117.2534}
```

- `jsonLdType` chooses the structured-data block a permalink emits beside its
  `BlogPosting`. It is one of `BLOG_POSTING` (the default, which adds
  nothing), `TOURIST_ATTRACTION`, `TOURIST_TRIP`, `EVENT` and `FAQ_PAGE`,
  read case-insensitively. An unknown name is a 400.
- `geoLatitude` and `geoLongitude` are decimal degrees. A value that is not
  finite, or lies outside ±90 or ±180, is a 400.
- A `TOURIST_ATTRACTION` with both coordinates emits them as its `geo`. The
  coordinates also centre the entry's `[map]`.
- A `TOURIST_TRIP` takes its itinerary from the `[map]` pins, and a
  `FAQ_PAGE` takes its questions from the `[faq]` pairs, so both work through
  the API. An `EVENT` takes `eventStart`, `eventEnd` and `eventLocation`
  (see Events below).
- Omitting a field leaves it alone; to remove coordinates, send
  `"clear": ["geoLatitude", "geoLongitude"]` (see
  [Removing a value](#removing-a-value)). Setting `jsonLdType` to `BLOG_POSTING` stops them
  being emitted as `geo`.

All three fields come back on `EntryView` under the same names.

**Events.** An `EVENT` takes `eventStart`, `eventEnd` and `eventLocation`.
`eventStart` and `eventEnd` are wall-clock strings in **the weblog's own
timezone**, in the formats `pubTime` takes (`2026-08-02T19:30`; seconds are
accepted, and so is an instant or an offset such as `2026-08-02T17:30:00Z` or
`2026-08-02T19:30+02:00`; anything else is a 400 whose detail shows the format).
`eventEnd` before `eventStart` is a 400 (`eventEnd must not be before
eventStart.`). `EntryView` returns both as instants (`2026-08-02T17:30:00Z` for
19:30 in Berlin in summer), and the permalink's `Event` block carries the same
instant. All three can be removed with `clear`.

**`canonicalUrl`** overrides the permalink in `<link rel="canonical">`,
`og:url` and the JSON-LD `mainEntityOfPage`, so it must be an absolute
`http` or `https` URL; anything else (`javascript:`, `data:`, a relative
path) is a 400 on `canonicalUrl`, the rule the admin UI's editor applies.

**The four writable statuses are `DRAFT`, `PUBLISHED`, `PENDING` and
`SCHEDULED`. `TRASHED` is not among them — a PATCH or create body carrying
`"status": "TRASHED"` is a 400** (`Use DELETE to trash an entry and POST
.../restore to bring it back.`). Trashing and restoring have their own
endpoints because both do more than flip a column: trashing removes the
entry from the search index and bumps the weblog's cache-invalidation
timestamp so a cached home page stops serving a post whose permalink now
404s; restoring reverses only the removal, never the cache bump (nothing
needs re-adding to a live page).

**PATCH on an already-trashed entry is refused with 409**, not resurrected
by a side door — `{"status": "PUBLISHED"}` against a trashed entry's id does
not un-trash and publish it in one call. **Restore always lands the entry
on `DRAFT`, never back on `PUBLISHED`** — an undelete that silently
republishes to feeds, the sitemap and every subscriber is worse than one
extra click, and no column remembers the pre-trash status to restore
instead. Getting a trashed entry back onto the site is therefore always two
calls: `restore`, then a `PATCH` setting `status` to `PUBLISHED` yourself,
never one.

`GET .../entries` accepts `?status=`, `?category=`, `?tags=` (repeatable),
`?text=` (free-text search), `?locale=`, `?startDate=` and `?endDate=` (a day,
`yyyy-MM-dd`, in the server's time zone: from the start of `startDate` to the end of `endDate`, as the entries
page's date range; either may be sent alone, and a malformed one is a 400 `parameter.invalidValue` naming it),
`?sortBy=` (exactly `PUBLICATION_TIME` or `UPDATE_TIME`, newest first, as the entries page's sort select; without it the
publication time; anything else is a 400 `parameter.invalidValue` naming it), plus `offset`/`limit`.
`?fields=views30d` adds each item's page views over the last 30 days (the entries page's Views column): present only
when analytics is configured and the figure is known, a known 0 once the blog has a complete snapshot, absent
otherwise; the whole-entry view never carries it. An entry in the trash carries `trashedAt`. Naming no
`status` at all excludes the trash by construction (`?status=TRASHED` is the
only way to see it — see [docs/dev/entries.md, "Trash"](../dev/entries.md#trash-soft-delete-w5)
for why the whole API's trash-safety property lives in that one default).

**Preview** (`POST .../entries/{id}/preview` or `.../entries/preview` for an
entry that does not exist yet) renders `{"text": "..."}` through the real
pipeline — shortcode expansion, then Markdown, then sanitization — the exact
sequence a saved entry goes through, so what you preview cannot disagree
with what gets published. Nothing is persisted; this is the only way to see
`[gallery]`/`[map]`/etc. expand before committing to a save. The response
is `{"html": "...", "warnings": [...]}`. `warnings` lists what will render
badly, and is empty when nothing will: an element that sets a text color
or background inline, a code block that names no language, and the body
checks of the [SEO audit](#audits) (an `h1` in the body, a skipped heading
level, an image with no alt text, a link to a missing part of the entry or
to an unpublished entry, an image of the weblog placed with a plain
`<img>`), each as a sentence. Warnings are advice: the same text saves
without complaint. `POST .../entries/{id}/preview`
also takes `edits` in place of `text` (below): it renders what an update
with those edits would store, and saves nothing. On `.../entries/preview`,
with no entry to apply them to, `edits` is a 400.

### Smaller responses

**`fields`** (a repeatable query parameter; an array in MCP) names the
`EntryView` properties a response carries, on every route that returns
entries: get (by id or by anchor), list, create, update, restore and a
revision restore. `id`
always comes back, and a property with no value is left out, as it is from
the full view. A list keeps its envelope (`items`, `offset`, `limit`,
`hasMore`) and projects each item. Without `fields`, or with it empty, a
response is the whole entry, `text` included.

```bash
# every entry's slug and search description, for an SEO pass
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  'http://localhost:8083/api/v1/weblogs/myblog/entries?fields=anchor&fields=searchDescription&limit=200'
```

```json
{"items": [{"id": "a1b2c3", "anchor": "harbour-walk", "searchDescription": "A walk along the harbour."}],
 "offset": 0, "limit": 200, "hasMore": false}
```

A name `EntryView` does not have is a 400 on `fields` whose message lists the
valid names. On create and update it is reported with the write's other field
problems, and nothing is written.

### Changing part of the text

Every `EntryView` carries **`contentHash`**: the SHA-256 of the stored
`text` as UTF-8, in bare lower-case hex, the scheme Wikantik's
`read_page`/`update_page` use. It is absent only when the entry has no text.

**`edits`** on an update (`PATCH .../entries/{id}`) changes part of the
text without sending all of it: a list of `{"find", "replace"}` pairs,
applied to the stored text in order, each to the text as the previous ones
left it.

```json
{"edits": [{"find": "The pier at dawn.", "replace": "The pier at dusk."},
           {"find": "## Getting there\n\nTake the 101", "replace": "## Getting there\n\nTake the 5"}],
 "expectedContentHash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}
```

- `find` must be exact text of the entry (as `get` returns it, so Markdown,
  not rendered HTML), non-empty, and occur **exactly once**. An edit that
  does not is a 400 on `edits[i]`: `edits[1] not found in the entry's text.`
  or `edits[1] found 2 times; include more surrounding text so it matches
  once.` Every failing edit is reported, with the write's other field
  problems, and nothing is saved.
- `replace` may be `""`, which deletes what `find` matched; a missing
  `replace` is a 400.
- At most 50 edits per request. `edits` with `text` in the same request is a
  400, and so is `edits` on a create.
- The result is then treated exactly as though it had been sent as `text`:
  the same checks, the `importExternalImages` step (an image an edit adds is
  imported), and one revision.

**`expectedContentHash`** on an update is the `contentHash` the caller read.
When it is not the hash of the text now stored, someone changed the entry
since, and the update is a **409** whose `type` ends in `/entry-changed` and
whose `currentContentHash` (an RFC 9457 extension member) is the stored
text's hash. Nothing is saved, and nothing is fetched: the hash is checked
before any other validation and before the import step. Get the entry again
and redo the change. Either case of hex matches; a value that is not 64 hex
digits is a 400 on `expectedContentHash`, and on a create the field is a 400.
Without it an update overwrites whatever is stored, as before. It guards the
text only: an update that sends no `text` or `edits` still changes only the
fields it sends.

The check holds against concurrent writers too. It runs again immediately
before the save, under a lock on the entry's row held until the save
commits, so of two updates sent with the same `expectedContentHash` one
saves and the other is the 409 above, however long the import step took in
between. `edits` are held to the text they were applied to in the same way,
with or without a hash: if another save changed the text meanwhile, the
update is the same 409 rather than edits laid over text the caller never
saw. An entry deleted forever in between is a 404.

```json
{
  "type": "https://pressroom.invalid/problems/entry-changed",
  "title": "Entry changed",
  "status": 409,
  "detail": "The entry's text changed after it was read; get the entry again and redo the change.",
  "instance": "/api/v1/weblogs/myblog/entries/a1b2c3",
  "currentContentHash": "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752"
}
```

`edits` with `expectedContentHash` is the safe pair: the hash proves the
text is the one the caller read, and each exact-once match proves an edit
lands where it was meant.

### Slugs and images

`GET .../entries/by-anchor/{anchor}` returns the same body as `GET .../entries/{id}`
for the entry of this weblog holding that anchor (a trashed one included, as
by id). An unknown anchor, another weblog's anchor and an anchor you may not
see are the same 404 an unknown id gets. In MCP, `entries` `get` takes `id` or
`anchor` (`id` wins if both are given).

**`anchor`** sets the entry's URL slug. It is accepted on create only: a
PATCH may send the stored value back unchanged, and any other value is a 400
on `anchor`. Omit it and Pressroom generates one. The form is lower-case
letters and digits in words joined by one `-` or `_`, never starting or
ending with a separator, at most 255 characters
(`^[a-z0-9]+(?:[-_][a-z0-9]+)*$`); a bad form is a 400 on `anchor`, and a
chosen anchor is refused rather than normalised. An anchor another entry of
the weblog holds is a 409 on `anchor`. A trashed entry still holds its
anchor, so the message says `delete it forever (delete_forever) to free it`:
call `POST .../entries/{id}/delete-forever` on that entry, which frees the
slug. Trashing alone does not.

**`featuredImageUrl`** imports a public image from an http(s) URL, sets it as
the featured image, and takes `featuredImageAlt` as its alt text, all in the
entry write. It cannot be combined with `featuredImageId` (or with clearing
it), `featuredImageAlt` without a URL is a 400, and it needs uploads enabled
for the site. The import follows the same rules as `media/imports`, and a
refusal is a 400 on `featuredImageUrl`. A private default media directory
refuses every import, because readers could not see the images: a 400 on
`featuredImageUrl`, or on `text` when only body images are imported.

**`importExternalImages`** imports each image the text loads from a host
other than the site's own, and points the text at the local copy. It is on
by default: a create or update that sends `text` (or `edits`) and leaves the
field out imports as though it were `true`, so a post never depends on
another site keeping its pictures. Send `false` to keep linking to them. A
write that sends no text imports only when it says `true` (a status change
starts no fetches). Left out, the import is **best effort**: an image that
cannot be copied (refused, failed, out of budget, an external `srcset`, or
past the first 10) stays linked as it was, and the write saves; a site with
uploads switched off or a private default media directory saves the text as
sent. Asked for with `true`, it is all or nothing, as listed below. The first
copied picture becomes the featured image when the entry has none (above). Found
are `<img src>` tags in HTML (the first `src` wins, as in a
browser) and every Markdown image, reference-style ones included, never an
image inside code. A Markdown image that is a paragraph of its own (not in a
list, quote or link, and with no title) becomes `[image id="..." alt="..."]`,
a responsive figure; a line directly after it is moved past a blank line,
since the figure would otherwise take it in. Any other Markdown image stays
Markdown pointing at the copy, its alt text exactly as written (a
reference-style one becomes inline). Only the `src` value of an HTML tag is
rewritten. A PATCH without `text` scans and
rewrites the stored text. A body image's alt text (`<img alt>` or the Markdown
alt) fills the imported file's alt text when that is null. An external
`srcset` is refused, not imported, so it is a 400 on `text`. An image left as
a plain image is reported by the SEO checks as `image_not_responsive`: swap
each for the `[image id=...]` the warning names. Writing body images as
Markdown on lines of their own avoids that.

- At most 10 distinct external URLs per write (`MAX_BODY_IMAGES`); more is a
  400 on `text`.
- The whole import step has a 60-second budget. An image not started within
  it counts as not fetched.
- With `true`, all or nothing for the entry: if any image is refused or not fetched, the
  write is a 400 naming each such image (on `text`, or on `featuredImageUrl` for
  the featured one), and the entry is not saved. Images that did import stay in
  the library, and a retry matches
  them by content hash, so it costs downloads, not copies.

**`featuredImageId` and `ogImageId`** must name an image of this weblog
outside any private directory, or the write is a 400 on that field. A value
sent back exactly as it was read is not re-checked, so a file deleted since
does not break a round-trip.

### Revisions

Every save that changes an entry's title, text or summary keeps what it
replaced as a **revision**: the web editor's saves and restores, bulk
actions, and API and MCP updates alike. A revision's `creator` is the user
who made that save (the signed-in editor, or the token's user), and `created`
is when. A save that changes none of the three leaves no revision.

**`revisionNote`** on an update says why the change is made, at most 255
characters (longer is a 400 on `revisionNote`). It labels the revision that
update leaves, the one holding the content it replaced, and the editor's
revision list shows it. An update that leaves no revision drops its note. A
blank note is no note, and on a create `revisionNote` is a 400: a create
replaces nothing.

```json
{"edits": [{"find": "The pier at dawn.", "replace": "The pier at dusk."}],
 "revisionNote": "The photos were taken at dusk"}
```

**`GET .../entries/{id}/revisions`** lists the entry's revisions, newest
first, as a bare array: `id`, `created`, `creator`, `note` (absent when there
is none) and `title` (plain text, like an entry's). No text, so a long
history stays a short answer. The first is what undoing the latest save
would bring back.

**`GET .../entries/{id}/revisions/{revisionId}`** returns one revision's
`title`, `summary` and `text` with `created`, `creator` and `note`, plus
`diff`: a unified diff from this revision's text to the entry's current
text, three lines of context, `+` lines being only in the current text.
`?against={otherRevisionId}` diffs against that revision of the same entry
instead. `diff` is empty when the two texts are the same. Lines alike at the
start and end are matched first; a changed region between them of more than
a million line pairs (1,000 changed lines against 1,000, say) is one hunk
that removes all of it and adds all of the other, rather than a finer diff.

```
--- revision 6b1f...
+++ current
@@ -1,3 +1,3 @@
 The harbour at
-dawn.
+dusk.
 Take the 101.
```

**`POST .../entries/{id}/revisions/{revisionId}/restore`** puts the
revision's title, summary and text back, through the same save as the web
editor's Restore: what it replaces becomes a revision itself, credited to the
token's user, so a restore is undone by restoring that one. It returns the
entry and takes `fields`. Its optional body `{"expectedContentHash": "..."}`
guards it as on an update: a hash that is not the stored text's is the 409
`entry-changed`, and nothing is saved. A trashed entry's revision is a 409,
as an update is (restore the entry from the trash first).

A revision is reachable only through its own entry: a revision id of another
entry (in this weblog or another), an unknown id, and an `against` naming
either are the same 404 an unknown entry gets. Reading revisions needs
`READ`, as reading the entry does; restoring needs `POST`.

## Categories and tags

```
GET    /api/v1/weblogs/{handle}/categories
POST   /api/v1/weblogs/{handle}/categories
PATCH  /api/v1/weblogs/{handle}/categories/{id}
DELETE /api/v1/weblogs/{handle}/categories/{id}?moveTo={id}
GET    /api/v1/weblogs/{handle}/tags
```

Reading the categories and tags needs a `READ` token and any role on the
weblog: a contributor (`EDIT_DRAFT`) reads them, as the entry editor shows
them. Creating, changing and deleting a category needs a `POST` or `ADMIN`
token and publish rights on the weblog, as the Categories page does (category
structure is blog-wide, not a single draft's business).

A category has an `image`, an icon the Categories page's form takes: an absolute `http` or `https` URL (at most 255
characters), read in every category view and written on create and update. A blank `image` on update removes it; one
that is not an absolute `http(s)` URL is a 400 on `image`, code `category.imageInvalid`, and a too-long one a 400
`field.tooLong`.

Deleting the weblog's last remaining category is a 409 — a weblog with zero
categories can never save an entry again.
Deleting a category that has entries is also a 409 *unless* `moveTo` names
another category to move them into first (and `moveTo` naming the category
being deleted is itself a 400). `GET .../tags` has no pagination — a
per-weblog tag cloud has never been large enough to need it.

> **Changed in 0.16.0: a category name must survive HTML escaping.**
> `POST .../categories` and `PATCH .../categories/{id}` refuse a `name`
> containing `&`, `<`, `>` or `"` (the characters HTML escaping would change)
> with a 400 on `name`, code `category.nameInvalid`, as the admin pages always
> have; earlier versions stored them. A PATCH that sends a category's current
> name again is never refused for this, so a category created before the rule
> (with an `&` in it) can still be edited. An apostrophe was never refused and
> still is not.

## Media

```
GET    /api/v1/weblogs/{handle}/media[?directoryId=&q=&type=&largerThan=&tags=&sort=&offset=&limit=]
GET    /api/v1/weblogs/{handle}/media/{id}
POST   /api/v1/weblogs/{handle}/media           multipart upload, one or more files
POST   /api/v1/weblogs/{handle}/media/imports   import an image from a URL
PATCH  /api/v1/weblogs/{handle}/media/{id}
POST   /api/v1/weblogs/{handle}/media/{id}/crop   crop an image (cannot be undone)
PUT    /api/v1/weblogs/{handle}/media/{id}/content   replace a file's bytes (multipart `file`)
DELETE /api/v1/weblogs/{handle}/media/{id}
GET    /api/v1/weblogs/{handle}/media/directories
POST   /api/v1/weblogs/{handle}/media/directories
PATCH  /api/v1/weblogs/{handle}/media/directories/{id}   set the private flag
DELETE /api/v1/weblogs/{handle}/media/directories/{id}   delete an empty directory
```

The three `GET`s need a `READ` token and any role on the weblog: a
contributor (`EDIT_DRAFT`) reads the files and directories, private ones
included, as the entry editor's image chooser lists them. Every other route
here needs a `POST` or `ADMIN` token and publish rights on the weblog, as the
Media Files pages do.

> **Changed in 0.16.0: a contributor reads media, categories and tags.** The
> three media `GET`s, `GET .../categories` and `GET .../tags` answered a
> member without publish rights 403 `auth.noPermission`; now they answer as
> for any member. The writes are unchanged.

> **Changed in 0.16.0: media names are judged more strictly.**
> `POST .../media/directories` refuses a `name` with a slash anywhere but the
> first character (one leading slash is dropped, as before): 400 on `name`, code
> `directory.nameInvalid`; it used to create the directory with the slash in its
> name. `PATCH .../media/{id}` answers 409 on `name`, code `media.nameInUse`,
> when the new `name` is held by another file in the directory the file will be
> in (the `directoryId` sent, else its current one), and changes nothing; it used
> to store two files of one name. A move alone (`directoryId` with no `name`)
> is judged on the name the file keeps: a directory that already holds a file
> of that name answers the same 409, and the file is neither changed nor moved.

`GET .../media` returns a `MediaPage` (`{items, offset, limit, hasMore}`) of
`MediaView`s sorted by name (or by `sort`, below). `q` keeps the files whose
name starts with that text and `type` the files of one type (`image`, `video`,
`audio` or `other`), exactly as the Media Files screen's search does: across the
weblog, or within the directory when `directoryId` is also given. `largerThan`
keeps the files strictly larger than that many bytes (negative is a 400
`parameter.invalidValue`) and `tags` those carrying any of the named tags
(repeat it, or separate with commas), so a page of the answer holds the matches. `sort` is
`name` (the default, ignoring case), `date_uploaded` (newest first) or `type`
(content type), each then by name; an unknown `sort` or `type` is a 400
`parameter.invalidValue` naming it. With `directoryId`, only that directory's files
(`dir` is its old name and still works; sending both with different values is
a 400). With `offset`/`limit`, a page of the sorted list (limit capped at 200);
**without either, every file**, as before, which on a large photo library is a
long response, blurhashes included, so page it. Without `limit`, `limit` in the
answer is the item count and `hasMore` is false.

**Upload is a batch, and a batch is not a transaction.** Send one or more
`file` parts (`multipart/form-data`), optionally `directoryId` to file them
somewhere other than the weblog's default directory:

```bash
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  -X POST http://localhost:8083/api/v1/weblogs/myblog/media \
  -F 'file=@photo1.jpg' -F 'file=@photo2.jpg'
```

The response is a `results` array, one entry per file, each with its own
`status` (`"created"`, `"quota_exceeded"`, `"forbidden_extension"`, or
`"error"`) and, only for a created file, its `MediaView`. **The HTTP status
is 201 only when every file in the batch landed; the moment any one file
fails, the whole response is 207 (Multi-Status)** — check the status code
before assuming success, and check `results[].status` (not just the
presence of a 2xx) to see which files actually made it, because 207 is
still a 2xx.

**Import by URL.** `POST .../media/imports` with `{"url": "...", "name": "...",
"directoryId": "...", "altText": "..."}` (only `url` is required) fetches one
image and stores it exactly as an upload would, answering 201 with the
`MediaView` and a `Location` header. Use its `id` as an entry's
`featuredImageId`. The type is decided by the bytes, not the URL or the
server's `Content-Type`: JPEG, PNG, GIF and WebP are accepted, SVG is refused,
and the stored extension follows the real type. Only public hosts are fetched,
on ports 80 and 443, following at most 3 redirects (each re-checked), within 30
seconds overall. The site's per-file size limit and its uploads-enabled switch
apply as they do for uploads. A refused URL is a 400 on `url` whose `detail` says why.
Bytes already in the target directory (matched by content hash) answer **200** with that file, with
no copy and no quota charge. If the existing file has no alt text, it takes the
request's `altText`; an existing `""` (decorative) is kept.
In the MCP endpoint this is `media` with `action: "import"`.

`MediaView.directory` is the owning directory's **id**, not its name —
deliberately the opposite of `EntryView.category`, which is a name. The read
side matches the corresponding write side in each case: `EntryWrite.category`
is a name (so round-tripping an `EntryView` back into a write needs no
lookup), while `MediaPatch.directoryId` is an id (so round-tripping a
`MediaView` back into a patch likewise needs no lookup). Do not assume the
two fields share a shape because they occupy the same conceptual slot.

`PATCH` updates `altText` (an explicit empty string `""` *is* a real value —
"decorative image", set on purpose — distinct from omitting the field
entirely), `focalX`/`focalY` (each a fraction of the image from 0 to 1,
anything else a 400; a file has both or neither, so one coordinate moves an
existing focal point and is a 400 on a file that has none), `name` (present-but-blank is rejected, not
stored — unlike `altText`, there is no legitimate "deliberately unnamed"
file), and `directoryId` (moves the file; a foreign or unknown directory id
is a 404, checked before anything else in the request is applied),
`description` (at most 255 characters), `copyrightText` (at most 1023) and
`tags` (the file's whole tag set, replaced; each tag at most 30 characters,
split on spaces and commas as the edit form's are; `[]` removes them all).
`description` and `copyrightText` are removed with `clear`. A limit broken is a
400 `field.tooLong` on that field. `MediaView` carries all three (`tags`
sorted, never null).

**Directories.** `PATCH .../media/directories/{id}` with `{"isPrivate": true}`
(or `false`) hides the directory from every public surface (the base media
path, inline galleries, the sitemap's images) while the weblog's members still
see it; `isPrivate` is required, and the call states the value rather than
flipping it, so a retry is harmless. `DELETE .../media/directories/{id}`
answers 204 and removes the directory, but only an empty one that is not the
default: 409 `directory.isDefault` or 409 `directory.notEmpty`, as the screen
offers Delete folder only there. A directory of another weblog, or an unknown
id, is a 404 `directory.notFound`. In the MCP endpoint these are `media` with
`action: "update_directory"` and `"delete_directory"`.

### Replacing a file's content

`PUT .../media/{id}/content` with a multipart `file` part replaces the bytes of an
existing file, as the media edit screen's Replace file does. The file keeps its
name, folder, description and tags; its content type follows the new bytes (or
the name when the client sends none), and the thumbnail, renditions, dimensions
and EXIF are rebuilt from them. The upload's checks run first (uploads switched
on, the per-file size, the weblog's quota with the replaced file's own bytes
left out, forbidden types): a refusal is a 400 `media.replaceRefused` whose
`detail` says which, and nothing changes. A missing or empty part is a 400
`media.fileRequired` on `file`. Not an MCP tool (a call carries JSON): import the
new image by URL.

### Cropping an image

`POST .../media/{id}/crop` crops an image to a rectangle, as the media editor's
crop does. **It cannot be undone:** the stored original is re-encoded to the
rectangle and every thumbnail and rendition is rebuilt, so the pixels outside
it are gone. Rendered pages expire at once, so they show the new sizes.

```json
{"x": 10, "y": 20, "width": 800, "height": 600, "expectedWidth": 1600, "expectedHeight": 1200}
```

`x`, `y`, `width` and `height` are required whole pixels of the image as it
is displayed (rotated as its EXIF orientation says, as a browser shows it);
`width` and `height` are at least 1. A rectangle that overshoots the image is
trimmed to it. **Send `expectedWidth` and `expectedHeight` from `media get`**:
the crop runs only if the file is still that size, so a retried or stale
request cannot crop a file twice (409 `media.cropStale`, whose `detail` gives
the current size). The answer is the file's `MediaView` after the crop: new
`width`, `height`, `length`, `blurhash`, and a focal point moved to keep
pointing at the same pixels.

Only JPEG and PNG files can be cropped; a GIF, a WebP or anything else is a 409
`media.notCroppable` naming its type, and nothing changes. A rectangle that
does not overlap the image is a 400 `media.cropOutside` naming the rectangle
and the image's size, a missing field a 400 `field.required` on each, and a
size below 1 a 400 `media.cropSizeInvalid`. A failure to store the result is a
500. In the MCP endpoint this is `media` with `action: "crop"`.

## Pages

```
GET    /api/v1/weblogs/{handle}/pages
GET    /api/v1/weblogs/{handle}/pages/{id}
POST   /api/v1/weblogs/{handle}/pages
PATCH  /api/v1/weblogs/{handle}/pages/{id}
DELETE /api/v1/weblogs/{handle}/pages/{id}                 move to the trash
POST   /api/v1/weblogs/{handle}/pages/{id}/restore         restore (always to DRAFT)
POST   /api/v1/weblogs/{handle}/pages/{id}/delete-forever  permanent delete
PUT    /api/v1/weblogs/{handle}/pages/{id}/parts
POST   /api/v1/weblogs/{handle}/pages/{id}/preview         render unsaved text, as the page would publish it
POST   /api/v1/weblogs/{handle}/pages/preview              the same for a page not saved yet
GET    /api/v1/weblogs/{handle}/pages?status=TRASHED       the page trash
GET    /api/v1/weblogs/{handle}/pages/{id}/revisions                       a page's revisions
GET    /api/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}          one revision, with a diff
POST   /api/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/restore  put a revision back
```

> **Changed in 0.13.0: `DELETE` on a page no longer deletes it permanently.**
> It moves the page to the trash, as `DELETE` on an entry does. To remove a
> page for good, trash it and then call `delete-forever`.

### Page preview

`POST .../pages/{id}/preview` with `{"text": "..."}` renders that Markdown as the
page will publish it (shortcodes, Markdown, then the sanitizer), on the page's
own slug, and saves nothing; `POST .../pages/preview` does the same for a page
that does not exist yet. The answer is `{html, warnings}` as an entry preview's
is, with the render warnings of an entry preview and none of its search checks.
`edits` in place of `text` renders what an update with those edits would store;
without an id there is no stored text, so `edits` is a 400
`edits.previewNeedsEntry`. A trashed page or another weblog's is a 404. It is
the Page editor's Preview, and in the MCP endpoint `pages` with
`action: "preview"`. It needs publish rights, as page writes do.

### Page trash

A trashed page is not served, is out of navigation, the sitemap and search,
and stops being the blog's front page. It keeps its slug, its `navOrder` and,
for a strand, its parts (their strand bar disappears until the page is back
and published). It is purged after `page.trash.retention.days` (a site
setting; default 7, -1 keeps it until deleted by hand).

| Request | On a page that is not trashed | On a trashed page |
|---|---|---|
| `GET .../pages` | listed | not listed |
| `GET .../pages?status=TRASHED` | not listed | listed, with `trashedAt`; any other `status` value is a 400 on `status` |
| `GET .../pages/{id}` | 200 | 404: read it from the trash list |
| `PATCH .../pages/{id}`, `PUT .../pages/{id}/parts` | as documented below | 409, `Page is in the trash; restore it first.` |
| `DELETE .../pages/{id}` | 204, trashed | 409, `Page is already in the trash.` |
| `POST .../pages/{id}/restore` | 409, `Page is not trashed.` | 200, the page as a `DRAFT` |
| `POST .../pages/{id}/delete-forever` | 409, `Page is not trashed; trash it first.` | 204 |

### Page revisions and the stale-write guard

Every update that changes a page's `title` or `text` keeps the version it
replaced as a revision; an update that changes only settings (slug, status,
navigation, SEO fields) leaves none. Each page keeps its newest
`page.revisions.retention` revisions (a site setting; default 50, -1 keeps
all, 0 records none).

- `GET .../pages/{id}/revisions` lists them newest first: `id`, `created`,
  `creator`, `note` and `title`, without the text.
- `GET .../pages/{id}/revisions/{revisionId}` adds `text` and `diff`, a
  unified diff from the revision to the page's current text;
  `?against={otherRevisionId}` diffs to another revision of the same page.
- `POST .../pages/{id}/revisions/{revisionId}/restore` puts the revision's
  title and text back and answers the page. What it replaces becomes a
  revision, so a restore can be undone. A revision holds no settings: slug,
  status and navigation are left as they are.
- A revision id that belongs to another page is a 404, `against` included.
  On a trashed page the two reads are a 404 and restore is a 409.

### Keeping a revision, and how many are stored

Revisions of posts and pages are bounded by five mechanisms, all site
settings (`GET`/`PATCH /api/v1/admin/config`):

| Setting | Default | What it does |
|---|---|---|
| `revisions.coalesce.minutes` | 10 | Repeated saves by the same user inside this window share one revision. A save with a `revisionNote`, a save by someone else, and a revision restore always leave their own. 0 turns it off. |
| `entry.revisions.retention` | -1 | Newest n revisions kept per post; -1 all, 0 records none. |
| `page.revisions.retention` | 50 | The same per page. |
| `revisions.maxAgeDays` | -1 | The daily sweep removes revisions older than this; -1 off. |
| `revisions.total.max` | -1 | The most revisions the site keeps, posts and pages together; over it, the sweep removes the oldest; -1 off. |

Two protections outrank the age limit and the site-wide limit: an item's
newest 5 revisions are never removed by either, and a **kept** revision is
exempt from every limit.

```
POST /api/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/keep
POST /api/v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/unkeep
POST /api/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/keep
POST /api/v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/unkeep
```

Each answers the revision's summary, which (like the revision view) carries
`kept`. One item may have at most 10 kept revisions; an eleventh is a 409.
Releasing a revision removes nothing by itself: the item's next save, or the
sweep, applies the limits to it. Changing a setting removes nothing either;
the sweep does, once a day.

A global admin can read the figures and run the sweep:
`GET /api/v1/admin/revisions` answers the totals for posts and pages, the
kept count, an approximate size, the oldest revision, the ten items with the
most, the last sweep, and `nextSweep`: what a sweep would remove now.
`POST /api/v1/admin/revisions/sweep` answers the same report shape
(`byCount`, `byAge`, `byCeiling`, `total`, `ceilingUnmeetable`,
`weblogsFailed`, `ceilingFailed`). **It is a dry run unless the body says
`{"dryRun": false}`**; with no body it removes nothing. Neither route is
offered over MCP.

Every page view carries `contentHash`, the SHA-256 of its `text`. Send it
back as `expectedContentHash` on `PATCH` or in a restore's optional body
(`{"expectedContentHash": "..."}`): if the page's text is no longer that,
the answer is a 409 of type `page-changed` whose `currentContentHash` is the
stored text's hash, and nothing is saved. It is optional; a write without it
is not checked. A value that is not 64 hex digits is a 400. `PATCH` also
accepts `revisionNote` (at most 255 characters), which labels the revision
that update leaves. Both fields are refused on create.

Restore (from the trash) always yields a `DRAFT` and never makes the page the front page
again: publish it, then set `frontPage` on the weblog. A create or rename
onto a slug that a trashed page holds is a 409 on `slug` that says so;
restore that page or delete it forever first. A body carrying
`"status": "TRASHED"` is a 400 on `status`. Deleting a strand forever leaves
its posts published, outside any strand. Another weblog's page id is a 404 on
every route.

`POST`-role required. A write's `status` is one of `DRAFT`/`PUBLISHED` only — pages
have no PENDING/SCHEDULED states and no scheduling story at all
(see [docs/dev/entries.md, "Pages"](../dev/entries.md#pages) for why pages are
a separate entity from entries); `TRASHED` is reached only through `DELETE`. A
create that names no `slug` takes it from the title (lower-cased words joined
by hyphens: "Start Here" becomes `start-here`). `slug`
is validated against the same reserved-word list (`ReservedSlugs`) the
renderer's own URL parser uses, and checked for a duplicate against the
weblog, before either ever reaches the database — both are 400/409 here
rather than an opaque 500. A page title is stored
raw and an entry title is stored HTML-escaped, but over this API **both are
plain text, in and out** (see "Entry titles are plain text" above).

A page carries the page editor's navigation and SEO fields too, in every
response (`list`, `get`, `create`, `update`; a field with no value is
omitted), and create and `PATCH` accept them:

| Field | Meaning |
|---|---|
| `showInNav` | List the page in the blog's navigation. |
| `navOrder` | Its position there, lowest first; pages with the same number sort by title. 0 or more: a negative number is a 400 on `navOrder`. The Pages screen renumbers a blog's pages 0..n-1 when an author reorders them. |
| `metaTitle` | Replaces the whole `<title>` and `og:title`. Trimmed; at most 255 characters. |
| `searchDescription` | The meta description search engines and share previews show. Trimmed; at most 255 characters. |
| `canonicalUrl` | An absolute `http`/`https` URL the page canonicalises to, by the validator the page and entry editors use; anything else is a 400 on `canonicalUrl`. |
| `noindex` | `true` asks search engines not to index the page. Not clearable. |
| `ogImageId` | The share image: the id of an image in this weblog's media, outside any private directory, the rule entry writes apply. Another weblog's media id, a non-image or an unknown id is a 400 on `ogImageId`; sending back the stored id is not re-checked. |

These follow the entry fields' rules, and as on an entry write a refused page
write lists every bad field in `errors` and changes nothing. A `PATCH` that
omits a field leaves it alone; `clear` removes `metaTitle`,
`searchDescription`, `canonicalUrl` or `ogImageId` (see [Removing a
value](#removing-a-value)), and `clear` on a create is a 400. A duplicate
slug is still a 409, reported once every field has passed.

### Strands

A strand is a page that carries an ordered series of its blog's posts: the
page's text frames the topic, and the posts are listed beneath it, in order.
Each post reader-visible in the series (published, and not dated in the
future) shows "Part N of M" linking to the strand page, with links to the
previous and next parts. A post is in at most one strand, and a strand's
parts are posts of its own blog.

Every page view carries `absoluteUrl` (where readers reach the page: the blog's
root for its front page, the site root for the front-door page) and `frontDoor`
(true for the site page served at the site root instead of at its slug), both
read-only, and `strand` (boolean) and `parts`, read-only: a strand's
members in order, drafts and the trash included, each
`{id, anchor, title, status, position}` (`title` is the entry's
plain-text title, as in the entry view; `position` orders the parts and need not
be contiguous). `parts` is empty for a page that is not a strand.

- **Make a page a strand** with `"strand": true` on create or `PATCH`.
  `"strand": false` on a strand that still has parts (any status, the trash
  included) is a 400 on `strand`, and nothing of that write is saved: take the
  parts out first. A site page cannot be a strand.
- **Set its parts** with `PUT /v1/weblogs/{handle}/pages/{id}/parts` and
  `{"entries": ["<id>", ...]}`, the post ids in reading order. It replaces the
  whole membership in one step (add, remove and reorder at once) and answers
  the page view; `{"entries": []}` removes every part. Each id that cannot be
  a part is its own `errors` entry on `entries`, naming it, and nothing
  changes: an id this blog has no post for (another blog's post reads the
  same), a post already in another strand (move it from the entry side), or
  an id listed twice. A page that is not a strand, or a body without
  `entries`, is a 400 on `entries`; another blog's page is a 404. At most
  500 ids; more is a 400 on `entries` ("At most 500 parts."), and nothing
  changes.
- **Put one post in a strand** with the entry's `strand`, a strand page's id,
  on entry create or `PATCH`: the post joins as the last part, and from
  another strand it moves. Sending the strand it is already in changes
  nothing. A plain page, another blog's page or an unknown id is a 400 on
  `strand`, and nothing of that write is saved. `"clear": ["strand"]` takes it
  out; `null` never does. The entry view carries `strand` while the entry is
  in one.

Changing either needs publish rights (`POST` role), as every page and entry
write does. Deleting a strand page keeps its posts and takes them out of it.
Over MCP: `pages update` with `strand`, `pages set_parts` with `entries`, and
`entries update` with `strand` or `clear`.

## Redirects

```
GET    /api/v1/weblogs/{handle}/redirects
POST   /api/v1/weblogs/{handle}/redirects
DELETE /api/v1/weblogs/{handle}/redirects/{id}
```

`POST`-role required. A redirect rule answers a weblog URL **that would
otherwise 404** with a 301 to live content — it can never shadow anything
that is actually being served, because the lookup only runs at points where
a 404 has already been decided (see
[docs/dev/routing.md, "Redirects"](../dev/routing.md#redirects-301s-for-urls-that-would-otherwise-404)). This is the only admin surface for rules: a site migration is
a script emitting exact rules through `POST`, and renaming a page's slug
mints its own `SLUG_HISTORY` rule with no API call at all.

A rule is `{"source": "/old-path", "target": "/new-path"}` — both
weblog-relative (no handle, no context path, no query string; the request's
query string is preserved on the 301 automatically). Off-site targets,
query strings in either path, duplicate sources, and anything that would
chain two rules together are refused as 400s (a duplicate source,
`redirect.sourceInUse`, as a 409) with the reason in the body, each with its
own `code` (`redirect.pathNotRelative`, `redirect.sourceInUse`, ...; see
[Errors](#errors)). There is no update verb: delete and recreate.

> **Changed in 0.16.0: a duplicate source is a 409, not a 400.**
> `redirect.sourceInUse` now answers 409 Conflict, like every other `*.inUse`
> code; its `type` is `/conflict`. The `detail` is unchanged.

> **Changed in 0.16.0: a failure to store a rule is a 500, not a 400.**
> Earlier versions answered any failure of the save with a 400 whose
> `detail` was the failure's own message, which for a database error could
> carry the database's text. Every refusal still is a 400 with its reason;
> anything else is the opaque 500 problem (`server.internalError`), with the
> cause logged on the server only.

The list echoes each rule's `hitCount` and `lastHitAt` — the way to see
whether a stale URL is still being asked for — and every *served* redirect
also logs one line to the `pressroom.redirects` logger with the requested URI,
referer, and user-agent for diagnosis.

## Comments

```
GET    /api/v1/weblogs/{handle}/comments[?status=&entryId=&limit=&offset=]
GET    /api/v1/weblogs/{handle}/comments/{id}
PATCH  /api/v1/weblogs/{handle}/comments/{id}
DELETE /api/v1/weblogs/{handle}/comments/{id}
POST   /api/v1/weblogs/{handle}/comments/{id}/replies
```

`POST`-role required: moderation is for the weblog's authors. These are the
comments written by signed-in readers. A comment of another weblog, or an id
that does not exist, answers 404. Moderation keeps working when comments are
switched off on the weblog, so existing comments stay listable and actionable.

`GET` lists newest first, as `{"items": [...], "offset", "limit", "hasMore"}`.
`status` is `PENDING` (held for approval), `PUBLISHED` or `HIDDEN`; `entryId` is
an entry id of the same weblog (another weblog's entry is a 404; `entry` is its
old name and still works); `limit`
defaults to 50 with a maximum of 200.

**The comment view:** `id`, `entryId`, `entryTitle`, `status`, `body`,
`created`, `statusChanged`, `authorUserName`, `authorDisplayName`,
`authorIsOwner` (the author holds a role on this weblog), `replyTo` (the id of
the comment this answers) and `reply` (the id of its reply, whatever that
reply's status). An email address or account id is never part of a view.
`entryTitle` is the entry's plain-text title, as in the entry view. An empty
`body` on a `HIDDEN` comment means its author
deleted it.

```bash
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  "https://blog.example.com/api/v1/weblogs/myblog/comments?status=PENDING"

# approve it
curl -sS -X PATCH -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  -H 'Content-Type: application/json' -d '{"status": "PUBLISHED"}' \
  https://blog.example.com/api/v1/weblogs/myblog/comments/COMMENT_ID

# answer it, as the token's user
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  -H 'Content-Type: application/json' -d '{"body": "Thanks!"}' \
  https://blog.example.com/api/v1/weblogs/myblog/comments/COMMENT_ID/replies
```

`PATCH` accepts only `PUBLISHED` or `HIDDEN`; any other value is a 400.
Publishing a comment its author deleted (a `HIDDEN` comment with an empty
`body`) is a 409: its text is gone, so it can never be shown again.
`DELETE` removes the comment and its reply (204). A reply is written as the
token's user, published at once, and answers 201 with its view; a reply the
service refuses (for example the comment already has one, or the body is empty
or over 4000 code points) is a 400 (429 for `comment.replyRateLimited`, see
below) whose `code` names the refusal, such as `comment.replyNotAccepted`,
`comment.replyEmptyBody`, `comment.replyBodyTooLong` or
`comment.replyRateLimited` (replies count toward the per-account comment rate
limit); its `detail` ends in the refusal's name (`BAD_REPLY`,
`EMPTY_BODY`, ...), as it always has.

> **Changed in 0.16.0: a rate-limited reply is a 429, not a 400.**
> `comment.replyRateLimited` now answers 429 with `type` `/throttled`. The
> `detail` is unchanged. There is no `Retry-After`: the comment limiter counts
> recent posts and does not know how long the wait is.

Replies are for the weblog's members: a global admin whose token reaches a
weblog they are not a member of gets `comment.replyNotAccepted`.

**Settings.** A weblog's `commentsEnabled` and `commentNotify` (email the
weblog's admins when a comment is held), `showMoreFromSite` (default true;
false hides the other blogs' posts in that blog's more-from-the-site block,
leaving its link to the front page) and `shareRowEnabled` (default true; false
removes the share row under that blog's posts, leaving Nearby and Keep
reading) are fields of the weblog view and
`PATCH /api/v1/weblogs/{handle}` (ADMIN token), and an entry's
`commentsClosed` is a field of the entry view and of entry create and update.
All five are booleans and cannot be cleared.

A weblog's `entryOrder` (`NEWEST`, the default, or `OLDEST`) is the order of
that blog's home, category, tag and archive lists. Send it in either case;
the view always answers in upper case. Any other value is a 400 with an
`errors` entry on `entryOrder`, and nothing is stored. Feeds, the site front
page, site-wide lists and search stay newest first. It cannot be cleared. The
MCP `weblogs` tool's `update` action carries it like any other field.

A weblog's `pageWidth` (`WIDE`, the default since 0.16.5, or `STANDARD`) is how
much of a large screen the blog's theme uses: `STANDARD` keeps the theme's
narrower page column, post column and line of text; phones look the same
either way. Send it in
either case; the view always answers in upper case. Any other value is a 400
with an `errors` entry on `pageWidth`, and nothing is stored. It cannot be
cleared. A blog whose stylesheet was customised keeps that stylesheet's
widths.

A weblog's `frontPage` is the id of one of its published pages. While set, the
blog's root shows that page and the post list moves to `/<handle>/posts`
(the nav gains a "Posts" link). Send `"clear": ["frontPage"]` to return the root
to the post list; the view omits the field when none is set. A draft page,
another blog's page and an unknown id are all a 400 with an `errors` entry on
`frontPage`, and nothing is stored. Making the front page a draft or deleting
it clears the setting by itself. The site space has no front page.

An entry's `pinned` (default false) puts it at the top of its blog's home
page, above the regular posts, on the first page only. It is a field of the
entry view and of entry create and update; `false` unpins. A blog has at most
3 pinned entries, counting drafts and scheduled entries but not the trash:
pinning a fourth is a 400 with an `errors` entry on `pinned`, and nothing of
that write is saved. Unpin one first. Pinning needs publish rights, as every
entry write does (a member who may only edit drafts gets 403). An entry
restored from the trash when 3 others are pinned comes back unpinned. It
cannot be cleared; feeds and the site front page ignore it.

## Audits

```
GET /api/v1/weblogs/{handle}/audit/seo[?status=&offset=&limit=]
GET /api/v1/weblogs/{handle}/audit/seo/entries/{id}
GET /api/v1/weblogs/{handle}/audit/media
```

These turn "improve SEO" / "describe your images" into a work list an agent
can loop over: find a gap, fix it, re-run, confirm the count dropped.
`READ`-role is enough — both are reads.

**`audit/seo`** reports, for each entry with anything to fix, its `entryId`,
`anchor`, `title` and `gaps`: a list of the codes below that are not body
checks — over `PUBLISHED` entries by default. An entry with no gaps is left
out. `total`/`counts` describe every gappy entry
found, not just the current page, so an agent can see the shape of the
problem without paging through everything; only the `entries` array itself
is paged. No entry body is rendered here; for those checks, audit one entry.

**`audit/seo/entries/{id}`** runs every check on one entry, its body rendered
exactly as it publishes: `{id, anchor, title, findings}`, each finding
`{code, severity, message}` plus `value` and `limit` (characters) when a
length is involved. `message` is a sentence saying what to change. An entry of
another weblog, or none, is a 404; a trashed entry is a 409, as on every other
entry route. The checks report findings, never a score:

| Code | Finding | Severity |
|---|---|---|
| `missing_search_description` | The field is blank (whitespace counts as blank). | warning |
| `missing_meta_title` | The meta title is blank and the entry title cannot stand in for it: the title is over 60 characters, or there is none. A title that fits is the page title, so it needs no meta title; never copy the title in. | warning |
| `missing_featured_image` | No featured image. Optional: a post with no picture is complete without one. But when the text shows a picture (an `<img>`, a Markdown image or an `[image]` shortcode, outside code), it is a **warning**: the front page and every list show the post as a lettered tile. A save sets the first of the weblog's own pictures as the featured image, so this means the picture is still on another site: the write sent `importExternalImages: false`, uploads are off, or the copy failed. Save the text again, or set `featuredImageUrl`. | info; warning when the text shows a picture |
| `noindex` | The entry asks search engines not to list it. | warning |
| `search_description_long` | Over 160 characters: a search result cuts it off. | warning |
| `search_description_short` | Under 50 characters: search engines tend to replace it. | info |
| `search_description_cut` | The description is the post's own opening cut off mid-sentence with a full stop added ("...to bring hygienic,."). A search result, and the front page's excerpt, would show a broken sentence; the front page uses the post's opening instead. | warning |
| `meta_title_cut` | The meta title is the start of the entry title ending inside a word ("...Placer Citr"), so the browser tab and a search result show a broken word. | warning |
| `title_long` | The page title is over 60 characters. The page title is the meta title, or without one the entry title, followed by " — " and the weblog's name when the whole fits in 60 characters. | warning |
| `title_duplicate`, `search_description_duplicate` | Another published entry of the weblog has the same page title or search description (case and spacing aside); the message names it by anchor. A draft is compared with the published entries. | warning |
| `body_h1` | An `h1` in the body; the theme already prints the entry title as the page's `h1`. | warning |
| `heading_skip` | A heading more than one level below the one before it (`h2` then `h4`); the entry title counts as the `h1` before the first. | info |
| `image_alt_missing` | An image with no `alt`, or with only its file name as `alt` (what `[image]` falls back to for a media file with no alt text and no caption). `alt=""` marks a decorative image and is not reported. | warning |
| `fragment_link_broken` | A link to `#x` with no `id="x"` in the body. `#` and `#top` always work. | warning |
| `image_not_responsive` | An image of this weblog's media, with resized copies, placed as a plain `<img>` with no `srcset`, so every reader downloads the full-size file. `[image id=...]` and `[gallery]` always write the `srcset`; the message names the `[image id=...]` to use. | warning |
| `image_hotlinked` | An image loaded from another site: an absolute `http(s)` or protocol-relative `src` whose host is neither the site's nor the weblog's own (a custom domain included). It gets no resized or WebP copy and breaks when that site moves it. One finding per entry; the message names the hosts and counts the images. Save the text again with `importExternalImages` left on, or copy each in with `media/imports` and place it with `[image id=...]`. Relative urls and `data:` URIs are not reported. | warning |
| `entry_link_broken` | A link to one of this weblog's entry urls, relative or absolute, whose anchor is not a published entry. | warning |

Body checks (the last seven, from `body_h1` on) are `audit/seo/entries/{id}`'s and preview's;
`audit/seo` lists the others.

**`?status=TRASHED` is refused here with a 400 — unlike `GET .../entries`,
which accepts it.** This is deliberate, not an oversight: the entries list
is a general browsing/management endpoint where seeing the trash is
legitimate (you're there to restore or purge it). The audit is a to-do-list
generator meant to be blindly iterated, and handing that loop a trashed
entry framed as "needs a meta title" is exactly the side-door-resurrection
shape this codebase has already had to fix once (writing real metadata onto
an entry that's supposed to be gone). Asking for no status at all still gets
you the safe `PUBLISHED` default either way.

**`audit/media`** returns a `MediaAudit`: `{missingAltText, items}` — no
pagination at all (it matches `GET .../media`'s own precedent of returning
everything unpaged, not an oversight specific to this endpoint).
`missingAltText` is the count; `items` is every media file with missing or
whitespace-only alt text, using the identical definition the renderer and
the admin UI's own "no alt text" marker use — this list can never disagree
with what a reader actually sees. Each item's `directoryName` is the owning
directory's **name**, not its id — deliberately different from `MediaView.directory`
(an id), so the two media-shaped payloads in this API cannot be confused by
assuming a shared field name implies shared semantics. An audit item is a
work-list entry for a human or agent to read, not a round-trippable write
payload (the actual fix goes through `PATCH /api/v1/weblogs/{handle}/media/{id}`,
substituting this item's own `mediaId` field for `{id}` — nothing about the
directory is involved in making the fix), so a readable directory name
serves a reader better here than an id they would only have to look up
again.

## Traffic

```
GET /api/v1/weblogs/{handle}/traffic[?period=]
```

What readers read on one weblog: the numbers its Traffic page shows, for
deciding which posts to update or write next. Only a weblog admin may read
them (the Traffic page's permission): a member who can post gets 403, a
caller with no role on the weblog 404. The site space is not offered here
and answers 404 like an unknown weblog (a site admin reads it at `/admin/traffic/{handle}`).
A `READ` token is enough.

The answer also names the weblog's `timeZone` (the zone `asOf` and the day buckets are read in), an
`umamiUrl` (where "Open in Umami" goes; absent when the weblog is not registered or no dashboard address is
set) and, when there are no numbers because registering the weblog with the analytics service failed, the
`registrationError` text.

`period` is `24h`, `7d`, `30d` (the default) or `90d`. Anything else is a
400 on `period` that names those four; the Traffic page shows 30d instead,
the API does not guess.

The numbers come from Pressroom's own copy of the analytics, which a
background read refreshes (every 15 minutes by default); no request waits on the
analytics service. So read `state` and `asOf` before the numbers:

| `state` | Meaning |
|---|---|
| `NOT_CONFIGURED` | No analytics service is configured. No numbers. |
| `NO_DATA` | Nothing has been read for this weblog yet. No numbers (or zeros, just after it was registered). |
| `STALE` | The last read failed; these are the numbers from `asOf`, the last good read. |
| `OK` | A current snapshot, read at `asOf`. |

```json
{
  "state": "OK",
  "asOf": "2026-11-08T10:15:00Z",
  "period": "30d",
  "current":  {"visitors": 40, "visits": 50, "views": 120, "bounces": 10, "totalTimeSeconds": 3600},
  "previous": {"visitors": 20, "visits": 25, "views": 60, "bounces": 5, "totalTimeSeconds": 1800},
  "series": [{"day": "2026-11-07", "visitors": 7, "views": 15}],
  "posts": [{"entryId": "8a2f...", "title": "Spring in Auburn", "path": "/myblog/entry/spring-in-auburn",
             "views": 90, "visitors": 30, "trend": 0.5}],
  "breakdowns": {"sources": [{"key": "google.com", "value": 12}], "places": [], "devices": [],
                 "events": [], "campaigns": [], "speed": []},
  "pathsCappedAt": 0
}
```

- `previous` is the period of the same length just before this one.
  Visitors are distinct within a period: never add two periods' (or two
  days') visitors together.
- `series` has one bucket per day, zero-filled, in the site's time zone; for
  `24h` the buckets are hours and carry `hour` (0 to 23).
- `posts` has one row per recorded path, most viewed first. `entryId` and
  `title` are null for a path that is not an entry (a tag page, the front
  page); `title` is plain text, as in `entries`. `visitors` is null when
  the service cannot count it. `trend` compares this period's daily view
  rate with the next longer period's, minus one: 0 is steady, 1 is twice the
  usual rate, -0.5 half; null when no longer period has the path to compare
  with.
- `pathsCappedAt` is 0 when `posts` holds every recorded path; otherwise only
  the top paths were read, and a post missing from `posts` is unknown, not
  zero.
- With no snapshot, `asOf`, `current` and `previous` are absent and the
  lists and `breakdowns` are empty.

### Site traffic

```
GET /api/v1/admin/traffic[?period=]
GET /api/v1/admin/traffic/{handle}[?period=]
```

The second form is one blog's traffic (the shape of [Traffic](#traffic)), including the site space's, which the
weblog route never offers; it answers 404 for an unknown handle.

What the server admin's Site Traffic page shows, as numbers: one row per
blog, and the site's top posts and sources. It needs a site admin and an
`ADMIN` token (the permission of the other `/admin` routes); a weblog-pinned
token gets 404. `period` and `state` are as above, and so is the 400 for a
bad `period`. The numbers come from the same local copy, so nothing waits on
the analytics service.

```json
{
  "state": "OK",
  "asOf": "2026-11-08T10:15:00Z",
  "period": "30d",
  "totalViews": 300,
  "totalVisits": 200,
  "blogs": [
    {"handle": "myblog", "name": "My Blog", "siteSpace": false, "standing": "READ",
     "visitors": 40, "views": 120, "visits": 50, "change": 0.25, "topPostTitle": "Spring in Auburn"},
    {"handle": "newblog", "name": "New Blog", "siteSpace": false, "standing": "NO_DATA",
     "visitors": null, "views": null, "visits": null, "change": null, "topPostTitle": null}
  ],
  "posts": [{"entryId": "8a2f...", "title": "Spring in Auburn", "path": "/myblog/entry/spring-in-auburn",
             "views": 90, "visitors": 30, "trend": 0.5}],
  "sources": [{"key": "google.com", "value": 12}]
}
```

- `blogs` lists every blog, the site space included (`siteSpace`): those
  with numbers first, most viewed first, then those without, by handle.
  `standing` is `READ`, `NO_DATA` (not read yet) or `NOT_REGISTERED`
  (registering it with the analytics service failed). Unless it is `READ`,
  `visitors`, `views`, `visits` and `change` are null, never 0. `change` is
  views against the previous period (0.25 is a quarter more), and null when
  the previous period had no views. `topPostTitle` is plain text.
- `totalViews` and `totalVisits` add across the blogs. There is no visitor
  total: a reader of two blogs would count twice.
- `posts` rows are the per-blog route's; `sources` rows show, for a source
  several blogs share, the largest single blog's visitors, so a value is a
  lower bound.
- `totalViews` and `totalVisits` mean something only when `state` is `OK` or
  `STALE`; with `NOT_CONFIGURED` they are 0, not "no traffic".
- With no analytics service (`NOT_CONFIGURED`) the answer is 200 with empty
  lists and no `asOf`.

## Members

```
GET /api/v1/weblogs/{handle}/members[?offset=&limit=]
```

Who holds a role on one weblog, as its Members page lists them. Only a weblog
admin may read it (the Members page's permission), with a token of any role: a
member who can post, or only write drafts, gets 403; a caller with no role on
the weblog, or the site space, gets 404.

The answer is `{items, offset, limit, hasMore, total}`, sorted by `userName`
without regard to case; `total` counts every member, across pages. Each item:

| Member | Meaning |
|---|---|
| `userName` | The account's user name. |
| `screenName` | The name it is shown under. |
| `kind` | `PERSON` or `AGENT`. |
| `permission` | The strongest action the membership grants: `ADMIN`, `POST` or `EDIT_DRAFT` (as `weblogs[].permission` on `/me`). |

No email address is returned. A membership whose account no longer resolves
(deleted or disabled) is left out; a lookup that cannot run is a 500, never a
shorter list. **Membership changes are made in the admin UI**, not over the
API (they stay session-only, as agent accounts and tokens do). MCP: `weblogs`
`list_members`.

## Site administration

An `ADMIN`-role token and a site admin, for all of the below except the
weblog routes: `GET/PATCH /api/v1/weblogs/{handle}` are the weblog owner's
(an `ADMIN`-role token), and `POST /api/v1/weblogs` follows the Create Weblog
page (both below).

```
GET   /api/v1/admin/users[?enabled=&q=&kind=&offset=&limit=]
POST  /api/v1/admin/users
PATCH /api/v1/admin/users/{userName}
POST  /api/v1/admin/users/{userName}/password-link   email the person a set-password link
GET   /api/v1/admin/users/{userName}/weblogs         the weblogs the account belongs to

GET   /api/v1/admin/config
GET   /api/v1/admin/config/definitions   the display groups and each property's message key, type and default (what the Global Config screen draws)
PATCH /api/v1/admin/config
GET   /api/v1/admin/revisions          what the revision store holds
POST  /api/v1/admin/revisions/sweep    run the sweep, or report what it would remove

GET/POST /api/v1/admin/businesses, GET/PATCH/DELETE /api/v1/admin/businesses/{id}
                                       business profiles (see Business profiles below)

GET   /api/v1/weblogs[?offset=&limit=]
GET   /api/v1/weblogs/{handle}
POST  /api/v1/weblogs
PATCH /api/v1/weblogs/{handle}
```

`GET /api/v1/admin/users` answers a `UserPage` and `GET /api/v1/weblogs` a
`WeblogListPage`, both the paged envelope (see [Pagination](#pagination)).
Each user in it carries `kind`, `PERSON` or `AGENT`.
`POST /api/v1/admin/users` creates people only. `PATCH
/api/v1/admin/users/{userName}` on an agent answers 403 `Agent accounts are
managed in the admin UI.`, as it does on an admin.

**`POST /api/v1/weblogs` creates a weblog, and is not `ADMIN`-only.** It
follows the Create Weblog page's rule: any user the page lets create a weblog
may do it with a token whose role is `POST` or `ADMIN` and that is not limited
to one weblog (a token pinned to a weblog answers 404, a `READ` token 403).
When the site turns creation off, or allows one weblog per user and the caller
has one, the answer is 403. `handle`, `name` and `theme` are required;
`tagline`, `copyright` (plain text, at most 255 characters), `emailAddress`,
`locale` and `timeZone` are optional, and an omitted
`emailAddress`, `locale` or `timeZone` is the caller's own. A handle that is
reserved for the site's own pages (`api`, `page`, `search` and the like) is a
400, a handle already taken is a 409, and `frontpage` (the site-wide
aggregator theme) is refused like any theme that is not enabled: the 400 lists
the valid theme ids. The creator becomes the weblog's owner. The answer is 201
with the new weblog and a `Location` header.

**`PATCH /api/v1/weblogs/{handle}` settings.** A field left out, or sent as JSON
`null`, is unchanged. To remove a value, name its field in `clear`
(`"clear": ["about", "bookingUrl"]`); `clear` applies only to updates. The
clearable weblog fields are `tagline`, `copyright`, `customDomain`, `icon`, `about`,
`newsletterListUuid`, `businessId`, `frontPage`,
`placeType`, `placeLocality`, `placeRegion`, `placeCountry`, `placeLat`,
`placeLng` and `bookingUrl`; `placeLat` and `placeLng` clear together, as a
pair. The weblog view shows all of the fields below. The eleven settings
added to `PATCH`, with the rules the settings form applies (the old `analyticsSiteId` and
`analyticsShareUrl` are gone: Pressroom registers every blog with Umami itself, so a body
property of either name is ignored and naming one in `clear` is a 400 on `clear`):

- `icon`: at most 255 characters.
- `about`: at most 255 characters.
- `newsletterListUuid`: the newsletter list's UUID.
- `businessId`: the id of an existing business profile; otherwise 400 `No such business.` `GET
  /api/v1/weblogs/{handle}/businesses` lists the ids the weblog may use (see
  [Business profiles](#business-profiles)).
- `placeType`: `LodgingBusiness`; needs a `placeLocality`.
- `placeLocality`: at most 128 characters.
- `placeRegion`: at most 128 characters.
- `placeCountry`: two letters.
- `placeLat`: a number from -90 to 90, given together with `placeLng`; stored to two decimals.
- `placeLng`: a number from -180 to 180, given together with `placeLat`; stored to two decimals.
- `bookingUrl`: an `http` or `https` URL, at most 255 characters.

The rules judge the weblog as it will be after the change, so setting only
`placeType` on a weblog with no `placeLocality` is a 400.

**`theme` switches the blog's shared theme**, by the rule the Theme page
applies: an enabled theme, never `frontpage` (the site-wide aggregator
theme) unless the blog is already on it. A theme that is not offered is a
400 on `theme` whose message lists the offered ids, as `POST
/api/v1/weblogs` answers a wrong theme; `custom` is a 400 `Custom themes
are managed in the admin UI.` Switching saves the blog and expires its
rendered pages; like the Theme page, it drops the blog's own copy of the old
theme's stylesheet, and re-sending the current theme keeps it. The weblog
view reports the current theme as `theme` (a shared theme id, or `custom`);
`editorTheme` carries the same value under its older name and is deprecated.

**`POST /api/v1/admin/users` never accepts a password.** The account is
created disabled, with a random password nobody is ever told, and a
set-password link is emailed — the same mechanism the JSP admin's own "send
set-password link" action uses. No plaintext password ever crosses this
API, and the call fails outright (400) if mail is not configured, rather
than creating an account with no way to ever reach a usable password.

> **Changed in 0.16.0: `emailAddress` is judged as the admin UI judges it.**
> `POST /api/v1/admin/users` and `PATCH /api/v1/admin/users/{userName}`
> check an address with the same validator as the person and agent screens
> (Apache Commons `EmailValidator`), after trimming, where earlier versions
> accepted anything shaped like `something@something.something`. The validator
> knows the top-level domains, so `a@b.c` (no such domain) and reserved names
> such as `you@example.test`, `.example`, `.invalid` and `.localhost` are now
> refused, as is a local part longer than 64 characters; a quoted local part
> (`"a b"@example.com`) is now accepted. A refused address answers as before:
> `user.emailRequired` on create, `user.emailInvalid` on update, both on
> `emailAddress`. An address on a top-level domain newer than the server's
> validator is refused until Pressroom is upgraded.

> **Changed in 0.16.0: another user's email address is session-only.** A token cannot change it:
> `PATCH /api/v1/admin/users/{userName}` with an `emailAddress` that differs from the stored one answers 403
> `user.emailUiOnly`, before anything is applied (a `screenName` or `enabled` sent alongside is not saved
> either). Resending the stored address unchanged is accepted. `screenName`, `enabled` and account creation
> are unchanged. The MCP `users` `update` action answers the same.

**Listing and filtering.** `GET /api/v1/admin/users` takes `q` (text matched, ignoring case, against user name,
screen name, full name and email) and `kind` (`people`, `agents` or `all`, the default): the admin screen's search
and its All, People and Agents tabs. An unknown `kind` is a 400 `parameter.invalidValue`. `enabled`, `offset` and
`limit` apply to what the search finds.

**Profile fields.** `POST` and `PATCH` take `fullName` (not blank, at most 255 characters; on create it defaults
to the screen name), `locale` and `timeZone` (one the settings forms offer; refused as `locale.invalid` and
`timeZone.invalid`). `PATCH` also takes `writer`, which grants or revokes the editor (author) role, as the person
screen's "can write" box does; only a single-sign-on account has one to edit, so on any other account it is a 400
`user.writerSsoOnly`. Every field is judged before any is saved.

> **Changed in the React admin migration (wave 4): the administrator flag, a typed password and `writer` are
> session-only fields.** `PATCH` takes `administrator` (grant or revoke the site administrator role) and `password`
> (a new password for another person's account, at least 8 characters; it ends that account's other sessions; for
> your own account it is refused with 409 `user.ownPasswordUseProfile`, use `PUT /v1/me/password`); `POST` takes `password` (the account is created
> enabled and no mail is needed) and `administrator`. A token that sends `password`, or an `administrator` or `writer`
> value that differs from the stored one, gets **403** `auth.sessionOnly` before anything is applied; resending the
> stored value passes (the email rule's pattern). The `writer` role was token-settable before: it is lasting access
> that revoking a token does not remove (owner decision 2026-11-25). A session is refused, as the person screen
> refuses it, for an admin who would disable their own account (409 `user.cantDisableSelf`) or untick their own
> administrator role (409 `user.cantChangeOwnRole`); for `administrator: true` on a single-sign-on account (400
> `user.ssoAdmin`); and for `password` on a single-sign-on account (400 `user.ssoBound`), an agent (400
> `user.notAPerson`) or one under 8 characters (400 `password.tooShort`, on `password`). A changed `screenName`
> expires the weblogs showing the account's comments, as the person screen does. Memberships are written under
> `/v1/admin/users/{userName}/weblogs` ([Session-only](#session-only)). Agent and admin accounts stay refused to
> tokens as before; a session may change them.

**`POST /api/v1/admin/users/{userName}/password-link`** mails the account's own address a set-password link, as the
person screen's "send set-password link" does, and answers 204. It is refused with 400 when mail is not configured
(`user.mailNotConfigured`), for a single-sign-on account (`user.ssoBound`) and for an agent (`user.notAPerson`);
a token gets 403 for an agent or admin account, and a mail failure is 502 `user.passwordLinkFailed` with nothing
changed. It is not an MCP tool.

**`GET /api/v1/admin/users/{userName}/weblogs`** answers `{items: [{handle, name, permission}]}`, ordered by
handle: the weblogs the account holds a permission on and its highest permission on each (`ADMIN`, `POST` or
`EDIT_DRAFT`). Read only; memberships are changed in the admin UI. MCP: `users` `weblogs`.

The user view (`UserView`) carries these fields:

| Field | Meaning |
|---|---|
| `userName`, `screenName`, `emailAddress` | The account's identity. |
| `enabled` | Whether the account may sign in. |
| `globalRoles` | The account's global roles (`admin`, `editor`, `reader`). |
| `fullName`, `locale`, `timeZone` | The account's profile. |
| `writer` | Whether the account holds the editor (author) role. |
| `sso` | `true` when the account signs in with single sign-on (Google). Such an account has no password. An email address patched on such an account is replaced at its next sign-in. |

`GET/PATCH /api/v1/admin/config` reads and writes Pressroom's runtime
properties (`runtimeConfigDefs.xml` is the allowlist — a name that file
doesn't declare, including anything startup-scoped, is a 400). `PATCH` takes
a flat `{"name": "value", ...}` map, not a list of typed objects — the type
is declared server-side, not asserted by the caller — and every name and
value in the request is validated *before* anything is saved, so a bad
request never leaves a partial write behind.

`GET /api/v1/admin/config/definitions` lists the display groups of that file in order, each with its
properties' `name`, `key` (the message key of the label), `type`, `defaultValue`, `rows` and `cols`:
the Global Config screen draws its form from it. It is an operator route, not an MCP tool.

> **Changed in 0.16.0: `PATCH /api/v1/admin/config` applies the rules the
> Global Config screen applies.** Earlier versions checked only each value's
> type. Now, judged together and answered as one 400 that names every refused
> setting in `errors` (nothing is stored):
> - `site.frontpage.page` changed to a slug that is not a published site page:
>   `config.frontDoorPageUnpublished`. An unchanged slug is not looked up, so
>   resending a page that was unpublished later is accepted.
> - `site.frontpage.weblog.handle` set to the site space's handle, in any
>   case: `config.frontDoorSiteSpace`.
> - Both front-door settings non-empty after the patch (counting the stored
>   one a patch leaves alone): `config.frontDoorBothSet`, on
>   `site.frontpage.page`. To move the front door to a site page, send
>   `"site.frontpage.weblog.handle": ""` with it; to a blog, send
>   `"site.frontpage.page": ""`.
> - `site.privacy.page` changed to a reference (`slug` or `handle/slug`) that
>   does not resolve to a published page: `config.privacyPageUnpublished`.
> - Several bad values, type errors included, are one 400
>   (`request.invalidFields` when there are several) rather than the first
>   alone. A single type error answers exactly as before.
> - A name the allowlist does not declare, or a `null` value, is refused on
>   its own (as before) and before any value is judged, so a request mixing
>   one with a type error answers the unknown name or the null; the first bad
>   entry used to win.
>
> String settings are stored trimmed, and the answer echoes the stored value.
> A change to `site.name`, `site.shortName`, `site.description`,
> `site.verification.bing`, either front-door setting, `site.privacy.page` or
> `site.absoluteurl` now expires every cached page (every weblog's
> `lastModified` moves), as a Global Config save does; a change to a
> `site.discovery.*` value still rebuilds the front page's discovery snapshot.
> That expiry runs after the values are stored, so a 5xx from this `PATCH` does
> not prove nothing was saved: `GET /api/v1/admin/config` to see what was.

**`GET/PATCH /api/v1/weblogs/{handle}` is the weblog's owner's, as the
Weblog Settings and Theme pages are.** They need the weblog's `ADMIN`
permission (the owner, or a site admin, who holds it on every weblog) and an
`ADMIN`-role token; a `READ` or `POST` token is a 403 (`This token is not
scoped for administration.`). A caller with no role on the weblog gets 404,
as for a handle that does not exist; a member below `ADMIN` gets 403. A token
pinned to that weblog reaches it; one pinned to another weblog gets 404,
whatever its owner's role there, so reaching several weblogs takes an
unpinned token. The settings form reserves no field to site admins, so
neither does `PATCH`. `GET /api/v1/weblogs`, the list of every weblog, stays
site-admin only: a site admin's pinned token gets 404 there because the
route names no weblog, and anyone else gets 403 first. These are not the same resource as
`/api/v1/weblogs/{handle}/entries` and the other content routes, which a
`POST` token and a lower weblog role reach.

**`customDomain`** carries the weblog's virtual-host hostname (e.g.
`blog.example.com`), or `null` when the weblog is served the default way,
under `/<handle>/` on the site host. `PATCH` applies the same rules the JSP
Weblog Settings form applies — both call the shared
`CustomDomainRules` class, so the two surfaces cannot drift — validating and
persisting the hostname synchronously in the same request:

- a malformed hostname is **400**;
- a hostname another weblog already holds is **409**; re-submitting a
  weblog's own already-stored hostname unchanged is not a conflict;
- an explicit blank string (`""`) clears the domain; omitting the field
  (or sending JSON `null`) leaves it unchanged, the same convention every
  other field on this PATCH follows.

There is no API equivalent of the JSP form's certificate-zone warning — it
is advisory UI text (`vhost.cert.zones`), and an API client has nowhere to
show it.

`analytics_weblog_sites` (see the Grafana/SEO analytics contract) carries
this same hostname alongside the weblog handle and the Umami website id, so
that view is the single place mapping a Search Console property (keyed by
hostname) back to the weblog an agent can edit through this API (keyed by
handle).

### Business profiles

```
GET    /api/v1/admin/businesses[?offset=&limit=]
GET    /api/v1/admin/businesses/{id}
POST   /api/v1/admin/businesses
PATCH  /api/v1/admin/businesses/{id}
DELETE /api/v1/admin/businesses/{id}

GET    /api/v1/weblogs/{handle}/businesses[?offset=&limit=]
```

> **New in 0.16.0.**

A business profile is stored once and shared by the weblogs that point at it
(`businessId`); every one of them shows it as schema.org data. The
`/admin/businesses` routes are the Businesses screen's, so they need a site
admin and an `ADMIN` token (a weblog-pinned token gets 404). They judge and
store a value exactly as the screen does: both call the same rules.

```json
{
  "id": "4f1c...",
  "name": "Casa Azul",
  "businessType": "LocalBusiness",
  "websiteUrl": "https://casa.example.com",
  "bookingUrl": "https://book.example.com/casa",
  "telephone": "+351 912 345 678",
  "email": "hi@casa.example.com",
  "logoUrl": "https://casa.example.com/logo.png",
  "sameAs": ["https://www.instagram.com/casaazul"],
  "areaServed": "Azores",
  "description": "A guest house.",
  "weblogCount": 2,
  "created": "2026-11-21T10:15:00Z",
  "lastModified": "2026-11-21T10:15:00Z"
}
```

- The list is `{items, offset, limit, hasMore, total}`, sorted by `name`
  without regard to case, then `id`; `total` counts every profile.
  `weblogCount` is how many weblogs use the profile, as the Businesses list
  shows.
- **Create** needs `name`; `businessType` defaults to `Organization`.
  `clear` is a 400 on create. The answer is 201 with the profile.
- **Update** changes only the fields sent, and judges the profile as it will
  be. `clear` removes `websiteUrl`, `bookingUrl`, `telephone`, `email`,
  `logoUrl`, `sameAs`, `areaServed` or `description`; naming `name` or
  `businessType` there is a 400 (`clear.notClearable`). `sameAs` replaces the
  whole list.
- The rules (each refusal names its field in `errors`, all of them in one
  400): `name` required (`field.required`; blank, `field.blank`) and at most
  255 characters; `businessType` one of `Organization`, `LocalBusiness`,
  `ProfessionalService` (`businessType.unknown`); `websiteUrl` and `logoUrl`
  public, absolute `http`/`https` URLs
  (`field.notPublicUrl`; localhost and intranet hosts are refused), and
  `bookingUrl` the same (`bookingUrl.invalid`, as on a weblog) of at most 255 characters;
  `telephone` digits, plus, parentheses, hyphen, dot and space with an
  optional extension, 3 to 32 characters (`telephone.invalid`); `email` a
  valid address (`email.invalid`) of at most 255; `areaServed` at most 255;
  at most 10 `sameAs` links (`sameAs.tooMany`), each a public `http`/`https`
  URL of at most 255 (`sameAs.entryInvalid`). Too long is `field.tooLong`.
  Values are stored trimmed, and a blank optional value as `null`.
- A save expires the rendered pages of every weblog using the profile, as
  the screen's does.
- **Delete** of a profile some weblog uses is a 409 (`business.inUse`) whose
  `detail` says how many; nothing is removed. Point each weblog elsewhere
  first. An unused profile answers 204. An unknown id, on get, update or
  delete, is a 404 (`business.notFound`).

**`GET /api/v1/weblogs/{handle}/businesses`** is the weblog owner's picker:
what the Weblog Settings form offers, `{items, offset, limit, hasMore,
total}` of `{id, name, businessType}`, in the same order. It needs what
`GET /api/v1/weblogs/{handle}` needs (the weblog's `ADMIN` permission and an
`ADMIN` token; a pinned token reaches its own weblog). Send an item's `id` as
`businessId` on `PATCH /api/v1/weblogs/{handle}`.

MCP: the `businesses` tool (`list`, `get`, `create`, `update`, `delete`) and
the `weblogs` `list_businesses` action.

## Maintenance actions

```
POST /api/v1/admin/weblogs/{handle}/actions/flush-cache
POST /api/v1/admin/weblogs/{handle}/actions/rebuild-index
POST /api/v1/admin/weblogs/{handle}/actions/regenerate-renditions
```

`ADMIN` scope only, and a site admin's — the same three actions the
Maintenance screen offers, reachable without a browser. A token pinned to the
weblog in the path may run them; one pinned to another weblog gets 404. All
three answer **202 Accepted**, not 200:
rebuilding the index and regenerating renditions are asynchronous
background work that has only just started when the response returns, and
flushing the cache — while synchronous — shares the same status code so a
caller never has to special-case one of the three.

### Discovery and analytics status

```
GET  /api/v1/admin/discovery           front-page discovery: state, last attempt and success, counts, last error
POST /api/v1/admin/discovery/refresh   rebuild the popularity snapshot now; answers the status it left
GET  /api/v1/admin/analytics           registration and read status: configured, reachable, registered/total,
                                       failures per weblog, last read and its error
POST /api/v1/admin/analytics/sync      ask for a registration sync; 202, the sync finishes after the response
```

What the Maintenance screen shows beside its buttons, from the same managers; a site admin with an `ADMIN` token.
`discovery/refresh` is synchronous, and a failed analytics read is a 200 whose `state` is `FAILED` with `lastError`,
as the screen reports it. Neither status calls the analytics service. None of the four is an MCP tool: they are
operator maintenance.

## MCP

`POST /api/mcp` is a Model Context Protocol endpoint (Streamable HTTP, stateless: no sessions, no
server-sent streams) for agents. Its tools are a facade over the routes in this document: each tool
call runs the matching `/api/v1` route inside Pressroom, so every rule here (token scopes, validation,
error details) applies unchanged. A weblog or item the token may not see answers "not found"; an
action beyond the token's role answers "forbidden", exactly as over REST.

- **API tokens only.** Mint one with `bin/pressroom-api auth login` or `POST /api/v1/tokens`. Username
  and password are refused at `/api/mcp` with the 401 every route but the token resource gives a
  password ([With the API](#with-the-api)); any other non-token caller gets 401 `MCP accepts API
  tokens only.` (code `mcp.tokenRequired`).
- **Admin accounts are read-only through the API**, and so through MCP. Changing one (for example
  `PATCH /api/v1/admin/users/{userName}` on an admin) answers 403 `Admin accounts are managed in the
  admin UI.` Disabling, deleting, re-addressing and role changes for admins happen in the admin UI.
- **Agent accounts are read-only through the API**, and so through MCP: `PATCH
  /api/v1/admin/users/{userName}` (the `users` tool's `update`) on an agent answers 403 `Agent accounts
  are managed in the admin UI.` Weblog memberships and tokens are managed by a person in the admin UI.
- **`site.absoluteurl` is read-only through the API.** `PATCH /api/v1/admin/config` (the
  `site_settings` tool) naming it answers 403 `site.absoluteurl is managed in the admin UI.` and
  applies nothing: it is the base of every password-reset link, so a stolen token able to change it
  could redirect an admin's reset to another host. Reading it is allowed.
- A request carrying a browser `Origin` other than the site's own is refused
  (403); bodies over 4,048,576 bytes
  are refused (413).
- Each tool call counts once against the token's rate limit.

Connect a client that supports Streamable HTTP with a custom header, for example:

```bash
claude mcp add --transport http pressroom https://blog.example.com/api/mcp \
    --header "Authorization: Bearer $PRESSROOM_API_TOKEN"
```

| Tool | Actions |
|---|---|
| `whoami` | |
| `site_info` | |
| `weblogs` | `list`, `get`, `create`, `update`, `list_members`, `list_businesses` |
| `entries` | `list`, `get`, `create`, `update`, `trash`, `restore`, `delete_forever`, `preview`, `revisions`, `revision`, `restore_revision`, `keep_revision`, `unkeep_revision` |
| `categories` | `list`, `create`, `update`, `delete`, `list_tags` |
| `media` | `list`, `get`, `import`, `update`, `crop`, `delete`, `list_directories`, `create_directory`, `delete_directory`, `update_directory` |
| `pages` | `list`, `get`, `create`, `update`, `preview`, `delete`, `restore`, `delete_forever`, `revisions`, `revision`, `restore_revision`, `keep_revision`, `unkeep_revision`, `set_parts` |
| `redirects` | `list`, `create`, `delete` |
| `comments` | `list`, `get`, `update`, `delete`, `reply` |
| `audits` | `seo`, `seo_entry`, `media` |
| `traffic` | `get`, `site` |
| `users` | `list`, `create`, `update`, `weblogs` |
| `businesses` | `list`, `get`, `create`, `update`, `delete` |
| `site_settings` | `get`, `update` |
| `maintenance` | `flush_cache`, `rebuild_index`, `regenerate_renditions` |

A tool takes `action` (except `whoami` and `site_info`) and that action's
arguments, named as in this document:
`weblog` is a handle, and body fields keep their names. An argument the action does not take is
refused, never ignored. An argument that fills part of the route's path
(`weblog`, `id`, `revisionId`, `userName`)
must be one path segment: an empty value, `.`, `..`, or a value containing `/`, `\` or `;` is
refused. A refusal from the API is a tool error whose text is the API's problem body. A refusal
the MCP endpoint makes itself (an unknown action, a missing or inapplicable argument) is a tool
error carrying the same problem shape as a REST 400 (`type`, `title` `Invalid request`, `status`,
`detail`, `instance`, `code`), its `code` one of the `mcp.*` codes in [Errors](#errors)
(`mcp.actionUnknown`, `mcp.argumentMissing`, ...), and a failure it cannot explain is REST's opaque
internal-error problem. A
503 arrives as a tool error with the `/busy` problem; the `Retry-After` header does not travel
through MCP, so wait about the lock timeout (10 seconds by default) before retrying. Not available
through MCP: minting, listing or revoking tokens, uploading media from local files, duplicating an entry,
sending an entry as a newsletter, and everything in
[Session-only](#session-only).

## Session-only

Some things are for a person signed in to the admin app, never for a token. A token of any role,
a password (HTTP Basic), an anonymous caller and any other credential get **403** `auth.sessionOnly` on these
routes; only an interactive signed-in session (the admin app's own cookie, with its CSRF header on every write) may
call them. A caller who may not see the weblog still gets the 404 every weblog route gives, first. None of them is an MCP tool.

| Route | What it does | Weblog permission |
|---|---|---|
| `POST /v1/weblogs/{handle}/members` | Grant an existing account a role: `{userName, permission}` (`ADMIN`, `POST` or `EDIT_DRAFT`), at once, no invitation. 201 with the member. | admin |
| `PATCH /v1/weblogs/{handle}/members/{userName}` | Give a member the one level `{permission}`. | admin |
| `DELETE /v1/weblogs/{handle}/members/{userName}` | Remove a member. 204. | admin |
| `POST /v1/weblogs/{handle}/themes/custom` | Switch to a custom theme (one way), optionally importing a shared theme's templates: `{importTheme}`. | admin |
| `GET`, `POST /v1/weblogs/{handle}/templates` | List the weblog's templates (with `availableActions`, the kinds that may be added now); add one: `{name, action}`, 201. | admin |
| `GET`, `PATCH`, `DELETE /v1/weblogs/{handle}/templates/{id}` | Read one (with its `contents`), change `{contents, contentType, and, for a custom one, name, description, link, navbar, hidden}` (an empty `contentType` lets the engine choose), delete it (204). | admin |
| `GET`, `PUT`, `DELETE /v1/weblogs/{handle}/stylesheet` | The weblog's own stylesheet: its state (`exists`, `customTheme`, `sharedThemeStylesheet`, `contents`), save `{contents}`, delete the copy (shared theme only). | admin |
| `POST /v1/weblogs/{handle}/stylesheet/copy-from-shared`, `/revert` | Copy the shared theme's stylesheet to edit it, or reset the copy to the theme's text (shared theme only). | admin |
| `GET /v1/weblogs/{handle}/submissions[?offset=&limit=]` | The contact-form inbox, newest first (default page 30, at most 200): `{items, offset, limit, hasMore, total}`, each message `{id, name, email, subject, message, pageSlug, entryAnchor, created}`. | post |
| `DELETE /v1/weblogs/{handle}/submissions/{id}` | Delete one message (204); a bulk delete is a loop. | post |
| `DELETE /v1/weblogs/{handle}` | Delete the weblog with everything on it, irreversibly (204). Confirming is the client's job. | admin |
| `POST /v1/weblogs/{handle}/entries/{id}/send-newsletter` | Email the entry to the weblog's newsletter list (as before; **moved here from the token-reachable routes**, owner decision 2026-11-25: the mail goes to every subscriber and cannot be recalled). | post |

Site administration and the caller's own account (a site admin, or any signed-in caller for the profile):

| Route | What it does |
|---|---|
| `GET`, `POST /v1/admin/agents` | List agent accounts (`q`, `enabled`, `offset`, `limit`); create one: `{userName, name, emailAddress?, notes?, siteAdmin?}`, 201. |
| `GET`, `PATCH /v1/admin/agents/{userName}` | One agent with its live `tokens`; change `{name, notes (empty clears), siteAdmin, enabled}` (a field left out is left alone). A person's account is `agent.notFound`. |
| `POST /v1/admin/agents/{userName}/tokens` | Mint a token for the agent: `{label, role, weblog?, expiresAt?}` as `POST /v1/tokens` takes them; 201 with the secret, shown once. The pin must be a weblog the agent belongs to (any weblog for a site-admin agent): `token.weblogNotMember`. |
| `DELETE /v1/admin/agents/{userName}/tokens/{id}` | Revoke one of that agent's tokens (204); any other id is `token.notFound`. |
| `GET /v1/admin/tokens[?all=true]` | Every user's tokens, newest first, `{items}` each with `owner`, `ownerKind`, `issuedBy` and `live`; `all` adds revoked and expired ones. |
| `DELETE /v1/admin/tokens/{id}` | Revoke any token (204). |
| `POST /v1/admin/users/{userName}/weblogs` | Give an account (a person or an agent) a role: `{handle, permission}`, 201 with `{handle, name, permission}`. |
| `PATCH`, `DELETE /v1/admin/users/{userName}/weblogs/{weblogHandle}` | Change the account's level `{permission}` there, or remove it (204). |
| `PATCH /v1/me/profile` | The caller's own `{screenName, fullName, emailAddress, locale, timeZone}`; a field left out is left alone. Answers the profile. |
| `PUT /v1/me/password` | Change the caller's own password: `{currentPassword, newPassword}`. 204; the account's other sessions end. |

`PATCH /v1/admin/users/{userName}` also takes `administrator` and `password`, and `POST /v1/admin/users` takes
`password` and `administrator`, as session-only fields (see the admin-users section above): a token that sends one gets
403 `auth.sessionOnly`. `site.absoluteurl` is written through `PATCH /v1/admin/config` by a session only; a token
still gets 403 `setting.managedInAdminUi`.

The agent rules are the agent screen's: a name that is not blank and at most 255 characters, notes at most 4000, a
user name of letters and digits that is not taken (409 `user.nameInUse`), an optional valid address; disabling an
agent ends every one of its tokens on its next request. The memberships rules are the person page's: group blogging
must be on to grant (409 `member.groupBloggingOff`), a reader account holds none (400 `member.readerAccount`), a
weblog keeps at least one admin (409 `member.lastAdmin`), an account with no role there is `member.notFound`, and
the person page has no rule against changing one's own role. The profile rules are `AccountRules`', the ones the
user patch applies; `PUT /v1/me/password` needs the current password (`profile.currentPasswordWrong`) and refuses a
single-sign-on account (`user.ssoBound`) and an agent (`user.notAPerson`). Every change writes the same
`pressroom.admin` line the screen does. A token's secret is never logged.

The rules are the admin screens' own. Members: one level per member; an existing, enabled account that is not a
reader (`member.userUnknown`, `member.readerAccount`, `member.levelInvalid`); nobody new while group blogging is
off (`member.groupBloggingOff`); nobody lowers or removes their own role (`member.selfChange`); a weblog keeps at
least one admin (`member.lastAdmin`); an account with no role here is `member.notFound`. Templates: a name of at
most 255 characters that no other template has (`template.nameInUse`, 409), an `action` the weblog may add now
(`template.actionUnavailable`), a required template of a custom theme cannot be removed (`template.required`);
removing the default page takes the stylesheet copy with it. Stylesheet: `stylesheet.notFound` when the
weblog holds no copy (copy it first), `stylesheet.customTheme` for copy, revert and delete on a custom theme.
Switching to a custom theme: `theme.customisationDisabled` (403) when the site has custom themes off or the
weblog is the site space, `theme.notOffered` when `importTheme` is not an enabled theme on offer.

Reading who holds a role (`GET .../members`) stays open to tokens. Also open to tokens, as screen pickers:
`GET /v1/meta/weblog-options` (any authenticated caller: `themes` a new weblog may start on, each
`{id, name, description, previewPath}` with `previewPath` relative to the site root, the preselected `defaultTheme`,
every `locales[]` `{id, name}` and `timeZones[]`, exactly as the create form lists them) and
`GET /v1/weblogs/{handle}/themes` (an ADMIN token: the shared themes the weblog may switch to, the `current` one,
`custom`, `customAllowed`, `sharedStylesheetCustomised`, `firstCustomization`; switch with `PATCH` on the weblog).

Every SA-18 resource is now behind these routes (wave 4 added agents, the site-wide token list, profile writes, the
administrator flag, typed passwords, a user's memberships and `site.absoluteurl`; none is an MCP tool). The rule for
a token is unchanged: a person does them in the admin app. Policy: SA-18 in the Simple Agility decision log;
send-newsletter and the `writer` role grant were added to its list by owner decision 2026-11-25 (decision-log
entry drafted, not yet posted).

## The OpenAPI document

```bash
curl -sS -H "Authorization: Bearer $PRESSROOM_API_TOKEN" \
  http://localhost:8083/api/v1/openapi.json
```

A machine-readable OpenAPI 3 document describing every endpoint above, for
generating a client or importing into API tooling. **It requires
authentication like everything else under `/api/**`** (a bearer token) —
there is no anonymous browser UI for it (`springdoc-openapi`'s Swagger UI is
deliberately not installed; see `application.properties`), and it is not
exempt from the site-wide `/api/**` security chain the way `GET
/api/v1/ping` is. If you want to browse it visually, fetch it with `curl`
and open it in any external OpenAPI viewer/editor. It is not part of one weblog, so a
token pinned to a weblog is refused with the 404 problem every such route
gives it ([Token scopes](#token-scopes)); fetch it with an unpinned token.

Every operation has an explicit `operationId` (`entriesList`, `pagesRestoreRevision`), which generated
clients name their methods after, and a tag naming its group of routes. Success bodies are
`application/json`. Every operation's `default` response is an `ApiProblem` served as
`application/problem+json` (see [Errors](#errors)); its `code` is a plain string whose known values are
listed as `x-extensible-enum`, so a client should accept a code it does not know.

New in 0.16.0: the operation ids and tags, every `DELETE` documented as a 204 with no body, the page
revision restore's body schema named `PageRevisionRestore`, and the media upload described as a
`multipart/form-data` body of `file` parts. A client generated from an earlier document gets new
method names when regenerated.

## `bin/pressroom-api`

A thin `curl`+`jq` wrapper covering most of the above:
`auth login`/`auth status`, `entries {list,get,create,patch,trash,restore,
delete-forever,preview}`, `media {list,get,upload,patch,delete,dirs,
create-dir}`, `audit {seo,media}`, and `admin {rebuild-index,flush-cache,
regenerate-renditions,users,config,weblogs}` (`admin reindex` is the old
name of `rebuild-index` and still works). `bin/pressroom-api <group> --help`
prints that group's full usage. It does not wrap categories and tags, pages,
redirects, comments, weblog creation (`POST /api/v1/weblogs`), media imports
by URL, the one-entry SEO audit, traffic, or listing and revoking tokens: call those with `curl`, using the
paths documented above, or through MCP.

The CLI, REST and MCP name some operations differently:

| `bin/pressroom-api` | REST | MCP tool `action` |
|---|---|---|
| `entries patch` | `PATCH .../entries/{id}` | `entries` `update` |
| `entries delete-forever` | `POST .../entries/{id}/delete-forever` | `entries` `delete_forever` |
| `media patch` | `PATCH .../media/{id}` | `media` `update` |
| `media dirs` / `media create-dir` | `GET`/`POST .../media/directories` | `media` `list_directories` / `create_directory` |
| `media upload` | `POST .../media` (multipart) | not available; `media` `import` fetches from a URL instead |
| `media replace` | `PUT .../media/{id}/content` (multipart) | not available |
| `admin rebuild-index` | `POST /api/v1/admin/weblogs/{handle}/actions/rebuild-index` | `maintenance` `rebuild_index` |
| `admin flush-cache` | `.../actions/flush-cache` | `maintenance` `flush_cache` |
| `admin users patch` | `PATCH /api/v1/admin/users/{userName}` | `users` `update` |
| `admin config list` / `set` | `GET`/`PATCH /api/v1/admin/config` | `site_settings` `get` / `update` |
| `admin weblogs patch` | `PATCH /api/v1/weblogs/{handle}` | `weblogs` `update` |
| `auth status` | `GET /api/v1/me` | `whoami` |
A leading `--url URL`/`--token TOKEN`, before the command name, overrides
`PRESSROOM_API_URL`/`PRESSROOM_API_TOKEN` and `~/.pressroom/credentials` for a single
one-off call against a different instance, without touching either.
