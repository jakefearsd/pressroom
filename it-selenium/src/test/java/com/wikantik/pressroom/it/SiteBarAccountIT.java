/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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

import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.attributeMatching;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The way in and out at the top of every blog page (2026-11-24), driven
 * through the real form. Since 0.18.0 (Part D) reader sign-in is on for the
 * whole suite, so a visitor's way in at the top is the provider's button
 * ({@code ReaderSignInSurfacesIT}); an author's is the colophon's Sign in,
 * which carries the page as its return path (added by the page's script,
 * which nothing else exercises). The password sign-in comes back to that
 * page, and the bar then says who is signed in and, in its "You" menu
 * (0.18.0, Part E), offers the Main Menu and Sign out. Reads the
 * site's Absolute URL (the links are built from it), hence the shared lock on
 * global config.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class SiteBarAccountIT extends PressroomIT {

    @Test
    void signingInFromABlogPageComesBackToIt() {
        String page = "/" + WEBLOG_HANDLE + "/?sitebar=1";
        openPath(page);
        $("nav.site-bar a.g-signin").shouldBe(visible);
        $(".colophon a.colophon-sign-in").shouldHave(text("Sign in"))
                .shouldHave(attributeMatching("href", ".*/pressroom-ui/login\\.rol\\?return=.*sitebar.*"));

        clickAndAwaitNewPage($(".colophon a.colophon-sign-in"));
        assertTrue(WebDriverRunner.url().contains("/pressroom-ui/login.rol?return="), WebDriverRunner.url());
        $("input[name='return']").should(exist);
        openAuthorSignIn();
        $("#j_username").setValue(ADMIN_USERNAME);
        $("#j_password").setValue(ADMIN_PASSWORD);
        clickAndAwaitNewPage($("#login"));

        assertEquals(baseUrl() + page, WebDriverRunner.url(), "the sign-in must come back to the page");
        $("nav.site-bar .site-bar-who").shouldBe(visible);
        // Since 0.18.0 (Part E) the signed-in links sit in the "You" menu, a details disclosure.
        $("nav.site-bar details.you-menu summary").click();
        $("nav.site-bar .site-bar-account a[href$='/pressroom-ui/app/']").shouldBe(visible);
        $("nav.site-bar .site-bar-account a[href$='/pressroom-ui/logout.rol']").shouldHave(text("Sign out"));
        $("nav.site-bar a.g-signin").shouldNot(exist);
        $(".colophon a.colophon-sign-in").shouldNot(exist);
        logout();
    }
}
