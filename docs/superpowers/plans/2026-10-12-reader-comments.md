# Comments for Signed-in Readers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Signed-in readers comment on entries. Owners moderate in the UI and through `/api/v1` + MCP. Published comments render into the cached entry page, and the comment form is built in the browser.

**Architecture:**
- A new `roller_comment` table (V031) is reached through a `CommentManager` on the `Weblogger` facade.
- All rules live in one container-managed `CommentService`: who may post, the status a comment gets, the trust rule, rate limits, cache bumps and notifications. Every surface calls it: the rendering endpoints, the moderation page, the API and the account page.
- Published comments render through a `showComments` macro, so their bytes are shared and cached.
- A script in the page fills a `.comments-slot` placeholder from a `no-store` session endpoint, which also hands out the CSRF token for posting.

**Tech Stack:** Java 25, Spring Boot 4.1 / Spring Security 7.1, EclipseLink + PostgreSQL 18, JSP admin UI, Velocity themes, JUnit 5 + Mockito + MockMvc, Selenide browser ITs with Keycloak 26.

**Spec:** `docs/superpowers/specs/2026-10-12-reader-comments-design.md` (criteria 1-32). Read it before your task. When this plan and the spec disagree, the spec wins.

## Global Constraints

**Process**
- Work on `master`. Commit your own work, adding only your files. **Never push.**
- Follow TDD: write the failing test, watch it fail for the expected reason, then implement. A characterisation test (written to pass against the old code) says so in its javadoc.
- One build at a time per working tree. Wait inline before every `mvn`:
  `while pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; do sleep 10; done; mvn ...`.
  Run one class with `mvn -q -o -pl app test -Dtest=ClassName -Djacoco.skip=true`.

**Gates.** Each must report zero, and each fails the build:
- static checks, run as `mvn -q -o -pl app compile pmd:check pmd:cpd-check spotbugs:check -Djacoco.skip=true` (PMD 7.27.0, CPD at 110 tokens, SpotBugs);
- the JaCoCo floors;
- diff coverage of about 90%;
- the i18n ratchets: `MessageKeyTest` (it also fails on orphan keys), `MessagePlaceholderContractTest` and `MessageFormatRegressionTest`.

**Code rules**
- Name every `@RequestParam` and `@PathVariable`.
- SLF4J: a Throwable is the last argument, and no `{}` consumes it.
- Container-managed classes take `@Lazy Weblogger` in the constructor. There is no static service locator.
- No code may assume a context path.
- Fail closed: a check that could not run is not a check that passed.

**Escaping and markup**
- Escaping follows CLAUDE.md:
  - author-controlled EL in a JSP goes through `fn:escapeXml`;
  - in Velocity, a comment's display name is emitted with `$utils.escapeHTML(...)`;
  - the rendered body is already-safe HTML from `CommentBodyRenderer`;
  - message arguments typed by users are escaped by the controller.
- Confirmations use `data-confirm`, never inline `onclick`.
- New admin pages get a `Routes` entry and a CSS marker in the same commit.
- Velocity is lenient: a reference to a missing member prints literally. Never write a zero-argument macro name with its `#` in a comment.
- New message keys go in `app/src/main/resources/ApplicationResources.properties`. In a message that takes arguments, an apostrophe is written `''`.

**Never log** a comment body, an email address, a token or a client secret. Logger `roller.comments` records ids, statuses, usernames and codes.

**Exact names, verbatim**
- Table: `roller_comment`.
- Columns:
  - `weblog.comments_enabled`, default false;
  - `weblog.comment_notify`, default true;
  - `weblogentry.comments_closed`, default false.
- Status enum: `ReaderComment.Status { PENDING, PUBLISHED, HIDDEN }`.
- Global action: `GlobalPermission.COMMENT = "comment"`.
- Role actions:
  - `role.action.reader=comment`
  - `role.action.editor=login,weblog,comment`
  - `role.action.admin=login,weblog,admin,comment`
- Limits:
  - the body is at most 4000 characters after trimming, and non-empty;
  - a display name is 1-50 code points;
  - more than 2 `http(s)` URLs means the comment is `PENDING`;
  - the rate limit is 5 per 10 minutes and 30 per day per account, keyed by user id.
- Endpoints (all under the context path):
  - `GET /roller-ui/rendering/comments/session.rol?entry=<id>`
  - `POST /roller-ui/rendering/comments.rol`
  - `DELETE /roller-ui/rendering/comments/{id}.rol`
  - `/roller-ui/authoring/comments.rol` (the moderation page)
  - `/roller-ui/account.rol`
  - `/api/v1/weblogs/{handle}/comments...`
- MCP: tool `comments` with actions `list`, `get`, `update`, `delete`, `reply`.

## Review Focus

Five inputs the spec implies but its criteria don't test, most likely to bite first. Each line names the test that pins it.

1. **Someone with an account but no permission on this weblog** comments. This covers a password-account editor of a different weblog, or a promoted reader. They get reader rules: their first comment is held and it carries no author badge. Becoming an owner makes the next one published, with the badge. (Task 3, `CommentServiceTest#anAuthorOfAnotherWeblogIsHeldLikeAReader`.)
2. **An entry with 200 published comments and replies** renders with a bounded number of queries, not one per comment or per author. (Task 2, `CommentManagerTest#publishedForEntryIsOneQuery`. Task 4 renders from that one list.)
3. **A display name containing `<script>` or `"><img onerror>`** shows as text everywhere:
   - on the entry page;
   - on the moderation page;
   - in the session JSON (the script inserts it with `textContent`);
   - in the notification email, which is plain text.

   (Tasks 4, 6, 7 and 10 each carry a test named `...displayNameIsEscaped...`.)
4. **Deleting an account** whose published comments, on two weblogs, each have an author reply. It removes the comments and the replies, and bumps both weblogs' `lastModified`. (Task 11, `AccountControllerTest#deletingAnAccountRemovesItsCommentsAndRepliesAndExpiresEachWeblog`.)
5. **Turning comments off on a weblog that already has them.** The section disappears from the page, posting answers 404, and moderation and the API still list and act on the existing comments. (Task 3, `CommentServiceTest#commentsOffStillAllowsModeration`. Task 9, `CommentsApiTest#moderationWorksWithCommentsOff`.)

---

### Task 1: The `comment` global action, with `GlobalPermission.implies` failing closed

**Why first:**
- `GlobalPermission.implies(Permission)` ends its `RollerPermission` branch with `return true`. A holder with neither `admin`, `weblog` nor `login` therefore implies every global permission, admin included.
- Today no such holder exists. Once readers hold only `comment`, every reader would pass every check.
- This task makes `implies` fail closed, and only then grants `comment`.

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/GlobalPermission.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`, at `role.action.*` (about lines 318-322)
- Test: `app/src/test/java/org/apache/roller/weblogger/pojos/GlobalPermissionTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/ReaderRefusalSweepTest.java`. It must stay green; read it, but do not change its assertions.

**Interfaces:**
- Produces:
  - `GlobalPermission.COMMENT = "comment"`;
  - the new `implies` contract, below;
  - the role actions listed in the Global Constraints.

- [ ] **Step 1: Write the failing tests.** Add them to `GlobalPermissionTest`, using the same construction style the file already uses: `GlobalPermission(List<String>)` for the required permission, and a user-bound `GlobalPermission` for the holder.

```java
@Test void aHolderOfOnlyCommentImpliesNothingButComment() throws Exception {
    GlobalPermission reader = holder(GlobalPermission.COMMENT);
    assertTrue(reader.implies(required(GlobalPermission.COMMENT)));
    for (String a : List.of(GlobalPermission.LOGIN, GlobalPermission.WEBLOG, GlobalPermission.ADMIN)) {
        assertFalse(reader.implies(required(a)), a);
    }
    assertFalse(reader.implies(required(GlobalPermission.LOGIN, GlobalPermission.COMMENT)));
}
@Test void anUnknownActionIsImpliedOnlyByAdmin() throws Exception {
    assertFalse(holder(GlobalPermission.LOGIN, GlobalPermission.WEBLOG).implies(required("frobnicate")));
    assertFalse(holder(GlobalPermission.COMMENT).implies(required("frobnicate")));
    assertTrue(holder(GlobalPermission.ADMIN).implies(required("frobnicate")));
}
@Test void editorsAndAdminsHoldComment() throws Exception {
    // via role.action.* in the shipped roller.properties
    assertTrue(GlobalPermission.impliedByRoles(user(), List.of("editor")).implies(required(GlobalPermission.COMMENT)));
    assertTrue(GlobalPermission.impliedByRoles(user(), List.of("admin")).implies(required(GlobalPermission.COMMENT)));
    GlobalPermission reader = GlobalPermission.impliedByRoles(user(), List.of("reader"));
    assertTrue(reader.implies(required(GlobalPermission.COMMENT)));
    assertFalse(reader.implies(required(GlobalPermission.LOGIN)));
}
```

Also add characterisation tests, which pass before and after the change:
- `admin` implies everything, including any `WeblogPermission`;
- `weblog` implies `login` and `weblog` but not `admin`;
- `login` implies `login` but not `weblog` or `admin`;
- an empty holder implies nothing;
- a non-admin holder never implies a `WeblogPermission`.

`holder(String...)` and `required(String...)` are small helpers.

- [ ] **Step 2: Run them.** Expected: `aHolderOfOnlyCommentImpliesNothingButComment` and `anUnknownActionIsImpliedOnlyByAdmin` FAIL, with `implies` returning true. `editorsAndAdminsHoldComment` FAILS because `comment` is not in the role actions.

- [ ] **Step 3: Implement.** Add the constant next to `ADMIN`, with javadoc saying it lets the holder post reader comments and implies nothing else. Then replace the `RollerPermission` branch so that **every** action the required permission names must be implied by the holder:

```java
} else if (perm instanceof RollerPermission) {
    if (hasAction(ADMIN)) {
        return true;                                  // admin implies all other permissions
    }
    for (String action : ((RollerPermission) perm).getActionsAsList()) {
        if (!impliesAction(action)) {
            return false;                             // fail closed, including for unknown actions
        }
    }
    return true;
}

/** Whether this holder (known not to hold ADMIN) has {@code action}: WEBLOG implies LOGIN; COMMENT stands alone. */
private boolean impliesAction(String action) {
    if (LOGIN.equals(action)) {
        return hasAction(LOGIN) || hasAction(WEBLOG);
    }
    if (WEBLOG.equals(action) || COMMENT.equals(action)) {
        return hasAction(action);
    }
    return false;
}
```

Then change `roller.properties` to the three role actions from the Global Constraints. Rewrite the comment above `role.action.reader` to say a reader holds `comment` only, and that it implies no authoring action.

- [ ] **Step 4: Run** `GlobalPermissionTest`, `ReaderRefusalSweepTest`, `RollerHandlerInterceptorTest`, `UserQueryAndRoleTest`, `TokensApiTest` and `BaseControllerTest`, then the static gates. Expected: PASS. If an existing test asserted the old fall-through, it was pinning the bug. Report it and update it with a reason.

- [ ] **Step 5: Commit.** Subject: `fix(security): GlobalPermission.implies fails closed; readers gain the comment action`.

---

### Task 2: Data: V031, `ReaderComment`, `CommentManager`, and the three settings columns

**Files:**
- Create: `bin/db/migrations/V031__reader_comments.sql`
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/ReaderComment.java`
- Create: `app/src/main/resources/org/apache/roller/weblogger/pojos/ReaderComment.orm.xml`, and register it in `app/src/main/resources/META-INF/persistence.xml` beside `Business.orm.xml`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/CommentManager.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPACommentManagerImpl.java`
- Modify, to wire it in the way `BusinessManager` is wired (`git show 292819cb3` shows every file):
  - `Weblogger.java` and `WebloggerImpl.java`;
  - `JPAWebloggerImpl.java`;
  - `business/jpa/WebloggerBeanConfig.java`;
  - the test double `app/src/test/java/org/apache/roller/weblogger/business/MockWeblogger.java`.
- Modify the weblog fields `commentsEnabled` and `commentNotify`:
  - `pojos/Weblog.java`, as `Boolean` fields with getters and setters and the copy-from-other block;
  - `Weblog.orm.xml`;
  - `pojos/wrapper/WeblogWrapper.java`.
- Modify the entry field `commentsClosed`:
  - `pojos/WeblogEntry.java`, including the copy constructor;
  - `WeblogEntry.orm.xml`;
  - `WeblogEntryWrapper.java`.
- Test: `app/src/test/java/org/apache/roller/weblogger/business/CommentManagerTest.java` (DB-backed, like `BusinessManagerTest`)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/CommentsMigrationTest.java`, modelled on `UserSsoIdentityMigrationTest`
- Test: add a `Specimen` for `ReaderComment` to `EqualsContractTest`

**Interfaces:**
- Produces:

```java
public class ReaderComment implements Serializable {        // id = UUIDGenerator.generateUUID(); equals/hashCode on id
    public enum Status { PENDING, PUBLISHED, HIDDEN }
    // fields + getters/setters: id, entry (WeblogEntry, many-to-one), author (User, many-to-one),
    // replyTo (ReaderComment, many-to-one, nullable), body (String), status (Status, EnumType.STRING),
    // authorIsOwner (boolean), created (Date), statusChanged (Date)
    // inverse side, read-only: reply (ReaderComment, one-to-one mappedBy="replyTo") -> getReply()
}
public interface CommentManager {
    void saveComment(ReaderComment c) throws WebloggerException;      // store (insert or update)
    void removeComment(ReaderComment c) throws WebloggerException;    // delete; its reply goes with it (cascade)
    ReaderComment getComment(String id) throws WebloggerException;    // null when absent
    /** PUBLISHED top-level comments of the entry, oldest first, each with its PUBLISHED reply fetched — ONE query. */
    List<ReaderComment> getPublishedForEntry(WeblogEntry entry) throws WebloggerException;
    /** Any status filter (null = all), newest first, paged; entry may be null. */
    List<ReaderComment> getComments(Weblog weblog, ReaderComment.Status status, WeblogEntry entry,
                                    int offset, int length) throws WebloggerException;
    long countComments(Weblog weblog, ReaderComment.Status status) throws WebloggerException;
    long countForEntry(WeblogEntry entry, ReaderComment.Status status) throws WebloggerException;
    List<ReaderComment> getPendingForEntryAndAuthor(WeblogEntry entry, User author) throws WebloggerException;
    List<ReaderComment> getCommentsByAuthor(User author) throws WebloggerException;   // newest first, all weblogs
    /** At least one PUBLISHED and no HIDDEN comment by this author on this weblog. */
    boolean isTrusted(User author, Weblog weblog) throws WebloggerException;
    /** The distinct weblogs where this author has a PUBLISHED comment (for cache bumps on rename/delete). */
    List<Weblog> getWeblogsWithPublishedCommentsBy(User author) throws WebloggerException;
}
```

`Weblogger.getCommentManager()`. `Weblog.getCommentsEnabled()/setCommentsEnabled(Boolean)`, `getCommentNotify()/setCommentNotify(Boolean)`. `WeblogEntry.getCommentsClosed()/setCommentsClosed(Boolean)`. Wrappers expose the three getters. Like `BusinessManager`, the manager does not flush; callers flush.

- [ ] **Step 1: Write the failing migration test.** Model it on `UserSsoIdentityMigrationTest`, a fresh database through the migration chain. It asserts:
  - the table and columns exist;
  - the three new columns' defaults: `comments_enabled` false, `comment_notify` true, `comments_closed` false;
  - `status` rejects a value outside the three, through a CHECK constraint, with SQLState `23514`;
  - deleting an entry row deletes its comments;
  - deleting a user row deletes theirs;
  - deleting a parent comment deletes its reply;
  - `SchemaMigrationTest#everyMigrationIsIdempotent` still passes.

- [ ] **Step 2: Run it.** Expected: FAIL, because the relation does not exist. Write V031, copying V029's Apache header:

```sql
-- Migration: comments for signed-in readers (docs/superpowers/specs/2026-10-12-reader-comments-design.md).
-- A fresh table: the W1 comment subsystem (dropped in V022) is not restored. Idempotent.

CREATE TABLE IF NOT EXISTS roller_comment (
    id              varchar(48)  NOT NULL PRIMARY KEY,
    entry_id        varchar(48)  NOT NULL REFERENCES weblogentry(id) ON DELETE CASCADE,
    author_id       varchar(48)  NOT NULL REFERENCES roller_user(id) ON DELETE CASCADE,
    reply_to_id     varchar(48)  REFERENCES roller_comment(id) ON DELETE CASCADE,
    body            text         NOT NULL,
    status          varchar(16)  NOT NULL,
    author_is_owner boolean      DEFAULT false NOT NULL,
    created         timestamp(3) with time zone NOT NULL,
    status_changed  timestamp(3) with time zone NOT NULL
);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'rc_status_ck') THEN
        ALTER TABLE roller_comment ADD CONSTRAINT rc_status_ck
            CHECK (status IN ('PENDING', 'PUBLISHED', 'HIDDEN'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS rc_entry_status_created_idx ON roller_comment (entry_id, status, created);
CREATE INDEX IF NOT EXISTS rc_author_idx ON roller_comment (author_id);
CREATE INDEX IF NOT EXISTS rc_reply_to_idx ON roller_comment (reply_to_id);

ALTER TABLE weblog      ADD COLUMN IF NOT EXISTS comments_enabled boolean DEFAULT false NOT NULL;
ALTER TABLE weblog      ADD COLUMN IF NOT EXISTS comment_notify   boolean DEFAULT true  NOT NULL;
ALTER TABLE weblogentry ADD COLUMN IF NOT EXISTS comments_closed  boolean DEFAULT false NOT NULL;
```

Run it again. Expected: PASS.

- [ ] **Step 3: Write the failing `CommentManagerTest`.** Use `TestUtils.setupWeblogger`, `setupUser`, `setupWeblog` and `setupWeblogEntry`, and tear everything down. The tests:
  - `saveGetRemoveRoundTrip`.
  - `publishedForEntryIsOneQuery` (Review Focus 2):
    1. Create 40 published comments with 20 replies, plus some `PENDING` and `HIDDEN` ones.
    2. `getPublishedForEntry` returns exactly the published top-level ones, oldest first, each with `getReply()` loaded where one is `PUBLISHED`.
    3. Assert the query count. Use EclipseLink's `SessionProfiler` or a counting `SessionEventListener` if the repository has one; otherwise assert the JPQL uses `JOIN FETCH` for author and reply by reading the named-query text from the ORM. State which in the report.
  - `getCommentsFiltersByStatusAndEntryAndPages`.
  - `countsPerWeblogAndEntry`.
  - `pendingForEntryAndAuthor`.
  - `byAuthorAcrossWeblogs`.
  - `trustNeedsAPublishedAndNoHidden`:
    - none: false;
    - one `PENDING`: false;
    - one `PUBLISHED`: true;
    - a `PUBLISHED` plus a `HIDDEN`: false;
    - `PUBLISHED` on another weblog only: false.
  - `weblogsWithPublishedCommentsBy`.
  - `theNewColumnsMapAndDefault`. A new weblog has `commentsEnabled` false and `commentNotify` true; a new entry has `commentsClosed` false. All three round-trip true and false.

  `getReply()` reads the inverse side `reply`, mapped one-to-one with `mappedBy="replyTo"`, as listed in the Interfaces block.

- [ ] **Step 4: Run it.** Expected: compile FAIL. Implement the pojo, the ORM with named queries (positional `?1` parameters, as `Business.orm.xml` does), the manager and the wiring. Run it again. Expected: PASS. Then run `SchemaMigrationTest`, `MigrationCatalogTest`, `EqualsContractTest`, `BusinessManagerTest` (a wiring smoke test), `WeblogTest` and `WeblogEntryTest`, then the static gates.

- [ ] **Step 5: Commit.** Subject: `feat(comments): roller_comment (V031), ReaderComment, CommentManager, settings columns`.

---

### Task 3: `CommentService`: every rule in one place

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/comments/CommentService.java`, a `@Component` taking `@Lazy Weblogger`
- Create: `.../business/comments/CommentRules.java`, pure static functions
- Create: `.../business/comments/CommentOutcome.java`, a record
- Create: `.../business/comments/CommentRefusal.java`, an enum of refusal codes
- Create: `.../business/comments/package-info.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/comments/CommentRulesTest.java`
- Test: `.../business/comments/CommentServiceTest.java` (DB-backed)

**Interfaces:**
- Consumes (Task 1): `GlobalPermission.COMMENT`.
- Consumes (Task 2): `CommentManager`, `ReaderComment`, the new getters.
- Produces:

```java
public enum CommentRefusal { NOT_SIGNED_IN, NO_PERMISSION, DISABLED, NOT_FOUND, CLOSED,
                             COMMENTS_OFF, CUSTOM_DOMAIN, EMPTY_BODY, BODY_TOO_LONG, BAD_REPLY, RATE_LIMITED }
public record CommentOutcome(ReaderComment comment, CommentRefusal refusal) {  // exactly one non-null
    public boolean ok() { return refusal == null; }
}
public final class CommentRules {
    public static final int MAX_BODY = 4000;
    public static final int MAX_LINKS_BEFORE_HOLD = 2;
    public static String normalizeBody(String raw);          // trim; null -> ""
    public static int countHttpLinks(String body);           // http:// and https:// occurrences, case-insensitive
    public static ReaderComment.Status statusFor(boolean owner, boolean trusted, String body);
        // owner -> PUBLISHED; trusted && links <= 2 -> PUBLISHED; else PENDING
}
@Component
public class CommentService {
    public CommentService(@Lazy Weblogger weblogger);
    /** Why this account may not post on this entry, or null when it may. Re-reads the account (enabled). */
    public CommentRefusal whyNot(User user, WeblogEntry entry) throws WebloggerException;
    public boolean isOwner(User user, Weblog weblog) throws WebloggerException;   // holds any WeblogPermission
    public CommentOutcome post(User user, String entryId, String body, String replyToId) throws WebloggerException;
    public CommentOutcome reply(User owner, ReaderComment parent, String body) throws WebloggerException;
    public void setStatus(ReaderComment c, ReaderComment.Status status, User actor) throws WebloggerException;
    public void delete(ReaderComment c, User actor) throws WebloggerException;
    /** A reader deletes their own comment; false (and nothing done) when it is not theirs. */
    public boolean deleteOwn(User user, String commentId) throws WebloggerException;
    /** Bumps lastModified (saveWeblog sets it) on the weblog, so rendered pages expire. */
    void expire(Weblog weblog) throws WebloggerException;
}
```

Rules, verbatim from the spec:
- **The order of `whyNot`'s checks:**
  1. `NOT_SIGNED_IN`: the user is null.
  2. `DISABLED`: the user is re-read through `getUserByUserName(name, null)` and is not enabled.
  3. `NO_PERMISSION`: no global `COMMENT`.
  4. `NOT_FOUND`: the entry is null or not `PUBLISHED`.
  5. `COMMENTS_OFF`: the weblog's `commentsEnabled` is not TRUE.
  6. `CUSTOM_DOMAIN`: the weblog has a non-blank custom domain.
  7. `CLOSED`: the entry's `commentsClosed` is TRUE.
- **`post` then checks the reply and the body:**
  1. If `replyToId` is set: `BAD_REPLY` unless the poster is an owner and the parent is `PUBLISHED`, top-level, on the same entry, and has no reply yet.
  2. `EMPTY_BODY` or `BODY_TOO_LONG`, on the normalised body.
- **The rate limit** is two `GenericThrottle` instances, (5, 600 s) and (30, 86 400 s), with `maxEntries` 1000. They are checked through `isAbusive(userId)`, and a hit is recorded only when a comment is stored. Owners' comments count too. The limit gives `RATE_LIMITED`.
- **The stored comment:**
  - it is `PUBLISHED` when it is a reply, or when `statusFor(isOwner, isTrusted, body)` says so; otherwise `PENDING`;
  - `authorIsOwner` is `isOwner`;
  - `created` and `statusChanged` are set to now.
- **Expiry and notification:**
  - `post` calls `expire(weblog)` only when the stored status is `PUBLISHED`.
  - `setStatus` calls `expire` only when the change moves into or out of `PUBLISHED`.
  - `delete` calls `expire` when the comment was `PUBLISHED`.
  - `reply` always calls `expire`.
  - Then `weblogger.flush()`.
  - When the stored status is `PENDING`, `post` calls the Task 10 hook `CommentNotifier.notifyHeld(comment)`. Until Task 10 exists, define a `CommentNotifier` interface in this package with a no-op `@Component` `NoOpCommentNotifier`. Task 10 replaces that bean.
- **Logging** to `roller.comments`, for example "comment {id} stored {status} on entry {entryId} by {username}". It never logs the body or an email address.

- [ ] **Step 1: Write the failing `CommentRulesTest`.**

```java
@ParameterizedTest
@CsvSource(delimiter = '|', value = {
    "true|false|plain|PUBLISHED", "false|true|plain|PUBLISHED", "false|false|plain|PENDING",
    "false|true|http://a https://b|PUBLISHED", "false|true|http://a https://b HTTP://c|PENDING",
    "true|false|http://a http://b http://c|PUBLISHED"})
void statusFor(boolean owner, boolean trusted, String body, ReaderComment.Status expected) {
    assertEquals(expected, CommentRules.statusFor(owner, trusted, body));
}
@Test void bodyLimits() { /* "  " -> ""; 4000 chars ok; 4001 -> too long (via service test); null -> "" */ }
```

- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS.

- [ ] **Step 3: Write the failing `CommentServiceTest`.** It is DB-backed. Use a weblog with comments on, a published entry, a reader (`addReader`, an SSO-bound user with `role.action.reader=comment`) and an owner (the weblog creator). It covers spec criteria 4-13 at the service level and Review Focus 1 and 5:
  - `aReadersFirstCommentIsHeld` (4).
  - `afterApprovalTheNextIsPublishedButNotOnAnotherWeblog` (5).
  - `threeLinksAreHeldEvenWhenTrusted` (6).
  - `hidingOneSendsTheReaderBackToHeld` (7).
  - `ownerCommentsAndRepliesArePublishedAndMarked` (8).
  - `badRepliesAreRefused` (9). Cover:
    - a non-owner reply;
    - a reply to a `PENDING` comment;
    - a reply to a reply;
    - a reply to another entry's comment;
    - a second reply to the same comment.
  - `bodyLimits` (10).
  - `notFoundClosedOffAndCustomDomainAreRefusedAndStoreNothing` (11). Cover a draft, a scheduled entry, a pending entry, a trashed entry, an unknown id, a closed entry, comments off, and a custom domain.
  - `disabledAndPermissionlessAccountsAreRefused` (12, service side). A user disabled after loading is refused, because the service re-reads the account.
  - `theSixthIn10MinutesAndThe31stInADayAreRateLimited` (13). Build the service with injectable throttles through a package-private constructor, so the test does not wait.
  - `publishedChangesExpireTheWeblogPendingDoesNot` (18). Compare `weblog.getLastModified()` before and after each operation.
  - `anAuthorOfAnotherWeblogIsHeldLikeAReader` (Review Focus 1). An editor account with no permission on this weblog is held and gets no badge. After it is made a member, its next comment is `PUBLISHED` with `authorIsOwner`.
  - `commentsOffStillAllowsModeration` (Review Focus 5). With comments switched off, `setStatus`, `delete` and `reply` still work, and `post` gives `COMMENTS_OFF`.
  - `heldCommentsCallTheNotifierPublishedOnesDoNot`, with a Mockito spy on `CommentNotifier`.
  - `noLogLineCarriesTheBody` (criterion 31). Capture `roller.comments` (see `SsoLogCapture`) across post, setStatus, delete and reply, using a body containing a unique marker and a reader whose email contains another. Assert that neither marker is ever logged.

- [ ] **Step 4: Run, implement, run.** Expected: FAIL, then PASS. Then run the static gates.

- [ ] **Step 5: Commit.** Subject: `feat(comments): CommentService holds every posting, trust and moderation rule`.

---

### Task 4: Rendering the comments section on entry pages

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/comments/CommentBodyRenderer.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/pojos/wrapper/ReaderCommentWrapper.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/wrapper/WeblogEntryWrapper.java`, adding `getComments()` and `getCommentCount()`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/UtilitiesModel.java`, adding `getCommentEndpoints()` and `isSsoEnabled()`
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm`, adding a new macro `showComments $weblog $entry`, with its styles in the macro
- Modify: `app/src/main/webapp/themes/journal/permalink.vm`, `themes/travel/permalink.vm` and `themes/portfolio/permalink.vm`. Call the macro after the entry body. Find the right spot in each: under the entry, before the footer.
- Modify: `ApplicationResources.properties`, adding the `comments.*` keys below
- Test: `.../business/comments/CommentBodyRendererTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/CommentsSectionRenderingTest.java`. Model it on `FrontpageHeaderRenderingTest`, or on whichever test renders a theme permalink with a stub model.

**Interfaces:**
- Consumes: Task 2's `getPublishedForEntry` and `countForEntry`.
- Produces:

```java
public final class CommentBodyRenderer {
    /** Escape -> link http(s) URLs (rel="nofollow ugc noopener") -> paragraphs/<br> -> HTMLSanitizer.sanitize. */
    public static String toHtml(String rawBody);
}
public final class ReaderCommentWrapper {        // what templates see
    public String getId(); public String getDisplayName();   // RAW: templates escape it
    public boolean isAuthorIsOwner(); public Date getCreated();
    public String getBodyHtml();                  // CommentBodyRenderer.toHtml(body)
    public ReaderCommentWrapper getReply();       // PUBLISHED reply or null
    public String getAuthorUserName();            // for data-author (delete link matching)
}
// WeblogEntryWrapper:
public List<ReaderCommentWrapper> getComments();   // empty list on any lookup failure (display path), logged
public long getCommentCount();                     // PUBLISHED top-level count
// UtilitiesModel:
public Map<String, String> getCommentEndpoints();  // {"session": ctx+"/roller-ui/rendering/comments/session.rol", "post": ctx+"/roller-ui/rendering/comments.rol", "delete": ctx+"/roller-ui/rendering/comments/"}
```

**The macro.** The script hooks onto these classes and data attributes verbatim:

```velocity
#macro(showComments $weblog $entry)
#if($weblog.commentsEnabled)
<section class="comments" id="comments" aria-labelledby="comments-heading">
  <h2 id="comments-heading">$text.get("comments.heading", [$entry.commentCount])</h2>
  #foreach($c in $entry.comments)
  <article class="comment#if($c.authorIsOwner) comment-author#end" id="comment-$c.id" data-author="$utils.escapeHTML($c.authorUserName)">
    <header><span class="comment-name">$utils.escapeHTML($c.displayName)</span>#if($c.authorIsOwner) <span class="comment-badge">$text.get("comments.authorBadge")</span>#end
      <time datetime="$utils.formatIso8601Date($c.created)">$utils.formatDate($c.created, $text.get("comments.dateFormat"))</time></header>
    <div class="comment-body">$c.bodyHtml</div>
    #if($c.reply)
    <div class="comment-reply comment-author" id="comment-$c.reply.id">
      <header><span class="comment-name">$utils.escapeHTML($c.reply.displayName)</span> <span class="comment-badge">$text.get("comments.authorBadge")</span>
        <time datetime="$utils.formatIso8601Date($c.reply.created)">$utils.formatDate($c.reply.created, $text.get("comments.dateFormat"))</time></header>
      <div class="comment-body">$c.reply.bodyHtml</div>
    </div>
    #end
  </article>
  #end
  #if($entry.commentsClosed)
  <p class="comments-closed">$text.get("comments.closed")</p>
  #elseif($utils.isNotEmpty($weblog.customDomain))
  <div class="comments-slot" data-unavailable="custom-domain"></div>
  #else
  <div class="comments-slot" data-entry="$entry.id" data-permalink="$utils.escapeHTML($entry.permalink)"
       data-session="$utils.commentEndpoints.session" data-post="$utils.commentEndpoints.post"
       data-delete="$utils.commentEndpoints.delete"></div>
  #end
</section>
#end
#end
```

Check that every helper the macro calls exists in `UtilitiesModel` (`formatIso8601Date`, `formatDate`, `isNotEmpty`). Use the real names you find, because Velocity prints an unknown reference literally. `$entry.permalink` must be the path the reader returns to; read `WeblogEntryWrapper`, and use the relative permalink with the context path, not an absolute URL. The `comments.heading` key takes `{0}`. `MessageFormatRegressionTest` applies, so write any apostrophe as `''`.

Message keys:
```properties
comments.heading={0,choice,0#Comments|1#1 comment|1<{0} comments}
comments.authorBadge=Author
comments.closed=Comments are closed.
comments.dateFormat=d MMM yyyy
comments.unavailableHere=Commenting is not available on this address yet.
comments.signIn=Sign in with Google to comment
```

- [ ] **Step 1: Write the failing `CommentBodyRendererTest`** (criterion 16).

```java
@ParameterizedTest
@ValueSource(strings = {
    "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "javascript:alert(1)",
    "<a href=\"javascript:alert(1)\">x</a>", "data:text/html;base64,PHNjcmlwdD4=",
    "&lt;script&gt;alert(1)&lt;/script&gt;", "‮gnp.exe", "a​b<b>c</b>"})
void hostileBodiesRenderAsText(String body) {
    String html = CommentBodyRenderer.toHtml(body);
    assertFalse(html.toLowerCase(Locale.ROOT).contains("<script"));
    assertFalse(html.toLowerCase(Locale.ROOT).contains("onerror="));
    assertFalse(html.toLowerCase(Locale.ROOT).contains("href=\"javascript:"));
    assertFalse(html.toLowerCase(Locale.ROOT).contains("href=\"data:"));
}
@Test void httpUrlsBecomeNofollowLinks() {
    assertEquals("<p>see <a href=\"https://ex.com/a?b=1&amp;c=2\" rel=\"nofollow ugc noopener\">https://ex.com/a?b=1&amp;c=2</a></p>",
            CommentBodyRenderer.toHtml("see https://ex.com/a?b=1&c=2"));
}
@Test void blankLinesMakeParagraphsAndNewlinesBreaks() {
    assertEquals("<p>a<br>b</p><p>c</p>", CommentBodyRenderer.toHtml("a\nb\n\nc"));
}
```

Run `HTMLSanitizer.sanitize` once and look at its output: it may normalise `<br>` to `<br />`. Write the expected strings to match the sanitizer's actual output, and keep the assertions exact.

- [ ] **Step 2: Run, implement, run.** URLs match `https?://[^\s<>"']+`, with trailing punctuation `.,;:!?)` left out of the link. Escaping happens before linkify, and the link text is the escaped URL. Expected: FAIL, then PASS.

- [ ] **Step 3: Write the failing `CommentsSectionRenderingTest`.** Render the journal permalink with a stub weblog, an entry and a stubbed `getPublishedForEntry`.
  - Criterion 14: with comments off there is no `class="comments"`.
  - Criterion 15:
    - only published top-level comments appear, oldest first;
    - a published reply appears under its parent;
    - nothing appears for a hidden parent's reply, because the manager never returns one; assert that through the stub's contract.
  - Criterion 17: two renders are identical from `<section class="comments"` to its `</section>`. One has no principal; the other has a reader principal, as `ReaderCacheKeyTest` builds it.
  - Criterion 19: a closed entry shows "Comments are closed." and no `comments-slot`.
  - Criterion 22, render half: a weblog with a custom domain renders `data-unavailable="custom-domain"`.
  - `displayNameIsEscapedOnThePage` (Review Focus 3): the name `<script>x</script>` appears as `&lt;script&gt;`.
  - Travel and portfolio each call the macro too. A source-scanning assertion on the three `permalink.vm` files is enough.

- [ ] **Step 4: Run, implement, run.** Expected: FAIL, then PASS. Then run `ThemeCspCoverageTest`, `ThemeReferenceLeakTest` and any theme-scanning tests, the message ratchets, and the static gates.

- [ ] **Step 5: Commit.** Subject: `feat(comments): render published comments into entry pages`.

---

### Task 5: The rendering endpoints: session, post, delete

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/CommentsController.java`. Put it in the `/roller-ui/rendering` mapping, like `ContactController`, with `isUserRequired()` false. It does its own sign-in checks and answers in JSON.
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/CommentsControllerTest.java`. Use MockMvc with Spring Security's `springSecurity()` and CSRF enabled; see `SecurityConfigTest`'s harness and `SsoLoginWiringTest`.

**Interfaces:**
- Consumes: Task 3's `CommentService` and Task 4's `UtilitiesModel` endpoint paths.
- Produces the JSON contract the script (Task 6) reads:

```
GET  /roller-ui/rendering/comments/session.rol?entry=<id>     (Cache-Control: no-store)
  anonymous   -> {"signedIn":false, "signInUrl": "<ctx>/roller-ui/sso/start?return=<url-encoded entry path>"}   (signInUrl absent when SSO is off)
  can't post  -> {"signedIn":true, "canPost":false, "reason":"closed|disabled|custom-domain|no-permission|comments-off|not-found"}
  can post    -> {"signedIn":true, "canPost":true, "displayName":"…", "userName":"…", "isOwner":bool,
                  "csrf":{"header":"X-CSRF-TOKEN","token":"…"}, "accountUrl":"<ctx>/roller-ui/account.rol",
                  "pending":[{"id":"…","body":"…","created":"ISO-8601"}]}
POST /roller-ui/rendering/comments.rol   body {"entry":"…","body":"…","replyTo":"…"?}   header X-CSRF-TOKEN
  201 {"id":"…","status":"PUBLISHED|PENDING"} | 400 {"error":"empty-body|body-too-long|bad-reply"} | 401 | 403 | 404 | 429
DELETE /roller-ui/rendering/comments/{id}.rol   header X-CSRF-TOKEN   -> 204 | 401 | 404
```

Implementation notes:
- The signed-in user is the one the interceptor resolves (`getAuthenticatedUser(request)`).
- The CSRF token comes from `(CsrfToken) request.getAttribute(CsrfToken.class.getName())` (or `"_csrf"`), using its `getHeaderName()` and `getToken()`.
- `signInUrl` is built only when the SSO settings say SSO is enabled. Inject the `SsoSettings` bean.
- The entry path for `return` is the entry's relative permalink.
- Map refusals to codes:
  - `NOT_FOUND`, `CLOSED`, `COMMENTS_OFF` and `CUSTOM_DOMAIN` answer 404 on post;
  - `DISABLED` and `NO_PERMISSION` answer 403;
  - `RATE_LIMITED` answers 429.
- The session endpoint answers 404 for an unknown or unpublished entry, so it leaks nothing.
- POST needs no change to `SecurityConfig`: `/roller-ui/rendering/**` is `permitAll`, and Spring's CSRF filter rejects a POST or DELETE without the token with 403. Assert that nothing is added to `isPublicAudiencePost`.

- [ ] **Step 1: Write the failing tests.** They cover criteria 11, 12, 13, 20, 22 (the endpoint half) and 23, and they assert the exact JSON:
  - `theSessionAnswersAnonymousWithASignInUrlOnlyWhenSsoIsOn`.
  - `theSessionGivesAReaderTheirOwnPendingAndAToken`. Another reader's pending comment is never present. The response header is `Cache-Control: no-store`.
  - `theSessionGivesReasonsForThoseWhoCannotPost`, one per reason.
  - `postingWithoutASessionIs401`, `withoutCsrfIs403`, `disabledIs403`.
  - `postingStoresPendingForANewReaderAnd201`.
  - `postingToAClosedOrCustomDomainEntryIs404`.
  - `theSixthPostIn10MinutesIs429`.
  - `deletingOwnIs204AndOthersIs404`.
  - `theSessionJsonCarriesTheDisplayNameAsData`. A name with `<script>` comes back JSON-escaped, never as HTML.
  - `noCsrfExemptionWasAdded`: `SecurityConfig.isPublicAudiencePost` is false for both comment paths.

- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Then run `SecurityConfigTest`, `ReaderRefusalSweepTest` (`CommentsController` is not user-required, so the sweep skips it; confirm that) and the static gates.

- [ ] **Step 3: Commit.** Subject: `feat(comments): session, post and delete endpoints for the page script`.

---

### Task 6: The comment form script

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm`. Add the comments script to the shared audience-assets macro, next to the contact and subscribe code.
- Also add the user-facing strings to Task 4's `comments-slot` div in the showComments macro, each rendered with `$text.get(...)`, so the script carries no English:
  - `data-msg-sign-in` (`comments.signIn`);
  - `data-msg-unavailable` (`comments.unavailableHere`);
  - `data-msg-pending` (`comments.pending`);
  - `data-msg-awaiting` (`comments.awaiting`);
  - `data-msg-published` (`comments.published`);
  - `data-msg-error` (`comments.error`);
  - `data-msg-rate` (`comments.rateLimited`);
  - `data-msg-signed-in-as` (`comments.signedInAs`, with `{0}` replaced in the script by the display name, through `textContent`);
  - `data-msg-account` (`comments.yourAccount`);
  - `data-msg-delete` (`comments.deleteOwn`);
  - `data-msg-delete-confirm` (`comments.deleteOwn.confirm`);
  - `data-msg-post` (`comments.post`);
  - `data-msg-closed` (`comments.closed`).

  The custom-domain slot carries `data-msg-unavailable` too. Add these keys to `ApplicationResources.properties`:
  `comments.pending=Thanks — your comment will appear once it is approved.`,
  `comments.awaiting=Awaiting approval`, `comments.published=Published.`,
  `comments.error=Your comment could not be posted. Please try again.`,
  `comments.rateLimited=You are commenting too fast. Please wait a while.`,
  `comments.signedInAs=Signed in as {0}`, `comments.yourAccount=Your account`,
  `comments.deleteOwn=Delete`, `comments.deleteOwn.confirm=Delete your comment?`,
  `comments.post=Post comment`.
  `MessageKeyTest` must see each key referenced; Velocity `$text.get("...")` literals count if that test scans `.vm` files. Check, and if they do not count, reference each key the way existing theme strings do.
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/CommentsScriptSourceTest.java`

**Interfaces:** Consumes Task 4's slot attributes and Task 5's JSON contract.

The behaviour:
- **Finding the slots.** For each `.comments-slot`:
  - if it has `data-unavailable="custom-domain"`, show `comments.unavailableHere`. The text comes from a `data-msg-*` attribute that the macro renders with `$text`, so the script carries no English.
  - otherwise check `data-session`, `data-post` and `data-delete` with an `endpointOk` suffix check: `/roller-ui/rendering/comments/session.rol`, `/roller-ui/rendering/comments.rol` and `/roller-ui/rendering/comments/`.
- **The session call.** Fetch the session with `credentials: 'same-origin'`.
  - **Anonymous with `signInUrl`:** a link "Sign in with Google to comment". The text comes from a data attribute.
  - **Anonymous without it:** nothing.
  - **`canPost` false:** a short reason line.
  - **`canPost` true:** a form with a textarea (`maxlength` 4000) and a Post button, plus "Signed in as {displayName} · Your account". Then:
    - list `pending` items as "awaiting approval";
    - add a Delete link to every `article.comment` whose `data-author` equals `userName`. Deletion asks `confirm()`, sends a fetch `DELETE`, and removes the article on 204.
- **Posting** sends the JSON with the CSRF header.
  - On 201 `PUBLISHED`, reload the page with a cache-busting query (`?c=<id>#comment-<id>`).
  - On 201 `PENDING`, show the "will appear once it is approved" message and add the comment to the awaiting list.
  - On 400, 403, 404 or 429, show the matching message.
- **Text handling.** Every server string is inserted with `textContent` or `createTextNode`. The script never assigns `innerHTML` from server data; the form's static skeleton is built with `createElement`.

**A page that must be reloaded after a publish** gets a fresh render because `CommentService` bumped `lastModified`. The `?c=` query avoids the browser's HTTP cache.

- [ ] **Step 1: Write the failing source test.** It extracts the comments part of the audience-assets macro, from a marker comment `// --- comments ---` to `// --- end comments ---`, and asserts that:
  - there are no `innerHTML` and no `insertAdjacentHTML`;
  - it uses `textContent`;
  - the three `endpointOk` suffixes are the exact strings above;
  - `credentials: 'same-origin'` is present;
  - the CSRF header name comes from the session JSON (`csrf.header`), not a literal.

  It also asserts `displayNameIsEscapedInTheScriptByTextContent` (Review Focus 3): the display name is assigned through `textContent`.

- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Real behaviour is proven by the Task 12 browser tests. Then run `ThemeCspCoverageTest` and the static gates.

- [ ] **Step 3: Commit.** Subject: `feat(comments): the page script builds the comment form from the session endpoint`.

---

### Task 7: The moderation page and the Main Menu count

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/CommentsModerationController.java`, under `/roller-ui/authoring`, requiring the weblog `POST` permission
- Create: `app/src/main/webapp/WEB-INF/jsps/editor/Comments.jsp`
- Modify:
  - `ui/controllers/RollerViewResolver.java`: tile `.Comments` on `.tiles-tabbedpage`, pinned in `RollerViewResolverTest`;
  - `app/src/main/resources/org/apache/roller/weblogger/ui/menu/editor-menu.xml`: `<menu-item action="comments" name="tabbedmenu.comments" weblogPerms="post"/>`, placed after `submissions`, and pinned in `MenuDefinitionTest`;
  - `BaseController.SWITCHER_ACTIONS`, adding `"comments"`, pinned in `BaseControllerTest`;
  - `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java`: `new Route("/roller-ui/authoring/comments.rol", Role.EDITOR, WEBLOG, "#commentsModerationForm")`;
  - `ui/controllers/WeblogPermissionView.java`, adding `getPendingCommentCount()` in the lazily cached style of `getEntryCount()`, falling back to 0 with a log line;
  - `app/src/main/webapp/WEB-INF/jsps/core/MainMenu.jsp`: a badge beside the Entries link, gated on `perms.hasAction('post')` and shown only when the count is above 0;
  - `ApplicationResources.properties`: the keys below.
- Test: `.../ui/controllers/editor/CommentsModerationControllerTest.java`
- Test: extend `JspConsistencyTest` and `AdminJspHygieneTest` where they scan JSPs; the new page has one `.selection-bar`
- Test: a `MainMenu` test for the badge

**Interfaces:** Consumes Task 3's `CommentService.setStatus`, `delete` and `reply`, and Task 2's `getComments` and `countComments`.

**Endpoints:**
- `GET /roller-ui/authoring/comments.rol?weblog=<h>&status=PENDING|PUBLISHED|HIDDEN&page=<n>`. The default status is `PENDING`, with 30 rows per page.
- `POST /roller-ui/authoring/comments!approve.rol`, `comments!hide.rol` and `comments!delete.rol`. Each takes `@RequestParam(name="ids") String[] ids` plus the weblog, and handles one row or many, the bulk pattern `FormSubmissionsController` uses. Each id is ownership-checked against the action weblog, and foreign ids are skipped.
- `POST /roller-ui/authoring/comments!reply.rol`, with `@RequestParam(name="id")` and `@RequestParam(name="body")`.

**The JSP:**
- Tabs for the three statuses.
- One `<form id="commentsModerationForm">` around the table, with `<sec:csrfInput/>`, a `data-select-all` checkbox and per-row checkboxes.
- The bulk buttons are Approve, Hide and Delete. Delete carries `data-confirm`.
- Each row has its own Approve, Hide and Delete buttons, posting the single id through `formaction` and `name="ids" value="<id>"`.
- Each row has a Reply `<details>` holding a textarea and a submit.
- Every user string is `fn:escapeXml`'d: entry title, display name, username and body. The body shows as escaped plain text with `white-space: pre-wrap`.
- An "Author" badge marks `authorIsOwner`.

Keys:
```properties
tabbedmenu.comments=Comments
comments.title=Comments
comments.tab.pending=Awaiting approval
comments.tab.published=Published
comments.tab.hidden=Hidden
comments.approve=Approve
comments.hide=Hide
comments.delete=Delete
comments.delete.confirm=Delete the selected comments? Their author replies go with them.
comments.reply=Reply
comments.reply.post=Post reply
comments.none=No comments here.
comments.approved={0} approved.
comments.hidden={0} hidden.
comments.deleted={0} deleted.
comments.replied=Reply posted.
comments.reply.refused=That comment cannot take a reply.
mainMenu.pendingComments={0} awaiting approval
```

- [ ] **Step 1: Write the failing tests** (criterion 24, the UI half; criterion 25, the Main Menu half):
  - `listsByTabAndPages`.
  - `approveHideDeleteAndBulk`. Each changes status through the service; verify the service calls and the flash message.
  - `foreignIdsAreSkipped`.
  - `replyPostsAnAuthorReply`, and `aRefusedReplyShowsTheMessage`.
  - `aReaderAndAMemberOfAnotherWeblogAreRefused`. This goes through the interceptor's permission declaration: assert `requiredWeblogPermissionActions() == [post]`, and `ReaderRefusalSweepTest` picks the new controller up automatically.
  - `displayNameIsEscapedOnTheModerationPage` (Review Focus 3). Use a JSP source assertion that every `${c.…}` user field is wrapped in `fn:escapeXml`, in the style `EditorJspEscapingTest` uses; extend that test's file list.
  - `mainMenuShowsThePendingCount`.

- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Then run `RollerViewResolverTest`, `MenuDefinitionTest`, `BaseControllerTest`, `RouteCoverageTest`, `JspConsistencyTest`, `AdminJspHygieneTest`, `EditorJspEscapingTest`, `ReaderRefusalSweepTest`, the message ratchets, and the static gates.

- [ ] **Step 3: Commit.** Subject: `feat(comments): moderation page and pending counts on the main menu`.

---

### Task 8: Settings: Weblog Settings, the entry editor, and the API fields

**Files:**
- Modify the weblog settings:
  - `ui/controllers/editor/WeblogConfigBean.java`: `commentsEnabled` and `commentNotify`, as primitive booleans, wired through `copyFrom` and `copyTo`;
  - `WEB-INF/jsps/editor/WeblogConfig.jsp`: two checkboxes, in the style of `bean.active`.
- Modify the entry editor:
  - `ui/controllers/editor/EntryBean.java`: `commentsClosed`, in `copyFrom` and `copyTo` like `noindex`;
  - `WEB-INF/jsps/editor/EntryEdit.jsp`: a "Comments closed" checkbox beside `bean.noindex`, plus a line "{published} published · {pending} awaiting approval" linking to the moderation page, shown when the weblog has comments enabled and the entry exists.
- Modify the API:
  - `ui/restapi/dto/AdminDtos.java`: `WeblogView` and `toView` gain `commentsEnabled` and `commentNotify`; `WeblogPatch` and `applyPatch` gain the same two. Booleans are not clearable. Update the `@Schema` text on `clear` only if it lists all fields.
  - `ui/restapi/dto/EntryDtos.java`: `EntryView`/`toView` and `EntryWrite`/`applyWrite` gain `commentsClosed`.
- Modify: `ApplicationResources.properties`: `weblogSettings.commentsEnabled=Allow comments`, `weblogSettings.commentNotify=Email me when a comment needs approval`, `weblogEdit.commentsClosed=Comments closed`, `weblogEdit.commentCounts={0} published · {1} awaiting approval`.
- Test: extend `WeblogConfigBeanTest`, `WeblogConfigControllerTest`, `EntryBeanTest` (or the entry editor tests that exist), `WeblogsApiTest`, `EntriesWriteApiTest`, and `EntryEditJspTest`.

**Interfaces:** Consumes Task 2's getters and setters, and Task 2's `countForEntry`. Produces the API JSON fields `commentsEnabled`, `commentNotify` and `commentsClosed`. MCP picks them up automatically from the records.

- [ ] **Step 1: Write the failing tests** (criterion 25, the settings half; criterion 27, the settings half):
  - a round-trip for each setting through bean, controller and JSP;
  - the API `PATCH /weblogs/{h}` with `{"commentsEnabled":true}` persists it, and the view shows it;
  - `PATCH` of an entry with `{"commentsClosed":true}` likewise;
  - null leaves each unchanged.
- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Then run `McpDocsTest`, `McpCoverageTest`, `OpenApiDocumentTest`, the message ratchets, `EditorJspEscapingTest`, and the static gates.
- [ ] **Step 3: Commit.** Subject: `feat(comments): comment settings in Weblog Settings, the entry editor and the API`.

---

### Task 9: The API routes and the MCP `comments` tool

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/CommentsApi.java`. Model it on `RedirectsApi`: `@RequestMapping("/v1/weblogs/{handle}/comments")`, weblog `POST` permission, and empty global actions.
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/CommentDtos.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpToolCatalog.java`. Add tool `comments`, read-write, with actions `list`, `get`, `update`, `delete` and `reply`.
- Modify: `McpInstructions.java`. Add one bullet about moderation (held comments, approve or hide, replies as the token's user), within the 3000-character cap.
- Modify: `docs/api/README.md`. Add a contents entry and a `## Comments` section with routes, role, the view fields and examples. Add the MCP table row ``| `comments` | `list`, `get`, `update`, `delete`, `reply` |``, and note the new weblog and entry fields.
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/CommentsApiTest.java`
- Test: `McpCoverageTest`, `McpDocsTest`, `McpInstructionsTest` and `OpenApiDocumentTest`. These must pass; extend them only if they require it.
- Test: `ApiSecurityRefusalTest`. Extend it if it enumerates routes.

**Interfaces:** Consumes Task 3's `CommentService` and Task 2's manager. Produces:

```java
public record CommentView(String id, String entryId, String entryTitle, String status, String body,
                          Instant created, Instant statusChanged, String authorUserName,
                          String authorDisplayName, boolean authorIsOwner, String replyTo, String reply) {}
public record CommentPatch(String status) {}     // "PUBLISHED" | "HIDDEN"; anything else -> 400
public record CommentReply(String body) {}
```

Routes, from the spec:
- `GET ""` takes `status`, `entry`, `limit` (default 50, maximum 200) and `offset`, newest first.
- `GET "/{id}"`.
- `PATCH "/{id}"`.
- `DELETE "/{id}"`.
- `POST "/{id}/replies"` answers 201 with the reply's view.

A comment of another weblog answers 404, through an ownership check in the style of `WeblogOwnership`. `entry` must belong to the weblog, or the answer is 404. A reply that is refused answers 400 with the refusal code. `entryTitle` is the stored title; titles are stored escaped, so pass them through unchanged and document that.

- [ ] **Step 1: Write the failing `CommentsApiTest`** (criteria 26 and 27, and Review Focus 5):
  - `listFiltersAndPages`.
  - `getOne`.
  - `patchSetsStatus`, and `patchRejectsOtherValues`.
  - `deleteRemoves`.
  - `replyCreatesAnAuthorReply`.
  - `anotherWeblogsCommentIs404`.
  - `aTokenWithoutPostOrLimitedToAnotherWeblogGets404`.
  - `noResponseContainsAnEmail`. Serialise every view in the test with a reader whose email is `secret@example.com`, and assert the string never appears.
  - `moderationWorksWithCommentsOff`.
- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Then run the MCP and OpenAPI drift tests, `ApiSecurityRefusalTest`, and the static gates.
- [ ] **Step 3: Commit.** Subject: `feat(comments): /api/v1 comment moderation and the MCP comments tool`.

---

### Task 10: Notifications to weblog admins when a comment is held

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/business/comments/MailCommentNotifier.java`. It is a `@Component`, marked `@Primary` or replacing Task 3's `NoOpCommentNotifier`. Remove the no-op class if nothing else needs it.
- Modify: `ApplicationResources.properties`: `comments.notify.subject=New comment awaiting approval on {0}` and `comments.notify.body={0} commented on "{1}":\n\n{2}\n\nReview it: {3}`, with arguments in that order.
- Test: `.../business/comments/MailCommentNotifierTest.java`

**Interfaces:** It implements Task 3's `CommentNotifier.notifyHeld(ReaderComment comment)`.

**Behaviour:**
- **When:**
  - nothing happens unless `comment.getEntry().getWebsite().getCommentNotify()` is TRUE and `MailUtil.isMailConfigured()`;
  - anything request-bound (the subject, body and link) is resolved on the calling thread;
  - the sending then happens through `weblogger.getThreadManager().executeInBackground(...)`, re-interrupting on `InterruptedException`.
- **Who:** recipients are `UserManager.getWeblogPermissions(weblog)`, filtered on `hasAction(WeblogPermission.ADMIN)`, then mapped through `getUserByUserName(name)`. Disabled users and blank emails are skipped.
- **What:**
  - **Subject:** the weblog name.
  - **Body:**
    - the display name, as plain text and not escaped, since the email is plain text;
    - the entry title;
    - the first 500 characters of the body;
    - the moderation URL: `<site.absoluteurl>/roller-ui/authoring/comments.rol?weblog=<handle>`, built from `WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl")`. With that unset, use the relative path.
  - **Sending:** `MailUtil.sendTextMessage(from, to[], cc, bcc, subject, body)`, with `from` = `site.adminemail`.
- **Failure handling:**
  - the runnable releases with `weblogger.release()` in `finally`;
  - a mail failure is logged with the comment id only, never addresses or body;
  - the comment is never touched.

- [ ] **Step 1: Write the failing tests** (criterion 28, and Review Focus 3's email half):
  - `aHeldCommentMailsEachAdminMemberOnce`. Use an in-memory `MailProvider` or a mocked `MailUtil` seam; see `ContactControllerTest` and `PasswordResetControllerTest` for how mail is captured.
  - `notifyOffMailsNobody`, and `noMailConfiguredMailsNobody`.
  - `disabledAndBlankEmailAdminsAreSkipped`, and `postOnlyMembersAreNotMailed`.
  - `aMailFailureLeavesTheCommentStored`.
  - `theDisplayNameIsPlainTextInTheEmail`. A name with `<b>` appears verbatim in a text/plain body, and no HTML part exists.
  - `theRunnableReleasesThePersistenceSession`.
  - `noLogLineCarriesAnAddressOrTheBody`, using a log capture (see `SsoLogCapture`).
- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Then run `CommentServiceTest`, because the real notifier is now wired, and the static gates.
- [ ] **Step 3: Commit.** Subject: `feat(comments): email weblog admins when a comment is held`.

---

### Task 11: The reader's account page

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/AccountController.java`. It is `@RequestMapping("/roller-ui")`, with `GET /account.rol`, `POST /account!saveName.rol`, `POST /account!deleteComment.rol` and `POST /account!deleteAccount.rol`. Its `requiredGlobalPermissionActions()` is `List.of(GlobalPermission.COMMENT)`, it is user-required, and it requires no weblog.
- Create: `app/src/main/webapp/WEB-INF/jsps/core/Account.jsp`, with tile `.Account` in `RollerViewResolver` (a simple page layout, as `Profile` uses)
- Modify: `it-selenium/.../support/Routes.java`. The route is `/roller-ui/account.rol`. Check `Routes`' `Role` enum: if it has no READER role, add `Role.READER` only if `RouteSweepIT` can sign a reader in. Otherwise mark it `Role.EDITOR`, since editors hold `comment`. The marker is `#accountPage`.
- Modify: `ReaderRefusalSweepTest`. Exclude `AccountController` explicitly, with the reason "requires the global comment action by design, which readers hold". Add a positive test that a reader passes and an anonymous visitor is sent to login.
- Modify: `ApplicationResources.properties`, adding the `account.*` keys
- Test: `.../ui/controllers/core/AccountControllerTest.java`

**Interfaces:** Consumes Task 2's manager (`getCommentsByAuthor`, `getWeblogsWithPublishedCommentsBy`) and Task 3's service (`deleteOwn`, `expire`).

**Behaviour, from the spec:**
- **Saving the display name.**
  - Strip control and format characters, collapse whitespace, and trim. Then require 1-50 code points.
  - It is refused, with `account.name.taken`, when it case-insensitively equals the screen name of any account holding any `WeblogPermission`.
  - To find those accounts, add `UserManager.getScreenNamesOfWeblogMembers()` with a named query, or derive them from `getWeblogPermissions` per weblog. Prefer one query, and add a test to `UserQueryAndRoleTest`.
  - On success, set `screenName` (and `fullName` if it equals the old screen name), save, flush, and call `CommentService.expire` on each weblog from `getWeblogsWithPublishedCommentsBy`.
- **Deleting a comment** calls `deleteOwn`.
- **Deleting the account:**
  - It is refused, with `account.delete.member`, for an account holding any weblog permission. It is refused, with `account.delete.admin`, for one holding `admin`.
  - Otherwise:
    1. Collect `getWeblogsWithPublishedCommentsBy`.
    2. Remove the user through `UserManager.removeUser`. That deletes its roles and tokens, and the comments cascade through the database. Check `removeUser`'s cleanup list, and add comment removal in JPA first if the cascade alone leaves the persistence context stale. Prefer an explicit `CommentManager` delete-by-author, then `removeUser`.
    3. Flush.
    4. Expire each weblog.
    5. Invalidate the session and clear the `SecurityContext`, as `logout-redirect.jsp` does.
    6. Redirect to `<ctx>/` with a flash message.
- The page lists the account's comments, newest first, with status, entry link and a Delete button (`data-confirm`). The "Delete my account" button sits under `data-confirm`.

- [ ] **Step 1: Write the failing tests** (criteria 29 and 30, and Review Focus 4):
  - `anonymousIsSentToLogIn`, and `aReaderSeesThePage`.
  - `aNameMatchingAnOwnersScreenNameIsRefused`, `aBlankOr51CodePointNameIsRefused` and `aValidNameIsSavedAndExpiresTheWeblogs`.
  - `deletingOwnCommentWorks`.
  - `deletingAnAccountRemovesItsCommentsAndRepliesAndExpiresEachWeblog`. Use two weblogs, each with a published reader comment carrying an author reply.
  - `deletingIsRefusedForAMemberAndForAnAdmin`.
  - `theSessionEndsAfterDeletion`.
  - `displayNameIsEscapedOnTheAccountPage`, extending `EditorJspEscapingTest`'s file list.
- [ ] **Step 2: Run, implement, run.** Expected: FAIL, then PASS. Then run `ReaderRefusalSweepTest`, `RollerViewResolverTest`, `RouteCoverageTest`, `JspConsistencyTest`, the message ratchets, and the static gates.
- [ ] **Step 3: Commit.** Subject: `feat(comments): the reader's account page: name, comments, delete account`.

---

### Task 12: Browser integration tests at both context paths

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/CommentsIT.java`
- Modify, only if needed: `it-selenium/src/test/resources/keycloak/roller-it-realm.json`. Add readers `commenter-it` and `commenter2-it` (verified emails, names), so this class shares no account with `SsoSignInIT`.
- Modify, only if needed: `support/RollerIT.java`. Lift any helper that `SsoSignInIT` and `CommentsIT` both need, such as `signInWithKeycloak`, so CPD stays quiet.

**Interfaces:** Consumes everything above.

**Fixture:**
- The class takes an exclusive `@ResourceLock(RollerIT.GLOBAL_CONFIG)`, because it sets `site.absoluteurl` as `SsoSignInIT` does.
- Once per class: create a weblog (UI helper, with a nonce handle) and turn on "Allow comments" in Weblog Settings. Publish two entries; mark the second "Comments closed".
- A second weblog with a custom domain gets its host mapping from `VirtualHostIT`'s pattern. Reuse that approach, or skip this one test with a clear assumption if the shared driver cannot be reconfigured in this class. In that case, cover criterion 22 through an HTTP `GET` with a `Host` header, which `VirtualHostIT` also uses.

**Tests**, each mapping to (I) criteria:
- `anAnonymousVisitorSeesTheSignInLink` (21).
- `aNewReadersCommentIsHeldThenApprovedAndShows` (4, 21, 24):
  1. The reader signs in from the link and posts.
  2. They see "will appear once it is approved" and the awaiting item.
  3. The anonymous page does not show the comment.
  4. The admin approves it on the Comments page.
  5. The anonymous page now shows it, so the cache has expired.
- `aTrustedReadersNextCommentShowsAtOnce` (5, 21).
- `anAuthorReplyShowsUnderTheComment` (8, 24).
- `aReaderDeletesTheirComment` (23).
- `aClosedEntryShowsCommentsAndNoForm` (19).
- `aCustomDomainWeblogShowsNoForm` (22).
- `theReaderChangesTheirNameAndDeletesTheirAccount` (29, 30).
- `moderationThroughTheApi` (26). Use an admin token through `apiPost`/`HttpClient`, as `RedirectIT` does.
- `commentsOffHidesTheSection` (14).

- [ ] **Step 1: Write the IT and run it at `/`.**
  `while pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; do sleep 10; done; mvn -q verify -Pit -pl it-selenium -am -Dit.test=CommentsIT`
  Use the module's real single-class property; `docs/dev/browser-its.md` names it. Expected: PASS.
- [ ] **Step 2: Run it at `-Dit.context.path=roller`.** Expected: PASS.
- [ ] **Step 3: Run the full browser suite at `/`.** Expected: all green, apart from known skips. Report the totals.
- [ ] **Step 4: Commit.** Subject: `test(it): reader comments end to end at both context paths`.

---

### Task 13: Documentation

**Files:**
- Create: `docs/dev/comments.md`. Cover:
  - the data model;
  - `CommentService` as the one rule holder;
  - the trust rule;
  - rendering and cache expiry;
  - the page script and the session endpoint, including why CSRF is fetched rather than cached;
  - custom domains not being in v1, and why;
  - the API and MCP;
  - notifications;
  - the account page;
  - the `GlobalPermission.implies` fail-closed fix and why it came first;
  - hazards found while building.
- Modify:
  - `CLAUDE.md`: one row in "Where the rest lives", `Comments (reader)` → `docs/dev/comments.md`. Keep the file under about 20 KB.
  - `docs/dev/entries.md`: replace the "Comments" paragraph with a pointer to `comments.md`, and keep the "sweep for 'comment' must leave alone" list.
  - `RollerSSO.md`: replace "What a reader can do today" with commenting, and the account page.
  - `docker_deployment.md`: a short "Comments" note: turn them on per weblog in Weblog Settings; notifications need mail configured; held comments appear on the Comments page and through MCP.
- Test: run `ProductionComposeTest` and any test that pins doc content.

- [ ] **Step 1: Write the docs.** Check every class, method and path you cite against the code with grep.
- [ ] **Step 2: Run the pinning tests.** Commit with the subject `docs(comments): developer notes, operator notes, SSO doc update`.
