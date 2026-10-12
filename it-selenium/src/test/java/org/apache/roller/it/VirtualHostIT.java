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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.codeborne.selenide.Configuration;
import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.TestInstance;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The virtual-host wave's acceptance criteria, end to end, against the real
 * packaged WAR -- everything before this class was unit-tested against mocks
 * or an in-memory servlet mapper.
 *
 * <p>The IT harness reaches the app over {@code 127.0.0.1}, so there is no
 * real DNS for a custom domain. Two different techniques stand in for it,
 * chosen per test:
 *
 * <ul>
 *   <li>Most assertions here are redirect/status/body checks that a plain
 *       {@link HttpClient} can make by sending an explicit {@code Host}
 *       header -- {@link #get(String, String)}, following {@code ApiIT}'s
 *       established pattern for this module. {@code HttpClient.Redirect.NEVER}
 *       is load-bearing: the 301 status and {@code Location} header ARE the
 *       assertion, and the redirect target ({@code https://vhost.example.com})
 *       does not exist as a live TLS endpoint anywhere in this environment, so
 *       nothing may ever attempt to actually follow it.</li>
 *   <li>Criterion 1 needs a REAL rendered page -- specifically, one whose
 *       sub-resources (theme CSS/JS/webfonts) a real browser actually
 *       fetches, which is exactly the failure mode ("theme assets not
 *       resolving on a custom domain") this class exists to catch. A plain
 *       {@code Host}-header GET cannot exercise that. Chrome's
 *       {@code --host-resolver-rules} flag maps {@link #VHOST} to
 *       {@code 127.0.0.1} for the whole class (see {@link #enableVhostDns()}),
 *       so {@link #openVhostPath(String)} can navigate a real browser
 *       straight to the custom domain over plain HTTP (this environment has
 *       no certificate, so only the {@code https://} redirect targets are
 *       ever asserted as strings -- actual navigation stays on {@code http://}).
 *       This has to be class-wide, set in {@link #enableVhostDns()}
 *       (a JUnit {@code @BeforeAll}), because {@code BrowserHealthExtension}
 *       (registered on {@link RollerIT}) creates its driver in a
 *       {@code BeforeEachCallback}, which always runs before any
 *       {@code @BeforeEach} method this class could declare -- by the time a
 *       {@code @Test} or {@code @BeforeEach} body runs, the driver already
 *       exists, so there is no later point at which per-test Chrome
 *       capabilities could still take effect. Setting {@code Configuration}
 *       fields in {@code @BeforeAll} is not sufficient by itself, though --
 *       see {@link #enableVhostDns()}'s own javadoc for the once-per-suite
 *       race that also requires {@link RollerIT#markSelenideConfigured()}.</li>
 * </ul>
 *
 * <p>Every criterion here documents, in its own javadoc, whether it was
 * expected to pass on arrival (this whole class is acceptance testing of
 * already-implemented behaviour, not TDD) -- see the wave's task-11 report
 * for the actual run results.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(RollerIT.GLOBAL_CONFIG)
class VirtualHostIT extends RollerIT {

    private static final String VHOST = "vhost.example.com";

    /** Rendered on the entry edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /** The dedicated weblog this class owns, created fresh by {@link #createVhostWeblog()}. */
    private String vhostHandle;

    /** {@code site.absoluteurl} as it stood before this test touched it, restored in {@link #removeVhostWeblog()}. */
    private String priorSiteAbsoluteUrl;

    // ------------------------------------------------------------- fixture

    /**
     * Maps {@link #VHOST} to {@code 127.0.0.1} for every browser this class
     * starts, so {@link #openVhostPath(String)} can actually reach a
     * rendered page at the custom domain. See the class javadoc for why this
     * has to run once, class-wide, in {@code @BeforeAll} rather than per test.
     *
     * <p>Deliberately does not call {@code RollerIT.configureSelenide()} --
     * it is package-private to {@code org.apache.roller.it.support}, and this
     * class lives in {@code org.apache.roller.it}. Setting the same
     * {@link Configuration} fields directly is equivalent, but NOT on its own
     * idempotent against {@code configureSelenide()}'s own once-per-suite
     * guard: that method still runs (and overwrites {@code browserCapabilities}
     * back to the plain, no-host-resolver-rules default) the first time
     * {@code BrowserHealthExtension.beforeEach()} calls it, UNLESS something
     * has already marked configuration done. When some other IT class happens
     * to run before this one, that something is that other class's own first
     * test; when this class runs standalone (as in
     * {@code -Dit.test=VirtualHostIT}, exactly the run shape used to develop
     * this fixture) or happens to run first in the full suite, nothing has,
     * and {@code aCustomDomainRendersTheHomePageAndPermalink} fails with
     * {@code net::ERR_NAME_NOT_RESOLVED} despite the flag being set right
     * here -- it gets clobbered moments later, before this class's own first
     * test even starts. {@link RollerIT#markSelenideConfigured()} closes that
     * race by marking configuration done from THIS {@code @BeforeAll}
     * instead, so the later, ordinary {@code configureSelenide()} call is a
     * no-op and these capabilities survive regardless of run order.
     */
    @BeforeAll
    void enableVhostDns() {
        Selenide.closeWebDriver();
        Configuration.baseUrl = baseUrl();
        Configuration.browser = "chrome";
        Configuration.headless = true;
        Configuration.browserSize = BROWSER_SIZE;
        // Through RollerIT.chromeOptions rather than a hand-rolled list: this class only
        // needs ONE extra switch, and repeating the shared list here is how a flag added
        // to the suite silently fails to reach the one class that resolves a second host.
        Configuration.browserCapabilities =
                chromeOptions("--host-resolver-rules=MAP " + VHOST + " 127.0.0.1");
        RollerIT.markSelenideConfigured();
    }

    /**
     * Tears the class fixture down and undoes {@link #enableVhostDns()}, so
     * later test classes in the suite get a plain Chrome instance.
     *
     * <p>One {@code @AfterAll} rather than two, because the order in which
     * JUnit runs several {@code @AfterAll} methods of the same class is not
     * specified -- and these two are order-dependent, the fixture teardown
     * needing the browser this method then closes.
     */
    @AfterAll
    void removeVhostWeblogAndDisableVhostDns() {
        inBrowserAfterAll(this::removeVhostWeblog);
        Selenide.closeWebDriver();
        Configuration.browserCapabilities = chromeOptions();
    }

    /**
     * Creates ONE weblog for the whole class, with {@link #VHOST} as its
     * custom domain, and points {@code site.absoluteurl} at the site host so
     * the control-plane redirect (criterion 3) has something real to redirect
     * to.
     *
     * <p><b>Once per class, not once per test, and the guard is what makes it
     * so.</b> The obvious spelling of "once per class" is {@code @BeforeAll},
     * and it does not work here: {@code BrowserHealthExtension} creates the
     * WebDriver from a {@code BeforeEachCallback}, so a {@code @BeforeAll}
     * body has no browser to drive and every helper below it navigates. The
     * fixture therefore stays in {@code @BeforeEach} and skips itself once
     * built -- which {@code @TestInstance(PER_CLASS)}, already on this class
     * for {@link #enableVhostDns()}, is what makes possible: the instance
     * field survives from one test to the next.
     *
     * <p>Sharing is safe because every test here is additive against the
     * fixture and none of them mutates it: five are pure {@link #get} calls
     * that only read, two publish an entry under a {@link #nonce()} title,
     * one publishes a page, and criterion 11 restores
     * {@code site.absoluteurl} in its own {@code finally}. Nothing renames
     * the weblog, clears its custom domain, or deletes it -- the operations
     * a later test would notice.
     *
     * <p>It is worth the ceremony: the fixture is nine page loads and cost
     * ~9.7s, which for the five tests whose entire body is one HTTP GET was
     * essentially all of their runtime, and this class sits on the
     * {@link RollerIT#GLOBAL_CONFIG} lock -- the suite's critical path -- so
     * every second saved here is a second off the wall clock rather than a
     * second returned to a thread pool that has other work anyway.
     *
     * <p>The seeded {@code it_weblog} is deliberately left alone -- other IT
     * classes depend on it carrying no custom domain.
     */
    @BeforeEach
    void createVhostWeblogOnce() {
        if (vhostHandle != null) {
            return;
        }
        loginAsAdmin();
        priorSiteAbsoluteUrl = setSiteAbsoluteUrl(baseUrl());
        vhostHandle = createWeblog();
        setCustomDomain(vhostHandle, VHOST);
        logout();
    }

    /**
     * Removes the fixture weblog and restores {@code site.absoluteurl} -- both
     * global state, so neither may be left behind for the next class.
     *
     * <p>Guarded on the handle because a failure inside
     * {@link #createVhostWeblogOnce()} leaves it null, and an
     * {@code @AfterAll} that then threw would replace the real failure with a
     * {@code NullPointerException} from the teardown.
     */
    private void removeVhostWeblog() {
        if (vhostHandle == null) {
            return;
        }
        loginAsAdmin();
        removeWeblog(vhostHandle);
        setSiteAbsoluteUrl(priorSiteAbsoluteUrl);
        logout();
    }

    // ---------------------------------------------------------- criterion 1

    /**
     * {@code GET https://b.example.com/} renders that weblog's home page;
     * {@code GET https://b.example.com/entry/x} renders its permalink.
     *
     * <p>The one test in this class that drives a real browser at the
     * spoofed custom domain (see class javadoc) -- specifically so
     * {@code BrowserHealthExtension} can catch a theme asset that 404s or is
     * refused only when served from the vhost branch of
     * {@code WeblogRequestMapper}, which a raw HTTP body check would never
     * see.
     *
     * <p>Expected to pass on arrival: the vhost rendering path and every
     * theme asset it serves already exist from earlier tasks in this wave.
     */
    @Test
    void aCustomDomainRendersTheHomePageAndPermalink() {
        String title = "Vhost render check " + nonce();

        loginAsAdmin();
        String permalink = publishEntry(vhostHandle, title);
        logout();
        String anchor = anchorOf(permalink);

        openVhostPath("/");
        $("body").shouldHave(text(title));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();

        openVhostPath("/entry/" + anchor);
        $("body").shouldHave(text(title));
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    // ---------------------------------------------------------- criterion 2

    /**
     * {@code GET https://site/handle/entry/x?p=2} -> 301
     * {@code https://b.example.com/entry/x?p=2} -- query string preserved.
     *
     * <p>Expected to pass on arrival: {@code WeblogRequestMapper}'s
     * canonical-host redirect (Task 3) does not require the path to name a
     * real entry -- it fires purely on the resolved handle carrying a custom
     * domain, before any attempt to resolve {@code x} as an anchor.
     */
    @Test
    void thePathFormRedirectsToTheCustomDomain() throws Exception {
        HttpResponse<String> response = get("/" + vhostHandle + "/entry/x?p=2", "127.0.0.1");
        assertEquals(301, response.statusCode());
        assertEquals(customDomainUrl("/entry/x?p=2"),
                response.headers().firstValue("Location").orElseThrow());
    }

    // ---------------------------------------------------------- criterion 3

    /**
     * {@code GET https://b.example.com/roller-ui/menu.rol} -> 301 to the
     * same path on the site host. {@code GET https://b.example.com/api/v1/ping}
     * likewise.
     *
     * <p>The one criterion that depends on {@code site.absoluteurl} being
     * configured -- {@link #createVhostWeblog()} sets it to {@link #baseUrl()}
     * for exactly this test (see criterion 11 for the unset case).
     *
     * <p>Expected to pass on arrival: {@code ControlPlaneHostFilter} (Task 6)
     * runs ahead of Spring Security in the filter chain and redirects both
     * paths unconditionally once a vhost handle resolves.
     */
    @Test
    void adminAndApiLeaveTheCustomDomain() throws Exception {
        HttpResponse<String> admin = get("/roller-ui/menu.rol", VHOST);
        assertEquals(301, admin.statusCode());
        assertEquals(baseUrl() + "/roller-ui/menu.rol",
                admin.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> api = get("/api/v1/ping", VHOST);
        assertEquals(301, api.statusCode());
        assertEquals(baseUrl() + "/api/v1/ping",
                api.headers().firstValue("Location").orElseThrow());
    }

    // ---------------------------------------------------------- criterion 4

    /**
     * {@code POST https://b.example.com/roller-ui/rendering/contact.rol} is
     * NOT redirected. {@code POST https://b.example.com/newsletter/subscribe}
     * likewise.
     *
     * <p>The silent-breakage guard: both endpoints are posted by
     * {@code fetch} from the rendered blog page under a {@code connect-src
     * 'self'} CSP, so a cross-origin redirect here breaks every
     * {@code [contact]}/{@code [subscribe]} shortcode on every vhost weblog,
     * visible only in a browser console -- never as a failed HTTP assertion
     * anywhere else in this wave's test suite.
     *
     * <p>A plain GET (not a POST) is enough to prove the point: both
     * endpoints are {@code @PostMapping}-only, so a GET here answers 405
     * from Spring MVC itself, never 301 from {@code ControlPlaneHostFilter}
     * -- the filter's {@code belongsToSiteHost} check runs on the path alone
     * and does not care about the HTTP method.
     *
     * <p>Expected to pass on arrival: {@code ControlPlaneHostFilter}
     * explicitly excludes {@code /roller-ui/rendering/} before it ever
     * checks the {@code /roller-ui/} prefix, and {@code /newsletter/} never
     * matches {@code belongsToSiteHost} at all.
     */
    @Test
    void theContactAndSubscribeEndpointsStayOnTheCustomDomain() throws Exception {
        assertNotEquals(301, get("/roller-ui/rendering/contact.rol", VHOST).statusCode());
        assertNotEquals(301, get("/newsletter/subscribe", VHOST).statusCode());
    }

    // ---------------------------------------------------------- criterion 5

    /**
     * A rendered permalink's {@code rel=canonical} and {@code og:url} are
     * the domain form, whichever host served the request.
     *
     * <p>Proved two ways for the same entry: (a) requested directly on the
     * custom domain, where {@code #showSeoHead} renders it straight from
     * {@code PageModel#getCanonicalUrl}; and (b) requested via the site
     * host's path form, where the 301 {@code Location} (criterion 2's own
     * mechanism) names the IDENTICAL url -- proving a reader arriving by
     * either route converges on the same canonical address, which is the
     * whole point of {@code MultiWeblogURLStrategy#getWeblogURL} deriving
     * urls from the WEBLOG rather than the inbound request (see that
     * method's own javadoc: the render cache is keyed on the handle, not the
     * host, so a request-derived url would let whichever host rendered first
     * stamp its own canonical onto the other's cached response).
     *
     * <p>A raw {@code Host}-header GET is enough here, matching the
     * established pattern in {@code EditorSeoIT} (which asserts the same
     * {@code rel="canonical" href="..."} / {@code property="og:url"
     * content="..."} fragments via {@code getAnonymously}, not a browser) --
     * canonical/og:url are plain response-body text, not something that
     * needs a rendered DOM to observe.
     *
     * <p>Expected to pass on arrival: Task 5 changed
     * {@code MultiWeblogURLStrategy#getWeblogURL} to read the custom domain
     * off the weblog; this is the end-to-end proof of that one-method change.
     */
    @Test
    void canonicalAndOgUrlAreTheDomainFormWhicheverHostServedTheRequest() throws Exception {
        String title = "Vhost canonical check " + nonce();

        loginAsAdmin();
        String permalink = publishEntry(vhostHandle, title);
        logout();
        String anchor = anchorOf(permalink);
        String expectedCanonical = customDomainUrl("/entry/" + anchor);

        HttpResponse<String> onVhost = get("/entry/" + anchor, VHOST);
        assertEquals(200, onVhost.statusCode(), onVhost.body());
        assertTrue(onVhost.body().contains("rel=\"canonical\" href=\"" + expectedCanonical + "\""),
                "expected the domain-form canonical, got:\n" + onVhost.body());
        assertTrue(onVhost.body().contains("property=\"og:url\" content=\"" + expectedCanonical + "\""),
                "expected the domain-form og:url, got:\n" + onVhost.body());

        HttpResponse<String> viaSiteHost = get("/" + vhostHandle + "/entry/" + anchor, "127.0.0.1");
        assertEquals(301, viaSiteHost.statusCode());
        assertEquals(expectedCanonical, viaSiteHost.headers().firstValue("Location").orElseThrow(),
                "a reader arriving via the site host's path form must be sent to the exact "
                        + "same address the vhost-served page names as its own canonical");
    }

    // ---------------------------------------------------------- criterion 6

    /**
     * {@code https://b.example.com/robots.txt} names
     * {@code https://b.example.com/sitemap.xml}; that sitemap lists only
     * that weblog's URLs, every one in domain form.
     *
     * <p>Expected to pass on arrival: {@code SeoController} (Task 8) is
     * host-aware on both endpoints.
     */
    @Test
    void robotsOnTheCustomDomainNamesItsOwnSitemap() throws Exception {
        assertTrue(get("/robots.txt", VHOST).body()
                .contains("Sitemap: " + customDomainUrl("/sitemap.xml")));

        String sitemap = get("/sitemap.xml", VHOST).body();
        assertFalse(sitemap.contains("<sitemapindex"),
                "a custom domain's /sitemap.xml must be that weblog's OWN sitemap, "
                        + "not the site-wide index:\n" + sitemap);
        assertTrue(sitemap.contains("<loc>" + customDomainUrl("/") + "</loc>"),
                "the custom domain's own sitemap must list its home page in domain form:\n" + sitemap);
    }

    // ---------------------------------------------------------- criterion 7

    /**
     * The site host's {@code /sitemap.xml} index omits custom-domain
     * weblogs.
     *
     * <p>Expected to pass on arrival: {@code SeoController#sitemapIndex}
     * skips any weblog whose {@code customDomain} is non-blank.
     */
    @Test
    void theSiteIndexOmitsTheCustomDomainWeblog() throws Exception {
        assertFalse(get("/sitemap.xml", "127.0.0.1").body().contains(vhostHandle));
    }

    // --------------------------------------------------------- criterion 10

    /**
     * {@code https://maiiavorobiova.com/jakefear/} resolves as a page-slug
     * candidate on Maiia's weblog and NOT as the {@code jakefear} weblog --
     * generalised here as: a path segment that happens to match the SEEDED
     * weblog's own handle resolves as a page slug on the vhost weblog.
     *
     * <p>Proved with real content rather than just a routing trace: a
     * published page slugged exactly {@link #WEBLOG_HANDLE} carries a
     * distinctive marker string, and fetching that path on {@link #VHOST}
     * returns 200 with the marker present -- so it genuinely rendered THIS
     * page, not (say) a silent redirect or forward to the {@code it_weblog}
     * weblog's own home page under a different mechanism entirely.
     *
     * <p>Expected to pass on arrival: on the vhost branch of
     * {@code WeblogRequestMapper}, the ENTIRE path is content-relative (the
     * host already resolved the handle), so the first segment is never
     * treated as a candidate weblog handle the way it would be on the site
     * host's path form.
     */
    @Test
    void aPathSegmentMatchingAnotherWeblogsHandleResolvesAsAPageSlugOnTheVhostWeblog() throws Exception {
        String marker = "Vhost page slug marker " + nonce();

        loginAsAdmin();
        createAndPublishPage(vhostHandle, WEBLOG_HANDLE, "Not the " + WEBLOG_HANDLE + " weblog", marker);
        logout();

        HttpResponse<String> response = get("/" + WEBLOG_HANDLE, VHOST);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(marker),
                "a path segment matching another weblog's handle must resolve as a page slug "
                        + "on the CUSTOM-DOMAIN weblog, not route to that other weblog:\n"
                        + response.body());
    }

    // --------------------------------------------------------- criterion 11

    /**
     * With {@code site.absoluteurl} unset and at least one weblog holding a
     * custom domain, {@code GET https://b.example.com/roller-ui/menu.rol} is
     * served, not redirected -- the degraded path is pre-vhost behaviour,
     * never a loop.
     *
     * <p>{@code site.absoluteurl} is a shared, global runtime property (see
     * {@link RollerIT#setGlobalFlag}'s own javadoc on the same hazard for
     * boolean flags); it is restored in a {@code finally} here on top of
     * {@link #removeVhostWeblog()}'s own restore, so a failure inside the
     * try block cannot leave later test classes running against a cleared
     * value.
     *
     * <p>Expected to pass on arrival: {@code ControlPlaneHostFilter}
     * degrades to {@code chain.doFilter(...)} when {@code site.absoluteurl}
     * resolves to null/blank, specifically to avoid building a redirect
     * target from {@code getAbsoluteContextURL()}'s InitFilter-latched value
     * -- which, reached over the custom domain, would loop forever.
     *
     * <p><b>"Served, not redirected" means never redirected CROSS-HOST, not
     * never redirected at all.</b> {@code menu.rol} requires an
     * authenticated session, and this test deliberately runs unauthenticated
     * (nothing here logs in before the request) -- so once
     * {@code ControlPlaneHostFilter} degrades and calls
     * {@code chain.doFilter(...)}, the request falls through to ordinary,
     * pre-vhost Spring Security behaviour, which for an unauthenticated
     * request IS a redirect: a same-host 302 to
     * {@code http://vhost.example.com/roller-ui/login.rol}. That is correct
     * and expected, not a regression -- the behaviour this criterion actually
     * protects is that the degraded path can never build a CROSS-host
     * redirect (toward a site host {@code site.absoluteurl} never named),
     * which is the specific loop {@code ControlPlaneHostFilter}'s degrade
     * branch exists to avoid. So: no redirect at all is a pass outright: any
     * redirect is only a pass if its {@code Location} names the SAME host the
     * request went to.
     */
    @Test
    void withSiteAbsoluteUrlUnsetTheControlPlaneIsServedNotRedirected() throws Exception {
        loginAsAdmin();
        String duringTest = setSiteAbsoluteUrl("");
        logout();
        try {
            HttpResponse<String> response = get("/roller-ui/menu.rol", VHOST);
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isPresent()) {
                assertEquals(VHOST, URI.create(location.get()).getHost(),
                        "the degraded path must never redirect cross-host, which is "
                                + "exactly the loop this behaviour exists to avoid: " + location.get());
            }
            // No Location header at all (e.g. the login form served directly) is
            // "served, not redirected" in the most literal sense and needs no
            // further assertion -- see this method's javadoc for why a same-host
            // redirect to the login page, the actual observed shape, is also fine.
        } finally {
            loginAsAdmin();
            setSiteAbsoluteUrl(duringTest);
            logout();
        }
    }

    // --------------------------------------------------------- url building

    /**
     * The custom-domain form of {@code path} under the configured servlet
     * context path -- e.g. {@code https://vhost.example.com/entry/x} at the
     * root context, {@code https://vhost.example.com/roller/entry/x} under a
     * {@code /roller} prefix. Every assertion in this class that predicts a
     * domain-form URL (a 301 {@code Location}, a canonical/og:url, a sitemap
     * {@code <loc>}) must route through this rather than concatenating
     * {@code "https://" + VHOST} directly, because that literal shape is only
     * correct at the root context -- see {@link #contextPath()} for where the
     * context segment actually comes from.
     */
    private static String customDomainUrl(String path) {
        return "https://" + VHOST + contextPath() + path;
    }

    /**
     * The servlet context path segment of {@link #baseUrl()} -- {@code ""} at
     * the root context, {@code "/roller"} under a prefix. Derived from the
     * same {@value RollerIT#BASE_URL_PROPERTY} system property the harness
     * was actually started with, rather than a second, independently-read
     * source (e.g. an {@code it.context.path} property) that could drift
     * from it.
     */
    private static String contextPath() {
        return URI.create(baseUrl()).getPath();
    }

    // ---------------------------------------------------------------- HTTP

    /** Does NOT follow redirects: the 301 itself is the assertion. */
    private HttpResponse<String> get(String path, String host) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Host", host)
                .GET().build();
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build().send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------- browser

    /**
     * Navigates a real browser to a path on {@link #VHOST}, over plain HTTP
     * on the same port the IT harness reserved -- {@code --host-resolver-rules}
     * (set once for the whole class in {@link #enableVhostDns()}) is what
     * makes {@link #VHOST} resolve to {@code 127.0.0.1} at all.
     */
    private void openVhostPath(String path) {
        Selenide.open(vhostUrl(path));
        BrowserHealth.current().settle();
    }

    private String vhostUrl(String path) {
        URI base = URI.create(baseUrl());
        return "http://" + VHOST + ":" + base.getPort() + contextPath() + path;
    }

    // --------------------------------------------------------------- weblog

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "vhostit" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("VHost IT " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Permanently deletes the fixture weblog through the real confirm-and-remove flow. */
    private void removeWeblog(String handle) {
        openPath("/roller-ui/authoring/weblogRemove.rol?weblog=" + handle);
        $("form[action$='/roller-ui/authoring/weblogRemove!remove.rol'] button[type='submit']")
                .should(visible).click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    /** Sets the weblog's custom domain on Weblog Settings and saves. */
    private void setCustomDomain(String handle, String domain) {
        openWeblogSettings(handle);
        $("input[name='bean.customDomain']").setValue(domain);
        saveWeblogSettings();
    }

    private void openWeblogSettings(String handle) {
        openPath("/roller-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.name']").should(visible);
    }

    private void saveWeblogSettings() {
        $("button[type='submit'].btn-primary").should(visible).click();
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(),
                "saving the weblog settings reported an error: "
                        + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));
        BrowserHealth.current().settle();
    }

    /** Publishes one entry and returns its permalink (already domain-form, the custom domain is set first). */
    private String publishEntry(String handle, String title) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("input[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    /** The last path segment of a permalink, i.e. the entry's anchor. */
    private static String anchorOf(String permalink) {
        return permalink.substring(permalink.lastIndexOf('/') + 1);
    }

    /**
     * Creates a page at the given slug, publishes it, and follows the
     * two-step draft-then-publish flow {@code PageIT}/{@code ContactFormIT}
     * already establish: the status select is only present once the page
     * already exists.
     */
    private void createAndPublishPage(String handle, String slug, String title, String body) {
        openPath("/roller-ui/authoring/pageEdit.rol?weblog=" + handle);
        $("#pageEditForm").should(exist);
        $("#page_bean_slug").setValue(slug);
        $("#page_bean_title").setValue(title);
        Editor.setText(body);
        saveOpenPage();

        openPath("/roller-ui/authoring/pages.rol?weblog=" + handle);
        $("#pageRemoveForm").should(exist);
        $$("#pageRemoveForm a").findBy(text(title)).should(exist).click();
        $("#pageEditForm").should(exist);
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        saveOpenPage();
    }

    private void saveOpenPage() {
        $("#pageEditForm button[type='submit']").click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
