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
package com.wikantik.pressroom.business.analytics;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.FrontDoor;
import com.wikantik.pressroom.ui.rendering.StubAnalytics;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC 8: no page render reaches Umami. The REAL analytics manager, registered
 * through a fake service, is installed in the tier; the service is then told to
 * hang (every call waits up to 30 s) and a blog page and the site space's
 * front page (as index.jsp serves {@code /}) are rendered. Both must come back
 * in normal time and the service must have been asked nothing.
 */
class RenderingNeverCallsAnalyticsServiceTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final Duration NORMAL = Duration.ofSeconds(5);
    /** A render that waits on the hanging service is abandoned (its thread interrupted) after this. */
    private static final Duration HARD_LIMIT = Duration.ofSeconds(10);

    private AnalyticsTier tier;
    private User user;
    private Weblog blog;
    private Weblog site;
    private boolean createdSite;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        tier = new AnalyticsTier();
        user = TestUtils.setupUser("renderneverumami");
        blog = TestUtils.setupWeblog("renderneverumamiblog", user);
        TestUtils.endSession(true);
        site = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        if (site == null) {
            site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(user);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
            createdSite = true;
        }
        // register everything through the fake service, then count from zero
        tier.manager.sync();
        TestUtils.endSession(false);
        assertTrue(tier.manager.isRegistered(blog.getId()), "the blog is registered");
        assertTrue(tier.manager.isRegistered(site.getId()), "the site space is registered");
        tier.service.calls.set(0);
        tier.service.pings.set(0);
        tier.service.hang = new CountDownLatch(1);
        RenderingTestSupport.clearRenderCaches();
    }

    @AfterEach
    void tearDown() throws Exception {
        tier.tearDown();
        if (createdSite) {
            TestUtils.weblogger().getWeblogManager().removeWeblog(
                    TestUtils.weblogger().getWeblogManager().getSiteSpace());
            TestUtils.endSession(true);
        }
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    @Test
    void aBlogPageRendersWithoutAskingTheServiceEvenWhileItHangs() throws Exception {
        try (StubAnalytics _ = StubAnalytics.installing(tier.manager)) {
            long start = System.nanoTime();
            MockHttpServletResponse response = assertTimeoutPreemptively(HARD_LIMIT,
                    () -> RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                            RenderingTestSupport.anonymousGet(PAGE_SERVLET, "/" + blog.getHandle())),
                    "the blog page render waited on the hanging Umami service");
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("data-website-id=\"" + blog.getId() + "\""),
                    "the registered blog still carries its tracker");
            assertTrue(took.compareTo(NORMAL) < 0, "rendered in " + took + " with Umami hanging");
        }
        assertEquals(0, tier.service.calls.get(), "no service call during the render");
        assertEquals(0, tier.service.pings.get());
    }

    @Test
    void theSiteFrontPageRendersWithoutAskingTheServiceEvenWhileItHangs() throws Exception {
        try (StubAnalytics _ = StubAnalytics.installing(tier.manager)) {
            String path = FrontDoor.forwardPath(TestUtils.weblogger());
            assertEquals(PAGE_SERVLET + "/site-space/page/discovery", path, "/ is the site space's front page");
            MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET,
                    path.substring(PAGE_SERVLET.length()));
            request.setDispatcherType(DispatcherType.FORWARD);

            long start = System.nanoTime();
            MockHttpServletResponse response = assertTimeoutPreemptively(HARD_LIMIT,
                    () -> RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request),
                    "the front page render waited on the hanging Umami service");
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            TestUtils.endSession(true);

            assertEquals(200, response.getStatus(), response.getContentAsString());
            assertTrue(response.getContentAsString().contains("data-website-id=\"" + site.getId() + "\""),
                    "the front page carries the site space's tracker:\n" + response.getContentAsString());
            assertTrue(took.compareTo(NORMAL) < 0, "rendered in " + took + " with Umami hanging");
        }
        assertEquals(0, tier.service.calls.get(), "no service call during the render");
        assertEquals(0, tier.service.pings.get());
    }
}
