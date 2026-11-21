# API contract: error codes, a typed OpenAPI document, one list shape (2026-11-20)

Tier 5 of the React-readiness groundwork (`.superpowers/react-readiness-tiers.md`,
items 9-11). Three gaps make `/api/v1` harder to consume than it should be,
for agents now and for a generated TypeScript client later:

- **Errors carry English only.** A problem+json body has `type` (about 14
  coarse categories) and an English `detail`. An agent that needs to tell
  "entry is trashed" from "anchor taken" from "too many pins" has to match on
  English text. About 160 `ApiException` call sites in `ui.restapi` build that
  text by hand, many by string concatenation.
- **The OpenAPI document cannot drive a code generator.** No operation
  declares an error response. Five responses are typed as `Map`
  (`MetaApi.ping`/`me`, the three `AdminActionsApi` actions). Operation ids are
  springdoc's defaults (`list`, `list_1`, ...), which renumber when a
  controller is added. Most request fields and every enum lack descriptions.
- **List endpoints answer three shapes.** `entries` and `comments` answer
  `{items, offset, limit, hasMore}`. `audit/seo` answers
  `{total, counts, entries}` with no `hasMore`. `weblogs`, `admin/users` and
  `media` take `offset`/`limit` but answer a bare array, so a caller can only
  page until a short page arrives.

Release: these change the API contract. Tasks 1-4 are additive. Task 5 wraps
three bare-array lists in an envelope, which **breaks clients**. It ships in
0.16.0, the release that already breaks clients with the Tier 2 Basic-auth
restriction, so callers adapt once. This work does not tag a release.

## Global constraints

- Test-driven, per CLAUDE.md. Where a task must not change behaviour (the
  English text of every existing problem; the `/me` body), it starts with
  characterisation tests that pass on the old code, and their javadoc says so.
- Every CLAUDE.md gate applies, including the security-boundary JaCoCo package
  rule (v1, MCP, API auth: L 0.93 / B 0.89), the i18n ratchets
  (`MessageKeyTest`, `MessagePlaceholderContractTest`,
  `MessageFormatRegressionTest`; their offender sets stay `Set.of()`), PMD,
  CPD, SpotBugs and `-Xlint:all` at zero. Finish each task with
  `mvn clean install` green from the repo root.
- **Additive by default.** `type`, `title`, `status`, `detail`, `instance`,
  `errors[].field`, `errors[].message` and `currentContentHash` keep their
  meaning and their English text. New members are added. The one breaking
  change (Task 5) is named in the README's change notes.
- **English byte-for-byte.** With no `Accept-Language` header, or one naming
  English or a language with no bundle, every `detail` and field-error
  `message` is the same string as before this work. No existing assertion on
  a `detail`, a field-error `message` or `ApiException.getMessage()` is edited
  by any task. Exact whole-body assertions (for example
  `McpToolExecutorTest.OPAQUE_500`) change only by the added `code` member.
- **Cross-product check first.** Before Task 1, look for a suite-wide error
  code convention in the Wikantik `SimpleAgilityDecisionLog`. If one exists,
  adopt its member name and code format in place of the ones below and note
  it in `docs/dev/api.md`. This spec assumes there is none.
- **Tier 2 runs concurrently** (spec
  `2026-11-20-caller-kind-and-auth-matrix-design.md`). It rewrites
  `ui.restapi.auth`, `TokensApi` and part of `MetaApi`. No task here depends on
  its internals. Task 3 edits `ui.restapi.auth` and `MetaApi` only after Tier
  2's commits are on master. Task 4 rebases `/me` onto whatever body master
  then produces.
- Docs move with behaviour: `docs/api/README.md` (Errors, Pagination, the
  three wrapped lists, change notes), `docs/dev/api.md` (how to add a problem
  code; how operation ids are named), MCP `@Schema` descriptions.
  `McpInstructions.TEXT` stays under 3,000 characters (`McpInstructionsTest`).
  Pinned surfaces that may need updating: `ApiRouteTableTest`,
  `ToolSchemasTest`, `McpToolCatalogTest`, `McpDocsTest`, `EntriesOpenApiTest`,
  `OpenApiDocumentTest`, `McpToolExecutorTest`.
- Commit on master; never push.

## Approaches considered

**Where codes come from.**

1. **Codes are the JSP message keys** (the tier list's suggestion; for
   example `createWeblog.error.handleExists`). The API would reuse the existing
   translations. Rejected, for three reasons:
   - Almost no API message has a JSP twin. The API's text is written for
     agents and names API fields ("pubTime is required when status is
     SCHEDULED"). `PagesApi` even maps the business tier's
     `strand.error.inAnotherStrand` to API wording on purpose. Reusing JSP keys
     would change most `detail` strings and break the byte-for-byte rule.
   - Several JSP values carry HTML (`error.upload.filemax` has `<b>`), which is
     wrong in a JSON `detail`.
   - JSP keys get renamed and deleted in UI cleanups, and `MessageKeyTest`
     tells you to delete orphans. A wire contract cannot be tied to the
     admin UI's names, least of all while that UI is being replaced.
2. **A separate code namespace with English hard-coded in Java**, beside the
   existing literals. This gives stable codes but no localisation, and the
   i18n ratchets cannot see the text. Rejected.
3. **Chosen: stable codes in their own namespace, with text in the same
   bundle.** A `ProblemCode` enum holds each code. The enum maps the code to
   a message key in `ApplicationResources.properties`, by default
   `api.problem.<code>`. The code is the contract. The key is an
   implementation detail and may change without breaking a client. The text
   goes through the existing `MessageSource`, so translations work as soon as
   anyone adds them and the ratchets cover the new keys. Where an existing JSP
   key's English is byte-identical to the API's and has no markup, the enum
   may name that key and inherit its translations.

**How `total` and `hasMore` are settled.** (a) `total` on every list: every
call would need a filtered count query, including new business-tier count
methods for entry filters. (b) **Chosen: `hasMore` everywhere, `total` where
it already exists.** The JSP pagers (`Entries.jsp`, `Comments.jsp`,
`Submissions.jsp`) show only newer and older links, never a total, so the
tier list's condition for adding `total` ("if admin pagers show totals") does
not hold. `total` can be added later without breaking anyone. (c) Headers
(`X-Total-Count`) for the bare arrays: these do not break clients, but MCP
callers never see headers and generated clients handle them badly. Rejected.

## Task 1: Problem codes on the wire, and the localisation pipeline

**Rule.** Every problem body and every field error carries a `code`: a
stable, case-sensitive string of dotted lowerCamel segments
(`entry.trashed`, `request.invalid`). `type` stays the coarse category and
`code` refines it. A `ProblemCode` enum in `ui.restapi` lists every code with
its message key and its argument count. This task adds the generic codes, one
for each existing `type`:

`request.invalid`, `resource.notFound`, `auth.unauthorized`, `auth.forbidden`,
`resource.conflict`, `entry.changed`, `page.changed`, `server.internalError`,
`server.busy`, `request.tooLarge`, `request.unsupportedMediaType`,
`request.notAcceptable`, `request.throttled`, `upstream.failure`, and
`http.error` for the `about:blank` problems `McpToolExecutor.statusProblem`
makes.

Every existing factory that takes a literal `detail` attaches the generic code
for its type. Call sites do not change in this task. Their literal text stays
as it is and is not localised (it is "unkeyed").

A new factory, `ApiException.problem(ProblemCode, String... args)`, plus
field-error equivalents on `ApiException` and `FieldErrors`, builds a keyed
problem. Arguments are `String`s, so `MessageFormat` never formats a number
(`1000` would otherwise render as `1,000`). The call site formats numbers
exactly as the concatenation did. A count of arguments that differs from the
code's declared arity throws `IllegalArgumentException` when the exception is
built.

This task keys the problems whose text is fixed and does not depend on the
call site:
- the 500;
- the 406 and the 415;
- `busy`;
- `entry-changed` and `page-changed`;
- the malformed-body 400;
- the four type-mismatch sentences;
- the `Paging.requireRange` sentence;
- `Authentication required.`

**Rendering.** `getMessage()` stays the English text, for logs and for every
existing test, rendered from the base bundle. `ApiExceptionHandler` renders a
problem for the request:
- It reads the `Accept-Language` *header* itself, never
  `request.getLocale()`. Tomcat answers the JVM default when the header is
  absent.
- It matches the header against the shipped bundles (`de`, `es`, `fr`, `ja`,
  `ko`, `ru`, `zh-CN`) by RFC 4647 basic filtering in q order.
- When nothing matches, or the match is `en`, it renders with `Locale.ROOT`,
  so the result never depends on the JVM locale (`ResourceBundleMessageSource`
  falls back to the system locale for a locale with no bundle).
- A code with arity 0 is rendered without `MessageFormat`, as the JSPs do, so
  its apostrophes are single.

A multi-field 400 has the code `request.invalidFields`, and its `detail` is
the rendered field messages joined by one space, as `FieldErrors.throwIfAny`
joins them today. When a problem has exactly one field error, the problem's
`code` is that field error's `code`. `title` is not localised. Problems
written outside the advice (`ApiProblemWriter` and its callers in `auth/`)
gain `code` through `toProblem` in this task and are localised in Task 3.

**JSON shape.** `code` is the last member of the problem and of each field
error, so earlier members serialise exactly as before:
`{"type","title","status","detail","instance","errors","currentContentHash","code"}`
and `{"field","message","code"}`. `code` is never null on a body this API
builds.

**Acceptance criteria.**
1. Every problem body under `/api/**` built by `ApiExceptionHandler`,
   `ApiProblemWriter` or `McpToolExecutor` has a non-null `code`. Tests cover
   at least the 400, 401, 403, 404, 406, 409, 413, 415, 429, 500, 502, 503 and
   MCP `about:blank` paths. Each field error has a non-null `code`.
2. `ProblemCodeCatalogTest` pins the full set of code strings as a literal
   `Set.of(...)` compared by equality. Removing or renaming a code fails it,
   and adding a code means editing the pin, which is deliberate.
3. The same test asserts four things for every `ProblemCode`:
   - its key exists in the base bundle;
   - the key's value has no `<`, so the text is never markup;
   - the declared arity equals the number of distinct `{n}` placeholders in
     the base value, and in every translation that defines the key;
   - every base-bundle key under `api.problem.` belongs to some `ProblemCode`,
     so an orphan fails here.

   `MessageKeyTest.dynamicallyAddressedKeys` learns the `ProblemCode` keys, so
   its orphan ratchet stays `Set.of()`.
4. Characterisation tests, written first and passing on the old code, pin the
   exact English `detail` of each problem this task keys. They pass unchanged
   afterwards, both without `Accept-Language` and with `Accept-Language: en-GB`.
5. `ApiException.problem(code, args)` with the wrong argument count does not
   change the answer's status: it logs a WARN naming the code and renders the
   message with the arguments it has (controller ruling, 2026-11-20: the status
   is the decision, the text is display; a throw would turn an untested 4xx
   path into a 500). A test enumerates every `ProblemCode` and asserts its
   declared argument count equals the placeholder count of its English text. A code whose text contains a number, rendered
   with the argument `"1000000"`, gives `1000000` and never `1,000,000`.
6. Localisation: with a test `MessageSource` holding a French value for a
   keyed code, `Accept-Language: fr-CA,en;q=0.5` renders the French `detail`,
   and `Accept-Language: xx, *` renders the English one. Run with the JVM
   default locale set to German, a request without the header still renders
   English. Unkeyed literal details stay English whatever the header says.
7. An MCP tool error carries the same problem as REST plus `code`. It is
   always English, because the in-process `DispatchedRequest` exposes no
   `Accept-Language` (and a test pins that it never does).
8. README "Errors" documents `code`:
   - Branch on `code`, never on `detail`.
   - `detail` may be localised via `Accept-Language` and is plain text.
   - Bodies from Boot's own `/error` (the unmapped-path 404, the 405, the
     multipart 413/415) are not problem bodies and have no `code`.

   It also has a table of codes (code, status, type, meaning). A test asserts
   that every `ProblemCode` appears in that table and that the table names no
   other code. `McpInstructions.TEXT` gains at most one short sentence saying
   errors carry a stable `code`.

## Task 2: Specific codes for entries, pages and revisions

**Rule.** Every unkeyed literal problem or field error in the content routes
becomes keyed with a specific code: `EntriesApi`, `EntriesWriteApi`,
`EntryContent`, `EntryEdits`, `EntryFields`, `EntryImports`,
`RevisionedContent`, `PagesApi`, `PageContent`, `BaseApiController`,
`ColumnLimits`, and the DTO validators `EntryDtos`, `PageDtos`, `SeoFields`,
`ClearFields`.

A code names a meaning, not a call site. `No such entry.` in five places is
one code, `entry.notFound`. `<field> is required.` is one code,
`field.required`, with the field as `{0}`. The bundle's English for each code,
with the call site's arguments, reproduces the old literal exactly. Watch
the `MessageFormat` traps: a literal apostrophe in a value with placeholders
is written `''`, and a literal `{` is quoted.

Some text is not ours: a business exception's message passed through, such
as `RedirectsApi`'s `refusal.getMessage()` or `EntryImports`' `": " +
e.getMessage()`. Map it to a finite set of codes where the business tier's
messages are finite. Otherwise use a code whose base value is `{0}`, and list
that code in a pinned `PASSTHROUGH_CODES` set. That set may only shrink in
later work.

**Acceptance criteria.**
1. After this task, a source scan test finds no call in these files to an
   `ApiException` factory or a `FieldErrors.add*` that takes a literal or
   concatenated `String` detail. The test names the files it covers, and a
   guard asserts that the scan found these files.
2. No existing assertion on a `detail`, a field-error `message` or a
   `getMessage()` in the test tree is edited. `git diff` of the test tree
   shows only additions.
3. For each new code, at least one REST test asserts the `code`, and for
   field errors the `errors[].code`, that its route answers. One test for each
   multi-field route asserts `request.invalidFields` with each field's own
   code.
4. `ProblemCodeCatalogTest`'s pin and the README code table are extended. Both
   stay green.
5. `MessageFormatRegressionTest` and `MessagePlaceholderContractTest` stay
   green with their offender sets empty.

## Task 3: Specific codes for the rest of the API; retire unkeyed details

**Rule.** As Task 2, for the remaining routes and layers:
- `MediaApi`, `MediaImports`, `MediaDtos`, `CategoriesApi`, `CommentsApi`,
  `RedirectsApi`, `TokensApi`, `AdminApi`, `AdminDtos`, `AdminAccountGuard`,
  `WeblogsApi`, `WeblogSettingsApi`, `WeblogCreateApi`, `AuditApi`,
  `TrafficApi`, `MetaApi`;
- the MCP layer's own refusals (`McpToolExecutor`, `McpEndpointServlet`), with
  codes under `mcp.`;
- the `ui.restapi.auth` writers (`ApiAuthenticationEntryPoint`,
  `ApiAccessDeniedHandler`, the throttle in `ApiTokenAuthFilter`).

The `auth/` writers become locale-aware through an `ApiProblemWriter`
overload that takes the request and the `ApiException`. Start this task only
once Tier 2's commits are on master, and key whatever text those classes then
hold. Then **delete** every `ApiException` factory and constructor that takes
a `String` detail, so the compiler refuses an unkeyed problem from now on.

**Acceptance criteria.**
1. `ApiException` has no public factory or constructor taking a free-text
   detail. The Task 2 source scan widens to all of `ui.restapi` and
   `PressroomHandlerInterceptor`'s API refusals (any problem body it writes)
   and finds nothing.
2. Criteria 2-5 of Task 2 hold for these files.
3. The 401 and 403 from the security chain, and the 429 from the throttle,
   carry their specific codes. With a test `MessageSource` holding French
   values, they render the French `detail` for `Accept-Language: fr`.
4. `PASSTHROUGH_CODES` is pinned at its final size, and the report lists each
   code in it with the reason.
5. `McpInstructions.TEXT` is under 3,000 characters.

## Task 4: An OpenAPI document a generator can use

**Rule.**
- *Untyped responses.* `/v1/ping`, `/v1/me` and the three admin actions answer
  records instead of `Map`s, with JSON byte-identical to today's. `/me`'s
  `tokenScope` must still serialise as `{}` for a non-token caller: a
  `NON_NULL` record whose members are both null.
- *Error responses.* An `OpenApiCustomizer` adds a `default` response to every
  operation, typed `application/problem+json` against an `ApiProblem` schema.
  That schema carries descriptions and marks `type`, `title`, `status` and
  `code` required. Its `code` property is a plain `string`, not an `enum`, so
  an older generated client does not reject a new code. It carries
  `x-extensible-enum` listing every `ProblemCode`.
- *Operation ids and tags.* Every operation gets an explicit, unique
  `operationId` of the form `<resource><Verb>` (for example `entriesList`,
  `entriesUpdate`, `pagesRestoreRevision`), and one tag per controller.
- *Media type.* `springdoc.default-produces-media-type=application/json`, so
  success responses stop being `*/*`.
- *Descriptions.* Every enum-typed property and query parameter, and every
  request-body property, gets a non-empty description.
- *MCP tools.* These take their argument descriptions from the same
  `@Schema`/`@Parameter` text (`ArgumentDeriver`, `ToolSchemas`). A new
  description on an argument that several actions share can change which text
  the tool carries ("a schema carrying a description replaces one without").
  Keep the deliberate omissions, such as `MediaApi.list`'s `directoryId`.

**Acceptance criteria.**
1. `OpenApiContractTest` generates the whole document, with every
   `ui.restapi.v1` controller registered. It uses the `EntriesOpenApiTest`
   harness with mocked collaborators, or an existing full-context base if that
   is cheaper; say which. For **every** operation it asserts:
   - a 2xx response whose content is `application/json` with a schema that is
     a `$ref` or an array of `$ref`s, or a 204 with no content;
   - no schema anywhere that is a bare `object` with only
     `additionalProperties`, except request bodies the API defines as open
     maps (today only `PATCH /admin/config`), which the test lists by
     operationId;
   - a `default` response referencing `ApiProblem` as
     `application/problem+json`.
2. The same test pins the set of operationIds by equality, so renaming one
   fails it. It also asserts that ids are unique and none ends in `_<digit>`.
3. The same test asserts that every enum property and parameter, every
   request-body property and every `required` property has a non-empty
   description. It also asserts that `ApiProblem.code`'s `x-extensible-enum`
   equals the `ProblemCode` set.
4. A guard asserts that no two distinct Java types in `ui.restapi.dto` or
   `ui.restapi.v1` share a simple name. springdoc names schemas by simple name
   and silently merges a collision.
5. Characterisation tests, written first against the old `Map` code, pin the
   exact JSON of `ping`, `me` (token caller, pinned token, non-token caller)
   and each admin action. They pass unchanged on the records, except that the
   admin actions' member order is fixed as `action`, `weblog`. `Map.of`'s
   order was never defined.
6. `ToolSchemasTest`, `McpToolCatalogTest` and `McpDocsTest` stay green. Any
   edit to their pins is a deliberate description change named in the report.
   `EntriesOpenApiTest` stays green.
7. `docs/dev/api.md` states the operationId convention and that a new
   operation must extend the pin.

## Task 5: One list shape

> **Controller ruling (2026-11-20, overnight run): the three breaking wraps are
> held for Jake.** Wrapping `GET /v1/weblogs`, `GET /v1/admin/users` and
> `GET .../media` changes what live agents parse, and that is his call, not an
> overnight one. This run builds only the additive parts: `audit/seo` gains
> `offset`/`limit`/`hasMore` (AC3), and the OpenAPI pin (AC4) covers the
> operations that already answer an envelope, with the three bare-array lists
> named in an explicit, commented exception set that the wrap would empty.
> ACs 1, 2, 5, 6 and 7's breaking parts wait. Cost if wrong: one more small
> task in the morning.

**Rule.** Every operation that takes `limit`/`offset` answers the envelope
`{items, offset, limit, hasMore}`. `offset` is the requested offset. `limit` is
the effective page size after the cap of 200. `hasMore` is decided by
fetching one row past the page, or from the in-memory list where the endpoint
already holds it. An operation that returns a complete, unpaged set (pages,
categories, tags, tokens, redirects, revisions, directories, config) keeps its
bare array: its length is the total, and it has no more.

- `GET /v1/weblogs`, `GET /v1/admin/users` and `GET .../media` change from a
  bare array to the envelope. **This breaks clients.** The new records are
  named so they collide with no existing schema (not `WeblogPage`, which is
  the page entity): `WeblogListPage`, `UserPage`, `MediaPage`.
- `GET .../media` with no `limit` still returns every file. In that case
  `limit` equals the number of items and `hasMore` is false.
- `GET .../audit/seo` keeps `total`, `counts` and `entries`, and gains
  `offset`, `limit` and `hasMore` (additive). It keeps `entries` rather than
  `items`, because it is a report with a tally, not a plain list.
- `EntryPage` and `CommentPage` are unchanged. No list gains `total`.

**Acceptance criteria.**
1. For each of the three wrapped lists, a test asserts the envelope:
   - a first page with `hasMore: true`;
   - a last page with `hasMore: false`;
   - `limit=500` reported as `200`;
   - `media` without `limit` returning every file with `hasMore: false`.
2. The `users` and `weblogs` `hasMore` comes from one query of `limit + 1`
   rows, with no count query. A test with a mocked manager pins the length
   requested.
3. `audit/seo` answers `offset`, `limit` and `hasMore` correctly at the first,
   middle and last page. `total` and `counts` are unchanged (existing tests
   pass unedited).
4. `OpenApiContractTest` (or, if Task 4 has not landed, a test in this task
   that Task 4 then folds in) asserts that every operation with a `limit`
   query parameter answers a schema whose properties include `items` (or
   `entries` for `audit/seo`), `offset`, `limit` and `hasMore`, marked
   required.
5. The MCP list actions for media, users and weblogs return the envelope. Any
   MCP description or `McpInstructions.TEXT` wording that implies an array is
   corrected, and `TEXT` stays under 3,000 characters.
6. `bin/pressroom-api` has no command that depends on those three bodies being
   arrays (check for `.[]`-style jq paths). Fix any that does, and say so in
   the report.
7. README: the "Pagination" section describes the one shape and drops "the page
   envelope is not uniform". The `media`, `users` and `weblogs` sections show
   the envelope. A "Changes in 0.16.0" note names the three breaking
   endpoints.

## Order and parallelism

Task 1 comes first. Tasks 2 and 5 touch different files and may run side by
side, in worktrees pinned per `docs/dev/build-and-ci.md`. Task 3 follows Task 2
(both extend `ProblemCode`, the pin and the bundle) and waits for Tier 2. Task
4 runs last, because it pins the final operation set, the list schemas and the
code catalogue.

## Decisions made while drafting

1. **Codes are their own namespace, not JSP keys** (see Approaches). If wrong,
   the cost is that existing JSP translations are not inherited. The mitigation
   is in place: a `ProblemCode` may name a JSP key whose English matches.
2. **No translations are shipped.** Translations may lag (`MessageKeyTest`'s
   rule). Localisation is proven with a test `MessageSource`. If wrong, a
   non-English caller sees English until a translator adds
   `api.problem.*` values, with no code change needed.
3. **`title` is not localised.** RFC 9457 allows it, but clients may already
   match on it. If wrong, it can be added later via the same pipeline.
4. **`code` is the last JSON member.** This minimises churn in exact-string
   tests. If wrong, the cost is cosmetic.
5. **A single field error lifts its code to the problem; several give
   `request.invalidFields`.** If wrong, clients that read only the top-level
   code see a less specific value for multi-field 400s, and `errors[].code`
   still carries the detail.
6. **A wrong argument count throws at construction** (a 500 at runtime) rather
   than rendering a broken message. Display paths may be lenient under
   CLAUDE.md, but the coverage floors mean every API error path runs in a test,
   so the throw fails a test first. If wrong, an untested path answers 500
   instead of its 4xx.
7. **No `Content-Language` and no `Vary: Accept-Language`.** A matched
   language can still fall back to English key by key, so a
   `Content-Language` would sometimes be false, and problem responses are not
   cached. If wrong, either header can be added later without breaking
   anyone.
8. **MCP errors are always English.** The in-process request hides client
   headers by design. If wrong, forwarding one header is a small change.
9. **`hasMore` everywhere, `total` only on `audit/seo`.** No admin pager shows a
   total. If wrong, adding `total` later is additive, but needs filtered count
   queries for entries.
10. **The three bare-array lists are wrapped, a break, in 0.16.0.** Bare arrays
    cannot carry `hasMore`, and headers do not reach MCP. If wrong, external
    scripts that index those bodies as arrays break. Shipping with Tier 2's
    break limits this to one migration.
11. **`ApiProblem.code` is a `string` with `x-extensible-enum`, not an `enum`.**
    This keeps old generated clients from failing on new codes. If wrong,
    generators that ignore the extension produce an untyped `string`.
12. **Unpaged complete lists stay bare arrays.** If wrong, a React grid wanting a
    uniform envelope wraps client-side. Wrapping them would break nine
    endpoints for no gain.

## Out of scope

- Shipping translations of `api.problem.*` keys.
- Problem bodies for Boot's own `/error` responses (the unmapped-path 404, the
  405, the multipart 413/415). These stay plain `application/json` with no
  `code`, as documented.
- `total` on entries, comments, users, weblogs or media.
- Marking every always-present response property `required` (beyond
  `ApiProblem` and the page envelopes). Generated clients treat the rest as
  optional, which is safe.
- MCP `outputSchema`s on tools.
- Generating or publishing a TypeScript client.
- Typing the open-map request body (`PATCH /admin/config`).
- Anything in Tier 2's scope (caller kind, Basic restriction, the
  authorization matrix).
