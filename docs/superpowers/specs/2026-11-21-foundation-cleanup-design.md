# Foundation cleanup: the React-readiness backlog (0.16.3, 2026-11-21)

Jake asked for one cleanup pass over the minor items the 0.15.0/0.16.x reviews
deferred, released as a patch, so the next piece of work (session access to
`/api` for a React admin) starts from a clean base. Sources: the deferred and
parked lines in `.superpowers/tier{2,3,4,5}-ledger-archive.md`,
`.superpowers/overnight-2026-11-20.md`, and the 0.15.0 follow-ups in memory.

## Global constraints

- **A patch release: no client-breaking change.** No problem code is renamed,
  removed or changes status, except where this spec names one. No route,
  operationId, MCP action name or response field is removed or renamed. A CLI
  command may gain a name; its old name keeps working.
- **Check before fixing.** Later fix waves fixed some of these. For each item,
  first confirm it is still open; if it is not, record "already fixed by
  <commit or file:line>" in the report and move on. Never invent work.
- Test-driven per CLAUDE.md: each behaviour change gets a test seen to fail
  first. Pure refactors (import order, renames of tests, moving a class) need
  no new test but the suite stays green.
- Every CLAUDE.md gate applies (JaCoCo floors incl. the security-boundary
  package rule, PMD/CPD/SpotBugs zero, `-Xlint:all`, i18n ratchets,
  `ProblemCodeCatalogTest`, `ProblemCodeDocsTest`, `OpenApiContractTest`,
  `ApiAuthorizationMatrixTest`). Never two `mvn -pl app test` at once.
- Client-visible changes (any status, any README-documented text, the CLI)
  go in `docs/api/README.md`'s change notes under a new 0.16.3 heading.
  `McpInstructions.TEXT` stays under 3,000 characters.
- Commit on master; never push; never `--amend`.

## Out of scope (decided, not deferred by accident)

- Renaming the near-duplicate codes (8 `entry.`/`page.` pairs;
  `auth.notAuthenticated` vs `auth.unauthorized`): codes froze at 0.16.0. Task 2
  documents the alias instead.
- `TokensApi` checking the caller before body validation: unreachable through
  the filter chain.
- `SITE_LOGIN` vs `NO_GLOBAL` matrix cells being indistinguishable: no caller
  lacks the `weblog` global role, so no test can tell them apart.
- Splitting the 810-line matrix test: churn without a defect.
- Contributors reading private media directories over the API: Jake's
  editor-parity ruling; already reported to him.
- Old category names with an unclosed `<`: none exist in production
  (checked 2026-11-21).

## Task 1: Small behaviour fixes in the app

Each item: confirm open, failing test, fix.

1. **Over-limit streamed body is 413.** A request body over the API/MCP limit
   (`ColumnLimits`-derived) that arrives streamed (no or a lying
   `Content-Length`) answers 400 today; it answers **413** with the same code
   the declared-length case uses. 0.16.3 note.
2. **The JSP Categories screen accepts an unchanged legacy name.** 0.16.0 made
   category names strict on both surfaces, but the API lets a PATCH send back
   the category's current name unchanged even when that name predates the
   rule. Production has 24 names containing `&` ("Food & Open-Air Markets");
   editing any of them on the Categories screen (say, the description) is
   refused today. The screen accepts the unchanged name as the API does, and
   still refuses a new or changed name that breaks the rule. Use the shared
   rule class both surfaces already call; no second copy of the rule.
3. **`RedirectRefusedException` with null args** does not NPE (`args.clone()`).
4. **`image.scanRefused`** is raised through a keyed `ApiException` factory /
   `ProblemCode`, not a free-text literal (`ExternalImages` around line 259).
   Same status and code on the wire.
5. **Members list** (`GET .../members`): `offset` is echoed clamped, as every
   other list does (check `ListPage`/house style first); a disabled account's
   membership has a test pinning what the list shows; the stale-membership
   warning logs once per membership per process (or at debug), not on every
   list call.
6. **`ApiPrincipal` resolves an unauthenticated `Authentication` as
   unauthenticated** (check `isAuthenticated()` before the token check), if and
   only if a test shows no request reaching it through the chain changes
   answer. If any does, leave it and explain in the report.
7. **Message text derives its numbers.** `slugTooLong` takes the limit from
   `ColumnLimits` (not a literal 255); `revision.keepLimit` takes its 10 as a
   placeholder argument from the constant; strand-parts and pin-limit message
   arguments are formatted with grouping again (1,000) while still escaped.
   `MessagePlaceholderContractTest` and friends stay green with empty
   offender sets.
8. **`PageRules` does not log a raw slug**: user input in a log line is
   sanitised the way the rest of the codebase does it (CR/LF stripped or
   quoted), so a slug cannot forge log lines.
9. **Wrong password on `/api/v1/tokens`** answers problem+json with a code
   and keeps the `WWW-Authenticate` challenge. (Probably fixed by the Tier 2
   final fix wave: verify and record.)

## Task 2: Contract, OpenAPI, MCP and documentation accuracy

1. **Multipart media upload** is documented in OpenAPI as a
   `multipart/form-data` request body, not a query parameter. The operationId
   and path are unchanged (`OpenApiContractTest`).
2. **`EntriesOpenApiTest`** asserts the real media type, not `*/*`; if the
   spec still says `*/*` for an entries response, fix the annotation.
3. **No swagger annotation in the business tier**: `business/RevisionSweep`
   (and anything else under `business/` or `pojos/`) imports no
   `io.swagger` type. Move the `@Schema` to the API-facing record, and add an
   architecture test that fails if a `business`/`pojos` class imports
   `io.swagger`.
4. **The `weblogs` MCP tool's shared argument text** no longer says "defaults
   to the user's own" where it is used by update; create keeps it.
5. **CLI names.** `bin/pressroom-api` gains `weblogs get` / `weblogs patch`
   (the owner's own weblog, matching the API); `admin weblogs get/patch` keep
   working as aliases. Its help text and the README say which to use.
6. **README fixes** (`docs/api/README.md`): the clause around the weblogs
   routes that says `/api/v1/weblogs` needs the ADMIN role, which is wrong for
   `POST /api/v1/weblogs` (POST role); the pinned-list text (verify it says
   403 where the answer is 403); the 401 row for `/v1/tokens`; an alias note
   saying `auth.notAuthenticated` and `auth.unauthorized` both mean 401 and
   clients should treat them alike; wrap the long unwrapped lines (~1526).
7. **Dev docs**: `docs/dev/api.md` around line 225 ("Only ApiExceptionHandler
   localises") matches the code today; `ApiTokenAuthFilter` around line 97 has
   one parenthetical, not two; the matrix test's javadoc says servlet filters
   outside Spring Security are not dispatched by it.

## Task 3: Unit-test and main-code hygiene

No behaviour change; the suite and every gate stay green.

1. `ProblemCodeCatalogTest`'s placeholder regex does not count a quoted
   `'{0}'` (MessageFormat literal) as a placeholder; add a case proving it.
2. The keyed-factory scan also catches a three-or-more-argument
   `FieldErrors.add` with a variable argument, a static import of the
   free-text factory, and a public `ApiProblem` constructor call /
   `ApiProblemWriter.write(resp, ApiProblem)`; each with a planted-offender
   test like the existing ones.
3. A test pins that every `Violation`'s status agrees with its code's
   `ProblemType` (one source of truth, or the two cannot drift).
4. `ListShapeOpenApiTest` names the operation and the shape it found when it
   fails on an unexpected response shape; `AuditApiTest` covers an offset
   beyond the total; `UntypedResponsesJsonTest` characterises a null
   `screenName`.
5. Test names and duplication: rename
   `ApiPasswordScopeTest.aTokenStillReachesTheTokenResourcesOwnRefusal` to
   what it shows; `TokensApiTest` uses imports, not inline FQCNs; the
   duplicated three-request block in the two Tier 2 tests becomes one helper;
   the matrix's MCP token cells assert something stronger than "404 no
   handler" where a stronger answer exists.
6. `Caller.pin()`: removed if still unused in main.
7. `ui.view` imports nothing from `ui.controllers`
   (`WeblogPermissionView` moves to `ui.view` or the import goes);
   `ScreenIndependenceTest` gains that rule. The Menu's literal `"admin"` uses
   the named constant.
8. The `RuleProblems` duplication (Tier 3 M2) and the `ProblemCode` sentinel
   in `checkSlug` (M1) are cleaned up: one mapping, no sentinel value standing
   in for "no problem".
9. Import order in the files the reviews named (`SecurityConfig`, `AdminApi`,
   the ~14 `ProblemCode` importers, the `Schema` imports): fix only where the
   file is already being touched or the fix is mechanical; no new gate.

## Task 4: Browser-IT hygiene

Runs the browser suite at `/` and at `-Dit.context.path=pressroom` at the end.

1. `AdminToken.mintResponse()` removed if still unused.
2. `McpIT` and `SsoSignInIT` use `AdminToken`'s mint/revoke instead of their
   own Basic helpers.
3. `ApiIT`'s path-trick cases include `/api/v1/tokens//../me`.
4. `McpIT` takes the body cap from one place (the same formula the app uses,
   or a single shared constant in the IT module) instead of repeating it.
5. An end-to-end MCP test creates an entry at the maximum body length and
   reads it back intact, and one byte over is refused with the documented
   code.

## Release

After the final review and its fix wave: `mvn clean install` green, the
browser suite green at both context paths, then `bin/release.sh 0.16.3`
(tag locally). Push and deploy only when Jake says so.
