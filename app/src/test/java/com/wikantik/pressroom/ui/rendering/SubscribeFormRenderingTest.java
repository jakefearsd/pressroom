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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code #showSubscribeForm}, called unconditionally from every bundled
 * theme's footer: it renders the {@code subscribe-form-slot} placeholder
 * (plus the {@code #showAudienceAssets} injector marker) only when the
 * weblog has a newsletter list configured, and renders nothing at all
 * otherwise. Modeled on {@code PageNavRenderingTest}'s theme loop.
 */
class SubscribeFormRenderingTest {

    private static final List<String> THEMES =
            List.of("journal", "portfolio", "travel");

    private static final String HANDLE = "subscribeformrenderblog";
    private static final String LIST_UUID = "2f0f1b0c-1111-2222-3333-444455556666";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("subscribeformrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private void setListUuid(String uuid) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setNewsletterListUuid(uuid);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private String renderHomePage() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "home page must render");
        return response.getContentAsString();
    }

    @Test
    void everyBundledThemeShowsTheSlotWhenAListUuidIsConfigured() throws Exception {
        setListUuid(LIST_UUID);

        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                RenderingTestSupport.clearRenderCaches();
                switchTheme(theme);
                String body = renderHomePage();

                assertTrue(body.contains("class=\"subscribe-form-slot\""),
                        theme + ": expected the subscribe-form-slot placeholder:\n" + body);
                assertTrue(body.contains("data-list-uuid=\"" + LIST_UUID + "\""),
                        theme + ": expected the exact stored uuid as the attribute value:\n"
                                + body);
                assertTrue(body.contains("data-endpoint=\"/roller/newsletter/subscribe\""),
                        theme + ": expected the server-built, context-path-aware endpoint "
                                + "(never a client-guessed absolute-root path):\n" + body);
                assertTrue(body.contains("audience-hp"),
                        theme + ": expected #showAudienceAssets' injector script:\n" + body);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }

        assertTrue(failures.isEmpty(),
                "themes failed the subscribe-slot-present check:\n  "
                        + String.join("\n  ", failures));
    }

    @Test
    void everyBundledThemeOmitsTheSlotWhenNoListUuidIsConfigured() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                RenderingTestSupport.clearRenderCaches();
                switchTheme(theme);
                String body = renderHomePage();

                assertFalse(body.contains("class=\"subscribe-form-slot\""),
                        theme + ": expected no subscribe-form-slot without a list uuid:\n"
                                + body);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }

        assertTrue(failures.isEmpty(),
                "themes failed the subscribe-slot-absent check:\n  "
                        + String.join("\n  ", failures));
    }
}
