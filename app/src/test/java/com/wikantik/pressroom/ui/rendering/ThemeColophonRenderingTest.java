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
package com.wikantik.pressroom.ui.rendering;

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

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
    private User reader;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("colophonrenderuser");
        reader = TestUtils.setupUser("colophonrenderreader");
        TestUtils.weblogger().getUserManager().revokeRole("editor", reader);
        TestUtils.weblogger().getUserManager().grantRole("reader", reader);
        TestUtils.weblogger().flush();
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.teardownUser(reader.getUserName());
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
                    .anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE);
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

    /**
     * With a blog as the site's front page, the site directory and its Sign
     * in / Main menu links are no longer one click away, and no blog theme
     * had a way into the admin screens of its own. The colophon carries one
     * on every reader page, with the same words and rules as the frontpage
     * theme's top bar: Sign in for a visitor, Main menu and Sign out for an
     * author, Sign out only for a reader (the menu would refuse them).
     */
    @Test
    void everyBlogThemeColophonOffersTheWayIn() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : List.of("journal", "portfolio", "travel", "frontpage")) {
            Weblog managed = TestUtils.getManagedWebsite(weblog);
            managed.setEditorTheme(theme);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.endSession(true);

            String anonymous = colophon(null);
            if (!anonymous.contains("/pressroom-ui/login") || anonymous.contains("menu.rol")
                    || anonymous.contains("logout")) {
                failures.add(theme + " (visitor): " + anonymous);
            }
            String author = colophon(user.getUserName());
            if (author.contains("/pressroom-ui/login") || !author.contains("/pressroom-ui/menu.rol")
                    || !author.contains("logout")) {
                failures.add(theme + " (author): " + author);
            }
            String signedInReader = colophon(reader.getUserName());
            if (signedInReader.contains("/pressroom-ui/login") || signedInReader.contains("menu.rol")
                    || !signedInReader.contains("logout")) {
                failures.add(theme + " (reader): " + signedInReader);
            }
        }
        assertTrue(failures.isEmpty(), "colophons without the right way in: " + failures);
    }

    /** The colophon's markup on the blog's home page, as {@code userName} (null: a visitor). */
    private static String colophon(String userName) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE);
        if (userName != null) {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(userName, "n/a",
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
        }
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        int start = body.indexOf("<div class=\"colophon\">");
        assertTrue(start >= 0, "no colophon:\n" + body);
        return body.substring(start, body.indexOf("</div>", start));
    }
}
