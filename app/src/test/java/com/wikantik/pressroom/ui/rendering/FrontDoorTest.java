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
package com.wikantik.pressroom.ui.rendering;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code /} shows: the site page named by {@code site.frontpage.page}
 * when it is published, the site directory when that page is set but cannot
 * be shown, and otherwise exactly what {@code index.jsp} did before (a
 * front-page blog, else the directory). {@code /} is never a 404 and never
 * a 500: a lookup that throws is the directory too.
 */
class FrontDoorTest {

    private static final String DIRECTORY = "/roller-ui/siteDirectory.rol";

    private Weblogger tier;
    private WeblogManager weblogs;
    private WeblogPageManager pages;
    private Weblog site;

    @BeforeEach
    void setUp() throws WebloggerException {
        tier = mock(Weblogger.class);
        weblogs = mock(WeblogManager.class);
        pages = mock(WeblogPageManager.class);
        when(tier.getWeblogManager()).thenReturn(weblogs);
        when(tier.getWeblogPageManager()).thenReturn(pages);
        site = new Weblog(Weblog.SITE_SPACE_HANDLE, "admin", "Site", "",
                "a@example.com", "frontpage", "en_US", "UTC");
        site.setSiteSpace(true);
        when(weblogs.getSiteSpace()).thenReturn(site);
    }

    private static WeblogPage page(String slug, WeblogPage.PubStatus status) {
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        page.setStatus(status);
        return page;
    }

    private void published(WeblogPage... published) throws WebloggerException {
        when(pages.getPublishedPages(site)).thenReturn(List.of(published));
    }

    @Test
    void aPublishedFrontDoorPageIsForwardedToThroughTheSiteSpace() throws Exception {
        published(page("about", WeblogPage.PubStatus.PUBLISHED),
                page("home", WeblogPage.PubStatus.PUBLISHED));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering(
                "site.frontpage.page", "home", "site.frontpage.weblog.handle", "blog")) {
            assertEquals("/roller-ui/rendering/page/site-space/home", FrontDoor.forwardPath(tier),
                    "a site page, when set, wins over the front-page blog");
        }
    }

    @Test
    void theFrontDoorSlugIsEncodedForTheForward() throws Exception {
        published(page("our story", WeblogPage.PubStatus.PUBLISHED));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering(
                "site.frontpage.page", "our story")) {
            assertEquals("/roller-ui/rendering/page/site-space/our+story", FrontDoor.forwardPath(tier),
                    "the page servlet decodes the slug, as it does $url.staticPage's");
        }
    }

    @Test
    void aFrontDoorPageThatIsNotPublishedIsTheDirectory() throws Exception {
        // a draft is not among the published pages; the blog is not a fallback
        published(page("about", WeblogPage.PubStatus.PUBLISHED));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering(
                "site.frontpage.page", "home", "site.frontpage.weblog.handle", "blog")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
    }

    @Test
    void aLocaleShapedFrontDoorSlugIsTheDirectory() throws Exception {
        // Saved before locale-shaped slugs were refused: the page servlet would
        // read "hi" as a locale and 404, and / is never a 404.
        published(page("hi", WeblogPage.PubStatus.PUBLISHED));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "hi")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
        published(page("en_US", WeblogPage.PubStatus.PUBLISHED));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "en_US")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
    }

    @Test
    void aFrontDoorPageWithNoSiteSpaceIsTheDirectory() throws Exception {
        when(weblogs.getSiteSpace()).thenReturn(null);
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
    }

    @Test
    void aLookupThatThrowsIsTheDirectoryNeverAnException() throws Exception {
        when(pages.getPublishedPages(site)).thenThrow(new WebloggerException("database down"));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
        when(tier.getWeblogManager()).thenThrow(new IllegalStateException("tier gone"));
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
    }

    @Test
    void aFrontDoorPageWithNoTierIsTheDirectory() {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath((Weblogger) null));
        }
    }

    @Test
    void noFrontDoorPageAndAFrontPageBlogIsThatBlogUnchanged() throws Exception {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering(
                "site.frontpage.page", "  ", "site.frontpage.weblog.handle", "wikantik")) {
            assertEquals("/roller-ui/rendering/page/wikantik", FrontDoor.forwardPath(tier));
        }
        verify(tier, never()).getWeblogPageManager();
    }

    @Test
    void neitherSetIsTheDirectory() {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering(
                "site.frontpage.page", "", "site.frontpage.weblog.handle", " ")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier));
        }
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering()) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(tier), "both unset");
        }
    }

    @Test
    void beforeBootstrapTheProviderIsNeverAskedForTheTier() {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(false);
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            assertEquals(DIRECTORY, FrontDoor.forwardPath(provider));
        }
        verify(provider, never()).getWeblogger();
    }

    @Test
    void aBootstrappedProviderAnswersThroughItsTier() throws Exception {
        published(page("home", WeblogPage.PubStatus.PUBLISHED));
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier);
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            assertEquals("/roller-ui/rendering/page/site-space/home", FrontDoor.forwardPath(provider));
        }
    }
}
