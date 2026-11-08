# Agent API parity: page SEO, traffic, theme

Date: 2026-11-08. Status: approved (Jake, 2026-11-08).

## Why

The MCP tools are generated from `/api/v1`, and `McpCoverageTest` holds every
route to a tool action. Nothing holds a request body to the admin form it
mirrors, so fields the UI gained never reached the API: a page's SEO card, and
a blog's theme after creation. Blogging agents also cannot see what readers
read: the Traffic page is UI-only although it reads a local snapshot and never
calls Umami.

## Scope

1. **Page SEO fields.** `PageWrite` and `PageView` gain what `PageBean` has:
   `navOrder`, `metaTitle`, `searchDescription`, `canonicalUrl`, `noindex`,
   `ogImageId`, plus a `clear` list (`metaTitle`, `searchDescription`,
   `canonicalUrl`, `ogImageId`).
2. **Blog traffic.** `GET /v1/weblogs/{handle}/traffic?period=` and an MCP
   tool `traffic` (read-only).
3. **Theme switch.** `WeblogPatch` gains `theme` (a shared theme id) and
   `WeblogView` reports it.
4. **Field-parity gate.** A test holding each admin form bean to the API body
   that mirrors it.

Out of scope: page SEO audits (an agent scans `pages list`, which now carries
`searchDescription`); switching to or importing a custom theme; the site
space's traffic; front-page promotion (not built yet, but the parity gate
makes its field land in the API with it).

## Acceptance criteria

### Page SEO fields
- P1. `pages get`/`list`/`create`/`update` responses carry `navOrder`,
  `metaTitle`, `searchDescription`, `canonicalUrl`, `noindex`, `ogImageId`
  (null-valued ones omitted, as today).
- P2. Create and PATCH accept them; a PATCH that omits a field leaves the
  stored value alone; naming a field in `clear` removes it; an unknown name
  in `clear` is a 400 field error on `clear`.
- P3. Validation matches the page editor and the entry API: `metaTitle` and
  `searchDescription` are trimmed and held to their column limits;
  `canonicalUrl` must pass the same validator `PageEditController` uses (an
  invalid one is a 400 on `canonicalUrl`); `ogImageId` must name an image of
  *this* weblog that readers can load, by the same rule `EntriesWriteApi`
  applies to `ogImageId` (another weblog's media id answers a 400 field
  error, never a stored foreign id).
- P4. A refused write lists every bad field in `errors`, as entry writes do.
- P5. The rendered page uses them: a page saved through the API with a
  `searchDescription` emits it as its meta description (one test through the
  real renderer or the existing page-render test harness).

### Traffic
- T1. `GET /v1/weblogs/{handle}/traffic` returns the blog's snapshot for
  `period` (one of `24h`, `7d`, `30d`, `90d`; default `30d`): `state`
  (`NOT_CONFIGURED`, `NO_DATA`, `STALE`, `OK`), `asOf`, `period`, current and
  previous summaries, the daily series, the posts (entry id, title, path,
  views, visitors, trend) and the breakdowns.
- T2. An unknown `period` is a 400 field error on `period` naming the valid
  ones (the UI silently falls back; the API does not).
- T3. It reads the local snapshot only; no request calls Umami (a test
  asserts the analytics client is never touched).
- T4. Only a blog admin may read it, the same permission as the Traffic page
  (`WeblogPermission.ADMIN`); a member with POST gets 403, a caller with no
  role on the weblog gets 404, as everywhere in `/api/v1`.
- T5. `lastError` (internal error text) is not exposed; `STALE` says enough.
- T6. A trend of `NaN` is serialised as null, never as an invalid JSON token.
- T7. MCP: tool `traffic`, read-only, action `get`; `McpCoverageTest` passes;
  `McpInstructions` gains one line telling an agent what it is for.
- T8. The site space is refused like any unknown weblog.

### Theme
- H1. `PATCH /v1/weblogs/{handle}` with `theme` switches to that shared theme
  by the same rule the theme page applies (`offered`: enabled, not the front
  page theme, not for the site space), saves, and expires the blog's rendered
  pages (`weblog.lastModified`, plus `CacheManager.invalidate` as the theme
  page does).
- H2. A theme that is not offered is a 400 field error on `theme` that lists
  the offered ids (as `weblogs create` does for a wrong theme).
- H3. `custom` is refused with a message saying custom themes are managed in
  the admin UI.
- H4. `WeblogView` carries `theme` (the current theme id).

### Parity gate
- G1. A test compares `EntryBean` with `EntryWrite`, `WeblogConfigBean` with
  `WeblogPatch`, and `PageBean` with `PageWrite`: every bean property must map
  to a body field (an explicit rename table, e.g. `content`->`text`) or appear
  in an exclusion table with a reason.
- G2. A stale exclusion or rename (naming a property that no longer exists on
  either side) fails the test.
- G3. Written first, it fails on today's `PageBean` (the six page fields) for
  that reason; the page work makes it pass.
- G4. `pinnedToMain` is excluded with its reason (Roller's old pin, ignored
  by the front page; promotion will be designed separately).

### Docs
- D1. `docs/api/README.md` documents the new fields, the traffic endpoint and
  `theme`; `docs/dev/api.md` names the parity gate next to `McpCoverageTest`.
