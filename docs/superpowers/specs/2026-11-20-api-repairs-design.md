# API repairs: title round trip, body limits, owner settings (2026-11-20)

Three defects found while assessing the API as a future backend for a
browser admin UI. Each one already affects agents today. All three are
repairs; none adds a resource. Release: 0.15.0 (item 1 changes what the API
returns, which in 0.x warrants a minor bump).

## Global constraints

- Test-driven: each acceptance criterion gets a test written first and seen
  to fail for the expected reason before the fix.
- Every gate in CLAUDE.md applies (JaCoCo floors incl. the security-boundary
  package, PMD/CPD/SpotBugs zero, `-Xlint:all` zero warnings, i18n ratchets,
  SLF4J throwable-last). Finish with `mvn clean install` from the repo root,
  green.
- The API's error contract is unchanged: problem+json, 404 (never 403) for
  what the caller may not see, 403 for a member below the required level.
- Docs move with behaviour: `docs/api/README.md` (public), `docs/dev/api.md`
  (developer notes), MCP tool/field `@Schema` descriptions. `McpInstructions.TEXT`
  stays under its 3,000-character budget (2,296 now). Pinned surfaces that
  may need updating: `ApiRouteTableTest`, `ToolSchemasTest`,
  `McpToolCatalogTest`, `McpDocsTest`, `FormBodyParityTest`.
- Commit on master; never push.

### Task 1: Entry titles are plain text over the API, both ways

**Defect.** Entry titles are stored HTML-escaped (`EntryFieldRules.escapeTitle`,
a plain `escapeHtml4`, at save). `EntryDtos.toView` returns the stored value,
so `GET` answers `Tom &amp; Jerry`; a `PATCH` sending that back is escaped
again and stores `Tom &amp;amp; Jerry`. The JSP editor does not have this
bug because `EntryBean.copyFrom` unescapes for editing.

**Rule.** The API speaks plain text for entry titles in both directions.
Storage is unchanged (escaped once, at write). Page titles are already raw in
storage and over the API; leave them alone.

**Acceptance criteria.**
1. An entry whose stored title is `Tom &amp; Jerry &lt;3 &quot;x&quot;` is
   returned by `GET .../entries/{id}` with `title` = `Tom & Jerry <3 "x"`.
2. Round trip: `GET` an entry, `PATCH` its `title` with the value returned,
   and the stored title is byte-for-byte unchanged. Holds for titles with
   `&`, `<`, `>`, `"`, `'` and an already-literal `&amp;` typed by an author
   (stored `&amp;amp;`, returned `&amp;`, round-trips unchanged).
3. Every `/api/v1` and `/api/mcp` output that carries an entry title emits
   plain text. Find them by sweeping `ui.restapi` (and the MCP layer) for
   entry-title reads: at least the entry list/get/by-anchor views, revision
   views, preview, SEO audit rows, comment views that name their entry,
   traffic rows, strand parts, and anything else the sweep finds. Each
   surface found gets a test; list them in the report.
4. Any API operation that matches or compares against an entry title (edits,
   imports/duplicate checks, the `check` step, lint) compares plain text with
   plain text. If none does, say so in the report.
5. Docs: README and MCP descriptions state that entry titles are plain text in
   and out, and the README notes the change from earlier versions (clients
   that unescaped the old output should stop).

### Task 2: A maximum-length entry fits through every write path

**Defect.** `ColumnLimits.ENTRY_TEXT` allows 1,000,000 characters;
`ApiBodyLimitFilter.MAX_BODY_BYTES` is 2 MiB. A long entry in non-ASCII text
(3 bytes per character in UTF-8) is refused with 413 before the column limit
is ever checked. The JSP editor may have the same problem through Tomcat's
`max-http-form-post-size` (default 2 MB, and URL-encoding a 3-byte character
costs 9 bytes); this is unverified.

**Rule.** Any entry the column limit accepts must be accepted by every write
path's transport limit, and the transport limits are derived from the column
limit in code, so a future change to the column moves them.

**Acceptance criteria.**
1. A `POST`/`PATCH` to `/api/v1/.../entries` whose `text` is 1,000,000
   characters of a 3-byte BMP character (e.g. `中`), plus a normal title, is
   not refused by the body-size limit (test fails today with 413).
2. A body one byte over the new limit is still refused: 413 `too-large` when
   the length is declared; when it is not (streamed), the capped read fails
   and the answer is 400, as before this change and as the README documents.
   (Amended after the final review: the original text asked for 413 in both
   cases; making the streamed case 413 would be a separate behaviour change.)
3. `MAX_BODY_BYTES` is computed from `ColumnLimits.ENTRY_TEXT` (3 bytes per
   character plus a fixed headroom for the other fields and JSON framing),
   with the derivation in its javadoc, and a test pins the relationship.
4. `/api/mcp`'s own body limit is checked the same way: an MCP tool call
   carrying a maximum-length entry must not be refused for size. Fix and test
   if it is; report the numbers either way.
5. The JSP entry editor's save path: find out how it posts (urlencoded or
   multipart) and whether a maximum-length non-ASCII entry fits. If it does
   not, set the relevant Boot property from the same derivation and pin it
   with a test (a configuration test is enough; a real-container check only
   if the existing harness makes it cheap). Report what you found.

### Task 3: A weblog's owner can read and change its settings over the API

**Defect.** `GET`/`PATCH /api/v1/weblogs/{handle}` (`WeblogsApi`) require
`GlobalPermission.ADMIN`, while the JSP Weblog Settings and Theme pages
require only the weblog-level admin permission (the owner). An agent or
person that owns a blog, but is not a site admin, cannot change that blog's
settings or shared theme through the API.

**Rule.** The by-handle routes take the same permission the JSP settings page
takes (verify which: expected `WeblogPermission.ADMIN` on that weblog). The
list route `GET /api/v1/weblogs` stays site-admin only. The token-role
requirement is unchanged (`@AdminScoped`: an `ADMIN`-role token). Pins apply
as on every other weblog route: a token pinned to that weblog reaches it, one
pinned elsewhere gets 404.

**Acceptance criteria.**
1. A non-site-admin owner with an unpinned `ADMIN` token, and with an `ADMIN`
   token pinned to that weblog, can `GET` and `PATCH` it (settings and shared
   theme switch).
2. The same owner gets 404 on a weblog where they hold no role, and 403 on one
   where they are a member below the required level (the existing
   `PressroomHandlerInterceptor` rule; test both).
3. A token pinned to weblog A gets 404 on weblog B, whatever its owner's role.
4. A `READ`/`POST` token is still refused (403) on `PATCH`.
5. `GET /api/v1/weblogs` (the list) is still site-admin only.
6. Field parity with the JSP form: any setting the JSP form restricts to site
   admins stays restricted over the API for a non-site-admin caller (same
   refusal shape as other field errors). If the form restricts none, say so in
   the report.
7. The README's "Site administration" text that says a pinned token can never
   reach this route is rewritten; any MCP tool description that says these
   operations need a site admin is corrected.
