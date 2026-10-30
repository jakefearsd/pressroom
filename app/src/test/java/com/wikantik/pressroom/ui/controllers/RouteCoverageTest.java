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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when a controller grows a GET route that the browser reachability sweep
 * does not know about.
 *
 * <p>The sweep lives in the it-selenium module and only runs under
 * {@code mvn verify -Pit}, which costs minutes and needs Docker and a browser.
 * That is the wrong place to notice that a new page was never added to it, so
 * the bookkeeping half of the job lives here instead and runs in every
 * {@code mvn test}: enumerate the {@code @GetMapping} paths the controllers
 * actually declare, and check each one appears in the sweep's catalogue --
 * either as a route it visits, a route skipped with a written reason, or a
 * route recorded as broken. Adding a page to the UI without deciding which of
 * those three it is now fails the build.
 *
 * <p><b>Why this reads a file instead of importing the catalogue.</b> The app
 * module must not depend on an integration-test module -- that would make the
 * ordinary build need the IT module's Selenide and Selenium dependencies, and
 * invert the reactor (it-selenium already depends on the app WAR). So this test
 * reads {@code Routes.java} as text and pulls the route literals out with a
 * regex, exactly as {@link com.wikantik.pressroom.ui.WebjarReferenceTest}
 * reads JSPs to check the webjar URLs written into them. The alternative --
 * keeping a second copy of the list here -- would drift, and a coverage guard
 * that drifts is worse than none. The cost is a formatting rule: route paths in
 * Routes.java have to be plain {@code "/pressroom-ui/..."} string literals rather
 * than assembled from constants. That rule is stated at the top of Routes.java.
 */
public class RouteCoverageTest {

    private static final String CTRL_PACKAGE = "com.wikantik.pressroom.ui.controllers";

    /**
     * The catalogue the it-selenium sweep drives from. Relative to the app
     * module directory, which is the working directory Surefire uses.
     */
    private static final Path ROUTES_CATALOGUE = Paths.get(
            "../it-selenium/src/test/java/com/wikantik/pressroom/it/support/Routes.java");

    /**
     * Matches a servlet path written as a whole string literal. Deliberately
     * anchored on the {@code .rol} suffix so that prose in the skip reasons --
     * which mentions things like {@code /WEB-INF/jsps/editor/MediaFileViewLight}
     * -- cannot be mistaken for coverage. The extra alternatives are the
     * crawler-facing SEO routes (SeoController), which live outside
     * {@code /pressroom-ui/*.rol}.
     */
    private static final Pattern CATALOGUED_ROUTE = Pattern.compile(
            "\"(/pressroom-ui/[^\"\\s]*\\.rol|/sitemap[^\"\\s]*\\.xml|/robots\\.txt)\"");

    @Test
    public void everyGetRouteAppearsInTheSweepCatalogue() throws IOException {
        Set<String> declared = declaredGetRoutes();
        Set<String> catalogued = cataloguedRoutes();

        List<String> missing = new ArrayList<>();
        for (String route : declared) {
            if (!catalogued.contains(route)) {
                missing.add(route);
            }
        }

        assertTrue(missing.isEmpty(),
                "Controller GET route(s) are not listed in " + ROUTES_CATALOGUE.normalize()
                        + ", so the browser sweep will never open them:\n  "
                        + String.join("\n  ", missing)
                        + "\nAdd each one to Routes.java. If it renders with no parameters or "
                        + "with only weblog=it_weblog, put it in the covered list with a CSS "
                        + "selector for something its own content tile emits. If it needs an "
                        + "entity id nobody seeds, add it to SKIPPED with the reason. If it is "
                        + "broken, add it to BROKEN so the sweep asserts that it fails.");
    }

    /**
     * The other direction: a catalogued route whose controller was deleted or
     * renamed. Left in, the sweep would keep opening a URL that now 404s -- or
     * worse, a skip reason would keep excusing a route that no longer exists.
     */
    @Test
    public void everyCataloguedRouteStillHasAController() throws IOException {
        Set<String> declared = declaredGetRoutes();
        Set<String> catalogued = cataloguedRoutes();

        List<String> stale = new ArrayList<>();
        for (String route : catalogued) {
            if (!declared.contains(route)) {
                stale.add(route);
            }
        }

        assertTrue(stale.isEmpty(),
                "Route(s) listed in " + ROUTES_CATALOGUE.normalize() + " are no longer declared "
                        + "by any controller in " + CTRL_PACKAGE + ":\n  "
                        + String.join("\n  ", stale)
                        + "\nThe handler was probably renamed or removed. Delete the entry from "
                        + "Routes.java, or correct the path if it moved.");
    }

    /**
     * Guards the guards. Both tests above compare two sets; either one coming
     * back empty makes both of them vacuously pass. That would hide exactly the
     * kind of silent regression they exist to catch -- a component scan that
     * finds nothing because the package moved, or a catalogue file that was
     * relocated out from under the relative path.
     */
    @Test
    public void bothSidesOfTheComparisonAreActuallyPopulated() throws IOException {
        assertTrue(Files.exists(ROUTES_CATALOGUE),
                "Expected the sweep's route catalogue at "
                        + ROUTES_CATALOGUE.toAbsolutePath().normalize()
                        + ". If the it-selenium module moved, update ROUTES_CATALOGUE here; do "
                        + "not delete this test, because nothing else checks that new pages get "
                        + "an integration test.");

        Set<String> declared = declaredGetRoutes();
        assertFalse(declared.isEmpty(),
                "Scanned " + CTRL_PACKAGE + " and found no @GetMapping at all. The "
                        + "package was probably renamed, or the module was not compiled before "
                        + "the tests ran.");
        assertTrue(declared.size() > 40,
                "Only " + declared.size() + " GET routes were discovered, which is far fewer "
                        + "than Roller's admin UI has. The component scan is probably matching "
                        + "a subset of the controllers: " + declared);

        assertFalse(cataloguedRoutes().isEmpty(),
                "Read " + ROUTES_CATALOGUE.normalize() + " but matched no route literals. The "
                        + "file is present, so the paths are most likely no longer written as "
                        + "plain \"/pressroom-ui/...\" string literals -- see the formatting rule "
                        + "documented at the top of Routes.java.");
    }

    // ------------------------------------------------------------- discovery

    /**
     * Every GET path declared by an {@code @Controller} under the controllers
     * package, as full servlet paths (class-level prefix + method path).
     *
     * <p>Includes {@code @RequestMapping(method = GET)} as well as
     * {@code @GetMapping}: a few handlers -- entryAddWithMediaFile.rol,
     * install!bootstrap.rol and mediaFileView.rol -- are declared that way
     * because they accept both verbs, and a GET route is a GET route however
     * it was spelled.
     */
    private Set<String> declaredGetRoutes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<String> routes = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CTRL_PACKAGE)) {
            Class<?> controller = load(definition.getBeanClassName());
            String prefix = classLevelPrefix(controller);

            for (Method method : controller.getDeclaredMethods()) {
                for (String path : getPathsOn(method)) {
                    routes.add(prefix + path);
                }
            }
        }
        return routes;
    }

    private static String classLevelPrefix(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        if (mapping == null || mapping.value().length == 0) {
            return "";
        }
        return mapping.value()[0];
    }

    private static List<String> getPathsOn(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            return List.of(get.value());
        }

        RequestMapping request = method.getAnnotation(RequestMapping.class);
        if (request != null && handlesGet(request)) {
            return List.of(request.value());
        }

        return List.of();
    }

    /** An empty method list on @RequestMapping means "all verbs", GET included. */
    private static boolean handlesGet(RequestMapping mapping) {
        if (mapping.method().length == 0) {
            return true;
        }
        for (RequestMethod verb : mapping.method()) {
            if (verb == RequestMethod.GET) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "The component scan reported " + className + " but it is not loadable; the "
                            + "compiled classes and the scanned classpath disagree.", e);
        }
    }

    private Set<String> cataloguedRoutes() throws IOException {
        if (!Files.exists(ROUTES_CATALOGUE)) {
            // bothSidesOfTheComparisonAreActuallyPopulated() reports this
            // properly; returning empty here keeps the other two tests from
            // failing with a confusing "everything is missing" diff.
            return Set.of();
        }

        String source = Files.readString(ROUTES_CATALOGUE, StandardCharsets.UTF_8);
        Set<String> routes = new LinkedHashSet<>();
        Matcher matcher = CATALOGUED_ROUTE.matcher(source);
        while (matcher.find()) {
            routes.add(matcher.group(1));
        }
        return routes;
    }
}
