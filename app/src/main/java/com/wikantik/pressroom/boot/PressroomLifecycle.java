/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.boot;

import java.io.File;

import jakarta.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.startup.StartupException;
import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.rendering.velocity.PressroomVelocity;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drives the Roller startup/shutdown sequence that {@code PressroomContext}
 * used to run from {@code ServletContextListener.contextInitialized}/
 * {@code contextDestroyed}.
 *
 * <p>Registered as a Boot {@link SmartLifecycle} bean at phase 0 (explicit
 * {@link #getPhase()} override -- {@code SmartLifecycle}'s own default phase
 * is {@link SmartLifecycle#DEFAULT_PHASE}, {@code Integer.MAX_VALUE}, which
 * is numerically *larger* than Boot's own
 * {@code WebServerStartStopLifecycle} phase
 * ({@code Integer.MAX_VALUE - 2048}, verified via {@code javap} against
 * {@code spring-boot-web-server-4.1.0.jar}). {@code DefaultLifecycleProcessor}
 * starts phases in ascending order and stops them in descending order, so
 * leaving the default phase in place would start Roller *after* the
 * connector opens and stop it *before* the connector closes -- exactly
 * inverted from what's wanted. Phase 0 is safely below
 * {@code WebServerStartStopLifecycle}'s phase, so {@link #start()} runs
 * before the connector opens and {@link #stop()} runs after it has closed --
 * requests never see a half-started or half-shutdown Roller.
 *
 * <p>This transcribes {@code PressroomContext.contextInitialized}'s behavior
 * stage by stage, including its failure handling: most failure modes here
 * are logged as fatal and swallowed (the app keeps coming up, same as the
 * old listener never rethrowing out of {@code contextInitialized}) rather
 * than propagating, because a {@code SmartLifecycle.start()} exception
 * aborts the entire {@code SpringApplication.run()} -- a much bigger blast
 * radius than the old per-webapp listener failure ever had. The one
 * genuinely new failure mode (archive-mode {@code themes.dir}
 * misconfiguration, see {@link #resolveDirectories()}) is intentionally a
 * hard failure: there is no sensible fallback for it.
 */
@Component
@ConditionalOnProperty(name = "pressroom.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class PressroomLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PressroomLifecycle.class);

    private final WebloggerProvider webloggerProvider;
    private final ServletContext servletContext;
    private volatile boolean running;

    /**
     * @param webloggerProvider the bean that bootstraps the business tier and
     *        answers "is it up?" (plain injection -- constructing it does
     *        nothing; the tier is only built inside {@link #start()}, after
     *        {@code WebloggerStartup.prepare()}).
     */
    public PressroomLifecycle(WebloggerProvider webloggerProvider, ServletContext servletContext) {
        this.webloggerProvider = webloggerProvider;
        this.servletContext = servletContext;
    }

    @Override
    public void start() {
        // Keep a reference to the ServletContext, same as the old
        // contextInitialized's very first line.
        PressroomContext.hold(servletContext);

        resolveDirectories();

        // Prepare the core services (database/mail providers). A
        // StartupException here means the configured database could not be
        // reached at all. The old code logged this as fatal and returned
        // from contextInitialized, leaving the servlet context up (if
        // largely unusable) rather than failing the deployment outright;
        // reproduce that rather than aborting the whole Boot process.
        try {
            WebloggerStartup.prepare();
        } catch (StartupException ex) {
            log.error("Roller Weblogger startup failed during app preparation", ex);
            running = true;
            return;
        }

        final boolean ittest = "ittest".equals(WebloggerConfig.getProperty("installation.type"));

        // If preparation left the app unprepared (e.g. installation.type=auto
        // with pending schema work) and this isn't the IT-test bootstrap
        // path, do NOT try to bootstrap the business tier -- same as before,
        // this is a normal "not installed yet" state, not a failure.
        // BootstrapFilter forwards every request to the install wizard while
        // the provider reports not bootstrapped, exactly as today.
        if (!WebloggerStartup.isPrepared() && !ittest) {

            log.info("\n--------------------------------------------------------------"
                    + "\nRoller Weblogger startup INCOMPLETE, user interaction required"
                    + "\n--------------------------------------------------------------");

        } else {
            if (ittest) {
                try {
                    WebloggerStartup.createDatabase();
                } catch (StartupException e) {
                    // Old code let this one propagate out of
                    // contextInitialized (wrapped as a plain
                    // RuntimeException) rather than logging and continuing
                    // -- a broken IT-test bootstrap should stop startup.
                    throw new IllegalStateException("Failed to create database to IT testing", e);
                }
            }

            Weblogger weblogger = null;
            try {
                // The provider bean bootstraps against the Boot application
                // context, which already imports WebloggerBeanConfig via
                // component scan (scanBasePackages =
                // "com.wikantik.pressroom"), so controllers and the
                // business tier share a single Spring context. bootstrap()
                // also runs initialize() and releases its own session.
                webloggerProvider.bootstrap();
                weblogger = webloggerProvider.getWeblogger();

                // Velocity is initialised only once the tier is up: the engine
                // carries the facade for the two Roller resource loaders, and
                // nothing renders a Velocity template before BootstrapFilter
                // lets requests past (the install wizard is JSP).
                initializeVelocity(weblogger);

                // I2: site.absoluteurl becomes required the moment any
                // weblog has a custom domain (see needsSiteAbsoluteUrlWarning's
                // javadoc for the mechanism) -- warn once at startup rather
                // than relying solely on ControlPlaneHostFilter's own
                // warning, which fires only once a control-plane request
                // actually arrives on a custom domain, by which point
                // InitFilter may already have latched the wrong host.
                if (needsSiteAbsoluteUrlWarning(weblogger,
                        WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))) {
                    log.warn("At least one weblog has a custom domain but site.absoluteurl is "
                            + "unset -- every weblog WITHOUT a custom domain will inherit "
                            + "whichever hostname the first request after boot happens to "
                            + "arrive on (InitFilter's latch) in its canonical url, og:url, "
                            + "feed id, sitemap, robots.txt and password-reset links. Set "
                            + "site.absoluteurl (Admin -> Global Config, or "
                            + "PRESSROOM_SITE_ABSOLUTEURL) to the site's own address.");
                }

            } catch (BootstrapException ex) {
                log.error("Roller Weblogger bootstrap failed", ex);
            } finally {
                if (weblogger != null) {
                    weblogger.release();
                }
            }
        }

        // The rememberMeEnabled attribute (Login.jsp reads it via
        // ${rememberMeEnabled}) is set whether or not the business tier
        // bootstrapped above, same as the old code's unconditional final block.
        servletContext.setAttribute("rememberMeEnabled", WebloggerConfig.getProperty("rememberme.enabled"));

        running = true;
    }

    /**
     * Build the Velocity engine with the bootstrapped facade.
     *
     * <p>Decision: log-and-continue on failure, matching what the old
     * {@code contextInitialized} did for {@code setupVelocity()}. A
     * {@code SmartLifecycle.start()} exception aborts the entire
     * {@code SpringApplication.run()}, a much bigger blast radius than the
     * old per-webapp listener failure ever had, and Velocity's own failure
     * mode here (a missing/malformed {@code /WEB-INF/velocity.properties})
     * doesn't warrant taking the whole deployment down -- rendering would be
     * broken either way, but the admin UI and every non-Velocity code path
     * stay usable for an operator to fix the config.
     */
    private void initializeVelocity(Weblogger weblogger) {
        try {
            PressroomVelocity.initialize(servletContext, weblogger);
        } catch (WebloggerException ex) {
            log.error("Error initializing the Velocity rendering engine", ex);
        }
    }

    /**
     * Resolve uploads/themes directories from the webapp context path, same
     * as the old {@code contextInitialized}'s early lines -- with one
     * change: {@code ServletContext.getRealPath("/")} works when exploded
     * (external Tomcat, {@code spring-boot:run}), but returns null under
     * {@code java -jar} (the executable WAR's nested-jar resources are never
     * extracted to a real path). The old code required an exploded WAR and
     * simply gave up (logged fatal, returned) when {@code getRealPath}
     * returned null; that's no longer acceptable now that {@code java -jar}
     * is the primary deployment mode, so archive mode instead requires
     * {@code themes.dir} (and by the same reasoning, {@code uploads.dir}) to
     * already be configured via {@code pressroom.properties}/
     * {@code pressroom.custom.config}. {@code WebloggerConfig.setUploadsDir}/
     * {@code setThemesDir} are placeholder-only no-ops -- they only replace
     * a literal {@code ${webapp.context}} value -- so a configured value
     * always wins over the webapp-context guess either way.
     */
    private void resolveDirectories() {
        String realPath = servletContext.getRealPath("/");
        if (realPath != null) {
            String resourcesPath = realPath.endsWith(File.separator)
                    ? realPath + "resources"
                    : realPath + File.separator + "resources";
            WebloggerConfig.setUploadsDir(resourcesPath);
            WebloggerConfig.setThemesDir(realPath + File.separator + "themes");
        } else {
            String themesDir = WebloggerConfig.getProperty("themes.dir");
            if (themesDir == null || themesDir.startsWith("${")) {
                throw new IllegalStateException(
                        "Running from an archive: themes.dir must be configured "
                        + "(pressroom.properties / pressroom.custom.config)");
            }
        }
    }

    @Override
    public void stop() {
        if (webloggerProvider.isBootstrapped()) {
            webloggerProvider.getWeblogger().shutdown();
        }
        // do we need a more generic mechanism for presentation layer shutdown?
        CacheManager.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * True when {@code site.absoluteurl} is unset (or blank) AND at least
     * one weblog already has a custom domain -- the condition {@link
     * #start()} warns about once at startup (I2). Pure decision logic,
     * package-private so it can be unit tested directly with the already-
     * resolved config value as a parameter, rather than needing to drive
     * {@link #start()} end to end or capture log output to prove it.
     *
     * <p>A failure reading the weblog list (no schema yet, a transient
     * database error) is treated as "nothing to warn about" -- this check
     * exists to help an operator, not to become a second way startup can
     * fail.
     */
    static boolean needsSiteAbsoluteUrlWarning(Weblogger weblogger, String configuredSiteAbsoluteUrl) {
        if (configuredSiteAbsoluteUrl != null && !configuredSiteAbsoluteUrl.isBlank()) {
            return false;
        }
        try {
            return weblogger.getWeblogManager()
                    .getWeblogs(null, null, null, null, 0, -1).stream()
                    .anyMatch(w -> w.getCustomDomain() != null);
        } catch (WebloggerException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Explicit phase 0 -- see the class javadoc. {@code SmartLifecycle}'s
     * own default ({@link SmartLifecycle#DEFAULT_PHASE}) is
     * {@code Integer.MAX_VALUE}, which is *higher* than Boot's
     * {@code WebServerStartStopLifecycle} phase
     * ({@code Integer.MAX_VALUE - 2048}); leaving the default in place would
     * start Roller after the connector opens and stop it before the
     * connector closes. Phase 0 fixes both.
     */
    @Override
    public int getPhase() {
        return 0;
    }
}
