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
package com.wikantik.pressroom.ui.rendering.servlets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search box in the header of a blog's home page, in each article theme:
 * one form, in the header, posting to the blog's own search, with the
 * placeholder that names the blog and the site. The footer copy of the form
 * moved up with it, but only on the home page: a post keeps its footer form.
 */
class BlogHomeSearchBoxRenderingTest {

    private static final String HANDLE = "searchboxblog";
    private static final String SITE_NAME = "Harbour & Hills";
    private static final Map<String, String> FOOTER_FORM_CLASS =
            Map.of("journal", "qj-search", "travel", "tg-search", "portfolio", "pf-search");

    private User user;
    private Weblog weblog;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("searchboxuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.setupWeblogEntry("box-post", weblog, user);
        setProperty("site.name", SITE_NAME);
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
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    @Test
    void blogHomeHasExactlyOneSearchFormAndItIsInTheHeader() throws Exception {
        for (String theme : FOOTER_FORM_CLASS.keySet()) {
            String body = render(theme, "/" + HANDLE);
            assertEquals(1, count(body, "<form"), theme + ": one search form on the home page:\n" + body);
            assertEquals(1, count(body, "<form class=\"site-search\" role=\"search\" method=\"get\""),
                    theme + ": the shared box:\n" + body);
            String header = between(body, "<header", "</header>");
            assertTrue(header.contains("<form class=\"site-search\""), theme + ": in the header:\n" + header);
            assertTrue(header.contains("action=\"/pressroom/" + HANDLE + "/search\""),
                    theme + ": posts to the blog's own search:\n" + header);
            assertTrue(header.contains("type=\"search\" name=\"q\" maxlength=\"255\" required"),
                    theme + ": the input's contract:\n" + header);
            assertTrue(header.contains("placeholder=\"Search " + weblog.getName()
                    + " and the rest of Harbour &amp; Hills\""), theme + ": the placeholder:\n" + header);
            assertFalse(between(body, "<footer", "</footer>").contains("<form"),
                    theme + ": the footer form is gone from the home page");
            assertFalse(body.contains(FOOTER_FORM_CLASS.get(theme) + "\""), theme + ": no old form class");
        }
    }

    /**
     * Characterisation: written after the change and passing at once, because
     * the post page's footer form was deliberately left alone; it pins that the
     * home page's move did not take it.
     */
    @Test
    void postPageStillHasItsFooterSearchForm() throws Exception {
        for (Map.Entry<String, String> theme : FOOTER_FORM_CLASS.entrySet()) {
            String body = render(theme.getKey(), "/" + HANDLE + "/entry/box-post");
            assertTrue(between(body, "<footer", "</footer>").contains("<form class=\"" + theme.getValue() + "\""),
                    theme.getKey() + ": the post's footer form:\n" + body);
            assertFalse(body.contains("<form class=\"site-search\""), theme.getKey() + ": no header box on a post");
        }
    }

    @Test
    void placeholderFallsBackWhenSiteNameIsEmpty() throws Exception {
        setProperty("site.name", "");
        for (String theme : List.of("journal", "travel", "portfolio")) {
            String body = render(theme, "/" + HANDLE);
            assertTrue(body.contains("placeholder=\"Search " + weblog.getName() + "\""),
                    theme + ": just the blog:\n" + body);
        }
    }

    // ---------------------------------------------------------------- helpers

    private String render(String theme, String pathInfo) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
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

    private static String between(String body, String from, String to) {
        int start = body.indexOf(from);
        assertTrue(start >= 0, "no " + from + ":\n" + body);
        return body.substring(start, body.indexOf(to, start));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
