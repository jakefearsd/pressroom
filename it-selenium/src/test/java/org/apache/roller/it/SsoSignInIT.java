/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.it;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.KeycloakLoginPage;
import org.apache.roller.it.support.RollerIT;
import org.apache.roller.it.support.Routes;
import org.apache.roller.it.support.Routes.Route;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reader sign-in end to end: a real browser, the packaged WAR, and a real OIDC provider
 * (the run's Keycloak container, realm {@code roller-it}, see it-selenium/pom.xml). Covers
 * the acceptance criteria the spec marks (I), numbered as in
 * docs/superpowers/specs/2026-10-11-reader-sign-in-design.md; criterion 1 (SSO off) is
 * unit-tested only, because SSO is on for this whole suite.
 *
 * <p><b>The Absolute URL is set for the whole class.</b> The callback Roller hands the
 * provider is built from {@code site.absoluteurl}, and with it blank every sign-in
 * answers {@code sso=unavailable}. It is global, so the class holds
 * {@link RollerIT#GLOBAL_CONFIG} exclusively, sets it to {@link #baseUrl()} once (the
 * guarded {@code @BeforeEach}, as {@code VirtualHostIT} does, because a
 * {@code @BeforeAll} has no browser yet) and restores it in {@code @AfterAll}.
 *
 * <p><b>Each test starts at the Keycloak form.</b> {@code BrowserHealth.attach()} clears
 * every cookie, the provider's included, before each test, and {@link #logout()} does the
 * same mid-test, so a sign-in never rides a provider session left by an earlier one.
 *
 * <p><b>"Signed in" is read from the server, not the page.</b> A reader's session is
 * refused {@code menu.rol} with a 403; an anonymous one is sent to log in with a 302.
 * {@link #menuStatus} asks with the browser's own session cookie, which is what tells a
 * reader who is in from one who was bounced.
 *
 * <p><b>Accounts are read through the admin API</b> ({@link #accountsWithEmail}), which
 * reports the username, screen name, roles and {@code sso} of each account, and is the
 * one place a reader's {@code reader-xxxxxxxxxx} username can be found by email.
 *
 * <p>The tests that change an account (disable, promote) use Keycloak users of their own,
 * {@code disabled-it} and {@code writer-it}, so a failed restore cannot leak into the
 * others; {@code reader-it} is only ever signed in.
 *
 * <p>Acceptance testing: every behaviour here was driven out by unit tests first, so a
 * failure means the assembled WAR does not behave as those said it would. The first run
 * found one: a promoted reader was sent back to log in from {@code menu.rol}, because
 * the UI interceptor resolved only {@code UserDetails} principals (docs/dev/sso.md,
 * "Hazards found while building it").
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(RollerIT.GLOBAL_CONFIG)
class SsoSignInIT extends RollerIT {

    private static final String START = "/roller-ui/sso/start";
    private static final String CALLBACK = "/roller-ui/sso/callback";
    private static final String LOGIN = "/roller-ui/login.rol";
    private static final String MENU = "/roller-ui/menu.rol";
    private static final String USER_ADMIN = "/roller-ui/admin/userAdmin.rol";

    /** Keycloak users in src/test/resources/keycloak/roller-it-realm.json. */
    private static final String READER = "reader-it";
    private static final String UNVERIFIED = "unverified-it";
    private static final String NONAME = "noname-it";
    private static final String WRITER = "writer-it";
    private static final String DISABLED = "disabled-it";

    /** {@code login.sso.refused} and {@code login.sso.failed}, as the login page shows them. */
    private static final String REFUSED_TEXT = "This account cannot sign in here.";
    private static final String FAILED_TEXT = "Sign-in did not complete.";

    /** The pages criterion 23 names, by their {@link Routes} paths. */
    private static final List<String> AUTHOR_PATHS = List.of(
            "/roller-ui/menu.rol",
            "/roller-ui/profile.rol",
            "/roller-ui/createWeblog.rol",
            "/roller-ui/authoring/entries.rol",
            "/roller-ui/admin/userAdmin.rol");

    private static final int PAGE_SIZE = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Never follows a redirect: the 302 and its Location are what the assertions read. */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private boolean absoluteUrlSet;

    /** {@code site.absoluteurl} on arrival, restored by {@link #restoreTheAbsoluteUrl()}. */
    private String priorAbsoluteUrl;

    // ------------------------------------------------------------- fixture

    @BeforeEach
    void pointTheAbsoluteUrlAtThisSiteOnce() {
        if (absoluteUrlSet) {
            return;
        }
        loginAsAdmin();
        priorAbsoluteUrl = setSiteAbsoluteUrl(baseUrl());
        absoluteUrlSet = true;
        logout();
    }

    @AfterAll
    void restoreTheAbsoluteUrl() {
        if (!absoluteUrlSet) {
            return;
        }
        inBrowserAfterAll(() -> {
            loginAsAdmin();
            setSiteAbsoluteUrl(priorAbsoluteUrl);
            logout();
        });
    }

    // -------------------------------------------------------- criteria 5, 12

    /**
     * Criteria 5 and 12: a verified user lands on the page the sign-in started from,
     * signed in, as a new {@code reader-} account carrying the provider's name and email
     * and the {@code reader} role only; signing out ends that session on the server.
     */
    @Test
    void aVerifiedReaderSignsInAndLandsWhereTheyStarted() throws Exception {
        String landing = contextPath() + "/" + WEBLOG_HANDLE + "/";
        signInWithKeycloak(READER, landing);

        assertEquals(landing, currentPath(), "the sign-in did not return to where it started");
        String session = sessionCookie();
        assertEquals(403, menuStatus(session).statusCode(), "the reader is not signed in");

        JsonNode account = theOnlyAccountWith(emailOf(READER));
        assertTrue(account.get("userName").asString().matches("reader-[a-z0-9]{10}"),
                "not a reader- username: " + account);
        assertEquals("Ivy Reader", account.get("screenName").asString(), "the name claim is not the screen name");
        assertEquals(List.of("reader"), roles(account), "a new reader holds exactly the reader role");
        assertTrue(account.get("sso").asBoolean(), "the account is not bound to the provider: " + account);
        assertTrue(account.get("enabled").asBoolean(), "a new reader is disabled: " + account);

        logout();
        HttpResponse<String> afterSignOut = menuStatus(session);
        assertEquals(302, afterSignOut.statusCode(), "the signed-out session still authenticates");
        assertTrue(afterSignOut.headers().firstValue("Location").orElse("").contains(LOGIN),
                "the signed-out session is not sent to log in: " + afterSignOut.headers().map());
        openPath(MENU);
        assertEquals(contextPath() + LOGIN, currentPath(), "an anonymous browser reached the menu");
    }

    // ----------------------------------------------------------- criterion 2

    /** Criterion 2: the login page offers the provider under its configured label. */
    @Test
    void theLoginPageOffersTheProvider() throws Exception {
        openPath(LOGIN);
        $("#ssoSignIn").shouldHave(exactText("Sign in with Keycloak"));
        assertEquals(contextPath() + START, URI.create($("#ssoSignIn").getAttribute("href")).getPath());

        clickAndAwaitNewPage($("#ssoSignIn"));
        new KeycloakLoginPage().signIn(READER, passwordOf(READER));

        assertEquals(contextPath() + "/", currentPath(), "a sign-in with no return path lands on the site root");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "the reader is not signed in");
    }

    // ---------------------------------------------------------- criterion 23

    /**
     * Criterion 23: a signed-in reader is refused the menu, the profile, weblog creation,
     * an editor page and an admin page -- refused as a signed-in user (403, or the
     * access-denied page), not bounced to log in as an anonymous one would be.
     */
    @Test
    void aReaderIsRefusedEverythingAnAuthorCanReach() throws Exception {
        signInWithKeycloak(READER, contextPath() + "/");
        String session = sessionCookie();

        List<Route> routes = Routes.covered().stream()
                .filter(route -> AUTHOR_PATHS.contains(route.path()))
                .toList();
        assertEquals(AUTHOR_PATHS.size(), routes.size(), "Routes no longer names every path: " + routes);

        for (Route route : routes) {
            HttpResponse<String> response = get(route.url(), session);
            String location = response.headers().firstValue("Location").orElse("");
            assertTrue(response.statusCode() == 403
                            || (response.statusCode() == 302 && location.contains("/roller-ui/access-denied.rol")),
                    route + " answered a signed-in reader " + response.statusCode() + " " + location);

            BrowserHealth.current().expectRefusal(route.path());
            openPath(route.url());
            $(route.marker()).shouldNot(exist);
            assertNotEquals(contextPath() + LOGIN, currentPath(), route + " forgot the reader was signed in");
        }
    }

    // ---------------------------------------------------------- criterion 13

    /** Criterion 13: the same subject signing in twice is one account, not two. */
    @Test
    void theSameReaderTwiceIsOneAccount() throws Exception {
        signInWithKeycloak(READER, contextPath() + "/");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "the first sign-in failed");
        String first = theOnlyAccountWith(emailOf(READER)).get("userName").asString();
        logout();

        signInWithKeycloak(READER, contextPath() + "/");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "the second sign-in failed");
        assertEquals(first, theOnlyAccountWith(emailOf(READER)).get("userName").asString(),
                "the second sign-in did not resolve to the first one's account");
    }

    // ---------------------------------------------------------- criterion 17

    /** Criterion 17: a provider that does not vouch for the email is refused, and no account is made. */
    @Test
    void anUnverifiedEmailIsRefused() throws Exception {
        signInWithKeycloak(UNVERIFIED, contextPath() + "/");

        assertLoginPageSays("refused", REFUSED_TEXT);
        assertEquals(302, menuStatus(sessionCookie()).statusCode(), "a refused sign-in signed someone in");
        assertTrue(accountsWithEmail(emailOf(UNVERIFIED)).isEmpty(), "a refused sign-in created an account");
    }

    // ---------------------------------------------------------- criterion 18

    /** Criterion 18: with no name claim, the screen name is the email's local part. */
    @Test
    void aReaderWithoutANameGetsTheEmailLocalPart() throws Exception {
        signInWithKeycloak(NONAME, contextPath() + "/");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "the nameless reader is not signed in");
        logout();

        String userName = theOnlyAccountWith(emailOf(NONAME)).get("userName").asString();
        loginAsAdmin();
        openUserEdit(userName);
        $("#ssoBoundMarker").shouldBe(visible);
        $("input[name='bean.screenName']").shouldHave(value(NONAME));
        logout();
    }

    // ---------------------------------------------------------- criterion 16

    /** Criterion 16: a disabled bound account is refused, and no second account is made for it. */
    @Test
    void aDisabledReaderIsRefused() throws Exception {
        signInWithKeycloak(DISABLED, contextPath() + "/");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "the reader is not signed in");
        logout();
        String userName = theOnlyAccountWith(emailOf(DISABLED)).get("userName").asString();

        loginAsAdmin();
        setEnabled(userName, false);
        logout();
        try {
            signInWithKeycloak(DISABLED, contextPath() + "/");

            assertLoginPageSays("refused", REFUSED_TEXT);
            assertEquals(302, menuStatus(sessionCookie()).statusCode(), "a disabled reader was signed in");
            assertEquals(userName, theOnlyAccountWith(emailOf(DISABLED)).get("userName").asString(),
                    "the refused subject was given another account");
        } finally {
            loginAsAdmin();
            setEnabled(userName, true);
            logout();
        }
    }

    // ---------------------------------------------------------- criterion 29

    /**
     * Criterion 29: User Admin marks a bound account, offers "Can write" and no admin box,
     * and a reader given it signs in with the provider and reaches the editor menu --
     * still holding {@code reader}, and never {@code admin}.
     */
    @Test
    void aPromotedReaderReachesTheEditorMenu() throws Exception {
        signInWithKeycloak(WRITER, contextPath() + "/");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "before promotion a reader is refused the menu");
        logout();
        String userName = theOnlyAccountWith(emailOf(WRITER)).get("userName").asString();

        loginAsAdmin();
        openUserEdit(userName);
        $("#ssoBoundMarker").shouldBe(visible);
        $("#bean_administrator").shouldNot(exist);
        setWriter(userName, true);
        logout();
        try {
            signInWithKeycloak(WRITER, contextPath() + MENU);

            assertEquals(contextPath() + MENU, currentPath(), "the promoted reader did not reach the menu");
            $("#createWeblogLink").shouldBe(visible);
            List<String> roles = roles(theOnlyAccountWith(emailOf(WRITER)));
            assertTrue(roles.contains("editor") && roles.contains("reader") && !roles.contains("admin"),
                    "a promoted reader holds editor and keeps reader, never admin: " + roles);
        } finally {
            loginAsAdmin();
            setWriter(userName, false);
            logout();
        }
    }

    // ------------------------------------------------------ criteria 10, 11

    /**
     * Criteria 10 and 11: a callback whose {@code state} is not the one this session
     * issued, one whose {@code code} the provider never issued, and one with neither,
     * all answer {@code sso=failed} and sign no one in.
     *
     * <p>The forged state is sent while a real sign-in is pending in the session, so it
     * is refused for not matching, not merely for finding nothing to match.
     */
    @Test
    void forgedStateAndGarbageCodeFailClosed() throws Exception {
        startSignIn();
        openPath(CALLBACK + "?code=x&state=forged");
        assertLoginPageSays("failed", FAILED_TEXT);
        assertEquals(302, menuStatus(sessionCookie()).statusCode(), "a forged state signed someone in");

        String state = startSignIn();
        openPath(CALLBACK + "?code=garbage&state=" + encode(state));
        assertLoginPageSays("failed", FAILED_TEXT);
        assertEquals(302, menuStatus(sessionCookie()).statusCode(), "a garbage code signed someone in");

        openPath(CALLBACK);
        assertLoginPageSays("failed", FAILED_TEXT);
    }

    // ------------------------------------------------------- criteria 8, 9

    /**
     * Criteria 8 and 9: signing in issues a new session id (the anonymous one it started
     * from authenticates nobody afterwards), and the session cookie is {@code SameSite=Lax}
     * -- {@code Strict} would drop it on the return from the provider.
     */
    @Test
    void theSessionIdRotatesAndTheCookieIsLax() throws Exception {
        openPath(LOGIN);
        String before = sessionCookie();
        clickAndAwaitNewPage($("#ssoSignIn"));
        new KeycloakLoginPage().signIn(READER, passwordOf(READER));

        String after = sessionCookie();
        assertNotEquals(before, after, "the session id survived the sign-in");
        assertEquals(403, menuStatus(after).statusCode(), "the new session is not the reader's");
        assertEquals(302, menuStatus(before).statusCode(), "the pre-sign-in session id authenticates");

        HttpResponse<String> login = get(LOGIN, null);
        String cookie = login.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the login page set no session cookie: "
                        + login.headers().map()));
        assertTrue(cookie.contains("SameSite=Lax"), "the session cookie is not SameSite=Lax: " + cookie);
    }

    // ---------------------------------------------------------- criterion 24

    /**
     * Criterion 24: a signed-in reader's session cookie authenticates nothing under
     * {@code /api/}, {@code /api/mcp} included. Asked from the reader's own page, so the
     * browser sends the cookie exactly as it would to any same-origin request.
     */
    @Test
    void aReaderSessionCookieAuthenticatesNothingUnderApi() throws Exception {
        signInWithKeycloak(READER, contextPath() + "/");
        assertEquals(403, menuStatus(sessionCookie()).statusCode(), "the reader is not signed in");

        BrowserHealth.current().expectRefusal("/api/v1/me");
        BrowserHealth.current().expectRefusal("/api/mcp");
        Object me = Selenide.executeJavaScript(
                "return fetch(arguments[0], {credentials: 'same-origin'}).then(r => r.status);",
                contextPath() + "/api/v1/me");
        assertEquals(401L, ((Number) me).longValue(), "a reader's session cookie authenticated /api/v1/me");

        Object mcp = Selenide.executeJavaScript(
                "return fetch(arguments[0], {method: 'POST', credentials: 'same-origin',"
                        + " headers: {'Content-Type': 'application/json',"
                        + " 'Accept': 'application/json, text/event-stream'},"
                        + " body: arguments[1]}).then(r => r.status);",
                contextPath() + "/api/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertEquals(401L, ((Number) mcp).longValue(), "a reader's session cookie authenticated /api/mcp");
    }

    // ------------------------------------------------------------- sign-in

    /** Starts a sign-in that returns to {@code returnTo} and completes it on Keycloak's form. */
    private void signInWithKeycloak(String user, String returnTo) {
        openPath(START + "?return=" + encode(returnTo));
        new KeycloakLoginPage().signIn(user, passwordOf(user));
    }

    /**
     * Starts a sign-in and stops on the provider's form, returning the {@code state} it
     * was sent: the one value a callback must echo back.
     */
    private String startSignIn() {
        openPath(START + "?return=" + encode(contextPath() + "/"));
        String url = WebDriverRunner.url();
        String state = queryParameter(url, "state");
        assertNotNull(state, "the provider was sent no state: " + url);
        return state;
    }

    /** The realm's test-only password convention; see roller-it-realm.json. */
    private static String passwordOf(String user) {
        return user + "-password";
    }

    private static String emailOf(String user) {
        return user + "@example.com";
    }

    private void assertLoginPageSays(String code, String message) {
        String url = WebDriverRunner.url();
        assertEquals(contextPath() + LOGIN, currentPath(), "not on the login page: " + url);
        assertEquals("sso=" + code, URI.create(url).getQuery(), "wrong outcome: " + url);
        $("#errors").shouldHave(text(message));
    }

    // ------------------------------------------------------------ sessions

    /** The browser's Roller session id; the browser must be on a Roller page. */
    private static String sessionCookie() {
        Cookie cookie = WebDriverRunner.getWebDriver().manage().getCookieNamed("JSESSIONID");
        assertNotNull(cookie, "the browser holds no Roller session on " + WebDriverRunner.url());
        return cookie.getValue();
    }

    /** {@code menu.rol} asked with {@code sessionId}: 403 for a reader, 302 to log in for nobody. */
    private HttpResponse<String> menuStatus(String sessionId) throws Exception {
        return get(MENU, sessionId);
    }

    private HttpResponse<String> get(String path, String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20));
        if (sessionId != null) {
            request.header("Cookie", "JSESSIONID=" + sessionId);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------ accounts

    private JsonNode theOnlyAccountWith(String email) throws Exception {
        List<JsonNode> accounts = accountsWithEmail(email);
        assertEquals(1, accounts.size(), "expected exactly one account with " + email + ": " + accounts);
        return accounts.get(0);
    }

    /**
     * Every account with this email, from {@code GET /api/v1/admin/users} under an admin
     * token minted for the call and revoked after it. Keyed by username, because other
     * classes create accounts while this pages through them, and the newest-first order
     * then repeats a row across a page boundary.
     */
    private List<JsonNode> accountsWithEmail(String email) throws Exception {
        HttpResponse<String> minted = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", adminBasicAuth())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"label\":\"SsoSignInIT\",\"role\":\"ADMIN\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, minted.statusCode(), minted.body());
        JsonNode token = JSON.readTree(minted.body());
        try {
            Map<String, JsonNode> byName = new LinkedHashMap<>();
            for (int offset = 0; ; offset += PAGE_SIZE) {
                HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(baseUrl()
                                + "/api/v1/admin/users?limit=" + PAGE_SIZE + "&offset=" + offset))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token.get("token").asString())
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode(), page.body());
                JsonNode users = JSON.readTree(page.body());
                for (JsonNode user : users) {
                    if (email.equals(user.get("emailAddress").asString())) {
                        byName.put(user.get("userName").asString(), user);
                    }
                }
                if (users.size() < PAGE_SIZE) {
                    return new ArrayList<>(byName.values());
                }
            }
        } finally {
            http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens/"
                            + token.get("info").get("id").asString()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", adminBasicAuth())
                    .DELETE().build(), HttpResponse.BodyHandlers.discarding());
        }
    }

    private static List<String> roles(JsonNode account) {
        List<String> roles = new ArrayList<>();
        account.get("globalRoles").forEach(role -> roles.add(role.asString()));
        return roles;
    }

    private static String adminBasicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes(UTF_8));
    }

    // ---------------------------------------------------------- user admin

    /** Finds an account through User Admin's search and opens its edit page, as an admin gets there. */
    private void openUserEdit(String userName) {
        openPath(USER_ADMIN);
        $("#userName").should(visible).setValue(userName);
        // The search box is itself bean.userName and holds the name just typed, so the
        // wait below is only meaningful on the page that answers.
        clickAndAwaitNewPage($("#user-submit"));
        $("input[name='bean.userName']").should(visible).shouldHave(value(userName));
    }

    private void setEnabled(String userName, boolean enabled) {
        setCheckbox(userName, "#bean_enabled", enabled);
    }

    /** "Can write": the editor role, the only one User Admin can grant a bound account. */
    private void setWriter(String userName, boolean writer) {
        setCheckbox(userName, "#bean_writer", writer);
    }

    /** Sets one box on the account's edit page, saves, and reads it back. */
    private void setCheckbox(String userName, String checkbox, boolean on) {
        openUserEdit(userName);
        if ($(checkbox).should(exist).isSelected() != on) {
            $(checkbox).click();
        }
        clickAndAwaitNewPage($("#save_button"));
        $("#messages").should(exist);

        openUserEdit(userName);
        if (on) {
            $(checkbox).shouldBe(checked);
        } else {
            $(checkbox).shouldNotBe(checked);
        }
    }

    // ---------------------------------------------------------------- urls

    /** The context path, empty at the root: what every path a test builds starts with. */
    private static String contextPath() {
        String path = URI.create(baseUrl()).getPath();
        return path == null ? "" : path;
    }

    private static String currentPath() {
        return URI.create(WebDriverRunner.url()).getPath();
    }

    private static String queryParameter(String url, String name) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), UTF_8);
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }
}
