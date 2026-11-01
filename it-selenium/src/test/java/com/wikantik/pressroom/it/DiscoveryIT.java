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

import java.util.List;
import java.util.Map;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.WebDriverRunner;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.chromium.HasCdp;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front-page discovery work in a real browser: the discovery page at
 * {@code /}, the "more from the site" block on a blog page and its link home,
 * and "places you've been", which lives only in the reader's own
 * {@code localStorage}.
 *
 * <p><b>Written after the behaviour, characterisation-style.</b> Tasks 7 and 8
 * built the page and the block under unit tests; this class is browser
 * coverage added afterwards, so each test was expected to pass the first time
 * it ran. To show that none passes vacuously, each was run once against a
 * build whose marker was deliberately broken (the discovery section class, the
 * block's home link, the places recording element, the storage guard) and
 * failed; the markers were then restored.
 *
 * <p>Holds {@link PressroomIT#GLOBAL_CONFIG} for READ: {@code /} is the
 * discovery page only while no site page and no blog is the front door, and
 * the classes that set one (ColophonIT, SitePagesIT) hold it for WRITE.
 * Umami is not configured in the IT stack, so no assertion depends on a
 * popularity score, and none depends on which posts a given snapshot picked:
 * the seeded weblog is asserted through the all-blogs list, which is every
 * visible blog. Each test creates its own weblogs and clears the places key
 * before it starts.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class DiscoveryIT extends PressroomIT {

    private static final String PLACES_KEY = "pressroom.places";

    @BeforeEach
    void startWithNoRememberedPlaces() {
        // Navigate first: about:blank has no origin and storage calls throw.
        // The browser (and so the origin's storage) is shared with other
        // classes on the same thread.
        openPath("/");
        executeJavaScript("try { localStorage.removeItem('" + PLACES_KEY + "'); } catch (e) { }");
    }

    @Test
    void frontPageShowsSections() {
        openPath("/");

        $(".fd-discovery-headline").should(exist);
        $(".fd-discovery-headline a").shouldBe(visible);
        $(".fd-discovery-all").should(exist);
        $(".fd-discovery-all a[href$='/" + WEBLOG_HANDLE + "/']").shouldHave(text("IT Weblog"));
    }

    @Test
    void blogPageLinksHome() {
        loginAsAdmin();
        String handle = createWeblog("Disc Home " + nonce());
        logout();

        openPath("/" + handle + "/");
        $(".more-from-site").should(exist);
        $(".more-from-site-posts a").shouldBe(visible);
        $(".more-from-site-home a").shouldBe(visible).click();

        $(".fd-discovery-all").should(exist);
        assertEquals(baseUrl() + "/", WebDriverRunner.url(), "the home link must return to the site root");
    }

    @Test
    void placesRemembersVisitedBlogs() {
        String suffix = nonce();
        String nameA = "Disc First " + suffix;
        String nameB = "Disc Second " + suffix;
        loginAsAdmin();
        String handleA = createWeblog(nameA);
        String handleB = createWeblog(nameB);
        logout();

        openPath("/" + handleA + "/");
        openPath("/" + handleB + "/");
        openPath("/");

        $("nav.places-been").shouldBe(visible);
        List<String> shown = $$("nav.places-been li").texts();
        assertEquals(List.of(nameB, nameA), shown.subList(0, 2),
                "both visits must be listed, the later one first, got: " + shown);
        $("nav.places-been a[href$='/" + handleA + "/']").should(exist);
        $("nav.places-been a[href$='/" + handleB + "/']").should(exist);
    }

    @Test
    void aTamperedPlaceWithAScriptUrlNeverBecomesALink() {
        // Storage is the reader's to edit; an entry whose url is not http(s)
        // must be dropped, never rendered as an href.
        openPath("/");
        executeJavaScript("localStorage.setItem('" + PLACES_KEY + "', JSON.stringify("
                + "[{handle: 'evil', name: 'Evil Place', url: 'javascript:alert(1)'}]))");
        openPath("/");

        $(".fd-discovery-all").should(exist);
        assertTrue($$("a[href^='javascript:']").isEmpty(), "no javascript: anchor may render");
        $("nav.places-been").shouldNotBe(visible);
    }

    @Test
    void pageWorksWithStorageBlocked() {
        // BrowserHealthExtension fails the test on any console error or
        // failed sub-resource, so reaching the assertions is half the proof.
        String script = "Object.defineProperty(window, 'localStorage', "
                + "{ get: function () { throw new DOMException('blocked', 'SecurityError'); } });";
        HasCdp driver = (HasCdp) WebDriverRunner.getWebDriver();
        Object id = driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument",
                Map.of("source", script)).get("identifier");
        try {
            openPath("/");
            $(".fd-discovery-all").should(exist);
            $("nav.places-been").should(exist).shouldHave(attribute("hidden"));
            assertTrue($$("nav.places-been li").isEmpty(), "with storage blocked nothing may be listed");
        } finally {
            driver.executeCdpCommand("Page.removeScriptToEvaluateOnNewDocument", Map.of("identifier", id));
            Selenide.refresh();
        }
    }

    private String createWeblog(String name) {
        String handle = "disc" + nonce();
        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue(name);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();
        $("#messages").should(exist);
        return handle;
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
