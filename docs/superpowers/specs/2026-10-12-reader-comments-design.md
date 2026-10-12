# Comments for signed-in readers — design

Date: 2026-10-12. Design approved in conversation the same day, section by
section; this spec states what "done" means. It is the second half of step 3
of the MCP roadmap (`2026-10-11-mcp-endpoint-design.md`, Roadmap). The first
half, reader sign-in with Google (`2026-10-11-reader-sign-in-design.md`), was
released as 0.1.16.

## Problem

Jake wants readers to comment on entries, signed in. Readers can now get an
account: signing in with Google creates a `reader` that holds no global
action. The W1 comment subsystem was removed in V022 because nobody could
reach it. It also had no real spam defence. It is not being restored; this is
a fresh design.

## Decisions

Made by Jake in conversation:

- **Moderation: first held, then trusted.**
  - A reader's first comment on a weblog waits for an owner's approval.
  - Once one is approved, that reader's later comments on that weblog publish
    at once.
  - Owners or the agent can hide or delete any comment afterwards.
- **Shape: flat, with author replies inline.**
  - Each entry has one chronological list.
  - An author, or the agent through the API, can reply to a specific comment.
    The reply shows directly under it, marked as the author's.
  - Readers do not reply to each other.
- **Reader rights:**
  - A reader can delete their own comments and set their display name.
  - A reader can delete their account, which removes their comments.
  - There is no editing after posting.
- **Rendering: comments on the server, the form in the browser** (approach
  A).
  - Published comments are part of the cached entry page, so they are
    indexable and work without JavaScript.
  - The form is built in the browser from a session endpoint, like the
    contact form, so nothing visitor-specific enters the shared render cache.
- **Custom domains: not in v1.**
  - A custom-domain weblog shows its published comments, but posting is not
    offered there.
  - The reason: the reader's session lives on the site host, and its cookie
    never reaches the custom domain. The site-host URL of such a weblog
    301-redirects back to the custom domain.
  - Per-domain sign-in would be its own spec.

Made by me in the approved sections:

- The body is plain text. It is never Markdown, never shortcodes and never
  HTML. URLs become links with `rel="nofollow ugc noopener"`.
- Comments are off per weblog until an owner enables them.
- Owners are emailed when a comment is held. Readers are never emailed.
- The defences are:
  - Google sign-in;
  - holding a reader until they are trusted;
  - holding any comment with more than two links;
  - a per-account rate limit.

  There is no CAPTCHA and no content filter.

## Non-goals

- Commenting on custom-domain weblogs.
- Reader-to-reader replies and deeper threading.
- Editing a comment after posting.
- Notifications to readers.
- Comment feeds.
- Comment search.
- Comments in JSON-LD.
- Top-level comments by the agent. Through the API it only replies.
- Per-weblog reader bans. Disabling an account site-wide exists already.
- Import of old comments. None survive V022.

## Design

### Data

Migration `V031__reader_comments.sql` is idempotent. **Table name.** It is `roller_reader_comment`, not `roller_comment`. The baseline V002 still creates the W1 `roller_comment` table and V022 drops it, so reusing that name would let a replayed V022 drop live comments (ruling R3, 2026-10-12).

**The comment table**, `roller_reader_comment`:
- `id varchar(48)` is the primary key.
- `entry_id varchar(48) NOT NULL` references `weblogentry(id)` `ON DELETE
  CASCADE`.
- `author_id varchar(48) NOT NULL` references `roller_user(id)` `ON DELETE
  CASCADE`.
- `reply_to_id varchar(48)` references `roller_reader_comment(id)` `ON DELETE
  CASCADE`. It is null for a top-level comment.
- `body text NOT NULL` holds the raw text, at most 4000 characters, checked
  in code.
- `status varchar(16) NOT NULL` holds `PENDING`, `PUBLISHED` or `HIDDEN`.
- `author_is_owner boolean NOT NULL DEFAULT false`.
- `created` and `status_changed` are `timestamp(3) with time zone NOT NULL`.
- Indexes cover `(entry_id, status, created)` and `(author_id)`.

**New columns on existing tables:**
- `weblog.comments_enabled boolean NOT NULL DEFAULT false`.
- `weblog.comment_notify boolean NOT NULL DEFAULT true`.
- `weblogentry.comments_closed boolean NOT NULL DEFAULT false`.

**Business tier:**
- A `CommentManager` reached through `Weblogger`, with an entity
  `ReaderComment`. The name keeps clear of the dropped W1 classes.
- It stores, lists by weblog, entry, status or author, counts, changes
  status, deletes, and answers `isTrusted(user, weblog)`.

### Permissions and rules

**The new global action.**
- `GlobalPermission.COMMENT = "comment"` is a new global action.
- `role.action.reader=comment`.
- `editor` and `admin` gain `comment`.
- `comment` does not imply `login`, so readers stay refused everywhere they
  are today.

**An owner of a weblog** is an account holding any `WeblogPermission` on it.

**Who may post** a top-level comment on an entry: an enabled account with the
`comment` action. All of the following must hold:
- the weblog has `comments_enabled`;
- the weblog has no custom domain (custom domains are not in v1, wherever
  the request arrives);
- the entry is `PUBLISHED` and not `comments_closed`.

**The status of a new comment:**
- An owner's comment is `PUBLISHED`, with `author_is_owner = true`.
- Otherwise the comment is `PUBLISHED` only when **both** hold:
  - the account is **trusted** on this weblog: it has at least one
    `PUBLISHED` comment there and no `HIDDEN` one;
  - the body contains at most two `http(s)` URLs.
- Anything else is `PENDING`.

**Replies.**
- Only an owner may send `replyTo`.
- It must name a `PUBLISHED` top-level comment on the same entry.
- A reply is `PUBLISHED` with `author_is_owner = true`.

**The body** is trimmed. It must be non-empty and at most 4000 characters.

**Rate limit.**
- At most 5 comments per account in any 10 minutes.
- At most 30 per account per day.
- Two `GenericThrottle` instances, keyed by user id, enforce it.
- Owners' replies count too.

**Deletion and hiding.**
- Deleting a comment deletes its reply (cascade).
- Hiding a comment leaves its reply in the database but not rendered: a
  reply renders only under a `PUBLISHED` parent.

**Trashed and deleted entries.**
- A trashed entry renders no page, so its comments show nowhere public. They
  survive a restore.
- Deleting an entry outright deletes its comments.

**Disabled accounts.** Every comment write re-reads the account. A disabled
account cannot post, even inside a live session.

### Rendering on the entry page

A shared macro is defined in `weblog.vm`; prose calls it "the showComments
macro", and it takes the weblog and the entry. Each theme's `permalink.vm`
calls it: journal, travel and portfolio.

**Comments off.** With `comments_enabled` false the macro renders nothing.

**Comments on.** The macro renders:
- **The list.** `PUBLISHED` top-level comments, oldest first. Each shows:
  - the writer's display name, escaped;
  - an "Author" badge when `author_is_owner`;
  - the date, in the weblog's zone;
  - the rendered body.

  Under each comment sits its `PUBLISHED` reply, if any, marked as the
  author's.
- **The count**, in the section heading ("3 comments").
- **When the entry is `comments_closed`**, the line "Comments are closed",
  and no form placeholder.
- **Otherwise, the form placeholder.** An inert `<div class="comments-slot">`
  carries the entry id and server-built endpoints, the way `[contact]` does.
  On a custom-domain weblog it carries `data-unavailable="custom-domain"`
  instead of endpoints.

**Body rendering** happens at render time only:
1. HTML-escape the raw text.
2. Turn `http(s)` URLs into `<a href rel="nofollow ugc noopener">`.
3. A blank line becomes a paragraph break, and a single newline becomes
   `<br>`.
4. Pass the result through `HTMLSanitizer`, the content boundary.

**Cache.** Any change that alters what a rendered page shows bumps the
weblog's `lastModified`. That is the only way rendered pages expire. The
changes are:
- a comment becomes or stops being `PUBLISHED`;
- a `PUBLISHED` comment is deleted;
- a reply is added or deleted;
- a commenter with `PUBLISHED` comments changes their display name. That
  bumps every weblog where they have one.

A `PENDING` comment changes no rendered page.

### The form in the browser

The shared audience-assets script, as the contact form does, turns each
`.comments-slot` into the comment form.

**Session request.**
- It calls `GET <ctx>/roller-ui/rendering/comments/session.rol?entry=<id>`.
- The answer is JSON, sent with `Cache-Control: no-store`.
- The endpoint suffix is checked the same way the contact script checks its
  own.

**Session answers.**
- **Anonymous:**
  `{"signedIn": false, "signInUrl": "<ctx>/roller-ui/sso/start?return=<this entry's path>"}`.
  - With SSO off, `signInUrl` is absent. The script then shows nothing.
- **Signed in, may not post:** `{"signedIn": true, "canPost": false, "reason": "<code>"}`, with `reason`
  one of:
  - `closed`;
  - `disabled`;
  - `custom-domain`;
  - `no-permission` (an account without `comment`, which none has today).
- **Signed in, may post:**

  ```
  {"signedIn": true, "canPost": true, "displayName": "...",
   "isOwner": bool, "csrf": {"header": "...", "token": "..."},
   "accountUrl": "<ctx>/roller-ui/account.rol",
   "pending": [ {id, body, created} ... ]}
  ```

  `pending` lists that account's own `PENDING` comments on this entry, shown
  as "awaiting approval".

**Posting.**
- `POST <ctx>/roller-ui/rendering/comments.rol` takes a JSON body
  `{entry, body, replyTo?}` and the CSRF header. The session cookie
  authenticates it. There is **no CSRF exemption**.
- Answers:
  - `201` with `{id, status}`;
  - `400` for an empty body, one over the limit, or a bad `replyTo`;
  - `401` when not signed in;
  - `403` for an account without `comment`, or one that is disabled;
  - `404` for an unknown, unpublished or closed entry, comments off, or a
    custom domain;
  - `429` from the rate limit.
- The script shows "Published" and reloads the comment list, or shows
  "Thanks — your comment will appear once it is approved".

**Deleting.**
- `DELETE <ctx>/roller-ui/rendering/comments/<id>.rol`, with CSRF, deletes
  the caller's own comment.
- Answers: `204`, or `404` when it is not theirs or does not exist.
- The script shows a Delete link on the reader's own published comments.
  Those are marked `data-author="<username>"`. The session answer carries the
  username so the script can match it; the username is not secret.

**Escaping.** The script inserts every server-provided string with
`textContent`, never `innerHTML`.

### Moderation in the admin UI

**The Comments page.** A new editor page, `Comments`, sits in the weblog's
menu. It needs the weblog `POST` permission.
- **Tabs:** "Awaiting approval" (the default), "Published" and "Hidden".
- **Rows:** each shows the entry title (linked), the writer (display name,
  username, and reader or author), the date and the body (escaped).
- **Row actions:**
  - Approve sets `PUBLISHED`.
  - Hide sets `HIDDEN`.
  - Delete is a `data-confirm` button.
  - Reply opens an inline textarea.
- **Bulk actions:** Approve, Hide and Delete work on the selected rows.

**Other admin pages.**
- **Main Menu** shows a pending count beside each weblog that has `PENDING`
  comments.
- **Weblog Settings** gains "Allow comments" (`comments_enabled`) and "Email
  me when a comment needs approval" (`comment_notify`).
- **The entry editor** gains "Comments closed" (`comments_closed`) and shows
  the entry's comment counts.

`Routes` pins the new page's CSS marker.

### API and MCP

Every route is weblog-scoped and needs a token with `POST` on the weblog.
Through the interceptor's one authorization path, anything the caller may
not see answers 404.

**Routes:**
- `GET /api/v1/weblogs/{handle}/comments?status=&entry=&limit=&offset=`
  lists comments, newest first. `limit` defaults to 50, with a maximum of
  200.
- `GET /api/v1/weblogs/{handle}/comments/{id}`.
- `PATCH /api/v1/weblogs/{handle}/comments/{id}` with
  `{"status": "PUBLISHED" | "HIDDEN"}`.
- `DELETE /api/v1/weblogs/{handle}/comments/{id}`.
- `POST /api/v1/weblogs/{handle}/comments/{id}/replies` with `{"body"}`. The
  reply is written as the token's user and is `PUBLISHED`.

**The comment view:**
- `id`, `entryId`, `entryTitle`, `status`, `body`, `created`,
  `statusChanged`;
- `authorUserName`, `authorDisplayName`, `authorIsOwner`;
- `replyTo`, and `reply`: the id of its reply, if any.

**Never in the view:** an email address, the author's id, or anything else
from the account.

**Settings through the API.**
- `WeblogView` and `WeblogPatch` gain `commentsEnabled` and `commentNotify`.
- `EntryView` and `EntryWrite` gain `commentsClosed`.
- Each joins the `clear` rules where they apply. Booleans are never
  clearable.

**MCP.** A `comments` tool with the actions `list`, `get`, `update`, `delete`
and `reply` is generated from the routes. `McpCoverageTest` and `McpDocsTest`
guard it. The MCP instructions gain one bullet on moderation.

### Notifications

When a comment is stored as `PENDING`, and the weblog has `comment_notify`:
- **Who:** each member holding the weblog `ADMIN` permission, with a
  non-blank email, gets one email.
- **Content:**
  - the entry title;
  - the writer's display name;
  - the first 500 characters of the body, as plain text;
  - a link to the Comments page, built from the Absolute URL.
- **How:** `MailUtil` sends it off the request thread, after the comment is
  stored, releasing the persistence session in a `finally`.
- **Failure:** a mail failure is logged and loses nothing.
- **Never mailed:** published comments, replies, and readers.

### The reader's account page

`/roller-ui/account.rol` needs the global `comment` action. Anonymous
visitors are sent to log in.

**Display name.**
- It is the account's screen name, 1 to 50 code points after stripping
  control and format characters and collapsing whitespace.
- It is refused, with a message, when it case-insensitively equals the
  screen name of any account that holds a weblog permission. That stops
  author impersonation.
- Saving it bumps the cache as above.

**Your comments.** Every comment of the account's, across weblogs, newest
first, with:
- the status;
- the entry link;
- a Delete button for each.

**Delete my account.**
- It is a `data-confirm` button.
- It deletes the account's comments (and their replies), roles, tokens and
  user row, then ends the session.
- It is refused, with a message, for an account holding any weblog
  permission ("Leave your weblogs first"), and for an account holding
  `admin`.

**The comment form** links to this page.

### Logging

Logger `roller.comments` writes to roller.log. It logs:
- comment ids;
- status changes, with the actor's username;
- rate-limit refusals, with the username;
- account deletions, with the username.

It never logs comment bodies or email addresses.

### Documentation

**New docs.**
- `docs/dev/comments.md` replaces the "Comments" paragraph in
  `docs/dev/entries.md`.
- `CLAUDE.md` gains one index row for it.

**Updated docs.**
- `docs/api/README.md` covers the comment routes and the new weblog and entry
  fields.
- `RollerSSO.md` replaces "What a reader can do today" with commenting.
- `docker_deployment.md` says how to turn comments on for a weblog.

## Testing

**Unit (`app`):**
- the migration;
- `CommentManager`, including the trust rule and the cascades;
- body rendering, including XSS cases;
- the session, post and delete controllers;
- the account page;
- the Comments page;
- the API routes and MCP drift;
- notifications;
- the cache bumps;
- `ReaderRefusalSweepTest`, which stays green while readers now hold
  `comment`.

**Browser (`it-selenium`):**
- Keycloak readers, at both `/` and `/roller`.
- A weblog with comments on.
- A custom-domain weblog. Reuse VirtualHostIT's host mapping.

## Acceptance criteria

**Data and permissions**

1. V031 creates the table, its foreign keys, cascades and indexes, plus the
   three columns with their defaults. It is idempotent. (U)
2. `reader`, `editor` and `admin` all imply the global `comment` action. A
   reader still implies none of `login`, `weblog` or `admin`, and every
   controller in the reflective sweep still refuses a reader. (U)
3. A weblog defaults to `comments_enabled = false` and
   `comment_notify = true`. An entry defaults to `comments_closed = false`.
   (U)

**Posting**

4. A reader's first comment on a weblog is stored `PENDING`. (U, I)
5. After one of a reader's comments on a weblog is approved, their next
   comment there is `PUBLISHED` at once. A comment by them on another weblog
   is still `PENDING`. (U, I)
6. A trusted reader's comment with three or more `http(s)` URLs is
   `PENDING`. (U)
7. Hiding any of a reader's comments on a weblog makes their next comment
   there `PENDING`. (U)
8. An owner's comment and an owner's reply are `PUBLISHED`, with
   `authorIsOwner` true. (U, I)
9. A reply from a non-owner, a reply to a non-`PUBLISHED` comment, a reply to
   a reply, and a reply to a comment on another entry each answer 400. (U)
10. An empty or whitespace-only body, and a body over 4000 characters, answer
    400. (U)
11. Posting answers 404, and stores nothing, when:
    - the entry is unknown, a draft, scheduled, pending or trashed;
    - the entry is `comments_closed`;
    - the weblog has comments off;
    - the weblog is a custom-domain weblog. (U)
12. Posting without a session answers 401. Posting without the CSRF token
    answers 403. A disabled account answers 403, even with a session that was
    valid before it was disabled. (U)
13. The sixth comment from one account within 10 minutes, and the 31st in a
    day, answer 429, and nothing is stored. (U)

**Rendering**

14. With comments off, the entry page has no comments section. (U, I)
15. With comments on, the entry page lists `PUBLISHED` top-level comments
    oldest first, each with its `PUBLISHED` reply beneath. No `PENDING` or
    `HIDDEN` comment appears, and neither does a reply under a hidden parent.
    (U, I)
16. A comment body containing each of these renders as text, with no
    executable markup:
    - `<script>`;
    - an `onerror` attribute;
    - a `javascript:` URL;
    - a `data:` URL;
    - an HTML entity trick;
    - an RTL or zero-width character.

    `http(s)` URLs become links with `rel="nofollow ugc noopener"`. Blank
    lines become paragraphs. (U)
17. The comments section carries no visitor-specific content. For an
    anonymous render and a reader's render of the same entry, the bytes from
    the section's opening tag to its closing tag are identical. The pre-
    existing navigation already shows "Logout" to a signed-in visitor, which
    is out of scope here. (U)
18. Publishing, hiding or deleting a published comment, and adding or
    deleting a reply, each advance the weblog's `lastModified`. Storing a
    `PENDING` comment does not. (U)
19. A closed entry shows its published comments and "Comments are closed",
    with no form placeholder. (U, I)

**The form**

20. The session endpoint answers `no-store`, as follows. (U)
    - **Anonymous:** `signedIn: false`, plus a sign-in URL whose `return` is
      the entry's own path. With SSO off there is no sign-in URL.
    - **A reader who may post:** `canPost: true`, the display name, a CSRF
      token, and that reader's own `PENDING` comments on the entry. Another
      reader's comments never appear.
21. In a browser: an anonymous visitor sees "Sign in with Google to comment".
    A signed-in reader posts and sees "will appear once it is approved", and
    the comment is visible to them as awaiting approval. A trusted reader's
    comment appears in the list. (I)
22. On a custom-domain weblog, the entry page shows its published comments,
    and the placeholder says commenting is not available on this address.
    The session and post endpoints there answer `custom-domain` and 404
    respectively. (U, I)
23. A reader deletes their own comment, with a 204, and the comment and its
    reply are gone. Deleting another account's comment answers 404. (U, I)

**Moderation**

24. The Comments page lists comments by tab and needs the weblog `POST`
    permission; a reader, and a member of another weblog, are refused.
    Approve, Hide, Delete and Reply, and the three bulk actions, change the
    rows and the rendered page accordingly. (U, I)
25. Main Menu shows the pending count per weblog. Weblog Settings saves
    "Allow comments" and "Email me…". The entry editor saves
    "Comments closed". (U)
26. The API routes behave as specified:
    - `list` filters by status and entry, and pages;
    - `get` returns one comment;
    - `PATCH` sets the status;
    - `DELETE` removes the comment;
    - `replies` creates an author reply.

    A token without `POST` on the weblog, or limited to another weblog, gets
    404. No response contains an email address. (U, I)
27. The MCP `comments` tool exposes `list`, `get`, `update`, `delete` and
    `reply`, and the drift tests pass. `weblogs update` sets
    `commentsEnabled`/`commentNotify`, and `entries update` sets
    `commentsClosed`. (U)

**Notifications**

28. A `PENDING` comment on a weblog with `comment_notify` sends one email to
    each `ADMIN` member with an email. A `PUBLISHED` comment, a reply, and a
    weblog with notify off send none. A mail failure still leaves the comment
    stored. (U)

**Reader account**

29. The account page needs `comment`; anonymous visitors are sent to log in.
    A display name that case-insensitively matches an owner's screen name is
    refused. A valid name shows on the reader's published comments after the
    change. (U, I)
30. "Delete my account" removes the account, its comments and their replies,
    and ends the session. It is refused for an account with a weblog
    permission or `admin`. (U, I)

**Logging and docs**

31. No log line written while posting, moderating, notifying or deleting
    contains a comment body or an email address. (U)
32. The documentation items in "Documentation" are done. (review)
