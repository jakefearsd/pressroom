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
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A published page whose content is {@code [contact]} renders, on every
 * bundled theme, BOTH the placeholder ({@code contact-form-slot}) AND the
 * {@code #showAudienceAssets} injector marker ({@code audience-hp}) -- proving
 * the shortcode and its client-side macro ship together. Modeled on
 * {@code PageNavRenderingTest}'s theme loop.
 */
class AudienceAssetsRenderingTest {

    private static final List<String> THEMES =
            List.of("journal", "portfolio", "travel");

    private static final String HANDLE = "audienceassetsblog";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("audienceassetsuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);

        savePage("contact", "Contact", "[contact]");
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager()
                .removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, String content) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private String renderContactPage() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE + "/contact");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "contact page must render");
        return response.getContentAsString();
    }

    @Test
    void everyBundledThemeShipsThePlaceholderAndTheInjector() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                RenderingTestSupport.clearRenderCaches();
                switchTheme(theme);
                String body = renderContactPage();

                assertTrue(body.contains("contact-form-slot"),
                        theme + ": expected the [contact] placeholder in the body:\n" + body);
                assertTrue(body.contains("audience-hp"),
                        theme + ": expected #showAudienceAssets' injector script:\n" + body);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }

        assertTrue(failures.isEmpty(),
                "themes failed the audience-assets check:\n  " + String.join("\n  ", failures));
    }
}
