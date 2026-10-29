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
package com.wikantik.pressroom.ui.controllers.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RedirectController}, which exists so that a handful of
 * bare JSPs are reachable through the {@code .rol} dispatcher.
 *
 * <p>Each handler names a JSP as a string. Nothing at compile time checks that
 * the file is there, and a wrong name is a 404 or a blank page at runtime, so
 * these tests check both the returned target and that the file it names exists.
 */
class RedirectControllerTest {

    private static final Path WEBAPP = findWebapp();

    private final RedirectController controller = new RedirectController();

    @Test
    void loginRedirectForwardsToItsJsp() {
        assertForwardsToExistingJsp("/pressroom-ui/login-redirect.jsp", controller.loginRedirect());
    }

    @Test
    void logoutForwardsToItsJsp() {
        assertForwardsToExistingJsp("/pressroom-ui/logout-redirect.jsp", controller.logout());
    }

    @Test
    void accessDeniedResolvesThroughTheDeniedTilesView() {
        // The interceptor sends every permission failure here. Before this
        // handler existed the target had no .rol suffix, the dispatcher never
        // saw it, and every denial showed a bare 404 instead of this page.
        // Returning the ".denied" view name (rather than forwarding straight
        // to the raw JSP) is what gives denied.jsp the .tiles-errorpage
        // layout's head/banner/footer chrome and design-system CSS -- see
        // PressroomViewResolverTest for the view registration, and denied.jsp
        // still exists on disk as the content that view forwards to.
        Model model = new ExtendedModelMap();
        assertEquals(".denied", controller.accessDenied(model));
        assertTrue(Files.exists(WEBAPP.resolve("pressroom-ui/errors/denied.jsp")),
                ".denied's tiles content target is missing");
        // Without a pageTitle the errorpage layout falls back to the generic
        // `error` key, which put "Error" in the tab for a permission answer.
        assertEquals("denied.title", model.getAttribute("pageTitle"));
    }

    @Test
    void homeRedirectsToTheSiteRoot() {
        assertEquals("redirect:/", controller.home());
    }

    @Test
    void theseTargetsAreReachableWithoutSigningInOrPickingAWeblog() {
        // The logout and access-denied pages in particular are needed by
        // visitors who have just lost, or never had, a session.
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertEquals(List.of(), controller.requiredGlobalPermissionActions());
    }

    private static void assertForwardsToExistingJsp(String jspPath, String returnedView) {
        assertEquals("forward:" + jspPath, returnedView);
        assertTrue(Files.exists(WEBAPP.resolve(jspPath.substring(1))),
                "handler forwards to " + jspPath + ", but no such file exists under "
                        + WEBAPP.toAbsolutePath() + " -- the forward would 404 at runtime");
    }

    /**
     * The JSPs are not on the test classpath, only in the source tree, and the
     * working directory depends on how the tests were launched (Maven runs them
     * from {@code app/}, other runners from the project root).
     */
    private static Path findWebapp() {
        for (Path candidate : List.of(Path.of("src/main/webapp"), Path.of("app/src/main/webapp"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not find src/main/webapp from working directory "
                        + Path.of("").toAbsolutePath()
                        + ". Add the right relative path here so the forward targets can be checked.");
    }
}
