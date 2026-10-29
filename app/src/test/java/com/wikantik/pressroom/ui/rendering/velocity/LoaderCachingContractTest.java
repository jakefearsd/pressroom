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
package com.wikantik.pressroom.ui.rendering.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.apache.velocity.runtime.resource.Resource;
import org.apache.velocity.runtime.resource.loader.ResourceLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One invariant, over every Velocity resource loader this application
 * configures: <b>a loader that cannot report modification may not be
 * cached.</b>
 *
 * <p>The pairing is not decorative, and getting it wrong is invisible. With
 * caching on, Velocity asks {@code isSourceModified} whether its parse tree
 * is stale and reuses it whenever the answer is no. A loader that answers
 * "no" unconditionally therefore pins every template it ever loaded for the
 * life of the JVM: editing one does nothing at all until a restart, with no
 * error anywhere, on a screen whose whole purpose is editing templates.
 *
 * <p>This has been live in both directions. {@link ThemeResourceLoader} used
 * to hardcode {@code isSourceModified() -> false} and
 * {@code getLastModified() -> 0}, which is exactly why
 * {@code resource.loader.theme.cache} had to be {@code false}, which in turn
 * cost a full re-read and re-parse of every theme template on every request
 * in production. Making that loader honest is what let its cache be turned
 * on. {@link PressroomResourceLoader} is the half that did NOT change: it serves
 * custom templates from the database and still answers
 * {@code isSourceModified() -> false} unconditionally, so its cache must stay
 * off, and until this test the only thing standing between that and
 * silently-broken custom-template editing was one unremarked line in
 * velocity.properties.
 *
 * <p>Deliberately behavioural rather than a config-to-config comparison: it
 * probes what each cached loader actually answers, so a NEW loader that
 * arrives with the same trap is caught by the same test, and a loader later
 * taught to report modification honestly is free to enable its cache without
 * anyone editing a list here.
 */
class LoaderCachingContractTest {

    private static final Path VELOCITY_CONFIG =
            Path.of("src/main/webapp/WEB-INF/velocity.properties");

    /**
     * Cached loaders that cannot be probed by constructing them alone,
     * because their freshness answer genuinely depends on a collaborator
     * they are handed at {@code init()}.
     *
     * <p>Named individually, with a reason, rather than skipped by catching:
     * a new cached loader that cannot be probed fails this test until
     * someone decides which list it belongs in, the same way
     * {@code QualityGatePomTest} refuses an undeclared analysis exclusion.
     *
     * <p>{@code webapp} is {@code WebappResourceLoader}, which asks the
     * {@link jakarta.servlet.ServletContext} for a real file path and reports
     * that file's timestamp. Its freshness is the servlet container's answer
     * about a real file, not a Roller constant, so it is honest by
     * construction; it simply throws rather than answering when it has no
     * context, which is why the probe cannot judge it.
     */
    private static final Set<String> UNPROBEABLE_CACHED_LOADERS = Set.of("webapp");

    /**
     * A resource so old that any loader able to report modification at all
     * must call it stale. Answering "not modified" to this is the signature
     * of a loader whose freshness check is a constant.
     */
    private static Resource maximallyStaleResource() {
        Resource resource = new org.apache.velocity.Template();
        resource.setName("nosuchtheme:nosuchtemplate");
        resource.setLastModified(Long.MIN_VALUE);
        return resource;
    }

    @Test
    void everyCachedLoaderCanActuallyReportModification() throws IOException {
        Map<String, String> loaders = pressroomLoaders();
        Set<String> unprobeable = new LinkedHashSet<>();
        int probed = 0;

        for (Map.Entry<String, String> loader : loaders.entrySet()) {
            String name = loader.getKey();
            if (!cacheEnabled(name)) {
                continue;
            }

            // Not init()ed on purpose. A loader whose freshness answer is a
            // constant does not need collaborators to give it, and one that
            // does need them throws instead of answering -- which is the
            // distinction the declared list above is drawn on.
            Boolean answer;
            try {
                answer = instantiate(loader.getValue())
                        .isSourceModified(maximallyStaleResource());
            } catch (RuntimeException uninitialised) {
                unprobeable.add(name);
                continue;
            }

            probed++;
            assertTrue(answer,
                    "resource.loader." + name + ".cache is true, but " + loader.getValue()
                            + " answers isSourceModified(...) = false even for a maximally stale "
                            + "resource, so it can never tell Velocity a template changed. That "
                            + "combination pins every parsed template for the life of the JVM and "
                            + "makes editing one silently do nothing until a restart. Either turn "
                            + "the cache back off, or teach the loader to report a real timestamp "
                            + "first (see ThemeResourceLoader, which was cached only after it was "
                            + "made honest).");
        }

        assertEquals(UNPROBEABLE_CACHED_LOADERS, unprobeable,
                "the set of cached loaders that cannot be probed without their collaborators "
                        + "changed. A new one is not automatically safe: decide whether its "
                        + "freshness answer is real (like webapp's, which is the servlet "
                        + "container's timestamp for a real file) or a constant (like "
                        + "PressroomResourceLoader's), and either name it here with that reason or "
                        + "turn its cache off.");
        assertTrue(probed > 0,
                "no cached loader was actually probed; this test would be asserting nothing");
    }

    /**
     * The companion half, stated as its own case so the reason
     * {@code resource.loader.roller.cache} is false does not have to be
     * inferred from the absence of a failure above.
     */
    @Test
    void theCustomTemplateLoaderReportsNoTimestampAndIsThereforeUncached() throws IOException {
        PressroomResourceLoader loader = new PressroomResourceLoader();

        assertFalse(loader.isSourceModified(maximallyStaleResource()),
                "PressroomResourceLoader is expected to answer a constant false; if it has been "
                        + "taught to report real modification, update this test and the comment "
                        + "in velocity.properties -- its cache may then be enabled.");
        assertEquals(0L, loader.getLastModified(maximallyStaleResource()),
                "and a constant 0 for the timestamp, for the same reason");
        assertFalse(cacheEnabled("roller"),
                "so resource.loader.roller.cache MUST stay false: caching a loader that never "
                        + "reports modification breaks custom-template editing until a restart, "
                        + "silently.");
    }

    /**
     * The loader short names mapped to their class names, limited to Roller's
     * own loaders -- Velocity's stock classpath loader reads immutable jar
     * entries and is not ours to make claims about.
     */
    private static Map<String, String> pressroomLoaders() throws IOException {
        Properties props = config();
        Map<String, String> loaders = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("resource.loader.") && key.endsWith(".class")) {
                String name = key.substring("resource.loader.".length(),
                        key.length() - ".class".length());
                String className = props.getProperty(key).trim();
                if (className.startsWith("com.wikantik.pressroom.")) {
                    loaders.put(name, className);
                }
            }
        }
        if (loaders.isEmpty()) {
            fail("no Roller resource loaders found in " + VELOCITY_CONFIG
                    + "; this test would be asserting nothing");
        }
        return loaders;
    }

    private static boolean cacheEnabled(String loaderName) throws IOException {
        // Velocity's own default is false, so an absent key means "off" and
        // only an explicit true enables caching.
        return Boolean.parseBoolean(config()
                .getProperty("resource.loader." + loaderName + ".cache", "false").trim());
    }

    private static Properties config() throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(VELOCITY_CONFIG)) {
            props.load(in);
        }
        return props;
    }

    private static ResourceLoader instantiate(String className) {
        try {
            return (ResourceLoader) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException ex) {
            return fail("velocity.properties names " + className
                    + " as a resource loader, but it could not be instantiated as one", ex);
        }
    }
}
