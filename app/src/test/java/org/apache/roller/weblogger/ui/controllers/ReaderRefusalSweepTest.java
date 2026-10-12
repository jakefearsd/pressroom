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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RollerPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every controller that requires a signed-in user refuses a reader (spec
 * criterion 22).
 *
 * <p>Characterisation sweep for the new {@code reader} role: it passes against
 * the code as written, because every such controller already demands the
 * global {@code login} action or a weblog permission, neither of which
 * {@code reader} implies. It is reflective, so a controller added tomorrow is
 * covered without anyone remembering to list it, and it fails the day a
 * controller lowers its requirement.
 *
 * <p>The real {@link RollerHandlerInterceptor} is driven, not a re-statement
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
        // Test fixtures (RollerHandlerInterceptorTest.SecuredController and its kin)
        // live on the same classpath and are not controllers.
        return scanner.findCandidateComponents("org.apache.roller.weblogger.ui").stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> !name.contains("Test"))
                .sorted()
                .map(ReaderRefusalSweepTest::instantiate)
                .toList();
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

    @TestFactory
    Stream<DynamicTest> everyUserRequiredControllerRefusesAReader() {
        return securedControllers().stream()
                .filter(UISecurityEnforced::isUserRequired)
                .map(c -> DynamicTest.dynamicTest(c.getClass().getSimpleName(),
                        () -> assertFalse(interceptorAllows(c),
                                c.getClass().getName() + " let a reader through")));
    }

    @Test
    void theSweepFoundTheControllersItShould() {
        Set<String> names = securedControllers().stream()
                .map(c -> c.getClass().getSimpleName()).collect(toSet());
        assertTrue(names.containsAll(Set.of("MainMenuController", "ProfileController",
                "EntryEditController", "UserAdminController", "MediaApi")), names.toString());
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
            return interceptorAllowsWithAttachedMocks(controller);
        } finally {
            weblogger.detach();
            weblogger = null;
        }
    }

    private boolean interceptorAllowsWithAttachedMocks(UISecurityEnforced controller) throws Exception {
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        RollerHandlerInterceptor interceptor =
                new RollerHandlerInterceptor(provider, weblogger.weblogger());

        User reader = new User();
        reader.setUserName(READER);
        reader.setId("user-reader");
        Weblog blog = new Weblog();
        blog.setHandle(BLOG);
        blog.setId("weblog-1");
        when(weblogger.userManager().getUserByUserName(READER)).thenReturn(reader);
        when(weblogger.weblogManager().getWeblogByHandle(BLOG)).thenReturn(blog);
        when(weblogger.userManager().checkPermission(any(RollerPermission.class), any(User.class)))
                .thenAnswer(inv -> {
                    RollerPermission perm = inv.getArgument(0);
                    if (perm instanceof GlobalPermission) {
                        return GlobalPermission.impliedByRoles(reader, List.of("reader")).implies(perm);
                    }
                    return false; // a reader holds no weblog permission
                });

        UserDetails details = mock(UserDetails.class);
        when(details.getUsername()).thenReturn(READER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_reader")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/roller");
        request.setParameter("weblog", BLOG);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("handle", BLOG));
        HandlerMethod handler = new HandlerMethod(controller,
                controller.getClass().getMethod("isUserRequired"));
        try {
            return interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        } catch (ApiException refused) {
            return false;
        }
    }
}
