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
package org.apache.roller.weblogger.config;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebloggerRuntimeConfig} reads through the {@link PropertiesManager}
 * the provider attached at bootstrap (spec Decision 8 of the 2026-08-22
 * static-service-locator plan) -- no longer through the static locator. The
 * pre-bootstrap contract is unchanged: with nothing attached every read
 * answers {@code null}, and the boolean/fallback readers fall through to the
 * static {@code WebloggerConfig} exactly as before.
 *
 * <p>The attachment is process-global (the one static this class deliberately
 * keeps until the configuration wave retires it), so each test saves what was
 * attached on entry and restores it on exit -- a DB-backed class that
 * bootstrapped the real tier earlier in the JVM must find its manager still
 * attached afterwards.
 */
class WebloggerRuntimeConfigTest {

    private PropertiesManager previouslyAttached;
    private String previouslyLatched;

    @BeforeEach
    void saveAttachment() {
        previouslyAttached = WebloggerRuntimeConfig.attach(null);
        // InitFilter's first-request latch is process-global too. With nothing
        // attached and no static value the reader answers the latch itself.
        try (StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", null)) {
            previouslyLatched = WebloggerRuntimeConfig.getAbsoluteContextURL();
        }
    }

    @AfterEach
    void restoreAttachment() {
        WebloggerRuntimeConfig.setAbsoluteContextURL(previouslyLatched);
        WebloggerRuntimeConfig.attach(previouslyAttached);
    }

    @Test
    void getRuntimeConfigDefsAsStringReturnsTheActualXmlFileContent() {
        String defs = WebloggerRuntimeConfig.getRuntimeConfigDefsAsString();

        assertTrue(defs.contains("<runtime-configs>"),
                "must be the real runtimeConfigDefs.xml, not the empty-string error fallback");
        assertTrue(defs.contains("site.name"),
                "must contain a known runtime property definition");
    }

    @Test
    void nothingAttachedMeansEveryReadAnswersNullAsBeforeBootstrap() {
        assertNull(WebloggerRuntimeConfig.getProperty("site.name"));
        assertEquals(-1, WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries"));
        assertFalse(WebloggerRuntimeConfig.getBooleanProperty("some.flag.nobody.defines"));
    }

    @Test
    void anAttachedManagerAnswersTheReads() throws WebloggerException {
        PropertiesManager manager = mock(PropertiesManager.class);
        when(manager.getProperty("site.name"))
                .thenReturn(new RuntimeConfigProperty("site.name", "Attached Site"));
        when(manager.getProperty("site.pages.maxEntries"))
                .thenReturn(new RuntimeConfigProperty("site.pages.maxEntries", "42"));
        when(manager.getProperty("some.flag"))
                .thenReturn(new RuntimeConfigProperty("some.flag", "true"));

        WebloggerRuntimeConfig.attach(manager);

        assertEquals("Attached Site", WebloggerRuntimeConfig.getProperty("site.name"));
        assertEquals(42, WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries"));
        assertTrue(WebloggerRuntimeConfig.getBooleanProperty("some.flag"));
    }

    @Test
    void aFailingManagerIsLoggedAndReadAsNull() throws WebloggerException {
        PropertiesManager manager = mock(PropertiesManager.class);
        when(manager.getProperty("site.name")).thenThrow(new WebloggerException("store down"));
        WebloggerRuntimeConfig.attach(manager);

        assertNull(WebloggerRuntimeConfig.getProperty("site.name"),
                "the broad catch that answered null before the attachment still answers null");
    }

    @Test
    void theBooleanReaderFallsBackToTheStaticConfigWhenTheRuntimeRowIsAbsent()
            throws WebloggerException {
        // groupblogging.enabled is true in roller.properties; an attached
        // manager that has no row for it must not mask that fallback.
        PropertiesManager manager = mock(PropertiesManager.class);
        when(manager.getProperty("groupblogging.enabled")).thenReturn(null);
        WebloggerRuntimeConfig.attach(manager);

        assertEquals(Boolean.parseBoolean(WebloggerConfig.getProperty("groupblogging.enabled")),
                WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"));
        assertEquals(WebloggerConfig.getProperty("groupblogging.enabled"),
                WebloggerRuntimeConfig.getPropertyWithConfigFallback("groupblogging.enabled"));
    }

    /**
     * {@code ROLLER_SITE_ABSOLUTEURL} lands in the static config, not the
     * database row, and the row's default is blank. The deployment that proved
     * the bug: the environment said {@code http://docker2.lan}, the Global
     * Config row was untouched, the first request after a restart arrived by
     * IP -- and robots.txt, canonical urls, feeds and password-reset links all
     * took the IP, because this reader consulted only the row and the latch.
     */
    @Test
    void anEnvironmentSiteUrlBeatsTheFirstRequestLatchWhenTheDatabaseRowIsBlank() {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.absoluteurl", "");
             StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", "http://docker2.lan")) {
            WebloggerRuntimeConfig.setAbsoluteContextURL("http://192.168.5.52");

            assertEquals("http://docker2.lan", WebloggerRuntimeConfig.getAbsoluteContextURL(),
                    "a configured ROLLER_SITE_ABSOLUTEURL must win over whichever host hit first");
        }
    }

    /** Before bootstrap (nothing attached) the environment value is still the site url. */
    @Test
    void anEnvironmentSiteUrlIsHonouredWithNoDatabaseAttached() {
        try (StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", "http://docker2.lan")) {
            WebloggerRuntimeConfig.setAbsoluteContextURL("http://192.168.5.52");

            assertEquals("http://docker2.lan", WebloggerRuntimeConfig.getAbsoluteContextURL());
        }
    }

    /**
     * Characterisation of the precedence the fix must keep: a non-blank
     * Global Config row (what Admin -> Global Config writes) still beats the
     * environment. Passes before and after the fix.
     */
    @Test
    void aNonBlankDatabaseRowStillBeatsTheEnvironment() {
        try (RuntimeConfigAttachment _ =
                     RuntimeConfigAttachment.answering("site.absoluteurl", "https://admin.example.com");
             StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", "http://docker2.lan")) {
            WebloggerRuntimeConfig.setAbsoluteContextURL("http://192.168.5.52");

            assertEquals("https://admin.example.com", WebloggerRuntimeConfig.getAbsoluteContextURL());
        }
    }

    /**
     * Every caller appends "/" plus a path, so a configured value typed with a
     * trailing slash doubled it. Production had {@code https://blog.wikantik.com/}
     * in Global Config, and Create Weblog previewed
     * {@code https://blog.wikantik.com//wikantik} while every permalink, feed
     * and sitemap entry carried the same {@code //} and answered 400.
     */
    @Test
    void aTrailingSlashOnTheConfiguredSiteUrlIsDropped() {
        try (RuntimeConfigAttachment _ =
                     RuntimeConfigAttachment.answering("site.absoluteurl", "https://blog.example.com/")) {
            assertEquals("https://blog.example.com", WebloggerRuntimeConfig.getAbsoluteContextURL());
        }
    }

    /** The environment route takes the same normalisation as the row. */
    @Test
    void aTrailingSlashOnTheEnvironmentSiteUrlIsDropped() {
        try (StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", "http://docker2.lan//")) {
            assertEquals("http://docker2.lan", WebloggerRuntimeConfig.getAbsoluteContextURL());
        }
    }

    /**
     * Characterisation: with neither the row nor the environment set (a blank
     * environment value trims to empty) the first-request latch is the last
     * resort, never an empty string. Passes before and after the fix.
     */
    @Test
    void withNeitherConfiguredTheFirstRequestLatchIsTheLastResort() {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering("site.absoluteurl", " ");
             StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", "  ")) {
            WebloggerRuntimeConfig.setAbsoluteContextURL("http://192.168.5.52");

            assertEquals("http://192.168.5.52", WebloggerRuntimeConfig.getAbsoluteContextURL());
        }
    }

    @Test
    void detachOfASpecificManagerClearsOnlyThatOne() throws WebloggerException {
        PropertiesManager mine = mock(PropertiesManager.class);
        PropertiesManager someoneElses = mock(PropertiesManager.class);
        when(someoneElses.getProperty("k")).thenReturn(new RuntimeConfigProperty("k", "theirs"));

        WebloggerRuntimeConfig.attach(someoneElses);
        assertFalse(WebloggerRuntimeConfig.detach(mine),
                "a tier that never attached must not clear another tier's attachment");
        assertEquals("theirs", WebloggerRuntimeConfig.getProperty("k"));
        assertFalse(WebloggerRuntimeConfig.detach(null));

        assertTrue(WebloggerRuntimeConfig.detach(someoneElses));
        assertNull(WebloggerRuntimeConfig.getProperty("k"));
    }

    @Test
    void attachReplacesAndReturnsThePreviousManagerAndDetachClears() throws WebloggerException {
        PropertiesManager first = mock(PropertiesManager.class);
        PropertiesManager second = mock(PropertiesManager.class);
        when(first.getProperty("k")).thenReturn(new RuntimeConfigProperty("k", "one"));
        when(second.getProperty("k")).thenReturn(new RuntimeConfigProperty("k", "two"));

        assertNull(WebloggerRuntimeConfig.attach(first), "nothing was attached on entry");
        assertEquals("one", WebloggerRuntimeConfig.getProperty("k"));

        assertSame(first, WebloggerRuntimeConfig.attach(second),
                "attach hands back what it replaced, so a fixture can restore it");
        assertEquals("two", WebloggerRuntimeConfig.getProperty("k"));

        WebloggerRuntimeConfig.detach();
        assertNull(WebloggerRuntimeConfig.getProperty("k"));
    }
}
