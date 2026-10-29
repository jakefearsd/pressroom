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
 * The frontpage theme's top bar offers the authoring menu only to people who
 * can use it (spec criterion 26). A reader (SSO sign-in, role {@code reader})
 * is signed in, so the bar shows Logout, but the Main Menu link would only
 * lead to an access-denied page. Anonymous visitors get the login link.
 */
class FrontpageHeaderRenderingTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String MENU = "/pressroom-ui/menu.rol";

    private User author;
    private User reader;
    private Weblog weblog;
    private String handle;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "fphead" + suffix;
        author = TestUtils.setupUser("fpauthor" + suffix);
        reader = TestUtils.setupUser("fpreader" + suffix);
        TestUtils.weblogger().getUserManager().revokeRole("editor", reader);
        TestUtils.weblogger().getUserManager().grantRole("reader", reader);
        TestUtils.weblogger().flush();
        weblog = TestUtils.setupWeblog(handle, author);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme("frontpage");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(author.getUserName());
        TestUtils.teardownUser(reader.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anAuthorSeesTheMainMenuAndLogout() throws Exception {
        String html = render(author.getUserName());
        assertTrue(html.contains("fd-topbar"), html);
        assertTrue(html.contains(MENU), "an author must be offered the menu:\n" + html);
        assertTrue(html.contains("logout"), html);
    }

    @Test
    void aReaderSeesLogoutAndNoMainMenu() throws Exception {
        String html = render(reader.getUserName());
        assertTrue(html.contains("fd-topbar"), html);
        assertTrue(html.contains("logout"), "a signed-in reader must be able to sign out:\n" + html);
        assertFalse(html.contains("menu.rol"), "a reader must not be offered the authoring menu:\n" + html);
    }

    @Test
    void anAnonymousVisitorSeesLoginAndNoMainMenu() throws Exception {
        String html = render(null);
        assertTrue(html.contains("fd-topbar"), html);
        assertTrue(html.contains("login"), html);
        assertFalse(html.contains("menu.rol"), html);
        assertFalse(html.contains("logout"), html);
    }

    private String render(String userName) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(PAGE_SERVLET, "/" + handle + "/");
        if (userName != null) {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(userName, "n/a",
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
        }
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }
}
