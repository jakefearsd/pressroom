# Agent editing and SEO — design

Date: 2026-10-24. Design approved in conversation the same day ("go with
your recommendations, start wave A"). Answers `roller_mcp_editing_and_seo_feedback.md` (the
Antigravity agent's report after its editing pass over the 7-part *Wikantik
Post-Fork Evolution* series). Every claim in that report was checked against
the code before it was turned into work; several were already true of
Roller, one was wrong, and some are rejected. Jake took every recommended
option; the choices are marked **[Jake]**.

## Problem

| # | Report asks for | Finding (checked against the code) | Outcome |
|---|---|---|---|
| 1.1 | Patch edits instead of full-document overwrite | Half wrong. `entries update` is already a merge PATCH: null means absent (`EntryDtos.java:159-162`, `applyWrite`), so a metadata-only update sends only `searchDescription`. True for **text**: the body can only be replaced whole. | `edits` (find/replace pairs) on update. |
| 1.1 | Concurrency safety | True. No ETag, no version, no precondition; `updateTime` is a plain column; update is last-write-wins. | `contentHash` / `expectedContentHash`, as Wikantik's `update_page`; 409 on mismatch. |
| 1.2 | Address entries by anchor | True. Every single-entry route is by id (`BaseApiController.requireEntry`); `list` has no anchor filter, only a `LIKE` text search. | `entries get` takes `anchor` in place of `id`. |
| 1.3 | Smaller responses | True. `get`, `create`, `update` and every `list` item return the full `EntryView`, full `text` included. No projection anywhere. | `fields` projection. |
| 1.4, 3.1 | SEO diagnostics, `audit_seo` | Partly exists and the agent did not find it: the `audits` tool's `seo` action (`GET /audit/seo`) lists entries with a blank search description, blank meta title, no featured image, or `noindex`. No lengths, no body checks, no per-entry view. `entries preview` lints only inline colours and unlabelled code blocks. | One rule set, three surfaces (§6). Keyword density, readability grade, the 0–100 score: rejected. |
| 3.2 | Blog-wide metadata-only listing | Partly exists (`audits seo`); otherwise met by `fields` on `list`. | `fields` (§2). |
| 3.3 | `TechArticle`; custom JSON-LD | `JsonLdType` has BLOG_POSTING, TOURIST_ATTRACTION, TOURIST_TRIP, EVENT, FAQ_PAGE; the column is a STRING enum, so a new value needs no migration. No custom JSON-LD facility, by design (the sanitizer strips `<script>`). | `TECH_ARTICLE`. `jsonLdCustom`: rejected. |
| 4 | Revisions, diff, rollback | Mostly exists, web UI only. Every title/text/summary change writes a full snapshot (`weblogentry_revision`, V010), from the editor, bulk actions, REST and MCP alike; the editor rail lists, diffs (revision vs current, `TextDiff`) and restores. Not in the API. **Bug:** the revision's `creator` is the entry's author (`JPAWeblogEntryManagerImpl:300`), not whoever saved, so an agent's edits are attributed to Jake. | Fix attribution; expose list/get/diff/restore (§5). |
| 1.1 B | Update a section by heading | `edits` covers it without a second addressing scheme over Markdown headings. | Rejected. |

Also found, not in the report:
- `docs/api/README.md:358` says a PATCH that changes the anchor can 503;
  the code refuses any anchor change with a 400 (`EntriesWriteApi:138-140`).
- A whitespace-only `searchDescription` is "missing" to the audit
  (`isBlank`) but rendered as `<meta name="description" content="   ">`
  (`#showSeoHead` uses `isNotEmpty`; `AuditDtos.gapsFor` javadoc).

## Decisions

- **All additive.** `/api/v1` is a cross-product contract (CLAUDE.md, SA
  decision log): no existing field, default or status changes meaning. A
  caller that sends none of the new arguments sees today's behaviour. Each
  new route gets its MCP `ActionDecl` in the same change (`McpCoverageTest`,
  `McpDocsTest`); every new argument has one type across actions
  (`ToolSchemas`). 404, never 403, for anything the caller may not see.
- **Edits are exact-string, like the Artifact/Edit tools.** Rejected:
  regex (a pattern an agent gets subtly wrong edits the wrong place
  silently), and heading-scoped sections (ambiguous with repeated or
  renamed headings; `edits` with enough context does the same job).
- **Optimistic locking follows Wikantik.** The stack already has one
  scheme: `read_page` returns `contentHash` (SHA-256, bare lowercase hex,
  of the raw text) and `update_page` takes `expectedContentHash`. Roller
  uses the same names and the same hash, over the entry's stored `text`, so
  an agent working across the stack learns it once. Optional here, unlike
  Wikantik: making it required would break every existing caller. Rejected:
  `expectedUpdateTime` (a second scheme, and timestamp precision to agree
  on).
- **Anchor addressing on `get` only** [Jake]. An agent reads
  an entry before it edits it, and the read returns the id. `update` by
  anchor collides with `update`'s existing body argument `anchor`
  (create-only; a change is 400): the by-anchor route would need the path
  value and body value to be one argument, which `ArgumentDeriver` refuses
  today. Alternative: by-anchor twins for `update`/`trash`/`preview` too,
  with that deriver change.
- **The audit reports findings, not a score.** A number invites optimising
  the number. Keyword density and Flesch–Kincaid are rejected: neither is a
  ranking signal worth a rule, and FK is English-only on a multi-locale
  platform. "External links need `rel=noopener`" is rejected: browsers imply
  `noopener` for `target=_blank`, and the sanitizer already adds it where a
  target survives.
- **`TechArticle` replaces BlogPosting** [Jake] as the
  permalink's article block, rather than adding a second block beside it the
  way the travel types do: one page, one article type.
- **No author-supplied JSON-LD.** Free JSON in `<head>` is a script-injection
  surface beside a sanitizer that exists to strip `<script>`, and its schema
  validity cannot be checked.

## 1. `entries get` by anchor

`GET /api/v1/weblogs/{handle}/entries/by-anchor/{anchor}` returns the entry
exactly as `GET .../entries/{id}` does, same visibility rules (a draft or
trashed entry the caller may not see is 404). It is a second route variant
of the MCP `get` action, so `entries get` takes `weblog` plus `id` **or**
`anchor`; `id` wins when both are given (variant order).

## 2. `fields`

`fields` (repeatable query parameter; an array argument in MCP) on `get`,
`list`, `create`, `update`, `restore`: the `EntryView` properties to return.
`id` is always returned. Absent means the full view, as today. An unknown
name is a 400 whose field error lists the valid names. `list` keeps its
envelope (`items`, `offset`, `limit`, `hasMore`); only items are projected.

## 3. `edits`

`EntryWrite.edits`: a list of `{find, replace}`. On `update` only (a 400 on
create, and with `text` in the same request). Applied to the stored source
text, in order, each against the text as the previous edits left it:

- `find` must be non-empty and occur exactly once, or the request is a 400
  with a field error on `edits[i]`: "not found", or "found N times; include
  more surrounding text". Every failing edit is reported, nothing is saved.
- `replace` may be empty (deletes).
- At most 50 edits per request.
- The result is then treated exactly like a `text` write: validation, the
  `importExternalImages` step, one revision.
- `edits` with `expectedContentHash` is the safe pair: the hash proves the
  agent saw the current text, the exact-once matches prove each edit lands
  where it was meant.

`preview` with `id` also takes `edits`: it renders the edited text and saves
nothing, so an agent can look before it writes.

## 4. `contentHash` and `expectedContentHash`

`EntryView.contentHash`: SHA-256 of the stored `text` (UTF-8), bare
lowercase hex, as Wikantik's `read_page` returns it. `EntryWrite.
expectedContentHash` on `update`: when it differs from the stored text's
hash, the request is a 409 problem (`type` `entry-changed`) whose
`currentContentHash` extension carries the hash now stored; nothing is
saved. Checked before any other validation or import. Absent: last write
wins, as today. It guards the body only: a metadata PATCH already
overwrites only the fields it sends. The JSP editor's own lost-update risk
(a human saving over an agent) is out of scope.

## 5. Revisions

- **Attribution (bug).** A revision's `creator` is the user who made the
  save: the signed-in editor, or the API token's user (an agent account is a
  user). Existing rows are not rewritten.
- **`revisionNote`** [Jake]: optional, at most 255 characters,
  on `update`; stored in a new `note` column (migration, idempotent); shown
  in the editor's revision rail. Without it, revisions stay anonymous as to
  *why*.
- **API and MCP** (actions on the `entries` tool):
  - `GET .../entries/{id}/revisions` → newest first: `id`, `created`,
    `creator`, `note`, `title`; no text.
  - `GET .../entries/{id}/revisions/{rev}` → the snapshot's title, summary
    and text, plus `diff`: a unified diff (from `TextDiff`) against the
    current entry, or against `against={rev2}`.
  - `POST .../entries/{id}/revisions/{rev}/restore` → the web editor's
    restore (the replaced state becomes a revision), returns the entry
    (honours `fields`; takes `expectedContentHash`).
  - A revision of another entry, or of an entry the caller cannot see: 404.

## 6. SEO checks

One rule set, `EntrySeoChecks`, over the entry, its weblog and its rendered
body (`EntryRenderer.transformedText`). Each finding is `{code, severity,
message}` plus `value`/`limit` where a length is involved.

| Code | Rule | Severity |
|---|---|---|
| `missing_search_description`, `missing_meta_title`, `missing_featured_image`, `noindex` | today's `audits seo` gaps, same names on every surface | warning |
| `search_description_long` | > `SeoLimits.DESCRIPTION_MAX` (160) characters | warning |
| `search_description_short` | < `SeoLimits.DESCRIPTION_MIN` (50) characters | info |
| `title_long` | the `<title>` the themes print (metaTitle, else "title — weblog name", unescaped) > `SeoLimits.TITLE_MAX` (60) | warning |
| `body_h1` | an `<h1>` in the body (the theme already prints the title as the h1) | warning |
| `heading_skip` | a heading more than one level below the previous one (h2 → h4) | info |
| `image_alt_missing` | an `<img>` with no `alt` attribute, or an `alt` that is the file name | warning |
| `fragment_link_broken` | `href="#x"` with no element `id="x"` in the body | warning |
| `entry_link_broken` | a link to this weblog's `/entry/<anchor>` that is not a published entry | warning |
| `title_duplicate`, `search_description_duplicate` | equal to another published entry's in the weblog | warning (blog-wide only) |

Surfaces:
- `GET .../audit/seo` (existing, `audits seo`): adds the field checks
  (lengths, duplicates). Its existing gap codes are kept as they are.
- `GET .../audit/seo/entries/{id}` (new, `audits seo_entry`): every check
  for one entry, the body included.
- `entries preview`: the body checks join the existing warnings as
  sentences (the `List<String>` contract is unchanged).
- Whitespace: `applyWrite` trims `metaTitle`, `searchDescription` and
  `canonicalUrl` and stores a blank one as null, which closes the
  audit/renderer divergence for new writes.
- Editor: character counts beside meta title and search description in
  the SEO drawer, against the same limits [Jake].

## 7. `TECH_ARTICLE`

A new `JsonLdType` value. The permalink's article block is `TechArticle`
instead of `BlogPosting`, with the same properties. The editor's type
select, its message key and `EntryWrite.jsonLdType`'s `@Schema` text gain
it. `proficiencyLevel`, `dependencies` and the like need columns and wait
until an agent has a use for them.

## 8. Teaching it

- MCP instructions and the `entries` tool description name: get by anchor,
  `fields`, `edits` with `expectedContentHash`, revisions, and the `audits`
  tool. The report's author edited seven posts without finding `audits seo`.
- `docs/api/README.md`: a section per feature; fix line 358.

## Acceptance criteria

1. `entries get` with `anchor` returns the same body as with `id`; an
   anchor of a draft the caller may not see, or of another weblog, is 404.
2. `fields=[anchor,searchDescription]` on `update` returns exactly `id`,
   `anchor`, `searchDescription`; on `list`, every item has only the asked
   fields plus `id`; `fields=[nope]` is a 400 naming `nope` and listing the
   valid names. With no `fields`, every response is today's plus
   `contentHash`.
3. An `update` with two edits, one matching once and one matching twice,
   is a 400 with one field error on `edits[1]`, and the stored text and
   revision count are unchanged. Both matching once: text changed as
   specified, one revision written. `edits` with `text`: 400.
4. `get` returns `contentHash` equal to the SHA-256 hex of the stored text.
   An `update` whose `expectedContentHash` differs is a 409 `entry-changed`
   carrying `currentContentHash`, nothing saved and no image fetched; equal:
   saved, and the response's `contentHash` is the new text's.
5. A PATCH by an agent account's token records that account as the
   revision's creator; a save from the web editor records the signed-in user.
6. `revisions` lists newest first without text; `revision` returns the
   snapshot and a diff that shows a changed line; `restore_revision` makes
   the entry's text the snapshot's and adds a revision of the replaced
   state; a revision id from another entry is 404.
7. Each §6 code fires on a minimal entry built to trip it and not on a
   clean entry; `audits seo` on a weblog with two equal titles reports
   `title_duplicate` on both; `preview` of a body with an `<h1>` warns.
8. A whitespace-only `searchDescription` is stored as null and no
   `<meta name="description">` with blank content is rendered for it.
9. `jsonLdType: TECH_ARTICLE` renders one JSON-LD article block of type
   `TechArticle` and none of type `BlogPosting`.
10. `McpCoverageTest`, `McpDocsTest`, `ToolSchemasTest` updated, not
    excluded; all gates green.

## Waves

- **Wave A** (one release): §1, §2, §3, §4, §5 attribution, §8 for those,
  README line 358. Highest value per line: the report's P1/P2 items minus
  the audit.
- **Wave B** (next release): §5 API/MCP and note, §6, §7, §8 for those.

## Out of scope

Section edits by heading; regex edits; keyword density; readability
grades; a numeric score; `jsonLdCustom`; `rel` rules for external links;
TechArticle-specific properties; a lost-update guard in the JSP editor;
rewriting old revisions' `creator`.
