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

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WrapsDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v155.network.Network;
import org.openqa.selenium.devtools.v155.network.model.Cookie;
import org.openqa.selenium.devtools.v155.page.Page;
import org.openqa.selenium.devtools.v155.page.model.Frame;
import org.openqa.selenium.devtools.v155.storage.Storage;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Watches the browser over the Chrome DevTools Protocol and turns what it saw into a
 * pass/fail verdict: every sub-resource's HTTP status, every {@code console.error}, every
 * uncaught JavaScript exception.
 *
 * <p>This catches the class of breakage that ordinary Selenium assertions sail straight
 * past. A page whose stylesheet or webjar 404s still renders, still has the elements the
 * test clicks, and still passes - it just looks and behaves wrong for real users.
 *
 * <p>Instances are created and asserted on by {@link BrowserHealthExtension}; tests reach
 * the recorder for the browser on their own thread through {@link #current()}.
 */
public final class BrowserHealth {

    /**
     * The complete set of things a failing sub-resource may be excused for - deliberately
     * one constant, so a reviewer can see the entire blind spot at a glance. Every entry
     * costs us coverage, so add one only with evidence that the request is unavoidable
     * <em>and</em> harmless.
     *
     * <p>EMPTY, and worth keeping empty. It used to hold {@code /favicon.ico}: Chrome
     * requests a favicon on every navigation whether or not the page asks for one, and
     * Roller shipped none, so without the entry every single test in the suite failed.
     * Roller now ships a real {@code /favicon.ico} (rasterized from favicon.svg), so the
     * request 200s and the exemption - which excused that 404 on every page in the
     * suite - is gone. Do not reintroduce it to silence a 404; ship the file instead.
     * (The one case where no file <em>can</em> be shipped -- Chrome's origin-root probe
     * under a servlet context prefix -- is handled by
     * {@link #isOriginRootFaviconProbeOutsideTheContext(String)}, not by an entry here.)
     */
    private static final Set<String> IGNORED_PATH_SUFFIXES = Set.of();

    /**
     * Resource types a page may legitimately abandon mid-flight.
     *
     * <p>These are requests <em>page script starts</em>, and script that starts
     * a request may reasonably decide it no longer wants the answer. Two
     * examples in this suite, both correct behaviour: Leaflet cancels tile
     * requests as the viewport settles -- 48 in a single map render -- and
     * paints the aborted ones with a {@code data:} GIF; jQuery UI's
     * autocomplete cancels the in-flight lookup on every further keystroke, so
     * typing a username aborts one XHR per character.
     *
     * <p>{@code Document} is here for the same reason one level up: navigating
     * again before the previous navigation finished cancels it, and this suite
     * does exactly that (signing out then going straight to the login page).
     *
     * <p>Everything NOT listed here is a sub-resource the <em>document</em>
     * declared -- a stylesheet, a script, a font. Nothing cancels those, so an
     * abort means the browser refused the resource, and that is precisely the
     * case worth failing on.
     *
     * <p>Excusing these costs little. A request that genuinely fails with a
     * status still produces a response and is caught by
     * {@link #assertNoBrokenResources()}; one that fails at the network level
     * without being cancelled is still reported here. Only deliberate
     * cancellation is excused, and only for types that are cancelled on
     * purpose.
     */
    private static final Set<String> ABORT_IS_EXPECTED_FOR =
            Set.of("Image", "XHR", "Fetch", "Document");

    /**
     * Network errors that describe the machine running the test rather than
     * anything the page did.
     *
     * <p>{@code ERR_NETWORK_CHANGED} is Chrome noticing the host's network
     * interface changed underneath it -- a laptop switching wifi, a VPN coming
     * up. No page can cause it and no page can prevent it, so failing a test
     * for it reports a defect that does not exist.
     *
     * <p>Observed once, in a full run, on a script that loads fine every other
     * time. Kept to what has actually been seen: adding the neighbouring
     * {@code ERR_*} constants speculatively would widen the blind spot for
     * failures nobody has met.
     */
    private static final Set<String> ENVIRONMENTAL_ERRORS = Set.of("net::ERR_NETWORK_CHANGED");

    /**
     * Whether a request that produced no response can be excused.
     *
     * <p>A blocked request is never excusable, however it ended and whatever
     * its type: a CSP refusal is a real defect in the page that asked for the
     * resource.
     */
    private static boolean isExcusableFailure(String type, String blockedReason,
            boolean canceled, String errorText) {
        if (blockedReason != null) {
            return false;
        }
        return (canceled && ABORT_IS_EXPECTED_FOR.contains(type))
                || ENVIRONMENTAL_ERRORS.contains(errorText);
    }

    /**
     * Only 4xx and 5xx count as broken. Roller redirects constantly - Spring Security
     * bounces unauthenticated requests to the login form, controllers answer POSTs with
     * {@code redirect:} views - so treating "not 2xx" as failure would fail everything.
     */
    private static final int FIRST_FAILING_STATUS = 400;

    /** {@code ConsoleEvent#getType()} value for {@code console.error}; warnings are not failures. */
    private static final String CONSOLE_ERROR_TYPE = "error";

    /** How long the event stream must stay silent before {@link #settle()} calls the page done. */
    private static final Duration QUIET_PERIOD = Duration.ofMillis(500);

    /** Upper bound on {@link #settle()}, so a page that polls forever cannot hang the suite. */
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration SETTLE_POLL_INTERVAL = Duration.ofMillis(50);

    /** Selenide's WebDriver is per-thread, so the recorder watching it must be too. */
    private static final ThreadLocal<BrowserHealth> CURRENT = new ThreadLocal<>();

    /**
     * One response the browser received.
     *
     * @param page the main-frame URL that was open when the response arrived, so a failure
     *             report says which page pulled the resource in
     */
    public record Resource(String url, int status, String type, String page) {
        @Override
        public String toString() {
            return status + " " + type + " " + url + "   (while on " + page + ")";
        }
    }

    /**
     * A request the browser gave up on without ever receiving a response.
     *
     * @param type    the CDP resource type ({@code Stylesheet}, {@code Image}, ...)
     * @param reason  a readable account of why it failed
     */
    public record FailedRequest(String url, String type, String reason, String page) {
        @Override
        public String toString() {
            return type + " " + url + "   (" + reason + ", while on " + page + ")";
        }
    }

    // Written from the CDP websocket thread, read from the test thread.
    private final List<Resource> responses = new CopyOnWriteArrayList<>();
    private final List<FailedRequest> failedRequests = new CopyOnWriteArrayList<>();

    /**
     * Request id to URL, because {@code Network.loadingFailed} carries no URL of
     * its own. Bounded by the life of one browser, which is one test.
     */
    private final Map<String, String> requestUrls = new ConcurrentHashMap<>();
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();
    private final List<String> jsExceptions = new CopyOnWriteArrayList<>();
    private final AtomicLong lastEventNanos = new AtomicLong(System.nanoTime());
    private volatile String page = "about:blank";

    private BrowserHealth() {
    }

    /**
     * Parks the browser on {@code about:blank}, resets it to the state a freshly launched
     * one would be in, attaches the listeners, and binds the recorder to the calling
     * thread.
     *
     * <p>The blank page is not decoration. CDP only reports traffic that happens after a
     * listener is registered, so the browser has to sit somewhere harmless while we set up;
     * attaching after navigating loses exactly the events we care about.
     *
     * <p>The browser here is usually NOT new -- {@code BrowserHealthExtension} keeps one
     * per test class rather than one per test -- so this method has to undo everything the
     * previous test left behind. Two things, and skipping either one is a silent defect
     * rather than a loud one: stale DevTools listeners would still be registered and would
     * double-count every subsequent event, and a surviving login cookie would let a test
     * pass on its predecessor's session instead of establishing its own.
     */
    static BrowserHealth attach() {
        // Deliberately NOT Selenide.open("about:blank"). Selenide only treats a URL
        // as absolute when it starts with http/https, so "about:blank" is taken as
        // relative and appended to Configuration.baseUrl -- the browser lands on
        // http://host:port/rollerabout:blank, a 404, and every subsequent assertion
        // fails against a Tomcat error page. Starting the driver directly and
        // navigating through WebDriver skips that rewriting.
        WebDriver driver = WebDriverRunner.getAndCheckWebDriver();
        driver.get("about:blank");
        DevTools devTools = devTools();
        devTools.createSessionIfThereIsNotOne();
        // Before registering ours, drop the previous test's -- addListener appends, so on a
        // reused session every test would otherwise be watched by one more set than the last.
        devTools.clearListeners();

        BrowserHealth health = new BrowserHealth();
        health.listen(devTools);
        resetSessionState(devTools);
        CURRENT.set(health);
        return health;
    }

    /**
     * Signs the browser out and empties its web storage, so a test inheriting a browser
     * from the one before it starts where a brand new browser would.
     *
     * <p>Done over CDP rather than through WebDriver because it has to work while parked on
     * {@code about:blank}: {@code deleteAllCookies()} only reaches cookies visible to the
     * current document, and {@code about:blank} has no origin, so it would silently clear
     * nothing at all. The CDP commands take the origin as an argument instead.
     *
     * <p>Runs after the listeners are attached, so the {@code Network} domain is enabled by
     * the time it is called. Neither command issues a request, so nothing it does reaches
     * the recorder.
     */
    private static void resetSessionState(DevTools devTools) {
        devTools.send(Network.clearBrowserCookies());
        devTools.send(Storage.clearDataForOrigin(PressroomIT.origin(),
                "local_storage,session_storage"));
    }

    /**
     * Installs a cookie on the browser without navigating anywhere.
     *
     * <p>Over CDP for the same reason {@link #resetSessionState} is: this is called while
     * parked on {@code about:blank}, and WebDriver's {@code addCookie} can only write a
     * cookie for the document currently loaded. The CDP command takes the URL the cookie
     * belongs to as an argument, and Chrome derives the domain and path from it.
     *
     * <p><b>The path is passed explicitly and must not be left to CDP.</b> Given only a
     * url, Chrome derives the cookie's path from the url's directory -- {@code /roller/}
     * for {@code http://host/roller/} -- while Tomcat sets its session cookie on the
     * context path itself, {@code /roller}. Those are DIFFERENT cookies. Both match a
     * request under {@code /roller/}, so the browser sends two JSESSIONIDs, the server
     * honours whichever it reads first, and a freshly installed session is silently
     * ignored in favour of a stale one left over from an earlier navigation. At the root
     * context both paths are {@code /}, so the two agree and nothing looks wrong -- which
     * is exactly why this survived a green suite and only failed under
     * {@code -Dit.context.path=roller}.
     *
     * @param name  cookie name, e.g. {@code JSESSIONID}
     * @param value cookie value
     * @param url   the URL the cookie is scoped to, context path included
     * @param path  the path the SERVER set the cookie on, so this overwrites rather than
     *              adding a second cookie of the same name
     */
    static void installCookie(String name, String value, String url, String path) {
        Boolean accepted = devTools().send(Network.setCookie(name, value, Optional.of(url),
                Optional.empty(), Optional.of(path), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));

        // CDP's setCookie answers with a success flag, and a rejected cookie is
        // reported ONLY there -- the command does not fail, so an unchecked call
        // returns normally having written nothing. That is the whole failure mode
        // worth guarding: the sign-in helper transplants a session cookie minted
        // over HTTP into Chrome, and a silent rejection would surface much later
        // as every authenticated test landing on the login page for no stated
        // reason. Checking the flag costs nothing (the round trip has already
        // happened) and names the cause at the point it occurs.
        if (!Boolean.TRUE.equals(accepted)) {
            throw new IllegalStateException(
                    "Chrome refused the cookie '" + name + "' for " + url
                            + ". The browser is therefore not signed in, and every "
                            + "assertion that assumes it is will fail somewhere else.");
        }
    }

    /**
     * The cookies Chrome holds for {@code url}, as name-to-value pairs.
     *
     * <p>Over CDP for the same reason the two methods above are: the caller may be parked
     * on {@code about:blank}, where WebDriver's cookie API sees nothing at all. The CDP
     * command takes the URL as an argument instead of reading it off the loaded document.
     *
     * @param url the URL whose cookies to read, context path included
     */
    static Map<String, String> readCookies(String url) {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (Cookie cookie : devTools().send(Network.getCookies(Optional.of(List.of(url))))) {
            cookies.put(cookie.getName(), cookie.getValue());
        }
        return cookies;
    }

    /** Empties Chrome's cookie jar, which is what signs the browser out. */
    static void clearCookies() {
        devTools().send(Network.clearBrowserCookies());
    }

    /** Unbinds the recorder from the calling thread; pairs with {@link #attach()}. */
    static void detach() {
        CURRENT.remove();
    }

    /**
     * The recorder watching this thread's browser.
     *
     * @throws IllegalStateException if no recorder is attached, which means the test is not
     *                               running under {@link BrowserHealthExtension}
     */
    public static BrowserHealth current() {
        BrowserHealth health = CURRENT.get();
        if (health == null) {
            throw new IllegalStateException("No browser-health recorder is attached to this thread. "
                    + "Integration tests must extend " + PressroomIT.class.getName()
                    + " (or carry @ExtendWith(" + BrowserHealthExtension.class.getSimpleName() + ".class)).");
        }
        return health;
    }

    private void listen(DevTools devTools) {
        devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));

        // Mandatory, not an optimisation: with the HTTP cache on, the second page to ask for a
        // resource is served a cached 200 and a genuine 404 introduced in between goes unseen.
        devTools.send(Network.setCacheDisabled(true));

        // Page.frameNavigated is how a response gets attributed to the page that pulled it in.
        // Only the main frame counts - Roller's media-file editor uses iframes, and an iframe
        // navigation must not relabel the enclosing page.
        devTools.send(Page.enable(Optional.empty()));
        devTools.addListener(Page.frameNavigated(), event -> {
            Frame frame = event.getFrame();
            if (frame.getParentId().isEmpty()) {
                page = frame.getUrl();
            }
            markEventSeen();
        });

        // Needed only so a failed request can be named: loadingFailed identifies
        // the request by id and nothing else.
        devTools.addListener(Network.requestWillBeSent(), event ->
                requestUrls.put(event.getRequestId().toString(), event.getRequest().getUrl()));

        // Requests that never produced a response at all. Without this the
        // suite is blind to a whole class of breakage -- see the exemption
        // comment on ABORT_IS_EXPECTED_FOR.
        devTools.addListener(Network.loadingFailed(), event -> {
            String type = String.valueOf(event.getType());
            String blockedReason = event.getBlockedReason().map(String::valueOf).orElse(null);
            boolean canceled = event.getCanceled().orElse(Boolean.FALSE);
            String url = requestUrls.getOrDefault(event.getRequestId().toString(), "(unknown url)");

            if (!isExcusableFailure(type, blockedReason, canceled, event.getErrorText())) {
                String reason = blockedReason != null
                        ? "blocked: " + blockedReason
                        : (event.getErrorText() == null || event.getErrorText().isBlank()
                                ? "request failed" : event.getErrorText());
                failedRequests.add(new FailedRequest(url, type, reason, page));
            }
            markEventSeen();
        });

        devTools.addListener(Network.responseReceived(), event -> {
            responses.add(new Resource(event.getResponse().getUrl(), event.getResponse().getStatus(),
                    event.getType().toString(), page));
            markEventSeen();
        });

        // These two come from Selenium's version-independent "idealized" domain, which enables
        // the Runtime domain for us; they survive a Chrome major-version bump that the pinned
        // v150 bindings above would not.
        devTools.getDomains().events().addConsoleListener(event -> {
            if (CONSOLE_ERROR_TYPE.equals(event.getType())) {
                consoleErrors.add(String.join(" ", event.getMessages()) + "   (while on " + page + ")");
            }
            markEventSeen();
        });
        devTools.getDomains().events().addJavascriptExceptionListener(event -> {
            jsExceptions.add(event.getRawMessage() + "   (while on " + page + ")");
            markEventSeen();
        });

        delayNavigatingClicksIfAsked(devTools);
    }

    /**
     * Environment variable that turns on the late-click reproduction mode: its value, in
     * milliseconds, is how long every navigating click waits before it takes effect.
     *
     * <p>Off unless set. It exists because the suite's worst flakes had one cause that no
     * rerun could show: a click whose form submission Chrome had not yet started when the
     * WebDriver click returned, so the test moved on -- read the old page, signed out, or
     * ended -- underneath it. On a starved CI runner that window is real and rare; here it
     * is made fixed and wide, so a test that reads the page it clicked on fails every time
     * instead of one nightly in four. {@code IT_LATE_CLICK_MS=150 mvn verify -Pit} must
     * pass; see docs/dev/browser-its.md, "A click is not a barrier".
     */
    static final String LATE_CLICK_ENV = "IT_LATE_CLICK_MS";

    /**
     * Holds back every trusted click on a submit button or a navigating link by
     * {@value #LATE_CLICK_ENV} milliseconds, then replays it as the page's own click.
     * The replay is untrusted, so the listener lets it through, and it does exactly what
     * the original would have -- validation, confirm dialogs, the submission with its
     * {@code formaction} -- only later. It still falls inside the original click's
     * transient user activation, so the late navigation behaves as a real one does.
     */
    private static void delayNavigatingClicksIfAsked(DevTools devTools) {
        String delay = System.getenv(LATE_CLICK_ENV);
        if (delay == null || delay.isBlank()) {
            return;
        }
        System.err.println("BrowserHealth: " + LATE_CLICK_ENV + "=" + delay
                + " -- late-click reproduction mode ACTIVE; this run is not a normal run");
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
                "(function () { if (window.__pressroomLateClick) { return; } window.__pressroomLateClick = true;"
                        + "document.addEventListener('click', function (e) {"
                        + "  if (!e.isTrusted || !e.target.closest) { return; }"
                        + "  var t = e.target.closest('button, input[type=submit], a[href]');"
                        + "  if (!t) { return; }"
                        + "  if (t.tagName === 'A') {"
                        + "    var h = t.getAttribute('href') || '';"
                        + "    if (h === '' || h.charAt(0) === '#' || h.indexOf('javascript:') === 0"
                        + "        || t.target === '_blank' || t.hasAttribute('data-bs-toggle')) { return; }"
                        + "  } else if ((t.getAttribute('type') || 'submit').toLowerCase() !== 'submit' || !t.form) {"
                        + "    return;"
                        + "  }"
                        + "  e.preventDefault(); e.stopImmediatePropagation();"
                        + "  setTimeout(function () { t.click(); }, " + Integer.parseInt(delay.trim()) + ");"
                        + "}, true); })();",
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private void markEventSeen() {
        lastEventNanos.set(System.nanoTime());
    }

    /**
     * Waits for the browser to go quiet before anything is asserted.
     *
     * <p>CDP events arrive on a websocket thread that is not synchronised with WebDriver
     * commands, so resources are routinely still in flight when Selenide considers the
     * navigation finished. Asserting straight after {@code open()} therefore reads a
     * half-filled event list and passes for the wrong reason.
     *
     * <p>Waiting for a stretch of silence rather than sleeping a fixed span keeps quick
     * pages quick and still covers a page that loads late.
     */
    public void settle() {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (System.nanoTime() - lastEventNanos.get() < QUIET_PERIOD.toNanos()) {
            // Give up rather than hang: assert on whatever has been recorded so far.
            if (System.nanoTime() - deadline > 0 || !sleepQuietly()) {
                return;
            }
        }
    }

    /** Fails if any sub-resource came back 4xx or 5xx. */
    public void assertNoBrokenResources() {
        report(brokenResourceReport());
    }

    /** Fails if the page logged a console error or threw an uncaught JavaScript exception. */
    public void assertNoConsoleErrors() {
        report(consoleReport());
    }

    /**
     * Both checks at once, reported together: one run should surface every problem the page
     * has rather than one per fix-and-rerun cycle.
     */
    public void assertHealthy() {
        report(Stream.of(blindnessReport(), brokenResourceReport(), failedRequestReport(),
                        consoleReport())
                .flatMap(List::stream)
                .toList());
    }

    /**
     * Fails when this recorder saw NOTHING while the browser was demonstrably on
     * a real page -- i.e. when the monitor itself has gone blind.
     *
     * <p>Every other check here reports by finding something wrong in what it
     * recorded, so all of them pass vacuously when nothing is recorded at all:
     * {@code brokenResourceReport} over an empty list is an empty report, which
     * reads exactly like a clean page. A monitor that has stopped seeing traffic
     * therefore turns the whole suite green rather than red, on 126 tests at
     * once, and nothing in the output says so.
     *
     * <p>That is not a hypothetical failure mode, it is the one this class is
     * most exposed to. The listeners are registered through a Chrome DevTools
     * Protocol binding pinned to one CDP major ({@code devtools.v155}); Chrome
     * ships a new major every few weeks, and Selenium answers a mismatch by
     * falling back to the nearest binding it has and logging a line nobody
     * reads. The fallback works until it doesn't, and the failure is silence.
     *
     * <p>The discriminator has to come from OUTSIDE CDP or it is circular --
     * {@link #page} is itself set by a CDP event, so a blind recorder also
     * believes it is still on {@code about:blank}. {@code getCurrentUrl()} goes
     * over the WebDriver protocol instead, which is a separate channel: if
     * WebDriver says the browser is on an http(s) page and CDP recorded no
     * response for it, the two disagree and the recorder is the one that is
     * wrong. A test that never navigates (several drive the endpoints over
     * plain HTTP instead) parks on {@code about:blank} and is correctly
     * exempt.
     */
    private List<String> blindnessReport() {
        if (!responses.isEmpty()) {
            return List.of();
        }
        String url;
        try {
            url = WebDriverRunner.getAndCheckWebDriver().getCurrentUrl();
        } catch (RuntimeException cannotAsk) {
            // No opinion is the right answer here: if the browser cannot even be
            // asked where it is, this check has nothing to compare against, and
            // whatever went wrong will surface as the test's own failure.
            return List.of();
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return List.of();
        }
        return List.of(
                "The browser is on " + url + " but this recorder saw no network traffic at all.",
                "  That is a broken MONITOR, not a clean page: with nothing recorded, every",
                "  other check here passes vacuously and the whole suite goes green blind.",
                "  Most likely the CDP bindings no longer match the browser -- Selenium falls",
                "  back to its nearest version silently (look for \"Unable to find an exact match",
                "  for CDP version\" in the output). Fix: pin selenium-devtools-vNNN in",
                "  it-selenium/pom.xml to the version this Chrome speaks and update the imports",
                "  in BrowserHealth.",
                "  If that URL is a form's POST target, suspect the PREVIOUS test first: it clicked",
                "  a submit, did not wait for the answer, and the late navigation committed after",
                "  this test's recorder was attached (PressroomIT.clickAndAwaitNewPage).");
    }

    /**
     * Fails if a request ended without a response for a reason the page cannot
     * be excused for.
     *
     * <p>This is not the same check as {@link #assertNoBrokenResources()}, and
     * it exists because that one has a blind spot: a stylesheet whose URL 404s
     * is served an HTML error page, and Chrome -- refusing to apply a
     * stylesheet with the wrong content type -- aborts the load rather than
     * completing it. No response event is ever emitted, so the status check
     * never sees it. A theme whose CSS had gone missing rendered unstyled and
     * passed. Webfonts refused by a page's own Content-Security-Policy arrive
     * here too, for the same reason.
     */
    public void assertNoFailedRequests() {
        report(failedRequestReport());
    }

    /**
     * Paths this test expects the server to refuse.
     *
     * <p>A test that proves one user cannot touch another's content has to
     * actually attempt it, and a correct refusal is a 4xx -- which is
     * otherwise reported as a broken page. Declaring the path keeps the check
     * meaningful everywhere else instead of forcing such a test to skip the
     * health assertion wholesale.
     */
    private final List<String> expectedRefusals = new ArrayList<>();

    /** Declares that requests ending in {@code pathSuffix} are meant to fail. */
    public void expectRefusal(String pathSuffix) {
        expectedRefusals.add(pathSuffix);
    }

    private List<String> brokenResourceReport() {
        List<Resource> broken = responses.stream()
                .filter(resource -> resource.status() >= FIRST_FAILING_STATUS)
                .filter(resource -> !isIgnored(resource.url()))
                .filter(resource -> expectedRefusals.stream()
                        .noneMatch(suffix -> pathOf(resource.url()).endsWith(suffix)))
                .toList();
        if (broken.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("The browser loaded " + broken.size() + " broken sub-resource(s):");
        broken.forEach(resource -> lines.add("  " + resource));
        return lines;
    }

    private List<String> failedRequestReport() {
        List<FailedRequest> failed = failedRequests.stream()
                .filter(request -> !isIgnored(request.url()))
                .filter(request -> expectedRefusals.stream()
                        .noneMatch(suffix -> pathOf(request.url()).endsWith(suffix)))
                .toList();
        if (failed.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("The browser gave up on " + failed.size() + " request(s) without a response:");
        failed.forEach(request -> lines.add("  " + request));
        return lines;
    }

    private List<String> consoleReport() {
        List<String> lines = new ArrayList<>();
        if (!consoleErrors.isEmpty()) {
            lines.add("The browser logged " + consoleErrors.size() + " console error(s):");
            consoleErrors.forEach(message -> lines.add("  " + message));
        }
        if (!jsExceptions.isEmpty()) {
            lines.add("The browser saw " + jsExceptions.size() + " uncaught JavaScript exception(s):");
            jsExceptions.forEach(message -> lines.add("  " + message));
        }
        return lines;
    }

    private static void report(List<String> problems) {
        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
    }

    private static boolean isIgnored(String url) {
        String path = pathOf(url);
        return isOriginRootFaviconProbeOutsideTheContext(path)
                || IGNORED_PATH_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    /**
     * The one structural exception, and it is inert at the root context path.
     *
     * <p>For a document that declares no icon -- {@code sitemap.xml}, {@code robots.txt},
     * a feed -- Chrome probes {@code /favicon.ico} at the <em>origin root</em>, not under
     * the document's path. When Roller is deployed under a servlet context prefix
     * ({@code -Dit.context.path=roller}) that location is outside the application: Tomcat
     * answers 404 and nothing Roller ships can change that, because no file of the
     * application's can live above its context. HTML pages are unaffected -- their heads
     * declare the icon inside the context, so the probe never fires for them. At the root
     * context the probe hits the real {@code /favicon.ico} and 200s, so this rule excuses
     * nothing there. It is deliberately narrower than a path-suffix rule: only the exact
     * origin-root path, and only when a context prefix is in force.
     */
    private static boolean isOriginRootFaviconProbeOutsideTheContext(String path) {
        if (!"/favicon.ico".equals(path)) {
            return false;
        }
        String base = com.codeborne.selenide.Configuration.baseUrl;
        if (base == null) {
            return false;
        }
        String contextPath = pathOf(base);
        return contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath);
    }

    /** Ignore rules match on the path, so a cache-busting query string cannot defeat them. */
    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? url : path;
        } catch (IllegalArgumentException e) {
            // data:, blob: and friends; nothing on the ignore list ever looks like that.
            return url;
        }
    }

    /** @return false if the wait was interrupted, in which case the caller must stop waiting */
    private static boolean sleepQuietly() {
        try {
            Thread.sleep(SETTLE_POLL_INTERVAL.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Selenide hands out a decorated driver; DevTools lives on the real one underneath. */
    private static DevTools devTools() {
        WebDriver driver = WebDriverRunner.getWebDriver();
        while (driver instanceof WrapsDriver wrapper) {
            driver = wrapper.getWrappedDriver();
        }
        if (!(driver instanceof HasDevTools hasDevTools)) {
            throw new IllegalStateException("Browser-health checks need a DevTools-capable driver "
                    + "(Chrome or Edge), but got " + driver.getClass().getName());
        }
        return hasDevTools.getDevTools();
    }
}
