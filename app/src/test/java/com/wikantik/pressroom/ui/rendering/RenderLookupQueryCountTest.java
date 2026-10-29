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

import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.jpa.QueryRecorder;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lookups a weblog page render repeats on every request, counted.
 *
 * <p>A profiled reader load found three that reach the database (or the
 * EntityManager) on every render to learn something that changes only when
 * an author saves: the weblog's stylesheet override (usually absent, and an
 * absent answer was never remembered), its published pages for the nav, and
 * each runtime property read. Once the render is warm none of them may run a
 * query; a cold render may.
 */
class RenderLookupQueryCountTest {

    private static final String HANDLE = "renderlookupblog";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        user = TestUtils.setupUser("renderlookupuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);

        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug("about");
        page.setTitle("About");
        page.setContent("About body");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setShowInNav(true);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * Before: each warm render of this home page sent two
     * {@code weblog_custom_template} selects (the absent stylesheet override)
     * and one {@code pressroom_weblogpage} select to the database, and ran twelve
     * {@code RuntimeConfigProperty} finds (served by EclipseLink's shared
     * cache, so CPU rather than SQL).
     */
    @Test
    void aWarmHomePageRenderLooksNothingUpAgain() throws Exception {
        render(); // cold: whatever is not yet known is read here
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(this::render);

        assertEquals(List.of(), QueryRecorder.on(queries, WeblogTemplate.class),
                "the stylesheet override (absent here) is read on every render: " + queries);
        assertEquals(List.of(), QueryRecorder.sqlOn(queries, "weblog_custom_template"),
                "the stylesheet override is selected on every render: " + queries);
        assertEquals(List.of(), QueryRecorder.sqlOn(queries, "pressroom_weblogpage"),
                "the nav pages are selected on every render: " + queries);
        assertTrue(QueryRecorder.on(queries, WeblogPage.class).stream()
                        .allMatch(q -> q.startsWith("ReadObjectQuery:")),
                "a remembered nav page is resolved by id, never by a query: " + queries);
        assertEquals(List.of(), QueryRecorder.on(queries, RuntimeConfigProperty.class),
                "runtime properties are looked up on every read: " + queries);
        assertEquals(List.of(), QueryRecorder.sqlOn(queries, "pressroom_properties"),
                "runtime properties are selected on every read: " + queries);
    }

    @Test
    void theWarmRenderStillShowsTheNavPage() throws Exception {
        render();
        TestUtils.endSession(false);

        String body = render();

        assertTrue(body.contains("/" + HANDLE + "/about\">About</a>"),
                "the nav link must come from the remembered page list:\n" + body);
    }

    // ---------------------------------------------------------------- helpers

    private String render() throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }
}
