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

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.DispatcherType;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCache;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCaches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Site pages through the page servlet. {@code /<slug>} and {@code /} reach it
 * only by a container forward (the mapper's, {@code index.jsp}'s), so these
 * requests are FORWARD-dispatched; the one that is not is the crafted direct
 * request to the servlet's own path, which must not serve the site space
 * (spec AC7: not reachable by its internal handle). Context {@code /roller}.
 */
class SitePageRoutingTest {

    private User admin;
    private Weblog site;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        admin = TestUtils.setupUser("sitepageadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        savePage("sitepageabout", "About the Site", "Body of **about**", WeblogPage.PubStatus.PUBLISHED);
        savePage("sitepagedraft", "Not Yet", "Unfinished", WeblogPage.PubStatus.DRAFT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!originalProperties.isEmpty()) {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            originalProperties.forEach((name, value) -> config.get(name).setValue(value));
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
        WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog current = weblogs.getSiteSpace();
        if (current != null) {
            weblogs.removeWeblog(current);
        }
        TestUtils.endSession(true);
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private void savePage(String slug, String title, String content, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = TestUtils.weblogger().getWeblogPageManager()
                .getPageBySlug(TestUtils.getManagedWebsite(site), slug);
        if (page == null) {
            page = new WeblogPage();
            page.setWeblog(TestUtils.getManagedWebsite(site));
            page.setSlug(slug);
        }
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(status);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void setProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent(name, key -> config.get(key).getValue());
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    /** What the mapper's forward of {@code /<slug>} reaches. */
    private static MockHttpServletRequest forwarded(String pathInfo) {
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet("/roller-ui/rendering/page", pathInfo);
        request.setDispatcherType(DispatcherType.FORWARD);
        return request;
    }

    private static MockHttpServletResponse render(MockHttpServletRequest request) throws Exception {
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    @Test
    void aPublishedSitePageRendersInTheFrontpageTheme() throws Exception {
        MockHttpServletResponse response = render(forwarded("/site-space/sitepageabout"));
        String body = response.getContentAsString();

        assertEquals(200, response.getStatus(), body);
        assertTrue(body.contains("About the Site"), body);
        assertTrue(body.contains("<strong>about</strong>"), "the page body, as markdown:\n" + body);
        assertTrue(body.contains("class=\"fd-page roller-content\""),
                "the site space renders in its frontpage theme:\n" + body);
    }

    @Test
    void aDraftSitePageIs404() throws Exception {
        assertEquals(404, render(forwarded("/site-space/sitepagedraft")).getStatus(),
                "an unpublished site page must not be readable");
    }

    @Test
    void anUnknownSitePageIs404() throws Exception {
        assertEquals(404, render(forwarded("/site-space/sitepagenosuch")).getStatus());
    }

    /** The internal handle is never a public address (spec AC7). */
    @Test
    void aDirectRequestToTheSiteSpaceIs404() throws Exception {
        MockHttpServletRequest direct = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/page", "/site-space/sitepageabout");
        assertEquals(DispatcherType.REQUEST, direct.getDispatcherType());

        MockHttpServletResponse response = render(direct);

        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString().contains("About the Site"));
    }

    /** An ordinary blog's page still answers a direct request, as before. */
    @Test
    void aDirectRequestToAnOrdinaryBlogIsStillServed() throws Exception {
        Weblog blog = TestUtils.setupWeblog("sitepagesibling", admin);
        TestUtils.endSession(true);
        try {
            MockHttpServletRequest direct = RenderingTestSupport.anonymousGet(
                    "/roller-ui/rendering/page", "/sitepagesibling");
            assertEquals(200, render(direct).getStatus());
        } finally {
            TestUtils.teardownWeblog(blog.getId());
            TestUtils.endSession(true);
        }
    }

    /**
     * Saving a site page expires its cached rendering (spec AC4): savePage
     * touches the site space's lastModified, the page cache's only expiry.
     */
    @Test
    void savingASitePageShowsTheNewContentOnTheNextRequest() throws Exception {
        MockHttpServletRequest first = forwarded("/site-space/sitepageabout");
        first.removeAttribute("skipCache");
        waitPastLastModified();
        assertTrue(render(first).getContentAsString().contains("Body of"));
        assertNotNull(cached(first), "the first render must be cached, or this proves nothing");

        savePage("sitepageabout", "About the Site", "Rewritten **entirely**", WeblogPage.PubStatus.PUBLISHED);
        waitPastLastModified();

        MockHttpServletRequest second = forwarded("/site-space/sitepageabout");
        second.removeAttribute("skipCache");
        String body = render(second).getContentAsString();
        assertTrue(body.contains("<strong>entirely</strong>"), body);
        assertFalse(body.contains("Body of"), body);
    }

    /**
     * {@code /} as index.jsp serves it: FrontDoor's path, forwarded. With the
     * front door set to a published site page it is that page (AC1); once
     * the page is unpublished it is the directory (AC12), never a 404.
     */
    @Test
    void theFrontDoorPageRendersAtTheRootAndFallsBackWhenUnpublished() throws Exception {
        setProperty("site.frontpage.page", "sitepageabout");

        String path = FrontDoor.forwardPath(TestUtils.weblogger());
        assertEquals("/roller-ui/rendering/page/site-space/sitepageabout", path);
        MockHttpServletResponse response = render(forwarded(path.substring("/roller-ui/rendering/page".length())));
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("About the Site"));

        savePage("sitepageabout", "About the Site", "Body of **about**", WeblogPage.PubStatus.DRAFT);
        assertEquals("/roller-ui/siteDirectory.rol", FrontDoor.forwardPath(TestUtils.weblogger()));
    }

    private static void waitPastLastModified() throws Exception {
        long stamped = TestUtils.weblogger().getWeblogManager().getSiteSpace()
                .getLastModified().getTime();
        TestUtils.endSession(true);
        while (System.currentTimeMillis() <= stamped) {
            Thread.onSpinWait();
        }
    }

    private static Object cached(MockHttpServletRequest request) throws Exception {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        WeblogPageRequest pageRequest = new WeblogPageRequest(TestUtils.weblogger(), request);
        Weblog current = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        return cache.get(cache.generateKey(pageRequest), cache.lastModified(current));
    }
}
