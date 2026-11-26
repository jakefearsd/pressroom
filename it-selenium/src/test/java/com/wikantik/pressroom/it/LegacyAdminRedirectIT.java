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

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.webdriver;
import static com.codeborne.selenide.WebDriverConditions.url;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.pressroom.it.support.PressroomIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A8 step 1 of the React admin migration, on the real container and at both context paths: a screen the
 * React admin has taken over keeps its old {@code .rol} URL, which now answers 302 to the React route with
 * the weblog handle and the query carried over, and a sign-in lands on the app home. The base URL includes
 * the context path, so one test covers both.
 *
 * <p>Each row of {@code LegacyAdminRedirects} is driven twice: raw (the status and {@code Location}, which
 * a browser would hide) and in the browser (the React screen drew its own marker). A signed-out visitor to an
 * old bookmark signs in and arrives at the new screen.
 */
class LegacyAdminRedirectIT extends PressroomIT {

    @Test
    void menuGoesToTheAppHome() throws Exception {
        loginAsAdmin();
        assertRedirect("/pressroom-ui/menu.rol", "/pressroom-ui/app/");

        openPath("/pressroom-ui/menu.rol");
        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/"));
        $("h2.mm_weblog_name").should(exist);
        logout();
    }

    @Test
    void weblogTrafficCarriesTheHandleAndThePeriod() throws Exception {
        loginAsAdmin();
        assertRedirect("/pressroom-ui/authoring/traffic.rol?weblog=" + WEBLOG_HANDLE + "&period=7d",
                "/pressroom-ui/app/weblogs/" + WEBLOG_HANDLE + "/traffic?period=7d");

        openPath("/pressroom-ui/authoring/traffic.rol?weblog=" + WEBLOG_HANDLE + "&period=7d");
        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/weblogs/" + WEBLOG_HANDLE + "/traffic?period=7d"));
        $("#trafficPage").should(exist);
        $("#trafficPage nav.traffic-period a.on").shouldHave(com.codeborne.selenide.Condition.text("7d"));
        logout();
    }

    @Test
    void siteTrafficCarriesThePeriod() throws Exception {
        loginAsAdmin();
        assertRedirect("/pressroom-ui/admin/siteTraffic.rol?period=90d", "/pressroom-ui/app/admin/traffic?period=90d");

        openPath("/pressroom-ui/admin/siteTraffic.rol");
        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/admin/traffic"));
        $("#siteTrafficPage").should(exist);
        logout();
    }

    /**
     * Wave 3: every legacy settings, theme, template, stylesheet, member, inbox and remove URL answers 302 to its
     * React route with the weblog handle (and a template's id, an inbox's page) carried over; create weblog needs
     * no parameters. One signed-in session for all rows.
     */
    @Test
    void wave3ScreensRedirectWithTheirHandleAndIds() throws Exception {
        loginAsAdmin();
        String app = "/pressroom-ui/app/weblogs/" + WEBLOG_HANDLE;
        assertRedirect("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + WEBLOG_HANDLE, app + "/settings");
        assertRedirect("/pressroom-ui/authoring/themeEdit.rol?weblog=" + WEBLOG_HANDLE, app + "/theme");
        assertRedirect("/pressroom-ui/authoring/templates.rol?weblog=" + WEBLOG_HANDLE, app + "/templates");
        assertRedirect("/pressroom-ui/authoring/templateEdit.rol?weblog=" + WEBLOG_HANDLE + "&bean.id=t-1",
                app + "/templates/t-1");
        assertRedirect("/pressroom-ui/authoring/stylesheetEdit.rol?weblog=" + WEBLOG_HANDLE, app + "/stylesheet");
        assertRedirect("/pressroom-ui/authoring/members.rol?weblog=" + WEBLOG_HANDLE, app + "/members");
        assertRedirect("/pressroom-ui/authoring/submissions.rol?weblog=" + WEBLOG_HANDLE + "&page=2",
                app + "/inbox?page=2");
        assertRedirect("/pressroom-ui/authoring/weblogRemove.rol?weblog=" + WEBLOG_HANDLE, app + "/remove");
        assertRedirect("/pressroom-ui/createWeblog.rol", "/pressroom-ui/app/weblogs/new");
        logout();
    }

    /** The redirects land on the screens: settings and create weblog draw their own markers in the browser. */
    @Test
    void theSettingsAndCreateBookmarksArriveAtTheirScreens() {
        loginAsAdmin();
        openPath("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + WEBLOG_HANDLE);
        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/weblogs/" + WEBLOG_HANDLE + "/settings"));
        $("#settingsSectionIndex").should(exist);

        openPath("/pressroom-ui/createWeblog.rol");
        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/weblogs/new"));
        $("#handlePreview").should(exist);
        logout();
    }

    /** A bookmark followed while signed out goes to sign in, and the sign-in returns to the new screen. */
    @Test
    void anOldBookmarkWhileSignedOutSignsInAndArrivesAtTheNewScreen() {
        openPath("/pressroom-ui/authoring/traffic.rol?weblog=" + WEBLOG_HANDLE);
        $("#j_username").should(exist).setValue(ADMIN_USERNAME);
        $("#j_password").setValue(ADMIN_PASSWORD);
        $("#login").click();

        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/weblogs/" + WEBLOG_HANDLE + "/traffic"));
        $("#trafficPage").should(exist);
        logout();
    }

    /** A sign-in with nothing to return to lands on the app home, whatever weblogs the account holds. */
    @Test
    void aSignInWithNothingToReturnToLandsOnTheAppHome() {
        openPath("/pressroom-ui/login.rol");
        $("#j_username").setValue(ADMIN_USERNAME);
        $("#j_password").setValue(ADMIN_PASSWORD);
        $("#login").click();

        webdriver().shouldHave(url(baseUrl() + "/pressroom-ui/app/"));
        $("h2.mm_weblog_name").should(exist);
        $("a[href$='/pressroom-ui/logout.rol']").should(exist);
        logout();
    }

    /** The raw answer: 302 and a host-relative Location under the context path. */
    private void assertRedirect(String legacy, String expectedLocation) throws Exception {
        Map<String, String> cookies = new LinkedHashMap<>();
        for (java.net.HttpCookie cookie : authenticateOverHttp(ADMIN_USERNAME, ADMIN_PASSWORD)) {
            cookies.put(cookie.getName(), cookie.getValue());
        }
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + legacy))
                    .timeout(Duration.ofSeconds(20))
                    .header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining("; ")))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, response.statusCode(), legacy);
            String location = response.headers().firstValue("Location").orElse("");
            String contextPath = URI.create(baseUrl()).getPath();
            assertEquals(contextPath + expectedLocation, location, legacy);
            assertTrue(location.startsWith("/"), "host-relative: " + location);
        } finally {
            invalidateServerSession(cookies);
        }
    }
}
