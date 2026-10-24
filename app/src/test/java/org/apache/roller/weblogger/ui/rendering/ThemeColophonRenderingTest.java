/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
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
package org.apache.roller.weblogger.ui.rendering;

import java.util.ArrayList;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colophon through the real PageServlet, so {@code $config}, {@code $text}
 * and {@code $utils} are the ones a theme gets: the footer of each blog theme
 * ends with the colophon, after the existing footer content, and the page
 * carries no literal macro text (Velocity prints an unresolved call as is).
 */
class ThemeColophonRenderingTest {

    private static final String HANDLE = "colophonrenderblog";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("colophonrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void everyBlogThemeFootersEndWithTheColophon() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : List.of("journal", "portfolio", "travel")) {
            Weblog managed = TestUtils.getManagedWebsite(weblog);
            managed.setEditorTheme(theme);
            managed.setCopyright("Maiia & <b>");
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.endSession(true);
            RenderingTestSupport.clearRenderCaches();

            MockHttpServletRequest request = RenderingTestSupport
                    .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE);
            MockHttpServletResponse response = RenderingTestSupport
                    .execute(RenderingTestSupport.pageServlet(), request);
            assertEquals(200, response.getStatus());
            String body = response.getContentAsString();
            int footer = body.indexOf("<footer");
            int colophon = body.indexOf("<div class=\"colophon\">");
            if (footer < 0 || colophon < footer || body.indexOf("</footer>") < colophon
                    || !body.contains("Maiia &amp; &lt;b&gt;") || body.contains("&amp;amp;")
                    || body.contains("showColophon") || body.contains("$config")
                    || body.contains("$text") || body.contains("$colophon")) {
                failures.add(theme);
            }
            assertFalse(body.contains("Maiia & <b>"), theme);
        }
        assertTrue(failures.isEmpty(), "themes whose footer lacks a correct colophon: " + failures);
    }
}
