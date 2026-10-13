# Comments (reader)

Comments by signed-in readers, shipped 2026-10-12. Spec and acceptance
criteria: `docs/superpowers/specs/2026-10-12-reader-comments-design.md`
(rulings R3 to R7 below amend it where the code differs). Readers get their
account from Google sign-in (`docs/dev/sso.md`). The W1 comment subsystem
(V022 removed it) is not restored; nothing of it survives.

## A sweep for "comment" must leave alone

- **`roller_audit_log.comment_text`**: the audit log's own change note.
- **`util/GenericThrottle`**: it throttles `ContactController`
  (`contact.throttle.*`), `NewsletterController`
  (`newsletter.subscribe.throttle.*`) and `PasswordResetController`
  (`passwordreset.throttle.*`), and now `CommentService`.
- **`roller_comment`**: the dead W1 table name. V002 still creates it and V022
  drops it. `SchemaMigrationTest` keeps it in `REMOVED_FEATURE_TABLES`.
- **Search index**: `IndexOperation` writes no `C_CONTENT`; `SearchOperation`
  searches `{CONTENT, TITLE}`. Reader comments are not indexed (non-goal).

## Data model

Migration `bin/db/migrations/V031__reader_comments.sql` (idempotent).

- **Table `roller_reader_comment`** (ruling R3). Not `roller_comment`: V022 is
  replayed by the appliers and would drop live comments, and a replayed V002
  fails on the W1 indexes. Columns: `id`, `entry_id` (FK `weblogentry`, cascade),
  `author_id` (FK `roller_user`, cascade), `reply_to_id` (FK to itself,
  cascade), `body`, `status` (check constraint), `author_is_owner`, `created`,
  `status_changed`. Indexes `(entry_id, status, created)` and `(author_id)`,
  plus the partial unique index `rrc_reply_to_uq` on `reply_to_id`: a comment has
  at most one reply, even under a double submit.
- **New columns:** `weblog.comments_enabled` (default false),
  `weblog.comment_notify` (default true), `weblogentry.comments_closed`
  (default false).
- **Entity** `pojos/ReaderComment` (ORM `ReaderComment.orm.xml`), with
  `enum Status { PENDING, PUBLISHED, HIDDEN }`. `getReply()` is the true
  inverse of `replyTo`: the reply in **any** status. Every consumer that
  renders or counts a reply checks its status.
- **Storage** is `business/CommentManager` (`JPACommentManagerImpl`), reached
  as `weblogger.getCommentManager()`: `saveComment`, `removeComment`,
  `getComment`, `getPublishedForEntry` (one query, `JOIN FETCH` of reply and
  authors, for the page), `getComments(weblog, status, entry, offset, limit)`,
  `countComments(weblog, status[, entry])` (what `getComments` lists, replies
  included: the moderation pager's count), `countForEntry` (top-level only: the
  entry page heading's number, which the entry editor shows),
  `getPendingForEntryAndAuthor`,
  `getPublishedIdsForEntryAndAuthor`, `getCommentsByAuthor`, `isTrusted`,
  `getWeblogsWithPublishedCommentsBy`, `removeCommentsForEntry`,
  `removeCommentsByAuthor`. Callers flush before `getPublishedForEntry`.
  `removeWeblogEntry` and `removeUser` call the manager's removals first, so
  EclipseLink's shared cache stays coherent (see hazards).

## CommentService: the one rule holder

`business/comments/CommentService` holds every posting, trust and moderation
rule; `CommentRules` holds those that need no database. Every surface that
writes a comment goes through the service: the public endpoints, the
moderation page, the account page, the API. Nothing else saves a
`ReaderComment`.

- `whyNot(user, entry)` returns a `CommentRefusal` or null, checked in this
  order: signed in, account re-read and enabled (`DISABLED`), global `comment`
  action (`NO_PERMISSION`), entry `PUBLISHED` (`NOT_FOUND`), `comments_enabled`
  (`COMMENTS_OFF`), no custom domain (`CUSTOM_DOMAIN`), not `comments_closed`
  (`CLOSED`). The other refusals are `EMPTY_BODY`, `BODY_TOO_LONG`,
  `BAD_REPLY`, `RATE_LIMITED`.
- `post`, `reply` (owner only, to a PUBLISHED top-level comment without a
  reply), `setStatus`, `delete`, `deleteOwn`, `isOwner`, `expire(weblog)`,
  `expireAll`, `expireWhereShown(author)`. Post and reply results are a
  `CommentOutcome`; `setStatus` answers false when it refuses (a tombstone is
  never published, below), and each surface maps that: the moderation page does
  not count it, the API (and so MCP) answers 409.
- **Owner** = holds any `WeblogPermission` on the weblog.
- **Body:** `CommentRules.normalizeBody` trims and strips C0/C1 control
  characters except tab, newline and return; at most `MAX_BODY` (4000)
  code points, non-empty.
- **Rate limit (ruling R9: configurable):** by default 5 per 10 minutes and 30
  per day per account, keyed by user id, two `GenericThrottle`s under a striped
  per-account lock. Owners' replies count (the moderation page says so with its
  own message, `comments.reply.rateLimited`). The window is fixed, not sliding:
  a burst across a reset can store up to twice the window's limit in a little
  over one window. Configured in roller.properties as ALLOWED counts, read once
  at startup by `CommentLimits`:
  - `comments.throttle.window.limit` (default 5),
  - `comments.throttle.window.seconds` (default 600, at most 86400),
  - `comments.throttle.daily.limit` (default 30).

  A missing or unusable value falls back to its default, never to no limit.
  `CommentService.limit` is the one place an allowed count becomes a throttle
  threshold. The browser suite raises them (`roller-it.properties`:
  1000/600/10000) so `CommentsIT` is never one owner write from a 429; the unit
  tests prove the defaults.
- **Logging:** logger `roller.comments` (routed to roller.log): ids, statuses,
  usernames, codes. Never a body, an email address or a token.

### The trust rule

`CommentRules.statusFor(owner, trusted, body)`:

- An owner's comment or reply is `PUBLISHED` with `author_is_owner = true`.
- Anyone else is `PUBLISHED` only if **trusted on this weblog** (at least one
  PUBLISHED comment there and no HIDDEN one, `CommentManager.isTrusted`) **and**
  the body has at most two `http(s)` URLs (`MAX_LINKS_BEFORE_HOLD`); otherwise
  `PENDING`.
- Trust is per weblog. An account with no permission on the weblog (a password
  editor of another weblog, a promoted reader) gets reader rules. Becoming an
  owner makes the next comment published, with the Author badge.
- **Only Hide untrusts.** Deleting a spam comment leaves the reader trusted;
  the moderation page says so.
- **Tombstones (ruling R5).** A reader deleting their own HIDDEN comment gets
  the body erased and the row kept as HIDDEN with an empty body
  (`CommentRules.isTombstone`), so the owner's distrust survives. Account
  deletion removes everything. The moderation page shows a tombstone as
  "deleted by its author" and the account list leaves it out. `setStatus`
  refuses to publish a tombstone, for every surface. An owner's own comment or
  reply (`authorIsOwner`) is never tombstoned: `deleteOwn` deletes it outright,
  so an owner's hidden reply does not hold its comment's one reply slot.
- Turning comments off keeps moderation and the API working on existing
  comments; only posting (404) and the page section go.

## GlobalPermission.implies fails closed, and why it came first

`GlobalPermission.COMMENT = "comment"` is a new global action;
`role.action.reader=comment`, `editor=login,weblog,comment`,
`admin=login,weblog,admin,comment` (`roller.properties`).

The old `implies` fell through to `return true` for any required action it did
not recognise: a holder of `login` only was refused `weblog` and `admin` by
explicit checks, but a new action it had never heard of was granted to anyone.
Adding `comment` and giving it to readers would have meant every
`comment`-holding reader implied any unknown action. `implies` now requires
every required action to be implied (`impliesAction`): `admin` implies all,
`login` is implied by `login` or `weblog`, `weblog` and `comment` only by
themselves, and unknown actions only by `admin`. That landed first (Task 1) as
a security precondition: nothing may grant or check `comment` before it.
`comment` implies neither `login`, `weblog` nor `admin`, so readers stay refused
by every controller in `ReaderRefusalSweepTest`.

## Rendering and cache expiry

- The showComments macro in `WEB-INF/velocity/weblog.vm` takes the weblog and
  the entry; the journal, travel and portfolio `permalink.vm` call it. Comments
  off: it renders nothing. On: the heading with the published count, the list
  (oldest first, each with its PUBLISHED reply under it, Author badge when
  `authorIsOwner`), then "Comments are closed" **or** the inert
  `<div class="comments-slot">` carrying `data-entry`, `data-permalink`, the
  server-built endpoints (`UtilitiesModel.getCommentEndpoints`, so no context
  path is assumed) and `data-msg-*` texts. A custom-domain weblog gets
  `data-unavailable="custom-domain"` instead of endpoints.
- Data comes from `ReaderCommentWrapper` / `WeblogEntryWrapper` (`comments`,
  `commentCount`, `commentsClosed`), built from one `getPublishedForEntry`
  query. A reply shows only under a PUBLISHED parent.
- `CommentBodyRenderer.toHtml`: HTML-escape the plain text, link `http(s)` URLs
  (`rel="nofollow ugc noopener"`), blank line to paragraph, newline to `<br>`,
  then `HTMLSanitizer` (the content boundary). The body is never Markdown or a
  shortcode. A display name is stored raw and emitted with
  `$utils.escapeHTML`; the body is emitted bare (already safe).
- **The cached page carries no usernames and nothing visitor-specific
  (ruling R7).** An owner's login name is half a credential. The page script
  finds the reader's own comments by the ids in the session answer, matched
  against `id="comment-<id>"`.
- **Rendered pages expire only through `weblog.lastModified`.** The service
  calls `expire(weblog)` (`saveWeblog` then `CacheManager.invalidate`) when a
  comment becomes or stops being PUBLISHED, a PUBLISHED comment is deleted, a
  reply is added or deleted, or a commenter with published comments renames
  (every weblog where they have one: `expireWhereShown`). A rename can come from
  the account page, Profile (`ProfileController`) or User Admin
  (`UserEditController`); each expires after its save commits. A PENDING
  comment changes no page and expires nothing.
- Trashed entries render no page and their comments survive a restore;
  deleting an entry outright deletes them.

## The page script and the session endpoint

`CommentsController` (`/roller-ui/rendering`):

- `GET /comments/session.rol?entry=<id>`: JSON, `Cache-Control: no-store`; 404
  for an unknown or unpublished entry. Anonymous: `{signedIn:false, signInUrl?}` (absent with SSO off; the return
  path is the entry's own path). Signed in but refused:
  `{signedIn:true, canPost:false, reason}` with `reason` one of `closed`,
  `comments-off`, `disabled`, `custom-domain`, `no-permission` (the `CommentRefusal` name,
  lower case with hyphens; the script reads them, so a rename is a contract
  change). May post: `canPost`, `displayName`, `isOwner`, `csrf {header,token}`,
  `accountUrl`, `pending` (the account's own PENDING comments on this entry)
  and `ownPublished` (ids of its own PUBLISHED ones).
- `POST /comments.rol` (JSON `{entry, body, replyTo?}`): 201 `{id,status}`, 400
  (body, bad reply), 401, 403 (no `comment`, disabled, or no CSRF token), 404
  (unknown/unpublished/closed entry, comments off, custom domain), 429.
- `DELETE /comments/{id}.rol`: 204, 401 when anonymous, or 404 when it is not the caller's.

**Why CSRF is fetched, not cached.** The entry page is shared and cached, so a
token cannot be in it. The session endpoint, answering the signed-in reader
with `no-store`, hands them their token. There is **no CSRF exemption**:
`/roller-ui/rendering/**` is `permitAll`, but the UI chain's CSRF filter still
refuses a POST or DELETE without the token. The same reason keeps names, held
comments and ownership out of the page.

The script is in the audience-assets part of `weblog.vm`. It checks each
endpoint's suffix (`endpointOk`), inserts every server string with
`textContent` (never `innerHTML`), and accepts a sign-in link only if it is
same-origin. An expired session makes the CSRF filter answer 403 (HTML) before
the controller: on 401, 403 or an unexpected sub-400 answer (a login page where
JSON was expected) it re-reads the session and re-renders (signed out shows the
sign-in link, a reason shows its message, otherwise it retries once with the
fresh token), keeping the textarea. It never retries 400, 404, 429 or 5xx, so a
500 cannot double-post.

A signed-in principal that resolves to no enabled account is `disabled`, never
anonymous.

## Custom domains are not in v1

A custom-domain weblog shows its published comments, but posting is refused
(`CUSTOM_DOMAIN`; session reason `custom-domain`, post 404). The reader's
session cookie belongs to the site host and never reaches the custom domain,
and the site-host URL of such a weblog 301-redirects to the custom domain
(`docs/dev/routing.md`), so there is nowhere to sign in. Per-domain sign-in
would be its own spec.

## Moderation page

`CommentsModerationController` (`/roller-ui/authoring/comments.rol`,
`comments!approve|hide|delete|reply.rol`, `Comments.jsp`, a `Routes` entry and
CSS marker): needs the weblog `POST` permission. Tabs Awaiting approval
(default), Published, Hidden; filter `entry=`; single and bulk Approve, Hide,
Delete (`data-confirm`), inline Reply. `page` is clamped to 0..`MAX_PAGE`
(client input multiplied into an offset), and the pager counts what the list
shows, entry filter included. Ids are request parameters, so no log line
carries a raw one (a CR/LF could forge a line): a refusal names the weblog, or
the stored comment's id; `WeblogOwnership` logs the weblog for a failed lookup
of any kind.

**Who sees moderation elsewhere.** Main Menu shows the pending count per weblog
to a member whose permission row *implies* `POST`
(`WeblogPermissionView.isCanModerate`), never by looking for the literal
`post` action: a weblog's creator holds `admin` alone, and Members grants
single-action rows. The entry editor shows the counts line (top-level
comments, as the entry page's heading counts) only to a member with `POST`,
since it links to this page. Weblog Settings has "Allow comments" and "Email
me when a comment needs approval"; the entry editor has "Comments closed".

## API and MCP

`ui/restapi/v1/CommentsApi` under `/api/v1/weblogs/{handle}/comments`: list
(`status`, `entry`, `limit` default 50 max 200, `offset`; page object
`{items, offset, limit, hasMore}`), get, PATCH `{status}` (PUBLISHED or HIDDEN
only; publishing a tombstone is 409), DELETE, and `POST .../{id}/replies`
(written as the token's user, PUBLISHED; the token's user must be a weblog
member, so a global admin who is not one gets `BAD_REPLY`). View: `CommentDtos.CommentView`; it never carries an email or an
account id. `WeblogView`/`WeblogPatch` gain `commentsEnabled`/`commentNotify`,
`EntryView`/`EntryWrite` gain `commentsClosed`; booleans are never clearable.
**Ruling R6:** a token whose role lacks POST on a weblog it can reach keeps the
API's existing 403, like every other write route; 404 is for a weblog the token
cannot act on and for foreign or unknown comment ids (the spec's "without POST
gets 404" is amended). Public reference: `docs/api/README.md` (Comments).

MCP: the `comments` tool with `list`, `get`, `update`, `delete`, `reply`,
declared in `McpToolCatalog`, plus a bullet in `McpInstructions`.
`McpCoverageTest` and `McpDocsTest` (exact row order) guard drift;
`ToolSchemasTest` pins the field counts of `EntryWrite`; `ApiRouteTableTest`
counts the routes (all three broke once when routes or fields were added).

## Notifications

When a comment is stored PENDING and the weblog has `comment_notify`,
`MailCommentNotifier` (a `CommentNotifier`) mails each member with the weblog
`ADMIN` permission and a non-blank email: the entry title, the writer's display
name, the first 500 characters of the body as plain text, and a link to the
Comments page (from `site.absoluteurl`). `MailUtil` sends it on
`ThreadManager.executeInBackground`, after the comment is stored; the worker
re-loads the weblog by handle and releases the persistence session in a
`finally`. A mail failure loses nothing. Failures log only the exception
class names along the cause and next-exception chain (`kinds`): mail
exception messages quote recipient addresses. Published comments, replies and
readers are never mailed. Needs mail configured (`MailUtil.isMailConfigured`).

## The reader's account page

`AccountController`: `/roller-ui/account.rol`, `account!saveName.rol`,
`account!deleteComment.rol`, `account!deleteAccount.rol` (`Account.jsp`). It
requires the global `comment` action (`requiredGlobalPermissionActions`).
Display name (the screen name), one rule in `business/comments/DisplayNames`
shared with a reader's first sign-in (ruling R8): judged in exactly the form
`User.setScreenName` stores (`storedForm` repeats normalising and the setter's
markup stripping until the setter keeps the name unchanged, since
`HTMLSanitizer.sanitizeText` is not idempotent); 1 to 50 code points after
stripping control and format characters and collapsing whitespace; refused if
it equals, case-insensitively, the screen name of any weblog member
(anti-impersonation; a member keeping their own name is exempt); saving
expires the weblogs showing it. At first sign-in (`ReaderAccounts`) a refused
name claim falls back to the email's local part, then "Reader", then the
generated username. The page lists the account's comments with Delete. Delete
my account (`data-confirm`) removes comments, replies, roles, tokens and the
user, expires each weblog that showed them, and ends the session; it is
refused for an account with any weblog permission, `admin`, or any entry it
created (`WeblogEntryManager.hasEntriesCreatedBy`: bylines are usernames with
no foreign key).

## Hazards found while building

- **`implies` fell through to true** for unknown actions (above). Any new
  global action needs a test that an unrelated holder does not imply it.
- **The `eclipselink.refresh` hint corrupts cached replies.** An early
  `getPublishedForEntry` filtered the fetched reply by status with a refresh
  hint; afterwards a cached parent whose reply was HIDDEN reported
  `getReply() == null` to everyone (a second reply was accepted, and deleting
  the parent left a ghost reply). Now `getReply()` is the true inverse and
  consumers filter by status.
- **DB cascades leave stale shared cache.** `ON DELETE CASCADE` deletes the row
  but EclipseLink still serves the old object. Delete through
  `CommentManager`/`CommentService`, never by relying on the foreign key;
  `removeWeblogEntry` and `removeUser` do. `removeUser` also deletes the
  user's `userrole` rows (keyed by username; a same-name account would inherit
  them).
- **`GenericThrottle` refuses only when hits EXCEED the threshold** and is not
  thread-safe. The service passes `allowed - 1` and serialises per account.
- **A NUL in a body** (possible through JSON) makes PostgreSQL reject the
  insert: an unthrottled 500. `normalizeBody` strips controls.
- **A reused table name** (ruling R3, above).
- **A double-submitted reply** hits the unique index (SQL state 23505,
  `rrc_reply_to_uq`) at flush; the service maps it to `BAD_REPLY` and releases
  the persistence session, not a 500.
- **Entry titles are stored escaped.** `Comments.jsp` once escaped
  `displayTitle` again (a test even locked the double escape in). Emit it bare,
  as `Entries.jsp` does.
- **The cached page must carry no usernames** (R7): an earlier `data-author`
  exposed an owner's login name on every page they commented on.
- **A test that closes a Spring context can detach runtime config for the
  whole JVM.** A `SpringWebloggerProvider` is a `DisposableBean`; registering
  it as a bean made `context.close()` call `destroy()`, and ~75 later tests
  failed on null runtime properties. `CommentsControllerTest` and
  `AccountControllerTest` use a non-disposable provider view and an
  `@AfterAll` check.
- **A permission row's action list is not what it grants.** The Main Menu
  badge was first gated on `perms.hasAction("post")`, a literal lookup, so a
  weblog's creator (whose row holds `admin` alone) never saw it. Ask whether
  the row *implies* the action, as the interceptor does
  (`WeblogPermissionView.isCanModerate`).
- **Pinned counts break on new routes and fields:** `ApiRouteTableTest`,
  `ToolSchemasTest`, `McpDocsTest`; and `MessageFormatRegressionTest` forbids a
  doubled apostrophe in a key with no placeholder.
- **A mail failure's exception text quotes addresses**; log class names only.
- **Velocity:** the comments CSS has no `#id` selectors (a hash before a word
  can be read as a directive). Prose names a macro as "the showComments macro"
  or "the showAudienceAssets macro", never with its `#`; the only exception is
  the usage line in a macro's own `#**` doc block, a Velocity comment.
- **Bidi:** a display name or body may carry RTL overrides or zero-width
  characters. They render as text, isolated (`unicode-bidi: isolate` on
  `.comment-name` and `.comment-body`), and a link ends at any Unicode space or
  format character. Tests write such characters as `\u` escapes, never raw
  (Trojan Source).
