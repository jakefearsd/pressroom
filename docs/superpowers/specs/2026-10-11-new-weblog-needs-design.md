# What a new weblog needs through the API — design

Date: 2026-10-11. Design approved in conversation the same day; this spec
states what "done" means. It is step 2 of the five in the MCP roadmap
(`2026-10-11-mcp-endpoint-design.md`, Roadmap).

## Problem

Jake's publishing agent reaches Roller through `/api/mcp` (v0.1.14), a
façade over `/api/v1`. New topic blogs will be served from Roller, and the
agent should be able to stand one up and run it without a visit to the admin
UI. Today it cannot:

- **Creating a weblog** is UI-only.
- **Thirteen weblog settings** cannot be set through `PATCH /weblogs/{handle}`:
  icon, about, both analytics fields, newsletter list, business, the place
  fields and the booking link.
- **Featured images** must be media files, and media arrive only by multipart
  upload, which a tool call cannot carry.
- **Event dates** exist on entries (`EVENT` structured data) but the API
  cannot write them.
- **Nothing can be removed.** Every `PATCH` reads a null field as
  "unchanged", so a featured image, a location or a canonical URL, once set,
  stays set. Only `customDomain` clears, through an empty string.

Two UI defects surfaced while reading the code, and this work fixes them for
both surfaces:

- The Create Weblog page accepts a reserved path as a handle (`api`, `page`,
  `search`, `rss`, …), creating a weblog its own URL can never reach, and
  accepts any theme id, `frontpage` and made-up ones included.
- The editor reads event times in the server's zone, while `pubTime` uses
  the weblog's. With the container on UTC, a Berlin event entered as 19:30
  is published in structured data two hours wrong.

## Decisions

- **One shared rule class per concern, called by the UI and the API.** The
  UI's checks move out of `CreateWeblogController`,
  `WeblogConfigController.myValidate` and `EntryBean` into rule classes in
  `ui.controllers`, the pattern `CustomDomainRules` and `EntryFieldRules`
  already follow. Rejected: copying the checks into the API (the drift Jake
  ruled out when choosing in-process dispatch).
- **Weblog creation follows the UI's rule** (Jake's choice): any token not
  limited to one weblog, with `POST` or `ADMIN` role, whose user could create
  a weblog on the Create Weblog page. Rejected: site admins only.
- **Weblog settings stay admin-only**, as `WeblogsApi` is today. A
  non-admin user's agent can create a weblog and post to it; it changes the
  settings in the UI.
- **Image import fetches from any public host** (Jake's choice) and never
  from an internal address. Rejected: a host allowlist, and an optional one.
- **The fetch is synchronous**, on the request thread, under a 30-second
  deadline. Rejected: background jobs the agent polls; images are small.
- **Apache HttpClient 5** (version managed by Spring Boot) does the fetch,
  because its per-client `DnsResolver` lets the fetcher refuse internal
  addresses and connect only to addresses it checked. The JDK's
  `HttpClient` cannot pin a checked address without breaking TLS, and the
  JDK's resolver SPI is process-wide, which would cut the app off from its
  database and Listmonk.
- **Removing a value is an explicit `clear` list** (Jake's choice), not
  JSON Merge Patch (a null an agent sends for an argument it meant to skip
  would erase data) and not empty strings (no answer for numbers, booleans
  or dates).
- **Event times are wall-clock times in the weblog's zone**, in the API and
  in the editor, as `pubTime` already is.

## Design

### Creating a weblog

`POST /api/v1/weblogs`; MCP `weblogs create`.

| Field | |
|---|---|
| `handle` | Required. |
| `name` | Required, not blank, at most 255 characters. |
| `theme` | Required. |
| `tagline` | Optional, at most 255 characters. |
| `emailAddress` | Optional; the user's own when omitted. |
| `locale` | Optional; the user's own when omitted. |
| `timeZone` | Optional; the user's own when omitted. |

It answers 201 with the weblog view and a `Location` header naming
`/api/v1/weblogs/{handle}`. The weblog is created through the same
`WeblogManager.addWeblog` call the UI makes, so the token's user becomes its
owner and it gets the UI's default categories and pages.

The route lives in its own controller: `WeblogsApi` is `@AdminScoped` as a
whole, and creation is not admin-only. The controller requires a signed-in
user and no weblog. A token limited to one weblog reaches no weblog here and
is refused with 404 by `ApiScopeInterceptor`'s existing default; a `READ`
token is refused with 403 "This token is read-only.".

**`WeblogCreationRules`**, called by `CreateWeblogController` and the API:

- **May this user create a weblog?** `site.allowUserWeblogCreation` must be
  on (else 403 "Weblog creation is turned off on this site."). With
  `groupblogging.enabled` off, a user who is already a member of a weblog
  may not create another (else 403 "This site allows one weblog per
  user."). Site admins get no exemption, as in the UI.
- **Handle:** not blank; only the characters of `username.allowedChars`;
  at most 255 characters; not a name in
  `rendering.weblogMapper.rollerProtectedUrls` or
  `rendering.weblogMapper.userProtectedUrls`, compared ignoring case (400 for
  each, naming the rule); not already taken (409).
- **Theme:** an enabled theme other than `frontpage`. Otherwise 400 whose
  detail lists the valid ids, so the agent learns them from the error and no
  themes route is needed before step 5.
- **Locale and time zone:** one the UI's lists offer (`UIUtils.getLocales`,
  `UIUtils.getTimeZones`). Otherwise 400. The same rule applies to `PATCH`,
  where today any string up to the column length is stored, and an unknown
  zone then silently behaves as GMT.

These rules govern creation only. A weblog that already exists under a handle
that is now reserved keeps working as it does today.

### Weblog settings

`PATCH /api/v1/weblogs/{handle}` (MCP `weblogs update`) gains thirteen fields,
and the weblog view shows them:

| Field | Rule |
|---|---|
| `icon` | At most 255 characters, as the UI accepts it. |
| `about` | At most 255 characters. |
| `analyticsSiteId` | A UUID. |
| `analyticsShareUrl` | An `http` or `https` URL, at most 255 characters. |
| `newsletterListUuid` | A UUID. |
| `businessId` | An existing business (else 400 "No such business."). |
| `placeType` | `LodgingBusiness`; requires a `placeLocality`. |
| `placeLocality`, `placeRegion` | At most 128 characters each. |
| `placeCountry` | Two letters. |
| `placeLat`, `placeLng` | Numbers, given as a pair, within ±90 and ±180; stored at the column's two decimal places, as the UI stores them. |
| `bookingUrl` | An `http` or `https` URL, at most 255 characters. |

**`WeblogSettingsRules`** holds these checks, moved out of
`WeblogConfigController.myValidate` and `validateBusiness`, together with
the entry-count bound and the custom-domain checks `PATCH` already shares.
The settings form keeps its field errors; the API reports the same rule as a
400 detail. The rules judge the weblog as it will be after the change, so a
`PATCH` that sets only `placeType` on a weblog with no locality is refused.

Permission is unchanged: site administrators, with an `ADMIN` token.

### Importing an image from a URL

`POST /api/v1/weblogs/{handle}/media/imports`; MCP `media import`.

| Field | |
|---|---|
| `url` | Required. |
| `name` | Optional file name. |
| `directoryId` | Optional; the weblog's default directory when omitted. |
| `altText` | Optional. |

It answers 201 with the media view `media get` returns. The agent then sets
the entry's `featuredImageId` to its id. Token rules are an upload's: a
writing role, and a token limited to one weblog may import into that weblog.

**Storing.** The fetched image goes through the path a browser upload takes.
`MediaUploads.store` is generalised from `MultipartFile` to a small source
type (name, content type, size, stream) with one adapter per caller. So
every upload rule applies unchanged: uploads enabled, allowed and forbidden
extensions, the per-file and per-weblog size limits, GPS stripping and
renditions. A refusal by those rules is a 400 carrying the detail an
upload's per-file result carries. When uploads are off, the import is
refused before any network request.

**`RemoteImageFetcher`**, refusing every internal address:

- **URL:** scheme `http` or `https`; port 80 or 443, explicit or implied; no
  `user:password@`. Anything else is refused before any connection.
- **Addresses:** the fetcher's `DnsResolver` resolves the host and refuses
  it when *any* resolved address is not public; the connection then goes to
  an address that passed. An IP-literal host is checked the same way before
  the request. Refused:
  - IPv4: `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`,
    `169.254.0.0/16`, `172.16.0.0/12`, `192.0.0.0/24`, `192.0.2.0/24`,
    `192.168.0.0/16`, `198.18.0.0/15`, `198.51.100.0/24`, `203.0.113.0/24`,
    `224.0.0.0/4`, `240.0.0.0/4` (which includes `255.255.255.255`);
  - IPv6: `::/128`, `::1/128`, `fc00::/7`, `fe80::/10`, `ff00::/8`,
    `2001:db8::/32`; an IPv4-mapped (`::ffff:0:0/96`) or NAT64
    (`64:ff9b::/96`) address is judged by the IPv4 address inside it.
- **No proxy**, whatever the JVM's proxy properties say.
- **Redirects** are followed by the fetcher, not the client, at most 3, each
  checked as a new URL.
- **Time:** 5 seconds to connect; 30 seconds for the whole fetch,
  redirects included.
- **Size:** counted while reading against `uploads.file.maxsize`; the fetch
  stops at the first byte over, whatever `Content-Length` claims. The
  request asks for no compression, so the bytes counted are the bytes
  stored.
- **Content:** JPEG, PNG, GIF or WebP, decided by the file's first bytes, not
  by `Content-Type` or the URL. SVG is refused: it can carry script, served
  from the weblog's own origin.
- **Name:** the `name` field, else the URL's last path segment, else
  `image`; reduced to the characters an upload's name may hold; extension
  set from the detected type (`photo?id=7` becomes `photo.jpg`).
- **Identity:** `User-Agent: Roller/<version>`; JDK trust store and hostname
  verification.

Every refusal is a 400 whose detail says what went wrong, for example "That
address is not on the public internet.", "The image server answered 404.",
"Not a JPEG, PNG, GIF or WebP image.", "Larger than this site's 2 MB limit."
and "The image server took too long.".

Each import logs one INFO line on `roller.media.import`: weblog, host,
outcome, bytes and milliseconds. Never the path or query, which a signed URL
uses for its credentials.

There is no property, profile or flag that loosens the address policy. Tests
that need a local HTTP server construct the fetcher with a permissive policy
through a package-private constructor.

### Event dates

`EntryWrite` gains `eventStart` and `eventEnd`, wall-clock times in the
weblog's zone in the format `pubTime` accepts (`2026-08-02T19:30`), and
`eventLocation`, the venue name, at most 255 characters. `EntryView` gains
the three, the times as instants like `pubTime`.

`EntryFieldRules` gains the parse and the rule that `eventEnd` is not before
`eventStart`, judged on the entry as it will be after the change (400
"eventEnd must not be before eventStart."). The editor uses both: it shows
and reads event times in the weblog's zone, and an end before the start is a
field error.

### Clearing

`EntryWrite`, `WeblogPatch` and `MediaPatch` gain `clear`, a list of field
names to remove:

| Resource | Clearable |
|---|---|
| Entry | `summary`, `metaTitle`, `searchDescription`, `canonicalUrl`, `featuredImageId`, `ogImageId`, `jsonLdType` (back to `BLOG_POSTING`), `geoLatitude` with `geoLongitude`, `eventStart`, `eventEnd`, `eventLocation` |
| Weblog | `tagline`, `customDomain`, `icon`, `about`, `analyticsSiteId`, `analyticsShareUrl`, `newsletterListUuid`, `businessId`, `placeType`, `placeLocality`, `placeRegion`, `placeCountry`, `placeLat` with `placeLng`, `bookingUrl` |
| Media | `altText`, `focalX` with `focalY` |

Each is a 400:

- a name that is not clearable (the detail lists those that are);
- a name also given a value in the same body;
- one half of a pair (the detail names the pair);
- `clear` on an entry create.

Rules judge the result, so clearing `placeLocality` while `placeType`
stays is refused. Existing ways of emptying a field keep working:
`tags: []`, `noindex: false`, `customDomain: ""`.

### Discovery

`GET /api/v1/me` (MCP `whoami`) gains `weblogs`: the handle, name and
permission (`ADMIN`, `POST` or `EDIT_DRAFT`) of each weblog the token can act
on. That is the one weblog a limited token is limited to, or every weblog
its user is a member of, pending invitations excluded. It closes step 1's
follow-up: an unlimited token whose user is not a site admin could not find
its weblogs, since `weblogs list` needs `ADMIN`.

### MCP and docs

- `McpToolCatalog` gains `weblogs create` and `media import`.
  `McpCoverageTest` requires both. Every new body field, `clear` included,
  becomes a tool argument by derivation, described by its springdoc
  `@Schema` text; no MCP code names one.
- `McpInstructions.TEXT` explains `clear`, and that a featured image is
  imported with `media import` and then set by id.
- `docs/api/README.md` documents the routes and fields; `McpDocsTest` keeps
  its MCP table honest. `docs/dev/api.md` records the rule classes and the
  fetcher's no-bypass rule. The wiki page RollerMcp gains the new actions
  and the import limits (uploads must be on; the per-file limit).

## Acceptance criteria

Creating a weblog:

1. `POST /api/v1/weblogs` with an unlimited `POST` token creates the weblog:
   201, the weblog view, a `Location` header, the token's user holding
   `admin` permission on it, and the same category names as a weblog the UI
   creates with the same theme.
2. A missing `handle`, `name` or `theme` is a 400 naming the field. A handle
   with a character outside `username.allowedChars`, or over 255 characters,
   is a 400. A taken handle is a 409.
3. `api`, `API`, `search` and `page` are each refused as a handle: 400
   through the API and a field error on the Create Weblog page. So is a name
   added to `rendering.weblogMapper.userProtectedUrls`.
4. An unknown theme id and `frontpage` are each a 400 whose detail lists
   every enabled theme id except `frontpage`, and a field error on the
   Create Weblog page.
5. With `site.allowUserWeblogCreation` off, creation is a 403 through the
   API. With `groupblogging.enabled` off, a user already a member of a
   weblog gets a 403. The Create Weblog page refuses the same users as
   before.
6. A token limited to one weblog gets a 404. A `READ` token gets a 403 "This
   token is read-only.". Neither creates anything.
7. Omitted `emailAddress`, `locale` and `timeZone` take the user's values.
   A `locale` or `timeZone` the UI does not offer is a 400, on create and on
   `PATCH`.

Weblog settings:

8. `PATCH` sets each of the thirteen new fields and `GET` returns it. A value
   set through `PATCH` shows in the settings form, and one saved in the form
   shows in `GET`.
9. Each rule in the Weblog settings table is a 400 through `PATCH` and a
   field error in the settings form. Characterisation tests pin the form's
   field errors before the rules move and pass unchanged after.
10. A `POST` token's `PATCH` is still a 403.

Image import:

11. Importing a JPEG, a PNG, a GIF and a WebP from a test server (permissive
    policy) each creates a media file: 201, the media view, the stored bytes
    equal to the served bytes (test images carry no EXIF), the extension
    matching the detected type, in the default directory unless
    `directoryId` names another. Its id is accepted as an entry's
    `featuredImageId`.
12. A table test pins the production policy at the edges of every refused
    range in Importing an image from a URL, IPv4-mapped and NAT64 forms
    included, and allows a public IPv4 and a public IPv6 address. A host
    resolving to one public and one refused address is refused.
13. Refused before any connection: `ftp:` and `file:` URLs, an explicit port
    other than 80 or 443, a URL with user info, and the literals
    `127.0.0.1`, `2130706433`, `[::1]`, `[::ffff:127.0.0.1]`, `10.0.0.1` and
    `169.254.169.254`.
14. A redirect to a refused address is refused; a fourth redirect is
    refused; three redirects to an allowed test server succeed.
15. A body one byte over the limit is refused, both with a `Content-Length`
    under the limit and with none, and no more than the limit plus one
    buffer is read. A server that stops sending is abandoned at the
    deadline (tests construct a short one).
16. An HTML page served as `image/jpeg`, and an SVG, are each refused as
    "Not a JPEG, PNG, GIF or WebP image."
17. With uploads off, the import is refused and the test server receives no
    request. A forbidden extension and an exceeded weblog quota are refused
    with an upload's detail.
18. Each import logs exactly one INFO line on `roller.media.import` with
    weblog, host, outcome, bytes and milliseconds; the URL's path and query
    never appear in any log line.

Event dates:

19. On a weblog in `Europe/Berlin`, `entries create` with `jsonLdType`
    `EVENT`, `eventStart` `2026-08-02T19:30`, `eventEnd`
    `2026-08-02T22:00` and an `eventLocation` stores the start as the instant
    `2026-08-02T17:30:00Z`; `get` returns all three; the permalink's
    structured data carries that start instant, the end and the venue.
20. The editor, on the same weblog, shows that event as 19:30 and 22:00, and
    saving the form unchanged stores the same instants.
21. An end before the start is a 400 through the API and a field error in
    the editor, including when only one of the two is in the request.

Clearing:

22. For every clearable field of every resource: set it, clear it, and `GET`
    shows it absent (`jsonLdType` shows `BLOG_POSTING`), and the UI's form
    shows it empty.
23. A name that is not clearable, a name also given a value, half a pair,
    and `clear` on an entry create are each a 400 and change nothing.
24. Clearing `placeLocality` on a weblog whose `placeType` stays set is a
    400.

Discovery:

25. `whoami` lists, for an unlimited token, every weblog its user is a member
    of with handle, name and permission, and no weblog with only a pending
    invitation; for a limited token, only its weblog; for a user with none,
    an empty list.

MCP, docs and build:

26. `tools/list` shows `create` in the `weblogs` action enum and `import` in
    `media`'s. Their input schemas carry the new body fields, `clear`
    included, and no MCP code names them.
27. Through MCP alone, at context path `/` and `/roller`: `weblogs create`,
    `weblogs update` setting a place, `entries create` of an `EVENT` with
    dates, `entries update` clearing the geo pair, and `whoami` listing the
    new weblog. `media import` of `http://127.0.0.1/`,
    `http://localhost/` and `http://169.254.169.254/` are each a tool error
    with status 400 and the not-public detail.
28. The instructions stay under 3,000 characters and mention `clear` and the
    import-then-`featuredImageId` flow. `McpDocsTest` passes against the
    updated README.
29. The only new runtime dependency is `httpclient5`, at the version Spring
    Boot manages.
30. The full unit suite and every quality gate pass. The browser suite
    passes at `/` and at `-Dit.context.path=roller`.

## Risks

- **Event times move for existing editor entries.** An event saved through
  the editor before this change was read in the server's zone; after it, the
  same stored instant shows shifted by the gap between the server's zone and
  the weblog's. Production probably holds none (the agent's blogs are still
  on Blogger), but the database cannot be queried from here; the release
  notes say so.
- **HttpClient 5 and IP literals.** Whether the client consults its
  `DnsResolver` for a literal host is version behaviour, so the fetcher
  checks literals itself before the request (criterion 13), and the plan's
  first fetcher test establishes what the client does.
- **The feature does nothing until uploads are on.** Uploads default to off,
  with 2 MB per file and 20 MB per weblog. Jake will likely raise both for
  photo blogs; the RollerMcp page says where.
- **A slow image holds a request thread** for up to 30 seconds. The token
  throttle (120 calls a minute) bounds it; the site serves one agent.
- **Moving the settings checks** could change what the form accepts. The
  characterisation tests of criterion 9 are written first, against the old
  code.

## Out of scope

- Weblog settings for non-admin owners through the API.
- Listing businesses (step 4, business profiles).
- Themes and templates (step 5); comments (step 3).
- Images decoded for size or validity beyond their first bytes; the upload
  pipeline already handles them as it does uploads.
