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
package com.wikantik.pressroom.ui.controllers;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.PressroomPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code addFieldError} is {@code addError} plus the one extra fact the page
 * needs to point at the field that was refused: the DOM id of the control.
 *
 * <p>Two model attributes, deliberately: {@code invalidFields} is the ordered
 * set a test (or a future JSP) can ask questions of, and
 * {@code invalidFieldIds} is the space-joined string the three layouts render
 * into {@code <body data-invalid-fields>}. JSTL's {@code fn:join} only takes a
 * {@code String[]}, so the join happens here rather than in the page.
 */
class BaseControllerTest {

    private TestController controller;
    private Model model;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new TestController();
        // Nothing registered: getText falls back to the key, so the assertions
        // below read as message keys rather than English.
        controller.messageSource = new StaticMessageSource();
        model = new ExtendedModelMap();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.addPreferredLocale(Locale.US);
        request = mockRequest;
    }

    // --- the top bar's Main Menu link: userIsAuthor ---
    //
    // bannerStatus.jsp shows "Main Menu" only to someone who can use it. A reader
    // (SSO sign-in) holds no global action, so menu.rol refuses them; the link would
    // lead to the access-denied page from the login page and the site directory.

    @Test
    void anAuthorIsAnAuthor() throws WebloggerException {
        assertEquals(Boolean.TRUE, userIsAuthorFor(List.of("editor")));
    }

    @Test
    void aReaderIsNotAnAuthor() throws WebloggerException {
        assertEquals(Boolean.FALSE, userIsAuthorFor(List.of("reader")));
    }

    @Test
    void nobodySignedInIsNotAnAuthor() throws WebloggerException {
        controller.weblogger = MockWeblogger.create().weblogger();

        controller.populate(request, model);

        assertEquals(Boolean.FALSE, model.getAttribute("userIsAuthor"));
    }

    @Test
    void aPermissionCheckThatThrowsIsNotAnAuthor() throws WebloggerException {
        MockWeblogger mock = MockWeblogger.create();
        controller.weblogger = mock.weblogger();
        User user = userNamed("jake");
        when(mock.userManager().checkPermission(any(PressroomPermission.class), any(User.class)))
                .thenThrow(new WebloggerException("store unreachable"));
        setUser(user);

        controller.populate(request, model);

        assertEquals(Boolean.FALSE, model.getAttribute("userIsAuthor"),
                "a display default: a check that cannot run offers no link");
    }

    /** {@code userIsAuthor} for a signed-in user holding exactly {@code roles}. */
    private Object userIsAuthorFor(List<String> roles) throws WebloggerException {
        MockWeblogger mock = MockWeblogger.create();
        controller.weblogger = mock.weblogger();
        User user = userNamed("someone");
        when(mock.userManager().checkPermission(any(PressroomPermission.class), any(User.class)))
                .thenAnswer(inv -> inv.getArgument(0) instanceof GlobalPermission perm
                        && GlobalPermission.impliedByRoles(user, roles).implies(perm));
        setUser(user);

        controller.populate(request, model);

        return model.getAttribute("userIsAuthor");
    }

    private void setUser(User user) {
        ((MockHttpServletRequest) request).setAttribute("authenticatedUser", user);
    }

    private static User userNamed(String userName) {
        User user = new User();
        user.setUserName(userName);
        return user;
    }

    /**
     * The protected helpers are only reachable from a subclass, and this test
     * lives in the controllers package so it can be one.
     */
    private static final class TestController extends BaseController {

        void populate(HttpServletRequest request, Model model) {
            populateCommonModel(request, model);
        }
    }
}
