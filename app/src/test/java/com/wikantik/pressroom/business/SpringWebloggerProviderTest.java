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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringWebloggerProviderTest {

    /**
     * A throwaway provider's {@code bootstrap()} attaches its manager to
     * {@code WebloggerRuntimeConfig} (plan Task 19) -- the one residual static
     * -- so the tests below that bootstrap providers over mock contexts put
     * back whatever the JVM had, or a later database-backed test reads runtime
     * config from a mock. (There is no provider static to restore any more:
     * the locator is gone, and a throwaway provider cannot poison anything
     * else.)
     */
    private RuntimeConfigAttachment previouslyAttached;

    @BeforeEach
    void rememberAttachedRuntimeConfig() throws Exception {
        // The suite tier first: two tests below bootstrap it, and preserving
        // before that would save "nothing attached" and restore it afterwards.
        TestUtils.setupWeblogger();
        previouslyAttached = RuntimeConfigAttachment.preserve();
    }

    @AfterEach
    void restoreAttachedRuntimeConfig() {
        previouslyAttached.close();
    }

    /**
     * Whatever order the JVM runs classes in, this one leaves the suite's tier
     * attached. Run first, it is the class that bootstraps that tier, and the
     * Release run of v0.2.4 (39787178605) showed what restoring "nothing
     * attached" did: every later database-backed test read runtime config as
     * null -- uploads disabled, revision retention 0 -- for 82 failures and 85
     * errors, none of them in this class.
     */
    @AfterAll
    static void leavesTheSuiteTierAttached() throws Exception {
        PropertiesManager attached = WebloggerRuntimeConfig.attach(null);
        WebloggerRuntimeConfig.attach(attached);
        assertSame(TestUtils.weblogger().getPropertiesManager(), attached,
                "the suite tier's properties manager must still answer runtime config");
    }

    // ------------------------------------------------ the bean contract (D2)

    @Test
    void isNotBootstrappedAndRefusesToHandOutAWebloggerBeforeBootstrap() {
        SpringWebloggerProvider provider = new SpringWebloggerProvider(mock(ApplicationContext.class));

        assertFalse(provider.isBootstrapped());
        assertThrows(IllegalStateException.class, provider::getWeblogger,
                "a provider that has not bootstrapped must throw, not return null or build the graph");
    }

    @Test
    void bootstrapRefusesUntilTheApplicationIsPrepared() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        SpringWebloggerProvider provider = new SpringWebloggerProvider(ctx);

        try (MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {
            startup.when(WebloggerStartup::isPrepared).thenReturn(false);

            assertThrows(IllegalStateException.class, provider::bootstrap,
                    "the prepare-before-construct guard that used to live in the static locator's "
                            + "bootstrap must survive in the provider");
            assertFalse(provider.isBootstrapped());
        }
    }

    @Test
    void bootstrapInitializesOnceReleasesAndReportsBootstrapped() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(Weblogger.class)).thenReturn(weblogger);
        SpringWebloggerProvider provider = new SpringWebloggerProvider(ctx);

        try (MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);

            provider.bootstrap();
            provider.bootstrap();   // idempotent: a repeat must not initialize again

            assertTrue(provider.isBootstrapped());
            assertSame(weblogger, provider.getWeblogger());
            var order = inOrder(weblogger);
            order.verify(weblogger, times(1)).initialize();
            order.verify(weblogger, times(1)).release();
        }
    }

    @Test
    void anInitializeFailureStillReleasesAndSurfacesAsBootstrapException() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        doThrow(new InitializationException("boom")).when(weblogger).initialize();
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(Weblogger.class)).thenReturn(weblogger);
        SpringWebloggerProvider provider = new SpringWebloggerProvider(ctx);

        try (MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);

            assertThrows(BootstrapException.class, provider::bootstrap);
            verify(weblogger).release();
            // Same shape as before: the tier IS bootstrapped (the graph exists and
            // BootstrapFilter lets requests through) even though initialize() failed.
            assertTrue(provider.isBootstrapped());
        }
    }

    @Test
    void aNullWebloggerBeanIsABootstrapFailure() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(Weblogger.class)).thenReturn(null);
        SpringWebloggerProvider provider = new SpringWebloggerProvider(ctx);

        try (MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);

            assertThrows(BootstrapException.class, provider::bootstrap);
            assertFalse(provider.isBootstrapped());
        }
    }

    @Test
    void bootstrapReusesAnExistingApplicationContextInsteadOfBuildingOne() throws Exception {
        // The two-arg constructor exists precisely so the webapp's root Spring
        // context (which already imports WebloggerBeanConfig) is reused rather
        // than a second, independent context being built and left orphaned.
        Weblogger fakeWeblogger = mock(Weblogger.class);
        ApplicationContext existingContext = mock(ApplicationContext.class);
        when(existingContext.getBean(Weblogger.class)).thenReturn(fakeWeblogger);

        SpringWebloggerProvider provider = new SpringWebloggerProvider(existingContext);
        try (MockedStatic<WebloggerStartup> startup = mockStatic(WebloggerStartup.class)) {
            startup.when(WebloggerStartup::isPrepared).thenReturn(true);
            provider.bootstrap();
        }

        assertSame(fakeWeblogger, provider.getWeblogger(),
                "bootstrap() must resolve the Weblogger bean from the supplied context, "
                        + "not build a new self-owned one");
    }

    // -------------------------------------------------- the real graph

    /**
     * The two tests below exercise the provider the suite itself bootstrapped
     * ({@code TestUtils.setupWeblogger()}) rather than building a second
     * standalone context: since {@code bootstrap()} now also runs
     * {@code initialize()}, a second graph would start a second task-scheduler
     * thread and open a second Lucene index on the same directory for the rest
     * of the JVM. The no-arg constructor's own-context path is what
     * {@code TestUtils} drives on every run, so it is covered regardless.
     */
    private static SpringWebloggerProvider suiteProvider() throws Exception {
        TestUtils.setupWeblogger();
        return TestUtils.provider();
    }

    @Test
    void bootstrapBuildsTheFullGraphWithSingletons() throws Exception {
        SpringWebloggerProvider provider = suiteProvider();
        assertTrue(provider.isBootstrapped());
        Weblogger weblogger = provider.getWeblogger();

        assertNotNull(weblogger.getWeblogManager());
        assertNotNull(weblogger.getWeblogEntryManager());
        assertNotNull(weblogger.getUserManager());
        assertNotNull(weblogger.getMediaFileManager());
        assertNotNull(weblogger.getIndexManager());
        assertNotNull(weblogger.getThemeManager());
        assertNotNull(weblogger.getPluginManager());
        assertNotNull(weblogger.getThreadManager());
        assertNotNull(weblogger.getPropertiesManager());
        assertNotNull(weblogger.getUrlStrategy());
        // the circular edge: the manager's Weblogger proxy must resolve back
        // to the same singleton graph (same manager instance both ways)
        assertSame(weblogger.getWeblogManager(), provider.getWeblogger().getWeblogManager());
    }

    @Test
    void repeatBootstrapIsIdempotent() throws Exception {
        SpringWebloggerProvider provider = suiteProvider();
        Weblogger first = provider.getWeblogger();

        // a second bootstrap() call must not build a second (leaked) context,
        // nor initialize the tier again
        provider.bootstrap();
        Weblogger second = provider.getWeblogger();

        assertSame(first, second);
    }
}
