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
package org.apache.roller.weblogger.ui.controllers;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RollerPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
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

    @Test
    void addFieldErrorRecordsTheFieldAndTheJoinedIds() {
        controller.error(model, "weblog_bean_newsletterListUuid",
                "websiteSettings.newsletterListUuid.invalid", request);

        assertEquals(List.of("websiteSettings.newsletterListUuid.invalid"), errors(model));
        assertEquals(List.of("weblog_bean_newsletterListUuid"), invalidFields(model));
        assertEquals("weblog_bean_newsletterListUuid", model.getAttribute("invalidFieldIds"));
        assertTrue(controller.errorsPresent(model),
                "A field error must satisfy hasErrors() exactly the way addError does");
    }

    @Test
    void severalFieldErrorsJoinInTheOrderTheyWereAdded() {
        controller.error(model, "handle", "createWeblog.error.invalidHandle", request);
        controller.error(model, "emailAddress", "error.add.user.badEmail", request);

        assertEquals(List.of("handle", "emailAddress"), invalidFields(model));
        assertEquals("handle emailAddress", model.getAttribute("invalidFieldIds"));
    }

    @Test
    void theSameFieldNamedTwiceIsMarkedOnce() {
        controller.error(model, "handle", "createWeblog.error.invalidHandle", request);
        controller.error(model, "handle", "createWeblog.error.handleExists", request);

        assertEquals(List.of("handle"), invalidFields(model),
                "invalidFields is a set: two complaints about one control still mark it once");
        assertEquals("handle", model.getAttribute("invalidFieldIds"));
        assertEquals(2, errors(model).size(),
                "...but both messages are still shown, exactly as addError would");
    }

    @Test
    void theArgumentCarryingOverloadStillFormatsItsMessage() {
        controller.registerMessage("categoryForm.error.duplicateName", "Already have a {0}");

        controller.error(model, "category_bean_name", "categoryForm.error.duplicateName",
                new Object[]{"Travel"}, request);

        assertEquals(List.of("Already have a Travel"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
    }

    @Test
    void aModelWithNoFieldErrorsCarriesNeitherAttribute() {
        controller.plainError(model, "generic.error.check.logs", request);

        assertTrue(controller.errorsPresent(model));
        assertNull(model.getAttribute("invalidFields"),
                "addError must not start naming fields it was never given");
        assertNull(model.getAttribute("invalidFieldIds"));
    }

    @Test
    void hasErrorsIsUnchangedOnAnUntouchedModel() {
        assertFalse(controller.errorsPresent(model));
    }

    // --- B10: the weblog switcher's userWeblogs / switcherAction ---

    @Test
    void oneWeblogLeavesUserWeblogsAbsent() throws WebloggerException {
        MockWeblogger mock = MockWeblogger.create();
        controller.weblogger = mock.weblogger();
        User user = userNamed("jake");
        Weblog blogA = weblogNamed("blog-a", "Blog A");
        when(mock.userManager().getWeblogPermissions(user))
                .thenReturn(List.of(permissionFor(blogA, user)));
        when(mock.weblogManager().getWeblogByHandle("blog-a", null)).thenReturn(blogA);
        setUser(user);

        controller.populate(request, model);

        assertNull(model.getAttribute("userWeblogs"),
                "a switcher with nothing to switch to is not a switcher");
    }

    @Test
    void twoWeblogsAreListedSortedByHandle() throws WebloggerException {
        MockWeblogger mock = MockWeblogger.create();
        controller.weblogger = mock.weblogger();
        User user = userNamed("jake");
        Weblog blogB = weblogNamed("blog-b", "Blog B");
        Weblog blogA = weblogNamed("blog-a", "Blog A");
        when(mock.userManager().getWeblogPermissions(user)).thenReturn(
                List.of(permissionFor(blogB, user), permissionFor(blogA, user)));
        when(mock.weblogManager().getWeblogByHandle("blog-a", null)).thenReturn(blogA);
        when(mock.weblogManager().getWeblogByHandle("blog-b", null)).thenReturn(blogB);
        setUser(user);

        controller.populate(request, model);

        assertEquals(List.of(blogA, blogB), userWeblogs(model),
                "sorted by handle, regardless of permission order");
    }

    @Test
    void aWeblogLookupThatThrowsLeavesUserWeblogsAbsent() throws WebloggerException {
        MockWeblogger mock = MockWeblogger.create();
        controller.weblogger = mock.weblogger();
        User user = userNamed("jake");
        when(mock.userManager().getWeblogPermissions(user))
                .thenThrow(new WebloggerException("store unreachable"));
        setUser(user);

        controller.populate(request, model);

        assertNull(model.getAttribute("userWeblogs"),
                "the switcher is a convenience -- a failed lookup must not fail the page");
    }

    @Test
    void switcherActionKeepsARailAction() throws WebloggerException {
        controller.weblogger = MockWeblogger.create().weblogger();
        controller.setActionName("categories");

        controller.populate(request, model);

        assertEquals("categories", model.getAttribute("switcherAction"));
    }

    @Test
    void switcherActionFallsBackToEntriesOffTheRail() throws WebloggerException {
        controller.weblogger = MockWeblogger.create().weblogger();
        controller.setActionName("stylesheetEdit");

        controller.populate(request, model);

        assertEquals("entries", model.getAttribute("switcherAction"));
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
        when(mock.userManager().checkPermission(any(RollerPermission.class), any(User.class)))
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
        when(mock.userManager().checkPermission(any(RollerPermission.class), any(User.class)))
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

    private static Weblog weblogNamed(String handle, String name) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(name);
        return weblog;
    }

    private static WeblogPermission permissionFor(Weblog weblog, User user) {
        return new WeblogPermission(weblog, user, List.of(WeblogPermission.ADMIN));
    }

    @SuppressWarnings("unchecked")
    private static List<Weblog> userWeblogs(Model model) {
        return (List<Weblog>) model.getAttribute("userWeblogs");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errors(Model model) {
        return (List<String>) model.getAttribute("errors");
    }

    @SuppressWarnings("unchecked")
    private static List<String> invalidFields(Model model) {
        Collection<String> fields = (Collection<String>) model.getAttribute("invalidFields");
        return fields == null ? null : List.copyOf(fields);
    }

    /**
     * The protected helpers are only reachable from a subclass, and this test
     * lives in the controllers package so it can be one.
     */
    private static final class TestController extends BaseController {

        private String actionName;

        void populate(HttpServletRequest request, Model model) {
            populateCommonModel(request, model);
        }

        void setActionName(String actionName) {
            this.actionName = actionName;
        }

        @Override
        public String getActionName() {
            return actionName;
        }

        void error(Model model, String fieldId, String key, HttpServletRequest request) {
            addFieldError(model, fieldId, key, request);
        }

        void error(Model model, String fieldId, String key, Object[] args, HttpServletRequest request) {
            addFieldError(model, fieldId, key, args, request);
        }

        void plainError(Model model, String key, HttpServletRequest request) {
            addError(model, key, request);
        }

        boolean errorsPresent(Model model) {
            return hasErrors(model);
        }

        void registerMessage(String key, String pattern) {
            ((StaticMessageSource) messageSource).addMessage(key, Locale.US, pattern);
        }
    }
}
