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

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analytics pages as an administrator sees them on a server with no Umami
 * configured, which is what the IT app is: Blog &rsaquo; Traffic and Global
 * &rsaquo; Traffic say so and show no numbers, Maintenance's Analytics block
 * says analytics is off and offers no Sync, and no reader page loads a tracker.
 * The configured states (tiles, tables, the Sync button, the tag) are covered by
 * the controller and rendering unit tests; the IT stack has no Umami to read.
 *
 * <p><b>Written after the behaviour</b>, as browser coverage of pages the unit
 * suite already specifies, not as their specification. Each test was shown able
 * to fail by breaking its expectation once (see the Task 13b report).
 *
 * <p>Every test reads the seeded {@code it_weblog} and creates nothing, so
 * there is no fixture to clean up. The tracker check uses a raw, cookie-less
 * GET rather than a browser visit, for the reason {@link AnalyticsInjectionIT}
 * gives: a tag, were one emitted, would 404 against the IT stack and trip
 * {@link BrowserHealth} on a failure the test provoked, hiding the real
 * message. The GET asserts a 200 and an HTML head, so a redirect or an empty
 * body cannot pass it vacuously.
 *
 * <p>Holds {@code GLOBAL_CONFIG} in READ mode: the site root is the directory
 * or a front-page weblog depending on {@code ColophonIT}'s setting, and
 * {@code site.absoluteurl} writers ({@code VirtualHostIT}, {@code SsoSignInIT},
 * {@code CommentsIT}) change every rendered URL. Readers do not exclude each
 * other, so this costs no parallelism against the other READ classes.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class AnalyticsIT extends PressroomIT {

    /** ApplicationResources.properties, traffic.state.notConfigured. */
    private static final String NOT_CONFIGURED =
            "Traffic is not set up on this server, so there are no numbers to show.";

    /** ApplicationResources.properties, maintenance.analytics.off. */
    private static final String ANALYTICS_OFF =
            "Analytics is off: no Umami address is configured.";

    private static final String TRACKER = "/analytics/script.js";

    @Test
    void trafficPageSaysNotConfigured() {
        loginAsAdmin();

        openPath("/pressroom-ui/authoring/traffic.rol?weblog=" + WEBLOG_HANDLE);
        $("#trafficPage").should(exist);
        $("#trafficPage .traffic-notice-notConfigured").should(exactText(NOT_CONFIGURED));
        $("#trafficPage .traffic-tiles").shouldNot(exist);
        $("#trafficPage .traffic-tile").shouldNot(exist);

        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
        logout();
    }

    @Test
    void siteTrafficPageSaysNotConfigured() {
        loginAsAdmin();

        openPath("/pressroom-ui/admin/siteTraffic.rol");
        $("#siteTrafficPage").should(exist);
        $("#siteTrafficPage .traffic-notice-notConfigured").should(exactText(NOT_CONFIGURED));
        $("#siteTrafficPage .traffic-table").shouldNot(exist);

        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
        logout();
    }

    @Test
    void blogPagesCarryNoTracker() {
        assertNoTracker(baseUrl() + "/" + WEBLOG_HANDLE + "/");
        assertNoTracker(baseUrl() + "/");
    }

    @Test
    void maintenanceShowsAnalyticsBlock() {
        loginAsAdmin();

        openPath("/pressroom-ui/admin/maintenance.rol");
        $(".maintenance-analytics").should(exist);
        $(".maintenance-analytics h2").should(exactText("Analytics"));
        $(".maintenance-analytics").should(text(ANALYTICS_OFF));
        $(".maintenance-analytics button").shouldNot(exist);
        $("form[action$='maintenance!syncAnalytics.rol'] button").shouldNot(exist);

        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
        logout();
    }

    // ---------------------------------------------------------------- helpers

    /** GETs {@code url} as an anonymous reader and asserts the page carries no tracker. */
    private static void assertNoTracker(String url) {
        HttpResponse<String> response = getWithStatus(url);
        String body = response.body();
        assertEquals(200, response.statusCode(), "GET " + url + " should render a page, got: " + snippet(body));
        assertTrue(body.contains("<head"), "GET " + url + " should be an HTML page, got: " + snippet(body));
        assertFalse(body.contains(TRACKER),
                "with analytics unconfigured " + url + " must not load the tracker, got: " + snippet(body));
        assertFalse(body.contains("data-website-id"),
                "with analytics unconfigured " + url + " must carry no tracker tag, got: " + snippet(body));
    }

    /** Like {@link #getAnonymously}, but the status is kept so a redirect cannot pass as a clean page. */
    private static HttpResponse<String> getWithStatus(String url) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url + " anonymously", e);
        }
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? "<<" + flat + ">>" : "<<" + flat.substring(0, 300) + "...>>";
    }
}
