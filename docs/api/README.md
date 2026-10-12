# Roller Automation API

Roller's automation API (`/api/v1`) lets a script or an agent create and edit
entries, upload media, run SEO/media audits, and administer weblogs and
users — everything the JSP admin UI can do, callable without a browser.

> **`/api/v1` is unstable while Roller is 0.x.** Paths, fields and status
> codes here can change release to release with no deprecation window. Pin a
> Roller version if you automate against this; do not assume forward
> compatibility.

There is **no web UI for minting a token**. The only route in is the CLI:

```
bin/roller-api auth login --url http://localhost:8083
```

Everything below assumes a Roller running at that URL (the default for
`./roller dev`; see the repository's `CLAUDE.md`). In production, substitute
your site's own origin.

### Local development

Two commands, no password to remember:

```bash
./roller dev      # starts postgres, seeds the dev admin, runs the app
./roller token    # mints a bearer token for that admin
```

The dev admin password lives in `.roller-dev-secret`, generated with mode 0600
on the first `./roller db|dev|reset` and printed once at that moment. It is
git-ignored and is the source of truth: the seed re-applies it on every run, so
a password changed through the web UI is reverted next time. Edit the file to
choose your own.

### Non-interactive login

`auth login` prompts for a password by default. `--password-stdin` reads it
from stdin instead — never an argv entry, never an environment variable, so it
is safe in CI:

```bash
printf '%s\n' "$PASSWORD" | bin/roller-api auth login \
    --url https://example.com/roller --user admin --password-stdin
```

## Contents

- [Getting a token](#getting-a-token)
- [Token scopes](#token-scopes)
- [Making a request](#making-a-request)
- [Removing a value](#removing-a-value)
- [Pagination](#pagination)
- [Errors](#errors)
- [The throttle](#the-throttle)
- [Entries](#entries)
- [Categories and tags](#categories-and-tags)
- [Media](#media)
- [Pages](#pages)
- [Redirects](#redirects)
- [Audits](#audits)
- [Site administration](#site-administration)
- [Maintenance actions](#maintenance-actions)
- [MCP](#mcp)
- [The OpenAPI document](#the-openapi-document)
- [`bin/roller-api`](#binroller-api)

## Getting a token

`POST /api/v1/tokens` (mint), plus `GET`/`DELETE` on the same resource
(list/revoke), is where you use HTTP Basic authentication — your Roller
username and password — because minting your first token can't itself
require a token to prove who you are. Every other endpoint in this API is
Bearer-only in practice. **A Bearer-authenticated caller (an existing API
token) is explicitly refused on all three `/v1/tokens` operations**,
regardless of that token's own role — a leaked token can never mint itself
a replacement, list its owner's other tokens, or revoke one, which would
otherwise turn a single leaked credential into a permanent, self-renewing
one. This refusal is enforced by `TokensApi` itself, not by Spring Security
routing — Basic credentials are in fact accepted on every `/api/**` request,
not just this resource, so do not rely on Basic being *rejected* elsewhere;
it is simply undocumented and unnecessary anywhere else once you hold a
token.

```bash
curl -sS -u alice:hunter2 \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:8083/api/v1/tokens \
  -d '{"label": "seo-agent", "role": "POST", "weblog": "myblog"}'
```

```json
{
  "token": "rlr_9f3a1c...",
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
`info` object is a `TokenView`, the same shape `GET`/`DELETE` on this
resource return. (An earlier plan draft called this field `token_info`; the
shipped field name is `info` — check the response shape, not this document,
if the two ever disagree.)

`role` is one of `READ`, `POST`, `ADMIN` (see [Token scopes](#token-scopes)).
`weblog` is optional; omit it for a token that may act on every weblog its
owning user can already reach. `expiresAt` is an optional ISO-8601 instant;
omitted, the token never expires on its own — revoke it instead
(`DELETE /api/v1/tokens/{id}`, Basic-auth only, same as list/issue).

`bin/roller-api auth login` wraps exactly this call (minting an `ADMIN`-role
token under the label `roller-api cli`) and writes the URL and token to
`~/.roller/credentials` (`chmod 600`). Every other `roller-api` subcommand
reads that file, or `ROLLER_API_URL`/`ROLLER_API_TOKEN` if set, so it never
asks for a password again.

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
| `POST`  | GET, plus writes (POST/PATCH/DELETE) on ordinary content endpoints — entries, media, categories, pages. |
| `ADMIN` | Everything `POST` can, plus every `/api/v1/admin/**` endpoint and `/api/v1/weblogs` (the site-wide weblog list/administration resource, not the per-weblog content APIs) and the Maintenance actions. |

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
`GET /api/v1/me`) and are exempt from the weblog-pin check by design — a
weblog-scoped token must still be able to ask who it is. The two are not
otherwise equivalent: `GET /api/v1/ping` is the one route in the whole API
that needs **no credential at all** (`permitAll` in `SecurityConfig`, a bare
liveness check), while `GET /api/v1/me` still requires a valid Bearer or
Basic credential — it answers who *that* credential is, so an unauthenticated
call to it is a 401 like anywhere else. `GET /api/v1/me` also lists the
`weblogs` the token can act on, each with its `handle`, `name` and highest
`permission` (`ADMIN`, `POST` or `EDIT_DRAFT`), sorted by handle: the one
weblog a pinned token is limited to, or every weblog its user belongs to.
This is how a token that is not a site admin finds its weblogs, since listing
`/api/v1/weblogs` needs the admin role. Every other route
that resolves no weblog defaults to **deny**, not "unlimited because there
was nothing to check": `/api/v1/admin/**` and the site-wide
`/api/v1/weblogs` resource require `ADMIN` role via a separate, explicit
annotation on the controller (not the weblog-pin exemption above), and the
whole `/v1/tokens` resource (mint/list/revoke) refuses a Bearer-authenticated
caller outright regardless of role — see the next section.

## Making a request

Every non-mint call is Bearer-authenticated:

```bash
curl -sS -H "Authorization: Bearer $ROLLER_API_TOKEN" \
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
  `eventStart`, `eventEnd` and `eventLocation`. `geoLatitude` and `geoLongitude`
  clear together, as a pair.
- **Weblog:** `tagline`, `customDomain`, `icon`, `about`, `analyticsSiteId`,
  `analyticsShareUrl`, `newsletterListUuid`, `businessId`, `placeType`,
  `placeLocality`, `placeRegion`, `placeCountry`, `placeLat`, `placeLng` and
  `bookingUrl`. `placeLat` and `placeLng` clear together.
- **Media:** `altText`, `focalX` and `focalY`. `focalX` and `focalY` clear together.

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

List endpoints (`GET .../entries`, `GET .../audit/seo`,
`GET /api/v1/admin/users`, `GET /api/v1/weblogs`) accept `?offset=` and
`?limit=`. **Out-of-range values are a 400, never silently clamped or
wrapped**: `limit` below 1, or a negative `offset`, is rejected outright
before any query runs — a permissive endpoint would otherwise let
`limit=-2` reach the database layer's "no limit" sentinel and read the
entire table. A `limit` above the endpoint's cap (200, uniformly) is
silently capped rather than rejected — the one place this API *does*
clamp instead of refusing.

**The page envelope is not uniform across these four endpoints — check the
shape before assuming a field exists.**

- `GET .../entries` returns an `EntryPage`: `{items, offset, limit, hasMore}`.
  `hasMore` is computed by fetching one row past the requested page — page
  until it is `false`, not until a short page arrives.
- `GET .../audit/seo` returns a `SeoAudit`: `{total, counts, entries}`. There
  is no `hasMore`; `total` (and `counts`, the same per-gap-type tally) cover
  **every** gappy entry the search found, not just the current page, so an
  agent can read the shape of the problem without paging through it —
  `entries` alone is the `offset`/`limit`-sliced page.
- `GET /api/v1/admin/users` and `GET /api/v1/weblogs` return a **bare JSON
  array**, `[UserView, ...]` / `[WeblogView, ...]` — no envelope, no
  `total`, no `hasMore` at all. Getting fewer items back than the requested
  `limit` is the only signal that you have reached the end.

## Errors

Every failure a controller method can see is [RFC
9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.
**Four ordinary failures never reach a controller method at all, so they
are not:** `ApiExceptionHandler` is `@RestControllerAdvice(basePackages =
"org.apache.roller.weblogger.ui.restapi")`, and package-scoped advice is
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
  "type": "https://roller.invalid/problems/not-found",
  "title": "Not found",
  "status": 404,
  "detail": "No such entry.",
  "instance": "/api/v1/weblogs/myblog/entries/does-not-exist"
}
```

The problem type carries an `errors` array of `{"field": ..., "message":
...}` when the failure came from `@Valid` bean-validation — but as of this
writing **no `*Api` controller method actually declares `@Valid`**, so in
practice every 400 you will see from this API today carries only a plain
`detail` string, never an `errors` array. Do not build a client that
requires `errors` to be present; treat it as optional.

| Status | Meaning |
|--------|---------|
| 400 | Malformed request: bad JSON, an out-of-range `limit`/`offset`, an unknown status/role, a missing required field. |
| 401 | No credential, or a Bearer token that is missing, malformed, unknown, expired or revoked. |
| 403 | Authenticated, but the token's own scope refuses this call (wrong role, or an admin-only route with a non-`ADMIN` token). |
| 404 | The resource does not exist, **or the caller may not see it.** A weblog outside a token's pin, a foreign entry/category/page/media id, and a genuinely-missing id are all 404 — never 403, because a 403 there would confirm the resource exists under someone else's weblog. Also what an unmapped path under `/api/...` answers — see the note above on why that one is plain `application/json`, not a problem body. |
| 405 | The path exists but not for this HTTP verb (e.g. `DELETE` on a collection endpoint that only supports `GET`/`POST`). Plain `application/json` from Boot's own `/error`, not a problem body — see the note above. |
| 409 | The request conflicts with the resource's current state: trashing an already-trashed entry, restoring one that isn't trashed, PATCHing a trashed entry (restore it first), a duplicate category/directory/page-slug name, deleting a weblog's last category. |
| 413 | A multipart request body larger than `spring.servlet.multipart.max-request-size` (1GB). Plain `application/json` from Boot's own `/error`, not a problem body — see the note above. Distinct from the per-file media-quota refusal below, which is never a whole-request 413. |
| 415 | `POST .../media` without a `multipart/form-data` body. Plain `application/json`, not a problem body — see the note above. |
| 429 | Throttled — see below. |
| 500 | Unexpected server error. The response body never carries exception detail (message, stack trace, class name) — those are logged server-side only. |
| 502 | An upstream dependency this endpoint depends on failed after the request itself already succeeded — today only `POST /api/v1/admin/users` when the account was created but its set-password email could not be sent; the account survives and is resendable, so this is not retried as a fresh create. |

`ApiException` also has a `quotaExceeded()` factory that would answer 413,
but **no endpoint calls it today** — a media upload that would exceed a
quota is not a whole-request 413, it is a per-file `"quota_exceeded"`
`status` string inside that file's own `results[]` entry (see
[Media](#media)). Don't build a client that branches on 413 for a quota
refusal; check `results[].status` instead. The 413 you *can* actually get
is the framework-level one in the table above — a request body over
`spring.servlet.multipart.max-request-size` — which is a different
mechanism entirely (enforced by `DispatcherServlet` before any handler
runs) and carries no `results[]` body at all.

## The throttle

Every request to `/api/**` is rate-limited: **120 requests per 60-second
window** by default (`api.throttle.threshold` / `api.throttle.interval` in
`roller.properties`; the on/off switch, `api.throttle.enabled`, is a runtime
property changeable from Admin Settings without a restart). The key is the
token's own SHA-256 digest for a request carrying `Authorization: Bearer
...`, or the caller's IP address for any request that does not — in
practice that means Basic-authenticated calls to `/v1/tokens` (Basic is
accepted chain-wide, but this resource is the only one actually documented
to use it), so throttling is per-credential once you hold a token,
per-source-address before that. A throttled request gets a
429 `application/problem+json` body identical in shape to every other error
here, built and written outside the normal Spring MVC dispatch path (the
throttle check runs in a servlet filter, ahead of authentication) so a
throttled client sees the same error contract as everywhere else, not a
bare connection reset or an HTML error page.

## Entries

```
GET    /api/v1/weblogs/{handle}/entries                 list (filterable, paged)
GET    /api/v1/weblogs/{handle}/entries/{id}             get one
POST   /api/v1/weblogs/{handle}/entries                  create
PATCH  /api/v1/weblogs/{handle}/entries/{id}              partial update
DELETE /api/v1/weblogs/{handle}/entries/{id}              trash (not delete)
POST   /api/v1/weblogs/{handle}/entries/{id}/restore      restore (always to DRAFT)
POST   /api/v1/weblogs/{handle}/entries/{id}/delete-forever   permanent delete
POST   /api/v1/weblogs/{handle}/entries/{id}/preview       preview unsaved text
POST   /api/v1/weblogs/{handle}/entries/preview            preview text for a new entry
```

Reads need `READ`; writes need `POST` or `ADMIN`.

**Create:**

```bash
curl -sS -H "Authorization: Bearer $ROLLER_API_TOKEN" \
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

`title` and `text` are required on create (they are `NOT NULL` columns; an
entry with neither is not a resource this API will create). `status`
defaults to whatever a brand-new `WeblogEntry` defaults to (`DRAFT`) when
omitted; publishing with no `pubTime` supplied means "now", not an error.
`category` names a category **by name**, not id — the same shape
`EntryView.category` reads back — and an unknown name is a 400. `pubTime` is
a wall-clock string in **the weblog's own timezone**, e.g. `2026-03-01T09:30`
(see the repository's `CLAUDE.md`, "Entry editing", for why this is the
weblog's zone specifically and not the caller's).

**What `text` may hold.** Markdown, HTML, or both: HTML blocks pass through
Markdown untouched, and bare paragraphs between them are wrapped in `<p>`.
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
  the API. An `EVENT` also needs its dates, which only the editor sets so
  far.
- As with every field here, omitting one leaves it alone, so the API cannot
  clear coordinates. Setting `jsonLdType` to `BLOG_POSTING` stops them
  being emitted as `geo`.

All three fields come back on `EntryView` under the same names.

**Events.** An `EVENT` takes `eventStart`, `eventEnd` and `eventLocation`.
`eventStart` and `eventEnd` are wall-clock strings in **the weblog's own
timezone**, in the format `pubTime` takes (`2026-08-02T19:30`; seconds are
accepted, an offset such as `+02:00` is a 400 whose detail shows the format).
`eventEnd` before `eventStart` is a 400 (`eventEnd must not be before
eventStart.`). `EntryView` returns both as instants (`2026-08-02T17:30:00Z` for
19:30 in Berlin in summer), and the permalink's `Event` block carries the same
instant. All three can be removed with `clear`.

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
`?text=` (free-text search), `?locale=`, plus `offset`/`limit`. Naming no
`status` at all excludes the trash by construction (`?status=TRASHED` is the
only way to see it — see the repository's `CLAUDE.md`, "Trash", for why the
whole API's trash-safety property lives in that one default).

**Preview** (`POST .../entries/{id}/preview` or `.../entries/preview` for an
entry that does not exist yet) renders `{"text": "..."}` through the real
pipeline — shortcode expansion, then Markdown, then sanitization — the exact
sequence a saved entry goes through, so what you preview cannot disagree
with what gets published. Nothing is persisted; this is the only way to see
`[gallery]`/`[map]`/etc. expand before committing to a save.

## Categories and tags

```
GET    /api/v1/weblogs/{handle}/categories
POST   /api/v1/weblogs/{handle}/categories
PATCH  /api/v1/weblogs/{handle}/categories/{id}
DELETE /api/v1/weblogs/{handle}/categories/{id}?moveTo={id}
GET    /api/v1/weblogs/{handle}/tags
```

`POST`-role required for all of these (category structure is blog-wide, not
a single draft's business). Deleting the weblog's last remaining category is
a 409 — a weblog with zero categories can never save an entry again.
Deleting a category that has entries is also a 409 *unless* `moveTo` names
another category to move them into first (and `moveTo` naming the category
being deleted is itself a 400). `GET .../tags` has no pagination — a
per-weblog tag cloud has never been large enough to need it.

## Media

```
GET    /api/v1/weblogs/{handle}/media[?dir={directoryId}]
GET    /api/v1/weblogs/{handle}/media/{id}
POST   /api/v1/weblogs/{handle}/media           multipart upload, one or more files
POST   /api/v1/weblogs/{handle}/media/imports   import an image from a URL
PATCH  /api/v1/weblogs/{handle}/media/{id}
DELETE /api/v1/weblogs/{handle}/media/{id}
GET    /api/v1/weblogs/{handle}/media/directories
POST   /api/v1/weblogs/{handle}/media/directories
```

**Upload is a batch, and a batch is not a transaction.** Send one or more
`file` parts (`multipart/form-data`), optionally `directoryId` to file them
somewhere other than the weblog's default directory:

```bash
curl -sS -H "Authorization: Bearer $ROLLER_API_TOKEN" \
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
apply as they do for uploads. Every refusal is a 400 whose `detail` says why.
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
entirely), `focalX`/`focalY`, `name` (present-but-blank is rejected, not
stored — unlike `altText`, there is no legitimate "deliberately unnamed"
file), and `directoryId` (moves the file; a foreign or unknown directory id
is a 404, checked before anything else in the request is applied).

## Pages

```
GET    /api/v1/weblogs/{handle}/pages
GET    /api/v1/weblogs/{handle}/pages/{id}
POST   /api/v1/weblogs/{handle}/pages
PATCH  /api/v1/weblogs/{handle}/pages/{id}
DELETE /api/v1/weblogs/{handle}/pages/{id}
```

`POST`-role required. `status` is one of `DRAFT`/`PUBLISHED` only — pages
have no trash, no PENDING/SCHEDULED states, and no scheduling story at all
(see the repository's `CLAUDE.md`, "Pages", for why pages are a separate
entity from entries and deliberately excluded from the trash design). `slug`
is validated against the same reserved-word list (`ReservedSlugs`) the
renderer's own URL parser uses, and checked for a duplicate against the
weblog, before either ever reaches the database — both are 400/409 here
rather than an opaque 500. Unlike an entry title, **a page title is stored
raw, not HTML-escaped** — this API does not double-encode it, matching how
every bundled theme's page template escapes it at render time instead (see
CLAUDE.md's title-escaping asymmetry note if you are consuming both
`EntryView.title` and `PageView.title` in the same client).

## Redirects

```
GET    /api/v1/weblogs/{handle}/redirects
POST   /api/v1/weblogs/{handle}/redirects
DELETE /api/v1/weblogs/{handle}/redirects/{id}
```

`POST`-role required. A redirect rule answers a weblog URL **that would
otherwise 404** with a 301 to live content — it can never shadow anything
that is actually being served, because the lookup only runs at points where
a 404 has already been decided (see the repository's `CLAUDE.md`,
"Redirects"). This is the only admin surface for rules: a site migration is
a script emitting exact rules through `POST`, and renaming a page's slug
mints its own `SLUG_HISTORY` rule with no API call at all.

A rule is `{"source": "/old-path", "target": "/new-path"}` — both
weblog-relative (no handle, no context path, no query string; the request's
query string is preserved on the 301 automatically). Off-site targets,
query strings in either path, duplicate sources, and anything that would
chain two rules together are refused as 400s with the reason in the body.
There is no update verb: delete and recreate. The list echoes each rule's
`hitCount` and `lastHitAt` — the way to see whether a stale URL is still
being asked for — and every *served* redirect also logs one line to the
`roller.redirects` logger with the requested URI, referer, and user-agent
for diagnosis.

## Audits

```
GET /api/v1/weblogs/{handle}/audit/seo[?status=&offset=&limit=]
GET /api/v1/weblogs/{handle}/audit/media
```

These turn "improve SEO" / "describe your images" into a work list an agent
can loop over: find a gap, fix it, re-run, confirm the count dropped.
`READ`-role is enough — both are reads.

**`audit/seo`** reports entries missing a search description, a meta title,
or a featured image, plus entries explicitly marked `noindex` — over
`PUBLISHED` entries by default. `total`/`counts` describe every gappy entry
found, not just the current page, so an agent can see the shape of the
problem without paging through everything; only the `entries` array itself
is paged.

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

## Site administration

`ADMIN` scope only, for all of the below.

```
GET   /api/v1/admin/users[?enabled=&offset=&limit=]
POST  /api/v1/admin/users
PATCH /api/v1/admin/users/{userName}

GET   /api/v1/admin/config
PATCH /api/v1/admin/config

GET   /api/v1/weblogs[?offset=&limit=]
GET   /api/v1/weblogs/{handle}
POST  /api/v1/weblogs
PATCH /api/v1/weblogs/{handle}
```

**`POST /api/v1/weblogs` creates a weblog, and is not `ADMIN`-only.** It
follows the Create Weblog page's rule: any user the page lets create a weblog
may do it with a token whose role is `POST` or `ADMIN` and that is not limited
to one weblog (a token pinned to a weblog answers 404, a `READ` token 403).
When the site turns creation off, or allows one weblog per user and the caller
has one, the answer is 403. `handle`, `name` and `theme` are required;
`tagline`, `emailAddress`, `locale` and `timeZone` are optional, and an omitted
`emailAddress`, `locale` or `timeZone` is the caller's own. A handle that is
reserved for the site's own pages (`api`, `page`, `search` and the like) is a
400, a handle already taken is a 409, and `frontpage` (the site-wide
aggregator theme) is refused like any theme that is not enabled: the 400 lists
the valid theme ids. The creator becomes the weblog's owner. The answer is 201
with the new weblog and a `Location` header.

**`PATCH /api/v1/weblogs/{handle}` settings.** A field left out, or sent as JSON
`null`, is unchanged. To remove a value, name its field in `clear`
(`"clear": ["about", "bookingUrl"]`); `clear` applies only to updates. The
clearable weblog fields are `tagline`, `customDomain`, `icon`, `about`,
`analyticsSiteId`, `analyticsShareUrl`, `newsletterListUuid`, `businessId`,
`placeType`, `placeLocality`, `placeRegion`, `placeCountry`, `placeLat`,
`placeLng` and `bookingUrl`; `placeLat` and `placeLng` clear together, as a
pair. The weblog view shows all of the fields below. The thirteen settings
added to `PATCH`, with the rules the settings form applies:

- `icon`: at most 255 characters.
- `about`: at most 255 characters.
- `analyticsSiteId`: a UUID.
- `analyticsShareUrl`: an `http` or `https` URL, at most 255 characters.
- `newsletterListUuid`: the newsletter list's UUID.
- `businessId`: the id of an existing business profile; otherwise 400 `No such business.`
- `placeType`: `LodgingBusiness`; needs a `placeLocality`.
- `placeLocality`: at most 128 characters.
- `placeRegion`: at most 128 characters.
- `placeCountry`: two letters.
- `placeLat`: a number from -90 to 90, given together with `placeLng`; stored to two decimals.
- `placeLng`: a number from -180 to 180, given together with `placeLat`; stored to two decimals.
- `bookingUrl`: an `http` or `https` URL, at most 255 characters.

The rules judge the weblog as it will be after the change, so setting only
`placeType` on a weblog with no `placeLocality` is a 400.

**`POST /api/v1/admin/users` never accepts a password.** The account is
created disabled, with a random password nobody is ever told, and a
set-password link is emailed — the same mechanism the JSP admin's own "send
set-password link" action uses. No plaintext password ever crosses this
API, and the call fails outright (400) if mail is not configured, rather
than creating an account with no way to ever reach a usable password.

`GET/PATCH /api/v1/admin/config` reads and writes Roller's runtime
properties (`runtimeConfigDefs.xml` is the allowlist — a name that file
doesn't declare, including anything startup-scoped, is a 400). `PATCH` takes
a flat `{"name": "value", ...}` map, not a list of typed objects — the type
is declared server-side, not asserted by the caller — and every name and
value in the request is validated *before* anything is saved, so a bad
request never leaves a partial write behind.

`GET/PATCH /api/v1/weblogs/{handle}` is **not** the same resource as
`/api/v1/weblogs/{handle}/entries` etc. — those act only on the weblog a
token is scoped to; this one is meant to reach *any* weblog on the site by
handle, since it requires `ADMIN` role. **A weblog-pinned token cannot
actually reach it, `ADMIN` role or not.** `ApiScopeInterceptor` applies the
token's weblog-scope check *before and independently of* the role check —
if the token is pinned to a weblog, that pin is enforced against
`{handle}` first, and a pin that doesn't match answers 404 regardless of
role. Only an **unpinned** `ADMIN` token — one minted with no `weblog`
scope at all — reaches every weblog on the site through this endpoint. An
operator who wants that reach must mint the token unpinned; pinning it "to
be safe" and expecting `ADMIN` role to still cross other weblogs is the
opposite of what happens.

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

## Maintenance actions

```
POST /api/v1/admin/weblogs/{handle}/actions/flush-cache
POST /api/v1/admin/weblogs/{handle}/actions/rebuild-index
POST /api/v1/admin/weblogs/{handle}/actions/regenerate-renditions
```

`ADMIN` scope only — the same three actions the Maintenance screen offers,
reachable without a browser. All three answer **202 Accepted**, not 200:
rebuilding the index and regenerating renditions are asynchronous
background work that has only just started when the response returns, and
flushing the cache — while synchronous — shares the same status code so a
caller never has to special-case one of the three.

## MCP

`POST /api/mcp` is a Model Context Protocol endpoint (Streamable HTTP, stateless: no sessions, no
server-sent streams) for agents. Its tools are a facade over the routes in this document: each tool
call runs the matching `/api/v1` route inside Roller, so every rule here (token scopes, validation,
error details) applies unchanged. A weblog or item the token may not see answers "not found"; an
action beyond the token's role answers "forbidden", exactly as over REST.

- **API tokens only.** Mint one with `bin/roller-api auth login` or `POST /api/v1/tokens`. Username
  and password are refused at `/api/mcp` with 401 `MCP accepts API tokens only.`
- **Admin accounts are read-only through the API**, and so through MCP. Changing one (for example
  `PATCH /api/v1/admin/users/{userName}` on an admin) answers 403 `Admin accounts are managed in the
  admin UI.` Disabling, deleting, re-addressing and role changes for admins happen in the admin UI.
- **`site.absoluteurl` is read-only through the API.** `PATCH /api/v1/admin/config` (the
  `site_settings` tool) naming it answers 403 `site.absoluteurl is managed in the admin UI.` and
  applies nothing: it is the base of every password-reset link, so a stolen token able to change it
  could redirect an admin's reset to another host. Reading it is allowed.
- A request carrying a browser `Origin` other than the site's own is refused (403); bodies over 2 MB
  are refused (413).
- Each tool call counts once against the token's rate limit.

Connect a client that supports Streamable HTTP with a custom header, for example:

```bash
claude mcp add --transport http roller https://blog.example.com/api/mcp \
    --header "Authorization: Bearer $ROLLER_API_TOKEN"
```

| Tool | Actions |
|---|---|
| `whoami` | |
| `weblogs` | `list`, `get`, `create`, `update` |
| `entries` | `list`, `get`, `create`, `update`, `trash`, `restore`, `delete_forever`, `preview` |
| `categories` | `list`, `create`, `update`, `delete`, `list_tags` |
| `media` | `list`, `get`, `import`, `update`, `delete`, `list_directories`, `create_directory` |
| `pages` | `list`, `get`, `create`, `update`, `delete` |
| `redirects` | `list`, `create`, `delete` |
| `audits` | `seo`, `media` |
| `users` | `list`, `create`, `update` |
| `site_settings` | `get`, `update` |
| `maintenance` | `flush_cache`, `rebuild_index`, `regenerate_renditions` |

A tool takes `action` (except `whoami`) and that action's arguments, named as in this document:
`weblog` is a handle, and body fields keep their names. An argument the action does not take is
refused, never ignored. An argument that fills part of the route's path (`weblog`, `id`, `userName`)
must be one path segment: an empty value, `.`, `..`, or a value containing `/`, `\` or `;` is
refused. A refusal from the API is a tool error whose text is the API's problem body. Not available
through MCP: minting, listing or revoking tokens, and uploading media from local files.

## The OpenAPI document

```bash
curl -sS -H "Authorization: Bearer $ROLLER_API_TOKEN" \
  http://localhost:8083/api/v1/openapi.json
```

A machine-readable OpenAPI 3 document describing every endpoint above, for
generating a client or importing into API tooling. **It requires
authentication like everything else under `/api/**`** (Basic or Bearer) —
there is no anonymous browser UI for it (`springdoc-openapi`'s Swagger UI is
deliberately not installed; see `application.properties`), and it is not
exempt from the site-wide `/api/**` security chain the way `GET
/api/v1/ping` is. If you want to browse it visually, fetch it with `curl`
and open it in any external OpenAPI viewer/editor.

## `bin/roller-api`

A thin `curl`+`jq` wrapper covering most of the above:
`auth login`/`auth status`, `entries {list,get,create,patch,trash,restore,
delete-forever,preview}`, `media {list,get,upload,patch,delete,dirs,
create-dir}`, `audit {seo,media}`, and `admin {reindex,flush-cache,
regenerate-renditions,users,config,weblogs}`. Run any group with no
subcommand (e.g. `bin/roller-api entries`) to see that group's full usage.
It does not yet wrap categories, tags or pages — call those with `curl`
directly, using the paths documented above, until a subcommand exists.
A leading `--url URL`/`--token TOKEN`, before the command name, overrides
`ROLLER_API_URL`/`ROLLER_API_TOKEN` and `~/.roller/credentials` for a single
one-off call against a different instance, without touching either.
