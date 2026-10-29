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

import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anonymous reader's view: weblog front page, permalink, search and feeds,
 * with no login. BrowserHealthExtension (via PressroomIT) fails these tests if the
 * public theme's CSS/JS sub-resources 404 or the console shows errors — the
 * exact class of breakage that motivated Stage 0.
 *
 * <p>Markers are content-specific per the Routes discipline: the seeded entry's
 * title/body can only appear if the Velocity pipeline actually rendered.
 */
class PublicSurfaceIT extends PressroomIT {

    @Test
    void frontPageShowsSeededEntry() {
        openPath("/" + WEBLOG_HANDLE + "/");
        $("h1.qj-site").shouldHave(text("IT Weblog"));
        $("body").shouldHave(text("IT Seeded Entry"));
    }

    @Test
    @ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
    void theSiteRootIsThePublicDirectoryWhileNoWeblogIsTheFrontPage() {
        // ColophonIT sets a front-page weblog under the GLOBAL_CONFIG write
        // lock and restores it; holding the read lock means no IT has one set. index.jsp used to forward / to the
        // admin-only setup page, so every reader got the sign-in form.
        openPath("/");
        $("#siteDirectory").shouldHave(text("IT Weblog"));
        $("#siteDirectory a[href$='/" + WEBLOG_HANDLE + "/']").shouldBe(visible);
    }

    @Test
    void permalinkShowsEntryBody() {
        openPath("/" + WEBLOG_HANDLE + "/entry/it-seeded-entry");
        $("body").shouldHave(text("IT Seeded Entry"));
        $("body").shouldHave(text("Seeded entry body for public rendering checks."));
    }

    @Test
    void searchPageRendersAnonymously() {
        openPath("/" + WEBLOG_HANDLE + "/search?q=zzznope");
        $("h1.qj-site").shouldHave(text("IT Weblog"));
    }

    @Test
    void atomFeedServesTheSeededEntry() {
        String body = getAnonymously(baseUrl() + "/" + WEBLOG_HANDLE + "/feed/entries/atom");
        assertTrue(body.contains("<feed"), "must serve an Atom document:\n" + body);
        assertTrue(body.contains("IT Seeded Entry"), "seeded entry must be in the feed");
    }

    // --- SEO surface (SeoController via the dispatcher's extra url mappings) ---

    @Test
    void robotsTxtAllowsCrawlingAndAdvertisesTheSitemapIndex() {
        String body = getAnonymously(baseUrl() + "/robots.txt");
        assertTrue(body.contains("User-agent: *"),
                "robots.txt must address all crawlers:\n" + body);
        assertTrue(body.contains("Sitemap: ") && body.contains("/sitemap.xml"),
                "robots.txt must advertise the sitemap index:\n" + body);
    }

    @Test
    void sitemapIndexListsThePerWeblogSitemap() {
        String body = getAnonymously(baseUrl() + "/sitemap.xml");
        assertTrue(body.contains("<sitemapindex"),
                "must serve a sitemap index document:\n" + body);
        assertTrue(body.contains("/sitemap-" + WEBLOG_HANDLE + ".xml"),
                "the seeded weblog's sitemap must be listed:\n" + body);
    }

    @Test
    void perWeblogSitemapListsTheSeededPermalink() {
        String body = getAnonymously(baseUrl() + "/sitemap-" + WEBLOG_HANDLE + ".xml");
        assertTrue(body.contains("<urlset"),
                "must serve a urlset document:\n" + body);
        assertTrue(body.contains("/entry/it-seeded-entry"),
                "the seeded entry's permalink must be listed:\n" + body);
    }
}
