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

import java.net.URI;
import java.net.URLDecoder;

import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.hidden;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in page with reader sign-in on (0.18.0, Part D), in a real
 * browser: the provider's button is the first control, the heading names the
 * site, and the password form waits in a collapsed "Author sign-in" section
 * that one click opens, and that opens by itself on a failed sign-in, on
 * {@code ?author}, and for a browser that last signed in with a password.
 *
 * <p>The suite's provider is its Keycloak (SSO is on for every class), so the
 * button reads "Continue with Keycloak" and carries the neutral mark, never
 * Google's G. <b>The SSO-off page is not driven here:</b> the packaged WAR
 * reads the SSO client once at startup and the suite runs one app with it
 * set, so turning it off would mean a second app; the SSO-off page is pinned
 * by {@code LoginPageSsoOffCharacterisationTest} and {@code LoginControllerTest}
 * (docs/dev/browser-its.md, "Keycloak (reader sign-in)").
 */
class LoginPageIT extends PressroomIT {

    private static final String LOGIN = "/pressroom-ui/login.rol";
    private static final String SSO_START = "/pressroom-ui/sso/start";
    private static final String FLAG = "pressroom.authorSignIn";

    /** The first control the keyboard reaches in the page's content. */
    private static final String FIRST_CONTROL = "return document.querySelector('main').querySelector("
            + "'a[href], button, summary, select, textarea, input:not([type=hidden])').id";

    @AfterEach
    void forgetThePasswordSignIn() {
        // Other classes share this browser; leave the section collapsed for them.
        if (WebDriverRunner.hasWebDriverStarted() && WebDriverRunner.url().startsWith("http")) {
            executeJavaScript("window.localStorage.removeItem(arguments[0])", FLAG);
        }
    }

    @Test
    void theProvidersButtonComesFirstAndTheAuthorFormIsOneClickAway() {
        String returnPath = contextPath() + "/" + WEBLOG_HANDLE + "/?from=login-it";
        openPath(LOGIN + "?return=" + java.net.URLEncoder.encode(returnPath, UTF_8));

        $("h1.pressroom-page-title").shouldHave(text("Sign in to "));
        $(".login-reader-tagline").shouldHave(exactText("Comment, save articles and follow the blogs you like."));
        assertEquals("ssoSignIn", executeJavaScript(FIRST_CONTROL), "the provider's button is the first control");

        SelenideElement button = $("#ssoSignIn").shouldBe(visible).shouldHave(exactText("Continue with Keycloak"),
                attribute("data-umami-event", "sign-in"), attribute("data-umami-event-via", "login-page"));
        button.$("svg.g-signin-mark").should(exist);
        button.$("path[fill='#4285F4']").shouldNot(exist);
        String href = button.getAttribute("href");
        assertNotNull(href);
        assertEquals(contextPath() + SSO_START, URI.create(href).getPath(), href);
        assertEquals(returnPath, queryParameter(href, "return"), "the provider's way keeps the return: " + href);

        SelenideElement author = $("details#authorSignIn");
        assertNull(author.getAttribute("open"), "the author form starts collapsed");
        $("#j_username").shouldBe(hidden);
        author.$("summary").shouldHave(exactText("Author sign-in")).click();
        $("#j_username").shouldBe(visible);
        $("#j_password").shouldBe(visible);
        $("#loginForm input[name='return']").shouldHave(value(returnPath));
    }

    @Test
    void theAuthorFormOpensForAuthorAndForAFailedSignIn() {
        openPath(LOGIN + "?author");
        $("#j_username").shouldBe(visible);

        openPath(LOGIN + "?error=true");
        $("#j_username").shouldBe(visible);
        $("#errors").should(exist);
    }

    /** The third way in: a browser that last signed in with a password finds the form open. */
    @Test
    void aBrowserThatLastUsedAPasswordFindsTheFormOpen() {
        openPath(LOGIN);
        executeJavaScript("window.localStorage.removeItem(arguments[0])", FLAG);
        openPath(LOGIN);
        $("#j_username").shouldBe(hidden);

        openAuthorSignIn();
        // No such account: the flag is set on submit, whatever the answer.
        $("#j_username").setValue("nobody-login-it");
        $("#j_password").setValue("not-a-password-" + System.nanoTime());
        clickAndAwaitNewPage($("#login"));
        assertEquals("1", executeJavaScript("return window.localStorage.getItem(arguments[0])", FLAG));

        openPath(LOGIN);
        $("#j_username").shouldBe(visible);
        $("details#authorSignIn").shouldHave(attribute("open"));
    }

    private static String contextPath() {
        String path = URI.create(baseUrl()).getPath();
        return path == null ? "" : path;
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
}
