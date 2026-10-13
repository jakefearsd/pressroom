# Publishing-agent API refinements — design

Date: 2026-10-13. Design approved in conversation the same day ("lgtm");
this spec states what "done" means. It answers feedback from Jake's
publishing agent after its first posts through `/api/mcp`, each item checked
against the code before it was accepted.

## Problem

The agent asked for six things. Checked against the code:

| # | Asked for | Finding | Outcome |
|---|---|---|---|
| 1 | `featuredImageUrl` on entries | `featuredImageId`/`ogImageId` are only length-checked (`EntryDtos.applyWrite`): a typo, a foreign id or a non-image is stored and the post silently has no featured image. | Validate the ids; also add `featuredImageUrl` (Jake's choice). |
| 2 | Auto-create unknown categories | The 400 says `Unknown category 'X'.` with no list, so the agent pre-fetches the list. Auto-creating would publish a typo as a public category. | The 400 lists the valid names. No auto-create. |
| 3 | Choose the slug | The anchor is always the first five words of the title, with a bare digit on a clash (`torrey-pines1`). No surface can set it. | `anchor` on create. |
| 4 | Dry-run validation | A refused create saves nothing; DRAFT is already a safe save. The real cost is one error per round trip. | Dropped; covered by 5. |
| 5 | Field-level `errors[]` | Never populated; `docs/api/README.md` says so. Every check stops at the first problem. | Every bad field in one response. |
| 6 | Auto-import body images | `[image id=…]` already renders a responsive `<picture>`, but the MCP instructions never mention shortcodes. | Server-side import (Jake's choice), and the instructions teach the shortcodes. |

## Decisions

- **Entry writes become check → import → apply.** `applyWrite` today sets
  each field as it checks it. Imports cannot run inside that: creating a
  media file writes its bytes to disk at once and calls `weblogger.flush()`
  mid-method (`JPAMediaFileManagerImpl.persistNewMediaFile`), which would
  commit whatever the request had already changed on a managed entry. So
  every check runs first, against a scratch copy; then images are imported;
  then the write is applied to the real entry and saved. Nothing is fetched
  for a write that a check would refuse.
- **Field errors use the existing problem shape.** `ApiProblem.errors` and
  `FieldError(field, message)` already exist and are serialised; nothing
  fills them. `detail` stays a readable sentence (the messages joined), so a
  client that reads only `detail` keeps working. Rejected: bean validation
  (`@Valid`): most checks need the weblog (time zone, categories, media).
- **Anchors are set at create only** (Jake's choice) and stay immutable, as
  `docs/dev/routing.md` already states. Rejected: renaming with an automatic
  301 (needs the anchor cache cleared and one-hop redirect chains kept, and
  the JSP editor to match).
- **An unknown category is refused with the valid names** (Jake's choice).
  Rejected: auto-create; an opt-in `createCategory` flag.
- **Duplicate matching is by content (SHA-256), not by URL** (ruling). A
  stored source URL would keep a signed URL's credentials in the database —
  the import log records only the host for that reason — and a URL's content
  can change. Cost: a repeat import still downloads the image before it
  matches.
- **`featuredImageAlt` accompanies `featuredImageUrl`** (ruling), so the
  one-call path does not leave a file the media audit flags.
- **Body-image import is all or nothing for the entry** (ruling). Images
  that did import stay in the library and a retry matches them, so a retry
  costs downloads, not copies.
- **The import step has a budget** (ruling): at most 10 distinct body images
  per write, fetched one at a time, within 60 seconds for the whole step.
  Each fetch already has a 30-second deadline, and Cloudflare ends a request
  at 100 seconds.
- **An external `srcset` is refused, not half-imported** (ruling): rewriting
  `src` while `srcset` still names the other host would keep hotlinking.

## 1. Field errors

- A 400 or 409 about one request field carries
  `errors: [{"field": "<name>", "message": "<text>"}]`. `field` is the JSON
  property (or query parameter) name as the caller sent it: `category`,
  `geoLatitude`, `anchor`, `clear`, `featuredImageUrl`, `text`, `limit`.
- An entry create or update reports **every** field problem in one 400.
  `detail` is the messages joined by a space; with one problem it is that
  problem's message, unchanged from today.
- Every other single-field 400 in `/api/v1` (`ColumnLimits`, `ClearFields`,
  the other controllers and DTOs) carries a one-entry `errors` array. A 400
  not about a field (unparseable JSON, a missing body) carries none.
- MCP passes the API's problem body through unchanged, so a tool error
  carries the same `errors`.

## 2. Anchors

- `EntryWrite.anchor` is accepted on create. Absent, the anchor is generated
  from the title as today.
- Allowed form: `^[a-z0-9]+(?:[-_][a-z0-9]+)*$`, at most 255 characters
  (the column). Anything else is a 400 on `anchor`, never normalised.
- A taken anchor — held by any entry of this weblog, trashed ones included —
  is a **409** on `anchor`, reported only once every other field is valid.
  When the holder is in the trash, the message says so and that
  `delete_forever` frees it. The check runs before the import step and again
  immediately before the save.
- On update, an `anchor` equal to the stored one is accepted and ignored; a
  different one is a 400 on `anchor`: it is set when the entry is created.

## 3. Categories

- An unknown category is a 400 on `category` whose message names the
  weblog's categories: `Unknown category 'Travle'; it is one of: Food, Travel.`

## 4. Media ids

- `featuredImageId` and `ogImageId`, when written, must name an image media
  file of this weblog that is not in a private directory. Otherwise a 400 on
  that field. A value equal to the stored one is not re-checked, so sending
  an entry back as it was read never fails on an id whose file has since
  been deleted.
- One by-id ownership helper, `WeblogOwnership.mediaFile`, serves this check
  and `MediaApi` (whose local `belongsTo` it replaces).

## 5. Duplicate-free import

- Migration `V032` adds `roller_mediafile.content_hash varchar(64)`, nullable,
  with an index on `(directoryid, content_hash)`.
- Every new media file records the SHA-256 (lower-case hex) of the bytes it
  was created with — uploads, imports and theme files alike. Replacing a
  file's content or cropping it clears the hash. Files from before V032 have
  none and never match.
- `POST .../media/imports` whose fetched bytes match a file in the target
  directory returns that file: **200** (not 201), no new file, nothing
  counted against the quota. If that file's alt text is null and the import
  carries `altText`, it is filled; nothing else about the file changes.
  (`""` is a real value, "decorative", and is never overwritten.)
- Two concurrent imports of the same bytes may both create a file; there is
  no unique constraint.

## 6. `featuredImageUrl` and `featuredImageAlt`

- On create and update, `featuredImageUrl` imports the image (duplicate-free)
  into the weblog's default directory and sets `featuredImageId` to it.
- Refused (400) together with `featuredImageId`, or with `featuredImageId`
  in `clear`. `featuredImageAlt` without `featuredImageUrl` is a 400 on
  `featuredImageAlt`; it is at most 255 characters and fills the file's alt
  text only when that is null.
- Needs uploads enabled (as media import): otherwise a 400 on
  `featuredImageUrl`.
- A refused fetch is a 400 on `featuredImageUrl` carrying the fetcher's
  reason, and the entry is not written.

## 7. `importExternalImages`

- `EntryWrite.importExternalImages: true` on create or update imports the
  entry's external images and points the text at the copies. It is not
  stored. It scans the text being written, or, on an update without `text`,
  the stored text.
- **Found:** `<img src>` in HTML and every Markdown image (inline, reference,
  collapsed, shortcut), located with the commonmark parser and extensions the
  renderer uses, so code blocks and inline code are left alone.
- **External:** an absolute `http://` or `https://` URL whose host is neither
  the weblog's (custom domain included) nor the site's absolute URL's.
  Relative, protocol-relative and `data:` URLs are left alone.
- **Rewritten:** an `<img>`'s `src` value only, the rest of the tag and the
  text byte-for-byte unchanged. A Markdown image becomes the inline form
  `![alt](local-url "title")`. The local URL is the media file's absolute
  URL, as `MediaView.url` reports it.
- **Refused before any fetch** (400 on `text`): more than 10 distinct
  external URLs; any `srcset` (on `<img>` or `<source>`) naming an external
  URL; uploads disabled (400 on `importExternalImages`).
- Each distinct URL is fetched at most once per write, in order of first
  appearance, after the featured image; a URL used as both `featuredImageUrl`
  and a body image is imported once. Alt text from the tag or the Markdown
  image fills a null alt text on the file.
- **Budget:** the import step stops starting fetches 60 seconds after it
  began; each image not fetched by then is reported as such.
- **All or nothing:** any image refused or not fetched makes the write a 400
  with one `text` error per image (the URL, abbreviated, and the reason), and
  the entry is unchanged. Images that did import remain in the library.
- Each fetch logs its usual `roller.media.import` line (host only).

## 8. Instructions and documentation

- `McpInstructions` (stays under 3000 characters) adds: `anchor` at create,
  `errors` on a refusal, `featuredImageUrl`, `importExternalImages`, that a
  repeat import returns the first copy, and the shortcodes an agent can
  write — `[image id=…]`, `[gallery dir=…]`, `[map]` with `[pin]`,
  `[video url=…]`, `[faq]` with `[q]`/`[a]`.
- `docs/api/README.md`: Errors (the "never populated" paragraph replaced),
  Entries (the four new fields and their rules), Media (the 200 on a match).
- springdoc `@Schema` text on each new `EntryWrite` component (shared with
  the MCP tool argument descriptions).
- `docs/dev/api.md` (the check → import → apply order and why),
  `docs/dev/routing.md` (anchors: chosen at create, still immutable),
  `docs/dev/media.md` (the content hash and when it clears).

## Acceptance criteria

Each is a test.

1. A create with an unknown category and a latitude of 91 is one 400 whose
   `errors` fields are exactly `category` and `geoLatitude`; nothing is saved.
2. A single-problem 400 keeps today's `detail` text and adds one `errors`
   entry naming the field.
3. A PATCH refused for one field leaves every stored field unchanged,
   including the fields in the same request that were valid.
4. `ColumnLimits.requireMaxLength` refusals name their field in `errors`.
5. The unknown-category message lists the weblog's category names.
6. A create with `anchor: "torrey-pines"` stores that anchor and the
   permalink ends with it.
7. `anchor` values `Torrey`, `torrey pines`, `torrey--pines`, `-x`, `x-` and
   a 256-character one are 400s on `anchor`.
8. A create whose anchor is held by a live entry is a 409 on `anchor`; held
   by a trashed entry, a 409 whose message mentions the trash.
9. A PATCH with the stored anchor succeeds; with a different anchor it is a
   400 on `anchor` and nothing changes.
10. `featuredImageId` naming another weblog's image, a non-image file, a
    file in a private directory, or nothing, is a 400 on `featuredImageId`;
    the same for `ogImageId`. A PATCH carrying the stored `featuredImageId`
    of a deleted file succeeds.
11. V032 applies to a fresh database, applies twice without change, and adds
    a nullable `content_hash`.
12. A newly created media file's `content_hash` equals the SHA-256 of its
    bytes; replacing its content or cropping it clears the hash.
13. Importing the same bytes twice into one directory answers 201 then 200
    with the same id, and the directory holds one file; the second import's
    `altText` fills a null alt text and never replaces `""`.
14. A create with `featuredImageUrl` (fetcher stubbed) imports the image and
    the response's `featuredImageId` is its id; with `featuredImageId` too,
    or `featuredImageAlt` alone, a 400 and no fetch.
15. The scanner finds `<img src>` (double-, single- and unquoted), inline and
    reference Markdown images, and ignores fenced code, indented code, inline
    code, relative, protocol-relative, `data:` and own-host URLs.
16. The rewrite changes only the `src` value of an `<img>`; the surrounding
    text is byte-for-byte unchanged.
17. `importExternalImages` with 11 distinct external images, or with an
    external `srcset`, is a 400 on `text` and the fetcher is never called.
18. With one of three images failing, the write is a 400 with one `text`
    error naming that URL, the entry is unchanged, and the two imported
    files remain.
19. With the 60-second budget spent after the first image (clock stubbed),
    the remaining images are reported as not fetched and the write is a 400.
20. A PATCH with `importExternalImages: true` and no `text` rewrites the
    stored text.
21. An unknown-category write through MCP returns the problem with `errors`
    (browser IT, at `/` and `/roller`), and an MCP create with `anchor`
    yields a permalink ending in it.
22. `McpInstructionsTest` requires `anchor`, `featuredImageUrl`,
    `importExternalImages` and `[image id`, under 3000 characters.

## Out of scope

`ogImageUrl`; a slug field in the editor; the media-id check in the JSP
editor (its chooser offers only this weblog's files); URL-based duplicate
matching; renaming anchors; dry-run.

## Release

0.1.19, one migration (V032). Upgrade as usual.
