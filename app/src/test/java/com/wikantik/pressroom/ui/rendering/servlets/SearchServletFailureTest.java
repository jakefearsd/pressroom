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

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.core.PressroomContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search servlet over collaborators that fail: the reader gets a plain
 * 404, never a stack trace or a half-rendered page.
 */
class SearchServletFailureTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("searchfailuser");
        weblog = TestUtils.setupWeblog("searchfailblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static MockHttpServletResponse search(Weblogger weblogger) throws Exception {
        SearchServlet servlet = new SearchServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet("/pressroom-ui/rendering/search", "/searchfailblog");
        request.setParameter("q", "anything");
        return RenderingTestSupport.execute(servlet, request);
    }

    /** A weblog lookup that blows up is a 404, as an unknown weblog is. */
    @Test
    void aWeblogLookupThatFailsIsNotFound() throws Exception {
        WeblogManager broken = mock(WeblogManager.class);
        when(broken.getWeblogByHandle(anyString(), any())).thenThrow(new IllegalStateException("pool exhausted"));
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(broken);

        assertEquals(404, search(weblogger).getStatus());
    }

    /** A theme that cannot be read leaves no template to render with: a 404, not a 500 or an empty 200. */
    @Test
    void aThemeLookupThatFailsIsNotFound() throws Exception {
        ThemeManager broken = mock(ThemeManager.class);
        when(broken.getTheme(any(Weblog.class))).thenThrow(new WebloggerException("theme directory unreadable"));
        Weblogger weblogger = mock(Weblogger.class, delegatesTo(TestUtils.weblogger()));
        org.mockito.Mockito.doReturn(broken).when(weblogger).getThemeManager();

        MockHttpServletResponse response = search(weblogger);

        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString().contains("Exception"));
    }
}
