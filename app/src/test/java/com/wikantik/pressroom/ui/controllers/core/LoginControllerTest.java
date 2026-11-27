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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.List;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.ui.core.security.sso.SignInMark;
import com.wikantik.pressroom.ui.core.security.sso.SsoSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LoginController}, the sign-in page.
 */
class LoginControllerTest {

    private MockWeblogger weblogger;
    private LoginController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new LoginController(new SsoSettings("", "", "", "")));
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void theSignInPageStartsWithNoComplaint() {
        String view = controller.execute(ControllerTestFixture.requestFor(null), model, null, null, null, null);

        assertEquals(".Login", view);
        assertEquals(List.of(), ControllerTestFixture.errors(model),
                "an untried login form must not accuse the visitor of anything");
        assertEquals("loginPage.title", model.getAttribute("pageTitle"));
    }

    @Test
    void comingBackWithAnErrorParameterShowsTheFailureMessage() {
        // Spring Security bounces a failed login back to /login.rol?error, and
        // this is the only thing that tells the user their attempt failed.
        String view = controller.execute(ControllerTestFixture.requestFor(null), model, "", null, null, null);

        assertEquals(".Login", view);
        assertEquals(List.of("error.password.mismatch"), ControllerTestFixture.errors(model));
    }

    @Test
    void theSignInPageIsReachableWithoutBeingSignedIn() {
        // Requiring a user here would make the login page unreachable.
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertEquals("loginPage.title", controller.getPageTitle());
    }

    private String runWith(SsoSettings settings, String sso) {
        LoginController c = ControllerTestFixture.withMessages(new LoginController(settings));
        return c.execute(ControllerTestFixture.requestFor(null), model, null, sso, null, null);
    }

    @Test
    void ssoOnShowsTheProviderButtonWithAnEscapedLabel() {
        runWith(new SsoSettings("id", "s", SsoSettings.GOOGLE_DISCOVERY, "<b>G</b>"), null);

        assertEquals(Boolean.TRUE, model.getAttribute("ssoEnabled"));
        assertEquals("&lt;b&gt;G&lt;/b&gt;", model.getAttribute("ssoLabel"));
    }

    @Test
    void aLabelWithACommaReachesTheMessageAsOneArgument() {
        // spring:message splits a String argument on ',', so the JSP is handed an array.
        runWith(new SsoSettings("id", "s", "", "Google, Inc."), null);

        assertArrayEquals(new Object[] {"Google, Inc."}, (Object[]) model.getAttribute("ssoLabelArgs"));
    }

    @Test
    void ssoOffShowsNoButton() {
        runWith(new SsoSettings("", "", "", ""), null);

        assertEquals(Boolean.FALSE, model.getAttribute("ssoEnabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "failed", "refused"})
    void knownCodesShowTheirMessage(String code) {
        runWith(new SsoSettings("id", "s", "", ""), code);

        assertEquals(List.of("login.sso." + code), ControllerTestFixture.errors(model));
    }

    @Test
    void unknownCodesAreIgnored() {
        runWith(new SsoSettings("id", "s", "", ""), "<script>");

        assertTrue(ControllerTestFixture.errors(model).isEmpty());
    }
    /**
     * A page's Sign in link carries the page's path; the form and the Google
     * button hand it on so the visitor comes back to that page. Anything but a
     * path under the context is dropped, never echoed into the page.
     */
    @Test
    void aReturnPathUnderTheContextIsHandedToTheForm() {
        controller.execute(ControllerTestFixture.requestFor(null), model, null, null, "/investing/entry/x?y=1", null);

        assertEquals("/investing/entry/x?y=1", model.getAttribute("returnPath"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example/", "https://evil.example/", "/a\r\nX: y", ""})
    void anythingElseIsDropped(String hostile) {
        controller.execute(ControllerTestFixture.requestFor(null), model, null, null, hostile, null);

        assertEquals(null, model.getAttribute("returnPath"), hostile);
    }

    @Test
    void noReturnPathMeansNone() {
        controller.execute(ControllerTestFixture.requestFor(null), model, null, null, null, null);

        assertEquals(null, model.getAttribute("returnPath"));
    }

    // ------------------------------------------------ reader-first page (0.18.0, Part D)

    private static final SsoSettings GOOGLE = new SsoSettings("id", "s", SsoSettings.GOOGLE_DISCOVERY, "Google");
    private static final SsoSettings KEYCLOAK =
            new SsoSettings("id", "s", "http://localhost:8180/realms/pressroom-it", "Keycloak");

    private void siteNamed(String name) throws Exception {
        when(weblogger.propertiesManager().getProperty("site.name"))
                .thenReturn(new RuntimeConfigProperty("site.name", name));
    }

    private void open(SsoSettings settings, String error, String author) {
        LoginController c = ControllerTestFixture.withMessages(new LoginController(settings));
        c.execute(ControllerTestFixture.requestFor(null), model, error, null, null, author);
    }

    /**
     * With SSO on the page is the readers' door: its heading names the site
     * (escaped here, since spring:message arguments are not escaped).
     */
    @Test
    void ssoOnHeadsThePageSignInToTheSiteName() throws Exception {
        siteNamed("Maiia & <Co>");
        open(GOOGLE, null, null);

        // The fixture's message source echoes the key and its arguments: the
        // title is login.reader.title's text, which the layout prints as the
        // code's fallback, with the name escaped once.
        assertEquals("login.reader.title[Maiia &amp; &lt;Co&gt;]", model.getAttribute("pageTitle"));
        assertEquals("Maiia &amp; &lt;Co&gt;", model.getAttribute("siteName"));
    }

    @Test
    void aBlankSiteNameFallsBackToTheProductName() throws Exception {
        siteNamed("  ");
        open(GOOGLE, null, null);

        assertEquals("login.reader.title[Pressroom]", model.getAttribute("pageTitle"));
    }

    /** SSO off: the page title and the model are what 0.17.1 rendered from. */
    @Test
    void ssoOffKeepsTheOldTitleAndAddsNothing() {
        open(new SsoSettings("", "", "", ""), null, null);

        assertEquals("loginPage.title", model.getAttribute("pageTitle"));
        assertFalse(model.containsAttribute("siteName"));
        assertFalse(model.containsAttribute("ssoMark"));
        assertFalse(model.containsAttribute("authorSignInOpen"));
    }

    @Test
    void theMarkFollowsTheProvider() {
        open(GOOGLE, null, null);
        assertEquals(SignInMark.forProvider(GOOGLE), model.getAttribute("ssoMark"));

        model = new ExtendedModelMap();
        open(KEYCLOAK, null, null);
        assertEquals(SignInMark.forProvider(KEYCLOAK), model.getAttribute("ssoMark"));
    }

    /** The author form is collapsed, and opens by itself on a failed password or {@code ?author}. */
    @Test
    void theAuthorFormOpensOnAnErrorOrAuthorParameter() {
        open(GOOGLE, null, null);
        assertEquals(Boolean.FALSE, model.getAttribute("authorSignInOpen"));

        model = new ExtendedModelMap();
        open(GOOGLE, "", null);
        assertEquals(Boolean.TRUE, model.getAttribute("authorSignInOpen"), "?error");

        model = new ExtendedModelMap();
        open(GOOGLE, null, "");
        assertEquals(Boolean.TRUE, model.getAttribute("authorSignInOpen"), "?author");
    }
}
