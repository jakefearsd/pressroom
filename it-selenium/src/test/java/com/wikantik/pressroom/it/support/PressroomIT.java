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

package com.wikantik.pressroom.it.support;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.webdriver;
import static com.codeborne.selenide.WebDriverConditions.urlContaining;

/**
 * Base class for Roller integration tests: drives a headless Chrome against a running
 * Roller instance and, by way of {@link BrowserHealthExtension}, fails any test whose
 * pages loaded broken resources or produced JavaScript errors.
 *
 * <p>The instance under test is named by the {@value #BASE_URL_PROPERTY} system property.
 * Tests assume the seed fixture named by the constants below already exists; they never
 * create it.
 */
@ExtendWith(BrowserHealthExtension.class)
public abstract class PressroomIT {

    /**
     * Resource-lock key for the site-wide runtime properties.
     *
     * <p>{@link #setGlobalFlag} writes Admin Settings, which is ONE set of
     * properties behind ONE app instance shared by the whole suite. Two classes
     * doing that concurrently would each observe the other's flag, and the
     * failure would look like a flaky assertion rather than a race. Every class
     * that calls {@code setGlobalFlag} must therefore carry
     * {@code @ResourceLock(PressroomIT.GLOBAL_CONFIG)}, which serialises them
     * against each other while still allowing the classes that touch no global
     * state to run alongside them.
     *
     * <p>This is not {@code @Isolated} on purpose: isolation would stall all 34
     * classes for the duration of each of these five, which is most of the
     * benefit of running in parallel at all.
     */
    public static final String GLOBAL_CONFIG = "roller.global-runtime-config";

    /**
     * Resource-lock key for the shared weblog's media directory.
     *
     * <p>The media ITs all drive {@code mediaFileView.rol} against the same
     * {@code WEBLOG_HANDLE}, so they share one upload directory. Run
     * concurrently they clear each other's fixtures, and the symptom is not an
     * obvious race -- it is an assertion that the file list is empty, or that a
     * button which only renders when files exist is missing. Classes that
     * upload, crop or delete media must carry
     * {@code @ResourceLock(PressroomIT.SHARED_MEDIA)}.
     *
     * <p>The alternative -- giving each media IT its own weblog -- is the better
     * long-term fix and would let them run concurrently, but it is a larger
     * change to fixtures than the parallelism work warranted; these three
     * classes total under a minute serialised.
     */
    public static final String SHARED_MEDIA = "roller.shared-media-directory";

    /** Root URL of the Roller under test, context path included, e.g. {@code http://localhost:8080/roller}. */
    public static final String BASE_URL_PROPERTY = "it.base.url";

    /** Seed administrator, created by the integration-test fixture. */
    protected static final String ADMIN_USERNAME = "it_admin";
    protected static final String ADMIN_PASSWORD = "it-admin-password";

    /** Seed weblog owned by {@link #ADMIN_USERNAME}. */
    protected static final String WEBLOG_HANDLE = "it_weblog";

    private static final String LOGIN_PATH = "/pressroom-ui/login.rol";
    private static final String MENU_PATH = "/pressroom-ui/menu.rol";

    /** Present on every page of an authenticated session and nowhere else - see bannerStatus.jsp. */
    private static final String LOGOUT_LINK = "a[href$='/pressroom-ui/logout.rol']";

    private static boolean selenideConfigured;

    /**
     * Root URL of the Roller under test, without a trailing slash.
     *
     * @throws IllegalStateException if the property is unset, rather than letting the suite
     *                               run on and fail later with a confusing connection error
     */
    protected static String baseUrl() {
        String configured = System.getProperty(BASE_URL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("System property '" + BASE_URL_PROPERTY + "' is not set. "
                    + "Point it at the Roller instance under test, "
                    + "e.g. -D" + BASE_URL_PROPERTY + "=http://localhost:8080/roller");
        }
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    /**
     * Roller's admin UI is Bootstrap: below the responsive breakpoint the navigation bar
     * collapses behind a toggle and tests would be clicking links that are not on screen.
     */
    private static final int BROWSER_WIDTH = 1366;

    private static final int BROWSER_HEIGHT = 768;

    /** The viewport every browser in this suite starts at, in Selenide's spelling. */
    protected static final String BROWSER_SIZE = BROWSER_WIDTH + "x" + BROWSER_HEIGHT;

    /**
     * The Chrome arguments every browser in this suite starts with, plus whatever the
     * caller needs on top.
     *
     * <p>Single-sourced deliberately. {@code VirtualHostIT} has to build its own
     * {@link ChromeOptions} so it can add {@code --host-resolver-rules}, and used to
     * repeat this list by hand -- which meant a flag added here silently did not reach
     * that class. Anything needing extra arguments passes them in rather than starting
     * a second list.
     *
     * <p>The first three are what make Chrome usable inside the containers this suite
     * runs in on CI. The rest are resource hygiene: a browser that exists for one test
     * class has no business updating components, syncing a profile, fetching
     * safe-browsing lists or reporting metrics, and none of that traffic is under test.
     *
     * @param extraArguments additional Chrome switches, e.g. {@code --host-resolver-rules=...}
     */
    protected static ChromeOptions chromeOptions(String... extraArguments) {
        ChromeOptions options = new ChromeOptions().addArguments(
                "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-client-side-phishing-detection",
                "--disable-domain-reliability",
                "--disable-sync",
                "--metrics-recording-only",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-default-apps",
                "--disable-extensions",
                "--mute-audio",
                // Site isolation buys a renderer PROCESS per origin. This suite talks to
                // one origin -- VirtualHostIT's second hostname is --host-resolver-rules'd
                // onto the same 127.0.0.1 -- so it is pure per-process overhead here.
                "--disable-features=Translate,OptimizationHints,MediaRouter,"
                        + "IsolateOrigins,site-per-process",
                // Matches Configuration.browserSize, so the window opens at the right size
                // instead of being resized over the wire after launch.
                "--window-size=" + BROWSER_WIDTH + "," + BROWSER_HEIGHT);
        return extraArguments.length == 0 ? options : options.addArguments(extraArguments);
    }

    /**
     * Scheme, host and port of the Roller under test, without the context path -- the
     * form CDP's storage commands want, and not something {@link #baseUrl()} can give
     * them because a prefixed deployment puts a path on the end of it.
     */
    static String origin() {
        URI uri = URI.create(baseUrl());
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /**
     * Applies the browser settings shared by the whole suite. Called by
     * {@link BrowserHealthExtension} before it starts a browser; idempotent, because
     * Selenide's configuration is global and only read at browser startup.
     */
    static synchronized void configureSelenide() {
        if (selenideConfigured) {
            return;
        }
        Configuration.baseUrl = baseUrl();
        Configuration.browser = "chrome";
        Configuration.headless = true;
        Configuration.browserSize = BROWSER_SIZE;
        Configuration.browserCapabilities = chromeOptions();
        selenideConfigured = true;
    }

    /**
     * Lets a subclass's own {@code @BeforeAll} win a race with this method.
     *
     * <p>{@code configureSelenide()} above is guarded to run its body exactly once per
     * suite -- necessary, since {@code Configuration} is Selenide's process-wide static
     * state and every class shares it. But {@code @BeforeAll} in a subclass runs before
     * {@code BrowserHealthExtension.beforeEach()} ever fires for that class's first test,
     * so a subclass that sets {@code Configuration.*} fields directly from its own
     * {@code @BeforeAll} (rather than through this class, which it cannot reach --
     * {@code configureSelenide()} is package-private to {@code support} and such a
     * subclass typically lives outside it, see {@code VirtualHostIT}) is racing the very
     * next {@code configureSelenide()} call: if THIS is the first one the suite has ever
     * made, {@code selenideConfigured} is still false, so it runs its full body and
     * clobbers whatever the subclass just set. That only surfaces when the subclass's
     * class happens to run first in the suite -- including every single-class run via
     * {@code -Dit.test=<ThatClass>}, which is exactly the run shape most likely to catch
     * it late. Calling this at the end of such a {@code @BeforeAll} marks configuration
     * as already done, so the next {@code configureSelenide()} call is a no-op and the
     * subclass's own fields survive regardless of run order.
     */
    protected static synchronized void markSelenideConfigured() {
        selenideConfigured = true;
    }

    /**
     * Opens a path on the Roller under test and waits for the browser to finish, so the
     * health recorder has seen every request the page made before the test asserts anything.
     *
     * @param path server-relative path below the Roller context, e.g. {@code /pressroom-ui/menu.rol}
     */
    protected void openPath(String path) {
        Selenide.open(baseUrl() + (path.startsWith("/") ? path : "/" + path));
        BrowserHealth.current().settle();
    }

    /** How long a navigation a test started may take to replace the page; matches settle()'s bound. */
    private static final Duration NEW_PAGE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Clicks something that submits a form or follows a link, and returns only once the
     * server's answer has replaced the page.
     *
     * <p>Use it for every click that navigates unless the very next statement waits on
     * something only the NEW page can satisfy. Neither the click nor {@link BrowserHealth#settle()}
     * is a barrier: Chrome starts a form submission in a task of its own, so on a loaded
     * machine the click returns, settle() finds the network quiet (the request does not
     * exist yet), and the test reads the OLD page -- passing vacuously on it, or signing out
     * underneath the request. The late POST then reaches a dead session, is refused for its
     * CSRF token (the "403 on createUser!save.rol" of build-and-ci.md), and its answer lands
     * wherever the browser happens to be next: in the same test's health check, over the
     * next user's freshly installed session cookie, or committed during the next test,
     * whose recorder never saw it start. docs/dev/browser-its.md has the reproduction.
     *
     * @param target the button or link to click
     */
    protected static void clickAndAwaitNewPage(SelenideElement target) {
        awaitNewPageAfter(target::click);
    }

    /**
     * Runs {@code action} -- a click followed by {@code Selenide.confirm()}, a script that
     * submits a form -- and returns once the page it started from has been replaced.
     *
     * <p>The wait is on the old document going stale, which happens only when the new
     * one commits: it cannot be satisfied by the page the action started from, however
     * late the browser gets round to sending the request. See {@link #clickAndAwaitNewPage}.
     * It accepts any newly committed document, not only the one this action caused, so it
     * is sound only when every earlier navigating action was itself awaited.
     */
    protected static void awaitNewPageAfter(Runnable action) {
        WebElement page = $("html").toWebElement();
        action.run();
        new WebDriverWait(WebDriverRunner.getWebDriver(), NEW_PAGE_TIMEOUT)
                .withMessage("the page was never replaced: the click or submission started no navigation")
                .until(driver -> isReplaced(page));
        BrowserHealth.current().settle();
    }

    /**
     * Whether {@code element}'s document is no longer the page's.
     *
     * <p>Not {@code ExpectedConditions.stalenessOf}, which recognises only a
     * {@link StaleElementReferenceException}. When the check lands while the new document
     * is committing, ChromeDriver can answer it instead with "unknown error: unhandled
     * inspector error: Node with given id does not belong to the document" -- its element
     * ids carry the document they were found in, and that document is no longer the
     * frame's. That is the same fact reported under another code, and stalenessOf threw
     * it out of the wait (UserAdminIT, 2 runs in 6). Anything else still propagates.
     */
    private static boolean isReplaced(WebElement element) {
        try {
            element.isEnabled();
            return false;
        } catch (StaleElementReferenceException e) {
            return true;
        } catch (WebDriverException e) {
            if (String.valueOf(e.getMessage()).contains("does not belong to the document")) {
                return true;
            }
            throw e;
        }
    }

    /**
     * Signs in as the seed administrator and leaves the browser on the main menu.
     *
     * <p>Goes to the login form directly and navigates to the menu afterwards, rather
     * than requesting the menu first and letting Spring Security replay the saved
     * request. That replay appends its {@code continue} marker
     * ({@code menu.rol?continue}, the default since Spring Security 6.1) and Roller
     * answers that URL with a 404 -- a real defect that affects anyone deep-linking
     * while logged out, tracked separately. Depending on it here would make every
     * test in the suite fail for a reason that has nothing to do with the route
     * under test.
     */
    protected void loginAsAdmin() {
        signInAs(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    /** Spring Security's login-processing endpoint; see {@code SecurityConfig.formLogin}. */
    private static final String LOGIN_PROCESSING_PATH = "/pressroom_j_security_check";

    /** Matches the token {@code <sec:csrfInput/>} renders into the login form. */
    private static final Pattern CSRF_INPUT = Pattern.compile(
            "name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    /**
     * Signs in the cheap way: authenticate over HTTP and hand the browser the resulting
     * session cookie. Navigates nowhere.
     *
     * <p>This exists because signing in was the single most expensive thing the suite did.
     * {@link #loginAs} drives the real form, which costs THREE full page loads -- the login
     * page, whatever {@code login-redirect.rol} forwards to, and the menu -- and every one
     * of them re-fetches and re-parses jQuery UI and Bootstrap, because the health recorder
     * runs with the HTTP cache disabled. (The editor bundle is not among them: it loads only
     * on the two screens that mount one, and none of these three is.) 21 of the 36 IT
     * classes pay that from {@code @BeforeEach}. Measured: a test that logs in and opens
     * one page costs 2.86s ({@code RouteSweepIT}) against 0.88s for one that opens a page
     * without signing in ({@code PublicSurfaceIT}) -- so about 2s per test, roughly 30%
     * of the suite.
     *
     * <p>An {@code HttpClient} fetches the login page as ONE document and no sub-resources,
     * so the two requests here cost a small fraction of the two page loads they replace.
     *
     * <p><b>This is not a weaker check than the form login, and in one way it is stronger.</b>
     * Success is read from the redirect Spring Security itself issues -- anything pointing at
     * {@code login.rol?error} is a refusal -- rather than inferred from a page having
     * rendered.
     *
     * <p><b>It deliberately does not navigate afterwards, and that is the second saving.</b>
     * It used to finish by opening the menu and asserting a logout link, which cost a further
     * full page load on every call for a check the two steps above already make between them:
     * the 302 proves the credentials and the session, and {@code installCookie} now checks
     * CDP's own success flag, which is the only place a refused cookie is ever reported. What
     * the menu load added beyond that was a better error message, not a broader check. Every
     * caller was audited before it went: all 21 sites either navigate as their next statement
     * or call a helper whose first statement is an {@code openPath}, so nothing depended on
     * landing anywhere in particular. <b>Do not write one that does</b> -- after this call the
     * browser is wherever it already was, usually {@code about:blank} at the start of a test.
     *
     * <p><b>Isolation is unchanged.</b> Each call authenticates on its own, producing its own
     * server-side session; nothing is cached or shared between tests or classes.
     *
     * <p>{@link #loginAs} is deliberately kept for anything that is TESTING the login screen
     * rather than merely needing to be signed in -- it is the only path that exercises the
     * form, the redirect chain and the failure page.
     *
     * @throws IllegalStateException if the credentials are refused
     */
    protected void signInAs(String username, String password) {
        for (HttpCookie cookie : authenticateOverHttp(username, password)) {
            // The server's own path, not one derived from the url -- see
            // BrowserHealth.installCookie for what a derived path costs under a
            // servlet context prefix. A cookie with no path defaults to "/",
            // which is what the root context sets anyway.
            String path = cookie.getPath() == null || cookie.getPath().isBlank()
                    ? "/" : cookie.getPath();
            BrowserHealth.installCookie(cookie.getName(), cookie.getValue(),
                    baseUrl() + "/", path);
        }
    }

    /**
     * Performs the form login over HTTP and returns the cookies it produced.
     *
     * <p>Redirects are NEVER followed: the 302's {@code Location} is the evidence of success
     * or failure, and following it would both hide that and pull down a page we do not want.
     * Every cookie the exchange produced is returned rather than {@code JSESSIONID} alone,
     * so a deployment that renames the session cookie, or adds one, still works.
     */
    private static List<HttpCookie> authenticateOverHttp(String username, String password) {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        try {
            HttpResponse<String> form = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + LOGIN_PATH))
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Matcher token = CSRF_INPUT.matcher(form.body());
            if (!token.find()) {
                throw new IllegalStateException("No _csrf token on " + LOGIN_PATH
                        + ". The login form renders one through <sec:csrfInput/>; if that has "
                        + "changed, this fast sign-in has to change with it.");
            }
            String body = "j_username=" + encode(username)
                    + "&j_password=" + encode(password)
                    + "&_csrf=" + encode(token.group(1));
            HttpResponse<String> post = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + LOGIN_PROCESSING_PATH))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String location = post.headers().firstValue("Location").orElse("");
            if (location.contains("login.rol")) {
                throw new IllegalStateException("Sign-in refused for '" + username
                        + "': Spring Security redirected to " + location);
            }
            return cookies.getCookieStore().getCookies();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not sign in as '" + username + "'", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Where a reader's sign-in with the provider starts (SsoPaths.START in app). */
    private static final String SSO_START_PATH = "/pressroom-ui/sso/start";

    /**
     * Signs a reader in through the run's Keycloak (realm {@code roller-it}): starts a
     * sign-in that returns to {@code returnTo} and completes it on the provider's form,
     * returning once the provider's answer has replaced the page.
     *
     * <p>Needs {@code site.absoluteurl} pointed at {@link #baseUrl()}, since the callback
     * is built from it; the caller holds {@link #GLOBAL_CONFIG} exclusively and sets it.
     * Shared by {@code SsoSignInIT} and {@code CommentsIT}.
     *
     * @param user     a username from roller-it-realm.json
     * @param returnTo the path, context path included, the sign-in should land on
     */
    protected void signInWithKeycloak(String user, String returnTo) {
        openPath(SSO_START_PATH + "?return=" + encode(returnTo));
        new KeycloakLoginPage().signIn(user, KeycloakLoginPage.passwordOf(user));
    }

    /**
     * Signs in as any user. Journeys that create users need this; everything
     * else wants {@link #loginAsAdmin()}.
     */
    protected void loginAs(String username, String password) {
        openPath(LOGIN_PATH);

        $("#j_username").setValue(username);
        $("#j_password").setValue(password);
        $("#login").click();

        // Wait for the login form to go away before navigating again.
        //
        // Settling on network silence is not enough on its own: the POST and its
        // redirect can still be in flight when the quiet period expires, and the
        // next open() then arrives unauthenticated and gets bounced to the front
        // page. That showed up as one route in twenty-odd failing for a reason
        // that had nothing to do with the route under test. Waiting on a DOM
        // condition ties us to the thing we actually care about - that the
        // browser has left the login page.
        $("#j_username").should(disappear);
        BrowserHealth.current().settle();

        openPath(MENU_PATH);

        // Bad credentials bounce back to login.rol?error=true, so reaching the menu
        // with a logout link is what distinguishes success from failure.
        webdriver().shouldHave(urlContaining(MENU_PATH));
        $(LOGOUT_LINK).should(exist);

        BrowserHealth.current().settle();
    }

    /**
     * Signs out: empties the browser's cookie jar and invalidates the server-side
     * session. Navigates nowhere.
     *
     * <p>The mirror image of {@link #signInAs}, and cheap for the same reason. Signing
     * out used to be two full page loads -- {@code logout.rol}, then the login page, to
     * settle the redirect chain the first one kicks off -- and the suite does it 52
     * times. Neither load was doing work a test depended on: an audit of all 52 sites
     * found that every one either navigates as its next statement, calls a helper whose
     * first statement is an {@code openPath}, makes a raw HTTP request that never had
     * the browser's cookies anyway, or is the last statement of its test (where
     * {@code BrowserHealth.attach()} clears the cookie jar before the next test
     * regardless). <b>Nothing may be written that depends on landing on the login
     * page</b> -- after this call the browser is still showing whatever it was.
     *
     * <p><b>Both halves are here on purpose.</b> Clearing Chrome's cookies is what makes
     * the BROWSER anonymous, which is what the tests that log out mid-test actually
     * want -- they go on to read a page as a stranger would. Hitting {@code logout.rol}
     * is what makes the SERVER forget, so this method does not quietly become a lie
     * about a session that is still perfectly usable. The order matters only in that
     * the cookies must be read before they are cleared.
     *
     * <p>The HTTP call is best-effort: a session that has already expired, or a
     * transport that fails, still leaves the browser signed out, which is the part a
     * caller can observe. Failing here would turn a tidy-up into a test failure.
     */
    protected void logout() {
        // Settle first, so whatever the page is still loading is recorded against
        // this session. This is NOT a barrier against a test's own POST: a form
        // submission the browser has not yet sent produces no event to wait for,
        // so settle() returns at once and the POST then reaches the session this
        // method is about to destroy. A caller that clicked something that
        // navigates must have waited for the answer -- clickAndAwaitNewPage --
        // before signing out.
        BrowserHealth.current().settle();

        Map<String, String> cookies = BrowserHealth.readCookies(baseUrl() + "/");
        BrowserHealth.clearCookies();
        invalidateServerSession(cookies);
    }

    /** GETs {@code logout.rol} carrying {@code cookies}, so the server drops the session. */
    private static void invalidateServerSession(Map<String, String> cookies) {
        if (cookies.isEmpty()) {
            return;
        }
        String header = cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        try {
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(20)).build()
                    .send(HttpRequest.newBuilder(URI.create(baseUrl() + "/pressroom-ui/logout.rol"))
                            .header("Cookie", header).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            // Best-effort by design -- see the javadoc above.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs browser work from a class's {@code @AfterAll}, where there is a live
     * browser but no attached health recorder.
     *
     * <p>Needed because the two halves of the browser's lifecycle end at
     * different moments. {@code BrowserHealthExtension.afterEach} calls
     * {@code BrowserHealth.detach()} after every test, which clears the
     * thread-local recorder; the WebDriver itself stays up until that
     * extension's own {@code afterAll}. JUnit runs a class's {@code @AfterAll}
     * methods BEFORE any {@code AfterAllCallback} extension, so in between
     * those two points {@link #openPath} has a browser to drive but
     * {@code BrowserHealth.current()} throws. This re-attaches a recorder for
     * the duration of {@code work} and detaches it again.
     *
     * <p>Use it for class-level fixture teardown that has no HTTP equivalent
     * -- deleting a weblog, for instance, which the automation API cannot do.
     * Assertions made inside {@code work} are ordinary assertions; the
     * recorder is here so the navigation helpers function, not to add a
     * health verdict to a class that has already finished reporting.
     *
     * <p>A no-op when no browser was ever started (every test in the class was
     * skipped, say), rather than starting one just to close it.
     */
    protected static void inBrowserAfterAll(Runnable work) {
        if (!WebDriverRunner.hasWebDriverStarted()) {
            return;
        }
        BrowserHealth.attach();
        try {
            work.run();
        } finally {
            BrowserHealth.detach();
        }
    }

    /** The site-wide settings page, where global runtime properties are edited. */
    private static final String GLOBAL_CONFIG_PATH = "/pressroom-ui/admin/globalConfig.rol";

    /**
     * Sets a global runtime property from the admin settings page and returns
     * what it was before, so the caller can put it back.
     *
     * <p>Driven through the real page rather than the database because that is
     * the only path an administrator has, and because the page writes through
     * {@code PropertiesManager} — a direct UPDATE would leave the running
     * instance holding the old value.
     *
     * <p><b>These properties are global and this suite shares one running
     * instance.</b> Anything switched here stays switched for every test that
     * runs afterwards, in whatever order JUnit picks, so a test that changes
     * one must restore it in a {@code finally}:
     *
     * <pre>{@code
     * boolean was = setGlobalFlag("themes.customtheme.allowed", true);
     * try {
     *     ...
     * } finally {
     *     setGlobalFlag("themes.customtheme.allowed", was);
     * }
     * }</pre>
     *
     * <p>Requires an administrator session; sign in before calling.
     *
     * @param name  the property name, which is also the checkbox's form name
     * @param value what to set it to
     * @return the value it had on arrival
     */
    protected boolean setGlobalFlag(String name, boolean value) {
        return setGlobalFlags(Map.of(name, value)).get(name);
    }

    /**
     * Sets several global runtime flags in one save, returning what they were.
     *
     * <p>One page load and one save however many flags are involved. Setting
     * them one at a time costs a full settings round trip each, which for a
     * test that switches three features off and back on again is more time in
     * the settings page than in the behaviour under test.
     *
     * <p>The same restore discipline applies as for {@link #setGlobalFlag}:
     * these are global, the suite shares one instance, so put them back in a
     * {@code finally}.
     *
     * @param flags property name to desired value
     * @return each property's value on arrival
     */
    protected Map<String, Boolean> setGlobalFlags(Map<String, Boolean> flags) {
        openPath(GLOBAL_CONFIG_PATH);

        Map<String, Boolean> previous = new LinkedHashMap<>();
        flags.forEach((name, value) -> {
            SelenideElement checkbox = $("input[name='" + name + "']").should(exist);
            previous.put(name, checkbox.isSelected());
            if (checkbox.isSelected() != value) {
                checkbox.click();
            }
        });

        $("#saveButton").click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();

        // Read them back. An unticked checkbox posts nothing at all, so a form
        // that binds a value wrongly still redirects and still reports success
        // -- only re-reading the page proves the settings took.
        openPath(GLOBAL_CONFIG_PATH);
        flags.forEach((name, value) -> {
            if (value) {
                $("input[name='" + name + "']").shouldBe(checked);
            } else {
                $("input[name='" + name + "']").shouldNotBe(checked);
            }
        });
        return previous;
    }

    /**
     * Sets {@code site.absoluteurl} from the real Admin Settings page and returns what it
     * was before, mirroring {@link #setGlobalFlag}'s contract for a string-typed runtime
     * property (that helper only covers checkboxes).
     *
     * <p>Global like every setting on that page, so the caller holds {@link #GLOBAL_CONFIG}
     * exclusively and puts the old value back. Two classes need it: {@code VirtualHostIT}
     * (the control-plane redirect has to name a site host) and {@code SsoSignInIT} (the
     * sign-in callback is built from this URL; with it blank, sign-in answers
     * {@code sso=unavailable}). Requires an administrator session.
     *
     * @param value the new Absolute URL; {@code null} or empty clears it
     * @return the value it had on arrival
     */
    protected String setSiteAbsoluteUrl(String value) {
        openPath(GLOBAL_CONFIG_PATH);
        String previous = $("input[name='site.absoluteurl']").should(exist).getValue();
        $("input[name='site.absoluteurl']").setValue(value == null ? "" : value);
        clickAndAwaitNewPage($("#saveButton"));
        $("#messages").should(exist);
        return previous;
    }

    /** GET with no cookies, i.e. exactly what an anonymous reader sends. */
    protected static String getAnonymously(String url) {
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url + " anonymously", e);
        }
    }
}
