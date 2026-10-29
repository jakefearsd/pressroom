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
package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogRedirect;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The four consultation seams, end to end against the real tier: a rule
 * answers exactly the requests that would otherwise 404 -- and, by
 * construction, nothing that is being served for real. The expected Location
 * is DERIVED from the strategy in every assertion, never hardcoded: the
 * vhost wave's context-path bug survived precisely because a test had baked
 * the buggy url shape in as its expectation.
 */
class RedirectServingTest {

    private User user;
    private Weblog weblog;
    private WeblogRequestMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("redirserveuser");
        weblog = TestUtils.setupWeblog("redirserveblog", user);
        TestUtils.endSession(true);

        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug("about");
        page.setTitle("About Us");
        page.setContent("The **about** page");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Weblogger tier = TestUtils.weblogger();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier);
        mapper = new WeblogRequestMapper(provider, tier);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private WeblogRedirectManager redirects() {
        return TestUtils.weblogger().getWeblogRedirectManager();
    }

    private WeblogRedirect saveRule(String source, String target) throws Exception {
        WeblogRedirect rule = new WeblogRedirect();
        rule.setWeblog(TestUtils.getManagedWebsite(weblog));
        rule.setSourcePath(source);
        rule.setTargetPath(target);
        rule.setOrigin(WeblogRedirect.Origin.MANUAL);
        redirects().saveRedirect(rule);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return rule;
    }

    /** What every Location must equal: the strategy's root plus the target. */
    private String derivedLocation(String targetPath) throws Exception {
        String root = TestUtils.weblogger().getUrlStrategy()
                .getWeblogURL(TestUtils.getManagedWebsite(weblog), null, true);
        assertNotNull(root);
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root + targetPath;
    }

    private MockHttpServletResponse pageServletGet(String pathAfterHandle) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/redirserveblog" + pathAfterHandle);
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    private MockHttpServletRequest mapperGet(String uriAfterContext) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/roller" + uriAfterContext);
        request.setContextPath("/roller");
        return request;
    }

    // ------------------------------------------------- PageServlet seams

    @Test
    void anUnknownSlugWithARuleIs301NotFound404Otherwise() throws Exception {
        assertEquals(404, pageServletGet("/old-about").getStatus(),
                "no rule yet: the 404 this feature is scoped to");

        saveRule("/old-about", "/about");

        MockHttpServletResponse response = pageServletGet("/old-about");
        assertEquals(301, response.getStatus());
        assertEquals(derivedLocation("/about"), response.getHeader("Location"));
    }

    @Test
    void anUnknownEntryAnchorWithARuleIs301() throws Exception {
        saveRule("/entry/renamed-post", "/about");

        MockHttpServletResponse response = pageServletGet("/entry/renamed-post");

        assertEquals(301, response.getStatus(),
                "the rejectionReason seam: a permalink that no entry answers");
        assertEquals(derivedLocation("/about"), response.getHeader("Location"));
    }

    /**
     * THE design property. A rule whose source names live content simply
     * never fires, because the lookup only runs where a 404 was already
     * decided -- shadowing is impossible by construction, not by validation.
     */
    @Test
    void aRuleCanNeverShadowLiveContent() throws Exception {
        saveRule("/about", "/never-see-this");

        MockHttpServletResponse response = pageServletGet("/about");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("About Us"));
    }

    @Test
    void aServedRedirectAdvancesItsHitCount() throws Exception {
        WeblogRedirect rule = saveRule("/counted-page", "/about");

        assertEquals(301, pageServletGet("/counted-page").getStatus());
        TestUtils.endSession(true);

        WeblogRedirect after = redirects().getRedirect(rule.getId());
        assertEquals(1L, after.getHitCount());
        assertNotNull(after.getLastHitAt());
    }

    // ---------------------------------------------- WeblogRequestMapper seams

    @Test
    void aMigratedMultiSegmentPathWithARuleIs301AtTheMapper() throws Exception {
        saveRule("/2019/05/old-post.html", "/about");
        MockHttpServletRequest request = mapperGet("/redirserveblog/2019/05/old-post.html");
        request.setQueryString("utm_source=feed");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response),
                "the mapper must answer rather than decline");
        assertEquals(301, response.getStatus());
        assertEquals(derivedLocation("/about") + "?utm_source=feed",
                response.getHeader("Location"));
    }

    @Test
    void aMigratedPathWithoutARuleStillDeclines() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(
                mapperGet("/redirserveblog/2019/05/unmapped.html"), response),
                "no rule: the request declines down the chain exactly as before");
    }

    @Test
    void aTrailingSlashContext404WithARuleIs301AtTheMapper() throws Exception {
        saveRule("/entry/old-post", "/about");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(
                mapperGet("/redirserveblog/entry/old-post/"), response));
        assertEquals(301, response.getStatus());
        assertEquals(derivedLocation("/about"), response.getHeader("Location"));
    }

    @Test
    void aTrailingSlashContext404WithoutARuleStays404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(
                mapperGet("/redirserveblog/entry/unmapped/"), response));
        assertEquals(404, response.getStatus());
    }

    @Test
    void aMalformedTagsShape404WithARuleIs301AtTheMapper() throws Exception {
        saveRule("/tags/old+tags", "/about");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(
                mapperGet("/redirserveblog/tags/old+tags/"), response),
                "a tags query with a trailing slash is the tags-shape 404 seam");
        assertEquals(301, response.getStatus());
        assertEquals(derivedLocation("/about"), response.getHeader("Location"));
    }

    /**
     * The consultation is a favor on top of a settled outcome: when the
     * store cannot be asked (or the weblog row vanishes between the handle
     * check and the lookup), the mapper degrades to the decline it had
     * already decided -- never a 500, never a redirect.
     */
    @Test
    void aFailingRuleLookupDegradesToTheDecline() throws Exception {
        com.wikantik.pressroom.business.MockWeblogger mock =
                com.wikantik.pressroom.business.MockWeblogger.create();
        WebloggerProvider mockProvider = mock(WebloggerProvider.class);
        when(mockProvider.isBootstrapped()).thenReturn(true);
        when(mockProvider.getWeblogger()).thenReturn(mock.weblogger());
        Weblog mockedWeblog = new Weblog();
        mockedWeblog.setHandle("mockblog");
        // First call answers isWeblog; the second is the consultation's own
        // lookup, which fails.
        when(mock.weblogManager().getWeblogByHandle("mockblog"))
                .thenReturn(mockedWeblog)
                .thenThrow(new com.wikantik.pressroom.WebloggerException("store down"));
        WeblogRequestMapper mockedMapper =
                new WeblogRequestMapper(mockProvider, mock.weblogger());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mockedMapper.handleRequest(
                mapperGet("/mockblog/2019/05/old.html"), response),
                "the decline proceeds unchanged when the rules cannot be consulted");
    }

    @Test
    void aVanishedWeblogRowDegradesToTheDecline() throws Exception {
        com.wikantik.pressroom.business.MockWeblogger mock =
                com.wikantik.pressroom.business.MockWeblogger.create();
        WebloggerProvider mockProvider = mock(WebloggerProvider.class);
        when(mockProvider.isBootstrapped()).thenReturn(true);
        when(mockProvider.getWeblogger()).thenReturn(mock.weblogger());
        Weblog mockedWeblog = new Weblog();
        mockedWeblog.setHandle("mockblog");
        when(mock.weblogManager().getWeblogByHandle("mockblog"))
                .thenReturn(mockedWeblog)
                .thenReturn(null);
        WeblogRequestMapper mockedMapper =
                new WeblogRequestMapper(mockProvider, mock.weblogger());

        assertFalse(mockedMapper.handleRequest(
                mapperGet("/mockblog/2019/05/old.html"), new MockHttpServletResponse()));
    }
}
