# Umami 3.3.0 HTTP API, as observed

What the digest-pinned Umami image in `docker-compose.prod.yml` (3.3.0) actually
does, recorded 2026-11-02 by running it. The sample bodies are copied from real
responses; later tasks build JSON fixtures from them.

**What is pinned and what was only observed.** `UmamiApiContractTest` runs
against that image via `UmamiContainer` (same image, read from the compose
file; scratch PostgreSQL 18 on a private network) and pins: everything in the
decisions table; the account script (role, bcrypt cost, idempotence, password
rotation, nothing in the server log on failure); registration and the domain
rule; the status codes and field names/types of `stats` (incl. `comparison`),
`pageviews` (local-midnight buckets, omitted empty days, `sessions.y` =
visitors, `&compare=prev`), `metrics` (every type listed, `y` = visitors, the
three rejected types), `metrics/expanded`, `events/stats`, `events/series`,
`reports/performance`, `reports/utm` (POST only, `filters` required); `/api/me`
returning a token; and from `script.js`: `data-performance="true"`,
`data-domains` against the port-less hostname, and no `setItem`/cookie.
**Observed by hand, not pinned:** the literal sample bodies themselves (values
like `organicSearch` or `GB-ENG`), the column lists, the 400 error body layout,
error `code` strings, the rest of the tracker attribute table and the beacon
format, `/api/me/websites`, `/api/admin/websites?search=`, the `events` listing,
the `metric` report parameter, and the other report types. If an image bump
breaks the contract test, re-check this whole file.

Spec: `docs/superpowers/specs/2026-11-01-analytics-without-admin-steps-design.md`.

## Decisions this file settles

| Question | Answer |
|---|---|
| Is a supplied website `id` honoured? | **Yes.** `POST /api/websites {"id": "<uuid>", ...}` creates the website with that ID, and `GET /api/websites/{id}` returns it. So **Umami website ID = weblog ID**. |
| Registering an ID that already exists | **HTTP 500, empty body** (unhandled primary-key violation). Not 409. A reconciler checks with `GET /api/websites/{id}` first. |
| Website that does not exist | `GET /api/websites/{id}` answers **401**, the same as a website the caller may not see. 401 alone never means "missing, register it". |
| `localhost` / `localhost:8083` as a domain | **Both accepted** (200). |
| Other host:port, IP addresses, upper case | **Rejected** (400). See the domain regex below. |
| Performance attribute | `data-performance="true"`, exactly the string `true`. |
| Does the account's password change invalidate old tokens? | **Yes.** A token issued before a re-run of the account SQL answers 401 afterwards. Log in again on 401. |
| Can the owner's `admin` see Pressroom's websites? | **Yes**, in Settings › Websites (`GET /api/admin/websites`, owner named) and by ID. Not in admin's own `/api/websites` list, which shows only admin's websites. |
| Can the `pressroom` account create users? | **No** (401). Nor read `/api/admin/websites` (401). |

## Authentication

- `POST /api/auth/login` `{"username","password"}` →
  `200 {"token":"…","user":{"id","username","role","createdAt","isAdmin","teams":[]}}`.
  Wrong password: `401 {"error":{"message":"Unauthorized","code":"incorrect-username-password","status":401}}`.
- Every other call: `Authorization: Bearer <token>`. A bad, expired or
  superseded token: `401 {"error":{"message":"Unauthorized","code":"unauthorized","status":401}}`.
- `POST /api/auth/verify` (no body) → `200 {"id","username","role":"user","createdAt","twoFactorRequired":false,"isAdmin":false,"teams":[]}`.
  A cheap "is my token still good" probe.
- `GET /api/me` also answers, but its body **includes a fresh token**; never log it.

The image seeds `admin`/`umami` on first boot. Production changes that
password; Pressroom never uses `admin`.

## The `pressroom` account

`deploy/analytics/umami-pressroom-account.sql`, run with psql variables
`username` and `pw` against the **umami** database after Umami's first boot.
It inserts into `"user"` with role `user` and a pgcrypto bcrypt hash
(`crypt(:'pw', gen_salt('bf', 10))`, a cost-10 `$2a$` hash Umami accepts;
pgcrypto's default cost is 6). On conflict on
`username` (unique index `user_username_key`) it sets the new password, puts
`role` back to `user`, clears `deleted_at` and stamps `updated_at`.

`pgcrypto` is already present in Umami's database (Umami's own migrations
create it); the script's `CREATE EXTENSION IF NOT EXISTS` is a no-op there.

**The password never reaches the server log.** psql pastes `:'pw'` into the
statement text before sending it, and PostgreSQL's default
`log_min_error_statement = error` would copy a failing statement, password and
all, into the server log (in production, the shared `postgres` service's log).
So the script opens with `SET log_min_error_statement = panic` (superuser-only;
the deploy's `POSTGRES_USER` is one), and a guard raises *"Umami has not created
its "user" table yet"* before the password is sent when Umami has not booted.
Pinned for both failure modes: no `"user"` table, and an unset `:username`
(a syntax error). It relies on `log_statement` staying at its default `none`.

`"user"` columns (3.3.0): `user_id uuid NOT NULL` (no default), `username
varchar(255) NOT NULL` unique, `password varchar(60) NOT NULL`, `role
varchar(50) NOT NULL`, `created_at` (default now), `updated_at`, `deleted_at`,
`display_name`, `logo_url`, `two_factor_required boolean NOT NULL DEFAULT false`.

Websites the account creates are **user-owned** (`userId` = `createdBy` = the
pressroom user, `teamId` null). No team is needed for admin to see them.

## Registering a website

`POST /api/websites` with `{"id": "<uuid>", "name": "…", "domain": "…"}`
(`shareId`, `teamId` optional) → 200:

```json
{"id":"798f2c87-5e32-4870-803d-db9fc87e4be0","name":"probe","domain":"example.com","resetAt":null,"userId":"b563be39-3008-4321-91e7-481a727457cd","teamId":null,"createdBy":"b563be39-3008-4321-91e7-481a727457cd","createdAt":"2026-11-02T12:20:06.007Z","updatedAt":"2026-11-02T12:20:06.007Z","deletedAt":null,"recorderEnabled":false,"replayConfig":null,"shareId":null}
```

`GET /api/websites/{id}` returns the same shape. `name` is `varchar(100)`.

`domain` is **required** (missing → 400) and must match:

```
/^(localhost(:[1-9]\d{0,4})?|((?=[a-z0-9-_]{1,63}\.)(xn--)?[a-z0-9-_]+(-[a-z0-9-_]+)*\.)+(xn--)?[a-z0-9-_]{2,63})$/
```

So: lower-case DNS names (punycode for IDNs), or `localhost` with an optional
port. No port on any other host, no IP addresses, no upper case: lower-case a
host before sending it. A validation failure answers 400 with the reason under
`error.properties.<field>.errors`:

```json
{"error":{"message":"Bad request","code":"bad-request","status":400,"errors":[],"properties":{"domain":{"errors":["Invalid string: must match pattern /^(localhost(:[1-9]\\d{0,4})?|…)$/"]}}}}
```

Listing: `GET /api/me/websites` (or `/api/websites`) pages the caller's own
websites: `{"data":[…],"count":n,"page":1,"pageSize":20,"orderBy":"name"}`.
Admin-only `GET /api/admin/websites?pageSize=&search=` lists every website, each
row adding `"user":{"username","id"}` and `"team"`.

## Reading numbers

All per-website reads are `GET /api/websites/{id}/<endpoint>` with
`startAt`/`endAt` as **epoch milliseconds**. An empty website answers 200 with
zeros or empty arrays everywhere (never 404). An unknown `timezone` is a 400
(`{"properties":{"timezone":{"errors":["Invalid timezone"]}}}`), not a silent UTC.

### `stats` — the five headline numbers, with the previous period built in

`stats?startAt=&endAt=`:

```json
{"pageviews":4,"visitors":2,"visits":3,"bounces":2,"totaltime":29,"comparison":{"pageviews":0,"visitors":0,"visits":0,"bounces":0,"totaltime":0}}
```

- `comparison` is **always present**: the equal-length period ending at
  `startAt`. No second call is needed for "change against previous period".
- `pageviews` counts `event_type = 1` only (custom events and performance
  beacons are not views). `visitors` = distinct sessions, `visits` = distinct
  visits. Bounce rate = `bounces / visits`; average visit time =
  `totaltime / visits` (seconds). All plain JSON numbers here.

### `pageviews` — the daily series

`pageviews?startAt=&endAt=&unit=day&timezone=America/Los_Angeles`:

```json
{"pageviews":[{"x":"2026-11-02 00:00:00","y":2}],"sessions":[{"x":"2026-11-02 00:00:00","y":1}]}
```

- `x` is the bucket's **local** midnight in `timezone`, with no offset. Days with
  no traffic are **absent**, not zero-filled. `sessions.y` = visitors.
- `&compare=prev` adds `startDate`, `endDate` and a `compare` object with the
  previous period's series.

### `metrics` — top-N by one dimension

`metrics?startAt=&endAt=&type=<type>[&limit=n]` → `[{"x": "<value>", "y": <n>}]`.

**`y` is distinct visitors (sessions)**, not pageviews and not visits (pinned
with a session that has two visits and three views of one path: `y` = 2).

| Type | `x` | Sample |
|---|---|---|
| `path` | URL path, no query | `[{"x":"/blog/entry/hello","y":2},{"x":"/blog/entry/second","y":1}]` |
| `entry` / `exit` | landing / last path | `[{"x":"/blog/entry/hello","y":1}]` |
| `title` | page title | `[{"x":"Hello","y":1}]` |
| `referrer` (`domain` is the same) | bare referring domain | `[{"x":"google.com","y":1}]` |
| `channel` | Umami's channel class | `[{"x":"organicSearch","y":1}]` |
| `country` | ISO 3166-1 alpha-2 | `[{"x":"GB","y":2}]` |
| `region` | ISO 3166-2 | `[{"x":"GB-ENG","y":1,"country":"GB"}]` |
| `city` | city name | `[{"x":"Ipswich","y":1,"country":"GB"}]` |
| `device` | `laptop`, `desktop`, `mobile`, `tablet`, … | `[{"x":"laptop","y":1}]` |
| `browser` | lower-case id | `[{"x":"chrome","y":1},{"x":"safari","y":1}]` |
| `os` | name | `[{"x":"Linux","y":1}]` |
| `language` | language only | `[{"x":"en","y":1}]` |
| `screen` | `WxH` | `[{"x":"1920x1080","y":1}]` |
| `event` | custom event name | `[{"x":"cta-click","y":1}]` |
| `hostname` | host the beacon named | `[{"x":"example.com","y":1}]` |
| `query` | raw query string | `[{"x":"utm_source=newsletter&utm_medium=email&utm_campaign=nov","y":1}]` |
| `tag` | `data-tag` value | `[]` |

**Not metric types** in 3.3.0 (400): `url`, `utm_source`, `utm_campaign` (and
the other `utm_*`). UTM comes from `reports/utm`.

### `metrics/expanded` — per-row visitors, visits, views, bounces, time

`metrics/expanded?startAt=&endAt=&type=path` (any metric type):

```json
[{"name":"/blog/entry/hello","pageviews":"3","visitors":2,"visits":3,"bounces":2,"totaltime":"0"},{"name":"/blog/entry/second","pageviews":"1","visitors":1,"visits":1,"bounces":0,"totaltime":"0"}]
```

This is the Posts tab's source: **per-path visitors are supported** here.
Note the types: `pageviews` and `totaltime` are **JSON strings** (bigint
serialisation), `visitors`/`visits`/`bounces` are numbers. Parse both.

### Events

- `events/stats?startAt=&endAt=` →
  `{"data":{"events":1,"visitors":1,"visits":1,"uniqueEvents":1,"comparison":{"events":0,"visitors":0,"visits":0,"uniqueEvents":0}}}`
- `events/series?startAt=&endAt=&unit=day&timezone=` → `[{"x":"cta-click","t":"2026-11-02 00:00:00","y":1}]`
- `events?startAt=&endAt=` pages raw events (`{"data":[…],"count","page","pageSize","isCapped"}`),
  each row carrying session-level fields (country, city, device, os, browser).
  Not needed for counts; `metrics?type=event` gives the per-name totals.

### Reports (POST, not GET)

`POST /api/reports/<type>` with a JSON body; **`filters` is required, even
empty** (omitting it is a 400). Dates are ISO-8601 strings. `GET` answers 405.

```json
{"websiteId":"<uuid>","type":"performance","filters":{},"parameters":{"startDate":"2026-10-26T12:22:13.742Z","endDate":"2026-11-02T12:23:13.742Z","timezone":"America/Los_Angeles","unit":"day"}}
```

`reports/performance` (Speed tab). `parameters.metric` (`lcp` default,
`inp`, `cls`, `fcp`, `ttfb`) picks what `chart`/`pages`/`pageTitles`/`devices`/
`browsers` show; `summary` always has all five. Milliseconds except `cls`.

```json
{"chart":[{"t":"2026-11-02 00:00:00","p50":1617.25,"p75":1808.625,"p95":1961.725}],"summary":{"lcp":{"p50":1617.25,"p75":1808.625,"p95":1961.725},"inp":{"p50":140,"p75":170,"p95":194},"cls":{"p50":0.1,"p75":0.125,"p95":0.145},"fcp":{"p50":1200,"p75":1350,"p95":1470},"ttfb":{"p50":210,"p75":255,"p95":291},"count":2},"pages":[{"name":"/blog/entry/hello","p50":1617.25,"p75":1808.625,"p95":1961.725,"count":2}],"pageTitles":[{"name":"Hello","p50":1617.25,"p75":1808.625,"p95":1961.725,"count":2}],"devices":[{"name":"laptop","p50":1617.25,"p75":1808.625,"p95":1961.725,"count":2}],"browsers":[{"name":"safari","p50":2000,"p75":2000,"p95":2000,"count":1},{"name":"chrome","p50":1234.5,"p75":1234.5,"p95":1234.5,"count":1}]}
```

With no data the percentiles are **0, not null**, and `summary.count` is 0:
check `count` before showing a speed number.

```json
{"chart":[],"summary":{"lcp":{"p50":0,"p75":0,"p95":0},"inp":{"p50":0,"p75":0,"p95":0},"cls":{"p50":0,"p75":0,"p95":0},"fcp":{"p50":0,"p75":0,"p95":0},"ttfb":{"p50":0,"p75":0,"p95":0},"count":0},"pages":[],"pageTitles":[],"devices":[],"browsers":[]}
```

`reports/utm` (Campaigns tab), same body with `"type":"utm"`:

```json
{"utm_source":[{"utm":"newsletter","views":2}],"utm_medium":[{"utm":"email","views":1}],"utm_campaign":[{"utm":"nov","views":2}],"utm_term":[],"utm_content":[]}
```

`views` counts pageviews carrying the parameter. There are **no visitor counts
per campaign** in this report.

Other report types the image accepts: `goal`, `funnel`, `journey`, `retention`,
`revenue`, `attribution`, `breakdown`, `heatmap`.

## The tracker (`/script.js`)

Attributes are read from the script tag as `data-<name>`:

| Attribute | Effect |
|---|---|
| `data-website-id` | required; no ID, no beacons |
| `data-host-url` | base for `<host-url>/api/send`; default is the script's own directory |
| `data-domains` | comma list; beacons only when `location.hostname` is in it. **Hostname only, no port**: `localhost:8083` never matches, `localhost` does |
| `data-performance` | `"true"` turns on Web Vitals (sends a `type: "performance"` beacon with `lcp`, `inp`, `cls`, `fcp`, `ttfb`, `duration`) |
| `data-auto-track` | `"false"` turns off automatic pageviews |
| `data-do-not-track` | `"true"` honours the browser's DNT |
| `data-exclude-search` / `data-exclude-hash` | `"true"` strips them from the URL |
| `data-tag`, `data-before-send`, `data-fetch-credentials` (default `omit`) | as named |

Beacons are `POST <host>/api/send` `{"type":"event"|"performance","payload":{"website","hostname","screen","language","title","url","referrer","tag","id"?, "name"?, "data"?}}`
with header `x-umami-website-id`. The response is `{"cache":"<jwt>","sessionId","visitId"}`.
Umami drops beacons from bot User-Agents (not separately pinned; the contract test sends a browser UA).

## What a visit stores (for the privacy text)

No IP address column and no cookie: the tracker sets nothing in the browser
(it only *reads* `localStorage["umami.disabled"]`; `script.js` has no `setItem`
and no `document.cookie`). Per Umami's design the session ID is a hash of the
website, the client IP (`CLIENT_IP_HEADER`, `X-Forwarded-For` in prod), the
User-Agent and a rotating salt, and the visit ID a hash of the session and a
time-bucketed salt; observed IDs are deterministic (UUID v5) and the same
browser's beacons share one session.

`session` (one row per visitor per salt period): `session_id`, `website_id`,
`browser`, `os`, `device`, `screen` (e.g. `1920x1080`), `language` (e.g. `en-US`),
`country` (ISO-2), `region` (ISO 3166-2), **`city`** (resolved from the IP by the
image's bundled geo database, then the IP is dropped), `distinct_id` (only if the
page calls `umami.identify`), `created_at`.

`website_event` (one row per pageview, custom event or performance beacon):
`event_id`, `website_id`, `session_id`, `visit_id`, `created_at`, `url_path`,
`url_query` (the full query string), `referrer_domain`, `referrer_path`,
`referrer_query`, `page_title`, `hostname`, `event_type` (1 pageview, 2 custom
event, 5 performance), `event_name`, `tag`, `utm_source`, `utm_medium`,
`utm_campaign`, `utm_content`, `utm_term`, ad click IDs (`gclid`, `fbclid`,
`msclkid`, `ttclid`, `li_fat_id`, `twclid`), and the vitals `lcp`, `inp`, `cls`,
`fcp`, `ttfb`.

`event_data` holds a custom event's properties (`data_key` plus a string, number
or date value), e.g. the `dest` URL of a `cta-click`. Session replay
(`session_replay`) stays empty unless a website enables `recorder_enabled`.

## Testing notes

- `UmamiContainer.get()` is one instance per JVM; test classes share it, so use
  distinct usernames and website names. `jdbc()` reaches Umami's own database,
  `psql(file, vars)` runs a SQL file the way the deploy one-shot does.
- Java's `HttpClient` must be pinned to `HTTP_1_1` against this image: the
  default HTTP/2 upgrade attempt on a POST gets an empty reply
  ("header parser received no bytes").
- `/api/send` needs a browser-like `User-Agent`. Seeded events land in real
  time, so read windows end a minute in the future.
