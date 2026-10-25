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

import java.lang.reflect.Field;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.config.RuntimeConfigAttachment;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The site space's pages in the sitemap: {@code /sitemap-site-pages.xml} lists
 * them at the one rule's addresses (the front door at the root), the index
 * points at it only when there is something to list, and the hidden handle
 * never appears in either file nor answers through {@code /sitemap-{handle}.xml}.
 */
class SeoControllerSitePagesTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/roller";

    private SeoController controller;
    private User admin;
    private Weblog ordinary;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        admin = TestUtils.setupUser("seoSiteAdmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        ordinary = TestUtils.setupWeblog("seositeblog", admin);
        TestUtils.endSession(true);
        controller = new SeoController();
        inject(controller, "weblogger", TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog site = mgr.getSiteSpace();
        if (site != null) {
            mgr.removeWeblog(site);
        }
        TestUtils.teardownWeblog(ordinary.getId());
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
    }

    private Weblog site() throws Exception {
        Weblog site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return TestUtils.weblogger().getWeblogManager().getSiteSpace();
    }

    private void savePage(String slug, WeblogPage.PubStatus status, boolean noindex) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(site());
        page.setSlug(slug);
        page.setTitle("Title " + slug);
        page.setContent("Body " + slug);
        page.setStatus(status);
        page.setNoindex(noindex);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private String index() {
        return controller.sitemapIndex(new MockHttpServletRequest()).getBody();
    }

    private ResponseEntity<String> sitePages() {
        return controller.siteSitemap(new MockHttpServletRequest());
    }

    @Test
    void theIndexListsTheSitePagesSitemapOnlyWhenASitePageIsPublished() throws Exception {
        assertFalse(index().contains("sitemap-site-pages.xml"), "no site space yet");
        savePage("draft-only", WeblogPage.PubStatus.DRAFT, false);
        assertFalse(index().contains("sitemap-site-pages.xml"), "a draft is nothing to list");
        savePage("about", WeblogPage.PubStatus.PUBLISHED, false);
        String body = index();
        assertTrue(body.contains("<loc>" + ABSOLUTE_CONTEXT + "/sitemap-site-pages.xml</loc>"), body);
        assertTrue(body.contains("/sitemap-" + ordinary.getHandle() + ".xml"), body);
        assertFalse(body.contains("site-space"), body);
    }

    @Test
    void theSitePagesSitemapListsPublishedIndexablePagesAndTheFrontDoorAtTheRoot() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, false);
        savePage("home", WeblogPage.PubStatus.PUBLISHED, false);
        savePage("secret", WeblogPage.PubStatus.DRAFT, false);
        savePage("hidden", WeblogPage.PubStatus.PUBLISHED, true);
        savePage("my page", WeblogPage.PubStatus.PUBLISHED, false);

        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.frontpage.page", "home")) {
            ResponseEntity<String> response = sitePages();
            assertEquals(200, response.getStatusCode().value());
            String body = response.getBody();
            assertNotNull(body);
            assertTrue(body.contains("<loc>" + ABSOLUTE_CONTEXT + "/about</loc>"), body);
            assertTrue(body.contains("<loc>" + ABSOLUTE_CONTEXT + "/my+page</loc>"), body);
            assertTrue(body.contains("<loc>" + ABSOLUTE_CONTEXT + "/</loc>"), body);
            assertEquals(1, body.split("<loc>" + ABSOLUTE_CONTEXT + "/</loc>", -1).length - 1,
                    "the front door is listed once");
            assertFalse(body.contains("/home</loc>"), "the front door has no second address:\n" + body);
            assertFalse(body.contains("secret"), body);
            assertFalse(body.contains("hidden"), body);
            assertFalse(body.contains("site-space"), body);
        }
    }

    @Test
    void theSitePagesSitemapIs404WithoutASiteSpaceOrAPublishedPage() throws Exception {
        assertEquals(404, sitePages().getStatusCode().value());
        savePage("draft-only", WeblogPage.PubStatus.DRAFT, false);
        assertEquals(404, sitePages().getStatusCode().value());
    }

    @Test
    void theHiddenHandleIsNeverReachableThroughThePerWeblogSitemap() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, false);
        assertEquals(404, controller.weblogSitemap(Weblog.SITE_SPACE_HANDLE,
                new MockHttpServletRequest()).getStatusCode().value());
    }

    @Test
    void aBlogsPagesComeFromTheOneUrlRule() throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(ordinary));
        page.setSlug("about");
        page.setTitle("About");
        page.setContent("x");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        String body = controller.weblogSitemap(ordinary.getHandle(), new MockHttpServletRequest()).getBody();
        assertNotNull(body);
        assertTrue(body.contains("<loc>" + ABSOLUTE_CONTEXT + "/" + ordinary.getHandle() + "/about</loc>"), body);
    }

    /**
     * CHARACTERISATION (AC11): written after the implementation and expected to
     * pass at once, because the custom-domain branch of the index returns the
     * blog's own sitemap before any site-page code runs. It pins that a blog's
     * own domain never advertises or lists the site's pages.
     */
    @Test
    void onACustomDomainTheIndexIsTheBlogsOwnSitemapWithNoSitePages() throws Exception {
        savePage("about", WeblogPage.PubStatus.PUBLISHED, false);
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog managed = TestUtils.getManagedWebsite(ordinary);
        managed.setCustomDomain("siteblog.example.com");
        mgr.saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sitemap.xml");
            request.addHeader("Host", "siteblog.example.com");
            String body = controller.sitemapIndex(request).getBody();
            assertNotNull(body);
            assertTrue(body.contains("<urlset"), "the blog's own sitemap, not an index:\n" + body);
            assertFalse(body.contains("sitemap-site-pages.xml"), body);
            assertFalse(body.contains("<sitemapindex"), body);
            assertFalse(body.contains("site-space"), body);
            assertFalse(body.contains("/about</loc>"), "no site page urls:\n" + body);
        } finally {
            TestUtils.weblogger().getVirtualHostRegistry().invalidate();
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + target.getClass());
    }
}
