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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.PressroomPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.core.AccountController;
import com.wikantik.pressroom.ui.restapi.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every controller that requires a signed-in user refuses a reader (spec
 * criterion 22), except the pages a reader is meant to reach: those are
 * excluded by name, each with its reason, in {@link #READER_PAGES}, and each
 * has a positive test of its own.
 *
 * <p>Characterisation sweep for the new {@code reader} role: it passes against
 * the code as written, because every such controller already demands the
 * global {@code login} action or a weblog permission, neither of which
 * {@code reader} implies. It is reflective, so a controller added tomorrow is
 * covered without anyone remembering to list it, and it fails the day a
 * controller lowers its requirement.
 *
 * <p>The real {@link PressroomHandlerInterceptor} is driven, not a re-statement
 * of its rules. The mocked user manager answers a global check with
 * {@code GlobalPermission.impliedByRoles(user, ["reader"])} and every weblog
 * check with false, which is what a reader holds.
 */
class ReaderRefusalSweepTest {

    private static final String BLOG = "someblog";
    private static final String READER = "reader-abcdefghij";

    private MockWeblogger weblogger;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (weblogger != null) {
            weblogger.detach();
        }
    }

    /** Concrete UISecurityEnforced classes in the app's ui packages, built with mocked arguments. */
    private static List<UISecurityEnforced> securedControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(UISecurityEnforced.class));
        return scanner.findCandidateComponents("com.wikantik.pressroom.ui").stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> !isTestFixture(name))
                .sorted()
                .map(ReaderRefusalSweepTest::instantiate)
                .toList();
    }

    /**
     * Test fixtures (PressroomHandlerInterceptorTest.SecuredController and its kin) live
     * on the same classpath and are not controllers. A fixture is a class whose
     * top-level class's simple name ends in {@code Test}; matching "Test" anywhere in
     * the name would also drop a {@code TestimonialsController}.
     */
    static boolean isTestFixture(String className) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        int nested = simpleName.indexOf('$');
        String topLevel = nested < 0 ? simpleName : simpleName.substring(0, nested);
        return topLevel.endsWith("Test");
    }

    private static UISecurityEnforced instantiate(String className) {
        try {
            Class<?> type = Class.forName(className);
            if (Modifier.isAbstract(type.getModifiers())) {
                fail(className + " is abstract; the scanner should have dropped it");
            }
            Constructor<?> widest = Stream.of(type.getConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow(() -> new IllegalStateException(
                            className + " has no public constructor"));
            Object[] args = Stream.of(widest.getParameterTypes())
                    .map(p -> Mockito.mock(p))
                    .toArray();
            return (UISecurityEnforced) widest.newInstance(args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new AssertionError(className + " could not be built from mocks for the reader sweep: " + e, e);
        }
    }

    /**
     * Pages a reader is meant to reach, each with the reason, left out of the
     * sweep by name and pinned by a positive test of its own instead.
     */
    private static final Map<String, String> READER_PAGES = Map.of(
            AccountController.class.getName(),
            "requires the global comment action by design, which readers hold");

    @TestFactory
    Stream<DynamicTest> everyUserRequiredControllerRefusesAReader() {
        return securedControllers().stream()
                .filter(UISecurityEnforced::isUserRequired)
                .filter(c -> !READER_PAGES.containsKey(c.getClass().getName()))
                .map(c -> DynamicTest.dynamicTest(c.getClass().getSimpleName(),
                        () -> assertFalse(interceptorAllows(c),
                                c.getClass().getName() + " let a reader through")));
    }

    /**
     * Only a test class's nested fixtures are left out: a controller whose name merely
     * contains "Test" is a controller, and escaping the sweep would be silent.
     */
    @Test
    void onlyNestedTestFixturesAreLeftOut() {
        String ui = "com.wikantik.pressroom.ui.controllers.";
        assertTrue(isTestFixture(ui + "PressroomHandlerInterceptorTest$SecuredController"));
        assertTrue(isTestFixture(ui + "core.ContactControllerTest$Probe"));
        assertTrue(isTestFixture("com.wikantik.pressroom.ui.restapi.ApiSecurityRefusalTest$Secured$Inner"));
        for (String controller : List.of("TestimonialsController", "LatestPostsController",
                "core.ContestController", "admin.TestsController", "MainMenuController")) {
            assertFalse(isTestFixture(ui + controller), controller + " would escape the sweep");
        }
    }

    @Test
    void theSweepFoundTheControllersItShould() {
        Set<String> names = securedControllers().stream()
                .map(c -> c.getClass().getSimpleName()).collect(toSet());
        assertTrue(names.containsAll(Set.of("MainMenuController", "ProfileController",
                "EntryEditController", "UserAdminController", "MediaApi")), names.toString());
        Set<String> classNames = securedControllers().stream()
                .map(c -> c.getClass().getName()).collect(toSet());
        assertTrue(classNames.containsAll(READER_PAGES.keySet()),
                "an exclusion that names no controller excuses nothing: " + READER_PAGES.keySet());
    }

    /**
     * The one page a reader may open: their own account page needs the global
     * {@code comment} action, which a reader holds; a visitor who is not signed
     * in is sent to log in first.
     */
    @Test
    void theAccountPageLetsAReaderInAndSendsAnonymousToLogIn() throws Exception {
        UISecurityEnforced account = instantiate(AccountController.class.getName());

        assertTrue(interceptorAllows(account), "a reader reaches their own account page");

        MockHttpServletResponse response = new MockHttpServletResponse();
        weblogger = MockWeblogger.attached();
        try {
            assertFalse(interceptorAllowsWithAttachedMocks(account, false, response));
        } finally {
            weblogger.detach();
            weblogger = null;
        }
        assertEquals("/roller/roller-ui/login.rol", response.getRedirectedUrl());
    }

    /**
     * Runs the real interceptor as a reader; a refusal is a false return or an ApiException.
     *
     * <p>Detaches its own mock before returning, not only in {@link #tearDown}: that runs
     * once for the whole {@code @TestFactory}, not once per dynamic test, and each
     * attachment restores the one before it. One detach for 42 attachments left
     * {@code WebloggerRuntimeConfig} on a mock for the rest of the JVM, so every
     * database-backed test after it read default runtime properties (uploads off, no
     * revision retention): 72 failures across 11 classes that each passed alone.
     */
    private boolean interceptorAllows(UISecurityEnforced controller) throws Exception {
        weblogger = MockWeblogger.attached();
        try {
            return interceptorAllowsWithAttachedMocks(controller, true, new MockHttpServletResponse());
        } finally {
            weblogger.detach();
            weblogger = null;
        }
    }

    private boolean interceptorAllowsWithAttachedMocks(UISecurityEnforced controller, boolean signedIn,
                                                       MockHttpServletResponse response) throws Exception {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        PressroomHandlerInterceptor interceptor =
                new PressroomHandlerInterceptor(provider, weblogger.weblogger());

        User reader = new User();
        reader.setUserName(READER);
        reader.setId("user-reader");
        Weblog blog = new Weblog();
        blog.setHandle(BLOG);
        blog.setId("weblog-1");
        when(weblogger.userManager().getUserByUserName(READER)).thenReturn(reader);
        when(weblogger.weblogManager().getWeblogByHandle(BLOG)).thenReturn(blog);
        when(weblogger.userManager().checkPermission(any(PressroomPermission.class), any(User.class)))
                .thenAnswer(inv -> {
                    PressroomPermission perm = inv.getArgument(0);
                    if (perm instanceof GlobalPermission) {
                        return GlobalPermission.impliedByRoles(reader, List.of("reader")).implies(perm);
                    }
                    return false; // a reader holds no weblog permission
                });

        if (signedIn) {
            UserDetails details = mock(UserDetails.class);
            when(details.getUsername()).thenReturn(READER);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(details, "n/a",
                            AuthorityUtils.createAuthorityList("ROLE_reader")));
        } else {
            SecurityContextHolder.clearContext();
        }

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/roller");
        request.setParameter("weblog", BLOG);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("handle", BLOG));
        HandlerMethod handler = new HandlerMethod(controller,
                controller.getClass().getMethod("isUserRequired"));
        try {
            return interceptor.preHandle(request, response, handler);
        } catch (ApiException refused) {
            return false;
        }
    }
}
