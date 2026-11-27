# Reader and Admin Experience (0.18.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give signed-in readers reasons to sign in (save, follow, history, reply emails, a "You" menu) behind a Google-first door. Make the admin bring waiting work to the author.

**Architecture:**
- Admin changes: additive v1 endpoints and DTO fields plus React screens.
- Reader state: three new tables, a `business.reader` service, and one `/pressroom-ui/rendering/reader.rol` JSON endpoint. Pages fill personal state client-side, so anonymous HTML stays shared.
- Reply mail: rides the existing `CommentNotifier`/`MailUtil` path, with an HMAC one-click unsubscribe.

**Tech Stack:** Java 25, Spring Boot 4.2, JPA/EclipseLink, PostgreSQL 18, Velocity (weblog.vm), JSP (Login, Account), React + TanStack Query + Vitest, Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-11-26-reader-and-admin-experience-design.md`. Every builder reads it first; it holds the copy, the acceptance criteria and the rationale. This plan holds the names, files, order and tests.

## Global Constraints

- TDD: each test is seen failing for the expected reason before the code that passes it. A characterisation test, written first and passing against the old code, says so in its javadoc.
- Every gate in CLAUDE.md holds at zero:
  - JaCoCo LINE 0.9720 / BRANCH 0.9260
  - security boundary L 0.96 / B 0.90, which **now includes `com.wikantik.pressroom.business.reader`** (Part E adds the `<include>` in the parent `pom.xml`)
  - diff coverage ~90%
  - PMD 7.29.0, CPD@110, SpotBugs + find-sec-bugs
  - javac -Xlint:all failOnWarning
  - ESLint zero warnings
  - Vitest floors (lines 98.2, branches 91.3, functions 95.8, statements 98.0)
- Migrations: V055 (Part A), V056 (Part E), V057 (Part F). Each is idempotent (`IF NOT EXISTS`), in `bin/db/migrations/`.
- No context-path assumptions. Parts D and E run the browser suite at `/` and `-Dit.context.path=pressroom`.
- Name every `@RequestParam`/`@PathVariable`.
- Velocity:
  - write "the showX macro" in comments, never a bare `#showX`
  - reader names and titles reach the DOM via `textContent`, never `innerHTML`
- A `Routes` marker change and its selector go in one commit.
- A new v1 operation updates all of these:
  - `openapi.json` (`-Dopenapi.snapshot.update=true`)
  - `OpenApiContractTest.OPERATION_IDS`
  - `ApiAuthorizationMatrixTest`
  - the MCP exclusion list
  - `docs/api/README.md`, `docs/dev/api.md`
  - and, when `@SessionOnly`, the list in `docs/superpowers/specs/2026-11-25-sa-draft-session-only-additions.md`
- SSO off means every reader surface renders as in 0.17.1: no Google buttons, no Save/Follow for signed-out visitors.
- Agents commit locally in their worktree; no agent pushes. Under heavy build load, re-run a timed-out Vitest file alone before treating it as a failure.

## Review Focus

1. **A Save/Follow intent survives the Google round trip and completes exactly once.** A reload after return must not repeat it, and a cancelled Google sign-in must leave no stale intent. Test: Part E Task E4, `intentCompletesOnce`.
2. **Deleting a saved entry, a followed weblog or the reader's account leaves no orphan rows and no FK error.** Test: Part E Task E1, `cascadesOnEntryWeblogAndUserDelete`.
3. **Links built outside a request must be absolute and context-path correct.** That is the unsubscribe and comment-anchor links in mail, and every `reader.rol`/`sso/start` URL under `/pressroom`. Tests: F2 `mailLinksUseSiteAbsoluteUrl`; D and E ITs run at both paths.
4. **Mail or listmonk being down never fails the user's action.** The reply posts and the follow saves, with a calm message for subscribe. Tests: F1 `replySucceedsWhenMailThrows`, E2 `subscribeFailureIsCalm`.
5. **Invisible content never leaks into reader lists.** Scheduled (future `pubTime`), draft, trashed entries, and entries on inactive or hidden weblogs. Test: E1 `visibilityRuleExcludesScheduledDraftTrashedHidden`.

---

## Execution layout

| Wave | Worktree branch | Part | Depends on |
|---|---|---|---|
| 1 | `ux-admin-home` | A | master @ plan commit |
| 1 | `ux-editor` | C | master @ plan commit |
| 1 | `ux-signin` | D | master @ plan commit |
| 1 | `ux-reply-mail` | F | master @ plan commit |
| 1 | `ux-reader-core` | E1–E2 (backend) | master @ plan commit |
| 2 | `ux-queue` | B | master after A merged |
| 2 | `ux-reader-pages` | E3–E5 (pages, account hub) | master after D, F, E1–E2 merged |

- The orchestrator merges each branch into master after its review. Merges are in wave order; within a wave, the order they finish.
- Overlap check (`docs/dev/build-and-ci.md`, "Parallel agents in worktrees") runs before each merge.
- `openapi.json` conflicts are resolved by regenerating, never by hand.

---

## Part A: Admin home, sidebar counts, inbox "new"

### Task A1: Unread state on submissions

**Files:**
- Create: `bin/db/migrations/V055__form_submission_read_at.sql`
- Modify: the `FormSubmission` pojo and `FormSubmission.orm.xml`
- Modify: `FormSubmissionManager` and its JPA impl
- Test: `FormSubmissionReadStateTest` (business tier, DB-backed like the existing manager tests)

**Interfaces (produces):**
- `Instant FormSubmission.getReadAt()`
- `long FormSubmissionManager.getUnreadCount(Weblog)`
- `int FormSubmissionManager.markRead(Weblog, Collection<String> ids)`, which returns the rows changed

- [ ] Write tests:
  - `unreadCountCountsNullReadAt`: 3 submissions, mark 1, then the count is 2.
  - `markReadIgnoresOtherWeblogsIds`: an id from weblog B passed for weblog A changes 0 rows, and B's stays unread.
  - `markReadIsIdempotent`: marking twice keeps the first `read_at`.
- [ ] Run them and see them fail (no column, no methods).
- [ ] Add the migration: `ALTER TABLE pressroom_form_submission ADD COLUMN IF NOT EXISTS read_at timestamptz;`. Map it, then implement both methods. `markRead` updates only `WHERE weblogid = :w AND id IN :ids AND read_at IS NULL`.
- [ ] Run the tests and see them pass. Commit.

### Task A2: `/v1/me/weblogs` counts and the mark-read endpoint

**Files:**
- Modify: `ui/restapi/dto/MetaDtos.java` (`MyWeblog`)
- Modify: `ui/restapi/v1/MetaApi.java` (`myWeblog`)
- Modify: `ui/restapi/v1/SubmissionsApi.java`
- Modify: the submission DTO
- Modify: the OpenAPI snapshot, contract, matrix, MCP exclusion, the docs and the SA draft list
- Test: `MetaApiAttentionCountsTest`, `SubmissionsMarkReadApiTest`

**Interfaces (produces):**
- `MyWeblog` gains these fields, appended after `pendingComments`: `long newInquiries`, `long drafts`, `long scheduled`, `Instant lastPublished`, which is nullable.
  - `newInquiries` is 0 when the caller lacks POST, the same rule as `pendingComments`.
- The submission view gains `boolean unread`.
- `POST /v1/weblogs/{handle}/submissions/read`, operationId `submissionsMarkRead`, body `{ "ids": [string] }`, answers 204. It is `@SessionOnly`, with the same permission as `submissionsList`.

- [ ] Tests:
  - The counts match seeded data: 2 drafts, 1 scheduled with a future pubTime, and `lastPublished` equal to the newest published pubTime.
  - An EDIT_DRAFT member gets `newInquiries == 0` and `pendingComments == 0`.
  - The endpoint answers 204, and the rows are read afterwards.
  - A foreign id is ignored, still 204.
  - A token caller gets the session-only refusal.
  - A non-member gets 404.
- [ ] Implement it, regenerate the snapshot, and update the contract, matrix, MCP exclusion and docs. Commit.

### Task A3: Home, Rail badges, Inbox

**Files:**
- Create: `app/frontend/src/admin/screens/homeOrder.ts`
- Modify: `screens/Home.tsx`
- Modify: `frame/Rail.tsx`, `routes/railTable.ts` (badge source per entry)
- Modify: `screens/people/Inbox.tsx`, `api/people.ts`
- Test: `test/admin/screens/homeOrder.test.ts`, `home.test.tsx`, `frame/rail.badges.test.tsx`, `screens/inbox.test.tsx`
- IT: `it-selenium/.../AdminAttentionIT.java`

**Interfaces:**
- `attentionOrder(a: MyWeblog, b: MyWeblog): number` sorts by `(pendingComments + newInquiries)` descending, then `lastPublished` descending with nulls last, then name.
- `useMarkSubmissionsRead(handle)` is a mutation whose `onSuccess` invalidates `['me','weblogs']` only. It does **not** refetch the open inbox page, so "New" pills stay until the next visit.

- [ ] Tests:
  - order cases, including ties and null `lastPublished`
  - the home row shows "3 awaiting approval" linking to `comments?status=PENDING`, "2 new inquiries", drafts and scheduled links, and "Last published …"
  - no permission sentence for Admin; a role label for Author and Limited
  - the cross-blog summary line appears only when the total is above 0, and links to `/comments`; that route arrives in Part B, so A renders the link and B makes it live
  - the Rail shows a Comments badge "3" with accessible name "Comments, 3 awaiting approval", and none at 0
  - the Inbox calls mark-read with the unread ids on screen, once
- [ ] Implement. Keep `h2.mm_weblog_name`.
- [ ] Write the IT `AdminAttentionIT`:
  - A reader comment held as PENDING shows a Comments badge on that blog's rail.
  - Approving it removes the badge.
  - An inquiry submitted through the contact form shows an Inquiries badge, which is gone after the inbox has been opened.
- [ ] Run Vitest with coverage and the IT at both context paths. Commit.

## Part B: Cross-blog approval queue (wave 2, after A)

### Task B1: Manager query and `/v1/me/comments`

**Files:**
- Modify: `business/CommentManager.java`, `JPACommentManagerImpl`
- Create: `ui/restapi/v1/MeCommentsApi.java`
- Create: a DTO `QueueComment` in the comment DTO file
- Modify: the snapshot, contract, matrix, MCP exclusion, the docs and the SA draft list
- Test: `CommentManagerAcrossWeblogsTest`, `MeCommentsApiTest`

**Interfaces:**
- `List<ReaderComment> getComments(Collection<Weblog> weblogs, CommentStatus status, int offset, int limit)` and `long countComments(Collection<Weblog> weblogs, CommentStatus status)`. An empty collection returns empty or 0 **without querying**.
- `GET /v1/me/comments?status=PENDING&offset=0&limit=50`, operationId `meCommentsList`.
  - Annotations: `@WeblogScopeExempt @SessionOnly`.
  - Response `{ total, items: QueueComment[] }`. A `QueueComment` is the existing comment view plus `weblog: {handle, name}`, `entryTitle` and `entryPermalink`.
  - Weblogs come from `getWeblogPermissions(user)` filtered to those implying POST, the `/v1/me/weblogs` rule.

- [ ] Tests:
  - two weblogs merge, newest first
  - EDIT_DRAFT weblog excluded
  - a global admin who is not a member sees nothing from that blog
  - a token caller is refused
  - the limit is capped at 100
  - an empty set does no query (verify with a mock)
- [ ] Implement and regenerate. Commit.

### Task B2: Queue screen

**Files:**
- Create: `screens/CommentQueue.tsx`
- Modify: `router.tsx` (route `comments`, `scoped('none', HomeSidebar)`), `Home.tsx` (the HomeSidebar "Comments" link)
- Modify: `api/listQueries.ts` (`useCommentQueue(status, offset)`, key `['commentQueue', status, offset]`)
- Modify: `Routes.java` (marker `#comment-queue-marker`)
- Test: `test/admin/screens/commentQueue.test.tsx`
- IT: `CommentQueueIT`

- [ ] Tests:
  - rows show the blog name
  - Approve calls `PATCH /v1/weblogs/{rowHandle}/comments/{id}`, then invalidates `['commentQueue']`, `['comments', handle]` and `['me','weblogs']`
  - an empty state, and paging
- [ ] Write the IT: comments pending on two blogs appear together; approve one, and it leaves the queue and that blog's rail badge drops.
- [ ] Implement. Run the suite at both context paths. Commit.

## Part C: Entries list and editor layout (frontend only)

### Task C1: Compact entries filter

**Files:**
- Modify: `screens/Entries.tsx`, `pressroom.css`
- Test: `test/admin/screens/entries.test.tsx`

- [ ] Tests:
  - text search and status chips render before the "More filters" toggle
  - the category, tags, date and sort fields are hidden until toggled
  - the toggle starts open when any of `category|tags|startDate|endDate|sortBy≠UPDATE_TIME` is in the URL
  - all existing field ids are still present once opened
- [ ] Implement with a `<details>` or a button with `aria-expanded`; the builder chooses. Keep `#entries-list-marker`. Commit.

### Task C2: Sticky rail and summary under the title

**Files:**
- Modify: `screens/EntryEditor.tsx` (move the summary drawer to directly under the permalink; render it as a single-line field that grows)
- Modify: `editor/EntryRail.tsx` (add the Ctrl+S hint to the title of the Save/Update button)
- Modify: `pressroom.css` (`.editor-rail { position: sticky; top: 16px; max-height: calc(100vh - 32px); overflow-y: auto }`, reset inside the existing one-column media query)
- Test: `entryEditor.test.tsx`
- IT: extend the existing editor IT with `publishPanelStaysVisibleOnLongEntry`

- [ ] Tests:
  - `#edit_summary` follows the permalink in DOM order
  - it shows the hint text about lists and search results
  - the button title includes the platform shortcut
- [ ] Write the IT: open a long entry, scroll to the bottom, and the Update button is in the viewport (`getBoundingClientRect`).
- [ ] Implement. Commit.

## Part D: Google-first sign-in surfaces

### Task D1: Sign-in page

**Files:**
- Modify: `WEB-INF/jsps/core/Login.jsp`, `Login-css.jsp`
- Modify: `LoginController.java` (add `siteName` to the model)
- Modify: `ApplicationResources.properties`, adding keys under `login.reader.*`
- Test: `LoginPageSsoOffCharacterisationTest`, written first and passing; `LoginControllerTest`
- IT: `LoginPageIT`

- [ ] Write the characterisation test: with SSO off, the rendered login page has the same form, ids and order as today.
- [ ] Tests with SSO on:
  - the first focusable control is `#ssoSignIn`, labelled "Continue with Google", with the G SVG
  - the heading reads "Sign in to {siteName}"
  - the password form is inside a closed `details#authorSignIn`
  - it is open when there is an error param, or when the URL has `?author`
  - `return` is carried on both paths
- [ ] Implement:
  - The "last signed in with a password" flag is localStorage `pressroom.authorSignIn=1`, set on submit of the password form. A small inline script opens the details when the flag is present.
  - The `data-umami-event="sign-in"` and `-via="login-page"` attributes are on the Google button.
- [ ] Write the IT: SSO off shows the old layout. SSO on, if the IT properties can enable it with dummy client settings without contacting Google: the Google button comes first and the author form opens in one click. If they can't, record why in `docs/dev/browser-its.md` and rely on the unit render tests.
- [ ] Commit.

### Task D2: Header, comments and the shared Google button

**Files:**
- Modify: `UtilitiesModel.java` (`boolean isSsoEnabled()`, `String getSsoStartUrl()`)
- Modify: `WEB-INF/velocity/weblog.vm`:
  - the showAccountLinks macro
  - a new showGoogleSignIn macro
  - a JS helper in the common script
  - the comment slot's `signInLink()`
  - the colophon
  - the Places nudge
- Modify: `themes/frontpage/_header.vm`
- Modify: the theme CSS for `.g-signin`
- Test: the existing Velocity render-test harness; add `GoogleSignInRenderTest`
- IT: `ReaderSignInSurfacesIT`

**Interfaces (produces, for Part E):**
- The Velocity macro `showGoogleSignIn($via, $labelKey)` emits `<a class="g-signin" data-umami-event="sign-in" data-umami-event-via="$via" data-sign-in-return href="{sso start}">` holding the G SVG and the message. The existing return script appends `?return=`.
- The JS helper `window.pressroomGoogleSignIn(via: string, label: string): HTMLAnchorElement` builds the same element client-side, with `return` set to `location.pathname + location.search + location.hash`. It is defined only when SSO is enabled.

- [ ] Tests:
  - SSO on, signed out: the header contains `.g-signin` with via `header`.
  - SSO off: the header is as before.
  - The colophon "Sign in" still links to `login.rol`.
  - The comment slot's signed-out markup renders a greyed `textarea[disabled]` and a Google button via `comments`.
  - After return with `#comments`, the composer has focus. A Vitest-style DOM test isn't available for weblog.vm script, so cover it in the IT.
  - The Places nudge is present only when signed out and SSO is on.
- [ ] Write the IT at both context paths:
  - Signed out, the header's Google link is `{ctx}/pressroom-ui/sso/start?return=…`, and the comments show the Google button.
  - A signed-in author sees no Google button.
- [ ] Commit.

## Part E: Reader state

### Task E1: Schema and `business.reader` service (wave 1, `ux-reader-core`)

**Files:**
- Create: `bin/db/migrations/V056__reader_state.sql` (three tables, per the spec, with `ON DELETE CASCADE` FKs to `pressroom_user(id)`, `weblogentry(id)` and `weblog(id)`)
- Create: `business/reader/{ReaderStateService.java, ReaderVisibility.java, ReaderLists.java, package-info.java}`, plus pojos and ORM for the three tables (or native queries; the builder chooses, consistent with `business.comments`)
- Modify: the parent `pom.xml` (add `business.reader` to the security-boundary `<include>` list)
- Test: `ReaderStateServiceTest`, `ReaderVisibilityTest`

**Interfaces (produces):**
- `boolean ReaderVisibility.isVisible(WeblogEntry)`: published, `pubTime <= now`, not trashed, and the weblog active and visible.
- `ReaderStateService` methods:
  - `void save(User, WeblogEntry)` and `void unsave(User, String entryId)`. `save` throws `ReaderStateRefusal.NOT_VISIBLE` when the entry is invisible.
  - `void follow(User, Weblog)` and `void unfollow(User, String weblogId)`.
  - `void recordRead(User, WeblogEntry)`: an upsert of `read_at`, trimmed to the newest 500. The keep-history guard is added in E3, after Part F's V057 is on master.
  - `void clearHistory(User)`.
  - `ReaderLists lists(User, Set<ReaderList> wanted, int limit)`. `ReaderList` is the enum `SAVED, FOLLOWING, HISTORY, PLACES, CONTINUE, FOLLOWED_POSTS, READ_IDS`. Every list is filtered by `isVisible`.
  - `boolean isSaved(User, String entryId)` and `boolean isFollowing(User, String weblogId)`.

- [ ] Tests:
  - `visibilityRuleExcludesScheduledDraftTrashedHidden`
  - `saveRefusesInvisibleEntry`
  - `saveIsIdempotent`
  - `historyTrimsTo500`
  - `followedPostsNewestFirstAcrossFollowedOnly`
  - `placesAreLastFiveDistinctWeblogs`
  - `cascadesOnEntryWeblogAndUserDelete`
- [ ] Implement. Commit.

### Task E2: `reader.rol` endpoint (wave 1)

**Files:**
- Create: `ui/controllers/core/ReaderStateController.java`
- Modify: the security configuration, only if `/pressroom-ui/rendering/reader.rol` needs the same matcher treatment as `comments.rol`
- Modify: the throttle registration, adding a `reader-write` key
- Test: `ReaderStateControllerTest`

**Interfaces (produces, for E3/E4):**
- `GET reader.rol?entry=&weblog=&include=places,continue,followed,readIds,saved,following`
  - Signed out: `{signedIn:false}`.
  - Signed in: `{signedIn:true, name, initials, author:boolean, csrf:{header,token}, saved?:boolean, following?:boolean, newsletter?:{available:boolean}, places?:[{handle,name,url}], continueReading?:[{id,title,url,weblogName}], followedPosts?:[{id,title,url,weblogName,pubTime}], readIds?:[string]}`.
  - Keys are present only when asked for or implied: `saved` with `entry`, `following` and `newsletter` with `weblog`.
  - Always `Cache-Control: no-store`.
- `POST reader.rol` with JSON `{action:"save"|"unsave"|"follow"|"unfollow"|"read"|"subscribe", entry?, weblog?}`.
  - On success: `200 {ok:true, saved?|following?}`.
  - Signed out: 401. Missing or invalid CSRF: 403, through Spring's filter as on `comments.rol`. Invisible entry or unknown weblog: 404. Throttled: 429.
  - `subscribe` answers `{ok:true, outcome:"confirm"|"already"}`, or `503 {outcome:"unavailable"}`. It calls `ListmonkClient.subscribe(user.getEmailAddress(), weblog.getNewsletterListUuid())` and records `NEWSLETTER_SUBSCRIBED` as `NewsletterController` does. A blank email or no list answers 404.
  - The global `comment` action is required.

- [ ] Tests:
  - the anonymous GET shape and anonymous POST 401
  - a bad CSRF token gets 403
  - save of a draft gets 404
  - `include` filtering
  - `subscribeFailureIsCalm`: a listmonk IOException gives 503 `unavailable` and no event
  - 409 maps to `already`
  - no-store on both verbs
  - throttle 429
- [ ] Implement. Commit.

### Task E3: Account hub (wave 2, `ux-reader-pages`)

**Files:**
- Modify: `AccountController.java`, adding `account!unsave.rol`, `account!unfollow.rol`, `account!clearHistory.rol`, `account!saveHistorySetting.rol` and `account!subscribe.rol`
- Modify: `Account.jsp` (sections and anchors in the spec's order: `#saved`, `#following`, `#history`, `#comments`, `#email`, `#name`, `#delete`; title "Your account"; "Answered by {author}" on comments with a reply)
- Test: `AccountControllerReaderStateTest`, and `AccountPageIT` (extend if it exists)

- [ ] Add the keep-history guard: `ReaderStateService.recordRead` does nothing when `!user.isKeepHistory()`. Test it first: `historyNotRecordedWhenKeepHistoryOff`.
- [ ] Tests:
  - each POST round-trips and redirects to its anchor
  - turning history off clears it
  - the delete-account confirmation text mentions saves, follows and history
  - an author's account page works
  - `ReaderRefusalSweepTest` still passes
- [ ] Implement. Commit.

### Task E4: Page integration (wave 2)

**Files:**
- Modify: `weblog.vm`:
  - the Save control in the share row (a new showSaveControl macro)
  - Follow in the masthead and at article end (a new showFollowControl macro; the article-end line sits above "Keep reading")
  - the read beacon
  - the Places source when signed in
  - the "You" menu in the showAccountLinks macro
  - the client script `pressroomReader` that calls `reader.rol`
- Modify: `themes/frontpage/_header.vm` (the "You" menu)
- Modify: `templates/weblog/discovery.vm` (`fd-continue` and `fd-followed` sections directly under the masthead, each in its own section per the header rule)
- Modify: the blog home template(s) of the shipped themes (the same two rows), and theme CSS
- Test: Velocity render tests (`ReaderControlsRenderTest`)
- IT: `ReaderStateIT` at both context paths

**Behaviour that needs pinning:**
- Signed-out HTML contains the controls in a neutral state. Signed-out clicks on Save or Follow:
  - store `sessionStorage['pressroom.intent'] = {action, entry|weblog}`
  - replace the control inline with `pressroomGoogleSignIn('save'|'follow', …)`

  On load, signed in, the script removes the intent **before** POSTing it, then confirms inline.
- With SSO off, signed-out pages render no Save/Follow controls.
- After a successful follow, when `newsletter.available` is true, the inline confirmation offers "Also email me new posts". It POSTs `subscribe`, then shows "Check your inbox to confirm", "Already subscribed" or a calm retry line for `unavailable`.
- The read beacon: after 10 s visible (`visibilitychange`-aware), it sends one POST `read` per page view. It runs only when signed in.
- Umami events: `save`, `follow` (with `-via`), and the D sign-in events.

- [ ] Tests:
  - `anonymousEntryHtmlIsIdentical`: two anonymous GETs give byte-identical HTML with no reader data
  - SSO off shows no controls
  - the "You" menu renders initials and links to the account anchors, plus Main menu for authors
- [ ] Write the ITs:
  - `intentCompletesOnce`: simulate by seeding the sessionStorage intent and then loading signed in. It saves once, and a reload does not save again.
  - save, then seen on the account page
  - follow, then the front page "From blogs you follow" shows that blog's newest post
  - read for 10 s, then "Continue reading" lists it in a fresh browser session (new driver) for the same reader
  - signed-in Places comes from the server
- [ ] How the ITs get a signed-in reader without Google: use the existing IT approach for reader sessions if one exists; otherwise add a test-only seeding path consistent with `docs/dev/browser-its.md`. Never a production backdoor.
- [ ] Commit.

### Task E5: Docs

- [ ] Update `docs/dev/comments.md` and `docs/dev/sso.md`, and add a "Reader state" section to `docs/dev/audience-and-analytics.md` covering the tables, the endpoint, the visibility rule and the history switch.
- [ ] Draft the privacy-page paragraph into the release notes file `docs/releases/0.18.0.md`, or wherever release notes live. Commit.

## Part F: Reply emails

### Task F1: Preferences column and the notifier

**Files:**
- Create: `bin/db/migrations/V057__user_reader_preferences.sql` with exactly:
  ```sql
  ALTER TABLE pressroom_user ADD COLUMN IF NOT EXISTS notify_replies boolean NOT NULL DEFAULT true;
  ALTER TABLE pressroom_user ADD COLUMN IF NOT EXISTS keep_history boolean NOT NULL DEFAULT true;
  ```
- Modify: `User` pojo and `User.orm.xml` (`isNotifyReplies`/`setNotifyReplies`, `isKeepHistory`/`setKeepHistory`)
- Modify: `CommentNotifier` (`void notifyReplied(ReaderComment reply)`), `MailCommentNotifier`
- Modify: `CommentService` (call it after commit in both `reply` and `post`-with-`replyTo`)
- Test: `ReplyNotificationTest`

- [ ] Tests:
  - an owner's reply sends exactly one mail to the parent's author
  - no mail when `notifyReplies` is false
  - no mail when the reply author is the parent author
  - no mail when the email is blank
  - no mail when mail isn't configured
  - `replySucceedsWhenMailThrows`
  - the subject is "{author} replied to your comment on {entry title}"
  - bodies hold the escaped plain text of both comments (the parent truncated to 300 characters with "…")
- [ ] Implement on the existing background sender used by `notifyHeld`. Commit.

### Task F2: Unsubscribe token, page and account switch

**Files:**
- Create: `business/comments/UnsubscribeTokens.java`
- Create: `ui/controllers/core/UnsubscribeController.java` and `WEB-INF/jsps/core/Unsubscribe.jsp` (tiles and resolver entries like the other simple pages)
- Modify: the security config (`/pressroom-ui/unsubscribe.rol` permitted anonymously and **CSRF-exempt**, because the token authorises and mail clients' one-click POST carries no CSRF token)
- Modify: the mail headers in `MailCommentNotifier`
- Modify: the secret's config property and its generation, which follows the existing generated-secret pattern
- Modify: `AccountController` and `Account.jsp` (an `#email` section with the switch, `account!saveEmailSetting.rol`)
- Test: `UnsubscribeTokensTest`, `UnsubscribeControllerTest`, extend `AccountController` tests

**Interfaces:**
- `String UnsubscribeTokens.issue(String userId, String purpose)` gives base64url of `userId|purpose|HMAC-SHA256`.
- `Optional<String> UnsubscribeTokens.verify(String token, String purpose)` returns the userId. It compares with `MessageDigest.isEqual`.

- [ ] Tests:
  - round trip
  - a tampered token or a wrong purpose gives empty
  - GET shows the confirmation and changes nothing
  - POST flips `notifyReplies` to false while signed out
  - the one-click POST (`List-Unsubscribe=One-Click` body) works
  - a bad token gets the error page with 400
  - `mailLinksUseSiteAbsoluteUrl`: the entry link and the unsubscribe link are absolute from the site's absolute URL, correct under a context path
  - the `List-Unsubscribe` and `List-Unsubscribe-Post` headers are present
  - the account switch round-trips
- [ ] Implement. Commit.

---

## Release (orchestrator)

- [ ] After every branch is merged:
  - `mvn clean verify` on master (all gates)
  - `mvn verify -Pit` at `/`, then at `-Dit.context.path=pressroom`
- [ ] Update `docs/dev/quality-gates.md` if any floor ratchets. Ratchet only from a clean build's figure.
- [ ] Run `bin/release.sh 0.18.0` without `--push`.
- [ ] Ask Jake before the tag push and the deploy.
- [ ] Delete this plan's worktrees and branches. Update the memory.
