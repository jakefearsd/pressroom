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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stale cached {@code /}: switching the front door from one site page to
 * another shows the new page on the very next request, with the render cache
 * live (Review Focus 5). Each page is cached under its own address, and
 * {@code FrontDoor} chooses the address per request, so no stale entry can
 * answer for the page the admin moved away from.
 */
class FrontDoorSwitchTest {

    private User admin;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        admin = TestUtils.setupUser("frontdoorswitchadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        Weblog site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        savePage(site, "doorhome", "Home page of the site", "Home body");
        savePage(site, "doorabout", "About page of the site", "About body");
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

    private void savePage(Weblog site, String slug, String title, String content) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(site));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void setFrontDoor(String slug) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent("site.frontpage.page", key -> config.get(key).getValue());
        config.get("site.frontpage.page").setValue(slug);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    /** {@code /} as index.jsp serves it: FrontDoor's choice, forwarded, cache enabled. */
    private String renderRoot() throws Exception {
        String path = FrontDoor.forwardPath(TestUtils.weblogger());
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/page", path.substring("/roller-ui/rendering/page".length()));
        request.setDispatcherType(DispatcherType.FORWARD);
        request.removeAttribute("skipCache");
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request).getContentAsString();
    }

    @Test
    void switchingTheFrontDoorShowsTheNewPageOnTheNextRequest() throws Exception {
        setFrontDoor("doorhome");
        assertTrue(renderRoot().contains("Home body"));
        assertTrue(renderRoot().contains("Home body"), "served again, now from the cache");

        setFrontDoor("doorabout");
        String after = renderRoot();
        assertTrue(after.contains("About body"), after);
        assertFalse(after.contains("Home body"), after);

        setFrontDoor("doorhome");
        assertTrue(renderRoot().contains("Home body"), "and back again");
        assertEquals("/roller-ui/rendering/page/site-space/doorhome",
                FrontDoor.forwardPath(TestUtils.weblogger()));
    }
}
