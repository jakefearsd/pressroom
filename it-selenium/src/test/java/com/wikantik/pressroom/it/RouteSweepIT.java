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
package com.wikantik.pressroom.it;

import com.codeborne.selenide.ElementsCollection;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pressroom.it.support.PressroomIT;
import com.wikantik.pressroom.it.support.Routes;
import com.wikantik.pressroom.it.support.Routes.BrokenRoute;
import com.wikantik.pressroom.it.support.Routes.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.Cookie;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Visits every reachable admin-UI GET route as an administrator and asserts the
 * page actually rendered its own content.
 *
 * <p>Reachability sweeps usually degrade into "did it return 200", which is
 * worthless here. Roller's tabbedpage layout supplies a banner, a navigation
 * menu, a footer and an {@code <h1 class="roller-page-title">} regardless of
 * what the content tile contains, so {@code categoryEdit.rol} -- whose content
 * tile is wired to empty.jsp -- returns a perfectly healthy-looking 200 with no
 * form on it. Every route therefore asserts a marker that only its own content
 * tile emits; see {@link Routes} for the per-route selector and why each was
 * chosen.
 *
 * <p>Routes already known to be broken live in {@link #knownBrokenRoutesAreStillBroken()},
 * which asserts they fail. Fixing one of them turns that test red, which is the
 * point: the fix cannot land without moving the route into the sweep proper.
 *
 * <p>Console errors and 404ing sub-resources are checked by the
 * BrowserHealthExtension that {@link PressroomIT} registers -- this class does not
 * repeat that work.
 *
 * <p><b>This class holds {@code GLOBAL_CONFIG} in READ mode, and it has to.</b>
 * It visits every admin route, so it is sensitive to <em>every</em> site-wide
 * runtime flag any other class permutes -- and the symptom is never an obvious
 * concurrency error, it is a marker that "should" be on the page and isn't.
 * Observed: {@code GlobalConfigMatrixIT} turns {@code groupblogging.enabled}
 * off to prove a second weblog is refused, and while it is off
 * {@code CreateWeblogController} answers {@code .GenericError} for an admin who
 * already owns a weblog -- a healthy 200 carrying full site chrome and no form,
 * which is precisely the {@code categoryEdit.rol} failure mode this sweep exists
 * to catch. Same shape as the {@code uploads.enabled} collision CLAUDE.md
 * records for the media classes. READ mode is what matters: it excludes the
 * five writers without serialising this class against the other readers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class RouteSweepIT extends PressroomIT {

    /**
     * WebDriver exposes no HTTP status code (the W3C protocol simply does not
     * carry one), so status is read with a second, plain HTTP request that
     * replays the browser's session cookie. The browser navigation still
     * happens, because that is what feeds the browser-health checks.
     */
    private HttpClient http;

    /**
     * Logs in for every case rather than once for the class.
     *
     * <p>A single class-wide login would be faster, but BrowserHealthExtension
     * deliberately gives each test its own browser: DevTools listeners accumulate
     * on a reused session, and a surviving login cookie would let a test pass on
     * the previous test's authentication. Since the browser does not outlive the
     * test, neither can the session. The cost is roughly a second per route; the
     * isolation is worth more than the minute it adds.
     */
    @BeforeEach
    void logIn() {
        loginAsAdmin();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.wikantik.pressroom.it.support.Routes#covered")
    void routeRendersItsOwnContent(Route route) {
        openPath(route.url());

        int status = statusOf(route.url());
        assertTrue(status < 500,
                route.url() + " returned HTTP " + status + ". A 5xx means the controller or "
                        + "its JSP threw; check the Tomcat log written to "
                        + "it-selenium/target/tomcat.log for the stack trace.");

        ElementsCollection found = $$(route.marker());
        assertTrue(found.size() > 0,
                route.url() + " returned HTTP " + status + " but nothing matched '"
                        + route.marker() + "', so the page rendered site chrome without its "
                        + "own content. Either the view definition lost its content tile (the "
                        + "categoryEdit.rol failure mode), or the JSP changed and the marker in "
                        + "Routes needs updating -- open the page and check which before "
                        + "relaxing this assertion.");
    }

    /**
     * Smoke test for the "quiet instrument" design wave's rail
     * (tiles-tabbedpage.jsp / tiles-mainmenupage.jsp, {@code id="adminRail"}):
     * a tabbed admin page renders the rail, and exactly the current tab's
     * link carries the {@code .rail-active} spine treatment. Entries is a
     * convenient, already-covered tabbed route (see {@link Routes#covered()}),
     * not a new fixture -- this rides the same login/weblog setup the rest of
     * the sweep uses.
     */
    @Test
    void adminRailIsPresentWithAnActiveSpineOnATabbedPage() {
        openPath("/pressroom-ui/authoring/entries.rol?weblog=" + Routes.WEBLOG_HANDLE);

        assertTrue($$("#adminRail").size() > 0,
                "Expected the tabbed admin layout to render the #adminRail nav");
        assertTrue($$("#adminRail .rail-active").size() > 0,
                "Expected #adminRail to have a .rail-active link marking the current tab");
    }

    /**
     * Asserts every route in {@link Routes#broken()} is still broken -- the
     * list is EMPTY today (its former residents were all fixed or deleted),
     * and this test exists for the next one.
     *
     * <p>Deliberately inverted. If somebody parks a broken route there and
     * later fixes it, this test fails and tells them to move the route
     * into the normal sweep with a real marker. Without that, a fixed page would
     * simply stay untested forever.
     *
     * <p>All six are checked in one test, with the failures collected, so a
     * single fix reports as one clear message instead of stopping at the first
     * assertion.
     */
    @Test
    void knownBrokenRoutesAreStillBroken() {
        // Currently empty: every route this list held has been fixed. Kept so the
        // next discovered-but-not-yet-fixable route has somewhere to go.
        assumeFalse(Routes.broken().isEmpty(), "no known-broken routes are recorded");

        List<String> nowWorking = new ArrayList<>();

        for (BrokenRoute route : Routes.broken()) {
            openPath(route.url());
            int status = statusOf(route.url());

            switch (route.breakage()) {
                case VIEW_NOT_RESOLVED -> {
                    // The forward targets a JSP that is not on disk, so the
                    // container answers 404 (or 500 if Jasper blows up first).
                    if (status < 400) {
                        nowWorking.add(route.url() + " -> HTTP " + status
                                + " (expected >= 400 while the view is unresolvable)");
                    }
                }
                case EMPTY_CONTENT_TILE -> {
                    // The definition resolves, so this is a healthy-looking 200
                    // whose content tile is empty.jsp. p.subtitle is the tell:
                    // every real content tile emits one and nothing under
                    // WEB-INF/jsps/tiles does.
                    boolean hasContent = $$("p.subtitle").size() > 0;
                    if (status >= 400) {
                        nowWorking.add(route.url() + " -> HTTP " + status
                                + " (expected 200 with an empty content tile; this is a "
                                + "different failure than the one recorded in Routes)");
                    } else if (hasContent) {
                        nowWorking.add(route.url() + " -> HTTP " + status
                                + " and now renders content");
                    }
                }
                default -> fail("Unhandled breakage kind " + route.breakage()
                        + " for " + route.url() + "; teach this test how to check it.");
            }
        }

        assertTrue(nowWorking.isEmpty(),
                "Route(s) recorded as broken in Routes.broken() no longer fail the way they "
                        + "used to:\n  " + String.join("\n  ", nowWorking)
                        + "\nIf you fixed them, delete them from Routes.BROKEN and add them to "
                        + "the covered list with a marker selector, so the sweep starts "
                        + "asserting on them. If the failure merely changed shape, update the "
                        + "Breakage kind and its reason.");
    }

    /**
     * Guards the guard. A @MethodSource that quietly returns nothing makes the
     * parameterised sweep above vacuously green, and an empty broken list makes
     * the inverted test above vacuously green too.
     */
    @Test
    void theCatalogueIsNotEmpty() {
        assertTrue(Routes.covered().size() >= 20,
                "Routes.covered() returned only " + Routes.covered().size()
                        + " routes. The sweep is meant to cover every parameterless and "
                        + "weblog-only GET route; if routes were removed from the catalogue "
                        + "rather than from the controllers, RouteCoverageTest in the app "
                        + "module will not catch it because it only checks the other direction.");
    }

    /**
     * Issues the same GET the browser just made, carrying the browser's session
     * cookie, purely to read the status line. Redirects are followed so the
     * status reflects where the request ends up, matching what the browser did.
     */
    private int statusOf(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .GET();

        Cookie session = WebDriverRunner.getWebDriver().manage().getCookieNamed("JSESSIONID");
        if (session != null) {
            request.header("Cookie", session.getName() + "=" + session.getValue());
        }

        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the status of " + path, e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not reach " + baseUrl() + path + " to read its HTTP status. The "
                            + "browser loaded it, so this is a problem with the status probe "
                            + "rather than with the route.", e);
        }
    }
}
