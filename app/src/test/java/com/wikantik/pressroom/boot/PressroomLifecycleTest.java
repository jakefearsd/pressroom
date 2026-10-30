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
package com.wikantik.pressroom.boot;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.ServletContext;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BootstrapException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.startup.StartupException;
import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link PressroomLifecycle}'s branching without a live servlet container
 * or database: {@code start()}'s {@code resolveDirectories()} step, its
 * StartupException/incomplete-installation/ittest/bootstrap-success/
 * bootstrap-failure branches, and {@code stop()} in both the "nothing to shut
 * down" and "shut down a bootstrapped Weblogger" shapes.
 *
 * <p>Every {@code start()}-exercising test here mock-installs
 * {@link WebloggerStartup} and {@link WebloggerConfig} so no real database or
 * classpath config is touched, and the business tier is a mock
 * {@link WebloggerProvider} handed to the constructor -- the lifecycle never
 * touches JVM-wide static state any more, so nothing here needs bracketing or
 * restoring.
 *
 * <p>Full end-to-end startup against a real application context and database
 * is exercised live instead (see the Task 2/2b reports: both a
 * {@code spring-boot:run} exploded run and a {@code java -jar} run) -- not
 * appropriate for a plain unit test in this suite.
 */
class PressroomLifecycleTest {

    /**
     * Boot's own {@code WebServerStartStopLifecycle} phase, {@code Integer.MAX_VALUE - 2048}.
     * Verified via {@code javap -c} against
     * {@code org.springframework.boot.web.server.servlet.context.WebServerStartStopLifecycle#getPhase()}
     * in {@code spring-boot-web-server-4.1.0.jar}. {@code PressroomLifecycle} must
     * report a phase strictly below this constant so that
     * {@code DefaultLifecycleProcessor} (which starts phases in ascending order
     * and stops them in descending order) starts Pressroom before the connector
     * opens and stops Pressroom after the connector closes. Note that
     * {@code SmartLifecycle}'s own default phase
     * ({@link org.springframework.context.SmartLifecycle#DEFAULT_PHASE}, plain
     * {@code Integer.MAX_VALUE}) is *above* this constant -- relying on the
     * default would invert both orderings, which is exactly the bug this test
     * guards against.
     */
    private static final int WEB_SERVER_START_STOP_LIFECYCLE_PHASE = Integer.MAX_VALUE - 2048;

    private WebloggerProvider provider;
    private Weblogger weblogger;
    private ServletContext servletContext;
    private PressroomLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        provider = mock(WebloggerProvider.class);
        weblogger = mock(Weblogger.class);
        servletContext = mock(ServletContext.class);
        lifecycle = new PressroomLifecycle(provider, servletContext);
    }

    /** Makes the mock provider behave like one whose {@code bootstrap()} succeeded. */
    private void providerBootstraps() throws BootstrapException {
        doAnswer(inv -> {
            when(provider.isBootstrapped()).thenReturn(true);
            when(provider.getWeblogger()).thenReturn(weblogger);
            return null;
        }).when(provider).bootstrap();
    }

    @Test
    void phaseStartsBeforeAndStopsAfterTheWebServerLifecycle() {
        assertTrue(lifecycle.getPhase() < WEB_SERVER_START_STOP_LIFECYCLE_PHASE,
                "PressroomLifecycle's phase must be below WebServerStartStopLifecycle's ("
                        + WEB_SERVER_START_STOP_LIFECYCLE_PHASE
                        + ") so it starts before the connector opens and stops after it closes");
    }

    @Test
    void notRunningUntilStarted() {
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void stopIsSafeWhenStartWasNeverCalled() {
        assertFalse(lifecycle.isRunning());
        assertDoesNotThrow(() -> lifecycle.stop());
        assertFalse(lifecycle.isRunning());
        verify(provider, never()).getWeblogger();
    }

    @Test
    void stopShutsDownTheBootstrappedWeblogger() {
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);

        lifecycle.stop();

        verify(weblogger).shutdown();
        assertFalse(lifecycle.isRunning());
    }

    // ------------------------------------------------- resolveDirectories()

    @Test
    void startResolvesUploadsAndThemesDirsFromRealPathThenStopsEarlyWhenPrepareFails() {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenThrow(new StartupException("db unreachable"));

            lifecycle.start();

            config.verify(() -> WebloggerConfig.setUploadsDir(
                    contains("/tmp/pressroom-lifecycle-test/resources")));
            config.verify(() -> WebloggerConfig.setThemesDir(
                    contains("/tmp/pressroom-lifecycle-test/themes")));
            assertTrue(lifecycle.isRunning(),
                    "start() marks itself running even when prepare() fails, matching the old "
                            + "contextInitialized behavior of leaving the servlet context up");
        }
    }

    @Test
    void startThrowsIllegalStateWhenArchiveModeThemesDirIsNotConfigured() {
        when(servletContext.getRealPath("/")).thenReturn(null);

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("themes.dir")).thenReturn(null);

            assertThrows(IllegalStateException.class, () -> lifecycle.start(),
                    "running from an archive (java -jar) with no themes.dir configured must fail "
                            + "fast rather than silently guess a webapp-context path that does not exist");
        }
    }

    // --------------------------------------------- installation state branches

    @Test
    void startLogsIncompleteAndSkipsBootstrapWhenNotPreparedAndNotIttest() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(false);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            lifecycle.start();

            verify(provider, never()).bootstrap();
            assertTrue(lifecycle.isRunning());
        }
    }

    // ------------------------------------------------------------- ittest

    @Test
    void startCreatesDatabaseThenBootstrapsWhenInstallationTypeIsIttest() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");
        providerBootstraps();

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            startup.when(WebloggerStartup::createDatabase).thenReturn(java.util.List.of());
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("ittest");

            lifecycle.start();

            startup.verify(WebloggerStartup::createDatabase);
            verify(provider).bootstrap();
            verify(weblogger).release();
            assertTrue(lifecycle.isRunning());
        }
    }

    @Test
    void startWrapsCreateDatabaseFailureDuringIttestAsIllegalState() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            startup.when(WebloggerStartup::createDatabase)
                    .thenThrow(new StartupException("could not create IT database"));
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("ittest");

            assertThrows(IllegalStateException.class, () -> lifecycle.start(),
                    "a broken IT-test bootstrap must stop startup rather than being logged and "
                            + "swallowed like the normal-deployment StartupException case");
            verify(provider, never()).bootstrap();
        }
    }

    // ------------------------------------------------------ bootstrap branch

    /**
     * The ordering invariant the whole provider design exists to preserve:
     * {@code WebloggerStartup.prepare()} strictly before
     * {@code WebloggerProvider.bootstrap()}, on the same thread, before the
     * connector opens (see the phase test above).
     */
    @Test
    void startPreparesThenBootstrapsThroughTheProvider() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");
        List<String> sequence = new ArrayList<>();
        doAnswer(inv -> {
            sequence.add("bootstrap");
            when(provider.isBootstrapped()).thenReturn(true);
            when(provider.getWeblogger()).thenReturn(weblogger);
            return null;
        }).when(provider).bootstrap();

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> {
                sequence.add("prepare");
                return null;
            });
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            lifecycle.start();

            assertEquals(List.of("prepare", "bootstrap"), sequence);
            // the lifecycle's own post-bootstrap work (the site.absoluteurl
            // check) opens a session it must release; the provider released
            // the bootstrapping session itself, inside bootstrap()
            verify(weblogger).release();
            assertTrue(lifecycle.isRunning());
        }
    }

    @Test
    void startSwallowsABootstrapExceptionFromTheProviderAndStaysUp() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");
        doThrow(new BootstrapException("boom")).when(provider).bootstrap();

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            assertDoesNotThrow(() -> lifecycle.start(),
                    "BootstrapException must be logged and swallowed, same as every other failure "
                            + "mode start() absorbs rather than aborting SpringApplication.run()");

            // nothing to release on this side: the provider never handed out a Weblogger
            verify(provider, never()).getWeblogger();
            assertTrue(lifecycle.isRunning());
        }
    }

    // ------------------------------------------- I2: site.absoluteurl warning

    /**
     * {@link PressroomLifecycle#needsSiteAbsoluteUrlWarning}, tested directly
     * rather than by driving {@code start()} and capturing log output: it is
     * pure decision logic (no logging itself), taking the already-resolved
     * {@code site.absoluteurl} value as a parameter rather than reading it
     * via the static {@code WebloggerRuntimeConfig} seam {@code start()}
     * itself uses -- the same reasoning that keeps {@code CustomDomainRules}
     * pure (see its own javadoc): resolving configuration is the caller's
     * job, deciding what to do with it is this method's.
     */
    @Test
    void needsSiteAbsoluteUrlWarningIsTrueWhenAWeblogHasADomainAndSiteAbsoluteUrlIsBlank() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        Weblog withDomain = new Weblog();
        withDomain.setCustomDomain("vhost.example.com");
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(withDomain));

        assertTrue(PressroomLifecycle.needsSiteAbsoluteUrlWarning(weblogger, null));
        assertTrue(PressroomLifecycle.needsSiteAbsoluteUrlWarning(weblogger, "   "));
    }

    @Test
    void needsSiteAbsoluteUrlWarningIsFalseWhenSiteAbsoluteUrlIsConfigured() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        Weblog withDomain = new Weblog();
        withDomain.setCustomDomain("vhost.example.com");
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(withDomain));

        assertFalse(PressroomLifecycle.needsSiteAbsoluteUrlWarning(
                weblogger, "https://blog.example.com"));
    }

    @Test
    void needsSiteAbsoluteUrlWarningIsFalseWhenNoWeblogHasACustomDomain() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenReturn(List.of(new Weblog()));

        assertFalse(PressroomLifecycle.needsSiteAbsoluteUrlWarning(weblogger, null));
    }

    /** A failed check must not itself abort or otherwise disrupt startup. */
    @Test
    void needsSiteAbsoluteUrlWarningIsFalseWhenTheWeblogQueryFails() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogs(null, null, null, null, 0, -1))
                .thenThrow(new WebloggerException("database down"));

        assertFalse(PressroomLifecycle.needsSiteAbsoluteUrlWarning(weblogger, null));
    }

    /**
     * The integration point: {@code start()} must call the check (and
     * therefore be ABLE to warn) once the business tier has bootstrapped,
     * without upsetting anything else {@code start()} already does. A plain
     * {@code mock(Weblogger.class)}'s {@code getWeblogManager()} returns
     * null here (unstubbed), which is exactly what proves the check's own
     * failure handling (see the test above) rather than the log-content
     * itself, which this suite does not capture -- see {@code
     * PressroomLifecycleTest}'s class javadoc for why a full log-driving test
     * belongs in the live end-to-end runs instead.
     */
    @Test
    void startChecksForCustomDomainsNeedingSiteAbsoluteUrlWithoutDisruptingBootstrap() throws Exception {
        when(servletContext.getRealPath("/")).thenReturn("/tmp/pressroom-lifecycle-test");
        providerBootstraps();

        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
                MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {

            startup.when(WebloggerStartup::prepare).thenAnswer(inv -> null);
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("installation.type")).thenReturn("manual");

            assertDoesNotThrow(() -> lifecycle.start(),
                    "the site.absoluteurl check must never itself abort startup, whatever it finds");

            verify(provider).bootstrap();
            verify(weblogger).release();
            assertTrue(lifecycle.isRunning());
        }
    }
}
