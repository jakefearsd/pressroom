# Owner decisions for 0.16.0: list envelopes, two statuses, contributor drafts (2026-11-21)

Jake answered the open questions from the overnight React-readiness run
(memory: React readiness; ledger `.superpowers/overnight-2026-11-20.md`,
"Jake's decisions"). Four of his answers need work; this spec holds them. All
four ship in 0.16.0, the release that already changes clients (Basic only on
`/v1/tokens`, problem codes). No tag is made by this work.

## Global constraints

- Test-driven, per CLAUDE.md. Every intended change has a test written first
  and seen to fail for the expected reason.
- Every CLAUDE.md gate applies, including the security-boundary JaCoCo package
  rule. Finish with `mvn clean install` green and the browser suite green at
  `/` and at `-Dit.context.path=pressroom`.
- The API contract from Tiers 2 and 5 holds: problem+json with a stable `code`
  (keyed `ApiException` factories only; `ProblemCodeCatalogTest`,
  `ProblemCodeDocsTest`), 404 never 403 for what the caller may not see, the
  authorization matrix (`ApiAuthorizationMatrixTest`, hand-written rows),
  operationIds pinned (`OpenApiContractTest`), caller kind asked of `Caller`
  only. A status change on an existing code updates its `ProblemType`, its
  README row and every test asserting that status.
- Every client-visible change is listed in `docs/api/README.md`'s 0.16.0 change
  notes and in `docs/dev/api.md`; MCP descriptions follow;
  `McpInstructions.TEXT` stays under 3,000 characters (about 2,995 today, so
  any added word is paid for by a removed one).
- Commit on master; never push.

## Approaches

Each task is small and has one obvious implementation, except Task 3. For
Task 3 the options were (a) a separate draft-only controller, (b) lowering
`EntriesWriteApi`'s declared permission to `EDIT_DRAFT` and enforcing the
status ceiling inside the write path, or (c) leaving the API POST-only.
Jake chose parity with the JSP editor, which rules out (c). (b) is chosen,
because the JSP editor works the same way (one controller gated on
`EDIT_DRAFT`, with the status decided from the caller's permission, see
`EntryEditController` and its `SaveIntent`), and a second controller would
duplicate the write path. The builder may choose (a) instead only if (b)
cannot keep every other route's matrix row unchanged; say why in the report.

## Task 1: One list shape for weblogs, users and media

This is the held part of Tier 5's Task 5
(`docs/superpowers/specs/2026-11-20-api-contract-design.md`, Task 5), now
approved. Build its acceptance criteria 1, 2, 5, 6 and 7 as written there.

- `GET /v1/weblogs`, `GET /v1/admin/users` and `GET .../media` answer the
  envelope `{items, offset, limit, hasMore}` with the record names that spec
  gives (`WeblogListPage`, `UserPage`, `MediaPage`).
- `OpenApiContractTest`'s `HELD_BARE_ARRAYS` exception set becomes empty and
  is removed with its comment.
- The README's "the page envelope is not uniform" sentence goes, and the
  0.16.0 notes name the three breaking endpoints.
- `bin/pressroom-api` and every it-selenium test that reads these three bodies
  as arrays are updated in the same change.

## Task 2: Two status corrections

- `redirect.sourceInUse` answers **409 Conflict** (was 400), like every other
  `*.inUse` code. Add a catalog rule: every `*.inUse` code is a 409.
- `comment.replyRateLimited` answers **429 Too Many Requests** (was 400). If
  the comment rate limiter knows how long the caller must wait, send
  `Retry-After` as the throttle does; if it does not, send no header and say
  so in the README row.
- English `detail` text is unchanged for both. Both go in the 0.16.0 notes.

## Task 3: Contributors write drafts over the API

**Rule.** A weblog member holding `EDIT_DRAFT` (a contributor) can do over
`/api/v1` and `/api/mcp` what the JSP entry editor lets them do, and no more.
Verify the editor's exact rules in `EntryEditController` and `SaveIntent`;
the expected parity is:
- create an entry, and update one they may edit, saved as `DRAFT`;
- "publish" from a contributor becomes `PENDING` (submitted for review), as the
  editor does;
- they may not publish, schedule, or change an entry's status to
  `PUBLISHED`/`SCHEDULED`;
- they may not edit an entry that the editor would not let them edit;
- they may not trigger anything the editor reserves for `POST` (for example
  the newsletter send).

The token role still applies: a `READ` token stays read-only. A contributor
needs a `POST`- or `ADMIN`-role token to write, because the token role is the
token's ceiling and the weblog permission is the person's.

**Acceptance criteria.**
1. Characterisation first: the JSP editor's outcomes for a contributor, for
   each case above, pinned before any change (most may already be pinned;
   list which tests pin them).
2. Over the API, a contributor with a `POST` token: create → 201 with status
   `DRAFT`; a create or patch asking for `PUBLISHED` or a future pubTime is
   saved as `PENDING` if that is what the editor does, otherwise refused with a
   specific code. Mirror the editor; state which in the report. Patching a
   published entry they may not edit → the editor's answer (403 with a code).
3. A contributor with a `READ` token → 403 on every write, as today.
4. Every other entry-write case for `POST` and `ADMIN` members is unchanged.
   The matrix rows for `EntriesWriteApi` change from "requires POST" to
   "requires EDIT_DRAFT" with the status ceiling. The matrix gains a cell
   proving a contributor cannot publish, which can't be a sentinel-only
   cell; use a focused dispatch test if the matrix's sentinel stops before
   the status decision.
5. MCP: the entries tool's write actions behave the same for a contributor's
   token (`McpToolExecutor` delegates to the API; one test per action family).
6. Trash, restore, delete-forever, revision restore and preview: each follows
   the JSP's rule for contributors. List each one's answer in the report, with
   its test. Where the JSP has no equivalent, keep `POST` and say so.
7. README: the entries section and the role table say what a contributor's
   token can do; 0.16.0 notes include it. `docs/dev/api.md` explains why one
   controller and how the ceiling is enforced.

## Out of scope

- New roles or permissions.
- Comment moderation by contributors, which stays as it is.
