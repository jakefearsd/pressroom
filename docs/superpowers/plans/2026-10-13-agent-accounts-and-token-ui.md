# Agent accounts and API tokens in the admin UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a site admin run blog.wikantik.com end to end from the admin UI: people and agent accounts, weblog memberships and API tokens, with agents doing day-to-day work over MCP.

**Architecture:** An agent is a `roller_user` row with `kind = AGENT` (V033), so memberships, permissions and tokens reuse the existing machinery. Every sign-in path refuses an agent at its one gate (`RollerUserDetailsService` for form, remember-me and Basic; `ReaderAccounts` for SSO; `JPAUserTokenManagerImpl` for set-password links), and `ApiTokenAuthFilter` refuses a disabled owner explicitly. The token form, one-time reveal and revocation live in one abstract controller (`TokenFormController`) shared by the Profile tokens page, the agent page and Admin → API tokens; the pin rule lives in `TokenPins`, shared by the UI and `POST /api/v1/tokens`. Every account, membership and token change writes one INFO line through `AdminLog` on logger `roller.admin`.

**Tech Stack:** Java 25, Spring Boot 4.1 MVC + Spring Security 7.1, JSP/JSTL with the homegrown tiles resolver, EclipseLink/PostgreSQL 18, JUnit 5 + Mockito + MockMvc, Testcontainers, Selenide browser ITs.

**Spec:** `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`

Release and deploy (0.2.0, blog.wikantik.com) are not tasks in this plan: the controller does them after the final review.

All paths are relative to the repository root, `/home/jakefear/source/roller`; run every command from there.

## Global Constraints

- Work directly on `master`. Commit your own task's work locally; **never push**. End each commit message with a blank line and then exactly:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_017ft4V8YAq5me5Kq3CcDYVa`
- TDD: write the failing test, run it and see it fail for the reason stated, then write the code.
- Never run two Maven builds at once in this checkout. Before any `mvn`, run `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR` and wait for CLEAR.
- Focused runs: `mvn -o -q -pl app test -Dtest=<Class>` (several classes: `-Dtest='A,B'` plus `-Dsurefire.failIfNoSpecifiedTests=false`). Each task ends with `mvn -o -pl app verify` green before its commit: javac `-Xlint:all` with warnings as errors, PMD 7.27.0 (quickstart + security + 21 named rules), CPD at 110 tokens, SpotBugs + find-sec-bugs at zero, JaCoCo LINE 0.9590 / BRANCH 0.9100 and the security-boundary package rule (sso, comments, API auth, v1, MCP) LINE 0.93 / BRANCH 0.89.
- it-selenium code compiles only with the `it` profile: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests`.
- Name every `@RequestParam`/`@PathVariable` (`name = "..."`); the build has no `-parameters`.
- An SLF4J `Throwable` stays the last argument with no `{}` for it. A class has at most one `Logger` (PMD `MoreThanOneLogger`); `roller.admin` lines go through `AdminLog`, never a second logger field.
- Nothing in `ui.controllers` may import from `ui.restapi` (the one existing exception, `RollerHandlerInterceptor`'s `ApiException`, stays the only one). `ui.restapi` may import `ui.controllers` (`TokenPins`, `AdminLog`).
- A message argument a controller passes is HTML-escaped with `StringEscapeUtils.escapeHtml4` (`MessageArgumentEscapingTest`). A message with a `{n}` placeholder carries no apostrophe; new values carry none at all, and no double quote.
- "An agent never signs in. It has no password and works only through tokens that a site admin mints for it." (spec, Decisions)
- "Identity and credentials stay with people. No API route can create, change or mint tokens for an agent account, nor touch an admin account (as today)." (spec, Decisions)
- The secret "never appears in a URL or a log; logs name the token's id and label only." The reveal page sends `Cache-Control: no-store`. (spec, section 3)
- Agent email default: `<username>@agents.invalid`; nothing is ever mailed to an agent. (spec, section 1)
- Token label: required, at most `ColumnLimits.TOKEN_LABEL` (255) characters. Expiry: 30, 90 or 365 days, or never; default 90 days. Role: Read / Write / Admin stored as `READ`/`POST`/`ADMIN`. (spec, section 3)
- "Set a password now": at least 8 characters. (spec, section 4)
- Logger `roller.admin`, one INFO line per event, naming the target and `by=` the acting account; no secret is ever logged. (spec, section 6)
- Every new message key is in all 8 bundles (English text in the seven translations until someone translates it). Every new GET route has a `Routes` entry with a marker. Every destructive control uses `data-confirm`. Every POST form carries `<sec:csrfInput/>`. (spec, criterion 25)
- No form field on these pages is named `weblog`: `RollerHandlerInterceptor` reads that parameter as the action weblog. The token form calls it `pin`; the Weblogs section calls it `handle`.
- A `catch` in a decision path fails closed (a check that could not run is not a check that passed); a log-only `catch` is allowed in display paths only. (CLAUDE.md)
- Match the surrounding code: javadoc on every new public type and non-obvious method, comments that say why, no comment that restates the code. New Java and JSP files start with the ASF licence header copied verbatim from the top of `app/src/main/java/org/apache/roller/weblogger/pojos/ApiToken.java` (Java) or `app/src/main/webapp/WEB-INF/jsps/admin/UserAdmin.jsp` (JSP, the `<%-- ... --%>` block).

## Review Focus

1. **The secret leaking through a log, a URL or the browser cache.** A mint's own log line must name only id and label; the reveal page must be `no-store`; the redirect to it must carry nothing in its query. Pinned in Task 13 (`noLogLineWrittenByAMintContainsTheSecret` captures every logger at every level around a UI mint; `theRevealShowsTheSecretOnceWithNoStoreAndAnMcpSnippet`; `mintingStoresTheChoicesRecordsTheIssuerAndRedirectsToTheReveal` asserts the redirect has no attributes) and Task 8 (`anApiMintIsLoggedWithoutItsSecret`).
2. **A disabled owner's token still working through some path** (REST, an MCP forward, an account cached by the running app). Pinned in Task 7 (`aDisabledPersonsTokenAuthenticatesNoOne`, `aDisabledAgentsTokenAuthenticatesNoOneUntilReEnabled`) and on the real WAR in Task 18 (`disablingAnAgentStopsItsTokenAndEnablingRestoresIt`, REST and MCP both).
3. **An agent signing in through remember-me, HTTP Basic or SSO.** Form login, remember-me and Basic all resolve the account through `RollerUserDetailsService`; SSO through `ReaderAccounts`. Pinned in Task 4 (`signingInAsAnAgentFailsWithTheSameMessageAsAWrongPassword` drives the real `DaoAuthenticationProvider` that form login and Basic share; `aRememberMeCookieNamingAnAgentSignsNoOneIn`; `aBoundAccountThatIsAnAgentIsRefused`) and Task 18 (`httpBasicAsAnAgentIs401`).
4. **The last-Admin rule on the user page.** Demoting or removing a weblog's only Admin from the account page must be refused exactly as the Members page refuses it, while a second Admin makes it allowed. Pinned in Task 12 (`demotingTheLastAdminIsRefused`, `removingTheLastAdminIsRefused`, `removingAnAdminWhileAnotherRemainsWorks`).
5. **Pin validation for site admins versus members.** A member may pin only weblogs they belong to; a site admin any existing weblog; nobody a weblog that does not exist; a lookup failure refuses. Pinned in Task 8 (`TokenPinsTest`, `mintingWithAForeignWeblogIsBadRequestOnWeblog`, `mintingWithAnUnknownWeblogIsBadRequestOnWeblog`, `aSiteAdminMayPinAnyExistingWeblog`) and Tasks 13/14 (`aPinOutsideMyWeblogsIsAFieldError`, `aPinToAWeblogTheAgentIsNotInIsRefused`, `aSiteAdminAgentMayPinAnyExistingWeblog`, `aPinCheckThatCannotRunRefuses`).

## File Structure

Created (production):
- `bin/db/migrations/V033__agent_accounts.sql` — `kind`, `notes`, `last_login` on `roller_user`; `passphrase` nullable; `issued_by` on `roller_api_token`.
- `app/src/main/java/org/apache/roller/weblogger/ui/core/security/LastLoginRecorder.java` — stamps `last_login` on every interactive sign-in.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/AdminLog.java` — the `roller.admin` log line.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenPins.java` — which weblogs a token may be pinned to (UI and API).
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenFormValues.java` — the token form's submitted values.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenFormController.java` — abstract: mint, reveal, revoke, list.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ProfileTokensController.java` — Profile → API tokens and the reveal page.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AccountRow.java`, `AccountRows.java` — the Users list's rows.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserNames.java` — the username rule for new people and agents.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsController.java`, `UserWeblogsModel.java` — the Weblogs section.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AgentBean.java`, `AgentController.java` — New agent and the agent page.
- `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AdminTokensController.java`, `TokenRow.java` — Admin → API tokens.
- JSPs: `WEB-INF/jsps/admin/UserWeblogs.jsp`, `admin/AgentCreate.jsp`, `admin/Agent.jsp`, `admin/ApiTokens.jsp`, `core/TokenForm.jsp`, `core/TokenList.jsp`, `core/ProfileTokens.jsp`, `core/TokenReveal.jsp`.
- Design cards: `docs/design/tables/tables-users.html`, `docs/design/forms/forms-agent-page.html`, `docs/design/forms/forms-token-reveal.html`.

Created (tests): `AgentAccountsMigrationTest`, `AgentAccountStoreTest`, `LastLoginRecorderTest`, `TokenPinsTest`, `AdminLogTest`, `org/apache/roller/testing/AdminLogCapture`, `AgentAccountMessagesTest`, `AgentAccountsJspTest`, `UserWeblogsControllerTest`, `UserWeblogsModelTest`, `ProfileTokensControllerTest`, `AgentControllerTest`, `AdminTokensControllerTest`, `it-selenium/.../AgentAccountsIT`.

Modified: `User` + `User.orm.xml`, `ApiToken` + `ApiToken.orm.xml`, `ApiTokenManager` + `JPAApiTokenManagerImpl`, `UserManager` + `JPAUserManagerImpl`, `JPAUserTokenManagerImpl`, `RollerUserDetailsService`, `ReaderAccounts`, `PasswordResetController`, `SecurityConfig`, `ApiTokenAuthFilter`, `TokensApi`, `TokenDtos`, `AdminAccountGuard`, `AdminApi`, `AdminDtos`, `MetaApi`, `McpInstructions`, `UserAdminController`, `UserEditController`, `CreateUserBean`, `RollerViewResolver`, `admin-menu.xml`, `log4j2.xml`, `roller.css`, `UserAdmin.jsp`, `UserEdit.jsp`, `Profile.jsp`, the 8 `ApplicationResources*.properties`, `MockWeblogger`, `docs/design/design-system.md`, `docs/api/README.md`, `docs/dev/admin-ui.md`, `docs/dev/api.md`, it-selenium `Routes.java`, `seed-it-data.sql`, `UserAdminIT`, `SsoSignInIT`, `MultiUserJourneyIT`, `ErrorCasesIT`. Deleted: `app/src/main/webapp/roller-ui/scripts/ajax-user.js`.

## Messages (i18n)

`MessageKeyTest` checks only the base bundle and fails on orphans; `ApplicationResourcesTest` has no `@Test` and checks nothing. Criterion 25 asks for every new key in all 8 bundles, so Task 10 adds `AgentAccountMessagesTest`, which fails when a base-bundle key under `userList.`, `personForm.`, `userWeblogs.`, `apiTokens.` or `agent.` (or `tabbedmenu.admin.apiTokens`) is missing from any of the seven translations.

Every task that adds keys appends the same block, English text, to all 8 files with this loop (the files all end in a newline today):

```bash
for f in app/src/main/resources/ApplicationResources*.properties; do
cat >> "$f" <<'KEYS'
<the task's block, verbatim>
KEYS
done
```

Keys a task stops using are deleted from all 8 files with this script (it removes a key and its `\` continuation lines; latin-1 round-trips every byte):

```bash
python3 -I - KEY1 KEY2 <<'PY'
import glob, re, sys
keys = set(sys.argv[1:])
for path in glob.glob('app/src/main/resources/ApplicationResources*.properties'):
    with open(path, encoding='latin-1') as f:
        lines = f.read().split('\n')
    out, skipping = [], False
    for line in lines:
        if skipping:
            skipping = line.endswith('\\')
            continue
        m = re.match(r'([A-Za-z0-9_.]+)\s*=', line)
        if m and m.group(1) in keys:
            skipping = line.endswith('\\')
            continue
        out.append(line)
    with open(path, 'w', encoding='latin-1') as f:
        f.write('\n'.join(out))
PY
```

The exact blocks are in Tasks 10, 11, 12, 13, 14 and 15.

---

### Task 1: V033 and the model

**Model:** haiku

**Files:**
- Create: `bin/db/migrations/V033__agent_accounts.sql`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/User.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/ApiToken.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml`
- Test: `app/src/test/java/org/apache/roller/weblogger/business/startup/AgentAccountsMigrationTest.java` (create)
- Test: `app/src/test/java/org/apache/roller/weblogger/pojos/UserAccountTest.java` (add tests)

**Interfaces:**
- Produces:
  - `User.Kind { PERSON, AGENT }`; `User.getKind()/setKind(User.Kind)` (default `PERSON`); `boolean User.isAgent()`.
  - `User.getNotes()/setNotes(String)`; `Date User.getLastLogin()/setLastLogin(Date)` (defensive copies).
  - `User.AGENT_EMAIL_DOMAIN = "agents.invalid"`, `User.NOTES_MAX_LENGTH = 1000`.
  - `User.resetPassword` throws `IllegalStateException("An agent account has no password.")` for an agent.
  - `ApiToken.getIssuedBy()/setIssuedBy(String)` — the issuing user's id, or null before V033.

- [ ] **Step 1: Write the failing tests**

Create `AgentAccountsMigrationTest.java`:

```java
package org.apache.roller.weblogger.business.startup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.roller.testing.MigrationFiles;
import org.apache.roller.testing.RollerPostgresContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V033 (spec 2026-10-13, section 1; criterion 1): an account kind, agent notes
 * and the last sign-in on roller_user, a nullable password, and the issuer on
 * roller_api_token. Rows from before V033 read back as people with no issuer.
 */
public class AgentAccountsMigrationTest {

    @Test
    void rowsFromBeforeV033ReadBackAsPeopleWithNoIssuerAndV033IsRepeatable() throws Exception {
        String db = "migrationtest_agent_accounts_existing";
        try (Connection con = databaseBefore(db, "V033")) {
            exec(con, "INSERT INTO roller_user (id, username, passphrase, screenname, fullname, "
                    + "emailaddress, datecreated, isenabled) "
                    + "VALUES ('u1', 'olduser', 'x', 'Old', 'Old', 'old@example.com', now(), true)");
            exec(con, "INSERT INTO roller_api_token (id, userid, label, token_sha256, scope_role, created) "
                    + "VALUES ('t1', 'u1', 'old', 'digest-1', 'READ', now())");

            String v033 = Files.readString(migration("V033"), StandardCharsets.UTF_8);
            exec(con, v033);
            exec(con, v033);

            assertEquals("PERSON", string(con, "SELECT kind FROM roller_user WHERE id = 'u1'"));
            assertNull(string(con, "SELECT notes FROM roller_user WHERE id = 'u1'"));
            assertNull(string(con, "SELECT last_login FROM roller_user WHERE id = 'u1'"));
            assertNull(string(con, "SELECT issued_by FROM roller_api_token WHERE id = 't1'"));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void kindIsPersonOrAgentAndAnAgentNeedsNoPassword() throws Exception {
        String db = "migrationtest_agent_accounts_kind";
        try (Connection con = freshDatabase(db)) {
            exec(con, "INSERT INTO roller_user (id, username, passphrase, screenname, fullname, "
                    + "emailaddress, datecreated, isenabled, kind) "
                    + "VALUES ('a1', 'bot', NULL, 'Bot', 'Bot', 'bot@agents.invalid', now(), true, 'AGENT')");
            assertEquals("AGENT", string(con, "SELECT kind FROM roller_user WHERE id = 'a1'"));

            SQLException refused = assertThrows(SQLException.class, () -> exec(con,
                    "INSERT INTO roller_user (id, username, passphrase, screenname, fullname, "
                    + "emailaddress, datecreated, isenabled, kind) "
                    + "VALUES ('r1', 'robot', 'x', 'R', 'R', 'r@example.com', now(), true, 'ROBOT')"));
            assertEquals("23514", refused.getSQLState(), "the check constraint refuses any other kind");
        } finally {
            dropDatabase(db);
        }
    }

    private static Path migration(String version) {
        return MigrationFiles.all().stream()
                .filter(p -> p.getFileName().toString().startsWith(version + "__"))
                .findFirst().orElseThrow(() -> new AssertionError(version + " is missing"));
    }

    private String string(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private Connection freshDatabase(String dbName) throws Exception {
        return databaseBefore(dbName, "V999");
    }

    /** A new database with every migration whose version sorts before {@code version} applied. */
    private Connection databaseBefore(String dbName, String version) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
        }
        Connection con = DriverManager.getConnection(
                jdbcUrlFor(dbName),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().compareTo(version) >= 0) {
                continue;
            }
            try (Statement st = con.createStatement()) {
                st.execute(Files.readString(migration, StandardCharsets.UTF_8)
                        .replace(":app_user", RollerPostgresContainer.getUsername()));
            }
        }
        return con;
    }

    private void dropDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
    }

    private String jdbcUrlFor(String dbName) {
        String url = RollerPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
```

Add to `UserAccountTest` (imports: add `import static org.junit.jupiter.api.Assertions.assertThrows;` and `import static org.junit.jupiter.api.Assertions.assertTrue;` if missing):

```java
    /** Spec 2026-10-13, section 1: every account is a person unless it is made an agent. */
    @Test
    void aNewAccountIsAPersonAndAnAgentSaysSo() {
        User user = new User();
        assertEquals(User.Kind.PERSON, user.getKind());
        assertFalse(user.isAgent());

        user.setKind(User.Kind.AGENT);
        assertTrue(user.isAgent());
    }

    /** An agent has no password, so there is nothing to reset -- and no encoder is consulted. */
    @Test
    void anAgentHasNoPasswordToReset() {
        User agent = new User();
        agent.setKind(User.Kind.AGENT);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> agent.resetPassword("anything-at-all"));

        assertEquals("An agent account has no password.", refused.getMessage());
        assertNull(agent.getPassword());
    }

    @Test
    void theLastLoginCannotBeMutatedThroughItsAccessor() {
        User user = new User();
        assertNull(user.getLastLogin());
        Date when = new Date(1_000L);
        user.setLastLogin(when);
        when.setTime(2_000L);
        assertEquals(1_000L, user.getLastLogin().getTime());
        user.getLastLogin().setTime(3_000L);
        assertEquals(1_000L, user.getLastLogin().getTime());
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='AgentAccountsMigrationTest,UserAccountTest'`
Expected: compilation failure (`User.Kind`, `isAgent`, `getLastLogin` do not exist).

- [ ] **Step 3: Write the migration**

`bin/db/migrations/V033__agent_accounts.sql`: copy the 14-line licence header (`-- Licensed to the Apache ...` through the line `--` after "limitations under the License.") verbatim from `bin/db/migrations/V032__media_content_hash.sql`, then:

```sql
--
-- Migration: agent accounts (docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md).
--
-- An agent is a roller_user row with kind = 'AGENT'. It never signs in: it has
-- no password and works only through API tokens a site admin mints for it, so
-- the password column becomes nullable. notes holds an agent's purpose for the
-- admin UI; last_login is the last interactive sign-in of a person.
-- roller_api_token.issued_by is the id of the user who minted the token, NULL
-- for tokens minted before this migration. No foreign key: a token's history
-- must survive its issuer.
--
-- Idempotent: safe to run on a database that already has it.

ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS kind varchar(16) NOT NULL DEFAULT 'PERSON';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ru_kind_ck') THEN
        ALTER TABLE roller_user ADD CONSTRAINT ru_kind_ck CHECK (kind IN ('PERSON', 'AGENT'));
    END IF;
END $$;

ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS notes varchar(1000);
ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS last_login timestamp(3) with time zone;
ALTER TABLE roller_user ALTER COLUMN passphrase DROP NOT NULL;

ALTER TABLE roller_api_token ADD COLUMN IF NOT EXISTS issued_by varchar(48);
```

Run `grep -rn "V032" app/src/test docs/dev bin/db/migrations/README.md` and update any list or count of migrations that stops at V032 (`SchemaMigrationTest` and `MigrationFiles` discover files themselves; expect no hit outside the V032 test and `docs/dev/media.md`'s own V032 note, which stay).

- [ ] **Step 4: Map the columns**

In `User.java`, after `private Boolean enabled = Boolean.TRUE;` add:

```java
    private Kind kind = Kind.PERSON;
    private String notes;
    private Date lastLogin;
```

After the `SSO_PASSWORD_MARKER` constant add:

```java
    /**
     * What an account is (V033). A person signs in; an agent never does -- it
     * has no password and works only through API tokens a site admin mints
     * for it (spec 2026-10-13, Decisions).
     */
    public enum Kind { PERSON, AGENT }

    /** The domain of the address an agent created with no email is given; never deliverable (RFC 2606). */
    public static final String AGENT_EMAIL_DOMAIN = "agents.invalid";

    /** roller_user.notes is varchar(1000). */
    public static final int NOTES_MAX_LENGTH = 1000;
```

In `resetPassword`, make the first statement:

```java
        if (isAgent()) {
            throw new IllegalStateException("An agent account has no password.");
        }
```

Before `//------------------------------------------------------- Good citizenship` add:

```java
    /** Whether this account is a person or an agent; never null. */
    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    /** True for an agent account: no password, no sign-in, API tokens only. */
    public boolean isAgent() {
        return kind == Kind.AGENT;
    }

    /** An agent's purpose, shown only in the admin UI; null for a person. */
    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** The last successful interactive sign-in (form, remember-me or SSO), or null. */
    public Date getLastLogin() {
        return lastLogin == null ? null : (Date) lastLogin.clone();
    }

    public void setLastLogin(Date lastLogin) {
        this.lastLogin = lastLogin == null ? null : (Date) lastLogin.clone();
    }
```

In `User.orm.xml`, inside `<attributes>`, after the `ssoSubject` `<basic>`:

```xml
            <basic name="kind">
                <column name="kind" insertable="true" updatable="true" nullable="false"/>
                <enumerated>STRING</enumerated>
            </basic>
            <basic name="notes">
                <column name="notes" insertable="true" updatable="true" nullable="true"/>
            </basic>
            <basic name="lastLogin">
                <column name="last_login" insertable="true" updatable="true" nullable="true"/>
                <temporal>TIMESTAMP</temporal>
            </basic>
```

In `ApiToken.java`, add the field `private String issuedBy;` after `private Timestamp revokedAt;`, and before `isUsable()`:

```java
    /**
     * The id of the user who minted this token -- the owner for a self-mint,
     * a site admin for a token minted on an agent's page -- or null for a
     * token minted before V033.
     */
    public String getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(String issuedBy) {
        this.issuedBy = issuedBy;
    }
```

In `ApiToken.orm.xml`, after the `revokedAt` `<basic>`:

```xml
            <basic name="issuedBy">
                <column name="issued_by" insertable="true" updatable="true" nullable="true"/>
            </basic>
```

- [ ] **Step 5: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='AgentAccountsMigrationTest,UserAccountTest,SchemaMigrationTest,ApiTokenManagerTest,UserQueryAndRoleTest'`
Expected: PASS (the last three show the mapping loads against the migrated schema).

- [ ] **Step 6: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add bin/db/migrations/V033__agent_accounts.sql \
        app/src/main/java/org/apache/roller/weblogger/pojos/User.java \
        app/src/main/java/org/apache/roller/weblogger/pojos/ApiToken.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml \
        app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml \
        app/src/test/java/org/apache/roller/weblogger/business/startup/AgentAccountsMigrationTest.java \
        app/src/test/java/org/apache/roller/weblogger/pojos/UserAccountTest.java
git commit -m "feat(model): V033 agent accounts -- kind, notes, last_login, nullable password, token issuer"
```

---

### Task 2: Token manager: issuer, lookups and an idempotent revoke

**Model:** haiku

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/ApiTokenManager.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAApiTokenManagerImpl.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml` (three named queries)
- Modify: `app/src/test/java/org/apache/roller/weblogger/business/MockWeblogger.java` (an `ApiTokenManager` mock)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/ApiTokenManagerTest.java` (add tests)

**Interfaces:**
- Consumes: Task 1's `ApiToken.getIssuedBy()/setIssuedBy(String)`.
- Produces:
  - `Issued issueToken(User user, String label, String scopeWeblog, ApiToken.Role role, Timestamp expiresAt, User issuedBy)` — records `issuedBy.getId()`.
  - `default Issued issueToken(User user, String label, String scopeWeblog, ApiToken.Role role, Timestamp expiresAt)` — a self-mint; the owner is the issuer.
  - `ApiToken getToken(String tokenId)` — null for a blank or unknown id.
  - `void revoke(ApiToken token)` — sets `revokedAt` once; revoking again changes nothing. Does not flush.
  - `List<ApiToken> getLiveTokens(User user)` — not revoked, not expired, newest first.
  - `List<ApiToken> getAllTokens(boolean includeInactive)` — every token on the site, newest first; only live ones unless `includeInactive`.
  - `MockWeblogger.apiTokenManager()` / `getApiTokenManager()` — a Mockito mock wired to `weblogger().getApiTokenManager()`.

- [ ] **Step 1: Write the failing tests**

Add to `ApiTokenManagerTest` (add `import java.util.List;`):

```java
    /** Spec 2026-10-13, section 3: a mint records who issued it. */
    @Test
    void aTokenRecordsWhoIssuedIt() throws Exception {
        User admin = TestUtils.setupUser("apitokenissuer" + System.nanoTime());
        TestUtils.endSession(true);
        try {
            ApiTokenManager.Issued issued = mgr.issueToken(user, "minted", null, ApiToken.Role.POST, null, admin);
            TestUtils.endSession(true);

            assertEquals(admin.getId(), mgr.getToken(issued.token().getId()).getIssuedBy());
        } finally {
            TestUtils.teardownUser(admin.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aSelfMintRecordsTheOwnerAsTheIssuer() throws Exception {
        ApiTokenManager.Issued issued = mgr.issueToken(user, "self", null, ApiToken.Role.READ, null);
        TestUtils.endSession(true);

        assertEquals(user.getId(), mgr.getToken(issued.token().getId()).getIssuedBy());
    }

    @Test
    void getTokenFindsATokenByIdAndNothingForABlankOrUnknownId() throws Exception {
        String id = mgr.issueToken(user, "findme", null, ApiToken.Role.READ, null).token().getId();
        TestUtils.endSession(true);

        assertEquals("findme", mgr.getToken(id).getLabel());
        assertNull(mgr.getToken("no-such-token"));
        assertNull(mgr.getToken("   "));
        assertNull(mgr.getToken(null));
    }

    /** Criterion 14: revoking twice succeeds and changes nothing. */
    @Test
    void revokingTwiceKeepsTheFirstRevocation() throws Exception {
        String raw = mgr.issueToken(user, "twice", null, ApiToken.Role.READ, null).rawToken();
        String id = mgr.authenticate(raw).getId();
        TestUtils.endSession(true);

        mgr.revoke(mgr.getToken(id));
        TestUtils.endSession(true);
        java.sql.Timestamp first = mgr.getToken(id).getRevokedAt();
        assertNotNull(first);

        mgr.revoke(mgr.getToken(id));
        TestUtils.endSession(true);

        assertEquals(first, mgr.getToken(id).getRevokedAt());
        assertNull(mgr.authenticate(raw), "a revoked token authenticates no one");
    }

    @Test
    void liveTokensLeaveOutRevokedAndExpiredOnes() throws Exception {
        String live = mgr.issueToken(user, "live", null, ApiToken.Role.READ, null).token().getId();
        String expired = mgr.issueToken(user, "expired", null, ApiToken.Role.READ,
                new Timestamp(System.currentTimeMillis() - 60_000L)).token().getId();
        String revoked = mgr.issueToken(user, "revoked", null, ApiToken.Role.READ, null).token().getId();
        TestUtils.endSession(true);
        mgr.revoke(mgr.getToken(revoked));
        TestUtils.endSession(true);

        List<String> ids = mgr.getLiveTokens(user).stream().map(ApiToken::getId).toList();

        assertEquals(List.of(live), ids);
        assertTrue(mgr.getTokens(user).stream().map(ApiToken::getId).toList().containsAll(
                List.of(live, expired, revoked)), "getTokens still lists every token");
    }

    /** Criterion 13: the site list shows live tokens; the toggle adds revoked and expired ones. */
    @Test
    void theSiteListAddsInactiveTokensOnlyWhenAsked() throws Exception {
        String live = mgr.issueToken(user, "site-live", null, ApiToken.Role.READ, null).token().getId();
        String revoked = mgr.issueToken(user, "site-revoked", null, ApiToken.Role.READ, null).token().getId();
        TestUtils.endSession(true);
        mgr.revoke(mgr.getToken(revoked));
        TestUtils.endSession(true);

        List<String> liveOnly = mgr.getAllTokens(false).stream().map(ApiToken::getId).toList();
        List<String> everything = mgr.getAllTokens(true).stream().map(ApiToken::getId).toList();

        assertTrue(liveOnly.contains(live));
        assertFalse(liveOnly.contains(revoked));
        assertTrue(everything.containsAll(List.of(live, revoked)));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest=ApiTokenManagerTest`
Expected: compilation failure (`issueToken` with six arguments, `getToken`, `revoke(ApiToken)`, `getLiveTokens`, `getAllTokens` do not exist).

- [ ] **Step 3: Implement**

In `ApiTokenManager.java`, replace the `issueToken` declaration and its javadoc with:

```java
    /**
     * Mints a token. {@link Issued#rawToken()} is the only time the secret
     * exists outside the caller's hands -- only its digest is stored.
     *
     * @param issuedBy the account minting it: the owner for a self-mint, a
     *                 site admin minting for an agent; its id is recorded
     */
    Issued issueToken(User user, String label, String scopeWeblog,
                      ApiToken.Role role, Timestamp expiresAt, User issuedBy) throws WebloggerException;

    /** A self-mint, as {@code POST /api/v1/tokens} does: the owner is the issuer. */
    default Issued issueToken(User user, String label, String scopeWeblog,
                              ApiToken.Role role, Timestamp expiresAt) throws WebloggerException {
        return issueToken(user, label, scopeWeblog, role, expiresAt, user);
    }
```

and after `boolean revoke(User user, String tokenId)` add:

```java
    /** The token with this id, live or not, or null for a blank or unknown id. */
    ApiToken getToken(String tokenId) throws WebloggerException;

    /**
     * Revokes {@code token} if it is not revoked already; revoking it again
     * changes nothing. No ownership check: the caller decides who may revoke
     * what. Like every write here it only begins a transaction; flush it.
     */
    void revoke(ApiToken token) throws WebloggerException;

    /** {@code user}'s tokens that are neither revoked nor expired, newest first. */
    List<ApiToken> getLiveTokens(User user) throws WebloggerException;

    /** Every token on the site, newest first: only live ones unless {@code includeInactive}. */
    List<ApiToken> getAllTokens(boolean includeInactive) throws WebloggerException;
```

In `JPAApiTokenManagerImpl.java`, change `issueToken` to the six-argument signature (keep `@Override`) and add `token.setIssuedBy(issuedBy == null ? null : issuedBy.getId());` after `token.setExpiresAt(expiresAt);`. Replace the body of `revoke(User user, String tokenId)` with:

```java
        ApiToken token = getToken(tokenId);
        // Ownership check, not a convenience: tokenId is client input and this
        // is a global by-id load, so without it any user could revoke any
        // other user's tokens.
        if (token == null || !token.getUser().getId().equals(user.getId())) {
            return false;
        }
        revoke(token);
        return true;
```

and add:

```java
    @Override
    public ApiToken getToken(String tokenId) throws WebloggerException {
        if (tokenId == null || tokenId.isBlank()) {
            return null;
        }
        return (ApiToken) strategy.load(ApiToken.class, tokenId);
    }

    @Override
    public void revoke(ApiToken token) throws WebloggerException {
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(new Timestamp(System.currentTimeMillis()));
            strategy.store(token);
        }
    }

    @Override
    public List<ApiToken> getLiveTokens(User user) throws WebloggerException {
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getLiveByUser", ApiToken.class);
        query.setParameter(1, user);
        query.setParameter(2, new Timestamp(System.currentTimeMillis()));
        return query.getResultList();
    }

    @Override
    public List<ApiToken> getAllTokens(boolean includeInactive) throws WebloggerException {
        if (includeInactive) {
            return strategy.getNamedQuery("ApiToken.getAll", ApiToken.class).getResultList();
        }
        TypedQuery<ApiToken> query = strategy.getNamedQuery("ApiToken.getAllLive", ApiToken.class);
        query.setParameter(1, new Timestamp(System.currentTimeMillis()));
        return query.getResultList();
    }
```

In `ApiToken.orm.xml`, after the `ApiToken.removeByUser` named query:

```xml
        <named-query name="ApiToken.getLiveByUser">
            <query>SELECT t FROM ApiToken t WHERE t.user = ?1 AND t.revokedAt IS NULL
                   AND (t.expiresAt IS NULL OR t.expiresAt &gt; ?2) ORDER BY t.created DESC</query>
        </named-query>
        <named-query name="ApiToken.getAll">
            <query>SELECT t FROM ApiToken t ORDER BY t.created DESC</query>
        </named-query>
        <named-query name="ApiToken.getAllLive">
            <query>SELECT t FROM ApiToken t WHERE t.revokedAt IS NULL
                   AND (t.expiresAt IS NULL OR t.expiresAt &gt; ?1) ORDER BY t.created DESC</query>
        </named-query>
```

In `MockWeblogger.java`: add the field `private final ApiTokenManager apiTokenManager = mock(ApiTokenManager.class);` after `userTokenManager`; in the constructor after the `getUserTokenManager` stub add `when(weblogger.getApiTokenManager()).thenReturn(apiTokenManager);`; and after `userTokenManager()` add:

```java
    public ApiTokenManager getApiTokenManager() {
        return apiTokenManager;
    }

    public ApiTokenManager apiTokenManager() {
        return apiTokenManager;
    }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='ApiTokenManagerTest,TokensApiTest,MockWebloggerTest'`
Expected: PASS (`TokensApiTest` still stubs the five-argument default, which `TokensApi` still calls).

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/ApiTokenManager.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAApiTokenManagerImpl.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/ApiToken.orm.xml \
        app/src/test/java/org/apache/roller/weblogger/business/MockWeblogger.java \
        app/src/test/java/org/apache/roller/weblogger/business/ApiTokenManagerTest.java
git commit -m "feat(tokens): record the issuer; getToken, idempotent revoke, live and site-wide listings"
```

---

### Task 3: User manager: agents, last sign-in and the account list

**Model:** haiku

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/UserManager.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml` (two named queries; one changed)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/AgentAccountStoreTest.java` (create)

**Interfaces:**
- Consumes: Task 1's `User.Kind`, `isAgent()`, `AGENT_EMAIL_DOMAIN`, `setLastLogin`.
- Produces:
  - `void UserManager.addAgent(User agent, boolean siteAdmin) throws WebloggerException` — stores kind `AGENT`, enabled, no password (refuses one), email `<userName>@agents.invalid` when blank; grants `editor`, plus `admin` when `siteAdmin`; throws `WebloggerException("error.add.user.userNameInUse")` for a taken username.
  - `void UserManager.recordLogin(String userName) throws WebloggerException` — stamps `lastLogin` now on the named account (enabled or not); does not flush.
  - `List<User> UserManager.getAccounts(User.Kind kind, String text) throws WebloggerException` — every account ordered by username; `kind` null for all; `text` matched case-insensitively against username, screen name, full name and email; blank text for no filter.
  - `getUserByEmail` never returns an agent.

- [ ] **Step 1: Write the failing test**

Create `AgentAccountStoreTest.java`:

```java
package org.apache.roller.weblogger.business;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB-backed: agent accounts, the last sign-in and the account list (spec 2026-10-13, sections 1, 2, 4). */
class AgentAccountStoreTest {

    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        for (String name : created) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
    }

    private static UserManager mgr() {
        return TestUtils.weblogger().getUserManager();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private User addAgent(String userName, String screenName, String email, boolean siteAdmin) throws Exception {
        User agent = new User();
        agent.setUserName(userName);
        agent.setScreenName(screenName);
        agent.setFullName(screenName);
        agent.setEmailAddress(email);
        agent.setDateCreated(new Date());
        mgr().addAgent(agent, siteAdmin);
        TestUtils.endSession(true);
        created.add(userName);
        return mgr().getUserByUserName(userName, null);
    }

    private User addPerson(String name) throws Exception {
        User person = TestUtils.setupUser(name);
        TestUtils.endSession(true);
        created.add(person.getUserName());
        return person;
    }

    /** Criterion 2. */
    @Test
    void anAgentIsStoredWithNoPasswordAsAnEnabledEditor() throws Exception {
        String name = "junit_agent" + suffix();
        User agent = addAgent(name, "Agent One", null, false);

        assertEquals(User.Kind.AGENT, agent.getKind());
        assertTrue(agent.isAgent());
        assertNull(agent.getPassword());
        assertEquals(Boolean.TRUE, agent.getEnabled());
        assertEquals(name + "@agents.invalid", agent.getEmailAddress());
        assertEquals(Set.of("editor"), Set.copyOf(mgr().getRoles(agent)));
        assertFalse(mgr().checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), agent));
    }

    /** Criteria 2 and 23: the site-admin switch is the admin role, and the admin role is the admin permission. */
    @Test
    void theSiteAdminSwitchAddsTheAdminRole() throws Exception {
        User agent = addAgent("junit_agentadm" + suffix(), "Agent Admin", null, true);

        assertEquals(Set.of("editor", "admin"), Set.copyOf(mgr().getRoles(agent)));
        assertTrue(mgr().checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), agent));
    }

    @Test
    void aGivenEmailIsKept() throws Exception {
        User agent = addAgent("junit_agentmail" + suffix(), "Agent Mail", "ops@example.com", false);

        assertEquals("ops@example.com", agent.getEmailAddress());
    }

    @Test
    void aTakenUserNameIsRefused() throws Exception {
        User person = addPerson("agenttaken" + suffix());
        User clash = new User();
        clash.setUserName(person.getUserName());
        clash.setScreenName("Clash");
        clash.setFullName("Clash");
        clash.setDateCreated(new Date());

        WebloggerException refused = assertThrows(WebloggerException.class, () -> mgr().addAgent(clash, false));

        assertEquals("error.add.user.userNameInUse", refused.getMessage());
    }

    @Test
    void anAgentWithAPasswordIsRefused() {
        User agent = new User();
        agent.setUserName("junit_agentpw" + suffix());
        agent.setScreenName("Agent Pw");
        agent.setFullName("Agent Pw");
        agent.setPassword(TestUtils.TEST_PASSWORD_HASH);
        agent.setDateCreated(new Date());

        assertThrows(WebloggerException.class, () -> mgr().addAgent(agent, false));
    }

    /** Criterion 20, the store half. */
    @Test
    void recordingASignInStampsTheLastLogin() throws Exception {
        User person = addPerson("agentlogin" + suffix());
        assertNull(mgr().getUserByUserName(person.getUserName(), null).getLastLogin());

        long before = System.currentTimeMillis();
        mgr().recordLogin(person.getUserName());
        TestUtils.endSession(true);

        Date stamped = mgr().getUserByUserName(person.getUserName(), null).getLastLogin();
        assertNotNull(stamped);
        assertTrue(stamped.getTime() >= before - 1_000L && stamped.getTime() <= System.currentTimeMillis() + 1_000L);
    }

    @Test
    void recordingASignInForAnUnknownNameDoesNothing() throws Exception {
        mgr().recordLogin("junit_nosuchaccount" + suffix());
        TestUtils.endSession(true);
    }

    /** Criterion 19, the store half: kind and text narrow the list, server-side. */
    @Test
    void theAccountListNarrowsByKindAndByText() throws Exception {
        String sfx = suffix();
        User agent = addAgent("junit_agentlist" + sfx, "Agent Zebra" + sfx, null, false);
        User person = addPerson("agentlistp" + sfx);
        person = mgr().getUserByUserName(person.getUserName(), null);
        person.setEmailAddress("quokka" + sfx + "@example.com");
        mgr().saveUser(person);
        TestUtils.endSession(true);

        List<String> agents = names(mgr().getAccounts(User.Kind.AGENT, null));
        List<String> people = names(mgr().getAccounts(User.Kind.PERSON, null));
        List<String> everyone = names(mgr().getAccounts(null, "  "));

        assertTrue(agents.contains(agent.getUserName()));
        assertFalse(agents.contains(person.getUserName()));
        assertTrue(people.contains(person.getUserName()));
        assertFalse(people.contains(agent.getUserName()));
        assertTrue(everyone.containsAll(List.of(agent.getUserName(), person.getUserName())));
        assertEquals(List.of(agent.getUserName()), names(mgr().getAccounts(null, "zebra" + sfx)),
                "the name matches, case-insensitively");
        assertEquals(List.of(person.getUserName()), names(mgr().getAccounts(null, "QUOKKA" + sfx)),
                "the email matches");
        assertEquals(List.of(), names(mgr().getAccounts(User.Kind.AGENT, "quokka" + sfx)),
                "kind and text combine");
    }

    /** Criterion 5, the store half: a reset by email never reaches an agent, even one sorting first. */
    @Test
    void anEmailLookupNeverFindsAnAgent() throws Exception {
        String sfx = suffix();
        String shared = "shared" + sfx + "@example.com";
        addAgent("junit_a0" + sfx, "Agent Shared", shared, false);
        User person = addPerson("zz" + sfx);
        person = mgr().getUserByUserName(person.getUserName(), null);
        person.setEmailAddress(shared);
        mgr().saveUser(person);
        String agentOnly = "agentonly" + sfx + "@example.com";
        addAgent("junit_a1" + sfx, "Agent Only", agentOnly, false);
        TestUtils.endSession(true);

        assertEquals(person.getUserName(), mgr().getUserByEmail(shared).getUserName());
        assertNull(mgr().getUserByEmail(agentOnly));
    }

    private static List<String> names(List<User> users) {
        return users.stream().map(User::getUserName).toList();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -o -q -pl app test -Dtest=AgentAccountStoreTest`
Expected: compilation failure (`addAgent`, `recordLogin`, `getAccounts` do not exist).

- [ ] **Step 3: Implement**

In `UserManager.java`, after `addReader`:

```java
    /**
     * Stores a new agent account (spec 2026-10-13, section 1): kind AGENT,
     * enabled, no password, and the {@code editor} role -- plus {@code admin}
     * when {@code siteAdmin}. A blank email becomes
     * {@code <userName>@agents.invalid}.
     *
     * @throws WebloggerException {@code error.add.user.userNameInUse} when the
     *         username is taken; also when the agent carries a password
     */
    void addAgent(User agent, boolean siteAdmin) throws WebloggerException;

    /**
     * Stamps the named account's last sign-in with now. Enabled or not, an
     * unknown name does nothing. Begins a transaction; the caller flushes.
     */
    void recordLogin(String userName) throws WebloggerException;

    /**
     * Every account, ordered by username, for the admin Users list.
     *
     * @param kind only this kind, or null for every account
     * @param text kept when it occurs, ignoring case, in the username, screen
     *             name, full name or email; blank for no filter
     */
    List<User> getAccounts(User.Kind kind, String text) throws WebloggerException;
```

In `JPAUserManagerImpl.java` add the imports `org.apache.commons.lang3.StringUtils` and `org.apache.commons.lang3.Strings`, then after `addReader`:

```java
    @Override
    public void addAgent(User agent, boolean siteAdmin) throws WebloggerException {
        if (agent == null || agent.getUserName() == null) {
            throw new WebloggerException("cannot add an agent without a username");
        }
        if (agent.getPassword() != null) {
            // Spec 2026-10-13: an agent never signs in. A password would be a
            // second credential nobody can see or revoke from the agent page.
            throw new WebloggerException("an agent account has no password");
        }
        if (getUserByUserName(agent.getUserName(), null) != null
                || getUserByUserName(agent.getUserName().toLowerCase(Locale.ROOT), null) != null) {
            throw new WebloggerException("error.add.user.userNameInUse");
        }
        agent.setKind(User.Kind.AGENT);
        agent.setEnabled(Boolean.TRUE);
        if (StringUtils.isBlank(agent.getEmailAddress())) {
            agent.setEmailAddress(agent.getUserName() + "@" + User.AGENT_EMAIL_DOMAIN);
        }
        if (agent.getDateCreated() == null) {
            agent.setDateCreated(new Date());
        }
        this.strategy.store(agent);
        grantRole("editor", agent);
        if (siteAdmin) {
            grantRole("admin", agent);
        }
    }

    @Override
    public void recordLogin(String userName) throws WebloggerException {
        User user = getUserByUserName(userName, null);
        if (user != null) {
            user.setLastLogin(new Date());
            this.strategy.store(user);
        }
    }

    @Override
    public List<User> getAccounts(User.Kind kind, String text) throws WebloggerException {
        TypedQuery<User> query;
        if (kind == null) {
            query = strategy.getNamedQuery("User.getAllOrderByUserName", User.class);
        } else {
            query = strategy.getNamedQuery("User.getByKindOrderByUserName", User.class);
            query.setParameter(1, kind);
        }
        List<User> accounts = query.getResultList();
        String needle = StringUtils.trimToNull(text);
        if (needle == null) {
            return accounts;
        }
        // Filtered here rather than in JPQL: a LIKE pattern would turn a
        // typed % or _ into a wildcard, and the list is a few dozen rows.
        return accounts.stream().filter(user -> mentions(user, needle)).toList();
    }

    private static boolean mentions(User user, String needle) {
        return Strings.CI.contains(user.getUserName(), needle)
                || Strings.CI.contains(user.getScreenName(), needle)
                || Strings.CI.contains(user.getFullName(), needle)
                || Strings.CI.contains(user.getEmailAddress(), needle);
    }
```

In `getUserByEmail`, after `query.setParameter(2, Boolean.TRUE);` add `query.setParameter(3, User.Kind.PERSON);`.

In `User.orm.xml`, change the `User.getByEmailAddress&amp;Enabled` query text to:

```xml
            <query>SELECT u FROM User u WHERE u.emailAddress = ?1 AND u.enabled = ?2 AND u.ssoSubject IS NULL AND u.kind = ?3 ORDER BY u.userName</query>
```

and extend its XML comment with one line: `People only (V033): nothing is ever mailed to an agent.` Then add, after the `User.getAll` named query:

```xml
        <named-query name="User.getAllOrderByUserName">
            <query>SELECT u FROM User u ORDER BY u.userName</query>
        </named-query>
        <named-query name="User.getByKindOrderByUserName">
            <query>SELECT u FROM User u WHERE u.kind = ?1 ORDER BY u.userName</query>
        </named-query>
```

- [ ] **Step 4: Run it and watch it pass**

Run: `mvn -o -q -pl app test -Dtest='AgentAccountStoreTest,UserQueryAndRoleTest,PasswordResetControllerTest'`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/business/UserManager.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java \
        app/src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml \
        app/src/test/java/org/apache/roller/weblogger/business/AgentAccountStoreTest.java
git commit -m "feat(users): addAgent, recordLogin and the account list; email lookups skip agents"
```

---
### Task 4: Agents never sign in (form, remember-me, Basic, SSO)

**Model:** haiku

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/RollerUserDetailsService.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/sso/ReaderAccounts.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/core/security/RollerUserDetailsServiceTest.java` (add tests)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/core/security/sso/ReaderAccountsTest.java` (add a test)

**Interfaces:**
- Consumes: Task 1's `User.isAgent()`, `User.Kind`.
- Produces: `RollerUserDetailsService.loadUserByUsername` throws `UsernameNotFoundException("ERROR agent account: <name>")` for an agent; `ReaderAccounts.signIn` throws `ReaderSignInRefused("agent account")` for a bound account that is an agent.

- [ ] **Step 1: Write the failing tests**

Add to `RollerUserDetailsServiceTest` these imports:

```java
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.User;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;
```

and these tests:

```java
    private static User agentNamed(String userName) {
        User agent = new User();
        agent.setUserName(userName);
        agent.setKind(User.Kind.AGENT);
        return agent;
    }

    /** Spec 2026-10-13, section 2: an agent is not an account a password can reach. */
    @Test
    void anAgentIsInvisibleToPasswordSignIn() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentNamed("bot"));

            UsernameNotFoundException thrown = assertThrows(UsernameNotFoundException.class,
                    () -> service.loadUserByUsername("bot"));

            assertEquals("ERROR agent account: bot", thrown.getMessage());
        } finally {
            weblogger.detach();
        }
    }

    /**
     * Criterion 4 and review focus 3. Form login and HTTP Basic on /api/**
     * share this provider over this service, so this is the one place both
     * refuse an agent -- with the same generic failure as a wrong password.
     */
    @Test
    void signingInAsAnAgentFailsWithTheSameMessageAsAWrongPassword() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentNamed("bot"));
            User alice = new User();
            alice.setUserName("alice");
            alice.setPassword(TestUtils.TEST_PASSWORD_HASH);
            when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
            when(weblogger.userManager().getRoles(alice)).thenReturn(List.of("editor"));
            DaoAuthenticationProvider dao = new DaoAuthenticationProvider(service);
            dao.setPasswordEncoder(new DelegatingPasswordEncoder("bcrypt",
                    Map.of("bcrypt", new BCryptPasswordEncoder())));

            BadCredentialsException agentRefused = assertThrows(BadCredentialsException.class,
                    () -> dao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("bot", "anything")));
            BadCredentialsException wrongPassword = assertThrows(BadCredentialsException.class,
                    () -> dao.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", "wrong")));

            assertEquals(wrongPassword.getMessage(), agentRefused.getMessage());
        } finally {
            weblogger.detach();
        }
    }

    /**
     * Review focus 3: a remember-me cookie names its account and is resolved
     * through this same service before its signature is even checked, so an
     * agent named in a cookie signs no one in.
     */
    @Test
    void aRememberMeCookieNamingAnAgentSignsNoOneIn() throws Exception {
        MockWeblogger weblogger = MockWeblogger.create();
        try {
            when(provider.getWeblogger()).thenReturn(weblogger.weblogger());
            when(weblogger.userManager().getUserByUserName("bot")).thenReturn(agentNamed("bot"));
            RollerRememberMeServices rememberMe;
            try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
                config.when(() -> WebloggerConfig.getProperty("rememberme.key", "springRocks"))
                        .thenReturn("a-real-secret");
                config.when(() -> WebloggerConfig.getBooleanProperty("rememberme.enabled")).thenReturn(true);
                rememberMe = new RollerRememberMeServices(service);
            }
            long expiry = System.currentTimeMillis() + 3_600_000L;
            String value = Base64.getEncoder().encodeToString(
                    ("bot:" + expiry + ":" + "0".repeat(128)).getBytes(StandardCharsets.UTF_8));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setCookies(new Cookie("remember-me", value));

            assertNull(rememberMe.autoLogin(request, new MockHttpServletResponse()));
            verify(weblogger.userManager()).getUserByUserName("bot");
        } finally {
            weblogger.detach();
        }
    }
```

Add to `ReaderAccountsTest` (after `aDisabledBoundAccountIsRefusedAndNotReplaced`):

```java
    /** Spec 2026-10-13, section 2: SSO never signs in as an agent. */
    @Test
    void aBoundAccountThatIsAnAgentIsRefused() throws Exception {
        ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
        ReaderClaims c = claims("agent-sso@example.com", "A");
        signIn(accounts, c);
        TestUtils.endSession(true);
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User bound = mgr.getUserBySsoIdentity(ISSUER, sub);
        bound.setKind(User.Kind.AGENT);
        mgr.saveUser(bound);
        TestUtils.endSession(true);

        assertThrows(ReaderSignInRefused.class, () -> accounts.signIn(c));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='RollerUserDetailsServiceTest,ReaderAccountsTest'`
Expected: FAIL — the three agent tests, because the agent's null password reaches Spring's `User` constructor ("Cannot pass null or empty values to constructor", surfacing as `IllegalArgumentException` or `InternalAuthenticationServiceException` instead of the expected exception), and `aBoundAccountThatIsAnAgentIsRefused`, because nothing is thrown.

- [ ] **Step 3: Implement**

In `RollerUserDetailsService.loadUserByUsername`, after the `isSsoBound()` block:

```java
            if (userData.isAgent()) {
                // Spec 2026-10-13: an agent never signs in -- form login,
                // remember-me and HTTP Basic all arrive here. Thrown as "not
                // found", which DaoAuthenticationProvider reports as the same
                // "Bad credentials" a wrong password gets.
                throw new UsernameNotFoundException("ERROR agent account: " + userName);
            }
```

In `ReaderAccounts.existingAccount`, before the `getEnabled()` check:

```java
        if (account.isAgent()) {
            // An agent never signs in, through a provider or otherwise
            // (spec 2026-10-13, section 2).
            throw new ReaderSignInRefused("agent account");
        }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='RollerUserDetailsServiceTest,ReaderAccountsTest,RollerRememberMeServicesTest,SsoHandlersTest'`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/core/security/RollerUserDetailsService.java \
        app/src/main/java/org/apache/roller/weblogger/ui/core/security/sso/ReaderAccounts.java \
        app/src/test/java/org/apache/roller/weblogger/ui/core/security/RollerUserDetailsServiceTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/core/security/sso/ReaderAccountsTest.java
git commit -m "feat(security): an agent never signs in -- form, remember-me, Basic and SSO refuse it"
```

---

### Task 5: No password reset or set-password link for an agent

**Model:** haiku

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetController.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserTokenManagerImpl.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetControllerTest.java` (add tests)
- Test: `app/src/test/java/org/apache/roller/weblogger/business/UserTokenManagerTest.java` (add tests)

**Interfaces:**
- Consumes: Task 1's `User.isAgent()`; Task 3's `UserManager.addAgent`.
- Produces: `UserTokenManager.issueToken` throws `WebloggerException("An agent account has no password.")` for an agent; `validate` returns null for a token whose user is an agent; `PasswordResetController` treats an agent (by username or email) as unknown and refuses a reset that consumes an agent's token.

- [ ] **Step 1: Write the failing tests**

Add to `PasswordResetControllerTest`:

```java
    private static User agent(String userName) {
        User agent = user(userName, userName + "@agents.invalid");
        agent.setKind(User.Kind.AGENT);
        return agent;
    }

    /** Criterion 5: an agent named by username reads as an unknown account and is sent nothing. */
    @Test
    void forgotPasswordNamingAnAgentSendsNothing() throws Exception {
        when(mocks.getUserManager().getUserByUserName("bot", Boolean.TRUE)).thenReturn(agent("bot"));

        String view = controller.forgotSend(request(), model, "bot");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    /** Criterion 5: and by email, should the store ever hand one back. */
    @Test
    void forgotPasswordByAnAgentsEmailSendsNothing() throws Exception {
        when(mocks.getUserManager().getUserByEmail("ops@example.com")).thenReturn(agent("bot"));

        controller.forgotSend(request(), model, "ops@example.com");

        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    /** Criterion 5: a set-password link presented for an agent is refused. */
    @Test
    void resetSaveRefusesAnAgent() throws Exception {
        User bot = agent("bot");
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(new UserToken());
        when(mocks.getUserTokenManager().consume("good-token")).thenReturn(bot);

        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                new RedirectAttributesModelMap(), "good-token", "newpassword1", "newpassword1");

        assertEquals(".ResetPassword", view);
        assertEquals(Boolean.FALSE, model.getAttribute("resetTokenValid"));
        verify(mocks.getUserManager(), never()).saveUser(any());
        assertNull(bot.getPassword());
    }
```

Add to `UserTokenManagerTest` the imports `java.util.Date`, `org.apache.roller.weblogger.WebloggerException`, `static org.junit.jupiter.api.Assertions.assertThrows`, and:

```java
    /** Criterion 5: a set-password link is never issued for an agent. */
    @Test
    void noLinkIsIssuedForAnAgent() throws Exception {
        String name = "junit_tokenagent" + System.nanoTime();
        User agent = new User();
        agent.setUserName(name);
        agent.setScreenName("Token Agent");
        agent.setFullName("Token Agent");
        agent.setDateCreated(new Date());
        TestUtils.weblogger().getUserManager().addAgent(agent, false);
        TestUtils.endSession(true);
        try {
            User managed = TestUtils.weblogger().getUserManager().getUserByUserName(name, null);

            WebloggerException refused = assertThrows(WebloggerException.class,
                    () -> manager().issueToken(managed, UserToken.Purpose.PASSWORD_SET));

            assertEquals("An agent account has no password.", refused.getMessage());
        } finally {
            TestUtils.endSession(false);
            TestUtils.teardownUser(name);
            TestUtils.endSession(true);
        }
    }

    /** Criterion 5: a link whose account is an agent does not validate, so the reset form refuses it. */
    @Test
    void aLinkHeldByAnAgentDoesNotValidate() throws Exception {
        String raw = manager().issueToken(TestUtils.getManagedUser(user), UserToken.Purpose.PASSWORD_SET);
        TestUtils.endSession(true);
        User reloaded = TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName(), null);
        reloaded.setKind(User.Kind.AGENT);
        TestUtils.weblogger().getUserManager().saveUser(reloaded);
        TestUtils.endSession(true);

        assertNull(manager().validate(raw));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='PasswordResetControllerTest,UserTokenManagerTest'`
Expected: FAIL — the agent is issued a token and mailed; `resetSaveRefusesAnAgent` NPEs in `resetPassword`'s encoder or throws `IllegalStateException`; `noLinkIsIssuedForAnAgent` throws nothing; `aLinkHeldByAnAgentDoesNotValidate` validates.

- [ ] **Step 3: Implement**

In `PasswordResetController`, replace the body of `findUser` after the blank check with:

```java
        UserManager userManager = weblogger.getUserManager();
        User user = userManager.getUserByUserName(identifier, Boolean.TRUE);
        if (user == null) {
            user = userManager.getUserByEmail(identifier);
        }
        // An SSO-bound account has no password to reset, and an agent has none
        // at all (spec 2026-10-13, section 2): both read as unknown.
        return user == null || user.isSsoBound() || user.isAgent() ? null : user;
```

and in `resetSave` change `if (user.isSsoBound()) {` to `if (user.isSsoBound() || user.isAgent()) {`, extending its comment: `... nor for an agent, which has no password at all.`

In `JPAUserTokenManagerImpl.issueToken`, make the first statement:

```java
        if (user.isAgent()) {
            // Nothing is ever mailed to an agent (spec 2026-10-13, section 1).
            throw new WebloggerException("An agent account has no password.");
        }
```

and in `validate`, just before the final `return token;`:

```java
        if (token.getUser() != null && token.getUser().isAgent()) {
            // A link presented for an agent account is refused.
            return null;
        }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='PasswordResetControllerTest,UserTokenManagerTest'`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetController.java \
        app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserTokenManagerImpl.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/business/UserTokenManagerTest.java
git commit -m "feat(security): password reset and set-password links treat an agent as unknown"
```

---

### Task 6: Record the last interactive sign-in

**Model:** haiku

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/LastLoginRecorder.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java` (one bean)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/core/security/LastLoginRecorderTest.java` (create)
- Test: `app/src/test/java/org/apache/roller/weblogger/boot/SecurityConfigTest.java` (add a test)

**Interfaces:**
- Consumes: Task 3's `UserManager.recordLogin(String)`.
- Produces: `LastLoginRecorder implements ApplicationListener<InteractiveAuthenticationSuccessEvent>`, constructor `LastLoginRecorder(WebloggerProvider)`; bean `SecurityConfig.lastLoginRecorder(WebloggerProvider)`.

- [ ] **Step 1: Write the failing tests**

Create `LastLoginRecorderTest.java`:

```java
package org.apache.roller.weblogger.ui.core.security;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 2026-10-13, section 2; criterion 20: every interactive sign-in stamps last_login. */
class LastLoginRecorderTest {

    private static InteractiveAuthenticationSuccessEvent signInOf(String userName) {
        return new InteractiveAuthenticationSuccessEvent(
                UsernamePasswordAuthenticationToken.authenticated(userName, null, List.of()),
                LastLoginRecorderTest.class);
    }

    private static WebloggerProvider bootstrapped(MockWeblogger tier) {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
        return provider;
    }

    @Test
    void aSignInStampsTheAccountCommitsAndReleases() throws Exception {
        MockWeblogger tier = MockWeblogger.create();

        new LastLoginRecorder(bootstrapped(tier)).onApplicationEvent(signInOf("alice"));

        verify(tier.userManager()).recordLogin("alice");
        assertEquals(1, tier.flushCount());
        verify(tier.weblogger()).release();
    }

    @Test
    void beforeBootstrapNothingIsTouched() {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(false);

        new LastLoginRecorder(provider).onApplicationEvent(signInOf("alice"));

        verify(provider, never()).getWeblogger();
    }

    /** A display value: a store that cannot record it never fails the sign-in. */
    @Test
    void aStoreFailureIsLoggedAndTheSignInProceeds() throws Exception {
        MockWeblogger tier = MockWeblogger.create();
        doThrow(new WebloggerException("db down")).when(tier.userManager()).recordLogin("alice");

        assertDoesNotThrow(() -> new LastLoginRecorder(bootstrapped(tier)).onApplicationEvent(signInOf("alice")));

        assertEquals(0, tier.flushCount());
        verify(tier.weblogger()).release();
    }
}
```

Add to `SecurityConfigTest` the imports `java.util.List`, `org.apache.roller.weblogger.TestUtils`, `org.apache.roller.weblogger.business.MockWeblogger`, `org.apache.roller.weblogger.pojos.User`, `static org.mockito.Mockito.reset`, `static org.mockito.Mockito.verify`, `static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin`, and:

```java
    /**
     * Criterion 20 through the real filter chain: the form-login filter
     * publishes InteractiveAuthenticationSuccessEvent only because the
     * configurer post-processes it with the context's event publisher, and
     * LastLoginRecorder hears it only because SecurityConfig declares it.
     * Either wire coming loose leaves last_login null forever, silently.
     */
    @Test
    void aSuccessfulFormSignInRecordsTheLastLogin() throws Exception {
        WebloggerProvider provider = context.getBean(WebloggerProvider.class);
        MockWeblogger tier = MockWeblogger.create();
        User alice = new User();
        alice.setUserName("alice");
        alice.setPassword(TestUtils.TEST_PASSWORD_HASH);
        when(tier.userManager().getUserByUserName("alice")).thenReturn(alice);
        when(tier.userManager().getRoles(alice)).thenReturn(List.of("editor"));
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
        try {
            mockMvc.perform(formLogin("/roller_j_security_check")
                            .user("j_username", "alice")
                            .password("j_password", TestUtils.TEST_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/roller-ui/login-redirect.rol"));

            verify(tier.userManager()).recordLogin("alice");
            assertEquals(1, tier.flushCount());
        } finally {
            reset(provider);
        }
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='LastLoginRecorderTest,SecurityConfigTest'`
Expected: compilation failure (`LastLoginRecorder` does not exist).

- [ ] **Step 3: Implement**

Create `LastLoginRecorder.java`:

```java
package org.apache.roller.weblogger.ui.core.security;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;

/**
 * Stamps {@code last_login} on every interactive sign-in (spec 2026-10-13,
 * section 2). Form login, remember-me and SSO each publish
 * {@link InteractiveAuthenticationSuccessEvent} from their filter; HTTP Basic
 * on {@code /api/**} does not, so an API call is not a sign-in.
 *
 * <p>A display value, not a decision: a store that cannot record it is
 * logged and the sign-in proceeds. It runs inside the security chain, where a
 * form or SSO sign-in answers the request without ever reaching
 * {@code PersistenceSessionFilter}, so it releases the persistence session
 * itself, as {@code SsoSuccessHandler} does.
 */
public class LastLoginRecorder implements ApplicationListener<InteractiveAuthenticationSuccessEvent> {

    private static final Logger log = LoggerFactory.getLogger(LastLoginRecorder.class);

    private final WebloggerProvider webloggerProvider;

    /** @param webloggerProvider asked per event: a sign-in can arrive before the tier is up */
    public LastLoginRecorder(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    public void onApplicationEvent(InteractiveAuthenticationSuccessEvent event) {
        if (!webloggerProvider.isBootstrapped()) {
            return;
        }
        Weblogger weblogger = webloggerProvider.getWeblogger();
        String userName = event.getAuthentication().getName();
        try {
            weblogger.getUserManager().recordLogin(userName);
            weblogger.flush();
        } catch (WebloggerException e) {
            log.warn("Could not record the sign-in of {}", userName, e);
        } finally {
            weblogger.release();
        }
    }
}
```

In `SecurityConfig.java`, import `org.apache.roller.weblogger.ui.core.security.LastLoginRecorder` and add after `rollerRememberMeServices`:

```java
    /**
     * Stamps last_login on every form, remember-me and SSO sign-in (spec
     * 2026-10-13, section 2). A bean so the context's event multicaster
     * delivers the filters' InteractiveAuthenticationSuccessEvent to it.
     */
    @Bean
    public LastLoginRecorder lastLoginRecorder(WebloggerProvider webloggerProvider) {
        return new LastLoginRecorder(webloggerProvider);
    }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='LastLoginRecorderTest,SecurityConfigTest'`
Expected: PASS. If `aSuccessfulFormSignInRecordsTheLastLogin` fails with "Wanted but not invoked: recordLogin", the event is not reaching the listener: do not weaken the test; follow superpowers:systematic-debugging (check that the login filter's `eventPublisher` is set).

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/core/security/LastLoginRecorder.java \
        app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java \
        app/src/test/java/org/apache/roller/weblogger/ui/core/security/LastLoginRecorderTest.java \
        app/src/test/java/org/apache/roller/weblogger/boot/SecurityConfigTest.java
git commit -m "feat(security): record last_login on every form, remember-me and SSO sign-in"
```

---

### Task 7: A disabled owner's token authenticates no one

**Model:** haiku

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/auth/ApiTokenAuthFilter.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/auth/ApiTokenAuthFilterTest.java` (add tests)

**Interfaces:**
- Consumes: Task 1's `User.Kind`.
- Produces: a Bearer token whose owner has `enabled != TRUE` leaves the security context empty (so the chain answers 401) and releases the persistence session.

- [ ] **Step 1: Write the failing tests**

Add to `ApiTokenAuthFilterTest`:

```java
    private static ApiToken tokenOwnedBy(User owner) {
        ApiToken t = new ApiToken();
        t.setUser(owner);
        t.setScopeRole(ApiToken.Role.POST);
        return t;
    }

    private Authentication filterWith(ApiToken token) throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("rlr_owned")).thenReturn(token);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer rlr_owned");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        return auth;
    }

    /** Criterion 7: checked here, explicitly, not left to the enabled-only user lookup downstream. */
    @Test
    void aDisabledPersonsTokenAuthenticatesNoOne() throws Exception {
        User person = new User();
        person.setUserName("jake");
        person.setEnabled(Boolean.FALSE);

        assertNull(filterWith(tokenOwnedBy(person)));
        verify(weblogger).release();
    }

    /** Criterion 7: an agent likewise -- and re-enabling it makes the same token work again. */
    @Test
    void aDisabledAgentsTokenAuthenticatesNoOneUntilReEnabled() throws Exception {
        User agent = new User();
        agent.setUserName("bot");
        agent.setKind(User.Kind.AGENT);
        agent.setEnabled(Boolean.FALSE);
        ApiToken token = tokenOwnedBy(agent);

        assertNull(filterWith(token));

        agent.setEnabled(Boolean.TRUE);
        Authentication auth = filterWith(token);
        assertNotNull(auth);
        assertEquals("bot", auth.getPrincipal());
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest=ApiTokenAuthFilterTest`
Expected: FAIL — both disabled owners authenticate (`expected: <null>`).

- [ ] **Step 3: Implement**

In `ApiTokenAuthFilter.authenticate`, after `if (token == null) { return; }`:

```java
            // Spec 2026-10-13, section 2: a disabled owner -- person or agent --
            // gets a 401. Checked here, explicitly, rather than left to the
            // enabled-only user lookup downstream that happens to refuse it.
            if (!Boolean.TRUE.equals(token.getUser().getEnabled())) {
                return;
            }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest=ApiTokenAuthFilterTest`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/auth/ApiTokenAuthFilter.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/auth/ApiTokenAuthFilterTest.java
git commit -m "fix(api): a token whose owner is disabled authenticates no one, checked explicitly"
```

---

### Task 8: Token rules shared by UI and API; the `roller.admin` log

**Model:** haiku

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/AdminLog.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenPins.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/TokensApi.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/TokenDtos.java`
- Modify: `app/src/main/resources/log4j2.xml` (one logger)
- Create (test support): `app/src/test/java/org/apache/roller/testing/AdminLogCapture.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/AdminLogTest.java`, `TokenPinsTest.java` (create)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/TokensApiTest.java` (add tests, adjust one)

**Interfaces:**
- Consumes: Task 2's `ApiTokenManager` (unchanged five-argument default for the API's self-mint); Task 1's `ApiToken.getRevokedAt()`.
- Produces:
  - `AdminLog.NAME = "roller.admin"`; `static void AdminLog.record(String event, User by, String details)` — one INFO line `"<event> <details> by=<userName or ->"`; `static String AdminLog.tokenDetails(ApiToken)` — `"id=… label=… owner=… role=… weblog=<handle or *> expires=<instant or never>"`.
  - `static List<Weblog> TokenPins.choices(Weblogger, User owner) throws WebloggerException` — the owner's weblogs, or every weblog for a site admin, sorted by handle.
  - `static boolean TokenPins.allows(Weblogger, User owner, String handle) throws WebloggerException` — true for a blank handle; else the weblog must exist and the owner be a site admin or a member of it.
  - `TokenDtos.TokenView` gains a last component `Instant revokedAt`.
  - `POST /api/v1/tokens`: a pin `TokenPins` refuses → 400 on `weblog`; an `expiresAt` not in the future → 400 on `expiresAt`; a mint and a revoke each log one `roller.admin` line.
  - Test support `org.apache.roller.testing.AdminLogCapture`: `static AdminLogCapture start()`, `List<String> lines()`, `close()`.

- [ ] **Step 1: Write the test support and the failing tests**

Create `app/src/test/java/org/apache/roller/testing/AdminLogCapture.java`:

```java
package org.apache.roller.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/** Captures the {@code roller.admin} logger at INFO for one test, then puts its configuration back. */
public final class AdminLogCapture implements AutoCloseable {

    private static final String LOGGER = "roller.admin";
    private static final String APPENDER = "admin-capture";

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final AbstractAppender appender;
    private final LoggerConfig previous;

    private AdminLogCapture() {
        appender = new AbstractAppender(APPENDER, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        previous = config.getLoggers().get(LOGGER);
        if (previous != null) {
            config.removeLogger(LOGGER);
        }
        LoggerConfig capture = new LoggerConfig(LOGGER, Level.INFO, false);
        capture.addAppender(appender, Level.INFO, null);
        config.addLogger(LOGGER, capture);
        ctx.updateLoggers();
    }

    public static AdminLogCapture start() {
        return new AdminLogCapture();
    }

    /** The formatted lines logged so far, in order. */
    public List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    @Override
    public void close() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.removeLogger(LOGGER);
        if (previous != null) {
            config.addLogger(LOGGER, previous);
        }
        ctx.updateLoggers();
        appender.stop();
    }
}
```

Create `app/src/test/java/org/apache/roller/weblogger/ui/controllers/AdminLogTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 2026-10-13, section 6: one INFO line on roller.admin per event, naming the target and by=. */
class AdminLogTest {

    @Test
    void anEventIsOneLineNamingWhoDidIt() {
        User jake = new User();
        jake.setUserName("jake");
        try (AdminLogCapture capture = AdminLogCapture.start()) {
            AdminLog.record("account.disabled", jake, "account=bot");
            AdminLog.record("token.revoked", null, "id=t1");

            assertEquals(List.of("account.disabled account=bot by=jake", "token.revoked id=t1 by=-"),
                    capture.lines());
        }
        assertEquals("roller.admin", AdminLog.NAME);
    }

    @Test
    void aTokenIsNamedByEverythingButItsSecret() {
        User bot = new User();
        bot.setUserName("bot");
        ApiToken token = new ApiToken();
        token.setId("t1");
        token.setLabel("publisher");
        token.setUser(bot);
        token.setScopeRole(ApiToken.Role.POST);
        token.setTokenSha256("digest-is-not-logged");

        assertEquals("id=t1 label=publisher owner=bot role=POST weblog=* expires=never",
                AdminLog.tokenDetails(token));

        token.setScopeWeblog("travel");
        token.setExpiresAt(Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")));
        assertEquals("id=t1 label=publisher owner=bot role=POST weblog=travel expires=2027-01-01T00:00:00Z",
                AdminLog.tokenDetails(token));
    }
}
```

Create `app/src/test/java/org/apache/roller/weblogger/ui/controllers/TokenPinsTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Review focus 5; criteria 11, 12 and 15: who may pin a token to which weblog. */
class TokenPinsTest {

    private final MockWeblogger tier = MockWeblogger.create();
    private final User owner = new User();
    private final Weblog travel = weblog("travel");
    private final Weblog food = weblog("food");

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
        return weblog;
    }

    @BeforeEach
    void setUp() throws Exception {
        owner.setUserName("owner");
        when(tier.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        when(tier.weblogManager().getWeblogByHandle("food", null)).thenReturn(food);
    }

    private void siteAdmin(boolean admin) throws WebloggerException {
        when(tier.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(owner)))
                .thenReturn(admin);
    }

    @Test
    void noPinIsAlwaysAllowed() throws Exception {
        siteAdmin(false);

        assertTrue(TokenPins.allows(tier.weblogger(), owner, null));
        assertTrue(TokenPins.allows(tier.weblogger(), owner, "  "));
    }

    @Test
    void aMemberMayPinOnlyTheirOwnWeblogs() throws Exception {
        siteAdmin(false);
        when(tier.userManager().getWeblogPermission(travel, owner))
                .thenReturn(new WeblogPermission(travel, owner, List.of(WeblogPermission.POST)));

        assertTrue(TokenPins.allows(tier.weblogger(), owner, "travel"));
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "food"), "a weblog they are not in");
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "nosuch"), "a weblog that does not exist");
    }

    @Test
    void aSiteAdminMayPinAnyExistingWeblogButNoneThatDoesNotExist() throws Exception {
        siteAdmin(true);

        assertTrue(TokenPins.allows(tier.weblogger(), owner, "food"));
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "nosuch"));
    }

    @Test
    void theChoicesAreTheMembershipsOrEveryWeblogForASiteAdmin() throws Exception {
        siteAdmin(false);
        when(tier.userManager().getWeblogPermissions(owner))
                .thenReturn(List.of(new WeblogPermission(travel, owner, List.of(WeblogPermission.POST))));
        assertEquals(List.of(travel), TokenPins.choices(tier.weblogger(), owner));

        siteAdmin(true);
        when(tier.weblogManager().getWeblogs(null, null, null, null, 0, -1)).thenReturn(List.of(travel, food));
        assertEquals(List.of(food, travel), TokenPins.choices(tier.weblogger(), owner), "sorted by handle");
    }
}
```

In `TokensApiTest`: add the imports `java.time.Instant`, `org.apache.roller.testing.AdminLogCapture`, `org.apache.roller.weblogger.business.WeblogManager`, `org.apache.roller.weblogger.pojos.Weblog`, `org.apache.roller.weblogger.pojos.WeblogPermission`, `static org.mockito.ArgumentMatchers.argThat`, `static org.mockito.ArgumentMatchers.eq`, `static org.junit.jupiter.api.Assertions.assertFalse`, `static org.junit.jupiter.api.Assertions.assertTrue`. In `mockedWeblogger()`, before `return weblogger;` add:

```java
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
```

In `mintingATokenReturnsTheSecretOnceAndNeverInTheMetadataView`, after the `when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);` line, add (the caller is a site admin here -- `mockedWeblogger` answers every global check true -- so the pin must name an existing weblog):

```java
        when(weblogger.getWeblogManager().getWeblogByHandle("testblog", null)).thenReturn(new Weblog());
```

Then add:

```java
    private static User basicOwner(Weblogger weblogger) throws Exception {
        User owner = new User();
        owner.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);
        return owner;
    }

    /** Only a check for the global admin action answers false; LOGIN stays true. */
    private static void notASiteAdmin(Weblogger weblogger, User owner) throws Exception {
        when(weblogger.getUserManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(owner)))
                .thenReturn(false);
    }

    private static ApiTokenManager.Issued issued(User owner, String id, String raw) {
        ApiToken persisted = new ApiToken();
        persisted.setId(id);
        persisted.setUser(owner);
        persisted.setLabel("ci");
        persisted.setScopeRole(ApiToken.Role.POST);
        persisted.setCreated(new Timestamp(System.currentTimeMillis()));
        return new ApiTokenManager.Issued(raw, persisted);
    }

    /** Criterion 12: a weblog the caller is not a member of is a 400 on weblog. */
    @Test
    void mintingWithAForeignWeblogIsBadRequestOnWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        notASiteAdmin(weblogger, owner);
        Weblog foreign = new Weblog();
        foreign.setHandle("foreign");
        when(weblogger.getWeblogManager().getWeblogByHandle("foreign", null)).thenReturn(foreign);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\",\"weblog\":\"foreign\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("weblog"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /** Criterion 12: an unknown weblog likewise -- even for a site admin. */
    @Test
    void mintingWithAnUnknownWeblogIsBadRequestOnWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        basicOwner(weblogger);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\",\"weblog\":\"nosuch\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("weblog"));
    }

    @Test
    void aMemberMayPinTheirOwnWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        notASiteAdmin(weblogger, owner);
        Weblog mine = new Weblog();
        mine.setHandle("mine");
        when(weblogger.getWeblogManager().getWeblogByHandle("mine", null)).thenReturn(mine);
        when(weblogger.getUserManager().getWeblogPermission(mine, owner))
                .thenReturn(new WeblogPermission(mine, owner, List.of(WeblogPermission.POST)));
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(issued(owner, "t-7", "rlr_mine"));

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"ci\",\"role\":\"POST\",\"weblog\":\"mine\"}"))
                .andExpect(status().isCreated());
    }

    /** Review focus 5: a site admin may pin any existing weblog. */
    @Test
    void aSiteAdminMayPinAnyExistingWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        when(weblogger.getWeblogManager().getWeblogByHandle("anyblog", null)).thenReturn(new Weblog());
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(issued(owner, "t-8", "rlr_any"));

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"ci\",\"role\":\"ADMIN\",\"weblog\":\"anyblog\"}"))
                .andExpect(status().isCreated());
    }

    /** Criterion 12: an expiry that is not in the future is a 400 on expiresAt. */
    @Test
    void anExpiryInThePastIsBadRequestOnExpiresAt() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        basicOwner(weblogger);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\",\"expiresAt\":\"2020-01-02T03:04:05Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("expiresAt"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /** Criterion 12: the listing shows when a token was revoked, and nothing for a live one. */
    @Test
    void theListShowsWhenATokenWasRevoked() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        ApiToken live = issued(owner, "t-live", "rlr_a").token();
        ApiToken revoked = issued(owner, "t-revoked", "rlr_b").token();
        revoked.setRevokedAt(Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        when(weblogger.getApiTokenManager().getTokens(owner)).thenReturn(List.of(live, revoked));

        String response = mockMvc(new TokensApi(weblogger))
                .perform(get("/v1/tokens"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode body = new tools.jackson.databind.ObjectMapper().readTree(response);
        assertFalse(body.get(0).has("revokedAt"), response);
        assertTrue(body.get(1).has("revokedAt"), response);
    }

    /**
     * Spec 2026-10-13, section 6; review focus 1: a mint is logged on
     * roller.admin, never with its secret. Only our own line is asserted
     * here: the response body IS the secret, and Spring MVC's DEBUG "Writing
     * [...]" line would carry it under a capture of every logger -- which is
     * why production keeps org.springframework at INFO. The UI mint, which
     * never writes the secret into a response body, is held to every logger
     * at every level in Task 13.
     */
    @Test
    void anApiMintIsLoggedWithoutItsSecret() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(issued(owner, "t-9", "rlr_neverInALog"));

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            mockMvc(new TokensApi(weblogger))
                    .perform(post("/v1/tokens").contentType("application/json")
                            .content("{\"label\":\"ci\",\"role\":\"POST\"}"))
                    .andExpect(status().isCreated());

            assertEquals(1, admin.lines().size(), admin.lines().toString());
            String line = admin.lines().get(0);
            for (String part : new String[] {"token.minted", "id=t-9", "label=ci", "owner=owner", "by=owner"}) {
                assertTrue(line.contains(part), part + " in " + line);
            }
            assertFalse(line.contains("rlr_neverInALog"), line);
        }
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='AdminLogTest,TokenPinsTest,TokensApiTest'`
Expected: compilation failure (`AdminLog`, `TokenPins` do not exist).

- [ ] **Step 3: Implement**

Create `AdminLog.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code roller.admin} log (spec 2026-10-13, section 6): one INFO line
 * per change to an account, a weblog membership or an API token, naming the
 * target and, as {@code by=}, the account that made it. The admin UI and the
 * API both write here, through this one class, so a controller keeps its
 * single logger. Never pass a secret: a token is named by
 * {@link #tokenDetails}, which leaves its secret out by construction.
 */
public final class AdminLog {

    /** The logger's name, configured in log4j2.xml. */
    public static final String NAME = "roller.admin";

    private static final Logger log = LoggerFactory.getLogger(NAME);

    private AdminLog() {
    }

    /**
     * Writes {@code "<event> <details> by=<user>"}.
     *
     * @param event   a dotted name, e.g. {@code token.minted}
     * @param by      the acting account; null is written as {@code -}
     * @param details the target as {@code key=value} pairs
     */
    public static void record(String event, User by, String details) {
        log.info("{} {} by={}", event, details, by == null ? "-" : by.getUserName());
    }

    /** A token's id, label, owner, role, weblog pin and expiry -- everything but its secret. */
    public static String tokenDetails(ApiToken token) {
        return "id=" + token.getId()
                + " label=" + token.getLabel()
                + " owner=" + (token.getUser() == null ? "-" : token.getUser().getUserName())
                + " role=" + token.getScopeRole()
                + " weblog=" + (token.getScopeWeblog() == null ? "*" : token.getScopeWeblog())
                + " expires=" + (token.getExpiresAt() == null ? "never" : token.getExpiresAt().toInstant());
    }
}
```

Create `TokenPins.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;

/**
 * Which weblogs a token may be pinned to (spec 2026-10-13, section 3): the
 * weblogs its owner is a member of, or every weblog for a site admin. The one
 * home for the rule, shared by the token form (agent page and Profile) and
 * {@code POST /api/v1/tokens}, like {@link EntryFieldRules}.
 */
public final class TokenPins {

    private TokenPins() {
    }

    /** The weblogs a token for {@code owner} may be pinned to, sorted by handle. */
    public static List<Weblog> choices(Weblogger weblogger, User owner) throws WebloggerException {
        List<Weblog> weblogs = new ArrayList<>();
        if (isSiteAdmin(weblogger, owner)) {
            weblogs.addAll(weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, -1));
        } else {
            for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(owner)) {
                Weblog weblog = permission.getObjectId() == null ? null
                        : weblogger.getWeblogManager().getWeblogByHandle(permission.getObjectId(), null);
                if (weblog != null) {
                    weblogs.add(weblog);
                }
            }
        }
        weblogs.sort(Comparator.comparing(Weblog::getHandle));
        return weblogs;
    }

    /**
     * Whether a token for {@code owner} may be pinned to {@code handle}. A
     * blank handle -- no pin, "all its weblogs" -- always may; otherwise the
     * weblog must exist and the owner be a site admin or a member of it.
     */
    public static boolean allows(Weblogger weblogger, User owner, String handle) throws WebloggerException {
        if (handle == null || handle.isBlank()) {
            return true;
        }
        Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle, null);
        if (weblog == null) {
            return false;
        }
        return isSiteAdmin(weblogger, owner)
                || weblogger.getUserManager().getWeblogPermission(weblog, owner) != null;
    }

    private static boolean isSiteAdmin(Weblogger weblogger, User owner) throws WebloggerException {
        return weblogger.getUserManager().checkPermission(
                new GlobalPermission(List.of(GlobalPermission.ADMIN)), owner);
    }
}
```

In `TokenDtos.java`, add `, Instant revokedAt` as the last component of `TokenView`, and `instant(token.getRevokedAt())` as the last argument in `toView` (after `instant(token.getExpiresAt())`).

In `TokensApi.java`, add the imports `java.time.Instant`, `org.apache.roller.weblogger.ui.controllers.AdminLog` and `org.apache.roller.weblogger.ui.controllers.TokenPins`. In `issue()`, replace

```java
        Timestamp expiresAt = request.expiresAt() == null ? null : Timestamp.from(request.expiresAt());
```

with

```java
        requirePinnable(user, request.weblog());
        Timestamp expiresAt = requireFuture(request.expiresAt());
```

and after its `weblogger.flush();` add `AdminLog.record("token.minted", user, AdminLog.tokenDetails(issued.token()));`. In `revoke()`, after its `weblogger.flush();` add `AdminLog.record("token.revoked", user, "id=" + id);`. Add the helpers:

```java
    /**
     * Spec 2026-10-13, section 3: a pin names a weblog the caller is a member
     * of, or any existing weblog for a site admin -- the rule the token form
     * applies, from {@link TokenPins}.
     */
    private void requirePinnable(User user, String weblog) throws WebloggerException {
        if (!TokenPins.allows(weblogger, user, weblog)) {
            throw ApiException.invalidField("weblog",
                    "weblog must name a weblog you are a member of; '" + weblog + "' is not one.");
        }
    }

    /** Null for a token that never expires; a 400 on expiresAt when it is not in the future. */
    private static Timestamp requireFuture(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw ApiException.invalidField("expiresAt", "expiresAt must be in the future.");
        }
        return Timestamp.from(expiresAt);
    }
```

In `app/src/main/resources/log4j2.xml`, after the `roller.comments` logger line add:

```xml
        <!-- account, membership and API-token changes, each naming by=; never a token secret -->
        <Logger name="roller.admin" level="info" additivity="false"> <AppenderRef ref="asyncRoller"/> </Logger>
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='AdminLogTest,TokenPinsTest,TokensApiTest,OpenApiDocumentTest,McpCoverageTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/AdminLog.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenPins.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/TokensApi.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/TokenDtos.java \
        app/src/main/resources/log4j2.xml \
        app/src/test/java/org/apache/roller/testing/AdminLogCapture.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/AdminLogTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/TokenPinsTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/TokensApiTest.java
git commit -m "feat(api): token pins and future expiries are checked; revokedAt listed; roller.admin log"
```

---
### Task 9: Design cards first

**Model:** haiku

**Files:**
- Create: `docs/design/tables/tables-users.html`
- Create: `docs/design/forms/forms-agent-page.html`
- Create: `docs/design/forms/forms-token-reveal.html`
- Modify: `docs/design/design-system.md` (a section listing the three)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/DesignCardsTest.java` (the count)

**Interfaces:** none (design source of record; Tasks 10, 13, 14 and 15 build their JSPs against these cards).

- [ ] **Step 1: Write the failing test**

In `DesignCardsTest.thereAreCardsToCheckAtAll`, change `assertEquals(19, cards().size(),` to `assertEquals(22, cards().size(),` and add to the comment above it: `+3 for the agent-accounts cards (tables-users, forms-agent-page, forms-token-reveal; spec 2026-10-13, section 7).`

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -o -q -pl app test -Dtest=DesignCardsTest`
Expected: FAIL — `expected: <22> but was: <19>`.

- [ ] **Step 3: Draw the cards**

The three cards share one base `<style>` block. Each card's listing below shows the line `  /* BASE */` where it goes: when writing the file, replace that line with this block, verbatim (the finished cards contain no `/* BASE */`; Step 4 checks):

```css
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; }
  body {
    background: #131C1C;
    color: #DCE7E5;
    font-family: "IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif;
    padding: 40px;
    -webkit-font-smoothing: antialiased;
  }
  .doc-head { margin-bottom: 28px; max-width: 900px; }
  .doc-title { font-size: 20px; font-weight: 600; margin: 0 0 6px; text-wrap: balance; }
  .doc-subtitle { font-size: 14.5px; line-height: 1.55; color: #8FA5A2; margin: 0; }
  .t-light {
    --paper:#F7F9F9; --surface:#FFFFFF; --ink:#17262A; --ink-soft:#5A6E72; --line:#DCE4E4;
    --accent:#0F6E68; --accent-quiet:#E3F0EE; --good:#2F7D4F; --warn:#9A6A1F; --bad:#A33B2E; --focus:#2AA198;
  }
  .t-dark {
    --paper:#131C1C; --surface:#1A2626; --ink:#DCE7E5; --ink-soft:#8FA5A2; --line:#2A3838;
    --accent:#4FB3AA; --accent-quiet:#1E3230; --good:#6FBF8F; --warn:#D0A45C; --bad:#D0705F; --focus:#2AA198;
  }
  .panels { display: flex; gap: 24px; flex-wrap: wrap; align-items: flex-start; }
  .panel {
    border-radius: 6px; padding: 24px; border: 1px solid var(--line);
    background: var(--paper); color: var(--ink);
    flex: 1 1 480px; min-width: 380px;
  }
  .panel-label {
    font-size: 12px; letter-spacing: .08em; text-transform: uppercase;
    color: var(--ink-soft); font-weight: 600; margin: 0 0 16px;
  }
  .mono { font-family: "IBM Plex Mono", ui-monospace, "SFMono-Regular", Menlo, monospace; font-variant-numeric: tabular-nums; }
  .btn {
    display: inline-flex; align-items: center; font-size: 14px; font-weight: 600; padding: 7px 14px;
    border-radius: 6px; border: 1px solid var(--line); background: var(--surface); color: var(--ink);
  }
  .btn.primary { background: var(--accent); border-color: var(--accent); color: var(--surface); }
  .btn.danger { background: transparent; border-color: var(--bad); color: var(--bad); }
  .btn.small { font-size: 12.5px; padding: 4px 10px; }
  table.rows { width: 100%; border-collapse: collapse; }
  table.rows th {
    text-align: left; font-size: 12px; letter-spacing: .08em; text-transform: uppercase;
    color: var(--ink-soft); font-weight: 600; padding: 0 10px 10px; border-bottom: 1px solid var(--line);
  }
  table.rows td { padding: 11px 10px; border-bottom: 1px solid var(--line); font-size: 14.5px; vertical-align: middle; }
  table.rows tr:last-child td { border-bottom: none; }
  .pill { display: inline-flex; font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 99px; }
  .pill.good { background: color-mix(in srgb, var(--good) 16%, var(--paper)); color: var(--good); }
  .pill.bad { background: color-mix(in srgb, var(--bad) 14%, var(--paper)); color: var(--bad); }
  .soft { color: var(--ink-soft); font-size: 12.5px; }
```

`docs/design/tables/tables-users.html`:

```html
<!-- @dsCard group="Tables" name="Users List" subtitle="People and agents in one list: tabs, filter, kind and status" -->
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Users List</title>
<style>
  /* BASE */
  .toolbar { display: flex; gap: 8px; margin-bottom: 16px; }
  .tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
  .tab { padding: 7px 12px; font-size: 14.5px; color: var(--ink-soft); border-bottom: 2px solid transparent; margin-bottom: -1px; }
  .tab.active { color: var(--ink); font-weight: 600; border-bottom-color: var(--accent); }
  .filter { display: flex; gap: 8px; margin-bottom: 14px; }
  .filter .field { flex: 1; padding: 7px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); color: var(--ink-soft); font-size: 14.5px; }
  .name { font-weight: 600; color: var(--accent); display: block; }
  .kind { font-size: 12px; letter-spacing: .06em; text-transform: uppercase; color: var(--ink-soft); }
</style>
</head>
<body>
  <div class="doc-head">
    <p class="doc-title">Users List</p>
    <p class="doc-subtitle">Admin &rarr; Users. Every account in one list, people and agents alike. New person is the screen's one primary action; the tabs and the filter narrow the list on the server. Last active is a person's last sign-in, or an agent's newest token use.</p>
  </div>

  <div class="panels">
    <section class="panel t-light">
      <p class="panel-label">Light</p>
      <div class="toolbar"><span class="btn primary">New person</span><span class="btn">New agent</span></div>
      <div class="tabs"><span class="tab active">All</span><span class="tab">People</span><span class="tab">Agents</span></div>
      <div class="filter"><span class="field">Username, name or email</span><span class="btn">Filter</span></div>
      <table class="rows">
        <thead><tr><th>Account</th><th>Kind</th><th>Role</th><th>Status</th><th>Weblogs</th><th>Last active</th></tr></thead>
        <tbody>
          <tr><td><span class="name">Jake Fear</span><span class="soft mono">jake</span></td><td class="kind">Person</td><td>Site admin</td><td><span class="pill good">Enabled</span></td><td class="mono">3</td><td class="mono">2026-10-13 09:12</td></tr>
          <tr><td><span class="name">Publisher</span><span class="soft mono">publisher</span></td><td class="kind">Agent</td><td>Author</td><td><span class="pill good">Enabled</span></td><td class="mono">1</td><td class="mono">2026-10-13 08:40</td></tr>
          <tr><td><span class="name">Old Importer</span><span class="soft mono">importer</span></td><td class="kind">Agent</td><td>Author</td><td><span class="pill bad">Disabled</span></td><td class="mono">0</td><td class="mono"></td></tr>
        </tbody>
      </table>
    </section>

    <section class="panel t-dark">
      <p class="panel-label">Dark</p>
      <div class="toolbar"><span class="btn primary">New person</span><span class="btn">New agent</span></div>
      <div class="tabs"><span class="tab">All</span><span class="tab">People</span><span class="tab active">Agents</span></div>
      <div class="filter"><span class="field">pub</span><span class="btn">Filter</span></div>
      <table class="rows">
        <thead><tr><th>Account</th><th>Kind</th><th>Role</th><th>Status</th><th>Weblogs</th><th>Last active</th></tr></thead>
        <tbody>
          <tr><td><span class="name">Publisher</span><span class="soft mono">publisher</span></td><td class="kind">Agent</td><td>Author</td><td><span class="pill good">Enabled</span></td><td class="mono">1</td><td class="mono">2026-10-13 08:40</td></tr>
        </tbody>
      </table>
    </section>
  </div>
</body>
</html>
```

`docs/design/forms/forms-agent-page.html`:

```html
<!-- @dsCard group="Forms" name="Agent Page" subtitle="Details, one confirmed Disable, weblogs and tokens" -->
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Agent Page</title>
<style>
  /* BASE */
  .section-head { font-size: 16px; font-weight: 600; margin: 24px 0 10px; }
  .first { margin-top: 0; }
  .field { margin-bottom: 12px; }
  .label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 4px; }
  .input { display: block; padding: 7px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); color: var(--ink); font-size: 14.5px; }
  .input.fixed { background: var(--paper); color: var(--ink-soft); }
  .check { display: inline-flex; align-items: center; gap: 8px; font-size: 14.5px; }
  .box { width: 16px; height: 16px; border: 1px solid var(--line); border-radius: 3px; background: var(--surface); }
  .status { display: flex; align-items: center; gap: 12px; }
  .add { display: flex; gap: 8px; align-items: center; margin-top: 10px; }
</style>
</head>
<body>
  <div class="doc-head">
    <p class="doc-title">Agent Page</p>
    <p class="doc-subtitle">An agent never signs in, so its page has no password and no set-password link. Save is the one primary action. Disable is a single destructive control that asks first and ends every token on its next request. Weblogs and API tokens are the same sections a person's page and the Profile use.</p>
  </div>

  <div class="panels">
    <section class="panel t-light">
      <p class="panel-label">Light</p>
      <p class="section-head first">Details</p>
      <div class="field"><span class="label">Username</span><span class="input fixed mono">publisher</span></div>
      <div class="field"><span class="label">Name</span><span class="input">Publisher</span></div>
      <div class="field"><span class="label">Notes</span><span class="input">Drafts and publishes the travel guides from the content pipeline.</span></div>
      <div class="field"><span class="check"><span class="box"></span>Site administrator</span><p class="soft">With this on, an Admin token from this agent can use the site-wide admin API.</p></div>
      <span class="btn primary">Save</span>

      <p class="section-head">Status</p>
      <div class="status"><span class="pill good">Enabled</span><span class="btn danger">Disable</span><span class="soft">Asks first.</span></div>

      <p class="section-head">Weblogs</p>
      <table class="rows">
        <thead><tr><th>Name</th><th>Level</th><th></th></tr></thead>
        <tbody><tr><td>Travel <span class="soft mono">travel</span></td><td><span class="input">Author</span></td><td><span class="btn small">Change</span> <span class="btn small">Remove</span></td></tr></tbody>
      </table>
      <div class="add"><span class="input">Food (food)</span><span class="input">Author</span><span class="btn">Add to weblog</span></div>

      <p class="section-head">API tokens</p>
      <table class="rows">
        <thead><tr><th>Label</th><th>Role</th><th>Weblog</th><th>Last used</th><th>Expires</th><th></th></tr></thead>
        <tbody><tr><td>publisher</td><td>Write</td><td class="mono">travel</td><td class="mono">2026-10-13 08:40</td><td class="mono">2027-01-11 08:00</td><td><span class="btn small">Revoke</span></td></tr></tbody>
      </table>
    </section>

    <section class="panel t-dark">
      <p class="panel-label">Dark</p>
      <p class="section-head first">Details</p>
      <div class="field"><span class="label">Username</span><span class="input fixed mono">publisher</span></div>
      <div class="field"><span class="label">Name</span><span class="input">Publisher</span></div>
      <span class="btn primary">Save</span>

      <p class="section-head">Status</p>
      <div class="status"><span class="pill bad">Disabled</span><span class="btn">Enable</span><span class="soft">None of its tokens work until it is enabled again.</span></div>

      <p class="section-head">API tokens</p>
      <table class="rows">
        <thead><tr><th>Label</th><th>Role</th><th>Weblog</th><th>Last used</th><th>Expires</th><th></th></tr></thead>
        <tbody><tr><td>publisher</td><td>Write</td><td class="mono">travel</td><td class="mono">2026-10-13 08:40</td><td class="mono">Never</td><td><span class="btn small">Revoke</span></td></tr></tbody>
      </table>
    </section>
  </div>
</body>
</html>
```

`docs/design/forms/forms-token-reveal.html`:

```html
<!-- @dsCard group="Forms" name="Token Form and Reveal" subtitle="Mint with a pin and an expiry; the secret shown once" -->
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Token Form and Reveal</title>
<style>
  /* BASE */
  .section-head { font-size: 16px; font-weight: 600; margin: 24px 0 10px; }
  .first { margin-top: 0; }
  .field { margin-bottom: 12px; }
  .label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 4px; }
  .input { display: block; padding: 7px 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); color: var(--ink); font-size: 14.5px; }
  .secret { display: flex; gap: 8px; align-items: stretch; margin-bottom: 12px; }
  .secret .input { flex: 1; background: var(--accent-quiet); border-color: var(--accent); overflow-wrap: anywhere; }
  .empty-title { font-weight: 600; font-size: 16px; margin: 0 0 4px; }
</style>
</head>
<body>
  <div class="doc-head">
    <p class="doc-title">Token Form and Reveal</p>
    <p class="doc-subtitle">One form on the agent page and the Profile. The weblog choices are the account's own weblogs (every weblog for a site admin). A created token lands on a page that shows the secret once, with an MCP snippet, and is never cached; loading it again says the token was already shown.</p>
  </div>

  <div class="panels">
    <section class="panel t-light">
      <p class="panel-label">Light &mdash; the form</p>
      <div class="field"><span class="label">Label</span><span class="input">publisher</span></div>
      <div class="field"><span class="label">Role</span><span class="input">Write</span></div>
      <div class="field"><span class="label">Weblog</span><span class="input">All its weblogs</span></div>
      <div class="field"><span class="label">Expires</span><span class="input">In 90 days</span></div>
      <span class="btn">Create token</span>

      <p class="section-head">Already shown</p>
      <p class="empty-title">This token was already shown.</p>
      <p class="soft">A token is shown only once. To get a new one, revoke it and create another.</p>
    </section>

    <section class="panel t-dark">
      <p class="panel-label">Dark &mdash; the reveal</p>
      <p class="section-head first">Your new token</p>
      <p class="soft">Copy it now. This is the only time it is shown.</p>
      <span class="label">Token</span>
      <div class="secret"><span class="input mono">rlr_7hQ2c9Vb1kZ0pX4mW8sT3yN6aE5uR2dF</span><span class="btn">Copy</span></div>
      <span class="label">MCP connection</span>
      <div class="secret"><span class="input mono">claude mcp add --transport http roller https://blog.wikantik.com/api/mcp --header "Authorization: Bearer rlr_7hQ2c9Vb1kZ0pX4mW8sT3yN6aE5uR2dF"</span><span class="btn">Copy</span></div>
      <span class="btn primary">Done</span>
    </section>
  </div>
</body>
</html>
```

Append to `docs/design/design-system.md`, before the `## Quality bar` heading:

```markdown
## Agent accounts and API tokens (2026-10-13)

Drawn ahead of the JSPs, per the cards-first rule
(`docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`, section 7):

- `tables/`tables-users.html — group="Tables" — Admin → Users: New person (the one primary) and New agent, All / People / Agents tabs over a server-side filter, and rows with the name over its mono username, kind, role, an Enabled/Disabled pill, a weblog count and a mono last-active.
- `forms/`forms-agent-page.html — group="Forms" — an agent's page: Details (fixed username, name, notes, the Site administrator switch, Save), Status (one Disable or Enable that asks first), Weblogs (a level per membership with Change and Remove, then Add to weblog) and API tokens.
- `forms/`forms-token-reveal.html — group="Forms" — the token form (label; Read, Write or Admin; all its weblogs or one; 30, 90 or 365 days or never) and the one-time reveal: the secret and an MCP snippet, each with Copy, then Done; and the already-shown state.

```

- [ ] **Step 4: Run it and watch it pass**

Run: `grep -c "BASE" docs/design/tables/tables-users.html docs/design/forms/forms-agent-page.html docs/design/forms/forms-token-reveal.html` — expected `:0` for each file.

Run: `mvn -o -q -pl app test -Dtest=DesignCardsTest`
Expected: PASS (count, markers, no external reference, listed in the spec).

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add docs/design/tables/tables-users.html docs/design/forms/forms-agent-page.html \
        docs/design/forms/forms-token-reveal.html docs/design/design-system.md \
        app/src/test/java/org/apache/roller/weblogger/ui/DesignCardsTest.java
git commit -m "docs(design): cards for the Users list, the agent page and the token reveal"
```

---

### Task 10: Admin → Users lists every account

**Model:** sonnet — replaces a page that two other controllers and three browser ITs reach into, and deletes keys across eight bundles.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AccountRow.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AccountRows.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserAdminController.java` (rewrite)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java` (its six `.UserAdmin` returns)
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/UserAdmin.jsp` (rewrite)
- Delete: `app/src/main/webapp/roller-ui/scripts/ajax-user.js`
- Modify: `app/src/main/webapp/roller-ui/styles/roller.css` (two pill states)
- Modify: the 8 `app/src/main/resources/ApplicationResources*.properties`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java`, `UserAdminIT.java`, `SsoSignInIT.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserAdminControllerTest.java` (rewrite), `UserEditControllerTest.java` (one test)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountMessagesTest.java` (create)

**Interfaces:**
- Consumes: Task 3's `UserManager.getAccounts(User.Kind, String)`; Task 1's `User.getLastLogin()`, `isAgent()`; `ApiTokenManager.getTokens(User)`.
- Produces:
  - `AccountRow` (public): `getUser()`, `getUserName()`, `getName()` (screen name), `isAgent()`, `isEnabled()`, `AccountRow.Role getRole()` (`READER`/`AUTHOR`/`SITE_ADMIN`), `int getWeblogCount()`, `Date getLastActive()`.
  - `AccountRows` (package-private): `static String showList(Weblogger, Model, String kind, String text)` → adds `accounts` (`List<AccountRow>`), `kindFilter` (`""`/`"people"`/`"agents"`), `textFilter` (trimmed, `""` for none) and returns `".UserAdmin"`; `static List<AccountRow> load(Weblogger, User.Kind, String) throws WebloggerException`.
  - `GET /roller-ui/admin/userAdmin.rol?kind=&q=`; marker `#userListPage`; each row's link `a[data-username='<userName>']` (to `agent.rol?userName=` for an agent, `modifyUser.rol?bean.userName=` for a person).
  - CSS `.status-pill.status-enabled`, `.status-pill.status-disabled`.

- [ ] **Step 1: Write the failing tests**

Replace `UserAdminControllerTest.java` entirely with:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin → Users (spec 2026-10-13, section 4; criterion 19): every account, tabs and a filter. */
class UserAdminControllerTest {

    private MockWeblogger weblogger;
    private UserAdminController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new UserAdminController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void theUsersPageIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("userAdmin", controller.getActionName());
        assertEquals("userAdmin.title.searchUser", controller.getPageTitle());
    }

    @Test
    void theListShowsEveryAccountWithKindRoleStatusWeblogsAndLastActive() throws Exception {
        User person = account("jake", User.Kind.PERSON);
        person.setLastLogin(new Date(1_000_000L));
        User agent = account("bot", User.Kind.AGENT);
        agent.setEnabled(Boolean.FALSE);
        when(weblogger.userManager().getAccounts(null, null)).thenReturn(List.of(person, agent));
        when(weblogger.userManager().getRoles(person)).thenReturn(List.of("editor", "admin"));
        when(weblogger.userManager().getRoles(agent)).thenReturn(List.of("editor"));
        when(weblogger.userManager().getWeblogPermissions(person))
                .thenReturn(List.of(new WeblogPermission(), new WeblogPermission()));
        when(weblogger.apiTokenManager().getTokens(agent))
                .thenReturn(List.of(usedAt(1_000L), usedAt(5_000L), usedAt(null)));

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model, null, null);

        assertEquals(".UserAdmin", view);
        List<AccountRow> rows = rows();
        assertEquals(2, rows.size());

        AccountRow jake = rows.get(0);
        assertEquals("jake", jake.getUserName());
        assertFalse(jake.isAgent());
        assertEquals(AccountRow.Role.SITE_ADMIN, jake.getRole());
        assertTrue(jake.isEnabled());
        assertEquals(2, jake.getWeblogCount());
        assertEquals(1_000_000L, jake.getLastActive().getTime(), "a person's last active is the last sign-in");

        AccountRow bot = rows.get(1);
        assertTrue(bot.isAgent());
        assertEquals(AccountRow.Role.AUTHOR, bot.getRole());
        assertFalse(bot.isEnabled());
        assertEquals(0, bot.getWeblogCount());
        assertEquals(5_000L, bot.getLastActive().getTime(), "an agent's last active is its newest token use");
        assertNotNull(model.getAttribute("menu"), "the admin menu is what makes the rest of the section reachable");
    }

    @Test
    void anAccountWithNoLoginRoleIsAReaderAndOneNeverActiveShowsNothing() throws Exception {
        User reader = account("reader-abc", User.Kind.PERSON);
        when(weblogger.userManager().getAccounts(null, null)).thenReturn(List.of(reader));
        when(weblogger.userManager().getRoles(reader)).thenReturn(List.of("reader"));

        controller.execute(ControllerTestFixture.requestFor(admin()), model, null, null);

        AccountRow row = rows().get(0);
        assertEquals(AccountRow.Role.READER, row.getRole());
        assertNull(row.getLastActive());
    }

    /** Criterion 19: the tabs and the text filter narrow the list on the server. */
    @Test
    void thePeopleAndAgentsTabsAndTheFilterNarrowTheListServerSide() throws Exception {
        controller.execute(ControllerTestFixture.requestFor(admin()), model, "agents", "  bo ");

        verify(weblogger.userManager()).getAccounts(User.Kind.AGENT, "bo");
        assertEquals("agents", model.getAttribute("kindFilter"));
        assertEquals("bo", model.getAttribute("textFilter"));

        controller.execute(ControllerTestFixture.requestFor(admin()), new ExtendedModelMap(), "people", null);
        verify(weblogger.userManager()).getAccounts(User.Kind.PERSON, null);

        ExtendedModelMap anything = new ExtendedModelMap();
        controller.execute(ControllerTestFixture.requestFor(admin()), anything, "nonsense", "");
        verify(weblogger.userManager()).getAccounts(null, null);
        assertEquals("", anything.getAttribute("kindFilter"));
        assertEquals("", anything.getAttribute("textFilter"));
    }

    /** A display path: a store that cannot list shows an empty list, not an error page. */
    @Test
    void aStoreThatCannotListShowsAnEmptyList() throws Exception {
        when(weblogger.userManager().getAccounts(null, null)).thenThrow(new WebloggerException("db down"));

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model, null, null);

        assertEquals(".UserAdmin", view);
        assertTrue(rows().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<AccountRow> rows() {
        return (List<AccountRow>) model.getAttribute("accounts");
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName("Name of " + userName);
        user.setKind(kind);
        return user;
    }

    private static ApiToken usedAt(Long millis) {
        ApiToken token = new ApiToken();
        token.setLastUsedAt(millis == null ? null : new Timestamp(millis));
        return token;
    }

    private static User admin() {
        User user = new User();
        user.setUserName("admin");
        return user;
    }
}
```

Add to `UserEditControllerTest`:

```java
    /** Task 10: a save lands on the Users list, so it must carry the list's rows. */
    @Test
    void aSuccessfulCreateLandsOnAUsersListWithItsRows() throws Exception {
        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("listed", "secret"));

        assertEquals(List.of(), model.getAttribute("accounts"));
        assertEquals("", model.getAttribute("kindFilter"));
    }
```

Create `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountMessagesTest.java`:

```java
package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterion 25 (spec 2026-10-13): every message key the agent-accounts work
 * adds is in all eight bundles. MessageKeyTest checks only the base bundle
 * and ApplicationResourcesTest runs nothing, so without this a translation
 * silently falls back to English -- or, for a key a later task forgets,
 * to nothing it can be checked against.
 */
class AgentAccountMessagesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static final List<String> PREFIXES =
            List.of("userList.", "personForm.", "userWeblogs.", "apiTokens.", "agent.");

    private static final Set<String> NAMED_KEYS = Set.of("tabbedmenu.admin.apiTokens");

    private static final List<String> TRANSLATIONS = List.of("de", "es", "fr", "ja", "ko", "ru", "zh_CN");

    private static Properties load(String fileName) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(RESOURCES.resolve(fileName))) {
            props.load(in);
        }
        return props;
    }

    @Test
    void everyAgentAccountsKeyIsInAllEightBundles() throws IOException {
        Properties base = load("ApplicationResources.properties");
        Set<String> keys = new TreeSet<>();
        for (String key : base.stringPropertyNames()) {
            if (NAMED_KEYS.contains(key) || PREFIXES.stream().anyMatch(key::startsWith)) {
                keys.add(key);
            }
        }
        assertTrue(keys.size() >= 20,
                "found only " + keys + " -- the scan is not looking where it thinks it is");

        List<String> missing = new ArrayList<>();
        for (String language : TRANSLATIONS) {
            Properties translation = load("ApplicationResources_" + language + ".properties");
            for (String key : keys) {
                if (!translation.containsKey(key)) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys missing from a translation:\n  " + String.join("\n  ", missing));
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='UserAdminControllerTest,UserEditControllerTest,AgentAccountMessagesTest'`
Expected: compilation failure (`AccountRow`, the four-argument `execute` do not exist).

- [ ] **Step 3: Implement the list**

Create `AccountRow.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.Date;

import org.apache.roller.weblogger.pojos.User;

/** One row of Admin → Users (spec 2026-10-13, section 4): an account and what the list says about it. */
public final class AccountRow {

    /** The account's role as the list names it: no login action, {@code editor}, or {@code admin}. */
    public enum Role { READER, AUTHOR, SITE_ADMIN }

    private final User user;
    private final Role role;
    private final int weblogCount;
    private final Date lastActive;

    public AccountRow(User user, Role role, int weblogCount, Date lastActive) {
        this.user = user;
        this.role = role;
        this.weblogCount = weblogCount;
        this.lastActive = lastActive == null ? null : (Date) lastActive.clone();
    }

    public User getUser() {
        return user;
    }

    public String getUserName() {
        return user.getUserName();
    }

    /** The screen name, which the list shows over the username. */
    public String getName() {
        return user.getScreenName();
    }

    public boolean isAgent() {
        return user.isAgent();
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(user.getEnabled());
    }

    public Role getRole() {
        return role;
    }

    public int getWeblogCount() {
        return weblogCount;
    }

    /** A person's last sign-in, or an agent's newest token use; null when never. */
    public Date getLastActive() {
        return lastActive == null ? null : (Date) lastActive.clone();
    }
}
```

Create `AccountRows.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;

/**
 * Builds Admin → Users (spec 2026-10-13, section 4) for every controller that
 * lands on it -- the list itself, and the user editor and agent page after a
 * save or a missing account -- so the page always has its rows.
 */
final class AccountRows {

    private static final Logger log = LoggerFactory.getLogger(AccountRows.class);

    private AccountRows() {
    }

    /**
     * Puts the list on the model and names its view. A display path: a store
     * that cannot list shows an empty list, logged.
     *
     * @param kind {@code people}, {@code agents}, or anything else for every account
     * @param text the filter as typed; blank for none
     */
    static String showList(Weblogger weblogger, Model model, String kind, String text) {
        String tab = "people".equals(kind) || "agents".equals(kind) ? kind : "";
        String filter = StringUtils.trimToEmpty(text);
        model.addAttribute("kindFilter", tab);
        model.addAttribute("textFilter", filter);
        model.addAttribute("accounts", rowsOrNone(weblogger, kindOf(tab), StringUtils.trimToNull(filter)));
        return ".UserAdmin";
    }

    static List<AccountRow> load(Weblogger weblogger, User.Kind kind, String text) throws WebloggerException {
        UserManager users = weblogger.getUserManager();
        List<AccountRow> rows = new ArrayList<>();
        for (User user : users.getAccounts(kind, text)) {
            rows.add(new AccountRow(user, roleOf(users.getRoles(user)),
                    users.getWeblogPermissions(user).size(), lastActive(weblogger, user)));
        }
        return rows;
    }

    private static List<AccountRow> rowsOrNone(Weblogger weblogger, User.Kind kind, String text) {
        try {
            return load(weblogger, kind, text);
        } catch (WebloggerException e) {
            log.error("Could not list the accounts", e);
            return List.of();
        }
    }

    private static User.Kind kindOf(String tab) {
        if ("people".equals(tab)) {
            return User.Kind.PERSON;
        }
        if ("agents".equals(tab)) {
            return User.Kind.AGENT;
        }
        return null;
    }

    private static AccountRow.Role roleOf(List<String> roles) {
        if (roles.contains("admin")) {
            return AccountRow.Role.SITE_ADMIN;
        }
        if (roles.contains("editor")) {
            return AccountRow.Role.AUTHOR;
        }
        return AccountRow.Role.READER;
    }

    /** A person's last sign-in; for an agent, which never signs in, its newest token use. */
    private static Date lastActive(Weblogger weblogger, User user) throws WebloggerException {
        if (!user.isAgent()) {
            return user.getLastLogin();
        }
        Date newest = null;
        for (ApiToken token : weblogger.getApiTokenManager().getTokens(user)) {
            Timestamp used = token.getLastUsedAt();
            if (used != null && (newest == null || used.after(newest))) {
                newest = used;
            }
        }
        return newest;
    }
}
```

Replace `UserAdminController.java` (keep its licence header) with:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Admin → Users (spec 2026-10-13, section 4): every account, people and
 * agents, with All / People / Agents tabs and a filter the server applies.
 * It replaced a type-ahead search box that listed nothing until searched.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserAdminController extends BaseController {

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getPageTitle() {
        return "userAdmin.title.searchUser";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "userAdmin";
    }

    /**
     * @param kind {@code people} or {@code agents} for a tab; anything else is All
     * @param q    matched against username, name and email, ignoring case
     */
    @GetMapping("/userAdmin.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(name = "kind", required = false) String kind,
                          @RequestParam(name = "q", required = false) String q) {
        populateCommonModel(request, model);
        return AccountRows.showList(weblogger, model, kind, q);
    }
}
```

In `UserEditController.java`, every `return ".UserAdmin";` becomes the list with its rows:

```bash
sed -i 's/return "\.UserAdmin";/return AccountRows.showList(weblogger, model, null, null);/' \
    app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java
grep -c 'AccountRows.showList' app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java
```

Expected output of the `grep`: `6`.

- [ ] **Step 4: Rewrite the page**

Replace `app/src/main/webapp/WEB-INF/jsps/admin/UserAdmin.jsp` with (licence header block first, then):

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- Admin -> Users (spec 2026-10-13, section 4; docs/design/tables/tables-users.html):
     every account, people and agents, with All / People / Agents tabs and a
     filter the server applies. RouteSweepIT finds this page by #userListPage;
     the ITs open an account by its link's data-username. --%>
<div id="userListPage">

<p class="subtitle"><spring:message code="userAdmin.subtitle.searchUser"/></p>

<div class="d-flex flex-wrap gap-2 mb-3">
    <a class="btn btn-primary btn-sm" id="newPersonLink"
       href="<c:url value='/roller-ui/admin/createUser.rol'/>"><spring:message code="userList.newPerson"/></a>
    <a class="btn btn-secondary btn-sm" id="newAgentLink"
       href="<c:url value='/roller-ui/admin/createAgent.rol'/>"><spring:message code="userList.newAgent"/></a>
</div>

<c:url var="allTabUrl" value="/roller-ui/admin/userAdmin.rol">
    <c:param name="q" value="${textFilter}"/>
</c:url>
<c:url var="peopleTabUrl" value="/roller-ui/admin/userAdmin.rol">
    <c:param name="kind" value="people"/>
    <c:param name="q" value="${textFilter}"/>
</c:url>
<c:url var="agentsTabUrl" value="/roller-ui/admin/userAdmin.rol">
    <c:param name="kind" value="agents"/>
    <c:param name="q" value="${textFilter}"/>
</c:url>
<spring:message code="userList.tabs" var="tabsLabel"/>
<nav class="nav nav-tabs mb-3" aria-label="${fn:escapeXml(tabsLabel)}">
    <a class="nav-link${empty kindFilter ? ' active' : ''}" id="userListTabAll" href="${allTabUrl}"
       <c:if test="${empty kindFilter}">aria-current="page"</c:if>><spring:message code="userList.tab.all"/></a>
    <a class="nav-link${kindFilter == 'people' ? ' active' : ''}" id="userListTabPeople" href="${peopleTabUrl}"
       <c:if test="${kindFilter == 'people'}">aria-current="page"</c:if>><spring:message code="userList.tab.people"/></a>
    <a class="nav-link${kindFilter == 'agents' ? ' active' : ''}" id="userListTabAgents" href="${agentsTabUrl}"
       <c:if test="${kindFilter == 'agents'}">aria-current="page"</c:if>><spring:message code="userList.tab.agents"/></a>
</nav>

<spring:message code="userList.filter" var="filterLabel"/>
<form method="get" action="<c:url value='/roller-ui/admin/userAdmin.rol'/>"
      class="d-flex flex-wrap gap-2 mb-3" id="userListFilterForm">
    <input type="hidden" name="kind" value="${fn:escapeXml(kindFilter)}"/>
    <label for="userListFilter" class="visually-hidden">${fn:escapeXml(filterLabel)}</label>
    <input type="search" class="form-control" id="userListFilter" name="q"
           value="${fn:escapeXml(textFilter)}" placeholder="${fn:escapeXml(filterLabel)}"/>
    <button type="submit" class="btn btn-secondary" id="userListFilterSubmit">
        <spring:message code="userList.filter.apply"/>
    </button>
</form>

<c:choose>
    <c:when test="${not empty accounts}">
        <table class="rollertable table" id="userListTable">
            <tr>
                <th scope="col"><spring:message code="userList.column.account"/></th>
                <th scope="col"><spring:message code="userList.column.kind"/></th>
                <th scope="col"><spring:message code="userList.column.role"/></th>
                <th scope="col"><spring:message code="userList.column.status"/></th>
                <th scope="col"><spring:message code="userList.column.weblogs"/></th>
                <th scope="col"><spring:message code="userList.column.lastActive"/></th>
            </tr>
            <c:forEach var="row" items="${accounts}">
                <c:choose>
                    <c:when test="${row.agent}">
                        <c:url var="accountUrl" value="/roller-ui/admin/agent.rol">
                            <c:param name="userName" value="${row.userName}"/>
                        </c:url>
                    </c:when>
                    <c:otherwise>
                        <c:url var="accountUrl" value="/roller-ui/admin/modifyUser.rol">
                            <c:param name="bean.userName" value="${row.userName}"/>
                        </c:url>
                    </c:otherwise>
                </c:choose>
                <tr>
                    <td>
                        <a href="${accountUrl}" data-username="${fn:escapeXml(row.userName)}">${fn:escapeXml(row.name)}</a>
                        <br/><span class="data">${fn:escapeXml(row.userName)}</span>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.agent}"><spring:message code="userList.kind.agent"/></c:when>
                            <c:otherwise><spring:message code="userList.kind.person"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.role == 'SITE_ADMIN'}"><spring:message code="userList.role.siteAdmin"/></c:when>
                            <c:when test="${row.role == 'AUTHOR'}"><spring:message code="userList.role.author"/></c:when>
                            <c:otherwise><spring:message code="userList.role.reader"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${row.enabled}"><span class="status-pill status-enabled"><spring:message code="userList.status.enabled"/></span></c:when>
                            <c:otherwise><span class="status-pill status-disabled"><spring:message code="userList.status.disabled"/></span></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data">${row.weblogCount}</td>
                    <td class="data"><rc:date value="${row.lastActive}"/></td>
                </tr>
            </c:forEach>
        </table>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="userList.empty.title"/></p>
            <p class="empty-state-body"><spring:message code="userList.empty.body"/></p>
        </div>
    </c:otherwise>
</c:choose>

</div>
```

Delete the search box's script, now included by nothing: `git rm app/src/main/webapp/roller-ui/scripts/ajax-user.js`.

In `roller.css`, after the `.status-pill.status-unsaved` rule add:

```css
.status-pill.status-enabled { background: color-mix(in srgb, var(--good) 18%, var(--surface)); border-color: color-mix(in srgb, var(--good) 45%, var(--surface)); }
.status-pill.status-disabled { background: color-mix(in srgb, var(--bad) 12%, var(--surface)); border-color: color-mix(in srgb, var(--bad) 35%, var(--surface)); }
```

Messages. Delete the search box's keys from all 8 bundles with the "Messages (i18n)" script: `userAdmin.prompt.searchUser userAdmin.subtitle.userCreation userAdmin.prompt.orYouCan userAdmin.prompt.createANewUser`. Rename the menu entry in the base bundle only: `sed -i 's/^tabbedmenu.admin.userAdmin=User Admin$/tabbedmenu.admin.userAdmin=Users/' app/src/main/resources/ApplicationResources.properties`. Append this block to all 8 bundles with the loop:

```properties

# Agent accounts and API tokens (spec 2026-10-13): English in every bundle until translated.
userList.newPerson=New person
userList.newAgent=New agent
userList.tabs=Show accounts
userList.tab.all=All
userList.tab.people=People
userList.tab.agents=Agents
userList.filter=Username, name or email
userList.filter.apply=Filter
userList.column.account=Account
userList.column.kind=Kind
userList.column.role=Role
userList.column.status=Status
userList.column.weblogs=Weblogs
userList.column.lastActive=Last active
userList.kind.person=Person
userList.kind.agent=Agent
userList.role.reader=Reader
userList.role.author=Author
userList.role.siteAdmin=Site admin
userList.status.enabled=Enabled
userList.status.disabled=Disabled
userList.empty.title=No accounts match.
userList.empty.body=Clear the filter or pick another tab to see more accounts.
```

- [ ] **Step 5: Keep the browser suite on the new page**

In `Routes.java`, change the `userAdmin.rol` entry's marker from `"form[action$='/roller-ui/admin/userAdmin!edit.rol']"` to `"#userListPage"`.

In `UserAdminIT.java`, replace `openUserForEditing` with:

```java
    /**
     * Finds a user in Admin → Users and opens their page: filter by name,
     * then follow the row's link, the way an administrator gets there.
     */
    private void openUserForEditing(String userName) {
        openPath(USER_ADMIN + "?q=" + userName);
        clickAndAwaitNewPage($("a[data-username='" + userName + "']"));

        $("input[name='bean.userName']").should(visible).shouldHave(value(userName));
    }
```

In `SsoSignInIT.java`, replace `openUserEdit` with:

```java
    /** Finds an account in Admin → Users by name and opens its page, as an admin gets there. */
    private void openUserEdit(String userName) {
        openPath(USER_ADMIN + "?q=" + userName);
        clickAndAwaitNewPage($("a[data-username='" + userName + "']"));
        $("input[name='bean.userName']").should(visible).shouldHave(value(userName));
    }
```

Run: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests` — expected BUILD SUCCESS.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='UserAdminControllerTest,UserEditControllerTest,UserEditPasswordLinkTest,AgentAccountMessagesTest,MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest,JspConsistencyTest,AdminJspHygieneTest,RouteCoverageTest,DesignTokenTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AccountRow.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AccountRows.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserAdminController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java \
        app/src/main/webapp/WEB-INF/jsps/admin/UserAdmin.jsp \
        app/src/main/webapp/roller-ui/styles/roller.css \
        app/src/main/resources/ApplicationResources*.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserAdminControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountMessagesTest.java \
        it-selenium/src/test/java/org/apache/roller/it/support/Routes.java \
        it-selenium/src/test/java/org/apache/roller/it/UserAdminIT.java \
        it-selenium/src/test/java/org/apache/roller/it/SsoSignInIT.java
git commit -m "feat(admin): Admin -> Users lists every account with tabs, a filter, kind, role, status and last active"
```

(`git rm` already staged the deletion of `ajax-user.js`.)

---
### Task 11: Create a person: required fields, role, enabled, password choice

**Model:** sonnet — rewrites a shared create/modify form and re-baselines some twenty existing controller tests and three browser-IT helpers around it.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserNames.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/CreateUserBean.java`
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp` (rewrite)
- Modify: the 8 `app/src/main/resources/ApplicationResources*.properties`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/UserAdminIT.java`, `MultiUserJourneyIT.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditControllerTest.java`, `UserEditPasswordLinkTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java` (create)

**Interfaces:**
- Consumes: Task 10's `AccountRows.showList`.
- Produces:
  - `UserNames.problem(String userName, UserManager users)` (package-private, static) → null, or the key `error.add.user.missingUserName` / `error.add.user.badUserName` / `error.add.user.userNameInUse` / `generic.error.check.logs` (a taken-check that cannot run refuses).
  - `CreateUserBean.PASSWORD_LINK = "link"`, `PASSWORD_NOW = "now"`, `getPasswordMode()/setPasswordMode(String)`.
  - The create form: `bean.passwordMode` radios `#bean_passwordModeLink` (default) / `#bean_passwordModeNow` only when `mailConfigured`, else a hidden `now`; `bean.administrator` radios `#bean_roleAuthor` (default) / `#bean_roleAdmin`; `#bean_enabled` ticked by default.

- [ ] **Step 1: Re-baseline the existing tests and write the new ones**

In `UserEditControllerTest.java`:

1. Every create-path password literal must now be at least 8 characters: `sed -i 's/"secret"/"secret-password"/g' app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditControllerTest.java`.
2. Replace the `beanFor` helper with:

```java
    private static CreateUserBean beanFor(String userName, String password) {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        bean.setPassword(password);
        // Spec 2026-10-13, section 4: name, full name and a valid email are
        // required on the server, so a bean meant to pass carries them.
        bean.setScreenName("Screen " + userName);
        bean.setFullName("Full " + userName);
        bean.setEmailAddress("person@example.com");
        bean.setPasswordMode(CreateUserBean.PASSWORD_NOW);
        bean.setEnabled(Boolean.TRUE);
        return bean;
    }
```

3. In `createUserWithoutUserNameOrPasswordCreatesNothing`, replace the expected list `List.of("error.add.user.missingUserName", "error.add.user.missingPassword")` with:

```java
        assertEquals(List.of("error.add.user.missingUserName", "personForm.error.screenName",
                        "personForm.error.fullName", "personForm.error.email", "error.add.user.missingPassword"),
                ControllerTestFixture.errors(model));
```

4. In `aRefusedUserNameAndPasswordEachMarkTheirOwnField`, replace `List.of("bean_userName", "bean_password")` with `List.of("bean_userName", "bean_screenName", "bean_fullName", "bean_email", "bean_password")`.
5. Add:

```java
    // --- create a person (spec 2026-10-13, section 4) ---

    /** Criterion 16: required on the server too, each against its own field. */
    @Test
    void createUserRefusesAMissingNameFullNameOrEmailServerSide() throws Exception {
        CreateUserBean bean = beanFor("newbie", "long-enough");
        bean.setScreenName("  ");
        bean.setFullName(null);
        bean.setEmailAddress("");

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("personForm.error.screenName", "personForm.error.fullName", "personForm.error.email"),
                ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_screenName", "bean_fullName", "bean_email"),
                ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 16: an email the server cannot accept is refused against the email field. */
    @Test
    void createUserRefusesAnInvalidEmail() throws Exception {
        CreateUserBean bean = beanFor("newbie", "long-enough");
        bean.setEmailAddress("not-an-address");

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of("personForm.error.email"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 16: "Set a password now" needs at least 8 characters. */
    @Test
    void aPasswordShorterThanEightCharactersIsRefused() throws Exception {
        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("newbie", "seven77"));

        assertEquals(List.of("personForm.error.passwordTooShort"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_password"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 3's rule holds for a person too: a taken username is a field error on the username. */
    @Test
    void aTakenUserNameIsAFieldErrorOnTheUserName() throws Exception {
        when(weblogger.userManager().getUserByUserName("taken", null)).thenReturn(user("taken", "t@example.com"));

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("taken", "long-enough"));

        assertEquals(List.of("error.add.user.userNameInUse"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** A check that could not run is not a check that passed (CLAUDE.md): a failed taken-check refuses. */
    @Test
    void aUserNameCheckThatCannotRunRefusesTheAccount() throws Exception {
        when(weblogger.userManager().getUserByUserName("newbie", null)).thenThrow(new WebloggerException("db down"));

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("newbie", "long-enough"));

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 16: the form starts enabled as an Author, and a default save makes exactly that. */
    @Test
    void aNewPersonIsEnabledByDefaultAsAnAuthor() throws Exception {
        controller.createUserExecute(ControllerTestFixture.requestFor(null), model);
        CreateUserBean form = (CreateUserBean) model.getAttribute("bean");
        assertEquals(Boolean.TRUE, form.getEnabled());
        assertFalse(form.isAdministrator());

        controller.createUserSave(ControllerTestFixture.requestFor(null), new ExtendedModelMap(),
                beanFor("author", "long-enough"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(weblogger.userManager()).addUser(saved.capture());
        assertEquals(Boolean.TRUE, saved.getValue().getEnabled());
        verify(weblogger.userManager(), never()).grantRole(eq("admin"), any());
    }

    /** Spec 2026-10-13, section 6: the person editor's Enabled box and admin role are roller.admin events. */
    @Test
    void disablingAPersonAndGrantingAdminAreLogged() throws Exception {
        storedUser("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setEnabled(Boolean.FALSE);
        bean.setAdministrator(true);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

            assertEquals(List.of("account.disabled account=jake by=admin", "siteAdmin.granted account=jake by=admin"),
                    log.lines());
        }
    }

    @Test
    void aSaveThatChangesNeitherLogsNothing() throws Exception {
        storedUser("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setEnabled(Boolean.TRUE);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

            assertEquals(List.of(), log.lines());
        }
    }
```

(Import `org.apache.roller.testing.AdminLogCapture` in `UserEditControllerTest`.)

In `UserEditPasswordLinkTest.java`, replace its `beanFor` helper with:

```java
    private static CreateUserBean beanFor(String userName, String password) {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        bean.setPassword(password);
        bean.setScreenName("Screen " + userName);
        bean.setFullName("Full " + userName);
        bean.setEmailAddress(userName + "@example.com");
        // "Email a set-password link" is the choice these tests exercise.
        bean.setPasswordMode(CreateUserBean.PASSWORD_LINK);
        bean.setEnabled(Boolean.TRUE);
        return bean;
    }
```

in `creatingWithABlankPasswordWhileMailIsConfiguredSetsARandomPasswordAndMailsALink`, after `User user = saved.getValue();` add `assertEquals(Boolean.TRUE, user.getEnabled(), "criterion 17: the account is created enabled");`, and add:

```java
    /** Criterion 17: the emailed link is offered only while mail is configured. */
    @Test
    void theLinkIsOfferedOnlyWhenMailIsConfigured() throws Exception {
        controller.createUserExecute(ControllerTestFixture.requestFor(null), model);
        assertEquals(Boolean.TRUE, model.getAttribute("mailConfigured"));

        MockMailProvider.uninstall();
        try {
            ExtendedModelMap noMail = new ExtendedModelMap();
            controller.createUserExecute(ControllerTestFixture.requestFor(null), noMail);
            assertEquals(Boolean.FALSE, noMail.getAttribute("mailConfigured"));
        } finally {
            mail = MockMailProvider.install();
        }
    }
```

Create `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java`:

```java
package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markup the agent-accounts screens promise (spec 2026-10-13), which a
 * rendered page cannot show a unit test: which controls are offered when,
 * and which ask before they act (criterion 25).
 */
class AgentAccountsJspTest {

    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static String jsp(String relative) throws IOException {
        Path path = JSPS.resolve(relative);
        assertTrue(Files.isRegularFile(path), "missing " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    /** Criterion 17: "Email a set-password link" sits inside the mailConfigured branch. */
    @Test
    void theEmailedLinkIsOfferedOnlyWhileMailIsConfigured() throws IOException {
        String src = jsp("admin/UserEdit.jsp");
        int when = src.indexOf("<c:when test=\"${mailConfigured}\">");
        int link = src.indexOf("id=\"bean_passwordModeLink\"");
        assertTrue(when >= 0 && link > when && link < src.indexOf("</c:when>", when),
                "the link choice must render only while mail is configured");
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='UserEditControllerTest,UserEditPasswordLinkTest,AgentAccountsJspTest'`
Expected: compilation failure (`setPasswordMode`, `PASSWORD_NOW` do not exist).

- [ ] **Step 3: Implement**

Create `UserNames.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The username rule for a new person or agent (spec 2026-10-13, criterion 3),
 * in one place for both create screens: present, inside
 * {@code username.allowedChars} (letters and digits by default), and not taken.
 */
final class UserNames {

    private static final Logger log = LoggerFactory.getLogger(UserNames.class);

    private UserNames() {
    }

    /**
     * The message key refusing {@code userName} for a new account, or null
     * when it is acceptable. A taken-check that cannot run refuses it: a
     * check that could not run is not a check that passed.
     */
    static String problem(String userName, UserManager users) {
        if (StringUtils.isEmpty(userName)) {
            return "error.add.user.missingUserName";
        }
        String allowed = WebloggerConfig.getProperty("username.allowedChars");
        if (StringUtils.isBlank(allowed)) {
            allowed = UIUtils.DEFAULT_ALLOWED_CHARS;
        }
        if (!CharSetUtils.keep(userName, allowed).equals(userName)) {
            return "error.add.user.badUserName";
        }
        try {
            return users.getUserByUserName(userName, null) == null ? null : "error.add.user.userNameInUse";
        } catch (WebloggerException e) {
            log.error("Could not check whether the username {} is taken", userName, e);
            return "generic.error.check.logs";
        }
    }
}
```

In `CreateUserBean.java`, after `private boolean writer = false;` add:

```java
    /** "Email a set-password link": offered, and the default, only while mail can deliver one. */
    public static final String PASSWORD_LINK = "link";
    /** "Set a password now". */
    public static final String PASSWORD_NOW = "now";
    /** {@link #PASSWORD_LINK} or {@link #PASSWORD_NOW}; create only. */
    private String passwordMode;
```

and after `setWriter`:

```java
    public String getPasswordMode() {
        return passwordMode;
    }

    public void setPasswordMode(String passwordMode) {
        this.passwordMode = passwordMode;
    }
```

In `UserEditController.java`:
- imports: remove `org.apache.commons.lang3.CharSetUtils` and `org.apache.roller.weblogger.config.WebloggerConfig`; add `org.apache.commons.validator.routines.EmailValidator`.
- after the `log` field add `private static final int MIN_PASSWORD_LENGTH = 8;`
- in `createUserExecute`, after `bean.setTimeZone(java.util.TimeZone.getDefault().getID());` add `bean.setEnabled(Boolean.TRUE);`
- in `createUserSave`, replace

```java
            // A blank password only reaches here when myValidate() let it
            // through, which happens only when mail is ready to deliver a
            // set-password link -- the account still needs SOME password, so
            // it gets a random one nobody is ever told.
            boolean emailSetPasswordLink = StringUtils.isEmpty(bean.getPassword());
```

with

```java
            // "Email a set-password link" (spec 2026-10-13, section 4): the
            // account still needs SOME password, so it gets a random one
            // nobody is ever told.
            boolean emailSetPasswordLink = sendsSetPasswordLink(bean);
```

- in `myValidate`, replace everything from `if (isAdd) {` up to (not including) `} else {` with:

```java
        if (isAdd) {
            String userNameProblem = UserNames.problem(bean.getUserName(), weblogger.getUserManager());
            if (userNameProblem != null) {
                addFieldError(model, "bean_userName", userNameProblem, request);
            }
            // Spec 2026-10-13, section 4: required on the server too, not
            // only by the page's script.
            if (StringUtils.isBlank(bean.getScreenName())) {
                addFieldError(model, "bean_screenName", "personForm.error.screenName", request);
            }
            if (StringUtils.isBlank(bean.getFullName())) {
                addFieldError(model, "bean_fullName", "personForm.error.fullName", request);
            }
            if (!EmailValidator.getInstance().isValid(StringUtils.trimToEmpty(bean.getEmailAddress()))) {
                addFieldError(model, "bean_email", "personForm.error.email", request);
            }
            if (!sendsSetPasswordLink(bean)) {
                if (StringUtils.isEmpty(bean.getPassword())) {
                    addFieldError(model, "bean_password", "error.add.user.missingPassword", request);
                } else if (bean.getPassword().length() < MIN_PASSWORD_LENGTH) {
                    addFieldError(model, "bean_password", "personForm.error.passwordTooShort", request);
                }
            }
```

- after `myValidate` add:

```java
    /**
     * Whether a new person gets an emailed set-password link instead of a
     * password now: chosen on the form, and possible only while mail can
     * deliver one -- otherwise the account could never be handed over.
     */
    private static boolean sendsSetPasswordLink(CreateUserBean bean) {
        return CreateUserBean.PASSWORD_LINK.equals(bean.getPasswordMode()) && PasswordLinkMailer.isReady();
    }

    /** Spec 2026-10-13, section 6: an account's Enabled flag changing is a roller.admin event. */
    private static void logEnabledChange(boolean enabledBefore, User user, User actor) {
        boolean enabledNow = Boolean.TRUE.equals(user.getEnabled());
        if (enabledBefore != enabledNow) {
            AdminLog.record(enabledNow ? "account.enabled" : "account.disabled", actor,
                    "account=" + user.getUserName());
        }
    }
```

and import `org.apache.roller.weblogger.ui.controllers.AdminLog`. Then, in `modifyUserSave`:
- after the line `String screenNameBefore = user.getScreenName();` add `boolean enabledBefore = Boolean.TRUE.equals(user.getEnabled());`
- in the SSO-bound branch, after its `weblogger.flush();` (the one indented 20 spaces, followed by `expireIfRenamed(user, screenNameBefore);`), add `logEnabledChange(enabledBefore, user, authUser);`
- replace the admin-role block, from `boolean userEditingSelf = user.equals(authUser);` through the `weblogger.flush();` that follows it (indented 16 spaces), with:

```java
                boolean userEditingSelf = user.equals(authUser);
                String roleEvent = null;
                if (hasAdmin && !bean.isAdministrator()) {
                    if (!userEditingSelf) {
                        // revoke role
                        mgr.revokeRole("admin", user);
                        roleEvent = "siteAdmin.removed";
                    } else {
                        addError(model, "userAdmin.cantChangeOwnRole", request);
                    }
                } else if (!hasAdmin && bean.isAdministrator()) {
                    mgr.grantRole("admin", user);
                    roleEvent = "siteAdmin.granted";
                }
                weblogger.flush();
                logEnabledChange(enabledBefore, user, authUser);
                if (roleEvent != null) {
                    AdminLog.record(roleEvent, authUser, "account=" + user.getUserName());
                }
```

Replace `app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp` with (its existing licence header block first, then):

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>


<%-- Titling, processing actions different between add and edit --%>
<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="subtitleKey" value="userAdmin.subtitle.createNewUser"/>
    </c:when>
    <c:otherwise>
        <c:set var="subtitleKey" value="userAdmin.subtitle.editUser"/>
    </c:otherwise>
</c:choose>

<p class="subtitle">
    <spring:message code="${subtitleKey}" arguments="${fn:escapeXml(bean.userName)}"/>
</p>

<p class="pagetip">
    <c:if test="${actionName == 'createUser'}">
        <spring:message code="userAdmin.addInstructions"/>
    </c:if>
</p>

<c:if test="${ssoBound}">
    <p class="form-text" id="ssoBoundMarker"><spring:message code="userAdmin.ssoBound"/></p>
</c:if>

<c:choose>
    <c:when test="${actionName == 'createUser'}">
        <c:set var="saveAction" value="/roller-ui/admin/createUser!save.rol"/>
        <c:set var="cancelAction" value="/roller-ui/admin/createUser!cancel.rol"/>
    </c:when>
    <c:otherwise>
        <c:set var="saveAction" value="/roller-ui/admin/modifyUser!save.rol"/>
        <c:set var="cancelAction" value="/roller-ui/admin/modifyUser!cancel.rol"/>
    </c:otherwise>
</c:choose>

<form method="post" action="<c:url value='${saveAction}'/>" class="form-stacked">
    <sec:csrfInput/>
    <c:if test="${actionName == 'modifyUser'}">
        <%-- bean for add does not have a bean id yet --%>
        <input type="hidden" name="bean.id" value="${fn:escapeXml(bean.id)}" />
    </c:if>

    <c:choose>
        <c:when test="${actionName == 'modifyUser'}">
            <div class="row mb-3">
                <label class="col-sm-3 col-form-label" for="bean_userName"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9">
                    <input type="text" id="bean_userName" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" onkeyup="formChanged()"
                           readonly="readonly" class="form-control"
                           title="<spring:message code='userSettings.tip.username'/>"/>
                </div>
            </div>
        </c:when>
        <c:otherwise>
            <div class="row mb-3">
                <label class="col-sm-3 col-form-label" for="bean_userName"><spring:message code="userSettings.username"/></label>
                <div class="col-sm-9">
                    <input type="text" id="bean_userName" name="bean.userName" value="${fn:escapeXml(bean.userName)}"
                           size="30" maxlength="30" onkeyup="formChanged()" class="form-control" autofocus
                           title="<spring:message code='userAdmin.tip.userName'/>"/>
                </div>
            </div>
        </c:otherwise>
    </c:choose>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_screenName"><spring:message code="userSettings.screenname"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_screenName" name="bean.screenName"
                   <c:if test="${actionName == 'modifyUser'}">autofocus</c:if> value="${fn:escapeXml(bean.screenName)}"
                   size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.screenName'/>"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_fullName"><spring:message code="userSettings.fullname"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_fullName" name="bean.fullName" value="${fn:escapeXml(bean.fullName)}"
                   size="30" maxlength="30" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.fullName'/>"/>
        </div>
    </div>

    <c:if test="${not ssoBound}">
        <%-- Spec 2026-10-13, section 4: a new person either gets an emailed
             set-password link -- offered, and the default, only while mail can
             deliver one -- or a password set now. --%>
        <c:if test="${actionName == 'createUser'}">
            <c:choose>
                <c:when test="${mailConfigured}">
                    <fieldset class="row mb-3">
                        <legend class="col-sm-3 col-form-label"><spring:message code="personForm.password"/></legend>
                        <div class="col-sm-9">
                            <div class="form-check">
                                <input type="radio" class="form-check-input" id="bean_passwordModeLink"
                                       name="bean.passwordMode" value="link"
                                       <c:if test="${bean.passwordMode != 'now'}">checked="checked"</c:if>/>
                                <label class="form-check-label" for="bean_passwordModeLink"><spring:message code="personForm.password.link"/></label>
                            </div>
                            <div class="form-check">
                                <input type="radio" class="form-check-input" id="bean_passwordModeNow"
                                       name="bean.passwordMode" value="now"
                                       <c:if test="${bean.passwordMode == 'now'}">checked="checked"</c:if>/>
                                <label class="form-check-label" for="bean_passwordModeNow"><spring:message code="personForm.password.now"/></label>
                            </div>
                        </div>
                    </fieldset>
                </c:when>
                <c:otherwise>
                    <input type="hidden" name="bean.passwordMode" value="now"/>
                </c:otherwise>
            </c:choose>
        </c:if>
        <div class="row mb-3">
            <label class="col-sm-3 col-form-label" for="bean_password"><spring:message code="userSettings.password"/></label>
            <div class="col-sm-9">
                <input type="password" id="bean_password" name="bean.password" size="30"
                       autocomplete="new-password"
                       onkeyup="formChanged()" class="form-control"
                       title="<spring:message code='userAdmin.tip.password'/>"/>
                <c:if test="${actionName == 'createUser'}">
                    <div class="form-text"><spring:message code="personForm.password.tip"/></div>
                </c:if>
            </div>
        </div>
    </c:if>


    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_email"><spring:message code="userSettings.email"/></label>
        <div class="col-sm-9">
            <input type="text" id="bean_email" name="bean.emailAddress" value="${fn:escapeXml(bean.emailAddress)}"
                   size="30" maxlength="255" onkeyup="formChanged()" class="form-control"
                   title="<spring:message code='userAdmin.tip.email'/>"/>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_locale"><spring:message code="userSettings.locale"/></label>
        <div class="col-sm-9">
            <select id="bean_locale" name="bean.locale" class="form-select"
                    title="<spring:message code='userAdmin.tip.locale'/>">
                <c:forEach var="loc" items="${localesList}">
                    <option value="${fn:escapeXml(loc)}"
                        <c:if test="${loc == bean.locale}">selected="selected"</c:if>
                    >${fn:escapeXml(loc.displayName)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_timeZone"><spring:message code="userSettings.timeZone"/></label>
        <div class="col-sm-9">
            <select id="bean_timeZone" name="bean.timeZone" class="form-select"
                    title="<spring:message code='userAdmin.tip.timeZone'/>">
                <c:forEach var="tz" items="${timeZonesList}">
                    <option value="${fn:escapeXml(tz)}"
                        <c:if test="${tz == bean.timeZone}">selected="selected"</c:if>
                    >${fn:escapeXml(tz)}</option>
                </c:forEach>
            </select>
        </div>
    </div>

    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_enabled"><spring:message code="userAdmin.enabled"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_enabled" class="form-check-input" name="bean.enabled" value="true"
                <c:if test="${bean.enabled}">checked="checked"</c:if>
                   title="<spring:message code='userAdmin.tip.enabled'/>"/>
            <div class="form-text"><spring:message code="userAdmin.enabled.signsOutNote"/></div>
        </div>
    </div>

    <c:if test="${not ssoBound}">
    <c:choose>
    <c:when test="${actionName == 'createUser'}">
    <%-- Spec 2026-10-13, section 4: Author or Site admin, Author by default.
         Both are the editor role; Site admin adds admin. --%>
    <fieldset class="row mb-3">
        <legend class="col-sm-3 col-form-label"><spring:message code="personForm.role"/></legend>
        <div class="col-sm-9">
            <div class="form-check">
                <input type="radio" class="form-check-input" id="bean_roleAuthor" name="bean.administrator" value="false"
                    <c:if test="${not bean.administrator}">checked="checked"</c:if>/>
                <label class="form-check-label" for="bean_roleAuthor"><spring:message code="personForm.role.author"/></label>
            </div>
            <div class="form-check">
                <input type="radio" class="form-check-input" id="bean_roleAdmin" name="bean.administrator" value="true"
                    <c:if test="${bean.administrator}">checked="checked"</c:if>/>
                <label class="form-check-label" for="bean_roleAdmin"><spring:message code="personForm.role.siteAdmin"/></label>
            </div>
        </div>
    </fieldset>
    </c:when>
    <c:otherwise>
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_administrator"><spring:message code="userAdmin.userAdmin"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_administrator" class="form-check-input" name="bean.administrator" value="true"
                <c:if test="${bean.administrator}">checked="checked"</c:if>
                   title="<spring:message code='userAdmin.tip.userAdmin'/>"/>
        </div>
    </div>
    </c:otherwise>
    </c:choose>
    </c:if>
    <c:if test="${ssoBound}">
    <div class="row mb-3">
        <label class="col-sm-3 col-form-label" for="bean_writer"><spring:message code="userAdmin.canWrite"/></label>
        <div class="col-sm-9">
            <input type="checkbox" id="bean_writer" class="form-check-input" name="bean.writer" value="true"
                <c:if test="${bean.writer}">checked="checked"</c:if>/>
        </div>
    </div>
    </c:if>


    <c:if test="${actionName == 'modifyUser'}">
        <h3 class="section-head"><spring:message code="userAdmin.userWeblogs" /></h3>

        <c:if test="${not empty permissions}">
            <p><spring:message code="userAdmin.userMemberOf" />:</p>
            <%-- rollertable for the token palette and the caps-label header
                 row. That rule keys off the first <tr> of the table, so this
                 table -- which had no header row at all -- needs one, or the
                 first weblog's name would be rendered as the label. --%>
            <table class="rollertable table" style="width: 80%">
                <tr>
                    <th scope="col" width="55%"><spring:message code="generic.name"/></th>
                    <th scope="col" width="15%">&nbsp;</th>
                    <th scope="col" width="15%">&nbsp;</th>
                    <th scope="col" width="15%">&nbsp;</th>
                </tr>
                <c:forEach var="perms" items="${permissions}">
                    <tr>
                        <td width="%30">
                            <a href='${urls.weblogAbsolute(perms.weblog)}'>
                                ${fn:escapeXml(perms.weblog.name)} [${fn:escapeXml(perms.weblog.handle)}]
                            </a>
                        </td>
                        <td width="%15">
                            <c:url var="newEntry" value="/roller-ui/authoring/entryAdd.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <span class="bi bi-pencil" aria-hidden="true"></span>
                            <a href='${newEntry}'>
                            <spring:message code="userAdmin.newEntry" /></a>
                        </td>
                        <td width="%15">
                            <c:url var="editEntries" value="/roller-ui/authoring/entries.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <span class="bi bi-pencil" aria-hidden="true"></span>
                            <a href='${editEntries}'>
                            <spring:message code="userAdmin.editEntries" /></a>
                        </td>
                        <td width="%15">
                            <c:url var="manageWeblog" value="/roller-ui/authoring/weblogConfig.rol">
                                <c:param name="weblog" value="${perms.weblog.handle}" />
                            </c:url>
                            <span class="bi bi-pencil" aria-hidden="true"></span>
                            <a href='${manageWeblog}'>
                            <spring:message code="userAdmin.manage" /></a>
                        </td>
                    </tr>
                </c:forEach>
            </table>
        </c:if>
        <%-- No action button: an administrator cannot grant weblog membership
             from this form -- that happens on the weblog's own Members page,
             which is per-weblog and needs a weblog picked first. --%>
        <c:if test="${empty permissions}">
            <div class="empty-state">
                <p class="empty-state-title"><spring:message code="empty.userWeblogs.title" /></p>
                <p class="empty-state-body"><spring:message code="userAdmin.userHasNoWeblogs" /></p>
            </div>
        </c:if>
    </c:if>

    <div class="control mt-4">
        <button type="submit" class="btn btn-primary" id="save_button">
            <spring:message code="generic.save"/>
        </button>
        <a href="<c:url value='${cancelAction}'/>" class="btn btn-secondary">
            <spring:message code="generic.cancel"/>
        </a>
    </div>

</form>
```

followed, unchanged, by everything in the current file from `<c:if test="${actionName == 'modifyUser' && not empty bean.id && not ssoBound}">` (the send-password-link form) to the end of the file (its closing `</c:if>` and the `<script>` block).

Messages. Delete `userAdmin.passwordOptionalWithMail` (base bundle only) with the "Messages (i18n)" script. Append this block to all 8 bundles with the loop:

```properties
personForm.role=Role
personForm.role.author=Author
personForm.role.siteAdmin=Site admin
personForm.password=Password
personForm.password.link=Email a set-password link
personForm.password.now=Set a password now
personForm.password.tip=At least 8 characters.
personForm.error.screenName=Enter a name.
personForm.error.fullName=Enter a full name.
personForm.error.email=Enter a valid email address.
personForm.error.passwordTooShort=A password needs at least 8 characters.
```

- [ ] **Step 4: Keep the browser helpers creating enabled accounts with a password**

In `UserAdminIT.createUser`, replace

```java
        $("input[name='bean.password']").setValue(PASSWORD);
        $("input[name='bean.emailAddress']").setValue(userName + "@example.invalid");
        $("input[name='bean.enabled']").should(exist).click();
```

with

```java
        // "Set a password now" is offered beside the emailed link only while
        // mail is configured; the password field is there either way.
        if ($("#bean_passwordModeNow").exists()) {
            $("#bean_passwordModeNow").click();
        }
        $("input[name='bean.password']").setValue(PASSWORD);
        $("input[name='bean.emailAddress']").setValue(userName + "@example.invalid");
        // Enabled is ticked by default now (spec 2026-10-13, section 4).
        $("input[name='bean.enabled']").shouldBe(checked);
```

In `MultiUserJourneyIT.createUser`, make the same replacement (its lines are identical), and add `import static com.codeborne.selenide.Condition.checked;` to its imports.

Run: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests` — expected BUILD SUCCESS.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='UserEditControllerTest,UserEditPasswordLinkTest,AgentAccountsJspTest,AgentAccountMessagesTest,CreateUserBeanTest,MessageKeyTest,MessagePlaceholderContractTest,JspConsistencyTest,AdminJspHygieneTest,EditorJspEscapingTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserNames.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/CreateUserBean.java \
        app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp \
        app/src/main/resources/ApplicationResources*.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditPasswordLinkTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java \
        it-selenium/src/test/java/org/apache/roller/it/UserAdminIT.java \
        it-selenium/src/test/java/org/apache/roller/it/MultiUserJourneyIT.java
git commit -m "feat(admin): create a person -- server-side required fields, Author or Site admin, enabled, link or password"
```

---
### Task 12: The Weblogs section on an account's page

**Model:** haiku

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsModel.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/admin/UserWeblogs.jsp`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java` (three lines)
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp` (the read-only table goes; the section comes in)
- Modify: the 8 `app/src/main/resources/ApplicationResources*.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsControllerTest.java`, `UserWeblogsModelTest.java` (create)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/ControllerTestFixture.java` (two flash helpers), `UserEditControllerTest.java` (one test)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java` (the CSRF scan), `AgentAccountsJspTest.java` (three tests)

**Interfaces:**
- Consumes: Task 8's `AdminLog.record`.
- Produces:
  - POST `/roller-ui/admin/userWeblogs!grant.rol` (`userName`, `handle`, `level`), `userWeblogs!change.rol` (same), `userWeblogs!remove.rol` (`userName`, `handle`); each redirects to `modifyUser.rol?bean.userName=` for a person or `agent.rol?userName=` for an agent, with a flash message or error. `UserWeblogsController.LEVELS = [admin, post, edit_draft]`.
  - `UserWeblogsModel.populate(Weblogger, Model, User account)` (package-private, static) → `weblogsAccount`, `groupBloggingEnabled`, `availableWeblogs` (weblogs the account is not in, sorted by handle).
  - `UserWeblogs.jsp`, included by a page whose model has `permissions` (`List<WeblogPermissionView>`) plus `populate`'s attributes; ids `#userWeblogsSection`, `#userWeblogsTable`, `#userWeblogsAddForm`, `#userWeblogsAddHandle`, `#userWeblogsAddLevel`, `#userWeblogsAddButton`, `#userWeblogsGroupBloggingOff`.
  - Admin `ControllerTestFixture.flashErrors(RedirectAttributes)`, `flashMessages(RedirectAttributes)`.

- [ ] **Step 1: Write the failing tests**

In the admin `ControllerTestFixture.java`, add the import `org.springframework.web.servlet.mvc.support.RedirectAttributes` and, after `messages(Model)`:

```java
    /** Errors a controller parked in flash scope to survive a redirect. */
    @SuppressWarnings("unchecked")
    static List<String> flashErrors(RedirectAttributes redirectAttributes) {
        List<String> errors = (List<String>) redirectAttributes.getFlashAttributes().get("errors");
        return errors == null ? Collections.emptyList() : errors;
    }

    /** Messages a controller parked in flash scope to survive a redirect. */
    @SuppressWarnings("unchecked")
    static List<String> flashMessages(RedirectAttributes redirectAttributes) {
        List<String> messages = (List<String>) redirectAttributes.getFlashAttributes().get("messages");
        return messages == null ? Collections.emptyList() : messages;
    }
```

Create `UserWeblogsControllerTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Weblogs section on an account's page (spec 2026-10-13, section 4; criterion 18; review focus 4). */
class UserWeblogsControllerTest {

    private MockWeblogger weblogger;
    private UserWeblogsController controller;
    private RedirectAttributesModelMap redirect;
    private User person;
    private User agent;
    private Weblog travel;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new UserWeblogsController());
        redirect = new RedirectAttributesModelMap();
        person = account("jake", User.Kind.PERSON);
        agent = account("bot", User.Kind.AGENT);
        travel = new Weblog();
        travel.setHandle("travel");
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(person);
        when(weblogger.userManager().getUserByUserName("bot", null)).thenReturn(agent);
        when(weblogger.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        // Both accounts hold the global login action unless a test says otherwise.
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setKind(kind);
        return user;
    }

    private static HttpServletRequest asAdmin() {
        return ControllerTestFixture.requestFor(account("admin", User.Kind.PERSON));
    }

    private WeblogPermission member(User user, String level) {
        return new WeblogPermission(travel, user, List.of(level));
    }

    @Test
    void theSectionIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
    }

    /** Criterion 18: granting adds the membership, logs it, and returns to the person's page. */
    @Test
    void grantingAddsTheMembershipLogsItAndReturnsToThePersonsPage() throws Exception {
        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.grant(asAdmin(), redirect, "jake", "travel", "post");

            assertEquals("redirect:/roller-ui/admin/modifyUser.rol", view);
            assertEquals("jake", redirect.asMap().get("bean.userName"));
            verify(weblogger.userManager()).grantWeblogPermission(travel, person, List.of("post"));
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("userWeblogs.granted[travel]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("membership.granted account=jake weblog=travel level=post by=admin"), log.lines());
        }
    }

    @Test
    void anAgentReturnsToItsOwnPage() throws Exception {
        String view = controller.grant(asAdmin(), redirect, "bot", "travel", "post");

        assertEquals("redirect:/roller-ui/admin/agent.rol", view);
        assertEquals("bot", redirect.asMap().get("userName"));
    }

    /** Criterion 18: with group blogging off, granting is refused, and the message says why. */
    @Test
    void grantingWhileGroupBloggingIsOffIsRefused() throws Exception {
        when(weblogger.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));

        controller.grant(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("userWeblogs.groupBloggingOff"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    /** Criterion 18: a reader-only account cannot be granted. */
    @Test
    void aReaderOnlyAccountCannotBeGranted() throws Exception {
        when(weblogger.userManager().checkPermission(any(), eq(person))).thenReturn(false);

        controller.grant(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("inviteMember.error.readerAccount"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void anUnknownWeblogOrLevelIsRefused() throws Exception {
        controller.grant(asAdmin(), redirect, "jake", "nosuch", "post");
        assertEquals(List.of("userWeblogs.error.noSuchWeblog"), ControllerTestFixture.flashErrors(redirect));

        RedirectAttributesModelMap badLevel = new RedirectAttributesModelMap();
        controller.grant(asAdmin(), badLevel, "jake", "travel", "owner");
        assertEquals(List.of("userWeblogs.error.level"), ControllerTestFixture.flashErrors(badLevel));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void anUnknownAccountGoesBackToTheList() throws Exception {
        String view = controller.grant(asAdmin(), redirect, "nobody", "travel", "post");

        assertEquals("redirect:/roller-ui/admin/userAdmin.rol", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.flashErrors(redirect));
    }

    /** Criterion 18: changing the level replaces the actions, and is logged. */
    @Test
    void changingALevelReplacesTheActions() throws Exception {
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(member(person, "post"));

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.change(asAdmin(), redirect, "jake", "travel", "admin");

            InOrder order = inOrder(weblogger.userManager());
            order.verify(weblogger.userManager()).revokeWeblogPermission(travel, person, WeblogPermission.ALL_ACTIONS);
            order.verify(weblogger.userManager()).grantWeblogPermission(travel, person, List.of("admin"));
            assertEquals(List.of("userWeblogs.changed[travel]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("membership.changed account=jake weblog=travel level=admin by=admin"), log.lines());
        }
    }

    /** Review focus 4: a weblog keeps at least one Admin -- the last one cannot be demoted. */
    @Test
    void demotingTheLastAdminIsRefused() throws Exception {
        WeblogPermission current = member(person, "admin");
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(current);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(List.of(current, member(agent, "post")));

        controller.change(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("memberPermissions.oneAdminRequired"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    /** Review focus 4: nor removed. */
    @Test
    void removingTheLastAdminIsRefused() throws Exception {
        WeblogPermission current = member(person, "admin");
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(current);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(List.of(current));

        controller.remove(asAdmin(), redirect, "jake", "travel");

        assertEquals(List.of("memberPermissions.oneAdminRequired"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    /** Review focus 4: with another Admin left, removing one works, and is logged. */
    @Test
    void removingAnAdminWhileAnotherRemainsWorks() throws Exception {
        WeblogPermission current = member(person, "admin");
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(current);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(List.of(current, member(agent, "admin")));

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.remove(asAdmin(), redirect, "jake", "travel");

            verify(weblogger.userManager()).revokeWeblogPermission(travel, person, WeblogPermission.ALL_ACTIONS);
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("userWeblogs.removed[travel]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("membership.removed account=jake weblog=travel by=admin"), log.lines());
        }
    }

    @Test
    void changingOrRemovingANonMemberSaysSo() throws Exception {
        controller.change(asAdmin(), redirect, "jake", "travel", "post");
        assertEquals(List.of("userWeblogs.error.notMember"), ControllerTestFixture.flashErrors(redirect));

        RedirectAttributesModelMap again = new RedirectAttributesModelMap();
        controller.remove(asAdmin(), again, "jake", "travel");
        assertEquals(List.of("userWeblogs.error.notMember"), ControllerTestFixture.flashErrors(again));
    }

    @Test
    void changingToAnUnknownLevelIsRefusedBeforeAnythingIsLookedUp() throws Exception {
        controller.change(asAdmin(), redirect, "jake", "travel", "owner");

        assertEquals(List.of("userWeblogs.error.level"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    /** A failed write is reported, never swallowed into a success banner. */
    @Test
    void aStoreFailureIsReportedNotSwallowed() throws Exception {
        doThrow(new WebloggerException("db down"))
                .when(weblogger.userManager()).grantWeblogPermission(travel, person, List.of("post"));

        controller.grant(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("memberPermissions.saveError"), ControllerTestFixture.flashErrors(redirect));
        assertEquals(0, weblogger.flushCount());
    }
}
```

Create `UserWeblogsModelTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** What the Weblogs section offers (spec 2026-10-13, section 4). */
class UserWeblogsModelTest {

    private MockWeblogger tier;
    private final User bot = new User();

    @BeforeEach
    void setUp() {
        tier = MockWeblogger.attached();
        bot.setUserName("bot");
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    @Test
    void theAddControlOffersOnlyWeblogsTheAccountIsNotInSortedByHandle() throws Exception {
        Weblog travel = weblog("travel");
        Weblog food = weblog("food");
        Weblog art = weblog("art");
        when(tier.userManager().getWeblogPermissions(bot))
                .thenReturn(List.of(new WeblogPermission(travel, bot, List.of(WeblogPermission.POST))));
        when(tier.weblogManager().getWeblogs(null, null, null, null, 0, -1)).thenReturn(List.of(travel, food, art));
        ExtendedModelMap model = new ExtendedModelMap();

        UserWeblogsModel.populate(tier.weblogger(), model, bot);

        assertEquals(List.of(art, food), model.getAttribute("availableWeblogs"));
        assertEquals("bot", model.getAttribute("weblogsAccount"));
        assertEquals(Boolean.TRUE, model.getAttribute("groupBloggingEnabled"));
    }

    /** Criterion 18: the section says granting is off instead of offering the control. */
    @Test
    void groupBloggingOffIsReported() throws Exception {
        when(tier.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));
        ExtendedModelMap model = new ExtendedModelMap();

        UserWeblogsModel.populate(tier.weblogger(), model, bot);

        assertEquals(Boolean.FALSE, model.getAttribute("groupBloggingEnabled"));
    }

    /** A display path: a store that cannot list offers no weblog. */
    @Test
    void aStoreThatCannotListOffersNothing() throws Exception {
        when(tier.userManager().getWeblogPermissions(bot)).thenThrow(new WebloggerException("db down"));
        ExtendedModelMap model = new ExtendedModelMap();

        UserWeblogsModel.populate(tier.weblogger(), model, bot);

        assertEquals(List.of(), model.getAttribute("availableWeblogs"));
    }
}
```

Add to `UserEditControllerTest` (import `org.apache.roller.weblogger.pojos.Weblog`):

```java
    /** Task 12: a person's page carries the Weblogs section's model. */
    @Test
    void modifyOffersTheWeblogsTheAccountIsNotIn() throws Exception {
        storedUser("jake");
        Weblog other = new Weblog();
        other.setHandle("other");
        when(weblogger.weblogManager().getWeblogs(null, null, null, null, 0, -1)).thenReturn(List.of(other));
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of(other), model.getAttribute("availableWeblogs"));
        assertEquals("jake", model.getAttribute("weblogsAccount"));
    }
```

Add to `JspConsistencyTest`:

```java
    /**
     * Criterion 25 (spec 2026-10-13): every POST form carries the CSRF token.
     * Spring Security refuses a POST without it, so a form that lost it is a
     * page that 403s on submit -- found by a person, not a test. Forms whose
     * tag names its action before its method are not seen; new forms put
     * method="post" first.
     */
    @Test
    void everyPostFormCarriesACsrfInput() throws IOException {
        Pattern formTag = Pattern.compile("<form\\b[^>]*>");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Path jsp : jsps()) {
            String src = withoutJspComments(Files.readString(jsp, StandardCharsets.UTF_8));
            Matcher m = formTag.matcher(src);
            while (m.find()) {
                if (!m.group().toLowerCase(Locale.ROOT).matches("(?s).*method\\s*=\\s*\"post\".*")) {
                    continue;
                }
                checked++;
                int end = src.indexOf("</form>", m.end());
                String body = end < 0 ? src.substring(m.end()) : src.substring(m.end(), end);
                if (!body.contains("<sec:csrfInput/>")) {
                    missing.add(jsp + ":" + lineOf(src, m.start()));
                }
            }
        }
        assertTrue(checked > 40,
                "only " + checked + " POST forms found -- the scan is not looking where it thinks it is");
        assertTrue(missing.isEmpty(), "POST forms with no <sec:csrfInput/>:\n  " + String.join("\n  ", missing));
    }
```

Add to `AgentAccountsJspTest` (imports `java.util.regex.Matcher`, `java.util.regex.Pattern`, `static org.junit.jupiter.api.Assertions.assertFalse`):

```java
    /** The form whose action ends with {@code actionSuffix}, from its tag to its close. */
    static String formPostingTo(String src, String actionSuffix) {
        Matcher m = Pattern.compile("(?s)<form\\b[^>]*?action=\"[^\"]*" + Pattern.quote(actionSuffix)
                + "[^\"]*\".*?</form>").matcher(src);
        assertTrue(m.find(), "no form posting to " + actionSuffix);
        return m.group();
    }

    /** Criterion 25: removing a membership asks first. */
    @Test
    void removingAMembershipIsConfirmed() throws IOException {
        assertTrue(formPostingTo(jsp("admin/UserWeblogs.jsp"), "userWeblogs!remove.rol").contains("data-confirm="));
    }

    /** Criterion 18: with group blogging off the section says so instead of offering to add. */
    @Test
    void addingIsOfferedOnlyWhileGroupBloggingIsOn() throws IOException {
        String src = jsp("admin/UserWeblogs.jsp");
        int off = src.indexOf("<c:when test=\"${not groupBloggingEnabled}\">");
        assertTrue(off >= 0, "the section has a group-blogging-off branch");
        int offEnd = src.indexOf("</c:when>", off);
        assertTrue(src.substring(off, offEnd).contains("userWeblogs.groupBloggingOff"));
        assertTrue(src.indexOf("userWeblogs!grant.rol") > offEnd, "the add form sits outside that branch");
    }

    /** No field is named weblog: RollerHandlerInterceptor would read it as the action weblog. */
    @Test
    void noMembershipFieldIsNamedWeblog() throws IOException {
        assertFalse(jsp("admin/UserWeblogs.jsp").contains("name=\"weblog\""));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='UserWeblogsControllerTest,UserWeblogsModelTest,UserEditControllerTest,JspConsistencyTest,AgentAccountsJspTest'`
Expected: compilation failure (`UserWeblogsController`, `UserWeblogsModel` do not exist).

- [ ] **Step 3: Implement**

Create `UserWeblogsModel.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;

/**
 * What {@code UserWeblogs.jsp} needs beyond the {@code permissions} rows, for
 * a person's page and an agent's page alike (spec 2026-10-13, section 4).
 */
final class UserWeblogsModel {

    private static final Logger log = LoggerFactory.getLogger(UserWeblogsModel.class);

    private UserWeblogsModel() {
    }

    /**
     * The account, whether granting is possible, and the weblogs the account
     * is not in. A display path: a store that cannot list offers no weblog.
     */
    static void populate(Weblogger weblogger, Model model, User account) {
        model.addAttribute("weblogsAccount", account.getUserName());
        model.addAttribute("groupBloggingEnabled", WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"));
        model.addAttribute("availableWeblogs", available(weblogger, account));
    }

    private static List<Weblog> available(Weblogger weblogger, User account) {
        try {
            Set<String> memberOf = new HashSet<>();
            for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(account)) {
                memberOf.add(permission.getObjectId());
            }
            List<Weblog> weblogs = new ArrayList<>();
            for (Weblog weblog : weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, -1)) {
                if (!memberOf.contains(weblog.getHandle())) {
                    weblogs.add(weblog);
                }
            }
            weblogs.sort(Comparator.comparing(Weblog::getHandle));
            return weblogs;
        } catch (WebloggerException e) {
            log.error("Could not list the weblogs {} could join", account.getUserName(), e);
            return List.of();
        }
    }
}
```

Create `UserWeblogsController.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.AdminLog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Weblogs section of an account's page, person or agent (spec 2026-10-13,
 * section 4): grant, change and remove memberships by the weblog Members
 * page's own rules -- a weblog keeps at least one Admin, nothing is granted
 * while group blogging is off, and a reader-only account is never granted.
 * Each change writes a {@code roller.admin} line.
 *
 * <p>The weblog travels as {@code handle}, never {@code weblog}:
 * {@code RollerHandlerInterceptor} would read that as the action weblog.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserWeblogsController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(UserWeblogsController.class);

    /** The levels, as the Members page offers them: Admin, Author, Limited. */
    static final List<String> LEVELS =
            List.of(WeblogPermission.ADMIN, WeblogPermission.POST, WeblogPermission.EDIT_DRAFT);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "userAdmin";
    }

    @PostMapping("/userWeblogs!grant.rol")
    public String grant(HttpServletRequest request, RedirectAttributes redirectAttributes,
                        @RequestParam(name = "userName", required = false) String userName,
                        @RequestParam(name = "handle", required = false) String handle,
                        @RequestParam(name = "level", required = false) String level) {
        User account = lookupAccount(userName);
        if (account == null) {
            return accountNotFound(request, redirectAttributes);
        }
        try {
            UserManager users = weblogger.getUserManager();
            Weblog weblog = weblogFor(handle);
            if (!WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled")) {
                addFlashError(redirectAttributes, "userWeblogs.groupBloggingOff", request);
            } else if (weblog == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.noSuchWeblog", request);
            } else if (!LEVELS.contains(level)) {
                addFlashError(redirectAttributes, "userWeblogs.error.level", request);
            } else if (!users.checkPermission(new GlobalPermission(List.of(GlobalPermission.LOGIN)), account)) {
                // A reader holds no login action: a weblog permission would be
                // dead weight and a promotion path (MembersController.grant).
                addFlashError(redirectAttributes, "inviteMember.error.readerAccount", request);
            } else {
                users.grantWeblogPermission(weblog, account, List.of(level));
                weblogger.flush();
                AdminLog.record("membership.granted", getAuthenticatedUser(request),
                        details(account, weblog) + " level=" + level);
                addFlashMessage(redirectAttributes, "userWeblogs.granted",
                        StringEscapeUtils.escapeHtml4(weblog.getHandle()), request);
            }
        } catch (WebloggerException e) {
            log.error("Could not add {} to weblog {}", userName, handle, e);
            addFlashError(redirectAttributes, "memberPermissions.saveError", request);
        }
        return backTo(account, redirectAttributes);
    }

    @PostMapping("/userWeblogs!change.rol")
    public String change(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "userName", required = false) String userName,
                         @RequestParam(name = "handle", required = false) String handle,
                         @RequestParam(name = "level", required = false) String level) {
        User account = lookupAccount(userName);
        if (account == null) {
            return accountNotFound(request, redirectAttributes);
        }
        if (!LEVELS.contains(level)) {
            addFlashError(redirectAttributes, "userWeblogs.error.level", request);
            return backTo(account, redirectAttributes);
        }
        try {
            UserManager users = weblogger.getUserManager();
            Weblog weblog = weblogFor(handle);
            if (weblog == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.noSuchWeblog", request);
                return backTo(account, redirectAttributes);
            }
            WeblogPermission current = users.getWeblogPermission(weblog, account);
            if (current == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.notMember", request);
            } else if (!WeblogPermission.ADMIN.equals(level) && isLastAdmin(users, weblog, current)) {
                addFlashError(redirectAttributes, "memberPermissions.oneAdminRequired", request);
            } else {
                users.revokeWeblogPermission(weblog, account, WeblogPermission.ALL_ACTIONS);
                users.grantWeblogPermission(weblog, account, List.of(level));
                weblogger.flush();
                AdminLog.record("membership.changed", getAuthenticatedUser(request),
                        details(account, weblog) + " level=" + level);
                addFlashMessage(redirectAttributes, "userWeblogs.changed",
                        StringEscapeUtils.escapeHtml4(weblog.getHandle()), request);
            }
        } catch (WebloggerException e) {
            log.error("Could not change {} on weblog {}", userName, handle, e);
            addFlashError(redirectAttributes, "memberPermissions.saveError", request);
        }
        return backTo(account, redirectAttributes);
    }

    @PostMapping("/userWeblogs!remove.rol")
    public String remove(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "userName", required = false) String userName,
                         @RequestParam(name = "handle", required = false) String handle) {
        User account = lookupAccount(userName);
        if (account == null) {
            return accountNotFound(request, redirectAttributes);
        }
        try {
            UserManager users = weblogger.getUserManager();
            Weblog weblog = weblogFor(handle);
            if (weblog == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.noSuchWeblog", request);
                return backTo(account, redirectAttributes);
            }
            WeblogPermission current = users.getWeblogPermission(weblog, account);
            if (current == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.notMember", request);
            } else if (isLastAdmin(users, weblog, current)) {
                addFlashError(redirectAttributes, "memberPermissions.oneAdminRequired", request);
            } else {
                users.revokeWeblogPermission(weblog, account, WeblogPermission.ALL_ACTIONS);
                weblogger.flush();
                AdminLog.record("membership.removed", getAuthenticatedUser(request), details(account, weblog));
                addFlashMessage(redirectAttributes, "userWeblogs.removed",
                        StringEscapeUtils.escapeHtml4(weblog.getHandle()), request);
            }
        } catch (WebloggerException e) {
            log.error("Could not remove {} from weblog {}", userName, handle, e);
            addFlashError(redirectAttributes, "memberPermissions.saveError", request);
        }
        return backTo(account, redirectAttributes);
    }

    /** Whether {@code current} is the weblog's only Admin membership -- which a weblog must keep. */
    private static boolean isLastAdmin(UserManager users, Weblog weblog, WeblogPermission current)
            throws WebloggerException {
        if (!current.hasAction(WeblogPermission.ADMIN)) {
            return false;
        }
        int admins = 0;
        for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
            if (permission.hasAction(WeblogPermission.ADMIN)) {
                admins++;
            }
        }
        return admins <= 1;
    }

    private static String details(User account, Weblog weblog) {
        return "account=" + account.getUserName() + " weblog=" + weblog.getHandle();
    }

    private Weblog weblogFor(String handle) throws WebloggerException {
        return StringUtils.isBlank(handle) ? null : weblogger.getWeblogManager().getWeblogByHandle(handle, null);
    }

    /** A failed lookup reads as no account, and nothing proceeds. */
    private User lookupAccount(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        try {
            return weblogger.getUserManager().getUserByUserName(userName, null);
        } catch (WebloggerException e) {
            log.error("Could not look up the account {}", userName, e);
            return null;
        }
    }

    private String accountNotFound(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        addFlashError(redirectAttributes, "userAdmin.error.userNotFound", request);
        return "redirect:/roller-ui/admin/userAdmin.rol";
    }

    /** Back to the account's own page: an agent's page, or the person editor. */
    private static String backTo(User account, RedirectAttributes redirectAttributes) {
        if (account.isAgent()) {
            redirectAttributes.addAttribute("userName", account.getUserName());
            return "redirect:/roller-ui/admin/agent.rol";
        }
        redirectAttributes.addAttribute("bean.userName", account.getUserName());
        return "redirect:/roller-ui/admin/modifyUser.rol";
    }
}
```

Create `app/src/main/webapp/WEB-INF/jsps/admin/UserWeblogs.jsp` (licence header block first, then):

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- The Weblogs section of an account's page, person or agent (spec
     2026-10-13, section 4; docs/design/forms/forms-agent-page.html), included
     with jsp:include. The page's model carries permissions (the rows) and
     UserWeblogsModel's weblogsAccount, groupBloggingEnabled and
     availableWeblogs. Levels and labels are the Members page's own. The
     weblog field is "handle", never "weblog". Remove asks first. --%>
<section id="userWeblogsSection" class="mt-4">
<h3 class="section-head"><spring:message code="userAdmin.userWeblogs"/></h3>

<c:choose>
    <c:when test="${not empty permissions}">
        <p><spring:message code="userAdmin.userMemberOf"/>:</p>
        <table class="rollertable table" id="userWeblogsTable">
            <tr>
                <th scope="col"><spring:message code="generic.name"/></th>
                <th scope="col"><spring:message code="userWeblogs.level"/></th>
                <th scope="col"><span class="visually-hidden"><spring:message code="userWeblogs.remove"/></span></th>
            </tr>
            <c:forEach var="perms" items="${permissions}">
                <c:choose>
                    <c:when test="${perms.hasAction('admin')}"><c:set var="currentLevel" value="admin"/></c:when>
                    <c:when test="${perms.hasAction('post')}"><c:set var="currentLevel" value="post"/></c:when>
                    <c:otherwise><c:set var="currentLevel" value="edit_draft"/></c:otherwise>
                </c:choose>
                <tr>
                    <td>
                        <a href="${urls.weblogAbsolute(perms.weblog)}">${fn:escapeXml(perms.weblog.name)}</a>
                        <span class="data">${fn:escapeXml(perms.weblog.handle)}</span>
                    </td>
                    <td>
                        <form method="post" action="<c:url value='/roller-ui/admin/userWeblogs!change.rol'/>"
                              class="d-flex flex-wrap gap-2 align-items-center">
                            <sec:csrfInput/>
                            <input type="hidden" name="userName" value="${fn:escapeXml(weblogsAccount)}"/>
                            <input type="hidden" name="handle" value="${fn:escapeXml(perms.weblog.handle)}"/>
                            <label class="visually-hidden" for="level-${fn:escapeXml(perms.weblog.handle)}"><spring:message code="userWeblogs.level"/></label>
                            <select class="form-select form-select-sm" name="level" id="level-${fn:escapeXml(perms.weblog.handle)}">
                                <option value="admin" <c:if test="${currentLevel == 'admin'}">selected="selected"</c:if>><spring:message code="memberPermissions.administrator"/></option>
                                <option value="post" <c:if test="${currentLevel == 'post'}">selected="selected"</c:if>><spring:message code="memberPermissions.author"/></option>
                                <option value="edit_draft" <c:if test="${currentLevel == 'edit_draft'}">selected="selected"</c:if>><spring:message code="memberPermissions.limited"/></option>
                            </select>
                            <button type="submit" class="btn btn-secondary btn-sm"><spring:message code="userWeblogs.change"/></button>
                        </form>
                    </td>
                    <td>
                        <form method="post" action="<c:url value='/roller-ui/admin/userWeblogs!remove.rol'/>">
                            <sec:csrfInput/>
                            <input type="hidden" name="userName" value="${fn:escapeXml(weblogsAccount)}"/>
                            <input type="hidden" name="handle" value="${fn:escapeXml(perms.weblog.handle)}"/>
                            <spring:message code="userWeblogs.remove.confirm"
                                            arguments="${fn:escapeXml(perms.weblog.handle)}" var="removeConfirm"/>
                            <button type="submit" class="btn btn-secondary btn-sm" data-confirm="${removeConfirm}">
                                <spring:message code="userWeblogs.remove"/>
                            </button>
                        </form>
                    </td>
                </tr>
            </c:forEach>
        </table>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="empty.userWeblogs.title"/></p>
            <p class="empty-state-body"><spring:message code="userAdmin.userHasNoWeblogs"/></p>
        </div>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${not groupBloggingEnabled}">
        <p class="form-text" id="userWeblogsGroupBloggingOff"><spring:message code="userWeblogs.groupBloggingOff"/></p>
    </c:when>
    <c:when test="${empty availableWeblogs}">
        <p class="form-text"><spring:message code="userWeblogs.noOtherWeblogs"/></p>
    </c:when>
    <c:otherwise>
        <form method="post" action="<c:url value='/roller-ui/admin/userWeblogs!grant.rol'/>"
              class="d-flex flex-wrap align-items-end gap-2 mt-3" id="userWeblogsAddForm">
            <sec:csrfInput/>
            <input type="hidden" name="userName" value="${fn:escapeXml(weblogsAccount)}"/>
            <div>
                <label for="userWeblogsAddHandle" class="form-label"><spring:message code="userWeblogs.weblog"/></label>
                <select class="form-select" name="handle" id="userWeblogsAddHandle">
                    <c:forEach var="choice" items="${availableWeblogs}">
                        <option value="${fn:escapeXml(choice.handle)}">${fn:escapeXml(choice.name)} (${fn:escapeXml(choice.handle)})</option>
                    </c:forEach>
                </select>
            </div>
            <div>
                <label for="userWeblogsAddLevel" class="form-label"><spring:message code="userWeblogs.level"/></label>
                <select class="form-select" name="level" id="userWeblogsAddLevel">
                    <option value="post" selected="selected"><spring:message code="memberPermissions.author"/></option>
                    <option value="admin"><spring:message code="memberPermissions.administrator"/></option>
                    <option value="edit_draft"><spring:message code="memberPermissions.limited"/></option>
                </select>
            </div>
            <button type="submit" class="btn btn-secondary" id="userWeblogsAddButton"><spring:message code="userWeblogs.add"/></button>
        </form>
    </c:otherwise>
</c:choose>
</section>
```

In `UserEditController.java`, after each of the three lines `model.addAttribute("permissions", getPermissions(user));` (in `modifyUserExecute`, `modifyUserSave` and `sendPasswordLink`) add, at the same indentation:

```java
        UserWeblogsModel.populate(weblogger, model, user);
```

In `UserEdit.jsp`:
1. Delete the whole block that starts with the line `    <c:if test="${actionName == 'modifyUser'}">` immediately followed by `        <h3 class="section-head"><spring:message code="userAdmin.userWeblogs" /></h3>`, through its closing `    </c:if>` just before `    <div class="control mt-4">` -- the read-only table and its empty state, which this section replaces.
2. Immediately before the line `<script>` near the end of the file, insert:

```jsp
<c:if test="${actionName == 'modifyUser' && not empty bean.id}">
    <%-- Outside the save form: the section posts forms of its own. --%>
    <jsp:include page="/WEB-INF/jsps/admin/UserWeblogs.jsp"/>
</c:if>

```

Messages. Delete `userAdmin.newEntry userAdmin.editEntries userAdmin.manage` from all 8 bundles with the "Messages (i18n)" script. Append this block to all 8 bundles with the loop:

```properties
userWeblogs.level=Level
userWeblogs.weblog=Weblog
userWeblogs.add=Add to weblog
userWeblogs.change=Change
userWeblogs.remove=Remove
userWeblogs.remove.confirm=Remove this account from {0}?
userWeblogs.granted=Added to {0}.
userWeblogs.changed=Changed the level on {0}.
userWeblogs.removed=Removed from {0}.
userWeblogs.groupBloggingOff=Group blogging is turned off, so an account cannot be added to a weblog here.
userWeblogs.noOtherWeblogs=This account is already a member of every weblog.
userWeblogs.error.noSuchWeblog=No such weblog.
userWeblogs.error.notMember=This account is not a member of that weblog.
userWeblogs.error.level=Choose Admin, Author or Limited.
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='UserWeblogsControllerTest,UserWeblogsModelTest,UserEditControllerTest,UserEditPasswordLinkTest,JspConsistencyTest,AdminJspHygieneTest,AgentAccountsJspTest,AgentAccountMessagesTest,MessageKeyTest,MessagePlaceholderContractTest,MessageArgumentEscapingTest,ReaderRefusalSweepTest,ControllerMetadataTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsModel.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java \
        app/src/main/webapp/WEB-INF/jsps/admin/UserWeblogs.jsp \
        app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp \
        app/src/main/resources/ApplicationResources*.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserWeblogsModelTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/ControllerTestFixture.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java
git commit -m "feat(admin): an account's page grants, changes and removes weblog memberships"
```

---

### Task 13: Token form, one-time reveal, and Profile → API tokens

**Model:** sonnet — introduces the shared abstract controller three pages build on, four JSPs that include one another, a view pair, security-pattern pins and two route markers.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenFormValues.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenFormController.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ProfileTokensController.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/core/TokenForm.jsp`, `core/TokenList.jsp`, `core/ProfileTokens.jsp`, `core/TokenReveal.jsp`
- Modify: `app/src/main/webapp/WEB-INF/jsps/core/Profile.jsp` (a link)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java` (two views)
- Modify: the 8 `app/src/main/resources/ApplicationResources*.properties`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java` (two routes)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/ProfileTokensControllerTest.java` (create)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java`, `app/src/test/java/org/apache/roller/weblogger/boot/SecurityConfigTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java`

**Interfaces:**
- Consumes: Task 2's `issueToken(…, User issuedBy)`, `getToken`, `revoke(ApiToken)`, `getLiveTokens`; Task 8's `TokenPins`, `AdminLog`.
- Produces:
  - `TokenFormValues(String label, String role, String pin, String expiry)`, `TokenFormValues()` (Write, all weblogs, 90 days); getters; `DEFAULT_ROLE = "POST"`, `DEFAULT_EXPIRY = "90"`, `NEVER = "never"`.
  - `abstract class TokenFormController extends BaseController`: constants `REVEALED_TOKEN`, `REVEALED_LABEL`, `REVEALED_FOR_AGENT`, `REVEAL_PATH = "/roller-ui/profileTokens!reveal.rol"`, `MAX_LABEL_LENGTH = 255`; `protected void addTokenSection(Model, User owner, TokenFormValues, String createAction, String revokeAction, String ownerParam)`; `protected ApiTokenManager.Issued mintToken(User owner, User actor, TokenFormValues, Model, HttpServletRequest)` (null when refused; field ids `tokenLabel`, `tokenRole`, `tokenPin`, `tokenExpiry`); `protected String revealRedirect(ApiTokenManager.Issued, String forAgent, RedirectAttributes)`; `protected String revealPage(HttpServletResponse, Model)`; `protected boolean revokeToken(User actor, String tokenId, User requiredOwner)`.
  - Routes: GET `/roller-ui/profileTokens.rol` (`#profileTokensPage`), POST `profileTokens!create.rol` (`label`, `role`, `pin`, `expiry`), POST `profileTokens!revoke.rol` (`id`), GET `profileTokens!reveal.rol` (`#tokenRevealPage`; `#revealedToken`, `#mcpSnippet`, `#tokenAlreadyShown`, `#tokenRevealDone`).
  - JSP includes `core/TokenForm.jsp` (`#tokenForm`, `#tokenCreateButton`) and `core/TokenList.jsp` (`#tokenListTable`), driven by the model attributes `tokens`, `pinChoices`, `tokenFormValues`, `tokenFormAction`, `tokenRevokeAction`, `tokenFormOwner`.

- [ ] **Step 1: Write the failing tests**

Create `ProfileTokensControllerTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers.core;

import java.sql.Timestamp;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.TokenFormController;
import org.apache.roller.weblogger.ui.controllers.TokenFormValues;
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Profile → API tokens (spec 2026-10-13, section 3; criteria 9, 10, 14, 15):
 * the token form, the one-time reveal and revocation, as a person sees them.
 */
class ProfileTokensControllerTest {

    private MockWeblogger mocks;
    private ProfileTokensController controller;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private User me;
    private Weblog travel;
    private Weblog food;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(mocks.weblogger());
        controller = ControllerTestFixture.withMessages(new ProfileTokensController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        me = new User();
        me.setUserName("jake");
        travel = weblog("travel");
        food = weblog("food");
        when(mocks.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        when(mocks.weblogManager().getWeblogByHandle("food", null)).thenReturn(food);
        WeblogPermission mine = new WeblogPermission(travel, me, List.of(WeblogPermission.POST));
        when(mocks.userManager().getWeblogPermission(travel, me)).thenReturn(mine);
        when(mocks.userManager().getWeblogPermissions(me)).thenReturn(List.of(mine));
        when(mocks.propertiesManager().getProperty("site.absoluteurl"))
                .thenReturn(new RuntimeConfigProperty("site.absoluteurl", "https://blog.example/"));
    }

    @AfterEach
    void tearDown() {
        mocks.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
        return weblog;
    }

    private HttpServletRequest request() {
        return ControllerTestFixture.requestFor(me);
    }

    private void siteAdmin(boolean admin) throws WebloggerException {
        when(mocks.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(me)))
                .thenReturn(admin);
    }

    private ApiTokenManager.Issued issued(String raw, String label) {
        ApiToken token = new ApiToken();
        token.setId("t1");
        token.setUser(me);
        token.setLabel(label);
        token.setScopeRole(ApiToken.Role.POST);
        return new ApiTokenManager.Issued(raw, token);
    }

    private void mintsAs(String raw, String label) throws WebloggerException {
        when(mocks.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(issued(raw, label));
    }

    @Test
    void thePageNeedsASignedInAuthorAndNoWeblog() {
        assertFalse(controller.isWeblogRequired());
        assertTrue(controller.isUserRequired());
        assertEquals(List.of(GlobalPermission.LOGIN), controller.requiredGlobalPermissionActions());
    }

    /** Criterion 15: my live tokens, and pin choices limited to my own weblogs. */
    @Test
    void thePageListsMyLiveTokensAndOffersOnlyMyWeblogs() throws Exception {
        ApiToken mine = issued("rlr_x", "ci").token();
        when(mocks.apiTokenManager().getLiveTokens(me)).thenReturn(List.of(mine));

        String view = controller.list(request(), model);

        assertEquals(".ProfileTokens", view);
        assertEquals(List.of(mine), model.getAttribute("tokens"));
        assertEquals(List.of(travel), model.getAttribute("pinChoices"));
        assertEquals("/roller-ui/profileTokens!create.rol", model.getAttribute("tokenFormAction"));
        assertEquals("/roller-ui/profileTokens!revoke.rol", model.getAttribute("tokenRevokeAction"));
        assertNull(model.getAttribute("tokenFormOwner"));
        TokenFormValues values = (TokenFormValues) model.getAttribute("tokenFormValues");
        assertEquals("POST", values.getRole());
        assertEquals("90", values.getExpiry());
    }

    /** Criteria 9 and 15: the mint stores the choices and the issuer, then goes to the reveal. */
    @Test
    void mintingStoresTheChoicesRecordsTheIssuerAndRedirectsToTheReveal() throws Exception {
        mintsAs("rlr_secret", "ci");
        long before = System.currentTimeMillis();

        String view = controller.create(request(), model, redirect, "  ci ", "POST", "travel", null);

        assertEquals("redirect:/roller-ui/profileTokens!reveal.rol", view);
        ArgumentCaptor<Timestamp> expires = ArgumentCaptor.forClass(Timestamp.class);
        verify(mocks.apiTokenManager()).issueToken(eq(me), eq("ci"), eq("travel"), eq(ApiToken.Role.POST),
                expires.capture(), eq(me));
        long ninetyDays = 90L * 24 * 60 * 60 * 1000;
        long expiry = expires.getValue().getTime();
        assertTrue(expiry >= before + ninetyDays - 60_000L
                && expiry <= System.currentTimeMillis() + ninetyDays + 60_000L, "the default expiry is 90 days");
        assertEquals(1, mocks.flushCount());
        assertEquals("rlr_secret", redirect.getFlashAttributes().get(TokenFormController.REVEALED_TOKEN));
        assertEquals("ci", redirect.getFlashAttributes().get(TokenFormController.REVEALED_LABEL));
        assertTrue(redirect.asMap().isEmpty(), "nothing rides in the redirect's query: the secret never reaches a URL");
    }

    /** Criteria 11 and 15; review focus 5: a pin outside my weblogs is a field error and nothing is minted. */
    @Test
    void aPinOutsideMyWeblogsIsAFieldError() throws Exception {
        siteAdmin(false);

        String view = controller.create(request(), model, redirect, "ci", "POST", "food", "30");

        assertEquals(".ProfileTokens", view);
        assertEquals(List.of("apiTokens.error.pin"), ControllerTestFixture.errors(model));
        assertEquals(List.of("tokenPin"), ControllerTestFixture.invalidFields(model));
        verify(mocks.apiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any(), any());
        assertEquals("food", ((TokenFormValues) model.getAttribute("tokenFormValues")).getPin(),
                "the form comes back as it was sent");
    }

    /** Criterion 15; review focus 5: a site admin may pin any existing weblog. */
    @Test
    void aSiteAdminMayPinAnyExistingWeblog() throws Exception {
        siteAdmin(true);
        mintsAs("rlr_ops", "ops");

        assertEquals("redirect:/roller-ui/profileTokens!reveal.rol",
                controller.create(request(), model, redirect, "ops", "ADMIN", "food", "365"));
    }

    /** Review focus 5: a pin check that cannot run refuses the pin. */
    @Test
    void aPinCheckThatCannotRunRefuses() throws Exception {
        when(mocks.weblogManager().getWeblogByHandle("travel", null)).thenThrow(new WebloggerException("db down"));

        controller.create(request(), model, redirect, "ci", "POST", "travel", "90");

        assertEquals(List.of("apiTokens.error.pin"), ControllerTestFixture.errors(model));
        verify(mocks.apiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aBlankLabelAnUnknownRoleAndAnUnknownExpiryAreFieldErrors() throws Exception {
        controller.create(request(), model, redirect, "   ", "ROOT", null, "7");

        assertEquals(List.of("apiTokens.error.labelRequired", "apiTokens.error.role", "apiTokens.error.expiry"),
                ControllerTestFixture.errors(model));
        assertEquals(List.of("tokenLabel", "tokenRole", "tokenExpiry"), ControllerTestFixture.invalidFields(model));
    }

    @Test
    void aLabelLongerThanTheColumnIsAFieldError() throws Exception {
        controller.create(request(), model, redirect, "x".repeat(256), "POST", null, "90");

        assertEquals(List.of("apiTokens.error.labelTooLong"), ControllerTestFixture.errors(model));
    }

    @Test
    void neverMeansNoExpiryAndABlankPinMeansAllItsWeblogs() throws Exception {
        mintsAs("rlr_n", "forever");

        controller.create(request(), model, redirect, "forever", "READ", "", "never");

        verify(mocks.apiTokenManager()).issueToken(eq(me), eq("forever"), isNull(), eq(ApiToken.Role.READ),
                isNull(), eq(me));
    }

    /** Criterion 9: the reveal shows the secret once, is never cached, and carries an MCP snippet. */
    @Test
    void theRevealShowsTheSecretOnceWithNoStoreAndAnMcpSnippet() {
        model.addAttribute(TokenFormController.REVEALED_TOKEN, "rlr_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.reveal(request(), response, model);

        assertEquals(".TokenReveal", view);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("https://blog.example/api/mcp", model.getAttribute("mcpUrl"));
        String snippet = (String) model.getAttribute("mcpSnippet");
        assertTrue(snippet.contains("https://blog.example/api/mcp"), snippet);
        assertTrue(snippet.contains("Authorization: Bearer rlr_secret"), snippet);
    }

    /** Criterion 10: loading the reveal again has no secret to show, and is still not cached. */
    @Test
    void aSecondVisitHasNoSecretToShow() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.reveal(request(), response, model);

        assertNull(model.getAttribute(TokenFormController.REVEALED_TOKEN));
        assertNull(model.getAttribute("mcpSnippet"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /** Criterion 10; review focus 1: no log line a mint writes carries the secret, on any logger, at any level. */
    @Test
    void noLogLineWrittenByAMintContainsTheSecret() throws Exception {
        mintsAs("rlr_neverLogged", "ci");

        try (SsoLogCapture everything = SsoLogCapture.everyLogger();
             AdminLogCapture admin = AdminLogCapture.start()) {
            controller.create(request(), model, redirect, "ci", "POST", null, null);

            assertEquals(1, admin.lines().size(), admin.lines().toString());
            String line = admin.lines().get(0);
            assertTrue(line.startsWith("token.minted id=t1 label=ci owner=jake"), line);
            assertTrue(line.endsWith("by=jake"), line);
            assertFalse(line.contains("rlr_neverLogged"), line);
            assertTrue(everything.everything().stream().noneMatch(l -> l.contains("rlr_neverLogged")),
                    everything.everything().toString());
        }
    }

    /** Criterion 14: I can revoke my own token, and revoking it again succeeds and changes nothing. */
    @Test
    void revokingMyOwnTokenWorksAndTwiceIsHarmless() throws Exception {
        ApiToken mine = issued("rlr_a", "ci").token();
        when(mocks.apiTokenManager().getToken("t1")).thenReturn(mine);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            assertEquals("redirect:/roller-ui/profileTokens.rol", controller.revoke(request(), redirect, "t1"));
            assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(redirect));

            mine.setRevokedAt(new Timestamp(System.currentTimeMillis()));
            RedirectAttributesModelMap again = new RedirectAttributesModelMap();
            controller.revoke(request(), again, "t1");
            assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(again));

            verify(mocks.apiTokenManager(), times(2)).revoke(mine);
            assertEquals(1, admin.lines().size(), "one revocation, one line");
        }
    }

    /** Criterion 14: another person's token id answers "no such token", and nothing is revoked. */
    @Test
    void anotherPersonsTokenIsNoSuchToken() throws Exception {
        User someoneElse = new User();
        someoneElse.setUserName("other");
        ApiToken theirs = issued("rlr_b", "theirs").token();
        theirs.setUser(someoneElse);
        when(mocks.apiTokenManager().getToken("t2")).thenReturn(theirs);

        controller.revoke(request(), redirect, "t2");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        verify(mocks.apiTokenManager(), never()).revoke(any(ApiToken.class));
    }

    @Test
    void anUnknownTokenIsNoSuchToken() {
        controller.revoke(request(), redirect, "nosuch");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
    }

    /** A store that fails mid-mint is a visible error and the form comes back; nothing is revealed. */
    @Test
    void aStoreFailureWhileMintingIsAnErrorNotAReveal() throws Exception {
        when(mocks.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new WebloggerException("db down"));

        String view = controller.create(request(), model, redirect, "ci", "POST", null, null);

        assertEquals(".ProfileTokens", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertTrue(redirect.getFlashAttributes().isEmpty());
    }

    /** A store that fails mid-revoke answers "no such token" rather than claiming success. */
    @Test
    void aStoreFailureWhileRevokingIsNotASuccess() throws Exception {
        when(mocks.apiTokenManager().getToken("t1")).thenThrow(new WebloggerException("db down"));

        controller.revoke(request(), redirect, "t1");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        assertTrue(ControllerTestFixture.flashMessages(redirect).isEmpty());
    }
}
```

In `RollerViewResolverTest`, change `case ".Profile" -> ` to `case ".Profile", ".ProfileTokens", ".TokenReveal" -> ` in `expectedLayout`, and add:

```java
    /** Profile → API tokens and its reveal are signed-in, un-scoped screens, like Profile itself. */
    @Test
    void theTokenScreensRenderThroughTheMainMenuLayout() throws Exception {
        resolver.init();

        assertEquals("/WEB-INF/jsps/core/ProfileTokens.jsp",
                renderAndCapture(".ProfileTokens").getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/core/TokenReveal.jsp",
                renderAndCapture(".TokenReveal").getAttribute("tile_content"));
    }
```

In `SecurityConfigTest`: add `"/roller-ui/profileTokens.rol"` and `"/roller-ui/profileTokens!reveal.rol"` to the `StubController`'s `@GetMapping` list, to the `@ValueSource` of `anonymousIsRedirectedToLoginOnEveryProtectedPattern`, and to the `@ValueSource` of `editorAuthorityIsAllowedOnEditorSurfaces`; then add:

```java
    /** A reader (SSO) holds neither editor nor admin, so the token pages -- the reveal included -- refuse them. */
    @Test
    void aReaderCannotReachTheTokenPages() throws Exception {
        for (String path : new String[] {"/roller-ui/profileTokens.rol", "/roller-ui/profileTokens!reveal.rol"}) {
            mockMvc.perform(get(path).with(user("readerUser").authorities(new SimpleGrantedAuthority("reader"))))
                    .andExpect(status().isForbidden());
        }
    }
```

In `JspConsistencyTest`, replace the `FIELD_ERROR_JSPS` initialiser (a `Map.of` that is already at its ten-pair limit) with:

```java
    private static final Map<String, List<String>> FIELD_ERROR_JSPS = Map.ofEntries(
            Map.entry("editor/WeblogConfigController.java", List.of("editor/WeblogConfig.jsp")),
            Map.entry("editor/CategoryEditController.java", List.of("editor/Categories.jsp")),
            Map.entry("editor/PageEditController.java", List.of("editor/PageEdit.jsp")),
            Map.entry("editor/EntryEditController.java", List.of("editor/EntryEdit.jsp")),
            Map.entry("editor/TemplatesController.java", List.of("editor/TemplatesSidebar.jsp")),
            Map.entry("editor/MembersController.java", List.of("editor/Members.jsp")),
            Map.entry("core/CreateWeblogController.java", List.of("core/CreateWeblog.jsp")),
            Map.entry("core/ProfileController.java", List.of("core/Profile.jsp")),
            Map.entry("admin/UserEditController.java", List.of("admin/UserEdit.jsp")),
            Map.entry("admin/BusinessesController.java", List.of("admin/BusinessEdit.jsp")),
            // The token form's controls, shared by the agent page and Profile -> API tokens.
            Map.entry("controllers/TokenFormController.java", List.of("core/TokenForm.jsp")));
```

Add to `AgentAccountsJspTest`:

```java
    /** Criterion 25: revoking a token asks first. */
    @Test
    void revokingATokenIsConfirmed() throws IOException {
        assertTrue(formPostingTo(jsp("core/TokenList.jsp"), "tokenRevokeAction").contains("data-confirm="));
    }

    /** Review focus 1: on the reveal page the secret is a form value, never part of a link. */
    @Test
    void theSecretIsNeverPartOfALink() throws IOException {
        String src = jsp("core/TokenReveal.jsp");
        assertTrue(src.contains("value=\"${fn:escapeXml(revealedToken)}\""));
        Matcher hrefs = Pattern.compile("href=\"([^\"]*)\"").matcher(src);
        while (hrefs.find()) {
            assertFalse(hrefs.group(1).contains("revealedToken") || hrefs.group(1).contains("mcpSnippet"),
                    hrefs.group());
        }
    }

    /** The pin field is "pin", never "weblog": RollerHandlerInterceptor would read that as the action weblog. */
    @Test
    void theTokenPinFieldIsNotNamedWeblog() throws IOException {
        String src = jsp("core/TokenForm.jsp");
        assertFalse(src.contains("name=\"weblog\""));
        assertTrue(src.contains("name=\"pin\""));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='ProfileTokensControllerTest,RollerViewResolverTest,SecurityConfigTest,JspConsistencyTest,AgentAccountsJspTest'`
Expected: compilation failure (`ProfileTokensController`, `TokenFormController`, `TokenFormValues` do not exist).

- [ ] **Step 3: Implement the controllers**

Create `TokenFormValues.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

/**
 * The token form's values as submitted (spec 2026-10-13, section 3), kept so
 * a refused form comes back as it was sent.
 */
public final class TokenFormValues {

    /** Write: a token for publishing. */
    public static final String DEFAULT_ROLE = "POST";

    /** 90 days, the spec's default. */
    public static final String DEFAULT_EXPIRY = "90";

    /** The expiry choice for a token that never expires on its own. */
    public static final String NEVER = "never";

    private final String label;
    private final String role;
    private final String pin;
    private final String expiry;

    /** A fresh form: Write, all its weblogs, 90 days. */
    public TokenFormValues() {
        this(null, DEFAULT_ROLE, null, DEFAULT_EXPIRY);
    }

    public TokenFormValues(String label, String role, String pin, String expiry) {
        this.label = label;
        this.role = role;
        this.pin = pin;
        this.expiry = expiry;
    }

    public String getLabel() {
        return label;
    }

    /** {@code READ}, {@code POST} or {@code ADMIN}, as submitted. */
    public String getRole() {
        return role;
    }

    /** A weblog handle, or blank for all its weblogs. */
    public String getPin() {
        return pin;
    }

    /** {@code 30}, {@code 90}, {@code 365} or {@code never}, as submitted. */
    public String getExpiry() {
        return expiry;
    }
}
```

Create `TokenFormController.java`:

```java
package org.apache.roller.weblogger.ui.controllers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The token form, its one-time reveal and revocation (spec 2026-10-13,
 * section 3), shared by Profile → API tokens, an agent's page and
 * Admin → API tokens so the three cannot drift.
 *
 * <p>The secret travels from the mint to the reveal page only as a one-read
 * flash attribute: never in a URL, and never in a log line -- the mint's
 * {@code roller.admin} line names the token by {@link AdminLog#tokenDetails}.
 */
public abstract class TokenFormController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(TokenFormController.class);

    /** Flash attribute carrying the raw secret to the reveal page, which reads it once. */
    public static final String REVEALED_TOKEN = "revealedToken";

    /** Flash attribute: the new token's label, shown beside the secret. */
    public static final String REVEALED_LABEL = "revealedLabel";

    /** Flash attribute: the agent a token was minted for, so Done returns to its page. */
    public static final String REVEALED_FOR_AGENT = "revealedForAgent";

    /** The reveal page, which {@code ProfileTokensController} serves to any signed-in author. */
    public static final String REVEAL_PATH = "/roller-ui/profileTokens!reveal.rol";

    /** roller_api_token.label is varchar(255): ColumnLimits.TOKEN_LABEL, which this package may not import. */
    public static final int MAX_LABEL_LENGTH = 255;

    /** The expiry choices besides "never", in days. */
    private static final List<Integer> EXPIRY_DAYS = List.of(30, 90, 365);

    /** An expiry choice, parsed: whether it was one of the four, and the instant (null for never). */
    private record ExpiryChoice(boolean valid, Timestamp expiresAt) { }

    /** Puts {@code owner}'s live tokens and the token form on the model. */
    protected void addTokenSection(Model model, User owner, TokenFormValues values,
                                   String createAction, String revokeAction, String ownerParam) {
        model.addAttribute("tokens", liveTokens(owner));
        model.addAttribute("pinChoices", pinChoices(owner));
        model.addAttribute("tokenFormValues", values);
        model.addAttribute("tokenFormAction", createAction);
        model.addAttribute("tokenRevokeAction", revokeAction);
        model.addAttribute("tokenFormOwner", ownerParam);
    }

    /**
     * Mints a token for {@code owner}, recording {@code actor} as its issuer,
     * and writes the {@code token.minted} line. Each refusal is a field error
     * on its control.
     *
     * @return the mint, or null when the form was refused (errors are on the model)
     */
    protected ApiTokenManager.Issued mintToken(User owner, User actor, TokenFormValues values,
                                               Model model, HttpServletRequest request) {
        String label = StringUtils.trimToEmpty(values.getLabel());
        if (label.isEmpty()) {
            addFieldError(model, "tokenLabel", "apiTokens.error.labelRequired", request);
        } else if (label.length() > MAX_LABEL_LENGTH) {
            addFieldError(model, "tokenLabel", "apiTokens.error.labelTooLong", request);
        }
        ApiToken.Role role = roleOf(values.getRole());
        if (role == null) {
            addFieldError(model, "tokenRole", "apiTokens.error.role", request);
        }
        String pin = StringUtils.trimToNull(values.getPin());
        if (!pinAllowed(owner, pin)) {
            addFieldError(model, "tokenPin", "apiTokens.error.pin", request);
        }
        ExpiryChoice expiry = expiryFor(values.getExpiry(), Instant.now());
        if (!expiry.valid()) {
            addFieldError(model, "tokenExpiry", "apiTokens.error.expiry", request);
        }
        if (hasErrors(model)) {
            return null;
        }
        try {
            ApiTokenManager.Issued issued = weblogger.getApiTokenManager()
                    .issueToken(owner, label, pin, role, expiry.expiresAt(), actor);
            weblogger.flush();
            AdminLog.record("token.minted", actor, AdminLog.tokenDetails(issued.token()));
            return issued;
        } catch (WebloggerException e) {
            log.error("Could not mint a token for {}", owner.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return null;
        }
    }

    /** Sends the new secret to the reveal page in flash scope only. */
    protected String revealRedirect(ApiTokenManager.Issued issued, String forAgent,
                                    RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute(REVEALED_TOKEN, issued.rawToken());
        redirectAttributes.addFlashAttribute(REVEALED_LABEL, issued.token().getLabel());
        if (forAgent != null) {
            redirectAttributes.addFlashAttribute(REVEALED_FOR_AGENT, forAgent);
        }
        return "redirect:" + REVEAL_PATH;
    }

    /**
     * The reveal page. The secret is on the model only on the visit right
     * after the mint (the flash attribute); a second visit finds nothing and
     * the page says the token was already shown. Never cached either way.
     */
    protected String revealPage(HttpServletResponse response, Model model) {
        // A shared machine's back button must never bring the secret back.
        response.setHeader("Cache-Control", "no-store");
        if (model.getAttribute(REVEALED_TOKEN) instanceof String secret) {
            String mcpUrl = StringUtils.removeEnd(WebloggerRuntimeConfig.getAbsoluteContextURL(), "/") + "/api/mcp";
            model.addAttribute("mcpUrl", mcpUrl);
            model.addAttribute("mcpSnippet", "claude mcp add --transport http roller " + mcpUrl
                    + " --header \"Authorization: Bearer " + secret + "\"");
        }
        return ".TokenReveal";
    }

    /**
     * Revokes a token, writing the {@code token.revoked} line the first time.
     * Revoking an already-revoked token succeeds and changes nothing.
     *
     * @param requiredOwner the account the token must belong to, or null for
     *                      a site admin, who may revoke any token
     * @return false for an unknown id, a token outside {@code requiredOwner},
     *         or a store that failed -- each answers "no such token"
     */
    protected boolean revokeToken(User actor, String tokenId, User requiredOwner) {
        try {
            ApiTokenManager tokens = weblogger.getApiTokenManager();
            ApiToken token = tokens.getToken(tokenId);
            if (token == null
                    || (requiredOwner != null && !token.getUser().getId().equals(requiredOwner.getId()))) {
                return false;
            }
            boolean wasLive = token.getRevokedAt() == null;
            tokens.revoke(token);
            weblogger.flush();
            if (wasLive) {
                AdminLog.record("token.revoked", actor, AdminLog.tokenDetails(token));
            }
            return true;
        } catch (WebloggerException e) {
            log.error("Could not revoke token {}", tokenId, e);
            return false;
        }
    }

    /** A display path: a store that cannot list shows no tokens. */
    private List<ApiToken> liveTokens(User owner) {
        try {
            return weblogger.getApiTokenManager().getLiveTokens(owner);
        } catch (WebloggerException e) {
            log.error("Could not list the tokens of {}", owner.getUserName(), e);
            return List.of();
        }
    }

    /** A display path: a store that cannot answer offers only "All its weblogs". */
    private List<Weblog> pinChoices(User owner) {
        try {
            return TokenPins.choices(weblogger, owner);
        } catch (WebloggerException e) {
            log.error("Could not list the weblogs {} may pin", owner.getUserName(), e);
            return List.of();
        }
    }

    /** A decision path: a pin check that could not run refuses the pin. */
    private boolean pinAllowed(User owner, String pin) {
        try {
            return TokenPins.allows(weblogger, owner, pin);
        } catch (WebloggerException e) {
            log.error("Could not check the pin {} for {}", pin, owner.getUserName(), e);
            return false;
        }
    }

    private static ApiToken.Role roleOf(String role) {
        for (ApiToken.Role candidate : ApiToken.Role.values()) {
            if (candidate.name().equals(role)) {
                return candidate;
            }
        }
        return null;
    }

    private static ExpiryChoice expiryFor(String choice, Instant now) {
        String value = StringUtils.defaultIfBlank(choice, TokenFormValues.DEFAULT_EXPIRY);
        if (TokenFormValues.NEVER.equals(value)) {
            return new ExpiryChoice(true, null);
        }
        for (int days : EXPIRY_DAYS) {
            if (Integer.toString(days).equals(value)) {
                return new ExpiryChoice(true, Timestamp.from(now.plus(Duration.ofDays(days))));
            }
        }
        return new ExpiryChoice(false, null);
    }
}
```

Create `ProfileTokensController.java`:

```java
package org.apache.roller.weblogger.ui.controllers.core;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.TokenFormController;
import org.apache.roller.weblogger.ui.controllers.TokenFormValues;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Profile → API tokens (spec 2026-10-13, section 3; criterion 15): every
 * signed-in author mints, lists and revokes their own tokens, and every
 * mint -- here or on an agent's page -- lands on this controller's reveal.
 * Under {@code /roller-ui/profile*}, so SecurityConfig admits editors and
 * admins only; a reader holds no login action and is refused.
 */
@Controller
@RequestMapping("/roller-ui")
public class ProfileTokensController extends TokenFormController {

    private static final String CREATE_ACTION = "/roller-ui/profileTokens!create.rol";
    private static final String REVOKE_ACTION = "/roller-ui/profileTokens!revoke.rol";

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "apiTokens.profile.title";
    }

    @GetMapping("/profileTokens.rol")
    public String list(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        return showPage(request, model, new TokenFormValues());
    }

    @PostMapping("/profileTokens!create.rol")
    public String create(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "label", required = false) String label,
                         @RequestParam(name = "role", required = false) String role,
                         @RequestParam(name = "pin", required = false) String pin,
                         @RequestParam(name = "expiry", required = false) String expiry) {
        populateCommonModel(request, model);
        User me = getAuthenticatedUser(request);
        TokenFormValues values = new TokenFormValues(label, role, pin, expiry);
        ApiTokenManager.Issued issued = mintToken(me, me, values, model, request);
        if (issued == null) {
            return showPage(request, model, values);
        }
        return revealRedirect(issued, null, redirectAttributes);
    }

    /** A person revokes only their own tokens; any other id answers "no such token". */
    @PostMapping("/profileTokens!revoke.rol")
    public String revoke(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "id", required = false) String id) {
        User me = getAuthenticatedUser(request);
        if (revokeToken(me, id, me)) {
            addFlashMessage(redirectAttributes, "apiTokens.revoked", request);
        } else {
            addFlashError(redirectAttributes, "apiTokens.error.noSuchToken", request);
        }
        return "redirect:/roller-ui/profileTokens.rol";
    }

    @GetMapping("/profileTokens!reveal.rol")
    public String reveal(HttpServletRequest request, HttpServletResponse response, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "apiTokens.reveal.title");
        return revealPage(response, model);
    }

    private String showPage(HttpServletRequest request, Model model, TokenFormValues values) {
        addTokenSection(model, getAuthenticatedUser(request), values, CREATE_ACTION, REVOKE_ACTION, null);
        return ".ProfileTokens";
    }
}
```

In `RollerViewResolver.init()`, after the `.Profile` definition:

```java
        // Profile -> API tokens and the one-time reveal: signed-in, un-scoped
        // screens in Profile's own chrome (spec 2026-10-13, section 3).
        addExtendedDefinition(".ProfileTokens", ".tiles-mainmenupage",
                Map.of("content", "/WEB-INF/jsps/core/ProfileTokens.jsp"));

        addExtendedDefinition(".TokenReveal", ".tiles-mainmenupage",
                Map.of("content", "/WEB-INF/jsps/core/TokenReveal.jsp"));
```

- [ ] **Step 4: Write the JSPs**

Each file starts with the licence header block.

`app/src/main/webapp/WEB-INF/jsps/core/TokenForm.jsp`:

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- The token form (spec 2026-10-13, section 3; docs/design/forms/forms-token-reveal.html),
     included by the agent page and Profile -> API tokens. The controller sets
     tokenFormAction, tokenFormOwner (an agent's username, or nothing),
     pinChoices and tokenFormValues. The weblog field is "pin", never
     "weblog": RollerHandlerInterceptor reads that as the action weblog. --%>
<form method="post" action="<c:url value='${tokenFormAction}'/>" class="form-stacked" id="tokenForm">
    <sec:csrfInput/>
    <c:if test="${not empty tokenFormOwner}">
        <input type="hidden" name="userName" value="${fn:escapeXml(tokenFormOwner)}"/>
    </c:if>

    <div class="mb-3">
        <label for="tokenLabel" class="form-label"><spring:message code="apiTokens.label"/></label>
        <input type="text" class="form-control" id="tokenLabel" name="label" maxlength="255"
               value="${fn:escapeXml(tokenFormValues.label)}"/>
    </div>

    <div class="mb-3">
        <label for="tokenRole" class="form-label"><spring:message code="apiTokens.role"/></label>
        <select class="form-select" id="tokenRole" name="role">
            <option value="READ" <c:if test="${tokenFormValues.role == 'READ'}">selected="selected"</c:if>><spring:message code="apiTokens.role.read"/></option>
            <option value="POST" <c:if test="${tokenFormValues.role == 'POST'}">selected="selected"</c:if>><spring:message code="apiTokens.role.write"/></option>
            <option value="ADMIN" <c:if test="${tokenFormValues.role == 'ADMIN'}">selected="selected"</c:if>><spring:message code="apiTokens.role.admin"/></option>
        </select>
    </div>

    <div class="mb-3">
        <label for="tokenPin" class="form-label"><spring:message code="apiTokens.weblog"/></label>
        <select class="form-select" id="tokenPin" name="pin">
            <option value=""><spring:message code="apiTokens.weblog.all"/></option>
            <c:forEach var="choice" items="${pinChoices}">
                <option value="${fn:escapeXml(choice.handle)}"
                    <c:if test="${choice.handle == tokenFormValues.pin}">selected="selected"</c:if>
                >${fn:escapeXml(choice.name)} (${fn:escapeXml(choice.handle)})</option>
            </c:forEach>
        </select>
    </div>

    <div class="mb-3">
        <label for="tokenExpiry" class="form-label"><spring:message code="apiTokens.expiry"/></label>
        <select class="form-select" id="tokenExpiry" name="expiry">
            <option value="30" <c:if test="${tokenFormValues.expiry == '30'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.30"/></option>
            <option value="90" <c:if test="${tokenFormValues.expiry == '90'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.90"/></option>
            <option value="365" <c:if test="${tokenFormValues.expiry == '365'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.365"/></option>
            <option value="never" <c:if test="${tokenFormValues.expiry == 'never'}">selected="selected"</c:if>><spring:message code="apiTokens.expiry.never"/></option>
        </select>
    </div>

    <button type="submit" class="btn btn-secondary" id="tokenCreateButton"><spring:message code="apiTokens.create"/></button>
</form>
```

`app/src/main/webapp/WEB-INF/jsps/core/TokenList.jsp`:

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- An account's live tokens (spec 2026-10-13, section 3), included by the
     agent page and Profile -> API tokens. The controller sets tokens,
     tokenRevokeAction and tokenFormOwner. Revoke asks first. --%>
<c:choose>
    <c:when test="${not empty tokens}">
        <spring:message code="apiTokens.revoke.confirm" var="revokeConfirm"/>
        <table class="rollertable table" id="tokenListTable">
            <tr>
                <th scope="col"><spring:message code="apiTokens.label"/></th>
                <th scope="col"><spring:message code="apiTokens.role"/></th>
                <th scope="col"><spring:message code="apiTokens.weblog"/></th>
                <th scope="col"><spring:message code="apiTokens.created"/></th>
                <th scope="col"><spring:message code="apiTokens.lastUsed"/></th>
                <th scope="col"><spring:message code="apiTokens.expiry"/></th>
                <th scope="col"><span class="visually-hidden"><spring:message code="apiTokens.revoke"/></span></th>
            </tr>
            <c:forEach var="token" items="${tokens}">
                <tr>
                    <td>${fn:escapeXml(token.label)}</td>
                    <td>
                        <c:choose>
                            <c:when test="${token.scopeRole == 'READ'}"><spring:message code="apiTokens.role.read"/></c:when>
                            <c:when test="${token.scopeRole == 'ADMIN'}"><spring:message code="apiTokens.role.admin"/></c:when>
                            <c:otherwise><spring:message code="apiTokens.role.write"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${empty token.scopeWeblog}"><spring:message code="apiTokens.weblog.all"/></c:when>
                            <c:otherwise><span class="data">${fn:escapeXml(token.scopeWeblog)}</span></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data"><rc:date value="${token.created}"/></td>
                    <td class="data"><rc:date value="${token.lastUsedAt}"/></td>
                    <td class="data">
                        <c:choose>
                            <c:when test="${empty token.expiresAt}"><spring:message code="apiTokens.expiry.never"/></c:when>
                            <c:otherwise><rc:date value="${token.expiresAt}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <form method="post" action="<c:url value='${tokenRevokeAction}'/>">
                            <sec:csrfInput/>
                            <c:if test="${not empty tokenFormOwner}">
                                <input type="hidden" name="userName" value="${fn:escapeXml(tokenFormOwner)}"/>
                            </c:if>
                            <input type="hidden" name="id" value="${fn:escapeXml(token.id)}"/>
                            <button type="submit" class="btn btn-secondary btn-sm" data-confirm="${revokeConfirm}">
                                <spring:message code="apiTokens.revoke"/>
                            </button>
                        </form>
                    </td>
                </tr>
            </c:forEach>
        </table>
    </c:when>
    <c:otherwise>
        <div class="empty-state" id="tokenListEmpty">
            <p class="empty-state-title"><spring:message code="apiTokens.none.title"/></p>
            <p class="empty-state-body"><spring:message code="apiTokens.none.body"/></p>
        </div>
    </c:otherwise>
</c:choose>
```

`app/src/main/webapp/WEB-INF/jsps/core/ProfileTokens.jsp`:

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- Profile -> API tokens (spec 2026-10-13, section 3; criterion 15).
     RouteSweepIT finds this page by #profileTokensPage. --%>
<div id="profileTokensPage">
    <p class="subtitle"><spring:message code="apiTokens.profile.subtitle"/></p>

    <h3 class="section-head"><spring:message code="apiTokens.live"/></h3>
    <jsp:include page="/WEB-INF/jsps/core/TokenList.jsp"/>

    <h3 class="section-head"><spring:message code="apiTokens.new"/></h3>
    <jsp:include page="/WEB-INF/jsps/core/TokenForm.jsp"/>
</div>
```

`app/src/main/webapp/WEB-INF/jsps/core/TokenReveal.jsp`:

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- The one-time reveal (spec 2026-10-13, section 3; docs/design/forms/forms-token-reveal.html).
     The secret arrives only as a one-read flash attribute, the response is
     Cache-Control: no-store, and a second visit says "already shown". The
     secret is only ever a form value here, never part of a link.
     RouteSweepIT finds this page by #tokenRevealPage. --%>
<div id="tokenRevealPage">
<c:choose>
    <c:when test="${not empty revealedToken}">
        <p class="subtitle"><spring:message code="apiTokens.reveal.once"/></p>
        <p><spring:message code="apiTokens.label"/>: <strong>${fn:escapeXml(revealedLabel)}</strong></p>

        <div class="mb-3">
            <label for="revealedToken" class="form-label"><spring:message code="apiTokens.reveal.token"/></label>
            <div class="input-group">
                <input type="text" class="form-control data" id="revealedToken" readonly="readonly"
                       autocomplete="off" value="${fn:escapeXml(revealedToken)}"/>
                <button type="button" class="btn btn-secondary clipbutton" data-clipboard-target="#revealedToken">
                    <spring:message code="apiTokens.reveal.copy"/>
                </button>
            </div>
        </div>

        <div class="mb-3">
            <label for="mcpSnippet" class="form-label"><spring:message code="apiTokens.reveal.mcp"/></label>
            <textarea class="form-control data" id="mcpSnippet" rows="3" readonly="readonly">${fn:escapeXml(mcpSnippet)}</textarea>
            <button type="button" class="btn btn-secondary clipbutton mt-2" data-clipboard-target="#mcpSnippet">
                <spring:message code="apiTokens.reveal.copy"/>
            </button>
        </div>
    </c:when>
    <c:otherwise>
        <div class="empty-state" id="tokenAlreadyShown">
            <p class="empty-state-title"><spring:message code="apiTokens.reveal.alreadyShown"/></p>
            <p class="empty-state-body"><spring:message code="apiTokens.reveal.alreadyShown.body"/></p>
        </div>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${not empty revealedForAgent}">
        <c:url var="revealDoneUrl" value="/roller-ui/admin/agent.rol">
            <c:param name="userName" value="${revealedForAgent}"/>
        </c:url>
    </c:when>
    <c:otherwise>
        <c:url var="revealDoneUrl" value="/roller-ui/profileTokens.rol"/>
    </c:otherwise>
</c:choose>
<p class="mt-3">
    <a href="${revealDoneUrl}" class="btn btn-primary" id="tokenRevealDone"><spring:message code="apiTokens.reveal.done"/></a>
</p>
</div>

<script>
    $(document).ready(function () {
        var clipboard = new ClipboardJS('.clipbutton');
        clipboard.on('success', function (e) {
            e.trigger.classList.add('copied');
            setTimeout(function () { e.trigger.classList.remove('copied'); }, 1500);
            e.clearSelection();
        });
    });
</script>
```

In `core/Profile.jsp`, immediately after the line `</form:form>` add:

```jsp

<p class="mt-4">
    <a href="<c:url value='/roller-ui/profileTokens.rol'/>" id="profileTokensLink"><spring:message code="apiTokens.profile.link"/></a>
</p>
```

Messages. Append this block to all 8 bundles with the loop:

```properties
apiTokens.profile.title=API tokens
apiTokens.profile.subtitle=A token lets a script or an agent use the API as you. Its secret is shown once, when it is created.
apiTokens.profile.link=Your API tokens
apiTokens.live=Live tokens
apiTokens.new=New token
apiTokens.label=Label
apiTokens.role=Role
apiTokens.role.read=Read
apiTokens.role.write=Write
apiTokens.role.admin=Admin
apiTokens.weblog=Weblog
apiTokens.weblog.all=All its weblogs
apiTokens.expiry=Expires
apiTokens.expiry.30=In 30 days
apiTokens.expiry.90=In 90 days
apiTokens.expiry.365=In 365 days
apiTokens.expiry.never=Never
apiTokens.create=Create token
apiTokens.created=Created
apiTokens.lastUsed=Last used
apiTokens.revoke=Revoke
apiTokens.revoke.confirm=Revoke this token? Anything using it stops working at once.
apiTokens.revoked=Token revoked.
apiTokens.none.title=No live tokens.
apiTokens.none.body=Create one below. Its secret is shown once.
apiTokens.error.labelRequired=Give the token a label.
apiTokens.error.labelTooLong=A label is at most 255 characters.
apiTokens.error.role=Choose Read, Write or Admin.
apiTokens.error.pin=Choose a weblog this account is a member of.
apiTokens.error.expiry=Choose 30, 90 or 365 days, or never.
apiTokens.error.noSuchToken=No such token.
apiTokens.reveal.title=Your new token
apiTokens.reveal.once=Copy it now. This is the only time it is shown.
apiTokens.reveal.token=Token
apiTokens.reveal.mcp=MCP connection
apiTokens.reveal.copy=Copy
apiTokens.reveal.done=Done
apiTokens.reveal.alreadyShown=This token was already shown.
apiTokens.reveal.alreadyShown.body=A token is shown only once. To get a new one, revoke it and create another.
```

- [ ] **Step 5: Add the routes**

In `Routes.java`, right after the `/roller-ui/profile.rol` entry in `NO_PARAMETERS`:

```java
            // Profile -> API tokens (spec 2026-10-13): the live tokens and the form.
            new Route("/roller-ui/profileTokens.rol", Role.EDITOR, "", "#profileTokensPage"),

            // The one-time reveal. Opened directly it has no flash secret, so it
            // renders its "already shown" state -- still its own content tile.
            new Route("/roller-ui/profileTokens!reveal.rol", Role.EDITOR, "", "#tokenRevealPage"),
```

Run: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests` — expected BUILD SUCCESS.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='ProfileTokensControllerTest,RollerViewResolverTest,SecurityConfigTest,JspConsistencyTest,AdminJspHygieneTest,AgentAccountsJspTest,AgentAccountMessagesTest,MessageKeyTest,MessagePlaceholderContractTest,MessageArgumentEscapingTest,RouteCoverageTest,ReaderRefusalSweepTest,ControllerMetadataTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenFormValues.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/TokenFormController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ProfileTokensController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java \
        app/src/main/webapp/WEB-INF/jsps/core/TokenForm.jsp app/src/main/webapp/WEB-INF/jsps/core/TokenList.jsp \
        app/src/main/webapp/WEB-INF/jsps/core/ProfileTokens.jsp app/src/main/webapp/WEB-INF/jsps/core/TokenReveal.jsp \
        app/src/main/webapp/WEB-INF/jsps/core/Profile.jsp \
        app/src/main/resources/ApplicationResources*.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/ProfileTokensControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java \
        app/src/test/java/org/apache/roller/weblogger/boot/SecurityConfigTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java \
        it-selenium/src/test/java/org/apache/roller/it/support/Routes.java
git commit -m "feat(tokens): Profile -> API tokens -- mint with a checked pin, reveal once uncached, revoke"
```

---
### Task 14: New agent and the agent page

**Model:** sonnet — the largest page: one controller over the shared token base, two JSPs composing three includes, the person editor's agent hand-offs, the menu, a seeded route and a contingency on SpotBugs' redirect detector.

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AgentBean.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AgentController.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/admin/AgentCreate.jsp`, `admin/Agent.jsp`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java` (agents go to their own page)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java` (two views)
- Modify: `app/src/main/resources/org/apache/roller/weblogger/ui/menu/admin-menu.xml` (a subaction)
- Modify: the 8 `app/src/main/resources/ApplicationResources*.properties`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java`, `it-selenium/src/test/resources/seed-it-data.sql`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/AgentControllerTest.java` (create), `UserEditControllerTest.java` (three tests)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java`

**Interfaces:**
- Consumes: Task 3's `UserManager.addAgent`; Task 10's `AccountRows.showList`; Task 11's `UserNames.problem`; Task 12's `UserWeblogsModel.populate`, `UserWeblogs.jsp`; Task 13's `TokenFormController`, `TokenFormValues`, `TokenForm.jsp`, `TokenList.jsp`.
- Produces:
  - `AgentBean` (public, bean): `userName`, `name`, `emailAddress`, `notes`, `siteAdmin`; `static AgentBean of(User agent, boolean siteAdmin)`.
  - GET `/roller-ui/admin/createAgent.rol`; POST `createAgent!save.rol` (`bean.userName`, `bean.name`, `bean.emailAddress`, `bean.notes`, `bean.siteAdmin`) → `redirect:/roller-ui/admin/agent.rol` with `userName`.
  - GET `/roller-ui/admin/agent.rol?userName=` (`#agentPage`); POST `agent!save.rol` (`bean.*`), `agent!setEnabled.rol` (`userName`, `enabled`), `agent!mintToken.rol` (`userName`, `label`, `role`, `pin`, `expiry`), `agent!revokeToken.rol` (`userName`, `id`).
  - Ids: create form `#agentCreateForm`, `#bean_userName`, `#bean_name`, `#bean_emailAddress`, `#bean_notes`, `#bean_siteAdmin`, `#agentCreateButton`; page `#agentPage`, `#agentDetailsForm`, `#agentUserName`, `#agentSaveButton`, `#agentEnabledForm`, `#agentDisableButton` / `#agentEnableButton`.
  - `modifyUser.rol` for an agent redirects to its page; `modifyUser!save.rol` and `userEdit!sendPasswordLink.rol` refuse an agent with `agent.error.notAPerson`.
  - IT seed: agent `it_agent`.

- [ ] **Step 1: Write the failing tests**

Create `AgentControllerTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.sql.Timestamp;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.TokenFormController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** New agent and the agent page (spec 2026-10-13, sections 1, 3 and 4; criteria 2, 3, 6, 8, 9, 11, 14). */
class AgentControllerTest {

    private static final String ALLOWED_CHARS = "username.allowedChars";

    private MockWeblogger weblogger;
    private AgentController controller;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private String previousAllowedChars;
    private User admin;
    private User agent;
    private Weblog travel;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        previousAllowedChars = ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "A-Za-z0-9");
        controller = ControllerTestFixture.withMessages(new AgentController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        admin = new User();
        admin.setUserName("admin");
        agent = new User();
        agent.setUserName("bot1");
        agent.setScreenName("Publisher");
        agent.setFullName("Publisher");
        agent.setKind(User.Kind.AGENT);
        travel = new Weblog();
        travel.setHandle("travel");
        travel.setName("Travel");
        when(weblogger.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restoreConfigProperty(ALLOWED_CHARS, previousAllowedChars);
    }

    private HttpServletRequest asAdmin() {
        return ControllerTestFixture.requestFor(admin);
    }

    private static AgentBean newAgent(String userName, String name) {
        AgentBean bean = new AgentBean();
        bean.setUserName(userName);
        bean.setName(name);
        return bean;
    }

    private void stored() throws WebloggerException {
        when(weblogger.userManager().getUserByUserName("bot1", null)).thenReturn(agent);
    }

    private void siteAdmin(boolean on) throws WebloggerException {
        when(weblogger.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(agent)))
                .thenReturn(on);
    }

    private ApiToken tokenOf(User owner, String id) {
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setUser(owner);
        token.setLabel("publisher");
        token.setScopeRole(ApiToken.Role.POST);
        return token;
    }

    @Test
    void theAgentPagesAreAdminOnlyAndNeedNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("agent", controller.getActionName());
    }

    @Test
    void theCreateFormStartsEmpty() {
        assertEquals(".AgentCreate", controller.createForm(asAdmin(), model));
        assertNull(((AgentBean) model.getAttribute("bean")).getUserName());
        assertEquals("agent.create.title", model.getAttribute("pageTitle"));
    }

    /** Criterion 2: an agent is stored as one, with its name and no password, and its page opens. */
    @Test
    void creatingAnAgentStoresItAndOpensItsPage() throws Exception {
        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.create(asAdmin(), model, redirect, newAgent("bot1", "  Publisher "));

            assertEquals("redirect:/roller-ui/admin/agent.rol", view);
            assertEquals("bot1", redirect.asMap().get("userName"));
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(weblogger.userManager()).addAgent(saved.capture(), eq(false));
            User stored = saved.getValue();
            assertEquals("bot1", stored.getUserName());
            assertEquals("Publisher", stored.getScreenName());
            assertEquals("Publisher", stored.getFullName());
            assertNull(stored.getEmailAddress(), "a blank email is the manager's to fill: bot1@agents.invalid");
            assertNull(stored.getPassword());
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("agent.created[bot1]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("agent.created agent=bot1 siteAdmin=false by=admin"), log.lines());
        }
    }

    /** Criterion 2: the Site administrator switch reaches the store. */
    @Test
    void theSiteAdministratorSwitchIsPassedThrough() throws Exception {
        AgentBean bean = newAgent("bot1", "Publisher");
        bean.setSiteAdmin(true);

        controller.create(asAdmin(), model, redirect, bean);

        verify(weblogger.userManager()).addAgent(any(User.class), eq(true));
    }

    /** Criterion 3: the username follows username.allowedChars. */
    @Test
    void anAgentUserNameFollowsTheAllowedCharacters() throws Exception {
        String view = controller.create(asAdmin(), model, redirect, newAgent("bot one", "Publisher"));

        assertEquals(".AgentCreate", view);
        assertEquals(List.of("error.add.user.badUserName"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addAgent(any(), eq(false));
    }

    /** Criterion 3: a taken username is a field error on the username. */
    @Test
    void aTakenUserNameIsAFieldErrorOnTheUserName() throws Exception {
        stored();

        controller.create(asAdmin(), model, redirect, newAgent("bot1", "Publisher"));

        assertEquals(List.of("error.add.user.userNameInUse"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addAgent(any(), eq(false));
    }

    @Test
    void aNameIsRequiredAndAGivenEmailMustBeValid() throws Exception {
        AgentBean bean = newAgent("bot2", "  ");
        bean.setEmailAddress("nope");

        controller.create(asAdmin(), model, redirect, bean);

        assertEquals(List.of("agent.error.nameRequired", "personForm.error.email"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_name", "bean_emailAddress"), ControllerTestFixture.invalidFields(model));
    }

    /** A store failure keeps the form and is reported; nothing is announced. */
    @Test
    void aStoreFailureWhileCreatingKeepsTheForm() throws Exception {
        doThrow(new WebloggerException("db down")).when(weblogger.userManager()).addAgent(any(), eq(false));

        String view = controller.create(asAdmin(), model, redirect, newAgent("bot3", "Publisher"));

        assertEquals(".AgentCreate", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(0, weblogger.flushCount());
        assertTrue(ControllerTestFixture.flashMessages(redirect).isEmpty());
    }

    @Test
    void notesLongerThanTheColumnAreRefused() throws Exception {
        AgentBean bean = newAgent("bot2", "Publisher");
        bean.setNotes("x".repeat(1001));

        controller.create(asAdmin(), model, redirect, bean);

        assertEquals(List.of("agent.error.notesTooLong"), ControllerTestFixture.errors(model));
    }

    /** Criterion 6 and the page's sections: details, status, weblogs and tokens; no password anywhere. */
    @Test
    void thePageShowsDetailsStatusWeblogsAndTokens() throws Exception {
        stored();
        siteAdmin(false);
        agent.setNotes("Publishes the travel guides");
        ApiToken live = tokenOf(agent, "t1");
        when(weblogger.apiTokenManager().getLiveTokens(agent)).thenReturn(List.of(live));

        String view = controller.page(asAdmin(), model, "bot1");

        assertEquals(".Agent", view);
        AgentBean bean = (AgentBean) model.getAttribute("bean");
        assertEquals("bot1", bean.getUserName());
        assertEquals("Publisher", bean.getName());
        assertEquals("Publishes the travel guides", bean.getNotes());
        assertFalse(bean.isSiteAdmin());
        assertEquals(Boolean.TRUE, model.getAttribute("agentEnabled"));
        assertEquals(List.of(live), model.getAttribute("tokens"));
        assertEquals("/roller-ui/admin/agent!mintToken.rol", model.getAttribute("tokenFormAction"));
        assertEquals("/roller-ui/admin/agent!revokeToken.rol", model.getAttribute("tokenRevokeAction"));
        assertEquals("bot1", model.getAttribute("tokenFormOwner"));
        assertEquals("bot1", model.getAttribute("weblogsAccount"));
        assertNotNull(model.getAttribute("permissions"));
    }

    @Test
    void aPersonOrAnUnknownNameIsNotAnAgentPage() throws Exception {
        User person = new User();
        person.setUserName("jake");
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(person);

        assertEquals(".UserAdmin", controller.page(asAdmin(), model, "jake"));
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));

        ExtendedModelMap unknown = new ExtendedModelMap();
        assertEquals(".UserAdmin", controller.page(asAdmin(), unknown, "nobody"));
    }

    /** Criterion 8: disabling records enabled = false and logs who did it. */
    @Test
    void disablingRecordsItAndLogsWhoDidIt() throws Exception {
        stored();

        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.setEnabled(asAdmin(), redirect, "bot1", "false");

            assertEquals("redirect:/roller-ui/admin/agent.rol", view);
            assertEquals("bot1", redirect.asMap().get("userName"));
            assertEquals(Boolean.FALSE, agent.getEnabled());
            verify(weblogger.userManager()).saveUser(agent);
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("agent.disabled"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("account.disabled account=bot1 by=admin"), log.lines());
        }
    }

    @Test
    void enablingItAgainIsRecordedAndLoggedToo() throws Exception {
        stored();
        agent.setEnabled(Boolean.FALSE);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.setEnabled(asAdmin(), redirect, "bot1", "true");

            assertEquals(Boolean.TRUE, agent.getEnabled());
            assertEquals(List.of("account.enabled account=bot1 by=admin"), log.lines());
        }
    }

    /** Spec section 6: the site-admin switch is granted and removed, each logged. */
    @Test
    void savingTheDetailsRenamesAndGrantsOrRemovesSiteAdmin() throws Exception {
        stored();
        when(weblogger.userManager().hasRole("admin", agent)).thenReturn(false);
        AgentBean bean = newAgent("bot1", "Publisher Two");
        bean.setNotes("Now also food");
        bean.setSiteAdmin(true);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            assertEquals("redirect:/roller-ui/admin/agent.rol", controller.save(asAdmin(), model, redirect, bean));

            assertEquals("Publisher Two", agent.getScreenName());
            assertEquals("Publisher Two", agent.getFullName());
            assertEquals("Now also food", agent.getNotes());
            verify(weblogger.userManager()).saveUser(agent);
            verify(weblogger.userManager()).grantRole("admin", agent);
            assertEquals(List.of("siteAdmin.granted account=bot1 by=admin"), log.lines());
        }

        when(weblogger.userManager().hasRole("admin", agent)).thenReturn(true);
        controller.save(asAdmin(), new ExtendedModelMap(), new RedirectAttributesModelMap(),
                newAgent("bot1", "Publisher Two"));
        verify(weblogger.userManager()).revokeRole("admin", agent);
    }

    /** Criterion 9: a mint from the agent page stores the choices, the default expiry and the issuer, then reveals. */
    @Test
    void mintingForTheAgentStoresTheChoicesAndRedirectsToTheReveal() throws Exception {
        stored();
        siteAdmin(false);
        when(weblogger.userManager().getWeblogPermission(travel, agent))
                .thenReturn(new WeblogPermission(travel, agent, List.of(WeblogPermission.POST)));
        when(weblogger.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued("rlr_agent", tokenOf(agent, "t1")));
        long before = System.currentTimeMillis();

        String view = controller.mint(asAdmin(), model, redirect, "bot1", "publisher", "POST", "travel", null);

        assertEquals("redirect:/roller-ui/profileTokens!reveal.rol", view);
        ArgumentCaptor<Timestamp> expires = ArgumentCaptor.forClass(Timestamp.class);
        verify(weblogger.apiTokenManager()).issueToken(eq(agent), eq("publisher"), eq("travel"),
                eq(ApiToken.Role.POST), expires.capture(), eq(admin));
        long ninetyDays = 90L * 24 * 60 * 60 * 1000;
        long expiry = expires.getValue().getTime();
        assertTrue(expiry >= before + ninetyDays - 60_000L
                && expiry <= System.currentTimeMillis() + ninetyDays + 60_000L, "the default expiry is 90 days");
        assertEquals("rlr_agent", redirect.getFlashAttributes().get(TokenFormController.REVEALED_TOKEN));
        assertEquals("bot1", redirect.getFlashAttributes().get(TokenFormController.REVEALED_FOR_AGENT));
    }

    /** Criterion 11: a pin to a weblog the agent is not in is a field error, and the page comes back. */
    @Test
    void aPinToAWeblogTheAgentIsNotInIsRefused() throws Exception {
        stored();
        siteAdmin(false);

        String view = controller.mint(asAdmin(), model, redirect, "bot1", "publisher", "POST", "travel", "90");

        assertEquals(".Agent", view);
        assertEquals(List.of("apiTokens.error.pin"), ControllerTestFixture.errors(model));
        assertEquals(List.of("tokenPin"), ControllerTestFixture.invalidFields(model));
        assertEquals("bot1", model.getAttribute("tokenFormOwner"));
        verify(weblogger.apiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any(), any());
    }

    /** Criterion 11: a site-admin agent may pin any existing weblog. */
    @Test
    void aSiteAdminAgentMayPinAnyExistingWeblog() throws Exception {
        stored();
        siteAdmin(true);
        when(weblogger.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued("rlr_ops", tokenOf(agent, "t2")));

        assertEquals("redirect:/roller-ui/profileTokens!reveal.rol",
                controller.mint(asAdmin(), model, redirect, "bot1", "ops", "ADMIN", "travel", "never"));
    }

    @Test
    void revokingTheAgentsTokenWorks() throws Exception {
        stored();
        ApiToken token = tokenOf(agent, "t1");
        when(weblogger.apiTokenManager().getToken("t1")).thenReturn(token);

        assertEquals("redirect:/roller-ui/admin/agent.rol", controller.revoke(asAdmin(), redirect, "bot1", "t1"));

        verify(weblogger.apiTokenManager()).revoke(token);
        assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(redirect));
    }

    /** Criterion 14: from an agent's page, a token of another account is "no such token". */
    @Test
    void revokingAnotherAccountsTokenFromThisPageIsNoSuchToken() throws Exception {
        stored();
        when(weblogger.apiTokenManager().getToken("t9")).thenReturn(tokenOf(admin, "t9"));

        controller.revoke(asAdmin(), redirect, "bot1", "t9");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.apiTokenManager(), never()).revoke(any(ApiToken.class));
    }
}
```

Add to `UserEditControllerTest`:

```java
    private User storedAgent(String userName) throws WebloggerException {
        User agent = storedUser(userName);
        agent.setKind(User.Kind.AGENT);
        return agent;
    }

    /** Criterion 6: an agent's admin page is its own page, which has no set-password control. */
    @Test
    void modifyingAnAgentOpensItsOwnPage() throws Exception {
        storedAgent("bot1");
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        assertEquals("redirect:/roller-ui/admin/agent.rol?userName=bot1",
                controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean));
    }

    /** Criteria 5 and 6: no set-password link is ever sent for an agent. */
    @Test
    void noSetPasswordLinkIsSentForAnAgent() throws Exception {
        User agent = storedAgent("bot1");
        when(weblogger.userManager().getUserByUserName("bot1", null)).thenReturn(agent);

        String view = controller.sendPasswordLink(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, "bot1");

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("agent.error.notAPerson"), ControllerTestFixture.errors(model));
        verify(weblogger.userTokenManager(), never()).issueToken(any(), any());
    }

    @Test
    void savingAnAgentThroughThePersonFormIsRefused() throws Exception {
        storedAgent("bot1");

        String view = controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, beanForExisting("u1", "bot1"));

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("agent.error.notAPerson"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }
```

In `RollerViewResolverTest`, change `case ".Comments", ".Submissions" -> ` to `case ".Comments", ".Submissions", ".Agent", ".AgentCreate" -> ` and add:

```java
    @Test
    void theAgentScreensRenderThroughTheTabbedLayout() throws Exception {
        resolver.init();

        assertEquals("/WEB-INF/jsps/admin/AgentCreate.jsp", renderAndCapture(".AgentCreate").getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/admin/Agent.jsp", renderAndCapture(".Agent").getAttribute("tile_content"));
    }
```

In `JspConsistencyTest`, add to `FIELD_ERROR_JSPS` after the `TokenFormController` entry (put a comma after that entry):

```java
            Map.entry("admin/AgentController.java", List.of("admin/AgentCreate.jsp", "admin/Agent.jsp"))
```

Add to `AgentAccountsJspTest` (imports `java.util.List`, `static org.junit.jupiter.api.Assertions.assertEquals`):

```java
    /** Criterion 6: an agent's pages carry no password field and no set-password link. */
    @Test
    void anAgentsPagesOfferNoPasswordAndNoSetPasswordLink() throws IOException {
        for (String page : List.of("admin/Agent.jsp", "admin/AgentCreate.jsp")) {
            String src = jsp(page);
            assertFalse(src.contains("sendPasswordLink"), page);
            assertFalse(src.contains("type=\"password\""), page);
        }
    }

    /** Criteria 8 and 25: Disable and Enable each ask first. */
    @Test
    void enablingAndDisablingAnAgentAreConfirmed() throws IOException {
        String form = formPostingTo(jsp("admin/Agent.jsp"), "agent!setEnabled.rol");
        assertTrue(form.contains("id=\"agentDisableButton\"") && form.contains("id=\"agentEnableButton\""));
        assertEquals(2, form.split("data-confirm=", -1).length - 1);
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='AgentControllerTest,UserEditControllerTest,RollerViewResolverTest,JspConsistencyTest,AgentAccountsJspTest'`
Expected: compilation failure (`AgentController`, `AgentBean` do not exist).

- [ ] **Step 3: Implement**

Create `AgentBean.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import org.apache.roller.weblogger.pojos.User;

/**
 * The New agent form and an agent page's Details form (spec 2026-10-13,
 * section 4). Field names are {@code bean.*}, bound through BaseController's
 * prefix. No password: an agent never signs in.
 */
public class AgentBean {

    private String userName;
    private String name;
    private String emailAddress;
    private String notes;
    private boolean siteAdmin;

    /** The Details form for an existing agent. Its email is set once, at creation. */
    static AgentBean of(User agent, boolean siteAdmin) {
        AgentBean bean = new AgentBean();
        bean.setUserName(agent.getUserName());
        bean.setName(agent.getScreenName());
        bean.setNotes(agent.getNotes());
        bean.setSiteAdmin(siteAdmin);
        return bean;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    /** Stored as both screen name and full name. */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Optional; blank becomes {@code <userName>@agents.invalid}. */
    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** The Site administrator switch: the admin role, for the site-wide admin API. */
    public boolean isSiteAdmin() {
        return siteAdmin;
    }

    public void setSiteAdmin(boolean siteAdmin) {
        this.siteAdmin = siteAdmin;
    }
}
```

Create `AgentController.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.controllers.AdminLog;
import org.apache.roller.weblogger.ui.controllers.TokenFormController;
import org.apache.roller.weblogger.ui.controllers.TokenFormValues;
import org.apache.roller.weblogger.ui.controllers.WeblogPermissionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * New agent and an agent's page (spec 2026-10-13, sections 1, 3 and 4). An
 * agent is a {@code roller_user} with kind AGENT that never signs in: its page
 * has no password and no set-password link, only its details, one confirmed
 * Disable or Enable, its weblogs, and the tokens a site admin mints for it.
 * Every change writes a {@code roller.admin} line.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class AgentController extends TokenFormController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private static final String MINT_ACTION = "/roller-ui/admin/agent!mintToken.rol";
    private static final String REVOKE_ACTION = "/roller-ui/admin/agent!revokeToken.rol";

    /** roller_user.screenname and fullname are varchar(255). */
    private static final int MAX_NAME_LENGTH = 255;

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "agent";
    }

    @Override
    public String getPageTitle() {
        return "agent.title";
    }

    @ModelAttribute("bean")
    public AgentBean getBean() {
        return new AgentBean();
    }

    @GetMapping("/createAgent.rol")
    public String createForm(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "agent.create.title");
        model.addAttribute("bean", new AgentBean());
        return ".AgentCreate";
    }

    @PostMapping("/createAgent!save.rol")
    public String create(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                         @ModelAttribute("bean") AgentBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "agent.create.title");
        model.addAttribute("bean", bean);
        String userNameProblem = UserNames.problem(bean.getUserName(), weblogger.getUserManager());
        if (userNameProblem != null) {
            addFieldError(model, "bean_userName", userNameProblem, request);
        }
        String name = checkedName(bean, model, request);
        String email = StringUtils.trimToNull(bean.getEmailAddress());
        if (email != null && !EmailValidator.getInstance().isValid(email)) {
            addFieldError(model, "bean_emailAddress", "personForm.error.email", request);
        }
        String notes = checkedNotes(bean, model, request);
        if (hasErrors(model)) {
            return ".AgentCreate";
        }

        User agent = new User();
        agent.setUserName(bean.getUserName());
        agent.setScreenName(name);
        agent.setFullName(name);
        agent.setEmailAddress(email);
        agent.setNotes(notes);
        agent.setLocale(Locale.getDefault().toString());
        agent.setTimeZone(TimeZone.getDefault().getID());
        agent.setDateCreated(new Date());
        try {
            weblogger.getUserManager().addAgent(agent, bean.isSiteAdmin());
            weblogger.flush();
        } catch (WebloggerException e) {
            log.error("Could not create the agent {}", bean.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return ".AgentCreate";
        }
        AdminLog.record("agent.created", getAuthenticatedUser(request),
                "agent=" + agent.getUserName() + " siteAdmin=" + bean.isSiteAdmin());
        addFlashMessage(redirectAttributes, "agent.created",
                StringEscapeUtils.escapeHtml4(agent.getUserName()), request);
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/roller-ui/admin/agent.rol";
    }

    @GetMapping("/agent.rol")
    public String page(HttpServletRequest request, Model model,
                       @RequestParam(name = "userName", required = false) String userName) {
        populateCommonModel(request, model);
        User agent = lookupAgent(userName);
        if (agent == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        return showAgent(model, agent, AgentBean.of(agent, isGlobalAdmin(agent)), new TokenFormValues());
    }

    @PostMapping("/agent!save.rol")
    public String save(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                       @ModelAttribute("bean") AgentBean bean) {
        populateCommonModel(request, model);
        User agent = lookupAgent(bean.getUserName());
        if (agent == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        String name = checkedName(bean, model, request);
        String notes = checkedNotes(bean, model, request);
        if (hasErrors(model)) {
            return showAgent(model, agent, bean, new TokenFormValues());
        }
        try {
            UserManager users = weblogger.getUserManager();
            agent.setScreenName(name);
            agent.setFullName(name);
            agent.setNotes(notes);
            users.saveUser(agent);
            boolean wasSiteAdmin = users.hasRole("admin", agent);
            if (bean.isSiteAdmin() && !wasSiteAdmin) {
                users.grantRole("admin", agent);
            } else if (!bean.isSiteAdmin() && wasSiteAdmin) {
                users.revokeRole("admin", agent);
            }
            weblogger.flush();
            if (bean.isSiteAdmin() != wasSiteAdmin) {
                AdminLog.record(bean.isSiteAdmin() ? "siteAdmin.granted" : "siteAdmin.removed",
                        getAuthenticatedUser(request), "account=" + agent.getUserName());
            }
        } catch (WebloggerException e) {
            log.error("Could not save the agent {}", agent.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return showAgent(model, agent, bean, new TokenFormValues());
        }
        addFlashMessage(redirectAttributes, "agent.saved", request);
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/roller-ui/admin/agent.rol";
    }

    /** One button behind data-confirm; disabling ends every token on its next request. */
    @PostMapping("/agent!setEnabled.rol")
    public String setEnabled(HttpServletRequest request, RedirectAttributes redirectAttributes,
                             @RequestParam(name = "userName", required = false) String userName,
                             @RequestParam(name = "enabled", required = false) String enabled) {
        User agent = lookupAgent(userName);
        if (agent == null) {
            addFlashError(redirectAttributes, "userAdmin.error.userNotFound", request);
            return "redirect:/roller-ui/admin/userAdmin.rol";
        }
        boolean enable = Boolean.parseBoolean(enabled);
        try {
            agent.setEnabled(enable);
            weblogger.getUserManager().saveUser(agent);
            weblogger.flush();
            AdminLog.record(enable ? "account.enabled" : "account.disabled", getAuthenticatedUser(request),
                    "account=" + agent.getUserName());
            addFlashMessage(redirectAttributes, enable ? "agent.enabled" : "agent.disabled", request);
        } catch (WebloggerException e) {
            log.error("Could not change whether the agent {} is enabled", agent.getUserName(), e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/roller-ui/admin/agent.rol";
    }

    @PostMapping("/agent!mintToken.rol")
    public String mint(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                       @RequestParam(name = "userName", required = false) String userName,
                       @RequestParam(name = "label", required = false) String label,
                       @RequestParam(name = "role", required = false) String role,
                       @RequestParam(name = "pin", required = false) String pin,
                       @RequestParam(name = "expiry", required = false) String expiry) {
        populateCommonModel(request, model);
        User agent = lookupAgent(userName);
        if (agent == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        TokenFormValues values = new TokenFormValues(label, role, pin, expiry);
        ApiTokenManager.Issued issued = mintToken(agent, getAuthenticatedUser(request), values, model, request);
        if (issued == null) {
            return showAgent(model, agent, AgentBean.of(agent, isGlobalAdmin(agent)), values);
        }
        return revealRedirect(issued, agent.getUserName(), redirectAttributes);
    }

    /** From an agent's page only that agent's tokens; any other id answers "no such token". */
    @PostMapping("/agent!revokeToken.rol")
    public String revoke(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "userName", required = false) String userName,
                         @RequestParam(name = "id", required = false) String id) {
        User agent = lookupAgent(userName);
        if (agent == null) {
            addFlashError(redirectAttributes, "userAdmin.error.userNotFound", request);
            return "redirect:/roller-ui/admin/userAdmin.rol";
        }
        if (revokeToken(getAuthenticatedUser(request), id, agent)) {
            addFlashMessage(redirectAttributes, "apiTokens.revoked", request);
        } else {
            addFlashError(redirectAttributes, "apiTokens.error.noSuchToken", request);
        }
        redirectAttributes.addAttribute("userName", agent.getUserName());
        return "redirect:/roller-ui/admin/agent.rol";
    }

    private String showAgent(Model model, User agent, AgentBean bean, TokenFormValues values) {
        model.addAttribute("bean", bean);
        model.addAttribute("agentEnabled", Boolean.TRUE.equals(agent.getEnabled()));
        model.addAttribute("permissions", permissionsOf(agent));
        UserWeblogsModel.populate(weblogger, model, agent);
        addTokenSection(model, agent, values, MINT_ACTION, REVOKE_ACTION, agent.getUserName());
        return ".Agent";
    }

    /** The trimmed name, with a field error on {@code bean_name} when it is missing or too long. */
    private String checkedName(AgentBean bean, Model model, HttpServletRequest request) {
        String name = StringUtils.trimToNull(bean.getName());
        if (name == null) {
            addFieldError(model, "bean_name", "agent.error.nameRequired", request);
        } else if (name.length() > MAX_NAME_LENGTH) {
            addFieldError(model, "bean_name", "agent.error.nameTooLong", request);
        }
        return name;
    }

    private String checkedNotes(AgentBean bean, Model model, HttpServletRequest request) {
        String notes = StringUtils.trimToNull(bean.getNotes());
        if (notes != null && notes.length() > User.NOTES_MAX_LENGTH) {
            addFieldError(model, "bean_notes", "agent.error.notesTooLong", request);
        }
        return notes;
    }

    /** A display path: a store that cannot list shows no memberships. */
    private List<WeblogPermissionView> permissionsOf(User agent) {
        try {
            return WeblogPermissionView.resolve(weblogger.getUserManager().getWeblogPermissions(agent), weblogger);
        } catch (WebloggerException e) {
            log.error("Could not list the weblogs of {}", agent.getUserName(), e);
            return List.of();
        }
    }

    /** The agent named, or null -- for a person, an unknown name, or a lookup that failed; nothing proceeds. */
    private User lookupAgent(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        try {
            User user = weblogger.getUserManager().getUserByUserName(userName, null);
            return user != null && user.isAgent() ? user : null;
        } catch (WebloggerException e) {
            log.error("Could not look up the agent {}", userName, e);
            return null;
        }
    }
}
```

In `UserEditController.java`:
- in `modifyUserExecute`, right after the `if (user == null) { ... }` block, add:

```java
        if (user.isAgent()) {
            // An agent has its own page, with no password and no set-password
            // link (spec 2026-10-13, criterion 6). The name is restricted to
            // username.allowedChars, so it needs no encoding in the query.
            return "redirect:/roller-ui/admin/agent.rol?userName=" + user.getUserName();
        }
```

- in `modifyUserSave` and in `sendPasswordLink`, right after each method's `if (user == null) { ... }` block, add:

```java
        if (user.isAgent()) {
            addError(model, "agent.error.notAPerson", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
```

If SpotBugs reports `SPRING_UNVALIDATED_REDIRECT` on the `modifyUserExecute` line, give `modifyUserExecute` a `RedirectAttributes redirectAttributes` parameter, use `redirectAttributes.addAttribute("userName", user.getUserName())` with `return "redirect:/roller-ui/admin/agent.rol";`, and pass `new RedirectAttributesModelMap()` at the existing test call sites -- never suppress the finding.

In `RollerViewResolver.init()`, after the `.UserEdit` definition:

```java
        addExtendedDefinition(".AgentCreate", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/AgentCreate.jsp"));

        addExtendedDefinition(".Agent", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/Agent.jsp"));
```

In `admin-menu.xml`, change the Users item's `subactions="createUser,modifyUser"` to `subactions="createUser,modifyUser,agent"` (the agent pages highlight Users).

- [ ] **Step 4: Write the JSPs**

Each starts with the licence header block.

`app/src/main/webapp/WEB-INF/jsps/admin/AgentCreate.jsp`:

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- New agent (spec 2026-10-13, sections 1 and 4). An agent never signs in,
     so there is no password; the email is optional and never mailed.
     RouteSweepIT finds this page by its form's action. --%>
<p class="subtitle"><spring:message code="agent.create.subtitle"/></p>

<form method="post" action="<c:url value='/roller-ui/admin/createAgent!save.rol'/>" class="form-stacked" id="agentCreateForm">
    <sec:csrfInput/>

    <div class="mb-3">
        <label for="bean_userName" class="form-label"><spring:message code="agent.userName"/></label>
        <input type="text" class="form-control" id="bean_userName" name="bean.userName" maxlength="255" autofocus
               value="${fn:escapeXml(bean.userName)}"/>
        <div class="form-text"><spring:message code="agent.userName.tip"/></div>
    </div>

    <div class="mb-3">
        <label for="bean_name" class="form-label"><spring:message code="agent.name"/></label>
        <input type="text" class="form-control" id="bean_name" name="bean.name" maxlength="255"
               value="${fn:escapeXml(bean.name)}"/>
    </div>

    <div class="mb-3">
        <label for="bean_emailAddress" class="form-label"><spring:message code="agent.email"/></label>
        <input type="text" class="form-control" id="bean_emailAddress" name="bean.emailAddress" maxlength="255"
               value="${fn:escapeXml(bean.emailAddress)}"/>
        <div class="form-text"><spring:message code="agent.email.tip"/></div>
    </div>

    <div class="mb-3">
        <label for="bean_notes" class="form-label"><spring:message code="agent.notes"/></label>
        <textarea class="form-control" id="bean_notes" name="bean.notes" rows="3" maxlength="1000">${fn:escapeXml(bean.notes)}</textarea>
        <div class="form-text"><spring:message code="agent.notes.tip"/></div>
    </div>

    <div class="mb-3 form-check">
        <input type="checkbox" class="form-check-input" id="bean_siteAdmin" name="bean.siteAdmin" value="true"
            <c:if test="${bean.siteAdmin}">checked="checked"</c:if>/>
        <label for="bean_siteAdmin" class="form-check-label"><spring:message code="agent.siteAdmin"/></label>
        <div class="form-text"><spring:message code="agent.siteAdmin.tip"/></div>
    </div>

    <button type="submit" class="btn btn-primary" id="agentCreateButton"><spring:message code="agent.create.button"/></button>
    <a href="<c:url value='/roller-ui/admin/userAdmin.rol'/>" class="btn btn-secondary"><spring:message code="generic.cancel"/></a>
</form>
```

`app/src/main/webapp/WEB-INF/jsps/admin/Agent.jsp`:

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- An agent's page (spec 2026-10-13, section 4; docs/design/forms/forms-agent-page.html).
     An agent never signs in: there is no password here and no set-password
     link. Disable and Enable ask first. RouteSweepIT finds this page by
     #agentPage. --%>
<div id="agentPage">

<p class="subtitle"><spring:message code="agent.subtitle" arguments="${fn:escapeXml(bean.userName)}"/></p>

<h3 class="section-head"><spring:message code="agent.details"/></h3>
<form method="post" action="<c:url value='/roller-ui/admin/agent!save.rol'/>" class="form-stacked" id="agentDetailsForm">
    <sec:csrfInput/>
    <input type="hidden" name="bean.userName" value="${fn:escapeXml(bean.userName)}"/>

    <div class="mb-3">
        <label for="agentUserName" class="form-label"><spring:message code="agent.userName"/></label>
        <input type="text" class="form-control data" id="agentUserName" readonly="readonly"
               value="${fn:escapeXml(bean.userName)}"/>
    </div>

    <div class="mb-3">
        <label for="bean_name" class="form-label"><spring:message code="agent.name"/></label>
        <input type="text" class="form-control" id="bean_name" name="bean.name" maxlength="255"
               value="${fn:escapeXml(bean.name)}"/>
    </div>

    <div class="mb-3">
        <label for="bean_notes" class="form-label"><spring:message code="agent.notes"/></label>
        <textarea class="form-control" id="bean_notes" name="bean.notes" rows="3" maxlength="1000">${fn:escapeXml(bean.notes)}</textarea>
        <div class="form-text"><spring:message code="agent.notes.tip"/></div>
    </div>

    <div class="mb-3 form-check">
        <input type="checkbox" class="form-check-input" id="bean_siteAdmin" name="bean.siteAdmin" value="true"
            <c:if test="${bean.siteAdmin}">checked="checked"</c:if>/>
        <label for="bean_siteAdmin" class="form-check-label"><spring:message code="agent.siteAdmin"/></label>
        <div class="form-text"><spring:message code="agent.siteAdmin.tip"/></div>
    </div>

    <button type="submit" class="btn btn-primary" id="agentSaveButton"><spring:message code="generic.save"/></button>
</form>

<h3 class="section-head"><spring:message code="agent.status"/></h3>
<form method="post" action="<c:url value='/roller-ui/admin/agent!setEnabled.rol'/>"
      class="d-flex flex-wrap align-items-center gap-3" id="agentEnabledForm">
    <sec:csrfInput/>
    <input type="hidden" name="userName" value="${fn:escapeXml(bean.userName)}"/>
    <c:choose>
        <c:when test="${agentEnabled}">
            <span class="status-pill status-enabled"><spring:message code="userList.status.enabled"/></span>
            <input type="hidden" name="enabled" value="false"/>
            <spring:message code="agent.disable.confirm" arguments="${fn:escapeXml(bean.userName)}" var="disableConfirm"/>
            <button type="submit" class="btn btn-danger" id="agentDisableButton" data-confirm="${disableConfirm}">
                <spring:message code="agent.disable"/>
            </button>
            <span class="form-text"><spring:message code="agent.enabledNote"/></span>
        </c:when>
        <c:otherwise>
            <span class="status-pill status-disabled"><spring:message code="userList.status.disabled"/></span>
            <input type="hidden" name="enabled" value="true"/>
            <spring:message code="agent.enable.confirm" arguments="${fn:escapeXml(bean.userName)}" var="enableConfirm"/>
            <button type="submit" class="btn btn-secondary" id="agentEnableButton" data-confirm="${enableConfirm}">
                <spring:message code="agent.enable"/>
            </button>
            <span class="form-text"><spring:message code="agent.disabledNote"/></span>
        </c:otherwise>
    </c:choose>
</form>

<jsp:include page="/WEB-INF/jsps/admin/UserWeblogs.jsp"/>

<h3 class="section-head"><spring:message code="agent.tokens"/></h3>
<jsp:include page="/WEB-INF/jsps/core/TokenList.jsp"/>

<h3 class="section-head"><spring:message code="apiTokens.new"/></h3>
<jsp:include page="/WEB-INF/jsps/core/TokenForm.jsp"/>

</div>
```

Messages. Append this block to all 8 bundles with the loop:

```properties
agent.title=Agent
agent.create.title=New agent
agent.create.subtitle=An agent is an account for software. It never signs in: it has no password and works only through API tokens.
agent.create.button=Create agent
agent.subtitle=Agent {0}
agent.userName=Username
agent.userName.tip=Letters and digits. It cannot be changed later.
agent.name=Name
agent.email=Email (optional)
agent.email.tip=Left blank, it is the username at agents.invalid. Nothing is ever mailed to an agent.
agent.notes=Notes
agent.notes.tip=What this agent is for. Shown only here.
agent.siteAdmin=Site administrator
agent.siteAdmin.tip=With this on, an Admin token from this agent can use the site-wide admin API.
agent.details=Details
agent.status=Status
agent.enabledNote=Its tokens work.
agent.disabledNote=None of its tokens work until it is enabled again.
agent.disable=Disable
agent.enable=Enable
agent.disable.confirm=Disable {0}? Every one of its tokens stops working on its next request.
agent.enable.confirm=Enable {0}? Its live tokens start working again.
agent.created=Agent {0} created.
agent.saved=Agent saved.
agent.enabled=Agent enabled.
agent.disabled=Agent disabled.
agent.tokens=API tokens
agent.error.nameRequired=Enter a name.
agent.error.nameTooLong=A name is at most 255 characters.
agent.error.notesTooLong=Notes are at most 1000 characters.
agent.error.notAPerson=That is an agent account. Manage it on its own page.
```

- [ ] **Step 5: Seed an agent and add the routes**

In `it-selenium/src/test/resources/seed-it-data.sql`, after the second `userrole` insert for `it_admin`:

```sql

-- An agent account (V033), so the route sweep can open an agent's page. An
-- agent has no password and never signs in (spec 2026-10-13).
INSERT INTO roller_user (id, username, passphrase, screenname, fullname,
                         emailaddress, datecreated, locale, timezone, isenabled, kind, notes)
VALUES ('it-agent-0000-0000-0000-000000000001',
        'it_agent', NULL, 'IT Agent', 'IT Agent', 'it_agent@agents.invalid',
        NOW(), 'en_US', 'UTC', true, 'AGENT', 'Seeded for the route sweep.')
ON CONFLICT (username) DO NOTHING;

INSERT INTO userrole (id, rolename, username)
VALUES ('it-role-0000-0000-0000-000000000003', 'editor', 'it_agent')
ON CONFLICT (id) DO NOTHING;
```

In `Routes.java`, after the `/roller-ui/admin/createUser.rol` entry:

```java
            // New agent (spec 2026-10-13): the create form.
            new Route("/roller-ui/admin/createAgent.rol", Role.ADMIN, "",
                    "form[action$='/roller-ui/admin/createAgent!save.rol']"),

            // An agent's page, for the agent seed-it-data.sql creates -- the one
            // route in this list that needs a query: a page needs its agent.
            new Route("/roller-ui/admin/agent.rol", Role.ADMIN, "userName=it_agent", "#agentPage"),
```

Run: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests` — expected BUILD SUCCESS.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='AgentControllerTest,UserEditControllerTest,UserEditPasswordLinkTest,RollerViewResolverTest,JspConsistencyTest,AdminJspHygieneTest,AgentAccountsJspTest,AgentAccountMessagesTest,MessageKeyTest,MessagePlaceholderContractTest,MessageArgumentEscapingTest,EditorJspEscapingTest,RouteCoverageTest,ReaderRefusalSweepTest,ControllerMetadataTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AgentBean.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AgentController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java \
        app/src/main/resources/org/apache/roller/weblogger/ui/menu/admin-menu.xml \
        app/src/main/webapp/WEB-INF/jsps/admin/AgentCreate.jsp app/src/main/webapp/WEB-INF/jsps/admin/Agent.jsp \
        app/src/main/resources/ApplicationResources*.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/AgentControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java \
        it-selenium/src/test/java/org/apache/roller/it/support/Routes.java \
        it-selenium/src/test/resources/seed-it-data.sql
git commit -m "feat(admin): agent accounts -- New agent, and an agent page with details, Disable, weblogs and tokens"
```

---

### Task 15: Admin → API tokens

**Model:** haiku

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/TokenRow.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AdminTokensController.java`
- Create: `app/src/main/webapp/WEB-INF/jsps/admin/ApiTokens.jsp`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java` (one view)
- Modify: `app/src/main/resources/org/apache/roller/weblogger/ui/menu/admin-menu.xml` (one item)
- Modify: the 8 `app/src/main/resources/ApplicationResources*.properties`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java` (one route)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/AdminTokensControllerTest.java` (create)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java`, `app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java`

**Interfaces:**
- Consumes: Task 2's `getAllTokens(boolean)`, `getToken`, `revoke(ApiToken)`; Task 13's `TokenFormController.revokeToken(User, String, User)`.
- Produces:
  - `TokenRow` (public): `ApiToken getToken()`, `String getIssuedBy()` (username, or null), `boolean isLive()`; package-private `static List<TokenRow> load(Weblogger, boolean includeInactive)`.
  - GET `/roller-ui/admin/apiTokens.rol?all=true|` (`#apiTokensPage`, `#apiTokensTable`, `#apiTokensToggle`); POST `apiTokens!revoke.rol` (`id`, `all`).
  - Admin menu item `apiTokens` (`tabbedmenu.admin.apiTokens`).

- [ ] **Step 1: Write the failing tests**

Create `AdminTokensControllerTest.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin → API tokens (spec 2026-10-13, section 3; criteria 13 and 14). */
class AdminTokensControllerTest {

    private MockWeblogger weblogger;
    private AdminTokensController controller;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private User admin;
    private User jake;
    private User bot;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new AdminTokensController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        admin = account("admin", User.Kind.PERSON);
        jake = account("jake", User.Kind.PERSON);
        bot = account("bot", User.Kind.AGENT);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setKind(kind);
        return user;
    }

    private static ApiToken token(String id, User owner, String issuedBy) {
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setUser(owner);
        token.setLabel("label-" + id);
        token.setScopeRole(ApiToken.Role.POST);
        token.setIssuedBy(issuedBy);
        return token;
    }

    private HttpServletRequest asAdmin() {
        return ControllerTestFixture.requestFor(admin);
    }

    @SuppressWarnings("unchecked")
    private List<TokenRow> rows() {
        return (List<TokenRow>) model.getAttribute("tokenRows");
    }

    @Test
    void thePageIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("apiTokens", controller.getActionName());
    }

    /** Criterion 13: every live token of people and agents, with owner, kind and issuer. */
    @Test
    void listsEveryLiveTokenWithOwnerKindAndIssuer() throws Exception {
        ApiToken personToken = token("t1", jake, jake.getId());
        ApiToken agentToken = token("t2", bot, admin.getId());
        ApiToken legacy = token("t3", jake, null);
        when(weblogger.apiTokenManager().getAllTokens(false)).thenReturn(List.of(personToken, agentToken, legacy));
        when(weblogger.userManager().getUser(jake.getId())).thenReturn(jake);
        when(weblogger.userManager().getUser(admin.getId())).thenReturn(admin);

        String view = controller.list(asAdmin(), model, null);

        assertEquals(".ApiTokens", view);
        assertEquals(Boolean.FALSE, model.getAttribute("showAll"));
        List<TokenRow> rows = rows();
        assertEquals(3, rows.size());
        assertSame(personToken, rows.get(0).getToken());
        assertEquals("jake", rows.get(0).getIssuedBy());
        assertTrue(rows.get(1).getToken().getUser().isAgent());
        assertEquals("admin", rows.get(1).getIssuedBy());
        assertNull(rows.get(2).getIssuedBy(), "a token from before V033 has no issuer");
    }

    /** Criterion 13: the toggle adds revoked and expired tokens. */
    @Test
    void theToggleAddsRevokedAndExpiredTokens() throws Exception {
        controller.list(asAdmin(), model, "true");

        verify(weblogger.apiTokenManager()).getAllTokens(true);
        assertEquals(Boolean.TRUE, model.getAttribute("showAll"));
    }

    @Test
    void aStoreThatCannotListShowsNoRows() throws Exception {
        when(weblogger.apiTokenManager().getAllTokens(false)).thenThrow(new WebloggerException("db down"));

        assertEquals(".ApiTokens", controller.list(asAdmin(), model, null));
        assertTrue(rows().isEmpty());
    }

    /** Criterion 14: a site admin can revoke any token, and the revocation is logged. */
    @Test
    void aSiteAdminRevokesAnyToken() throws Exception {
        ApiToken jakes = token("t1", jake, jake.getId());
        when(weblogger.apiTokenManager().getToken("t1")).thenReturn(jakes);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.revoke(asAdmin(), redirect, "t1", null);

            assertEquals("redirect:/roller-ui/admin/apiTokens.rol", view);
            verify(weblogger.apiTokenManager()).revoke(jakes);
            assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(1, log.lines().size());
            assertTrue(log.lines().get(0).startsWith("token.revoked id=t1 label=label-t1 owner=jake"),
                    log.lines().get(0));
            assertTrue(log.lines().get(0).endsWith("by=admin"), log.lines().get(0));
        }
    }

    @Test
    void revokingKeepsTheToggle() throws Exception {
        when(weblogger.apiTokenManager().getToken("t1")).thenReturn(token("t1", jake, null));

        controller.revoke(asAdmin(), redirect, "t1", "true");

        assertEquals("true", redirect.asMap().get("all"));
    }

    @Test
    void anUnknownIdIsNoSuchToken() throws Exception {
        controller.revoke(asAdmin(), redirect, "nosuch", null);

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.apiTokenManager(), never()).revoke(any(ApiToken.class));
    }
}
```

In `RollerViewResolverTest`, change `case ".Comments", ".Submissions", ".Agent", ".AgentCreate" -> ` to `case ".Comments", ".Submissions", ".Agent", ".AgentCreate", ".ApiTokens" -> ` and add:

```java
    @Test
    void theSiteTokenListRendersThroughTheTabbedLayout() throws Exception {
        resolver.init();

        assertEquals("/WEB-INF/jsps/admin/ApiTokens.jsp", renderAndCapture(".ApiTokens").getAttribute("tile_content"));
    }
```

Add to `AgentAccountsJspTest`:

```java
    /** Criteria 14 and 25: a site admin's Revoke asks first. */
    @Test
    void revokingFromTheSiteListIsConfirmed() throws IOException {
        assertTrue(formPostingTo(jsp("admin/ApiTokens.jsp"), "apiTokens!revoke.rol").contains("data-confirm="));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='AdminTokensControllerTest,RollerViewResolverTest,AgentAccountsJspTest'`
Expected: compilation failure (`AdminTokensController`, `TokenRow` do not exist).

- [ ] **Step 3: Implement**

Create `TokenRow.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One row of Admin → API tokens: a token, and the username of whoever issued it. */
public final class TokenRow {

    private static final Logger log = LoggerFactory.getLogger(TokenRow.class);

    private final ApiToken token;
    private final String issuedBy;

    TokenRow(ApiToken token, String issuedBy) {
        this.token = token;
        this.issuedBy = issuedBy;
    }

    public ApiToken getToken() {
        return token;
    }

    /** The issuer's username; the stored id if that account is gone; null before V033. */
    public String getIssuedBy() {
        return issuedBy;
    }

    /** Neither revoked nor expired. */
    public boolean isLive() {
        return token.isUsable();
    }

    /**
     * Every token on the site, newest first, only live ones unless
     * {@code includeInactive}. A display path: a store that cannot list shows none.
     */
    static List<TokenRow> load(Weblogger weblogger, boolean includeInactive) {
        try {
            Map<String, String> issuers = new HashMap<>();
            List<TokenRow> rows = new ArrayList<>();
            for (ApiToken token : weblogger.getApiTokenManager().getAllTokens(includeInactive)) {
                rows.add(new TokenRow(token, issuerName(weblogger, issuers, token.getIssuedBy())));
            }
            return rows;
        } catch (WebloggerException e) {
            log.error("Could not list the API tokens", e);
            return List.of();
        }
    }

    private static String issuerName(Weblogger weblogger, Map<String, String> issuers, String userId)
            throws WebloggerException {
        if (userId == null) {
            return null;
        }
        String name = issuers.get(userId);
        if (name == null) {
            User issuer = weblogger.getUserManager().getUser(userId);
            name = issuer == null ? userId : issuer.getUserName();
            issuers.put(userId, name);
        }
        return name;
    }
}
```

Create `AdminTokensController.java`:

```java
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.ui.controllers.TokenFormController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin → API tokens (spec 2026-10-13, section 3; criteria 13 and 14): every
 * live token on the site, people's and agents', with owner, kind and issuer;
 * a toggle adds revoked and expired ones; a site admin revokes any of them.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class AdminTokensController extends TokenFormController {

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "apiTokens";
    }

    @Override
    public String getPageTitle() {
        return "apiTokens.admin.title";
    }

    /** @param all {@code true} to add revoked and expired tokens */
    @GetMapping("/apiTokens.rol")
    public String list(HttpServletRequest request, Model model,
                       @RequestParam(name = "all", required = false) String all) {
        populateCommonModel(request, model);
        boolean includeInactive = "true".equals(all);
        model.addAttribute("showAll", includeInactive);
        model.addAttribute("tokenRows", TokenRow.load(weblogger, includeInactive));
        return ".ApiTokens";
    }

    @PostMapping("/apiTokens!revoke.rol")
    public String revoke(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "id", required = false) String id,
                         @RequestParam(name = "all", required = false) String all) {
        if (revokeToken(getAuthenticatedUser(request), id, null)) {
            addFlashMessage(redirectAttributes, "apiTokens.revoked", request);
        } else {
            addFlashError(redirectAttributes, "apiTokens.error.noSuchToken", request);
        }
        if ("true".equals(all)) {
            redirectAttributes.addAttribute("all", "true");
        }
        return "redirect:/roller-ui/admin/apiTokens.rol";
    }
}
```

Create `app/src/main/webapp/WEB-INF/jsps/admin/ApiTokens.jsp` (licence header block first, then):

```jsp
<%@ include file="/WEB-INF/jsps/taglibs-spring.jsp" %>

<%-- Admin -> API tokens (spec 2026-10-13, section 3; criterion 13): every live
     token on the site; the toggle adds revoked and expired ones. Revoke asks
     first. RouteSweepIT finds this page by #apiTokensPage. --%>
<div id="apiTokensPage">

<p class="subtitle"><spring:message code="apiTokens.admin.subtitle"/></p>

<c:choose>
    <c:when test="${showAll}">
        <c:url var="toggleUrl" value="/roller-ui/admin/apiTokens.rol"/>
        <p><a href="${toggleUrl}" id="apiTokensToggle"><spring:message code="apiTokens.admin.showLive"/></a></p>
    </c:when>
    <c:otherwise>
        <c:url var="toggleUrl" value="/roller-ui/admin/apiTokens.rol">
            <c:param name="all" value="true"/>
        </c:url>
        <p><a href="${toggleUrl}" id="apiTokensToggle"><spring:message code="apiTokens.admin.showAll"/></a></p>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${not empty tokenRows}">
        <spring:message code="apiTokens.revoke.confirm" var="revokeConfirm"/>
        <table class="rollertable table" id="apiTokensTable">
            <tr>
                <th scope="col"><spring:message code="apiTokens.owner"/></th>
                <th scope="col"><spring:message code="userList.column.kind"/></th>
                <th scope="col"><spring:message code="apiTokens.label"/></th>
                <th scope="col"><spring:message code="apiTokens.role"/></th>
                <th scope="col"><spring:message code="apiTokens.weblog"/></th>
                <th scope="col"><spring:message code="apiTokens.issuedBy"/></th>
                <th scope="col"><spring:message code="apiTokens.created"/></th>
                <th scope="col"><spring:message code="apiTokens.lastUsed"/></th>
                <th scope="col"><spring:message code="apiTokens.expiry"/></th>
                <th scope="col"><spring:message code="apiTokens.state"/></th>
            </tr>
            <c:forEach var="row" items="${tokenRows}">
                <tr>
                    <td>${fn:escapeXml(row.token.user.userName)}</td>
                    <td>
                        <c:choose>
                            <c:when test="${row.token.user.agent}"><spring:message code="userList.kind.agent"/></c:when>
                            <c:otherwise><spring:message code="userList.kind.person"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>${fn:escapeXml(row.token.label)}</td>
                    <td>
                        <c:choose>
                            <c:when test="${row.token.scopeRole == 'READ'}"><spring:message code="apiTokens.role.read"/></c:when>
                            <c:when test="${row.token.scopeRole == 'ADMIN'}"><spring:message code="apiTokens.role.admin"/></c:when>
                            <c:otherwise><spring:message code="apiTokens.role.write"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${empty row.token.scopeWeblog}"><spring:message code="apiTokens.weblog.all"/></c:when>
                            <c:otherwise><span class="data">${fn:escapeXml(row.token.scopeWeblog)}</span></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="data">${fn:escapeXml(row.issuedBy)}</td>
                    <td class="data"><rc:date value="${row.token.created}"/></td>
                    <td class="data"><rc:date value="${row.token.lastUsedAt}"/></td>
                    <td class="data">
                        <c:choose>
                            <c:when test="${empty row.token.expiresAt}"><spring:message code="apiTokens.expiry.never"/></c:when>
                            <c:otherwise><rc:date value="${row.token.expiresAt}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td>
                        <c:choose>
                            <c:when test="${not empty row.token.revokedAt}">
                                <spring:message code="apiTokens.state.revoked"/> <rc:date value="${row.token.revokedAt}"/>
                            </c:when>
                            <c:when test="${not row.live}">
                                <spring:message code="apiTokens.state.expired"/>
                            </c:when>
                            <c:otherwise>
                                <form method="post" action="<c:url value='/roller-ui/admin/apiTokens!revoke.rol'/>">
                                    <sec:csrfInput/>
                                    <input type="hidden" name="id" value="${fn:escapeXml(row.token.id)}"/>
                                    <c:if test="${showAll}"><input type="hidden" name="all" value="true"/></c:if>
                                    <button type="submit" class="btn btn-secondary btn-sm" data-confirm="${revokeConfirm}">
                                        <spring:message code="apiTokens.revoke"/>
                                    </button>
                                </form>
                            </c:otherwise>
                        </c:choose>
                    </td>
                </tr>
            </c:forEach>
        </table>
    </c:when>
    <c:otherwise>
        <div class="empty-state">
            <p class="empty-state-title"><spring:message code="apiTokens.admin.none"/></p>
        </div>
    </c:otherwise>
</c:choose>

</div>
```

In `RollerViewResolver.init()`, after the `.Agent` definition:

```java
        addExtendedDefinition(".ApiTokens", ".tiles-tabbedpage",
                Map.of("content", "/WEB-INF/jsps/admin/ApiTokens.jsp"));
```

In `admin-menu.xml`, after the `businesses` menu-item:

```xml
        <!-- globalPerms="admin"; every API token on the site (spec 2026-10-13) -->
        <menu-item action="apiTokens"
                   name="tabbedmenu.admin.apiTokens" />
```

Messages. Append this block to all 8 bundles with the loop:

```properties
tabbedmenu.admin.apiTokens=API tokens
apiTokens.admin.title=API tokens
apiTokens.admin.subtitle=Every live token on this site. A revoked token stops working on its next request.
apiTokens.admin.showAll=Show revoked and expired
apiTokens.admin.showLive=Show live tokens only
apiTokens.admin.none=No tokens to show.
apiTokens.owner=Owner
apiTokens.issuedBy=Issued by
apiTokens.state=State
apiTokens.state.revoked=Revoked
apiTokens.state.expired=Expired
```

In `Routes.java`, after the `/roller-ui/admin/businesses!edit.rol` entry:

```java
            // Admin -> API tokens (spec 2026-10-13): every live token on the site.
            new Route("/roller-ui/admin/apiTokens.rol", Role.ADMIN, "", "#apiTokensPage"),
```

Run: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests` — expected BUILD SUCCESS.

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='AdminTokensControllerTest,RollerViewResolverTest,AgentAccountsJspTest,AgentAccountMessagesTest,MessageKeyTest,JspConsistencyTest,AdminJspHygieneTest,RouteCoverageTest,ReaderRefusalSweepTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/TokenRow.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/AdminTokensController.java \
        app/src/main/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolver.java \
        app/src/main/resources/org/apache/roller/weblogger/ui/menu/admin-menu.xml \
        app/src/main/webapp/WEB-INF/jsps/admin/ApiTokens.jsp \
        app/src/main/resources/ApplicationResources*.properties \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/admin/AdminTokensControllerTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/controllers/RollerViewResolverTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/AgentAccountsJspTest.java \
        it-selenium/src/test/java/org/apache/roller/it/support/Routes.java
git commit -m "feat(admin): Admin -> API tokens lists every token with owner, kind and issuer; a site admin revokes any"
```

---
### Task 16: The API reports `kind` and never changes an agent; the MCP instructions say so

**Model:** haiku

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/AdminAccountGuard.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApi.java` (`patchUser`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java` (`UserView`, `toView`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java` (`me`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructions.java`
- Test: `AdminAccountGuardTest`, `AdminApiTest`, `MetaApiTest`, `McpInstructionsTest` (add tests)

**Interfaces:**
- Consumes: Task 1's `User.getKind()`, `isAgent()`.
- Produces: `AdminAccountGuard.AGENT_DETAIL = "Agent accounts are managed in the admin UI."`; `static void AdminAccountGuard.requireNotAgent(User)` (403); `AdminDtos.UserView` gains a last component `String kind`; `GET /api/v1/me` gains `kind`.

- [ ] **Step 1: Write the failing tests**

Add to `AdminAccountGuardTest`:

```java
    /** Spec 2026-10-13, section 5: agent accounts are managed in the admin UI only. */
    @Test
    void anAgentAccountIsRefusedWith403() {
        User agent = new User();
        agent.setKind(User.Kind.AGENT);

        ApiException ex = assertThrows(ApiException.class, () -> AdminAccountGuard.requireNotAgent(agent));

        assertEquals(403, ex.getStatus());
        assertEquals("Agent accounts are managed in the admin UI.", ex.getMessage());
    }

    @Test
    void aPersonPassesTheAgentGuard() {
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAgent(new User()));
    }
```

Add to `AdminApiTest`:

```java
    /** Criterion 21: PATCH on an agent is a 403 naming the admin UI, and changes nothing. */
    @Test
    void patchingAnAgentAccountIsRefusedAndChangesNothing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User agent = aUser("bot");
        agent.setKind(User.Kind.AGENT);
        when(weblogger.getUserManager().getUserByUserName("bot", null)).thenReturn(agent);

        String problem = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/admin/users/{userName}", "bot")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertEquals("Agent accounts are managed in the admin UI.",
                new tools.jackson.databind.ObjectMapper().readTree(problem).get("detail").asString());
        assertEquals(Boolean.TRUE, agent.getEnabled());
        verify(weblogger.getUserManager(), never()).saveUser(any());
    }

    /** Criterion 22: the users list reports each account's kind. */
    @Test
    void theUserListReportsEachAccountsKind() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User person = aUser("alice");
        User agent = aUser("bot");
        agent.setKind(User.Kind.AGENT);
        when(weblogger.getUserManager().getUsers(null, null, null, 0, 50)).thenReturn(List.of(person, agent));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/admin/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("PERSON", json.get(0).get("kind").asString());
        assertEquals("AGENT", json.get(1).get("kind").asString());
    }

    /** Spec 2026-10-13, section 6: enabling or disabling an account through the API is a roller.admin event too. */
    @Test
    void disablingThroughTheApiIsLogged() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        User user = aUser("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(user);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            mockMvc(controllerFor(weblogger))
                    .perform(patch("/v1/admin/users/{userName}", "alice")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")
                            .requestAttr("authenticatedUser", aUser("boss")))
                    .andExpect(status().isOk());

            assertEquals(List.of("account.disabled account=alice by=boss"), log.lines());
        }
    }
```

(Import `org.apache.roller.testing.AdminLogCapture` in `AdminApiTest`.)

Add to `MetaApiTest`:

```java
    /** Criterion 22: /me reports whether the caller is a person or an agent. */
    @Test
    void meReportsTheAccountKind() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        assertEquals("PERSON", me().get("kind").asString());

        user.setKind(User.Kind.AGENT);
        assertEquals("AGENT", me().get("kind").asString());
    }
```

In `McpInstructionsTest`, add `"Agent accounts"` and `"ask your person"` to the array of required points.

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -o -q -pl app test -Dtest='AdminAccountGuardTest,AdminApiTest,MetaApiTest,McpInstructionsTest'`
Expected: compilation failure (`requireNotAgent` does not exist); after it compiles, the `kind` and instruction assertions fail.

- [ ] **Step 3: Implement**

In `AdminAccountGuard.java`, after `DETAIL`:

```java
    /** What an API route that would change an agent account answers (spec 2026-10-13, section 5). */
    public static final String AGENT_DETAIL = "Agent accounts are managed in the admin UI.";

    /**
     * Agents' identity and credentials stay with people: no API route may
     * change an agent account, and so neither may MCP, a facade over the API.
     * Checked before {@link #requireNotAdmin}, so a site-admin agent is
     * refused as an agent.
     */
    public static void requireNotAgent(User user) {
        if (user.isAgent()) {
            throw ApiException.forbidden(AGENT_DETAIL);
        }
    }
```

In `AdminApi.patchUser`:
- give it a first parameter `HttpServletRequest request` (import `jakarta.servlet.http.HttpServletRequest` if missing; `MetaApi.me` shows the MCP argument deriver already ignores it);
- immediately before `AdminAccountGuard.requireNotAdmin(mgr, user);` add `AdminAccountGuard.requireNotAgent(user);`;
- right after the two guards add `boolean enabledBefore = Boolean.TRUE.equals(user.getEnabled());`;
- after its `weblogger.flush();` add (import `org.apache.roller.weblogger.ui.controllers.AdminLog`):

```java
        boolean enabledNow = Boolean.TRUE.equals(user.getEnabled());
        if (enabledBefore != enabledNow) {
            AdminLog.record(enabledNow ? "account.enabled" : "account.disabled",
                    (User) request.getAttribute("authenticatedUser"), "account=" + user.getUserName());
        }
```

In `AdminDtos.java`, make `UserView`

```java
    public record UserView(String userName, String screenName, String emailAddress,
                            boolean enabled, List<String> globalRoles, boolean sso, String kind) {
    }
```

and in `toView(User, List<String>)` pass `user.isSsoBound(), user.getKind().name()` as the last two arguments.

In `MetaApi.me`, after `body.put("screenName", user.getScreenName());` add `body.put("kind", user.getKind().name());`.

In `McpInstructions.TEXT`, replace the two lines

```
            - Admin accounts and the admin role are managed only in the admin UI; these tools cannot \
            change them.
```

with

```
            - Admin accounts and the admin role are managed only in the admin UI; these tools cannot \
            change them. Agent accounts, weblog memberships and tokens are managed by a person in the \
            admin UI too: to reach another weblog, ask your person for access.
```

- [ ] **Step 4: Run them and watch them pass**

Run: `mvn -o -q -pl app test -Dtest='AdminAccountGuardTest,AdminApiTest,AdminApiBranchesTest,MetaApiTest,McpInstructionsTest,McpToolCatalogTest,OpenApiDocumentTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (`McpInstructionsTest` also holds the text under 3000 characters).

- [ ] **Step 5: Full verify and commit**

Run: `mvn -o -pl app verify` — expected BUILD SUCCESS.

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/restapi/AdminAccountGuard.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApi.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApi.java \
        app/src/main/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructions.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/AdminAccountGuardTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/AdminApiTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MetaApiTest.java \
        app/src/test/java/org/apache/roller/weblogger/ui/restapi/mcp/McpInstructionsTest.java
git commit -m "feat(api): the API reports kind and refuses to change an agent; MCP instructions point to a person"
```

---

### Task 17: Documentation

**Model:** haiku

**Files:**
- Modify: `docs/api/README.md`, `docs/dev/api.md`, `docs/dev/admin-ui.md`

**Interfaces:** none (prose; `OpenApiDocumentTest` and `McpDocsTest` pin parts of the README).

- [ ] **Step 1: `docs/api/README.md`**

1. Replace the line `There is **no web UI for minting a token**. The only route in is the CLI:` with:

```markdown
Mint a token in the admin UI: **Profile → API tokens** for yourself, or an
agent's page (**Admin → Users →** the agent) for an agent account. The secret
is shown once. A script can mint one with the CLI instead:
```

2. Directly under the `## Getting a token` heading, before the paragraph that begins ``` `POST /api/v1/tokens` (mint) ```, insert:

```markdown
### In the admin UI

**Profile → API tokens** mints, lists and revokes your own tokens. A site
admin does the same for an agent on the agent's page, and sees every token on
the site under **Admin → API tokens** (owner, kind and issuer; a toggle adds
revoked and expired ones). The form takes a label, a role (Read, Write or
Admin: `READ`, `POST`, `ADMIN`), a weblog (all of the account's weblogs, or
one it is a member of; a site admin may pick any) and an expiry (30, 90 or
365 days, or never; 90 by default). The next page shows the secret once, with
an MCP snippet to copy. It is never cached, and loading it again only says
the token was already shown.

### Agent accounts

An agent is an account for software. It never signs in -- no password, no
password reset, no single sign-on -- and works only through the tokens a site
admin mints for it. A site admin creates it under **Admin → Users → New
agent**, adds it to weblogs on its page, and may switch on **Site
administrator**, which lets an `ADMIN` token from it use the site-wide admin
API. Disabling an account, agent or person, stops every token it holds on the
next request. Agent accounts, memberships and tokens are managed only in the
admin UI: no API route creates or changes an agent, or mints a token for one.

### With the API

```

3. After the paragraph that ends ``` (`DELETE /api/v1/tokens/{id}`, Basic-auth only, same as list/issue). ```, add:

```markdown

`weblog` must name a weblog you are a member of (a site admin may name any
existing weblog); a foreign or unknown weblog is a 400 whose
`errors[0].field` is `weblog`. `expiresAt` must be in the future, or it is a
400 on `expiresAt`. `GET /api/v1/tokens` lists revoked tokens too, each with
its `revokedAt`.
```

4. In `## Token scopes`, after the sentence that ends `since listing` / `` `/api/v1/weblogs` needs the admin role.``, insert the sentence: `` `GET /api/v1/me` also reports the account's `kind`: `PERSON` or `AGENT`. ``
5. In `## Site administration`, after the fenced block of endpoints, add:

```markdown
Each user in `GET /api/v1/admin/users` carries `kind`, `PERSON` or `AGENT`.
`POST /api/v1/admin/users` creates people only. `PATCH
/api/v1/admin/users/{userName}` on an agent answers 403 `Agent accounts are
managed in the admin UI.`, as it does on an admin.
```

6. In `## MCP`, after the bullet that begins `- **Admin accounts are read-only through the API**`, add:

```markdown
- **Agent accounts are read-only through the API**, and so through MCP: `PATCH
  /api/v1/admin/users/{userName}` (the `users` tool's `update`) on an agent answers 403 `Agent accounts
  are managed in the admin UI.` Weblog memberships and tokens are managed by a person in the admin UI.
```

- [ ] **Step 2: `docs/dev/api.md`**

1. Replace

```markdown
`docs/api/README.md`, the front door, since no UI mints a token
(`bin/roller-api auth login` is the only route in). `/api/v1` is unstable while
Roller is 0.x.
```

with

```markdown
`docs/api/README.md`, the front door. Tokens are minted in the admin UI
(Profile → API tokens, or an agent's page) or with `bin/roller-api auth login`.
`/api/v1` is unstable while Roller is 0.x.
```

2. At the end of the `AdminAccountGuard is mandatory` bullet, add: ``Agent accounts likewise: `requireNotAgent` answers 403 `Agent accounts are managed in the admin UI.`, and `patchUser` calls it before `requireNotAdmin`.``
3. Append:

```markdown
## Agent accounts (2026-10-13)

Spec `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`.

- **An agent is a `roller_user` with `kind = AGENT` (V033)**: no password (`passphrase` is nullable
  since V033), the `editor` role plus `admin` when its Site administrator switch is on, created only
  by `UserManager.addAgent`. Memberships, permissions and tokens are the people's machinery.
- **Every sign-in gate refuses an agent, explicitly**: `RollerUserDetailsService` (form login,
  remember-me and HTTP Basic all resolve accounts there), `ReaderAccounts` (SSO),
  `JPAUserTokenManagerImpl.issueToken`/`validate` and `PasswordResetController.findUser` (reset and
  set-password links). `ApiTokenAuthFilter` refuses a token whose owner is disabled, person or agent,
  without relying on the enabled-only user lookup downstream.
- **`TokenPins` is the one pin rule** (a member pins their own weblogs, a site admin any existing
  one), shared by `TokensApi` and the token form; `issueToken` records `issued_by`, the owner for a
  self-mint.
- **Every account, membership and token change writes one INFO line through `AdminLog`** (logger
  `roller.admin`, `by=` the actor). A token is named by `AdminLog.tokenDetails`, which leaves the
  secret out by construction; a controller never holds a second logger (`MoreThanOneLogger`).
- **The API never changes an agent** (`AdminAccountGuard.requireNotAgent`); `kind` is reported by
  `/me` and the admin users list.
```

- [ ] **Step 3: `docs/dev/admin-ui.md`**

1. Replace the bullet that begins ``- `roller-ui/scripts/ajax-user.js` is pulled in with `<%@ include %>` `` (five lines, through ``adds collaborators directly).``) with:

```markdown
- **Admin → Users is a list, not a search box** (2026-10-13): every account,
  All / People / Agents tabs and a `q` filter the server applies
  (`UserManager.getAccounts`, case-insensitive over username, names and email).
  `ajax-user.js` went with the search box; `UserDataServlet` has no page
  consumer left. Every controller that lands on `.UserAdmin` builds the rows
  through `AccountRows.showList`, or the page renders with none.
```

2. Append:

```markdown
## Agent accounts and API tokens

Spec `docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`; cards
`docs/design/tables/tables-users.html`, `forms/forms-agent-page.html`, `forms/forms-token-reveal.html`.

- **The agent page** (`AgentController`, `/roller-ui/admin/agent.rol?userName=`): details, one
  `data-confirm` Disable/Enable, the Weblogs section and the token section. No password field and no
  set-password link; `modifyUser.rol` sends an agent there, and the person editor's save and
  send-link actions refuse one (`agent.error.notAPerson`).
- **The Weblogs section** (`UserWeblogs.jsp` + `UserWeblogsController`) is on a person's page and an
  agent's page alike, with the Members page's rules: a weblog keeps one Admin, nothing is granted
  while `groupblogging.enabled` is off (the section says so), a reader is never granted. Its weblog
  field is `handle` and the token form's is `pin`: a field named `weblog` is read by
  `RollerHandlerInterceptor` as the action weblog.
- **The token form, reveal and revoke live in `TokenFormController`**, the abstract base of
  `ProfileTokensController`, `AgentController` and `AdminTokensController`. The secret reaches
  `profileTokens!reveal.rol` only as a one-read flash attribute (`REVEALED_TOKEN`), never in a URL;
  the reveal answers `Cache-Control: no-store`, and a reload says "already shown". Spring logs a
  FlashMap's contents at TRACE and a response body at DEBUG: keep `org.springframework` at INFO in
  production.
- **The last sign-in** is stamped by `LastLoginRecorder`, an `InteractiveAuthenticationSuccessEvent`
  listener: form login, remember-me and SSO publish that event and HTTP Basic does not, so an API call
  is not a sign-in. An agent's "last active" is its newest token use instead.
- **New message keys go into all eight bundles** (English until translated);
  `AgentAccountMessagesTest` checks the `userList.`, `personForm.`, `userWeblogs.`, `apiTokens.` and
  `agent.` prefixes.
```

- [ ] **Step 4: Check and commit**

Run: `mvn -o -q -pl app test -Dtest='OpenApiDocumentTest,McpDocsTest'` — expected PASS (the README still names `roller-api auth login`, `./roller token`, `.roller-dev-secret` and "unstable", and the MCP tool table is unchanged).

```bash
git add docs/api/README.md docs/dev/api.md docs/dev/admin-ui.md
git commit -m "docs: agent accounts, the token UI, the mint checks and the agent guard"
```

---

### Task 18: Browser ITs: the bootstrap, and agents on the real WAR

**Model:** sonnet — drives the real WAR through the browser and the API at once, where the waits and fixtures need judgment ("A click is not a barrier").

**Files:**
- Create: `it-selenium/src/test/java/org/apache/roller/it/AgentAccountsIT.java`

**Interfaces:** consumes everything above through the packaged WAR: ids from Tasks 10-15, `GET /api/v1/me` `kind`, MCP `whoami`, `entries`, `users`.

- [ ] **Step 1: Write the ITs**

Create `AgentAccountsIT.java`:

```java
package org.apache.roller.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Agent accounts and the token UI on the packaged WAR (spec 2026-10-13;
 * criteria 4, 7, 20, 21, 22, 23 and 24), at whichever context path the suite
 * runs. MockMvc runs no real transaction, filter chain or flash redirect;
 * this class does. Holds GLOBAL_CONFIG for READ: granting a membership needs
 * groupblogging.enabled, which GlobalConfigMatrixIT turns off.
 */
@ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class AgentAccountsIT extends RollerIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Criterion 24: an admin creates an agent, adds it to a weblog as Author
     * and mints a Write token pinned to that weblog; the token answers whoami
     * as an agent on that weblog and creates an entry there; once revoked,
     * its next call is a 401.
     */
    @Test
    void anAdminBootstrapsAnAgentThatPublishesThroughMcpUntilItsTokenIsRevoked() throws Exception {
        String agent = "itagent" + nonce();
        loginAsAdmin();
        createAgent(agent, false);

        $("#userWeblogsAddHandle").selectOptionByValue(WEBLOG_HANDLE);
        $("#userWeblogsAddLevel").selectOptionByValue("post");
        clickAndAwaitNewPage($("#userWeblogsAddButton"));
        $("#userWeblogsTable").shouldHave(text(WEBLOG_HANDLE));

        String secret = mintOnThisPage("bootstrap", "POST", WEBLOG_HANDLE);
        String snippet = $("#mcpSnippet").getValue();
        assertTrue(snippet.contains("/api/mcp"), snippet);
        assertTrue(snippet.contains("Authorization: Bearer " + secret), snippet);

        // Criterion 10 in the browser: the reveal shows the secret once.
        Selenide.refresh();
        BrowserHealth.current().settle();
        $("#tokenAlreadyShown").should(exist);
        assertTrue($$("#revealedToken").isEmpty(), "a second visit must not show the secret");

        String entryId = null;
        try {
            JsonNode who = JSON.readTree(toolText(callTool(secret, "whoami", Map.of())));
            assertEquals("AGENT", who.get("kind").asString(), who.toString());
            assertEquals(agent, who.get("userName").asString(), who.toString());
            assertEquals(1, who.get("weblogs").size(), who.toString());
            assertEquals(WEBLOG_HANDLE, who.get("weblogs").get(0).get("handle").asString());

            JsonNode created = callTool(secret, "entries", Map.of("action", "create", "weblog", WEBLOG_HANDLE,
                    "title", "AgentAccountsIT " + agent, "text", "<p>Written by an agent.</p>"));
            assertFalse(isError(created), created.toString());
            entryId = JSON.readTree(toolText(created)).get("id").asString();
        } finally {
            if (entryId != null) {
                adminRest("DELETE", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId);
                adminRest("POST", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId + "/delete-forever");
            }
        }

        openAgentPage(agent);
        revokeTheTokenOnThisPage();
        assertEquals(401, mcpPost(secret, rpcBody("tools/call",
                Map.of("name", "whoami", "arguments", Map.of()))).statusCode());

        // Leave the shared weblog as it was.
        awaitNewPageAfter(() -> {
            $("#userWeblogsTable button[data-confirm]").click();
            Selenide.confirm();
        });
        $("#userWeblogsTable").shouldNot(exist);
        logout();
    }

    /** Criterion 4: HTTP Basic as an agent is a 401 on /api/v1/me. */
    @Test
    void httpBasicAsAnAgentIs401() throws Exception {
        String agent = "itbasic" + nonce();
        loginAsAdmin();
        createAgent(agent, false);
        logout();

        HttpResponse<String> me = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/me"))
                .header("Authorization", basic(agent, "any-password-at-all")).GET());

        assertEquals(401, me.statusCode(), me.body());
    }

    /**
     * Criterion 7 and review focus 2: disabling an agent stops its token on
     * the next request, over REST and over MCP; enabling it makes the same
     * token work again -- on the running app, with whatever it caches.
     */
    @Test
    void disablingAnAgentStopsItsTokenAndEnablingRestoresIt() throws Exception {
        String agent = "itdisable" + nonce();
        loginAsAdmin();
        createAgent(agent, false);
        String secret = mintOnThisPage("disable-check", "READ", null);
        assertEquals(200, bearerGet(secret, "/api/v1/me").statusCode());

        openAgentPage(agent);
        awaitNewPageAfter(() -> {
            $("#agentDisableButton").click();
            Selenide.confirm();
        });
        $("#agentEnableButton").should(exist);
        assertEquals(401, bearerGet(secret, "/api/v1/me").statusCode(), "REST");
        assertEquals(401, mcpPost(secret, rpcBody("tools/call",
                Map.of("name", "whoami", "arguments", Map.of()))).statusCode(), "MCP");

        awaitNewPageAfter(() -> {
            $("#agentEnableButton").click();
            Selenide.confirm();
        });
        $("#agentDisableButton").should(exist);
        assertEquals(200, bearerGet(secret, "/api/v1/me").statusCode(), "the same token works again");

        revokeTheTokenOnThisPage();
        logout();
    }

    /** Criterion 23: a site-admin agent's ADMIN token reaches the admin API only while the switch is on. */
    @Test
    void aSiteAdminAgentReachesTheAdminApiOnlyWhileTheSwitchIsOn() throws Exception {
        String agent = "itsiteadmin" + nonce();
        loginAsAdmin();
        createAgent(agent, true);
        String secret = mintOnThisPage("site-admin", "ADMIN", null);
        assertEquals(200, bearerGet(secret, "/api/v1/admin/config").statusCode());

        openAgentPage(agent);
        $("#bean_siteAdmin").shouldBe(checked).click();
        clickAndAwaitNewPage($("#agentSaveButton"));
        $("#bean_siteAdmin").shouldNotBe(checked);
        assertEquals(403, bearerGet(secret, "/api/v1/admin/config").statusCode());

        revokeTheTokenOnThisPage();
        logout();
    }

    /** Criteria 21 and 22: the API reports an agent's kind and refuses to change it, over REST and MCP. */
    @Test
    void theApiReportsAnAgentButCannotChangeIt() throws Exception {
        String agent = "itpatch" + nonce();
        loginAsAdmin();
        createAgent(agent, false);
        logout();
        Token admin = mintAsAdmin("AgentAccountsIT admin");
        try {
            HttpResponse<String> patch = send(HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/api/v1/admin/users/" + agent))
                    .header("Authorization", "Bearer " + admin.raw())
                    .header("Content-Type", "application/json")
                    .method("PATCH", BodyPublishers.ofString("{\"enabled\":false}")));
            assertEquals(403, patch.statusCode(), patch.body());
            assertEquals("Agent accounts are managed in the admin UI.",
                    JSON.readTree(patch.body()).get("detail").asString());

            JsonNode viaMcp = callTool(admin.raw(), "users",
                    Map.of("action", "update", "userName", agent, "enabled", false));
            assertTrue(isError(viaMcp), viaMcp.toString());
            JsonNode problem = JSON.readTree(toolText(viaMcp));
            assertEquals(403, problem.get("status").asInt());
            assertEquals("Agent accounts are managed in the admin UI.", problem.get("detail").asString());

            JsonNode users = JSON.readTree(bearerGet(admin.raw(), "/api/v1/admin/users").body());
            String kind = null;
            for (JsonNode user : users) {
                if (agent.equals(user.get("userName").asString())) {
                    kind = user.get("kind").asString();
                }
            }
            assertEquals("AGENT", kind, "the newest account is on the first page, with its kind");
        } finally {
            revokeAsAdmin(admin.id());
        }
    }

    /** Criterion 20: a person's form sign-in shows on Admin → Users as their last active time. */
    @Test
    void aPersonsSignInShowsAsLastActiveOnTheUsersList() {
        String person = "itlast" + nonce();
        String password = "it-last-password";
        loginAsAdmin();
        openPath("/roller-ui/admin/createUser.rol");
        $("input[name='bean.userName']").should(visible).setValue(person);
        $("input[name='bean.screenName']").setValue("Screen " + person);
        $("input[name='bean.fullName']").setValue("Full " + person);
        if ($("#bean_passwordModeNow").exists()) {
            $("#bean_passwordModeNow").click();
        }
        $("input[name='bean.password']").setValue(password);
        $("input[name='bean.emailAddress']").setValue(person + "@example.invalid");
        clickAndAwaitNewPage($("#save_button"));

        openPath("/roller-ui/admin/userAdmin.rol?q=" + person);
        $("a[data-username='" + person + "']").closest("tr").find("time").shouldNot(exist);
        logout();

        loginAs(person, password);
        logout();

        loginAsAdmin();
        openPath("/roller-ui/admin/userAdmin.rol?q=" + person);
        $("a[data-username='" + person + "']").closest("tr").find("time").should(exist);
        logout();
    }

    // ---------------------------------------------------------------- helpers

    private void createAgent(String userName, boolean siteAdmin) {
        openPath("/roller-ui/admin/createAgent.rol");
        $("#bean_userName").should(visible).setValue(userName);
        $("#bean_name").setValue("IT Agent " + userName);
        if (siteAdmin) {
            $("#bean_siteAdmin").click();
        }
        clickAndAwaitNewPage($("#agentCreateButton"));
        $("#agentPage").should(exist);
    }

    private void openAgentPage(String userName) {
        openPath("/roller-ui/admin/agent.rol?userName=" + userName);
        $("#agentPage").should(exist);
    }

    /** Fills the token form on the page in view and returns the secret the reveal shows. */
    private String mintOnThisPage(String label, String role, String pin) {
        $("#tokenLabel").setValue(label);
        $("#tokenRole").selectOptionByValue(role);
        if (pin != null) {
            $("#tokenPin").selectOptionByValue(pin);
        }
        clickAndAwaitNewPage($("#tokenCreateButton"));
        return $("#revealedToken").should(visible).getValue();
    }

    private void revokeTheTokenOnThisPage() {
        awaitNewPageAfter(() -> {
            $("#tokenListTable button[data-confirm]").click();
            Selenide.confirm();
        });
    }

    record Token(String id, String raw) { }

    private Token mintAsAdmin(String label) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("role", "ADMIN");
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens"))
                .header("Authorization", basic(ADMIN_USERNAME, ADMIN_PASSWORD))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body))));
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        return new Token(json.get("info").get("id").asString(), json.get("token").asString());
    }

    private void revokeAsAdmin(String id) throws Exception {
        adminRest("DELETE", "/api/v1/tokens/" + id);
    }

    private HttpResponse<String> adminRest(String method, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", basic(ADMIN_USERNAME, ADMIN_PASSWORD))
                .method(method, BodyPublishers.noBody()));
    }

    private HttpResponse<String> bearerGet(String token, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token).GET());
    }

    private HttpResponse<String> mcpPost(String bearer, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + bearer)
                .POST(BodyPublishers.ofString(body)));
    }

    private JsonNode callTool(String bearer, String tool, Map<String, Object> arguments) throws Exception {
        HttpResponse<String> response = mcpPost(bearer, rpcBody("tools/call",
                Map.of("name", tool, "arguments", arguments)));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        if (json.has("error") || !json.has("result")) {
            fail("JSON-RPC error or no result: [" + response.body() + "]");
        }
        return json.get("result");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rpcBody(String method, Object params) throws Exception {
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", IDS.incrementAndGet());
        rpc.put("method", method);
        rpc.put("params", params);
        return JSON.writeValueAsString(rpc);
    }

    private static String toolText(JsonNode result) {
        return result.get("content").get(0).get("text").asString();
    }

    private static boolean isError(JsonNode result) {
        return result.path("isError").asBoolean(false);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(UTF_8));
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
```

The MCP-result helper is `toolText`, not `text`: a method named `text` in this class would shadow the statically imported `Condition.text(String)` for every call in it, and `shouldHave(text(WEBLOG_HANDLE))` would stop compiling.

- [ ] **Step 2: Compile it**

Run: `mvn -o -q -P it -pl it-selenium -am test-compile -DskipTests` — expected BUILD SUCCESS.

- [ ] **Step 3: Run the browser suite at both context paths**

Run, one after the other (never two builds at once):

```bash
mvn -o verify -Pit
mvn -o verify -Pit -Dit.context.path=roller
```

Expected: BUILD SUCCESS both times -- `AgentAccountsIT`, the `RouteSweepIT` markers for the six new routes, and the updated `UserAdminIT`, `SsoSignInIT`, `MultiUserJourneyIT` and `ErrorCasesIT` all green. A failure: follow superpowers:systematic-debugging, starting from `it-selenium/target/it-work/app-latest.log` and the failsafe report, before rerunning anything (an isolated rerun overwrites `it-selenium/target/failsafe-reports`). After adding clicking tests, also run once with `IT_LATE_CLICK_MS=150 mvn -o verify -Pit -Dit.test=AgentAccountsIT` (docs/dev/browser-its.md, "A click is not a barrier").

- [ ] **Step 4: Commit**

```bash
git add it-selenium/src/test/java/org/apache/roller/it/AgentAccountsIT.java
git commit -m "test(it): bootstrap an agent end to end; disabled, site-admin and API-guarded agents on the real WAR"
```

---

### Task 19: Final verification

**Model:** sonnet — runs the whole suite at both context paths and must diagnose, not retry, anything red.

**Files:** none changed unless a check fails (then the fix goes in its own commit, in the task that owns the code).

**Interfaces:** none.

- [ ] **Step 1: The unit suite and every gate**

Run (CLEAR first): `mvn -o -pl app verify`
Expected: BUILD SUCCESS -- javac `-Xlint:all` clean, PMD/CPD/SpotBugs at zero, JaCoCo LINE ≥ 0.9590 / BRANCH ≥ 0.9100 and the security-boundary package rule ≥ 0.93 / 0.89.

- [ ] **Step 2: The browser suite at both context paths**

```bash
mvn -o verify -Pit
mvn -o verify -Pit -Dit.context.path=roller
```

Expected: BUILD SUCCESS both times. Record each run's test count.

- [ ] **Step 3: Diff coverage over the whole wave**

```bash
git rev-parse --verify v0.1.19 || echo "no v0.1.19 tag: use the release commit 51677a04a as the base"
mvn -o -q -pl app jacoco:report
bin/check-diff-coverage.sh v0.1.19
```

Expected: at least 90% of changed lines covered. Below that, add tests that assert behaviour -- never assertion-free tests (docs/dev/quality-gates.md).

- [ ] **Step 4: The spec's acceptance criteria, one by one**

Confirm each criterion has its passing test: 1 `AgentAccountsMigrationTest`; 2 `AgentAccountStoreTest`, `AgentControllerTest`; 3 `AgentControllerTest`; 4 `RollerUserDetailsServiceTest`, `AgentAccountsIT`; 5 `PasswordResetControllerTest`, `UserTokenManagerTest`, `AgentAccountStoreTest`; 6 `AgentAccountsJspTest`, `UserEditControllerTest`; 7 `ApiTokenAuthFilterTest`, `AgentAccountsIT`; 8 `AgentControllerTest`; 9 `AgentControllerTest`, `ProfileTokensControllerTest`; 10 `ProfileTokensControllerTest`, `AgentAccountsIT`; 11 `AgentControllerTest`, `ProfileTokensControllerTest`, `TokenPinsTest`; 12 `TokensApiTest`; 13 `AdminTokensControllerTest`, `ApiTokenManagerTest`; 14 `ProfileTokensControllerTest`, `AdminTokensControllerTest`, `ApiTokenManagerTest`, `AgentAccountsIT`; 15 `ProfileTokensControllerTest`; 16 `UserEditControllerTest`; 17 `UserEditPasswordLinkTest`, `AgentAccountsJspTest`; 18 `UserWeblogsControllerTest`, `UserWeblogsModelTest`, `AgentAccountsJspTest`; 19 `UserAdminControllerTest`, `AgentAccountStoreTest`; 20 `SecurityConfigTest`, `LastLoginRecorderTest`, `AgentAccountStoreTest`, `UserAdminControllerTest`, `AgentAccountsIT`; 21 `AdminApiTest`, `AgentAccountsIT`; 22 `AdminApiTest`, `MetaApiTest`, `AgentAccountsIT`; 23 `AgentAccountStoreTest`, `AgentAccountsIT`; 24 `AgentAccountsIT` at `/` and `/roller`; 25 `RouteCoverageTest` + `RouteSweepIT`, `AgentAccountMessagesTest`, `AgentAccountsJspTest`, `JspConsistencyTest.everyPostFormCarriesACsrfInput`.

Report the two `-Pit` results (test counts), the diff-coverage percentage, and any criterion without a green test. Releasing 0.2.0 and deploying to blog.wikantik.com are the controller's, after the final review -- not this plan's. Never push.
