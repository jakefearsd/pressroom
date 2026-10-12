# Reader Sign-in with Google Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Readers sign in to Roller with Google (any OIDC provider by configuration). Their account is bound to the provider's `(iss, sub)`, holds only the `reader` role, and can do nothing an author can.

**Architecture:**
- Spring Security's own `oauth2Login()` sits in the existing UI filter chain. It is configured only when SSO is on, through one `SsoLogin` bean that `SecurityConfig` applies.
- A Roller `ReaderOidcUserService` turns the provider's identity into a Roller account through `ReaderAccounts`, which uses the new `UserManager.getUserBySsoIdentity` / `addReader`. The principal's name is the Roller username, so all existing "who is signed in" code works unchanged.
- Every SSO step that ends a request inside the security chain (order 40) releases the persistence session itself, because `PersistenceSessionFilter` (order 60) never runs for it.

**Tech Stack:**
- Java 25, Spring Boot 4.1, Spring Security 7.1 (`spring-security-oauth2-client`, `spring-security-oauth2-jose`, BOM-managed).
- EclipseLink/PostgreSQL 18, JSP, Velocity.
- JUnit 5 + Mockito + MockMvc; Selenide browser ITs; Keycloak 26 under docker-maven-plugin.

**Spec:** `docs/superpowers/specs/2026-10-11-reader-sign-in-design.md` (criteria 1-33). Read it before your task; it is the authority when this plan and it disagree.

## Global Constraints

**Process**
- Work on `master`, in the main checkout. Commit your own work. **Never push.**
- **TDD:** write the failing test, run it and watch it fail for the expected reason, then implement. A characterisation test (written to pass on old code) says so in its javadoc.
- **One build at a time** in this tree. Before any `mvn`, wait inline:
  `while pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; do sleep 10; done; mvn ...`

**Build commands**
- One class: `mvn -q -o -pl app test -Dtest=ClassName -Djacoco.skip=true`.
- Static gates: `mvn -q -o -pl app compile pmd:check pmd:cpd-check spotbugs:check -Djacoco.skip=true`. `verify -DskipTests` stops at jacoco:check, so don't use it.
- **New dependencies need one online resolve first.** Task 2 runs `mvn -q -pl app dependency:resolve` without `-o`. After that, `-o` works.

**Gates: zero violations, each fails the build**
- PMD 7.27.0, CPD at 110 tokens, SpotBugs 4.10.4.
- JaCoCo floors.
- Diff coverage ~90%.
- i18n ratchets (`MessageKeyTest`, `MessagePlaceholderContractTest`, `MessageFormatRegressionTest`).
- A one-off suppression is allowed only at the site, with a reason.

**Code rules**
- **Name every `@RequestParam`/`@PathVariable`.** The build has no `-parameters`.
- **SLF4J:** a `Throwable` is the LAST argument, and no `{}` consumes it.
- **No code may assume a context path.** Build every URL from `request.getContextPath()`, or use `<c:url>` in JSPs.
- **Never log** the client secret, ID token, access token or a reader's email. Logger `roller.sso` lines carry the username and a code only.
- **Container-managed classes** take `@Lazy Weblogger` in the constructor. There is no static service locator.
- **Fail closed:** a check that could not run is not a check that passed.

**Messages and markup**
- New message keys go in `app/src/main/resources/ApplicationResources.properties`. In a message that takes arguments, an apostrophe is written `''`.
- A user-typed or configured value passed as a `<spring:message arguments>` is escaped by the controller.

**Exact names used across tasks (verbatim)**
- Registration id: `oidc`.
- Paths:
  - start: `/roller-ui/sso/start`
  - authorization base: `/roller-ui/sso/authorize`
  - callback: `/roller-ui/sso/callback`
  - login page: `/roller-ui/login.rol`
- Session attribute for the return path: `roller.sso.return`.
- Error query parameter `sso`, with values `unavailable`, `failed`, `refused`.
- OAuth2 error codes: `sso_refused`, `sso_failed`.
- Role name: `reader`.
- Properties:
  - `sso.oidc.client.id`
  - `sso.oidc.client.secret`
  - `sso.oidc.discovery.uri`, default `https://accounts.google.com/.well-known/openid-configuration`
  - `sso.provider.label`, default `Google`
- Passphrase marker for bound accounts: `{bcrypt}!sso-bound`.
- Username shape: `reader-` + 10 characters from `[a-z0-9]`.

## Review Focus

These five inputs are implied by the spec but untested by its criteria. Each line is pinned by a test in the named task.

1. **The configured discovery value is the issuer URL itself** (`https://accounts.google.com`), not the full `/.well-known/...` URI. A person expects either form to work; both must resolve the same registration. (Task 2, `LazyOidcClientRegistrationRepositoryTest#issuerOrDiscoveryUriBothWork`.)
2. **The Absolute URL has a trailing slash, or includes the context path** (`https://blog.wikantik.com/`, `http://localhost:8080/roller`). The callback must be exactly `<absolute without trailing slash>/roller-ui/sso/callback`, with no double slash. (Task 4, `RollerAuthorizationRequestResolverTest#trailingSlashIsNotDoubled`.)
3. **The callback carries `_spring_security_remember_me=on`,** for example from a hand-edited URL. The sign-in still succeeds, sets no remember-me cookie, and never throws from the remember-me services. (Task 4, `SsoLoginWiringTest#callbackNeverSetsARememberMeCookie`.)
4. **Many sign-ins in a row on one worker thread** must not keep an EntityManager bound past each request. The start filter and both handlers release exactly once, even when they throw. (Task 4, the `*ReleasesThePersistenceSession*` tests.)
5. **A provider sends `email_verified` as a JSON string, or a `name` made only of control and whitespace characters.** A string is not `true`, so it is refused. A name that sanitises to nothing falls back to the email's local part, and then to `Reader`. (Task 3, `ReaderClaimsTest`.)

---

### Task 1: Business tier: SSO identity columns, `addReader`, the reader role

**Files:**
- Create: `bin/db/migrations/V030__user_sso_identity.sql`
- Modify: `app/src/main/java/org/apache/roller/weblogger/pojos/User.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/UserManager.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java`
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`, after `role.action.admin=...`
- Test (create): `app/src/test/java/org/apache/roller/weblogger/business/ReaderAccountStoreTest.java`
- Test (create): `app/src/test/java/org/apache/roller/weblogger/business/startup/UserSsoIdentityMigrationTest.java`

**Interfaces:**
- Produces:
  - `User.SSO_PASSWORD_MARKER = "{bcrypt}!sso-bound"`
  - `User.getSsoIssuer()` / `setSsoIssuer(String)`, `getSsoSubject()` / `setSsoSubject(String)`
  - `boolean User.isSsoBound()`: true when `ssoSubject != null`
  - `User.resetPassword(String)` throws `IllegalStateException` for a bound account
  - `UserManager.getUserBySsoIdentity(String issuer, String subject)`: enabled or disabled, null when none
  - `UserManager.addReader(User newUser)`: stores the user and grants only `reader`
  - `UserManager.getUserByEmail(String)` now considers enabled **local** accounts only

- [ ] **Step 1: Write the failing migration test.** Model it on `SchemaMigrationTest`'s `freshDatabase`/`dropDatabase`, copying those two private helpers' bodies into the new class and calling the same `MigrationFiles` helper. Assert:
  - after the chain, `roller_user` has `sso_issuer` and `sso_subject`;
  - inserting two rows with the same `(sso_issuer, sso_subject)` fails with SQLState `23505`;
  - two rows with both columns null succeed;
  - a row with only `sso_subject` set fails with SQLState `23514`.

```java
@Test
void identityPairIsUniqueAndAllOrNothing() throws Exception {
    String db = "migrationtest_sso_identity";
    try (Connection con = freshDatabase(db)) {
        insertUser(con, "u1", "a", null, null);
        insertUser(con, "u2", "b", null, null);                       // two local accounts: fine
        insertUser(con, "u3", "c", "https://issuer", "sub-1");
        SQLException dup = assertThrows(SQLException.class,
                () -> insertUser(con, "u4", "d", "https://issuer", "sub-1"));
        assertEquals("23505", dup.getSQLState());
        SQLException half = assertThrows(SQLException.class,
                () -> insertUser(con, "u5", "e", null, "sub-2"));
        assertEquals("23514", half.getSQLState());
    } finally {
        dropDatabase(db);
    }
}
// insertUser: INSERT INTO roller_user (id, username, passphrase, screenname, fullname,
//   emailaddress, datecreated, isenabled, sso_issuer, sso_subject)
//   VALUES (?, ?, 'x', ?, ?, 'e@example.com', now(), true, ?, ?)
```

- [ ] **Step 2: Run it.**
  - Run: `mvn -q -o -pl app test -Dtest=UserSsoIdentityMigrationTest -Djacoco.skip=true`
  - Expected: FAIL, column `sso_issuer` does not exist.

- [ ] **Step 3: Write `V030__user_sso_identity.sql`.** Copy V029's Apache header. The body:

```sql
-- Migration: bind an account to an OpenID Connect identity.
--
-- A reader who signs in with Google (or any OIDC provider) is found by the
-- provider's issuer and subject -- never by username or email, so a local
-- account is never adopted by a sign-in that happens to share its address.
-- Both columns are null on local (password) accounts. See
-- docs/superpowers/specs/2026-10-11-reader-sign-in-design.md.
--
-- Idempotent: safe to run on a database that already has some of it.

ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS sso_issuer  varchar(255);
ALTER TABLE roller_user ADD COLUMN IF NOT EXISTS sso_subject varchar(255);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ru_sso_identity_pair_ck') THEN
        ALTER TABLE roller_user ADD CONSTRAINT ru_sso_identity_pair_ck
            CHECK ((sso_issuer IS NULL) = (sso_subject IS NULL));
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ru_sso_identity_uq
    ON roller_user (sso_issuer, sso_subject)
    WHERE sso_subject IS NOT NULL;
```

  Run `bin/db/migrations/README.md`'s copy step, if it describes one; `SchemaMigrationTest#classpathCopyMatchesTheCanonicalMigrations` says where the classpath copy lives. Re-run the test. Expected: PASS. Then run `SchemaMigrationTest`; `everyMigrationIsIdempotent` must pass.

- [ ] **Step 4: Write the failing store test** (`ReaderAccountStoreTest`, against the shared Testcontainers DB like `UserTest`, with `TestUtils.setupWeblogger()` in `@BeforeEach`).

```java
@Test
void addReaderGrantsOnlyReaderAndLookupFindsDisabledToo() throws Exception {
    UserManager mgr = TestUtils.weblogger().getUserManager();
    User u = newBoundUser("reader-aaaaaaaaaa", "https://issuer.test", "sub-" + UUID.randomUUID());
    mgr.addReader(u);
    TestUtils.endSession(true);

    User found = mgr.getUserBySsoIdentity("https://issuer.test", u.getSsoSubject());
    assertEquals(u.getUserName(), found.getUserName());
    assertEquals(List.of("reader"), mgr.getRoles(found));

    found.setEnabled(Boolean.FALSE);
    mgr.saveUser(found);
    TestUtils.endSession(true);
    assertNotNull(mgr.getUserBySsoIdentity("https://issuer.test", u.getSsoSubject()),
            "a disabled bound account must still be found, or a new one would replace it");
}

@Test
void addReaderIgnoresFirstUserAdminEvenOnAnEmptyTable() throws Exception {
    // users.firstUserAdmin=true is the shipped default; simulate an empty table
    JPAUserManagerImpl real = (JPAUserManagerImpl) TestUtils.weblogger().getUserManager();
    JPAUserManagerImpl spy = Mockito.spy(real);
    Mockito.doReturn(List.of()).when(spy).getUsers(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.anyInt(), Mockito.anyInt());
    User u = newBoundUser("reader-bbbbbbbbbb", "https://issuer.test", "sub-" + UUID.randomUUID());
    spy.addReader(u);
    TestUtils.endSession(true);
    assertEquals(List.of("reader"), real.getRoles(u));
}

@Test
void getUserByEmailNeverReturnsABoundAccount() throws Exception {
    // a local "zz..." account sorts AFTER "reader-..." by username, the order getUserByEmail uses
    String email = "shared-" + UUID.randomUUID() + "@example.com";
    User local = TestUtils.setupUser("zzlocal" + System.nanoTime());
    local.setEmailAddress(email);
    UserManager mgr = TestUtils.weblogger().getUserManager();
    mgr.saveUser(local);
    User reader = newBoundUser("reader-cccccccccc", "https://issuer.test", "sub-" + UUID.randomUUID());
    reader.setEmailAddress(email);
    mgr.addReader(reader);
    TestUtils.endSession(true);
    assertEquals(local.getUserName(), mgr.getUserByEmail(email).getUserName());
}

@Test
void aBoundAccountCannotBeGivenAPassword() {
    User u = newBoundUser("reader-dddddddddd", "https://issuer.test", "sub-x");
    assertThrows(IllegalStateException.class, () -> u.resetPassword("anything-long-enough"));
}

@Test
void theMarkerMatchesNoPasswordAndNeverThrows() {
    PasswordEncoder enc = RollerContext.createPasswordEncoder();
    for (String raw : new String[] {"", "!", "sso-bound", User.SSO_PASSWORD_MARKER, "{bcrypt}!sso-bound"}) {
        assertFalse(enc.matches(raw, User.SSO_PASSWORD_MARKER), raw);
    }
}

@Test
void readerImpliesNoGlobalAction() throws Exception {
    User u = newBoundUser("reader-eeeeeeeeee", "https://issuer.test", "sub-y");
    GlobalPermission readerPerm = GlobalPermission.impliedByRoles(u, List.of("reader"));
    for (String action : List.of(GlobalPermission.LOGIN, GlobalPermission.WEBLOG, GlobalPermission.ADMIN)) {
        assertFalse(readerPerm.implies(new GlobalPermission(List.of(action))), action);
    }
}
```

`newBoundUser(userName, iss, sub)` builds a `User` with:
- a screen name and full name of `"Reader"`;
- an email of `userName + "@example.com"`;
- `setPassword(User.SSO_PASSWORD_MARKER)`;
- `setDateCreated(new Date())` and `setEnabled(TRUE)`;
- the issuer and subject.

Tear down every user you create in `@AfterEach`, using `TestUtils.teardownUser`.

- [ ] **Step 5: Run it.** Expected: compile FAIL, since `addReader` and `getUserBySsoIdentity` don't exist yet.

- [ ] **Step 6: Implement.**
  - `User.java`:
    - two fields with getters and setters;
    - `public static final String SSO_PASSWORD_MARKER = "{bcrypt}!sso-bound";`
    - `isSsoBound()`;
    - at the top of `resetPassword`: `if (isSsoBound()) { throw new IllegalStateException("An account that signs in with single sign-on has no password."); }`
  - `User.orm.xml`:
    - `<basic name="ssoIssuer"><column name="sso_issuer" .../></basic>` and the same for `ssoSubject`, beside the other basics;
    - a named query `User.getBySsoIdentity`: `SELECT u FROM User u WHERE u.ssoIssuer = ?1 AND u.ssoSubject = ?2`;
    - `User.getByEmailAddress&Enabled` becomes `... WHERE u.emailAddress = ?1 AND u.enabled = ?2 AND u.ssoSubject IS NULL ORDER BY u.userName`, and its XML comment gains: "local accounts only: a reader who shares an author's address must never capture the author's password reset (reader sign-in spec, criterion 33)".
  - `UserManager`:
    - declare the two methods, each with javadoc;
    - change `getUserByEmail`'s javadoc to "the enabled local (non-SSO) account".
  - `JPAUserManagerImpl`:
    - `getUserBySsoIdentity` with null guards (`WebloggerException` on null) and `getSingleResult`/`NoResultException`, as in `getUserByEmail`;
    - `addReader`, below:

```java
@Override
public void addReader(User newUser) throws WebloggerException {
    if (newUser == null || !newUser.isSsoBound()) {
        throw new WebloggerException("addReader needs an account bound to an SSO identity");
    }
    // Deliberately NOT addUser: that grants "editor" to every new account and
    // "admin" to the first one when users.firstUserAdmin is set. A reader holds
    // exactly one role, whatever the table looks like (reader sign-in spec, criterion 15).
    if (getUserByUserName(newUser.getUserName(), null) != null) {
        throw new WebloggerException("error.add.user.userNameInUse");
    }
    this.strategy.store(newUser);
    grantRole("reader", newUser);
}
```

  - `roller.properties`: after `role.action.admin=...` add:

```properties
# A reader (signed in with single sign-on) holds no global action: every
# existing check refuses it. Comments will add its one permission.
role.action.reader=
```

- [ ] **Step 7: Run both test classes, then `UserTest`, `UserQueryAndRoleTest`, `PasswordEncodingTest` and `SchemaMigrationTest`.** Expected: PASS. Then run the static gates.

- [ ] **Step 8: Commit.** `git add` the files above, then `git commit -m "feat(users): bind accounts to an SSO identity; addReader grants only reader (V030)"`.

---

### Task 2: SSO settings, lazy client registration, discovery self-check

**Files:**
- Modify: `app/pom.xml`. Add `org.springframework.security:spring-security-oauth2-client` and `org.springframework.security:spring-security-oauth2-jose` with no `<version>`; the Boot BOM manages them. Put them beside the existing spring-security dependencies.
- Modify: `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`. Add a new `# Single sign-on (readers)` section with the four properties and their defaults; leave id and secret empty.
- Modify: `app/src/main/resources/log4j2.xml`. Add `<Logger name="roller.sso" level="info" additivity="false"> <AppenderRef ref="asyncRoller"/> </Logger>` beside `io.modelcontextprotocol`.
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/sso/package-info.java`
- Create: `.../sso/SsoSettings.java`
- Create: `.../sso/LazyOidcClientRegistrationRepository.java`
- Create: `.../sso/OidcDiscoverySelfCheck.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/core/security/sso/SsoSettingsTest.java`
- Test: `.../sso/LazyOidcClientRegistrationRepositoryTest.java`
- Test: `.../sso/OidcDiscoverySelfCheckTest.java`

**Interfaces:**
- Produces:

```java
public record SsoSettings(String clientId, String clientSecret, String discoveryUri, String providerLabel) {
    public static final String REGISTRATION_ID = "oidc";
    public static final String GOOGLE_DISCOVERY = "https://accounts.google.com/.well-known/openid-configuration";
    public static SsoSettings fromConfig();   // reads WebloggerConfig, applies defaults, trims
    public boolean enabled();                 // both id and secret non-blank
    public String issuer();                   // discoveryUri without a trailing "/.well-known/openid-configuration", and without a trailing "/"
    @Override public String toString();       // NEVER prints the secret: "SsoSettings[clientId=..., discoveryUri=..., providerLabel=..., clientSecret=<set>|<unset>]"
}
public class LazyOidcClientRegistrationRepository implements ClientRegistrationRepository {
    public LazyOidcClientRegistrationRepository(SsoSettings settings);  // production fetcher
    LazyOidcClientRegistrationRepository(SsoSettings settings, Function<String, ClientRegistration.Builder> fetcher); // tests
    public ClientRegistration findByRegistrationId(String registrationId); // "oidc" only; up to 3 attempts; caches success; null on failure
    public boolean tryResolveOnce();          // one attempt, caches success; used by the self-check
}
public final class OidcDiscoverySelfCheck {
    public OidcDiscoverySelfCheck(LazyOidcClientRegistrationRepository repo, long delayMillis);
    public Thread start();                     // daemon thread "roller-sso-selfcheck"; 3 attempts; INFO on success, WARN per failure; never throws
}
```

- [ ] **Step 1: Resolve the new dependencies online once.** Add them to `app/pom.xml` and run `mvn -q -pl app dependency:resolve` (no `-o`). Expected: success.

- [ ] **Step 2: Write the failing tests.**

```java
// SsoSettingsTest
@ParameterizedTest
@CsvSource(value = {"id,secret,true", "'',secret,false", "id,'',false", "'  ',secret,false", "id,'  ',false"})
void enabledNeedsBothIdAndSecret(String id, String secret, boolean expected) {
    assertEquals(expected, new SsoSettings(id, secret, SsoSettings.GOOGLE_DISCOVERY, "Google").enabled());
}
@Test void toStringNeverCarriesTheSecret() {
    String s = new SsoSettings("id", "s3cr3t-value", SsoSettings.GOOGLE_DISCOVERY, "Google").toString();
    assertFalse(s.contains("s3cr3t-value"));
    assertTrue(s.contains("clientSecret=<set>"));
}
@ParameterizedTest
@ValueSource(strings = {
    "https://accounts.google.com/.well-known/openid-configuration",
    "https://accounts.google.com/",
    "https://accounts.google.com"})
void issuerOrDiscoveryUriBothWork(String configured) {   // Review Focus 1
    assertEquals("https://accounts.google.com",
            new SsoSettings("id", "s", configured, "Google").issuer());
}
@Test void defaultsApplyWhenUnset() { /* WebloggerConfig.setProperty is unavailable: build fromConfig()
   with the properties absent (the test classpath's roller.properties has the shipped empty
   values) and assert discoveryUri == GOOGLE_DISCOVERY, providerLabel == "Google", enabled() == false */ }
```

`SsoSettings.GOOGLE_DISCOVERY` is a public constant: `"https://accounts.google.com/.well-known/openid-configuration"`.

```java
// LazyOidcClientRegistrationRepositoryTest
private static ClientRegistration.Builder stubBuilder(String issuer) {
    return ClientRegistration.withRegistrationId("ignored")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationUri(issuer + "/auth").tokenUri(issuer + "/token")
            .jwkSetUri(issuer + "/jwks").issuerUri(issuer).userInfoUri(issuer + "/userinfo");
}
@Test void successIsCachedAndFetchedOnce() {
    AtomicInteger calls = new AtomicInteger();
    var repo = new LazyOidcClientRegistrationRepository(SETTINGS, iss -> { calls.incrementAndGet(); return stubBuilder(iss); });
    ClientRegistration r = repo.findByRegistrationId("oidc");
    assertSame(r, repo.findByRegistrationId("oidc"));
    assertEquals(1, calls.get());
    assertEquals("oidc", r.getRegistrationId());
    assertEquals("id", r.getClientId());
    assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_BASIC, r.getClientAuthenticationMethod());
    assertEquals(Set.of("openid", "profile", "email"), r.getScopes());
    assertEquals("sub", r.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
    assertEquals("Google", r.getClientName());
}
@Test void failureTriesThreeTimesThenAnswersNullAndRecoversLater() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean up = new AtomicBoolean(false);
    var repo = new LazyOidcClientRegistrationRepository(SETTINGS, iss -> {
        calls.incrementAndGet();
        if (!up.get()) { throw new IllegalArgumentException("connect timed out"); }
        return stubBuilder(iss);
    });
    assertNull(repo.findByRegistrationId("oidc"));
    assertEquals(3, calls.get());
    up.set(true);
    assertNotNull(repo.findByRegistrationId("oidc"));      // criterion 3: no restart needed
}
@Test void unknownRegistrationIdAndDisabledSettingsAnswerNull() { /* "google" -> null; settings with blank id -> null, fetcher never called */ }
@Test void theSecretAppearsInNoLogLineOrException() { /* attach a log4j2 capturing appender (see
   ApiTokenAuthFilterTest or any test using ListAppender in this repo; reuse its helper) to "roller.sso";
   make the fetcher throw new IllegalStateException("boom"); call findByRegistrationId; assert no captured
   message or throwable string contains the secret value (criterion 4) */ }
```

```java
// OidcDiscoverySelfCheckTest
@Test void logsAWarnPerFailedAttemptAndNeverThrows() throws Exception {
    var repo = new LazyOidcClientRegistrationRepository(SETTINGS, iss -> { throw new IllegalArgumentException("unreachable"); });
    Thread t = new OidcDiscoverySelfCheck(repo, 1).start();
    assertTrue(t.isDaemon());
    t.join(5000);
    assertEquals(3, capturedWarnLinesContaining("discovery"));   // criterion 3
}
@Test void logsOneInfoOnSuccessAndStops() { /* fetcher succeeds: exactly one INFO line, zero WARN, join returns */ }
```

- [ ] **Step 3: Run them.** Expected: compile FAIL.

- [ ] **Step 4: Implement.** `LazyOidcClientRegistrationRepository` core:

```java
private static final Logger log = LoggerFactory.getLogger("roller.sso");
private static final int ATTEMPTS = 3;
private final SsoSettings settings;
private final Function<String, ClientRegistration.Builder> fetcher;
private final AtomicReference<ClientRegistration> cached = new AtomicReference<>();

public LazyOidcClientRegistrationRepository(SsoSettings settings) {
    this(settings, ClientRegistrations::fromOidcIssuerLocation);
}

@Override
public ClientRegistration findByRegistrationId(String registrationId) {
    if (!SsoSettings.REGISTRATION_ID.equals(registrationId) || !settings.enabled()) {
        return null;
    }
    for (int attempt = 1; attempt <= ATTEMPTS && cached.get() == null; attempt++) {
        tryResolveOnce();
    }
    return cached.get();
}

public boolean tryResolveOnce() {
    if (cached.get() != null) {
        return true;
    }
    try {
        ClientRegistration built = fetcher.apply(settings.issuer())
                .registrationId(SsoSettings.REGISTRATION_ID)
                .clientId(settings.clientId())
                .clientSecret(settings.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .scope("openid", "profile", "email")
                // Replaced per request by RollerAuthorizationRequestResolver with the
                // configured Absolute URL; the template only satisfies the builder.
                .redirectUri("{baseUrl}/roller-ui/sso/callback")
                .userNameAttributeName("sub")
                .clientName(settings.providerLabel())
                .build();
        cached.compareAndSet(null, built);
        return true;
    } catch (RuntimeException ex) {
        // Message only, never the exception object: a provider error body could echo
        // request details, and the secret must never reach a log (spec criterion 4).
        log.warn("OIDC discovery for {} failed: {}", settings.issuer(), ex.getClass().getSimpleName());
        return false;
    }
}
```

`OidcDiscoverySelfCheck.start()`:
- starts a daemon thread named `roller-sso-selfcheck`;
- loops 3 times on `repo.tryResolveOnce()`, sleeping `delayMillis * attempt` between failures and restoring the interrupt flag on `InterruptedException`;
- logs `log.info("OIDC discovery reachable: {}", issuer)` once on success;
- after 3 failures logs `log.warn("OIDC discovery unreachable after {} attempts; sign-in will retry on demand", 3)`.

The repository already logs each failed attempt, so the test counts those three WARN lines. Make the test's assertion match whatever the final split is: exactly three WARN lines for three failed attempts.

The `package-info.java` javadoc gives one paragraph on the package. Readers sign in with OIDC through Spring Security's `oauth2Login()`. Wikantik's binding rules are ported (spec path). Everything that ends a request inside the security chain releases the persistence session.

- [ ] **Step 5: Run the three test classes.** Expected: PASS. Then run the static gates.

- [ ] **Step 6: Commit.** `feat(sso): settings, lazy OIDC client registration, discovery self-check`.

---

### Task 3: Reader binding: claims, accounts, the OIDC user service

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/sso/ReaderClaims.java`
- Create: `.../sso/ReaderSignInRefused.java`, a checked exception carrying `reason()`
- Create: `.../sso/ReaderAccounts.java`
- Create: `.../sso/RollerOidcUser.java`
- Create: `.../sso/ReaderOidcUserService.java`
- Test: `.../sso/ReaderClaimsTest.java`
- Test: `.../sso/ReaderAccountsTest.java` (DB-backed)
- Test: `.../sso/ReaderOidcUserServiceTest.java`

**Interfaces:**
- Consumes (Task 1): `UserManager.getUserBySsoIdentity`, `addReader`, `User.SSO_PASSWORD_MARKER`, `User.isSsoBound()`.
- Produces:

```java
public record ReaderClaims(String issuer, String subject, String email, boolean emailVerified, String name) {
    public static ReaderClaims from(String issuer, String subject, Map<String, Object> claims); // firstScalar on every claim
    static Object firstScalar(Object value);          // Collection -> first element (null when empty); else value
    public String displayName();                      // the spec's rule; never blank ("Reader" last resort)
    public void requireUsable() throws ReaderSignInRefused; // email present, non-blank, <= 255, emailVerified
}
public class ReaderAccounts {                          // container-managed: @Lazy Weblogger
    public ReaderAccounts(@Lazy Weblogger weblogger);
    ReaderAccounts(Weblogger weblogger, RandomGenerator random); // tests
    public SignIn signIn(ReaderClaims claims) throws ReaderSignInRefused, WebloggerException;
    public record SignIn(User user, List<String> roles, boolean created) { }
}
public final class RollerOidcUser implements OidcUser, Serializable {
    public RollerOidcUser(OidcUser delegate, String rollerUserName, Collection<? extends GrantedAuthority> authorities, boolean newAccount);
    public String getName();       // the Roller username, NOT the subject
    public boolean isNewAccount();
}
public class ReaderOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    public ReaderOidcUserService(ReaderAccounts accounts);                 // delegate = new OidcUserService()
    ReaderOidcUserService(ReaderAccounts accounts, OAuth2UserService<OidcUserRequest, OidcUser> delegate); // tests
    public static final String REFUSED = "sso_refused";
    public static final String FAILED = "sso_failed";
}
```

- [ ] **Step 1: Write the failing `ReaderClaimsTest`** (criteria 17, 18, Review Focus 5).

```java
@ParameterizedTest
@CsvSource(delimiter = '|', value = {
    "Ada Lovelace|ada@example.com|Ada Lovelace",
    "'  Ada \t\n  Lovelace  '|ada@example.com|Ada Lovelace",
    "'\u0000\u0007'|ada@example.com|ada",
    "''|ada@example.com|ada",
    "'   '|@example.com|Reader"})
void displayNameRule(String name, String email, String expected) {
    assertEquals(expected, new ReaderClaims("i", "s", email, true, name).displayName());
}
@Test void displayNameIsCutAt100CodePoints() {
    String longName = "😀".repeat(150);           // 150 emoji = 300 chars
    assertEquals(100, new ReaderClaims("i", "s", "a@b.c", true, longName).displayName().codePointCount(0,
            new ReaderClaims("i", "s", "a@b.c", true, longName).displayName().length()));
}
@Test void multiValuedClaimsTakeTheFirstValue() {
    ReaderClaims c = ReaderClaims.from("i", "s", Map.of("email", List.of("a@b.c", "x@y.z"),
            "email_verified", List.of(Boolean.TRUE), "name", List.of("First", "Second")));
    assertEquals("a@b.c", c.email());
    assertTrue(c.emailVerified());
    assertEquals("First", c.name());
}
@ParameterizedTest
@MethodSource("refusedClaims")
void unusableClaimsAreRefused(Map<String, Object> claims) {
    assertThrows(ReaderSignInRefused.class, () -> ReaderClaims.from("i", "s", claims).requireUsable());
}
static Stream<Map<String, Object>> refusedClaims() {
    return Stream.of(
        Map.of("email_verified", true),                                  // no email
        Map.of("email", "  ", "email_verified", true),                  // blank
        Map.of("email", "a".repeat(250) + "@b.cd", "email_verified", true), // 256 chars
        Map.of("email", "a@b.c", "email_verified", false),
        Map.of("email", "a@b.c"),                                        // verified absent
        Map.of("email", "a@b.c", "email_verified", "true"));             // string, not true (Review Focus 5)
}
```

- [ ] **Step 2: Run it.** Expected: compile FAIL. Implement `ReaderClaims`:
  - `from`: `firstScalar` on `email`, `email_verified` and `name`. `emailVerified` is `Boolean.TRUE.equals(value)`.
  - `displayName()`:
    1. Remove characters where `Character.isISOControl(cp)` or `Character.getType(cp) == Character.FORMAT`.
    2. `replaceAll("\\s+", " ")`, then `strip()`.
    3. Cut to 100 code points.
    4. If empty, apply the same steps to the email's local part (before the last `@`).
    5. If still empty, `"Reader"`.
  
  Run again. Expected: PASS.

- [ ] **Step 3: Write the failing `ReaderAccountsTest`** (DB-backed with `TestUtils.setupWeblogger()`; each test uses its own `sub-<uuid>`, and `@AfterEach` tears down created users).

```java
@Test void firstSignInCreatesAReaderAndTheSecondReusesIt() throws Exception {      // 5 (row shape), 13
    ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
    ReaderClaims c = claims(sub, "ada@example.com", "Ada Lovelace");
    ReaderAccounts.SignIn first = accounts.signIn(c);
    TestUtils.endSession(true);
    assertTrue(first.created());
    assertTrue(first.user().getUserName().matches("reader-[a-z0-9]{10}"));
    assertEquals("Ada Lovelace", first.user().getScreenName());
    assertEquals("Ada Lovelace", first.user().getFullName());
    assertEquals(User.SSO_PASSWORD_MARKER, first.user().getPassword());
    assertEquals(List.of("reader"), first.roles());
    long before = TestUtils.weblogger().getUserManager().getUserCount();
    ReaderAccounts.SignIn second = accounts.signIn(c);
    assertFalse(second.created());
    assertEquals(first.user().getUserName(), second.user().getUserName());
    assertEquals(before, TestUtils.weblogger().getUserManager().getUserCount());
}
@Test void aLocalAccountWithTheSameEmailIsNotAdopted() { /* setupUser local with email E; signIn(claims E) -> created,
   different username; local account unchanged (password, roles) -- criterion 14 */ }
@Test void aDisabledBoundAccountIsRefusedAndNotReplaced() { /* signIn, disable via saveUser, signIn again ->
   ReaderSignInRefused; getUserCount unchanged -- criterion 16 */ }
@Test void laterSignInRefreshesEmailButKeepsAnEditedScreenName() { /* signIn; set screenName "Edited"; signIn with
   new email -> email updated, screenName "Edited" -- criterion 19 */ }
@Test void aUsernameCollisionIsRedrawn() throws Exception {                        // criterion 21
    // Seeded generator: the first draw collides with an existing user, the second does not.
    RandomGenerator fixed = sequence("aaaaaaaaaa", "bbbbbbbbbb");    // helper returning chars for [a-z0-9] indices
    TestUtils.setupUser("reader-aaaaaaaaaa");
    ReaderAccounts.SignIn s = new ReaderAccounts(TestUtils.weblogger(), fixed).signIn(claims(sub, "x@example.com", "X"));
    assertEquals("reader-bbbbbbbbbb", s.user().getUserName());
}
@Test void aConcurrentFirstSignInEndsWithOneAccount() throws Exception {          // criterion 20
    // The "winner" row exists, but this caller's first lookup misses it (spied manager), so
    // addReader+flush hits the unique index; signIn must recover by re-reading the winner.
    ReaderAccounts.SignIn winner = new ReaderAccounts(TestUtils.weblogger()).signIn(claims(sub, "w@example.com", "W"));
    TestUtils.endSession(true);
    Weblogger spied = spyWebloggerWhoseFirstSsoLookupMisses();   // Mockito spy on weblogger + its UserManager
    ReaderAccounts.SignIn loser = new ReaderAccounts(spied).signIn(claims(sub, "w@example.com", "W"));
    assertEquals(winner.user().getUserName(), loser.user().getUserName());
    assertFalse(loser.created());
}
@Test void refusedClaimsCreateNothing() { /* unverified email -> ReaderSignInRefused, getUserCount unchanged */ }
```

- [ ] **Step 4: Run it.** Expected: compile FAIL. Implement `ReaderAccounts.signIn`:

```java
public SignIn signIn(ReaderClaims claims) throws ReaderSignInRefused, WebloggerException {
    claims.requireUsable();
    UserManager users = weblogger.getUserManager();
    User existing = users.getUserBySsoIdentity(claims.issuer(), claims.subject());
    if (existing != null) {
        return existingAccount(users, existing, claims);
    }
    User created = newReader(users, claims);
    try {
        users.addReader(created);
        weblogger.flush();
        return new SignIn(created, List.of("reader"), true);
    } catch (WebloggerException | RuntimeException race) {
        // The unique (sso_issuer, sso_subject) index decides a concurrent first
        // sign-in: the loser's flush fails, and the winner's row is the answer.
        weblogger.release();
        User winner = weblogger.getUserManager().getUserBySsoIdentity(claims.issuer(), claims.subject());
        if (winner == null) {
            throw race instanceof WebloggerException we ? we : new WebloggerException("Reader sign-in failed", race);
        }
        return existingAccount(weblogger.getUserManager(), winner, claims);
    }
}

private SignIn existingAccount(UserManager users, User account, ReaderClaims claims)
        throws ReaderSignInRefused, WebloggerException {
    if (!Boolean.TRUE.equals(account.getEnabled())) {
        throw new ReaderSignInRefused("account disabled");
    }
    if (!claims.email().equals(account.getEmailAddress())) {
        account.setEmailAddress(claims.email());
        users.saveUser(account);
        weblogger.flush();
    }
    return new SignIn(account, users.getRoles(account), false);
}
```

`newReader`:
- draws up to 5 usernames (`"reader-"` plus 10 characters, each `ALPHABET.charAt(random.nextInt(36))` with `ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"`), keeping the first for which `users.getUserByUserName(candidate, null) == null`;
- otherwise throws `WebloggerException("no free reader username after 5 draws")`;
- sets screen name and full name to `displayName()`, email, `SSO_PASSWORD_MARKER`, `new Date()`, enabled `TRUE`, the issuer and the subject.

The production `RandomGenerator` is `new SecureRandom()`.

`getRoles` is deprecated, so add `@SuppressWarnings("deprecation")` with the reason `UserManager.getRoles is the only role reader`.

Run again. Expected: PASS.

- [ ] **Step 5: Write the failing `ReaderOidcUserServiceTest`.**

```java
@Test void principalNameIsTheRollerUsernameAndAuthoritiesAreRollerRoles() throws Exception {   // flow step 5
    ReaderAccounts accounts = mock(ReaderAccounts.class);
    User u = new User(); u.setUserName("reader-abcdefghij");
    when(accounts.signIn(any())).thenReturn(new ReaderAccounts.SignIn(u, List.of("reader"), true));
    OidcUser fromProvider = oidcUser("https://issuer.test", "sub-1",
            Map.of("email", "a@b.c", "email_verified", true, "name", "A", "roles", List.of("admin")));
    OidcUser result = new ReaderOidcUserService(accounts, req -> fromProvider).loadUser(request());
    assertEquals("reader-abcdefghij", result.getName());
    assertEquals(Set.of("reader"), AuthorityUtils.authorityListToSet(result.getAuthorities()));  // never "admin" from a claim
    assertTrue(((RollerOidcUser) result).isNewAccount());
    assertDoesNotThrow(() -> new ObjectOutputStream(OutputStream.nullOutputStream()).writeObject(result)); // session-stored
}
@Test void aRefusalBecomesAnSsoRefusedOAuth2Error() { /* signIn throws ReaderSignInRefused("account disabled") ->
   OAuth2AuthenticationException with getError().getErrorCode() == "sso_refused" */ }
@Test void aStoreFailureBecomesSsoFailed() { /* signIn throws WebloggerException -> error code "sso_failed" */ }
@Test void claimsArePassedThroughWithIssuerAndSubjectFromTheIdToken() { /* ArgumentCaptor on ReaderClaims */ }
```

`oidcUser(...)` builds a `DefaultOidcUser` from an `OidcIdToken` with `iss`, `sub` and the claims. `request()` builds an `OidcUserRequest` from a stub `ClientRegistration`, an access token and that ID token.

- [ ] **Step 6: Implement** `RollerOidcUser`, which delegates `getClaims`, `getUserInfo`, `getIdToken` and `getAttributes`. It holds its authorities as an unmodifiable `List<GrantedAuthority>` of `SimpleGrantedAuthority`. Then implement `ReaderOidcUserService.loadUser`:

```java
OidcUser fromProvider = delegate.loadUser(userRequest);
ReaderClaims claims = ReaderClaims.from(fromProvider.getIdToken().getIssuer().toString(),
        fromProvider.getSubject(), fromProvider.getClaims());
try {
    ReaderAccounts.SignIn signIn = accounts.signIn(claims);
    List<GrantedAuthority> authorities = signIn.roles().stream()
            .map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
    return new RollerOidcUser(fromProvider, signIn.user().getUserName(), authorities, signIn.created());
} catch (ReaderSignInRefused refused) {
    throw new OAuth2AuthenticationException(new OAuth2Error(REFUSED, refused.reason(), null), refused.reason());
} catch (WebloggerException ex) {
    throw new OAuth2AuthenticationException(new OAuth2Error(FAILED), "reader account store failed", ex);
}
```

Run all three test classes, then the static gates. Expected: PASS.

- [ ] **Step 7: Commit.** `feat(sso): bind a provider identity to a reader account`.

---

### Task 4: Security wiring: start filter, resolver, handlers, `SsoLogin`

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/sso/ReturnPaths.java`
- Create: `.../sso/SsoStartFilter.java`
- Create: `.../sso/RollerAuthorizationRequestResolver.java`
- Create: `.../sso/SsoSuccessHandler.java`
- Create: `.../sso/SsoFailureHandler.java`
- Create: `.../sso/SsoLogin.java`
- Create: `app/src/main/java/org/apache/roller/weblogger/boot/SsoConfig.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/boot/SecurityConfig.java`
- Modify: `app/src/main/resources/application.properties`, beside `server.servlet.session.cookie.http-only=true`
- Test: `.../sso/ReturnPathsTest.java`
- Test: `.../sso/SsoStartFilterTest.java`
- Test: `.../sso/RollerAuthorizationRequestResolverTest.java`
- Test: `.../sso/SsoHandlersTest.java`
- Test: `app/src/test/java/org/apache/roller/weblogger/boot/SsoLoginWiringTest.java`
- Test: extend the existing test that pins `application.properties` keys, or create `SessionCookiePolicyTest`

**Interfaces:**
- Consumes:
  - Task 2: `SsoSettings`, `LazyOidcClientRegistrationRepository`, `OidcDiscoverySelfCheck`.
  - Task 3: `ReaderOidcUserService` (its `REFUSED`/`FAILED`), `RollerOidcUser.isNewAccount()`.
- Produces:

```java
public final class ReturnPaths {
    public static final String SESSION_ATTRIBUTE = "roller.sso.return";
    public static String sanitize(String raw, String contextPath); // valid path or contextPath + "/"
}
public final class SsoPaths {          // constants, in the sso package
    public static final String START = "/roller-ui/sso/start";
    public static final String AUTHORIZE_BASE = "/roller-ui/sso/authorize";
    public static final String CALLBACK = "/roller-ui/sso/callback";
    public static final String LOGIN_PAGE = "/roller-ui/login.rol";
    public static String loginError(String contextPath, String code); // contextPath + LOGIN_PAGE + "?sso=" + code
}
public class SsoLogin { public void applyTo(HttpSecurity http) throws Exception; }   // only exists as a bean when SSO is on
```

**How the configured Absolute URL is read** (spec: never the request): `WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl")`. That is the DB row, then `ROLLER_SITE_ABSOLUTEURL`. **Not** `getAbsoluteContextURL()`, whose last fallback is latched from the first request. Inject it as a `Supplier<String>` so tests do not touch static state.

**How the persistence session is released** (spec criterion 32): copy `ApiTokenAuthFilter`'s release-on-the-way-out shape. If `webloggerProvider` is bootstrapped, call `webloggerProvider.getWeblogger().release()` in a `finally`. Read that filter's javadoc first; it explains why order 40 needs this. Each of the start filter and both handlers takes a `WebloggerProvider`.

- [ ] **Step 1: Write the failing `ReturnPathsTest`** (criterion 6).

```java
@ParameterizedTest
@CsvSource(value = {
    "/roller/blog/entry/x|/roller|/roller/blog/entry/x",
    "/blog/|''|/blog/",
    "https://evil.example/|/roller|/roller/",
    "//evil.example/|''|/",
    "/\\evil.example|''|/",
    "/other/x|/roller|/roller/",
    "/roller|/roller|/roller/",
    "''|/roller|/roller/",
    "/roller/a\rb|/roller|/roller/",
    "/roller/a\nb|/roller|/roller/"}, delimiter = '|')
void sanitize(String raw, String ctx, String expected) {
    assertEquals(expected, ReturnPaths.sanitize(raw, ctx));
}
@Test void nullAndOverlongFallBack() {
    assertEquals("/", ReturnPaths.sanitize(null, ""));
    assertEquals("/", ReturnPaths.sanitize("/" + "a".repeat(2000), ""));
}
```

Rule:
- the value is accepted when it is non-null, at most 2000 characters, starts with `contextPath + "/"`, does not start with `//` or `/\`, and contains no character below `0x20` or equal to `0x7F`;
- `/roller` exactly is not accepted (it lacks the trailing `/`);
- anything else becomes `contextPath + "/"`.

Implement, then run. Expected: PASS.

- [ ] **Step 2: Write the failing `SsoStartFilterTest`** (MockMvc-free; `MockHttpServletRequest`/`Response`, `MockFilterChain`).

```java
@Test void storesTheReturnPathAndRedirectsToTheAuthorizationEndpoint() throws Exception {
    MockHttpServletRequest req = get("/roller", "/roller/roller-ui/sso/start", "/roller/blog/");
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, new MockFilterChain());
    assertEquals("/roller/roller-ui/sso/authorize/oidc", res.getRedirectedUrl());
    assertEquals("/roller/blog/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
}
@Test void noAbsoluteUrlAnswersUnavailableAndRedirectsToNoProvider() { /* supplier -> "" or null:
   redirect "/roller/roller-ui/login.rol?sso=unavailable" -- criterion 7 */ }
@Test void unresolvableRegistrationAnswersUnavailable() { /* repo returns null -> same redirect -- criterion 3 */ }
@Test void otherPathsPassThrough() { /* GET /roller/roller-ui/login.rol -> chain invoked, no redirect */ }
@Test void nonGetPassesThrough() { /* POST to start -> chain invoked */ }
@Test void releasesThePersistenceSessionExactlyOnceEvenWhenTheSupplierThrows() { /* provider mock bootstrapped;
   supplier throws RuntimeException -> response redirect unavailable (fail closed) AND verify(weblogger, times(1)).release()
   -- criterion 32, Review Focus 4 */ }
```

The start filter matches `request.getRequestURI().substring(request.getContextPath().length())` equal to `SsoPaths.START`, and only on GET. Implement, then run. Expected: PASS.

- [ ] **Step 3: Write the failing `RollerAuthorizationRequestResolverTest`** (criterion 7, Review Focus 2).

```java
@ParameterizedTest
@CsvSource(value = {
    "https://blog.example|https://blog.example/roller-ui/sso/callback",
    "https://blog.example/|https://blog.example/roller-ui/sso/callback",
    "http://localhost:8080/roller|http://localhost:8080/roller/roller-ui/sso/callback",
    "http://localhost:8080/roller/|http://localhost:8080/roller/roller-ui/sso/callback"}, delimiter = '|')
void trailingSlashIsNotDoubled(String absolute, String expectedCallback) {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/roller-ui/sso/authorize/oidc");
    req.setServerName("docker2.lan"); req.setServerPort(8080);       // a host the provider would refuse
    OAuth2AuthorizationRequest r = resolver(() -> absolute).resolve(req);
    assertEquals(expectedCallback, r.getRedirectUri());
    assertNotNull(r.getAttributes().get(PkceParameterNames.CODE_VERIFIER));   // PKCE for a confidential client
    assertNotNull(r.getAttributes().get(OidcParameterNames.NONCE));
    assertEquals(Set.of("openid", "profile", "email"), r.getScopes());
}
@Test void noAbsoluteUrlResolvesNothing() { /* supplier -> "" : resolve(req) == null, so no redirect to a provider */ }
```

Implement:
- Wrap `new DefaultOAuth2AuthorizationRequestResolver(repo, SsoPaths.AUTHORIZE_BASE)`.
- In each `resolve`:
  - read the absolute URL;
  - if it is blank, return null;
  - strip trailing `/` characters;
  - call `delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce().andThen(b -> b.redirectUri(callback)))`.

  That last call is not thread-safe per request, so do NOT set it per request. Set the customizer ONCE in the constructor, with a lambda that reads the supplier each time:

```java
delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
        .andThen(builder -> builder.redirectUri(callbackUrl())));
```

`resolve` returns null up front when `callbackUrl()` is null. `callbackUrl()` returns null for a blank absolute URL, and otherwise `stripEnd(absolute, "/") + SsoPaths.CALLBACK`.

Run. Expected: PASS.

- [ ] **Step 4: Write the failing `SsoHandlersTest`.**

```java
@Test void successRedirectsToTheStoredReturnPathAndClearsIt() { /* session attr "/roller/blog/" ->
   redirect "/roller/blog/", attribute removed; INFO line "roller.sso" contains the username and "new" */ }
@Test void successWithoutAStoredPathGoesToTheSiteRoot() { /* ctx "/roller" -> "/roller/" */ }
@ParameterizedTest
@CsvSource({"sso_refused,refused", "sso_failed,failed", "authorization_request_not_found,failed",
            "invalid_request,failed", "invalid_token_response,failed", "access_denied,failed"})
void failureMapsTheOAuth2ErrorToALoginCode(String oauthError, String code) {
    // -> redirect ctx + "/roller-ui/login.rol?sso=" + code; one WARN line with the code; no exception text
}
@Test void aNonOAuth2FailureMapsToFailed() { /* BadCredentialsException -> sso=failed */ }
@Test void bothHandlersReleaseThePersistenceSessionExactlyOnce() { /* verify(weblogger, times(1)).release() each */ }
```

Implement both handlers with `response.sendRedirect(...)` and `finally { release }`. The success log line is `log.info("Reader {} signed in ({} account)", name, newAccount ? "new" : "existing")`. The failure line is `log.warn("Reader sign-in refused or failed: {} ({})", code, oauthErrorCode)`; never log the exception's message, which can contain provider text. Run. Expected: PASS.

- [ ] **Step 5: Write the failing `SsoLoginWiringTest`.** Base it on `SecurityConfigTest`'s `AnnotationConfigWebApplicationContext` harness. Register `SecurityConfig` plus a test `@Configuration` that provides:
  - an `SsoLogin` bean, built from a stub `ClientRegistrationRepository` whose registration's authorization, token and JWK URIs point at `http://provider.test`;
  - a stub `ReaderOidcUserService`;
  - an absolute-URL supplier returning `https://blog.example`.

  The `StubController` serves `/roller-ui/login.rol`. A second context, built without the `SsoLogin` bean, covers criterion 1.

```java
@Test void ssoOffAnswers404OnAllThreePaths() throws Exception {                  // criterion 1
    for (String p : List.of("/roller-ui/sso/start", "/roller-ui/sso/authorize/oidc", "/roller-ui/sso/callback")) {
        offMvc.perform(get(p)).andExpect(status().isNotFound());
    }
}
@Test void authorizeRedirectsToTheProviderWithStateNoncePkceAndTheConfiguredCallback() throws Exception {
    MvcResult r = onMvc.perform(get("/roller-ui/sso/authorize/oidc").header("Host", "docker2.lan:8080"))
            .andExpect(status().is3xxRedirection()).andReturn();
    UriComponents u = UriComponentsBuilder.fromUriString(r.getResponse().getRedirectedUrl()).build();
    assertEquals("provider.test", u.getHost());
    assertEquals("https://blog.example/roller-ui/sso/callback",
            URLDecoder.decode(u.getQueryParams().getFirst("redirect_uri"), UTF_8));
    assertNotNull(u.getQueryParams().getFirst("state"));
    assertNotNull(u.getQueryParams().getFirst("nonce"));
    assertEquals("S256", u.getQueryParams().getFirst("code_challenge_method"));
}
@Test void callbackWithAForgedStateFailsClosed() throws Exception {             // criterion 10
    onMvc.perform(get("/roller-ui/sso/callback").param("code", "c").param("state", "forged"))
         .andExpect(redirectedUrl("/roller-ui/login.rol?sso=failed"));
}
@Test void callbackWithNeitherCodeNorStateFailsClosed() { /* criterion 11 (second half) */ }
@Test void callbackNeverSetsARememberMeCookie() { /* Review Focus 3: drive a full callback with a stubbed token
   response (MockRestServiceServer is not reachable from the filter's RestClient: instead configure the
   oauth2Login's accessTokenResponseClient via SsoLogin's test constructor to return a canned
   OAuth2AccessTokenResponse with an id_token signed by a test RSA key whose JWK the stub registration exposes
   through a JwtDecoderFactory override) with param _spring_security_remember_me=on; assert 302 to the stored
   return path and no Set-Cookie named "remember-me" (or Roller's configured remember-me cookie name).
   If wiring a signed id_token proves disproportionate, assert instead that the OAuth2LoginAuthenticationFilter in
   the built chain holds a NullRememberMeServices -- and say so in the report. */ }
```

The `SsoLogin.applyTo` contents. `SsoLogin` takes its collaborators in its constructor. A package-private test constructor also accepts an `OAuth2AccessTokenResponseClient`, which may be null to mean the default.

```java
public void applyTo(HttpSecurity http) throws Exception {
    http.oauth2Login(oauth -> {
        oauth.loginPage(SsoPaths.LOGIN_PAGE)
             .clientRegistrationRepository(registrations)
             .authorizationEndpoint(a -> a.baseUri(SsoPaths.AUTHORIZE_BASE)
                     .authorizationRequestResolver(resolver))
             .redirectionEndpoint(r -> r.baseUri(SsoPaths.CALLBACK))
             .userInfoEndpoint(u -> u.oidcUserService(userService))
             .successHandler(successHandler)
             .failureHandler(failureHandler)
             // No remember-me for single sign-on (spec, Session and cookies). The shared
             // RememberMeServices from the form-login chain would otherwise be applied to
             // this filter by AbstractAuthenticationFilterConfigurer.
             .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                 @Override
                 public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                     filter.setRememberMeServices(new NullRememberMeServices());
                     return filter;
                 }
             });
        if (tokenResponseClient != null) {
            oauth.tokenEndpoint(t -> t.accessTokenResponseClient(tokenResponseClient));
        }
    });
    http.addFilterBefore(startFilter, OAuth2AuthorizationRequestRedirectFilter.class);
}
```

`SecurityConfig.securityFilterChain` gains a parameter `ObjectProvider<SsoLogin> ssoLogin`, and before `return http.build();`:

```java
// Reader sign-in (single sign-on): configured only when SSO is on -- see SsoConfig.
for (SsoLogin sso : ssoLogin) {
    sso.applyTo(http);
}
```

`ObjectProvider` is iterable, and that `for` needs no lambda that could throw a checked exception. `SsoConfig` is a `@Configuration` with:
- a `@Bean SsoSettings ssoSettings()`, which is `SsoSettings.fromConfig()`;
- the rest under `@Conditional(SsoEnabledCondition.class)`, a nested `Condition` returning `SsoSettings.fromConfig().enabled()`;
- `@Bean`s for the repository, the resolver (supplier `() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl")`), `ReaderAccounts(@Lazy Weblogger)`, `ReaderOidcUserService`, both handlers, the start filter, `SsoLogin`, and the self-check;
- the self-check started from an `ApplicationListener<ApplicationReadyEvent>` bean, with delay 2000 ms.

**`SsoConfig` must be picked up the way `SecurityConfig` is.** Check how the boot package's `@Configuration` classes are found (component scan from the `@SpringBootApplication` class, or an explicit `@Import`) and follow it. The wiring test proves the `SsoLogin` bean exists only when SSO is on. A second small test proves the `SsoSettings` bean always exists, because `LoginController` (Task 5) needs it.

Register the start filter so Boot does NOT also register it as a container-wide filter. Mirror `apiTokenAuthFilterRegistration`: a disabled `FilterRegistrationBean` (see `SecurityConfigTest#apiTokenAuthFilterIsNotAutoRegisteredAsAContainerWideFilter` and add the same test for `SsoStartFilter`).

Add `server.servlet.session.cookie.same-site=lax` to `application.properties` with a comment: "Lax, never Strict: the return from the sign-in provider is a cross-site navigation, and Strict drops the session that holds the OAuth2 state (reader sign-in spec, criterion 9; Wikantik lost sessions to this)". Pin it in the properties test.

- [ ] **Step 6: Run** `SsoLoginWiringTest`, `SecurityConfigTest`, `ApiSecurityChainTest`, the four new SSO unit classes and the properties test, then the static gates. Expected: PASS.

- [ ] **Step 7: Commit.** `feat(sso): oauth2Login wiring, start filter, handlers, SameSite=Lax`.

---

### Task 5: Login page: provider button and error messages

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/LoginController.java`
- Modify: `app/src/main/webapp/WEB-INF/jsps/core/Login.jsp`
- Modify: `app/src/main/resources/ApplicationResources.properties`
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/core/LoginControllerTest.java` (extend if it exists, otherwise create)

**Interfaces:**
- Consumes: the `SsoSettings` bean (Task 4's `SsoConfig`, always present).
- Model attributes:
  - `ssoEnabled` (boolean);
  - `ssoLabel` (String, HTML-escaped by the controller);
  - an error added through `addError(model, "login.sso." + code, request)` for `code` in {`unavailable`, `failed`, `refused`}; any other value is ignored.

- [ ] **Step 1: Write the failing test.**

```java
@Test void ssoOnShowsTheProviderButtonWithAnEscapedLabel() {
    LoginController c = new LoginController(new SsoSettings("id", "s", SsoSettings.GOOGLE_DISCOVERY, "<b>G</b>"));
    Model m = new ExtendedModelMap();
    c.execute(new MockHttpServletRequest(), m, null, null);
    assertEquals(Boolean.TRUE, m.getAttribute("ssoEnabled"));
    assertEquals("&lt;b&gt;G&lt;/b&gt;", m.getAttribute("ssoLabel"));
}
@Test void ssoOffShowsNoButton() { /* blank id -> ssoEnabled false */ }
@ParameterizedTest @ValueSource(strings = {"unavailable", "failed", "refused"})
void knownCodesShowTheirMessage(String code) { /* errors contain "login.sso." + code */ }
@Test void unknownCodesAreIgnored() { /* "<script>" -> no error added */ }
```

The `execute` signature gains `@RequestParam(value = "sso", required = false) String sso`. Keep `error` working as today. Match how `LoginController` is constructed today: if it uses a no-arg constructor, add a constructor taking `SsoSettings`, and update any test or config that builds it.

- [ ] **Step 2: Run it.** Expected: FAIL. Then implement the controller and the JSP. Below the password form, before the "forgot password" link:

```jsp
<c:if test="${ssoEnabled}">
    <p class="mt-3">
        <a class="btn btn-secondary" id="ssoSignIn" href="<c:url value='/roller-ui/sso/start'/>">
            <spring:message code="loginPage.ssoSignIn" arguments="${ssoLabel}"/>
        </a>
    </p>
</c:if>
```

Message keys, verbatim:

```properties
loginPage.ssoSignIn=Sign in with {0}
login.sso.unavailable=Sign-in with your account provider is not available right now. Please try again later.
login.sso.failed=Sign-in did not complete. Please try again.
login.sso.refused=This account cannot sign in here. It may be disabled, or the provider did not confirm its email address.
```

Use the existing button-class conventions on the login page. If `docs/dev/admin-ui.md` names a different quiet-button class, use that one. Do NOT add an `onclick`.

- [ ] **Step 3: Run** `LoginControllerTest`, `MessageKeyTest`, `MessagePlaceholderContractTest`, `MessageFormatRegressionTest`, `MessageArgumentEscapingTest` and `JspConsistencyTest` (if they exist), then the static gates. Expected: PASS.

- [ ] **Step 4: Commit.** `feat(sso): login page offers provider sign-in and explains SSO errors`.

---

### Task 6: Authorization: readers reach nothing an author can

**Files:**
- Test (create): `app/src/test/java/org/apache/roller/weblogger/ui/controllers/ReaderRefusalSweepTest.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/TokensApi.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/MembersController.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/model/UtilitiesModel.java`
- Modify: `app/src/main/webapp/themes/frontpage/_header.vm`
- Modify: `ApplicationResources.properties`
- Test: extend `TokensApiTest`, `MembersControllerTest`, `UtilitiesModelTest`. Create a frontpage header render test if none exists; look for an existing frontpage `_header.vm` render test first (`grep -rl "fd-topbar" app/src/test`).
- Test (create): `app/src/test/java/org/apache/roller/weblogger/ui/rendering/util/cache/ReaderCacheKeyTest.java`

**Interfaces:**
- Consumes (Task 1): role `reader`, which implies no global action.
- Produces: `UtilitiesModel.isUserAuthor()`, true when the request's user holds the global `login` action.

- [ ] **Step 1: Write the failing sweep** (criterion 22). It is reflective, so a new controller is covered automatically.
  - Use `ClassPathScanningCandidateComponentProvider(false)` with `AssignableTypeFilter(UISecurityEnforced.class)` over `org.apache.roller.weblogger.ui`, keeping concrete classes only.
  - Instantiate each through its public constructor with the most parameters, passing `Mockito.mock(paramType)` for each parameter. A class that cannot be built that way is a test failure naming the class; do not skip it silently.
  - For every instance whose `isUserRequired()` is true, run the REAL `RollerHandlerInterceptor.enforceSecurity` path, the same way `RollerHandlerInterceptorTest` drives it (read that test first and reuse its setup). The authenticated user is a reader:
    - `UserManager.checkPermission(GlobalPermission, user)` answers with `GlobalPermission.impliedByRoles(user, List.of("reader")).implies(perm)`;
    - the weblog-permission check answers false, because a reader holds no weblog permission.
  - Assert the interceptor refuses, either `preHandle` false or a 403/404 response.

```java
@TestFactory
Stream<DynamicTest> everyUserRequiredControllerRefusesAReader() {
    return securedControllers().stream()
            .filter(UISecurityEnforced::isUserRequired)
            .map(c -> DynamicTest.dynamicTest(c.getClass().getSimpleName(),
                    () -> assertFalse(interceptorAllows(c, readerUser()),
                            c.getClass().getName() + " let a reader through")));
}
@Test void theSweepFoundTheControllersItShould() {
    // guards against a scan that silently finds nothing
    Set<String> names = securedControllers().stream().map(c -> c.getClass().getSimpleName()).collect(toSet());
    assertTrue(names.containsAll(Set.of("MainMenuController", "ProfileController", "EntryEditController",
            "UserAdminController", "MediaApi")), names.toString());
}
```

Confirm with grep that each of those five really implements `UISecurityEnforced`, directly or through `BaseController`/`BaseApiController`. Replace any that does not with one that does, keeping at least one each from `core`, `editor`, `admin` and `restapi.v1`.

- [ ] **Step 2: Run it.** Expected: PASS for every controller. This is a characterisation sweep for the new role; say so in its javadoc. It is still worth having because it fails the day a controller lowers its requirement. **Make it fail once on purpose to prove it can:** temporarily return `List.of()` from `BaseController.requiredGlobalPermissionActions` and watch it fail, then revert. Report that you did.

- [ ] **Step 3: Write the failing tests for the guards.**
  - **`TokensApiTest`:** `aReaderCannotMintAToken`. A Basic-authenticated principal whose user holds only `reader` gets 403 problem+json "This account cannot use the API." (criterion 24).
  - **`MembersControllerTest`:** `inviteRefusesAReaderOnlyAccount`. A field error `inviteMember.error.readerAccount` on `grantUserName`, and no permission created (criterion 25). An account holding `reader` and `editor` is invitable.
  - **`UtilitiesModelTest`:**
    - `isUserAuthor` is true for a user holding `login`;
    - it is false for a reader and for anonymous.
  - **Frontpage header** (criterion 26). Render `themes/frontpage/_header.vm` the way the existing frontpage render tests do:
    - an author sees `/roller-ui/menu.rol`;
    - a reader sees the logout link and no `menu.rol`;
    - anonymous sees the login link.
  - **`ReaderCacheKeyTest`** (criterion 27). A `WeblogPageRequest`/`ParsedRequest` built from a request whose principal is a Spring `OAuth2AuthenticationToken` wrapping a `RollerOidcUser` named `reader-abcdefghij` gives:
    - `getAuthenticUser()` = `"reader-abcdefghij"`;
    - `WeblogPageCache`'s key contains `/user=reader-abcdefghij`;
    - an anonymous request's key does not.

- [ ] **Step 4: Run.** Expected: FAIL for the guards, which don't exist yet. Implement:
  - **`TokensApi.requireUser`**, after the null check: if `!weblogger.getUserManager().checkPermission(new GlobalPermission(List.of(GlobalPermission.LOGIN)), user)`, throw `ApiException.forbidden("This account cannot use the API.")`.
  - **`MembersController` invite**, after the user is found: if the user holds no `login` global action, add field error `inviteMember.error.readerAccount` (same mechanism as `userNotFound`).
  - **`UtilitiesModel.isUserAuthor()`:** `parsedRequest.getAuthenticUser() != null && userManager.checkPermission(new GlobalPermission(List.of(GlobalPermission.LOGIN)), parsedRequest.getUser())`. Catch `WebloggerException` and return false (display path, fail to "not an author").
  - **`_header.vm`**:

```velocity
        #if(!$utils.isUserAuthenticated())
        <a href="$url.login">Login</a>
        #else
        #if($utils.isUserAuthor())
        <a href="$url.absoluteSite/roller-ui/menu.rol">Main Menu</a>
        #end
        <a href="$url.logout">Logout</a>
        #end
```

  - **Message key:** `inviteMember.error.readerAccount=That account is a reader. Make it an editor in User Admin before inviting it.`

- [ ] **Step 5: Run** every test touched, plus `RollerHandlerInterceptorTest` and `ApiSecurityRefusalTest`, then the static gates. Expected: PASS.

- [ ] **Step 6: Commit.** `feat(sso): readers reach nothing an author can (sweep, tokens, invite, frontpage menu)`.

---

### Task 7: Password paths refuse an account bound to SSO

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/core/security/RollerUserDetailsService.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/PasswordResetController.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/core/ProfileController.java`, plus its JSP (find it with `grep -rl "passwordText" app/src/main/webapp/WEB-INF/jsps/core`)
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java`
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp`
- Modify: `ApplicationResources.properties`
- Test: extend `RollerUserDetailsServiceTest`, `PasswordResetControllerTest`, `ProfileControllerTest` and `UserEditControllerTest` (create any that is missing; match the existing controller-test style in that package)

**Interfaces:**
- Consumes (Task 1): `User.isSsoBound()`, `User.resetPassword` throwing for bound accounts, and `getUserByEmail` covering local accounts only.
- Produces: the model attribute `ssoBound` (boolean) on the Profile and UserEdit pages.

- [ ] **Step 1: Write the failing tests** (criterion 28, plus 33 end-to-end at controller level).
  - `RollerUserDetailsServiceTest#aBoundAccountIsInvisibleToPasswordSignIn`: `loadUserByUsername("reader-x")` for a bound user throws `UsernameNotFoundException`, the same answer as an unknown user, so it leaks nothing.
  - `PasswordResetControllerTest`:
    - `forgotPasswordByAReaderUsernameSendsNothing`: the background runnable runs inline in the test, as the existing tests do, and `issueToken` is never called.
    - `forgotPasswordByASharedEmailReachesTheLocalAccount`: the manager's `getUserByEmail` is mocked to return the local account, and `issueToken` is called for it (criterion 33 at the controller seam; Task 1 pins the query).
    - `resetSaveRefusesABoundAccount`: `consume` returns a bound user; the page renders `resetTokenValid=false`, and `resetPassword` and `saveUser` are never called.
  - `ProfileControllerTest#aBoundAccountCannotChangeItsPassword`: a save with `passwordText`/`passwordConfirm` set adds error `userSettings.error.ssoPassword` and changes nothing, and the GET model has `ssoBound=true`.
  - `UserEditControllerTest`:
    - `savingAPasswordForABoundAccountIsRefused`: error `userAdmin.error.ssoPassword`, no `resetPassword`.
    - `sendPasswordLinkIsRefusedForABoundAccount`: error `userAdmin.error.ssoPassword`, `issueToken` never called.
    - `modifyUserExposesSsoBound`.

- [ ] **Step 2: Run.** Expected: FAIL. Implement:
  - **`RollerUserDetailsService`:** after the null check, `if (userData.isSsoBound()) { throw new UsernameNotFoundException("ERROR no password user: " + userName); }`. Keep the message shape of the existing not-found branch.
  - **`PasswordResetController.findUser`:** return null for a bound user found by username. The email route is already local-only.
  - **`resetSave`:** after `consume`, `if (user.isSsoBound()) { model.addAttribute("resetTokenValid", false); return ".ResetPassword"; }`.
  - **`ProfileController`:**
    - GET adds `ssoBound`;
    - save, when the account is bound and either password field is non-empty, adds `userSettings.error.ssoPassword` in `myValidate`;
    - the JSP wraps the two password rows in `<c:if test="${not ssoBound}">`.
  - **`UserEditController`:**
    - `modifyUserExecute` and `modifyUserSave` add `ssoBound`;
    - `myValidate` (or the save path, before `copyTo`) adds `userAdmin.error.ssoPassword` when the account is bound and `bean.getPassword()` is non-empty;
    - `sendPasswordLink` adds the same error and returns before issuing when the account is bound;
    - `UserEdit.jsp` wraps the password row and the send-link form in `<c:if test="${not ssoBound}">`.
  - **Message keys:**

```properties
userSettings.error.ssoPassword=This account signs in with single sign-on and has no password.
userAdmin.error.ssoPassword=This account signs in with single sign-on; it cannot be given a password.
```

- [ ] **Step 3: Run** the four test classes, `EditorJspEscapingTest`, `JspConsistencyTest` and the message ratchets, then the static gates. Expected: PASS.

- [ ] **Step 4: Commit.** `feat(sso): an account bound to SSO never gets or uses a password`.

---

### Task 8: User Admin promotion, admin refusal, and the API's `sso` flag

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/CreateUserBean.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/admin/UserEditController.java`
- Modify: `app/src/main/webapp/WEB-INF/jsps/admin/UserEdit.jsp`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/dto/AdminDtos.java`
- Modify: `docs/api/README.md`, user view
- Modify: `ApplicationResources.properties`
- Test: extend `UserEditControllerTest`, `AdminApiTest` and `AdminDtosTest` (where they exist), and `McpDocsTest`/`McpCoverageTest` if they pin `UserView`'s fields

**Interfaces:**
- Consumes: Task 7's `ssoBound` model attribute.
- Produces:
  - `CreateUserBean.isWriter()` / `setWriter(boolean)`;
  - `AdminDtos.UserView(String userName, String screenName, String emailAddress, boolean enabled, List<String> globalRoles, boolean sso)`, with the new component last.

- [ ] **Step 1: Write the failing tests** (criteria 29, 30).
  - `UserEditControllerTest`:
    - `aBoundAccountCannotBeMadeAdmin`: save with `bean.administrator=true` gives error `userAdmin.error.ssoAdmin`, and `grantRole("admin")` is never called.
    - `canWriteGrantsAndRevokesEditorOnABoundAccount`: `writer=true` calls `grantRole("editor")`; `writer=false` on an account holding `editor` calls `revokeRole("editor")`.
    - `canWriteIsIgnoredForLocalAccounts`: a local account's editor role is untouched whatever `writer` says.
    - `modifyUserPrefillsWriterFromTheEditorRole`.
  - `AdminApiTest`:
    - `listUsersReportsSso`: a bound user gives `"sso":true` and a local user `"sso":false`.
    - `patchCanDisableAReader`.

- [ ] **Step 2: Run.** Expected: FAIL. Implement:
  - **`CreateUserBean`:** add `writer`. `copyFrom` does not touch it; the controller sets it.
  - **`UserEditController.modifyUserExecute`:** `bean.setWriter(mgr.hasRole("editor", user))`.
  - **`modifyUserSave`**, when `user.isSsoBound()`:
    - if `bean.isAdministrator()`, add `userAdmin.error.ssoAdmin` and return `.UserEdit` before saving;
    - otherwise grant or revoke `editor` to match `bean.isWriter()`;
    - skip the admin grant/revoke block entirely, since a bound account never holds admin.
  - **`UserEdit.jsp`:**
    - a marker at the top when `ssoBound`: `<p class="form-text" id="ssoBoundMarker"><spring:message code="userAdmin.ssoBound"/></p>`;
    - the admin checkbox row wrapped in `<c:if test="${not ssoBound}">`;
    - a new row shown only when `ssoBound`: checkbox `bean.writer` with label `userAdmin.canWrite`.
  - **`AdminDtos`:** add `sso` to `UserView`. `toView` passes `user.isSsoBound()`. Every caller of the `UserView` constructor compiles again.
  - **`docs/api/README.md`:** in the user view's field table, add `sso`: true when the account signs in with single sign-on (Google). Note: "An email address patched on such an account is replaced at its next sign-in."
  - **Message keys:**

```properties
userAdmin.ssoBound=Signs in with Google (single sign-on). This account has no password.
userAdmin.canWrite=Can write (editor)
userAdmin.error.ssoAdmin=An account that signs in with single sign-on cannot be an administrator.
```

- [ ] **Step 3: Run** the touched tests and `McpDocsTest`/`McpCoverageTest`, then the static gates. Expected: PASS.

- [ ] **Step 4: Commit.** `feat(sso): User Admin promotes readers to editors, never admins; API reports sso`.

---

### Task 9: Browser integration tests against Keycloak at both context paths

**Files:**
- Modify: `it-selenium/pom.xml`
- Create: `it-selenium/src/test/resources/keycloak/roller-it-realm.json`. It is a test fixture; its client secret and user passwords are test-only values, documented as such in a `_comment` field.
- Modify: `it-selenium/src/test/resources/roller-it.properties`
- Create: `it-selenium/src/test/java/org/apache/roller/it/SsoSignInIT.java`
- Create: `it-selenium/src/test/java/org/apache/roller/it/support/KeycloakLoginPage.java`
- Modify: `it-selenium/src/test/java/org/apache/roller/it/support/RollerIT.java`. Move `VirtualHostIT.setSiteAbsoluteUrl(String)` here as `protected`, and make VirtualHostIT use it so CPD stays quiet.
- Modify: `docs/dev/browser-its.md`, one paragraph: the Keycloak container, its realm, its port property, and the GLOBAL_CONFIG lock rule for `site.absoluteurl`.

**Interfaces:**
- Consumes: everything above.
- Produces: none.

- [ ] **Step 1: Build the fixture.**
  - **`pom.xml`:**
    - add `it.kc.port` to `reserve-it-ports`;
    - add `<it.keycloak.image>quay.io/keycloak/keycloak:26.0</it.keycloak.image>` to properties;
    - add a second `<image>` to docker-maven-plugin, modelled on Wikantik's (`~/source/jspwiki/wikantik-it-tests/wikantik-it-test-sso/pom.xml` lines 150-180; read it):
      - `<cmd>start-dev --import-realm --http-port=8080</cmd>`;
      - `KC_HEALTH_ENABLED=true`;
      - port `${it.kc.port}:8080`;
      - volume `${project.basedir}/src/test/resources/keycloak:/opt/keycloak/data/import:ro`;
      - wait on `http://localhost:${it.kc.port}/realms/roller-it/.well-known/openid-configuration`, time 120000;
      - a per-run container name, following the postgres image's `containerNamePattern` convention and the sweep's prefix (read `docs/dev/browser-its.md`, "The IT harness cleans up by identity").

    `pg-start`/`pg-stop` already start and stop every image in the plugin's configuration. Confirm that, and add `removeVolumes` coverage.
  - **Realm `roller-it`:**
    - `sslRequired: none`;
    - client `roller-it`: confidential, standard flow, `redirectUris: ["*"]` (Keycloak rejects a wildcard in the port position, and the IT port is dynamic), `webOrigins: ["*"]`;
    - user `reader-it` (`emailVerified: true`, firstName `Ivy`, lastName `Reader`, email `reader-it@example.com`);
    - user `unverified-it` (`emailVerified: false`);
    - user `noname-it` (no first or last name, email `noname-it@example.com`, verified).
  - **`roller-it.properties`:**

```properties
sso.oidc.client.id=roller-it
sso.oidc.client.secret=<the realm's test secret>
sso.oidc.discovery.uri=http://localhost:${it.kc.port}/realms/roller-it/.well-known/openid-configuration
sso.provider.label=Keycloak
```

- [ ] **Step 2: Write `SsoSignInIT`** (extends `RollerIT`; class-level `@ResourceLock(RollerIT.GLOBAL_CONFIG)`, exclusive, because it sets `site.absoluteurl` to `baseUrl()` in a once-per-class fixture and restores it in `@AfterAll` through `inBrowserAfterAll`, as VirtualHostIT does). Tests:
  - `aVerifiedReaderSignsInAndLandsWhereTheyStarted` (5, 12):
    1. Open `/roller-ui/sso/start?return=<ctx>/<WEBLOG_HANDLE>/`.
    2. Log in on Keycloak (`KeycloakLoginPage`).
    3. Assert the browser URL ends with `/<WEBLOG_HANDLE>/`.
    4. Log out; assert `/roller-ui/menu.rol` now redirects to login.
  - `theLoginPageOffersTheProvider` (2): the login page shows `#ssoSignIn` with the text "Sign in with Keycloak". Clicking it reaches the Keycloak login form, and finishing it lands at `<ctx>/`.
  - `aReaderIsRefusedEverythingAnAuthorCanReach` (23): signed in as `reader-it`, each of these gives the access-denied page or a 403:
    - `/roller-ui/menu.rol`
    - `/roller-ui/profile.rol`
    - `/roller-ui/createWeblog.rol`
    - `/roller-ui/authoring/entries.rol?weblog=<WEBLOG_HANDLE>`
    - `/roller-ui/admin/userAdmin.rol`

    Use `Routes` if it names these.
  - `theSameReaderTwiceIsOneAccount` (13): sign in, log out, sign in again. Through `it_admin`, User Admin's search for the reader's email finds exactly one `reader-...` account; or use `GET /api/v1/admin/users` with an admin token if ApiIT shows the pattern.
  - `anUnverifiedEmailIsRefused` (17): `unverified-it` lands on the login page with the `login.sso.refused` text.
  - `aReaderWithoutANameGetsTheEmailLocalPart` (18): `noname-it`'s screen name, seen on the admin's User Edit page, is `noname-it`.
  - `aDisabledReaderIsRefused` (16): the admin disables the reader in User Edit; the next sign-in gives refused; re-enable in `finally`.
  - `aPromotedReaderReachesTheEditorMenu` (29): the admin ticks "Can write"; the reader signs in and `/roller-ui/menu.rol` renders. Revoke in `finally`.
  - `forgedStateAndGarbageCodeFailClosed` (10, 11): open `<ctx>/roller-ui/sso/callback?code=x&state=forged` and assert login `sso=failed`. For the garbage code, start a real sign-in but intercept: open `/roller-ui/sso/start`, read the `state` from the Keycloak URL, then open `<ctx>/roller-ui/sso/callback?code=garbage&state=<that state>`, and assert `sso=failed`.
  - `theSessionIdRotatesAndTheCookieIsLax` (8, 9): read `JSESSIONID` through Selenium cookies before and after sign-in; they differ. For `SameSite`, `java.net.http.HttpClient` GET of the login page shows a `Set-Cookie` containing `SameSite=Lax`.
  - `aReaderSessionCookieAuthenticatesNothingUnderApi` (24): with the reader signed in, `fetch('<ctx>/api/v1/me')` from the page through `executeJavaScript` gives 401.

  `KeycloakLoginPage.signIn(user, password)` fills `#username` and `#password` and clicks `#kc-login`. Model it on Wikantik's `KeycloakLoginPage`.

- [ ] **Step 3: Run at the root, then under `/roller`.**
  - `while pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; do sleep 10; done; mvn -q verify -Pit -pl it-selenium -am -Dit.test=SsoSignInIT`
  - then the same with `-Dit.context.path=roller`.

  Use the module's real single-class property; `docs/dev/browser-its.md` names it. Expected: PASS at both. Then run the FULL IT suite once at `/` to prove SSO being on harms no other class. Expected: all green.

- [ ] **Step 4: Commit.** `test(it): reader sign-in against Keycloak at both context paths`.

---

### Task 10: Documentation

**Files:**
- Create: `docs/dev/sso.md`
- Modify: `CLAUDE.md`, one row in "Where the rest lives": `Reader sign-in (SSO)` → `docs/dev/sso.md`. Keep the file under ~20 KB.
- Modify: `docker_deployment.md`, new section "Reader sign-in (Google)"
- Modify: `deploy/.env.example`, the four variables commented out with one line each
- Modify: `docs/dev/entries.md`, the "Comments" paragraph
- Test: `ProductionComposeTest` and any test that pins `deploy/.env.example` content; run them

- [ ] **Step 1: Write `docs/dev/sso.md`.** Read it again for accuracy against the code you have. It covers:
  - the flow, with paths;
  - binding by `(iss, sub)`, and why it is never by name or email;
  - `addReader` vs `addUser`;
  - the reader role holding no global action;
  - the four properties;
  - lazy discovery and the self-check;
  - the persistence-release rule at order 40;
  - SameSite=Lax;
  - the password refusals;
  - User Admin promotion;
  - the IT fixture;
  - the hazards found while building it (the release rule, `getUserByEmail` local-only, remember-me disabled on the OAuth2 filter).

- [ ] **Step 2: `docker_deployment.md`:** copy the spec's "Production setup (Jake's checklist)" five steps. Add: the redirect URI must match exactly; the client secret goes only into `.env`; `ROLLER_SITE_ABSOLUTEURL` in `.env` is an alternative to setting the Absolute URL in Global Config.

- [ ] **Step 3: `deploy/.env.example`:**

```bash
# Reader sign-in with Google (optional). Off unless both ID and SECRET are set.
# Redirect URI to register at the provider: <site absolute URL>/roller-ui/sso/callback
# ROLLER_SSO_OIDC_CLIENT_ID=
# ROLLER_SSO_OIDC_CLIENT_SECRET=
# ROLLER_SSO_OIDC_DISCOVERY_URI=https://accounts.google.com/.well-known/openid-configuration
# ROLLER_SSO_PROVIDER_LABEL=Google
```

- [ ] **Step 4: `docs/dev/entries.md` "Comments":** replace "removed outright in W1 and is not coming back…" with: "The W1 comment subsystem was removed (V022) because nobody could reach it: there was no way for a reader to get an account. Readers now sign in with Google (`docs/dev/sso.md`), and comments for signed-in readers are the next roadmap step, designed fresh rather than restored." Keep the "A sweep for "comment" must leave alone" list.

- [ ] **Step 5: Run** `ProductionComposeTest` and any deploy-file pins. Expected: PASS. Commit: `docs(sso): developer notes, deployment checklist, env example`.
