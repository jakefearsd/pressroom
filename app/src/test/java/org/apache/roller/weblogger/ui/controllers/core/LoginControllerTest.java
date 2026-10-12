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
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.List;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.apache.roller.weblogger.ui.core.security.sso.SsoSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        String view = controller.execute(ControllerTestFixture.requestFor(null), model, null, null);

        assertEquals(".Login", view);
        assertEquals(List.of(), ControllerTestFixture.errors(model),
                "an untried login form must not accuse the visitor of anything");
        assertEquals("loginPage.title", model.getAttribute("pageTitle"));
    }

    @Test
    void comingBackWithAnErrorParameterShowsTheFailureMessage() {
        // Spring Security bounces a failed login back to /login.rol?error, and
        // this is the only thing that tells the user their attempt failed.
        String view = controller.execute(ControllerTestFixture.requestFor(null), model, "", null);

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
        return c.execute(ControllerTestFixture.requestFor(null), model, null, sso);
    }

    @Test
    void ssoOnShowsTheProviderButtonWithAnEscapedLabel() {
        runWith(new SsoSettings("id", "s", SsoSettings.GOOGLE_DISCOVERY, "<b>G</b>"), null);

        assertEquals(Boolean.TRUE, model.getAttribute("ssoEnabled"));
        assertEquals("&lt;b&gt;G&lt;/b&gt;", model.getAttribute("ssoLabel"));
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
}
