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
import java.util.List;

import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The blog pages' reader sign-in surfaces (0.18.0, Part D) in a real
 * browser, at whichever context path the run uses: a signed-out visitor's
 * way in at the top of a blog page and of the front page is the provider's
 * button, going straight to {@code {ctx}/pressroom-ui/sso/start} with the
 * page as its return path (added by the page's script); a signed-in author
 * sees none; and {@code window.pressroomGoogleSignIn}, the helper Part E
 * builds its Save and Follow buttons with, makes the same element.
 *
 * <p>The suite's provider is Keycloak, so the label is "Sign in with
 * Keycloak" and the mark is the neutral one. The comments' button, its
 * greyed composer and the focus after the round trip are driven by
 * {@code CommentsIT}, which owns the comment-enabled fixture and the
 * Keycloak readers; the places nudge needs a second visited blog and is
 * pinned by {@code GoogleSignInRenderTest}.
 *
 * <p>Reads the front page, which is the discovery page only while no IT has
 * set a front-page weblog, and the Absolute URL the button is built from;
 * hence the shared lock on global config.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class ReaderSignInSurfacesIT extends PressroomIT {

    private static final String SSO_START = "/pressroom-ui/sso/start";
    private static final String LABEL = "Sign in with Keycloak";

    @Test
    void aBlogPageHeaderOffersTheProvidersButtonWithThePageAsItsReturn() {
        String page = "/" + WEBLOG_HANDLE + "/?surfaces=1";
        openPath(page);

        SelenideElement button = $("nav.site-bar .site-bar-account a.g-signin").shouldBe(visible);
        assertHeaderButton(button, contextPath() + page);
        $("nav.site-bar .site-bar-sign-in").shouldNot(exist);
    }

    @Test
    void theFrontPageMastheadOffersItToo() {
        openPath("/");
        SelenideElement button = $(".fd-mast .fd-account a.g-signin").shouldBe(visible);
        assertHeaderButton(button, contextPath() + "/");
    }

    @Test
    void aSignedInAuthorSeesNoProvidersButton() {
        loginAsAdmin();
        openPath("/" + WEBLOG_HANDLE + "/");
        $("nav.site-bar .site-bar-who").shouldBe(visible);
        $("a.g-signin").shouldNot(exist);
        logout();
    }

    /**
     * Part E's contract: the helper returns an element like the macro's, whose
     * return path is the page's path, query and hash, under the context.
     */
    @Test
    void theHelperBuildsTheSameButton() {
        String page = "/" + WEBLOG_HANDLE + "/?helper=1";
        openPath(page + "#top");
        List<Object> built = executeJavaScript(
                "var a = window.pressroomGoogleSignIn('save', 'Sign in to save this');"
                + " return [a.className, a.getAttribute('data-umami-event'), a.getAttribute('data-umami-event-via'),"
                + " a.hasAttribute('data-sign-in-return'), a.href, a.textContent, !!a.querySelector('svg.g-signin-mark')];");
        assertNotNull(built);
        assertEquals("g-signin", built.get(0));
        assertEquals("sign-in", built.get(1));
        assertEquals("save", built.get(2));
        assertEquals(Boolean.TRUE, built.get(3));
        String href = (String) built.get(4);
        assertEquals(contextPath() + SSO_START, URI.create(href).getPath(), href);
        assertEquals(contextPath() + page + "#top", queryParameter(href, "return"), href);
        assertEquals("Sign in to save this", built.get(5));
        assertEquals(Boolean.TRUE, built.get(6));
    }

    private static void assertHeaderButton(SelenideElement button, String expectedReturn) {
        button.shouldHave(exactText(LABEL), attribute("data-umami-event", "sign-in"),
                attribute("data-umami-event-via", "header"));
        button.$("svg.g-signin-mark").should(exist);
        button.$("path[fill='#4285F4']").shouldNot(exist);
        String href = button.getAttribute("href");
        assertNotNull(href);
        assertEquals(contextPath() + SSO_START, URI.create(href).getPath(), "not the provider's start: " + href);
        assertEquals(expectedReturn, queryParameter(href, "return"), "the page's script adds the return: " + href);
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
