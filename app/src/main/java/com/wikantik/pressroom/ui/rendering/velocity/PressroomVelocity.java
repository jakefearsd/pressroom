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
import java.util.Objects;
import java.util.Properties;

import jakarta.servlet.ServletContext;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The VelocityEngine used by Pressroom, initialised explicitly at bootstrap.
 *
 * <p>The engine used to be built in a static initialiser that read
 * {@code /WEB-INF/velocity.properties} through {@code PressroomContext}'s static
 * {@code ServletContext}; the two Pressroom resource loaders it instantiates
 * ({@link PressroomResourceLoader}, {@link ThemeResourceLoader}) then reached the
 * business tier through a static service locator. Both are gone:
 * {@link #initialize(ServletContext, Weblogger)} is called once, by
 * {@code PressroomLifecycle.start()} after the business tier has bootstrapped
 * (and by the rendering test support), and it hands the {@link Weblogger}
 * to the loaders the only way Velocity allows -- as an <em>application
 * attribute</em> on the engine, read back via {@code RuntimeServices} in each
 * loader's {@code init}. The engine itself remains a process-wide singleton,
 * on purpose: see the design spec's Decision 4 (2026-08-22, retire the static
 * service locator) for why the Velocity stack is left static while everything
 * above it is injected. The facade lives in the engine's attribute map, never
 * in a static field of this class.
 */
public final class PressroomVelocity {

    private PressroomVelocity() {
    }

    public static final String VELOCITY_CONFIG = "/WEB-INF/velocity.properties";

    /** The application-attribute key under which the engine carries the facade. */
    public static final String WEBLOGGER_ATTRIBUTE = Weblogger.class.getName();

    private static final Logger log = LoggerFactory.getLogger(PressroomVelocity.class);

    private static volatile VelocityEngine velocityEngine;
    // Private lock, not the class's own: the class is public and its intrinsic
    // lock reachable by anyone (SpotBugs USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION).
    private static final Object INIT_LOCK = new Object();


    /**
     * Build and install the engine. Idempotent: a second call is a no-op, even
     * with different arguments -- the rendering test support relies on that,
     * because the whole test module shares one JVM and the engine cannot be
     * re-initialised.
     *
     * @throws WebloggerException if {@value #VELOCITY_CONFIG} is missing or
     *         unreadable; nothing is installed in that case, so a later call
     *         may still succeed.
     */
    public static void initialize(ServletContext servletContext, Weblogger weblogger)
            throws WebloggerException {
        synchronized (INIT_LOCK) {
            if (velocityEngine != null) {
                log.debug("Velocity Rendering Engine already initialised; ignoring repeat call");
                return;
            }
            log.info("Initializing Velocity Rendering Engine");
            velocityEngine = buildEngine(servletContext, weblogger);
        }
    }

    /** True once {@link #initialize} has succeeded in this JVM. */
    public static boolean isInitialized() {
        return velocityEngine != null;
    }

    /**
     * Build an engine from {@value #VELOCITY_CONFIG} carrying {@code weblogger}
     * as its {@link #WEBLOGGER_ATTRIBUTE}. Package-private so a test can build
     * a throwaway engine without touching the JVM-wide one.
     */
    static VelocityEngine buildEngine(ServletContext servletContext, Weblogger weblogger)
            throws WebloggerException {
        Objects.requireNonNull(servletContext, "servletContext");
        Objects.requireNonNull(weblogger, "weblogger");

        Properties velocityProps = new Properties();
        try (InputStream instream = servletContext.getResourceAsStream(VELOCITY_CONFIG)) {
            if (instream == null) {
                throw new WebloggerException("Velocity config " + VELOCITY_CONFIG + " not found");
            }
            velocityProps.load(instream);
        } catch (IOException e) {
            throw new WebloggerException("Unable to read " + VELOCITY_CONFIG, e);
        }

        // How often Velocity may re-check whether a shared theme changed on disk
        // before it will reuse a cached parse tree. Seconds. Small enough that an
        // edit shows up on its own, large enough that steady read traffic pays one
        // timestamp comparison per template per interval rather than a full
        // re-parse per request.
        velocityProps.setProperty("resource.loader.theme.modification_check_interval",
                Integer.toString(WebloggerConfig.getIntProperty("themes.reload.interval", 5)));

        // Development theme reloading
        if (WebloggerConfig.getBooleanProperty("themes.reload.mode")) {
            velocityProps.setProperty("resource.loader.class.cache", "false");
            velocityProps.setProperty("resource.loader.class.modification_check_interval", "2");
            velocityProps.setProperty("resource.loader.webapp.cache", "false");
            velocityProps.setProperty("resource.loader.webapp.modification_check_interval", "2");
            // The theme loader is NOT switched off here, unlike the two above. It
            // keeps its parse cache and simply rechecks more eagerly, because it
            // can: ThemeResourceLoader reports the theme's real disk timestamp, so
            // an edit is picked up on the next check rather than needing the cache
            // gone entirely.
            velocityProps.setProperty("resource.loader.theme.modification_check_interval", "2");
            velocityProps.setProperty("velocimacro.library.autoreload", "true");
        }

        // Observation only (the recorder returns null everywhere): lets the rendering
        // tests see references Velocity would otherwise print as literal text or
        // swallow silently (quiet $!x, tested #if($x), null-valued).
        velocityProps.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES,
                InvalidReferenceRecorder.class.getName());
        velocityProps.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_QUIET, "true");
        velocityProps.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_NULL, "true");
        velocityProps.setProperty(RuntimeConstants.EVENTHANDLER_INVALIDREFERENCES_TESTED, "true");

        log.debug("Velocity engine props = {}", velocityProps);

        VelocityEngine engine = new VelocityEngine();
        // Must be set before init(): the engine instantiates the resource
        // loaders during init(), and PressroomResourceLoader/ThemeResourceLoader
        // read this attribute in their own init().
        engine.setApplicationAttribute(WEBLOGGER_ATTRIBUTE, weblogger);
        engine.init(velocityProps);
        return engine;
    }


    /**
     * Access to the VelocityEngine.
     *
     * @throws IllegalStateException before {@link #initialize} has run
     */
    public static VelocityEngine getEngine() {
        VelocityEngine engine = velocityEngine;
        if (engine == null) {
            throw new IllegalStateException("PressroomVelocity has not been initialised");
        }
        return engine;
    }

    /**
     * Convenience static method for looking up a template.
     * @throws org.apache.velocity.exception.ResourceNotFoundException,
     *       org.apache.velocity.exception.ParseErrorException
     */
    public static Template getTemplate(String name) {
        return getEngine().getTemplate(name + "|standard");
    }

    /**
     * Convenience static method for looking up a template.
     * @throws org.apache.velocity.exception.ResourceNotFoundException,
     *       org.apache.velocity.exception.ParseErrorException
     */
    public static Template getTemplate(String name, String encoding) {
        return getEngine().getTemplate(name + "|standard", encoding);
    }
}
