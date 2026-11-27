# Reader and admin experience (0.18.0)

Status: design, 2026-11-26. Ships as minor release 0.18.0.

## Intent

A browser review of 0.17.1 found two things.

- **Signed-in readers get almost nothing.** They can comment, rename themselves and delete their comments or account. Signing in is barely worth it.
- **The admin hides the work waiting for you.** The home page lists every blog with the same sentence. Pending comments and new inquiries are only found by clicking in, blog by blog.

This release gives readers reasons to sign in and makes Google sign-in the obvious door. In the admin, the work that's waiting comes to the author. Jake wants the user focus of the set delivered whole, as a series of small projects in one minor release.

## Goals

- Readers can **save** articles, **follow** blogs, keep a **reading history** that works on every device, and get an **email when the author answers their comment**.
- A **"You" menu** in the blog header, leading to one account page that holds all of that.
- **Google sign-in is the obvious way in** everywhere a reader meets it: the header, the comments, the sign-in page, and at the moments a reader wants something (Save, Follow).
- Umami counts sign-in clicks by where they came from.
- Admin: a **home page that shows what needs you**, **counts in the sidebar**, **one approval queue across blogs**, an **entries list whose results are above the fold**, and an **editor whose publish panel stays on screen**, with the summary near the title.

## Non-goals

- Readers replying to each other. Only owners reply, once per comment, as today (`CommentService.acceptsReply`).
- Pressroom sending new-post emails. New-post mail stays listmonk's: Follow offers to subscribe the reader to the blog's existing newsletter (decision 1, below).
- Profile photos. The "You" menu uses initials; no new Google claim is stored.
- Changing how password sign-in works for authors, or any API token or MCP behaviour.

## Anti-goals (failure even if it works)

- Pop-ups, modals or walls that interrupt reading. The site's direction is "quiet instrument"; every nudge is inline and dismissible or simply small.
- Anonymous pages getting slower or less cacheable. Signed-out HTML stays identical for every visitor.
- Mailing a reader who didn't expect it, or without a one-click way out.
- Storing reading data a reader can't see or delete.

## Decisions taken in conversation

1. **Reader email = author replies only** (Jake chose option 1).
   - When an owner answers a reader's comment, the reader gets one email. It is on by default, with an unsubscribe link and a switch on the account page.
   - "Email me new posts" on Follow subscribes the reader's Google email to the blog's listmonk list through the existing public subscription call. listmonk runs its own confirmation and unsubscribe.
   - No new contract with the back-office suite.
2. **Reader-to-reader replies stay out**, as above.

## Approaches considered

### How personal state reaches a page

- **Chosen: client-side state.**
  - The HTML stays the same for everyone, apart from the header, which is already rendered per user.
  - A script asks one small endpoint for the reader's state, then fills in Save/Follow state, the follow and continue-reading rows, and read marks.
  - Rendered pages expire only through `weblog.lastModified`. Following a blog doesn't touch that, so a server-rendered "From blogs you follow" row would go stale for up to an hour.
  - The shared page cache is a 400-entry LRU, and signed-in renders are already per user. More per-user markup would evict anonymous pages.
- **Rejected: server-rendered state** in the Velocity model. It goes stale, crowds the cache, and needs a new expiry path.

### Where reading history lives

- **Chosen: a server table for signed-in readers,** written by a beacon from the entry page.
  - It is shown on the account page, with "Clear history".
  - It is deleted with the account.
  - It is capped per reader.
  - Signed-out readers keep localStorage only, as today.
- **Rejected: syncing localStorage up on sign-in.** It merges two sources of truth and still needs the table.

### Inquiry "new" state

- **Chosen: a shared `read_at` on each submission.** An inquiry one member has opened is handled for everyone on the blog.
- **Rejected: a per-user "last seen" time.** It needs a new table and doesn't match how small teams split an inbox.

## The parts

These are built as independent projects with one release at the end. Each part has its own acceptance criteria; anything not stated is the builder's call.

### Part A: Admin home, sidebar counts, inbox "new"

**Schema.** `V055__form_submission_read_at.sql` adds `read_at timestamptz NULL` to `pressroom_form_submission`. It is idempotent.

**API (`/v1/me/weblogs`).** `MyWeblog` already carries `pendingComments` (0 without POST). It also gains:
- `newInquiries`: unread submissions, 0 when the caller lacks POST
- `drafts`
- `scheduled`
- `lastPublished`: ISO time or null

Only fields are added. Update `openapi.json` and the docs.

**Mark as read.** `POST /v1/weblogs/{handle}/submissions/read` with `{ids:[...]}` sets `read_at` where it is null.
- It needs weblog POST plus global LOGIN, and is `@SessionOnly`, like the rest of SubmissionsApi.
- It returns 204.
- Ids that aren't on this weblog are ignored, never an error that reveals they exist.
- Add it to `OpenApiContractTest`, `ApiAuthorizationMatrixTest`, the MCP exclusion list, and the pending SA draft list of session-only routes (`docs/superpowers/specs/2026-11-25-sa-draft-session-only-additions.md`).

**Inbox screen.**
- When a page of inquiries is shown, the screen marks the unread ones on it as read. The inbox has no filters; "on it" means the rows of the page returned, up to 30.
- Read state is deliberately shared. Only POST holders can open the inbox, so an EDIT_DRAFT member can neither see it nor clear it.
- Unread rows carry a visible "New" pill and keep it until the next visit.
- The sidebar and home counts update without a reload, through query invalidation of `['me','weblogs']`.

**Home screen (`Home.tsx`).**
- Each blog is a compact row: name, then counts as links: "3 awaiting approval" (to Comments?status=PENDING), "2 new inquiries" (to Inbox), "4 drafts" and "1 scheduled" (to Entries?status=…).
- Each row also shows "Last published 3 days ago", plus New entry and View blog links.
- The repeated "You have Admin permission on this weblog" line goes. The role is shown only when it is not Admin, as a small label.
- Sorting: blogs with pending comments or new inquiries first (by that total, descending), then by last published, newest first.
- Above the list, a summary line appears when anything is waiting across blogs: "5 comments awaiting approval across 2 blogs · Review all", linking to the queue in Part B.
- The `h2.mm_weblog_name` marker stays (`Routes` pins it).

**Sidebar (`Rail.tsx`).**
- Comments and Inquiries show a count badge when it's above 0.
- The badge comes from the already-loaded `useMyWeblogs()` row for the current handle; no new request.
- The accessible name reads "Comments, 3 awaiting approval".

**Acceptance.**
- A blog with pending comments sorts above one without.
- The counts match the DB.
- Opening the inbox clears the new count for every member of that blog.
- A member with EDIT_DRAFT sees no comment or inquiry counts.
- Vitest covers the sort, the badges and mark-as-read invalidation.
- A browser IT shows a badge appearing after a reader comment is held, and clearing after approval.

### Part B: Cross-blog approval queue

**API.** `GET /v1/me/comments?status=PENDING&offset&limit` returns comments across every blog where the caller's permission implies POST.
- `@WeblogScopeExempt`, `@SessionOnly`.
- It uses the same membership rule as `/v1/me/weblogs`: a global admin sees only blogs they are a member of.
- Each row carries `weblog {handle, name}` and the entry title and permalink, plus the existing comment fields.
- Order is newest first, with a `total`.
- The manager gains a query over a set of weblogs. It is never "all weblogs" unfiltered.
- Register it in the OpenAPI snapshot, the contract test, the authorization matrix, the MCP exclusion list, and the SA draft list.

**Screen.**
- New route `/pressroom-ui/app/comments` (scope `none`), reached from the home summary line and from a "Comments" item in the home sidebar.
- The list looks like the per-blog Comments screen, with the blog name on each row.
- Approve, Hide and Delete call the existing per-blog endpoints using the row's handle.
- After an action, it invalidates the queue, `['comments', handle, …]` and `['me','weblogs']`.
- It needs a new marker id pinned in `Routes` in the same commit.

**Acceptance.**
- Comments from two blogs appear together.
- A blog where the caller is EDIT_DRAFT contributes nothing.
- A token caller gets the session-only refusal.
- Approving from the queue publishes the comment and decrements both badges.
- There is an API test for the membership rule, including a global admin who isn't a member.

### Part C: Entries list and editor layout

The frontend changes only; no API change.

**Entries filter bar (`Entries.tsx`).**
- One row at the top: the text search (`#entries_bean_text`), the status chips (moved up from below the form), and a "More filters" toggle.
- The toggle reveals category, tags, dates and sort. It opens automatically when any of those is set in the URL.
- The list starts on the first screen at 1440×800.
- All existing field ids stay, because ITs use them.

**Editor (`EntryEditor.tsx`, `pressroom.css`).**
- `.editor-rail` becomes `position: sticky; top: 16px` with its own scroll when taller than the viewport. It stops being sticky in the existing one-column media query.
- The Summary moves from the drawer under the body to directly under the permalink.
  - With no summary it shows as a one-line "Add a summary" field; it grows on focus or with content.
  - Its id `#edit_summary` stays.
  - A short hint explains that it is used on lists and in search results.
- Ctrl+S already exists. Show the shortcut in the Save/Update button's title.

**Acceptance.**
- Vitest pins the filter toggle's auto-open and the summary position.
- The existing editor ITs pass untouched.
- A browser IT scrolls a long entry and finds Update still in the viewport.

### Part D: Google-first sign-in surfaces

**The rule.** These changes apply only when `SsoSettings.enabled()`. With SSO off, every surface is as today.

**Sign-in page (`Login.jsp`).**
- The heading becomes "Sign in to {site name}", with one line underneath: "Comment, save articles and follow the blogs you like."
- A full-width "Continue with Google" button comes first. It follows Google's sign-in button branding: white, the multicolour G mark as inline SVG, "Continue with Google".
- The password form moves into a collapsed "Author sign-in" section (`<details>`). It opens automatically:
  - when there is a sign-in error
  - when the URL has `?author`
  - when this browser last signed in with a password (a localStorage flag set on submit)
- `return` handling is unchanged.
- The existing ids (`ssoSignIn`, the j_username/j_password fields) stay.

**Blog header (the showAccountLinks macro, and the frontpage theme's `_header.vm` copy).**
- Signed out, the header shows a compact button-styled "Sign in with Google" with the G mark. It goes straight to `sso/start?return=<current path>`, the same route the comments link already uses.
- The colophon's "Sign in" link stays a plain link to the sign-in page. That is the authors' way in, and the author form is one click away there.

**Comments.** Signed out, the comments slot shows:
- a greyed, non-editable composer
- the Google button: "Sign in with Google to comment"

After sign-in the reader returns to `#comments`, with the composer focused.

**Moments of intent.**
- Signed out, Save and Follow (Part E) show the same Google button inline: "Sign in with Google to save this". There is no modal.
- The intent is stored in sessionStorage before leaving. After return, the page completes the action once and confirms it inline.

**End of article.**
- A single quiet line, above "Keep reading": "Follow {blog name}". Signed out, it carries the Google button.
- Under "Places you've been", signed out: "Sign in to keep this on all your devices."

**Umami.**
- Every sign-in link and button carries `data-umami-event="sign-in"` and `data-umami-event-via` set to `header`, `comments`, `save`, `follow`, `article-end`, `places` or `login-page`.
- Save and Follow clicks carry `save` and `follow` events.

**Acceptance.**
- With SSO on, the Google button is the first control on the sign-in page and in the header.
- With SSO off, the sign-in page and header render as in 0.17.1 (characterisation test first).
- The return paths survive.
- The author form is reachable in one click and opens by itself on error.
- Browser ITs cover the login page in both SSO states. (Amended 2026-11-27 at review: the browser suite runs with SSO on throughout, so the SSO-off page is covered by the unit characterisation tests `LoginPageSsoOffCharacterisationTest` and `LoginControllerTest`; `LoginPageIT` covers the SSO-on page.)

### Part E: Reader state (Save, Follow, history, "You" menu, account hub)

**Schema: `V056__reader_state.sql`.**
- `pressroom_reader_save(user_id, entry_id, created)`, primary key `(user_id, entry_id)`.
- `pressroom_reader_follow(user_id, weblog_id, created)`, primary key `(user_id, weblog_id)`.
- `pressroom_reader_read(user_id, entry_id, read_at)`, primary key `(user_id, entry_id)`. It is upserted, and capped at the newest 500 per reader (the builder decides when to trim).
- All have foreign keys with `ON DELETE CASCADE` to the user, entry and weblog. Deleting an entry or weblog removes its rows. Account deletion removes everything, and its confirmation text says so.
- Trashed entries are hidden from the lists.
- **Visible entries rule** (for every row, list and save):
  - published, with `pubTime <= now`
  - not in trash
  - on a weblog that is active and publicly visible

  Drafts, pending and scheduled entries never appear. Hidden weblogs' entries never appear.

**Endpoints.** They live beside the comments endpoint under `/pressroom-ui/rendering/`, use the same session, CSRF pattern and throttling, and are always `no-store`.
- `GET reader.rol?entry=<id>&weblog=<handle>` returns:
  - `{signedIn, name, initials, csrf, saved, following, newsletter:{available, subscribed?}, places[], continueReading[], followedPosts[], readIds[]}`
  - Only what the page asked for is included.
  - Signed out, it returns `{signedIn:false}`.
- `POST reader.rol` with an action of `save`/`unsave`, `follow`/`unfollow`, `read`, or `subscribe` (newsletter for a followed blog).
- Every action needs the global `comment` action, which readers, authors and admins all hold.
- Placement (builder's call, settled here): the logic goes in a new `com.wikantik.pressroom.business.reader` package, which is added to the security-boundary JaCoCo package rule (L 0.96 / B 0.90). The controller sits beside `CommentsController` in `ui.controllers.core`.
- Reads are throttled exactly as the comments GET is (no new limit). Writes use the comment-post throttle family with its own key.

**Pages.**
- Entry pages: a Save control in the article's share row, Follow at article end and in the blog's masthead, and a `read` beacon after the reader has spent 10 seconds on the page.
- Blog home and front page: for a signed-in reader, these rows are filled client-side from `reader.rol`. Each is hidden when it would be empty.
  - "Continue reading": up to 3 recently read articles.
  - "From blogs you follow": up to 6 newest published entries across followed blogs.
  - On the front page these sit directly under the masthead, inside the existing section rules of `discovery.vm`.
- Read marks: a small "Read" label on list items whose ids are in `readIds`, on the blog home and the front page.
- Signed-in "Places you've been" is built from the server's history (last five blogs), so it is the same on every device.
  - It replaces the local list while signed in.
  - The browser's localStorage list is left untouched, never merged up, and shows again after sign-out.
- **Follow's newsletter offer.**
  - After following a blog that has a list (and with listmonk configured), the confirmation shows "Also email me new posts".
  - That calls `ListmonkClient.subscribe(email, listUuid)` server-side with the account's Google email.
  - Answer "Check your inbox to confirm" on 200 and "Already subscribed" on 409. On 5xx or IO failure, show a calm retry message and record nothing.
  - Record `NEWSLETTER_SUBSCRIBED` as the form path does.
  - Pressroom does not store subscription state; `subscribed` is omitted.

**"You" menu.**
- Signed in, the header shows initials and the name. A small disclosure menu offers Saved, Following, Reading history, Your comments, Email settings and Sign out. An author also gets Main menu.
- The links point to sections (anchors) of the account page.
- The menu is keyboard-operable (a `details`/`summary` or a button with `aria-expanded`).

**Account page (`Account.jsp`, still JSP).** Sections, in order:
1. Saved: newest first, each with Remove.
2. Following: each with Unfollow, and "Email me new posts" where available.
3. Reading history: newest 50, with "Clear history".
4. Your comments: as today, now showing "Answered by {author}" when a reply exists.
5. Email: the reply-notification switch (Part F).
6. Display name.
7. Delete account.

**History switch.** Reading history has its own switch, "Keep my reading history", on by default.
- Turning it off clears the history and stops the beacon from recording.
- The column `keep_history boolean NOT NULL DEFAULT true` goes in V057 with `notify_replies`, so Part F's migration owns both columns. The switch sits in the account's Reading history section.

The page title is "Your account". It works for authors too. Their saved and followed lists are their own, and nothing changes in the admin.

**Privacy.** The design notes the new stored data:
- saves, follows and reading history
- reply emails

Deliverable: update `docs/dev/comments.md`/`sso.md`, and draft paragraph text for the production privacy page (a site page Jake edits; the builder supplies the wording in the release notes, not an edit to production).

**Acceptance.**
- Save, Follow and read round-trip; they survive sign-out and sign-in on a "different device" (a fresh browser profile in an IT).
- An anonymous request to `reader.rol` POST gets 401.
- A missing or invalid CSRF token is refused.
- An entry id from a draft or trashed entry can't be saved.
- An unpublished entry never shows in any row.
- Account deletion removes all three tables' rows.
- Signed-out HTML of an entry page is byte-identical across two anonymous requests and contains no reader data.
- `ReaderRefusalSweepTest` still passes: readers reach nothing new in the admin.

### Part F: Reply emails

**Trigger.** `CommentService.reply` (and the public `post` path with `replyTo`, which owners use) calls a new `CommentNotifier.notifyReplied(reply)` after commit. A mail goes to the parent comment's author when all of these hold:
- they have an email address
- their `notify_replies` is true
- they are not the reply's author
- mail is configured (`MailUtil.isMailConfigured()` and `site.adminemail`)

Mail goes out on the existing background sender, the way `notifyHeld` does. A failure never fails the reply. It is logged with the exception's class (and the failing step), not its message or stack trace: JavaMail messages quote the reader's address, and a reader's address does not belong in server logs (amended 2026-11-27 at review; the SMTP provider's own log holds the detail).

**Schema.** `V057__user_reader_preferences.sql` adds two columns to `pressroom_user`:
- `notify_replies boolean NOT NULL DEFAULT true`
- `keep_history boolean NOT NULL DEFAULT true`, used by Part E

**Where the email comes from.** A reader's email lives on `pressroom_user`. `ReaderAccounts` sets it at first sign-in and refreshes it at every sign-in from the provider's claim. An account with a blank email gets no mail and no newsletter offer.

**The email.** Plain text plus HTML.
- Subject: "{author} replied to your comment on {entry title}".
- Body: the reader's comment (shortened) and the reply, as plain text; never raw HTML from content.
- A link to the entry's comment anchor.
- An unsubscribe link.
- Headers: `List-Unsubscribe` (https URL) and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.

**Unsubscribe.** `GET/POST /pressroom-ui/unsubscribe.rol?t=<token>`.
- The token is an HMAC-signed `{userId, purpose:"replies"}`. The key is a new secret from config; the builder picks the property, and it is generated at first start if absent, stored like other generated secrets. It does not expire, and it is the same in every mail to that user.
- That is accepted: a leaked link can only turn reply emails off, which the reader can turn back on from the account page.
- No per-user salt is needed.
- GET shows a one-button confirmation page. POST, and the one-click POST, set `notify_replies=false` and show "You won't get reply emails any more" with a link to the account page.
- No sign-in is needed.
- A bad token gets a plain error page.

**Acceptance.**
- An owner's reply sends exactly one mail to the reader, checked with a test mail provider.
- No mail when the switch is off, when the reply author is the comment author, or when mail isn't configured.
- The unsubscribe link works signed out and flips the switch.
- A tampered token is refused.
- The account page switch round-trips.
- The headers are present.

## Constraints that apply to every part

- TDD throughout. Every repository gate in `CLAUDE.md` holds:
  - JaCoCo floors, including the security-boundary package rule; the new reader endpoints sit under it if they live in `ui/core` comments/sso packages, and builders check which rule binds
  - diff coverage, PMD/CPD/SpotBugs at zero, javac lint, ESLint zero warnings, Vitest floors
- No code may assume a context path: Parts D and E (new links and scripts) run the browser suite at `/` and `/pressroom`.
- Velocity hazards: no bare zero-arg macro names in comments; deleted members grepped in templates.
- Every new controller `@RequestParam` is named.
- Any `Routes` marker change and its selector go in one commit.
- Byte-identity test: two anonymous requests to the same URL. The header's `return` is appended in the browser by the existing return script, so it is not in the HTML.
- Escaping: names and titles going into reader-state JSON are rendered client-side with `textContent`, never `innerHTML`.
- Migrations V055 to V057 are assigned here so parallel builders don't collide: A takes V055, E takes V056, F takes V057.

## Build order and parallelism

- A, B, C and D are independent of each other.
- B depends on nothing from A, but shares `Home.tsx` (the summary link), so B goes after A, or B's link is added by A as a stub route.
- E depends on D only for the shared signed-out Google button partial: whichever lands first defines it in weblog.vm, and the other reuses it.
- F is independent, except that the account page switch lives in E's account page. F adds the column and mailer, and E or F, whichever lands second, adds the switch.

Builders run in pinned worktrees per `docs/dev/build-and-ci.md`. weblog.vm is touched by D and E, so they are serialised or E rebases its weblog.vm edits onto D, never the reverse.

## Release

All six parts ship together: the final `mvn verify` and the both-context-path browser run happen once, after the last merge. If a part is blocked by a real problem, Jake is asked whether to ship without it; no part is dropped silently.

- When all parts are merged, run full `mvn verify`, then the browser suite at both context paths.
- `bin/release.sh 0.18.0` locally.
- Jake is asked before the tag is pushed and the deploy.
- Release notes include the privacy-page wording and the reply-email/unsubscribe behaviour.

## Left to the builder

- Visual details within the existing tokens and quiet style.
- The exact SQL for the cross-blog query.
- How the history cap is enforced.
- The HMAC property name.
- Component decomposition.
- The read-beacon mechanism (`sendBeacon` vs fetch).
- How the "Read" label looks.
