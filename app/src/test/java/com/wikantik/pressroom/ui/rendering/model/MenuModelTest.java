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

package com.wikantik.pressroom.ui.rendering.model;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MenuModel}, the {@code $menuModel} object that renders
 * the editor and admin navigation bars into a live blog page.
 *
 * <p>This model is an authorisation boundary: it decides whether a visitor sees
 * links into the editing UI. The tests below concentrate on it failing closed —
 * an anonymous visitor, or one whose permission check errors, must get nothing.
 */
class MenuModelTest {

    private UserManager userManager;
    private Weblogger weblogger;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        // No static shim here: the model gets its facade through initData, and
        // MenuHelper takes the UserManager as a parameter (plan Task 8).
        userManager = mock(UserManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(userManager);

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");
    }

    private WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        return request;
    }

    private MenuModel modelFor(WeblogPageRequest request) throws WebloggerException {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        MenuModel model = new MenuModel();
        model.init(initData);
        return model;
    }

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("menuModel", new MenuModel().getModelName(),
                "Themes reference this model as $menuModel.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        MenuModel model = new MenuModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initRejectsRequestsThatAreNotPageRequests() {
        WeblogFeedRequest feedRequest = new WeblogFeedRequest();
        feedRequest.setWeblog(weblog);
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest);

        MenuModel model = new MenuModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "MenuModel must refuse a request it cannot serve.");
        assertTrue(thrown.getMessage().contains("WeblogPageRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    @Test
    void anonymousVisitorsGetNoMenus() throws Exception {
        MenuModel model = modelFor(pageRequest());

        assertNull(model.getAuthorMenu(),
                "A visitor who is not logged in must not be offered the editor menu.");
        assertNull(model.getAdminMenu(),
                "A visitor who is not logged in must not be offered the admin menu.");
    }

    @Test
    void aLoggedInVisitorWithoutAdminRightsGetsNoAdminMenu() throws Exception {
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);
        when(userManager.checkPermission(any(), any())).thenReturn(false);

        assertNull(modelFor(request).getAdminMenu(),
                "Being logged in is not enough; the admin menu needs the global "
                        + "admin permission.");
    }

    @Test
    void aFailedPermissionCheckHidesTheAdminMenu() throws Exception {
        // Fail closed: if we cannot confirm the permission we must not show the
        // menu, and the page must still render.
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);
        when(userManager.checkPermission(any(), any()))
                .thenThrow(new WebloggerException("database is down"));

        assertNull(modelFor(request).getAdminMenu(),
                "A permission check that blows up must hide the menu, not show it.");
    }

    /**
     * A failed permission check must not just be swallowed at DEBUG: it is
     * logged at ERROR with the exception attached, matching its siblings in
     * MenuHelper. Captures the real log4j2 output via a throwaway appender
     * bound to MenuModel's own logger, rather than trusting that a return
     * value alone proves the log call changed.
     */
    @Test
    void aFailedPermissionCheckIsLoggedAtErrorWithTheException() throws Exception {
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);
        WebloggerException failure = new WebloggerException("database is down");
        when(userManager.checkPermission(any(), any())).thenThrow(failure);

        List<LogEvent> captured = new ArrayList<>();
        Appender appender = new AbstractAppender("MenuModelTest-capture", null, null, false,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (MenuModel.class.getName().equals(event.getLoggerName())) {
                    captured.add(event.toImmutable());
                }
            }
        };
        appender.start();

        LoggerContext context = LoggerContext.getContext(false);
        LoggerConfig loggerConfig = context.getConfiguration().getLoggerConfig(MenuModel.class.getName());
        loggerConfig.addAppender(appender, null, null);
        try {
            modelFor(request).getAdminMenu();
        } finally {
            loggerConfig.removeAppender("MenuModelTest-capture");
            appender.stop();
        }

        assertFalse(captured.isEmpty(), "Expected the failed permission check to log something.");
        LogEvent event = captured.get(0);
        assertEquals(Level.ERROR, event.getLevel(),
                "A failed permission check must be logged at ERROR, not silently downgraded to DEBUG.");
        assertNotNull(event.getThrown(),
                "The caught exception must be attached to the log record, not discarded.");
        assertEquals("database is down", event.getThrown().getMessage());
    }

    @Test
    void aLoggedInAdministratorGetsTheAdminMenu() throws Exception {
        User user = new User();
        user.setUserName("root");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("root");
        request.setUser(user);
        when(userManager.checkPermission(any(), any())).thenReturn(true);

        assertNotNull(modelFor(request).getAdminMenu(),
                "A logged-in global administrator must be offered the admin menu.");
    }

    @Test
    void aLoggedInVisitorGetsTheEditorMenu() throws Exception {
        User user = new User();
        user.setUserName("bob");
        WeblogPageRequest request = pageRequest();
        request.setAuthenticUser("bob");
        request.setUser(user);

        assertNotNull(modelFor(request).getAuthorMenu(),
                "A logged-in author must be offered the editor menu; this is the only "
                        + "route back into the admin UI from a blog page.");
    }
}
