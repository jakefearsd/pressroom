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
package com.wikantik.pressroom.ui.rendering.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * A blog's pinned posts lead page one of its home listing, each marked
 * "Pinned", in the three article themes; the feed keeps its date order.
 * Rendered through the real servlets, because Velocity prints a wrong
 * reference as text instead of failing.
 */
class PinnedRowsRenderingTest {

    private static final long DAY = 86_400_000L;
    private static final String MARK = "class=\"entry-pinned\">Pinned<";

    private String handle;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "pinnedrows" + suffix;
        user = TestUtils.setupUser("pinnedrowsuser" + suffix);
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void post(String anchor, String title, int daysAgo, boolean pinned) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user));
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPubTime(new Timestamp(System.currentTimeMillis() - DAY * daysAgo));
        managed.setTitle(title);
        managed.setText("Body of " + title + ".");
        managed.setPinned(pinned);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private void theme(String theme) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setLocale("en_US");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String home() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + handle);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        for (String leak : new String[] {"$pager", "$text.", "entryMeta.", "$pinned", "#set("}) {
            assertFalse(body.contains(leak), "'" + leak + "' leaked into the page:\n" + body);
        }
        return body;
    }

    @ParameterizedTest
    @ValueSource(strings = {"journal", "portfolio", "travel"})
    void theHomeShowsAPinnedPostFirstWithItsMark(String theme) throws Exception {
        post("old-pinned", "PINNEDTITLE", 30, true);
        post("fresh-one", "FRESHTITLE", 1, false);
        post("middle-one", "MIDDLETITLE", 5, false);
        theme(theme);

        String body = home();

        int pinned = body.indexOf("PINNEDTITLE");
        assertTrue(pinned > 0, "the pinned post is listed");
        assertTrue(pinned < body.indexOf("FRESHTITLE"), "pinned before the newest post");
        assertTrue(body.indexOf("FRESHTITLE") < body.indexOf("MIDDLETITLE"));
        assertEquals(1, body.split(MARK, -1).length - 1, "the mark appears once, on the pinned post");
        assertEquals(1, body.split(">PINNEDTITLE<", -1).length - 1, "the pinned post is listed once");
    }

    @Test
    void theFeedListsAPinnedPostInItsDatePlace() throws Exception {
        post("old-pinned", "PINNEDTITLE", 30, true);
        post("fresh-one", "FRESHTITLE", 1, false);
        theme("journal");

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/feed", "/" + handle + "/entries/atom");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);
        String body = response.getContentAsString();

        assertTrue(body.indexOf("FRESHTITLE") > 0 && body.indexOf("FRESHTITLE") < body.indexOf("PINNEDTITLE"),
                "the feed stays newest first:\n" + body);
        assertFalse(body.contains("Pinned"), "the feed carries no pin mark");
    }
}
