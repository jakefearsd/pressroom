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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The three front-page discovery settings: defaults, display-safe reads and range checks. */
class DiscoverySettingsTest {

    private PropertiesManager previouslyAttached;

    @BeforeEach
    void detach() {
        previouslyAttached = WebloggerRuntimeConfig.attach(null);
    }

    @AfterEach
    void restore() {
        WebloggerRuntimeConfig.attach(previouslyAttached);
    }

    private static void store(String name, String value) throws WebloggerException {
        PropertiesManager manager = mock(PropertiesManager.class);
        when(manager.getProperty(name)).thenReturn(new RuntimeConfigProperty(name, value));
        WebloggerRuntimeConfig.attach(manager);
    }

    @Test
    void defaultsWhenUnset() {
        assertEquals(30, DiscoverySettings.windowDays());
        assertEquals(20, DiscoverySettings.sharePercent());
        assertEquals(3600, DiscoverySettings.refreshSeconds());
    }

    @Test
    void storedValuesInRangeAreUsed() throws WebloggerException {
        store(DiscoverySettings.SHARE_PERCENT, "0");
        assertEquals(0, DiscoverySettings.sharePercent(), "0 percent is a real choice, not a missing value");
    }

    @Test
    void outOfRangeStoredValueReadsAsDefault() throws WebloggerException {
        store(DiscoverySettings.SHARE_PERCENT, "150");
        assertEquals(20, DiscoverySettings.sharePercent());
        store(DiscoverySettings.WINDOW_DAYS, "junk");
        assertEquals(30, DiscoverySettings.windowDays());
        store(DiscoverySettings.REFRESH_SECONDS, "5");
        assertEquals(3600, DiscoverySettings.refreshSeconds());
    }

    @Test
    void rangeErrorBounds() {
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.WINDOW_DAYS, 0).isPresent());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.WINDOW_DAYS, 366).isPresent());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.WINDOW_DAYS, 1).isEmpty());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.WINDOW_DAYS, 365).isEmpty());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.SHARE_PERCENT, -1).isPresent());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.SHARE_PERCENT, 101).isPresent());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.SHARE_PERCENT, 0).isEmpty());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.SHARE_PERCENT, 100).isEmpty());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.REFRESH_SECONDS, 59).isPresent());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.REFRESH_SECONDS, 86401).isPresent());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.REFRESH_SECONDS, 60).isEmpty());
        assertTrue(DiscoverySettings.rangeError(DiscoverySettings.REFRESH_SECONDS, 86400).isEmpty());
    }

    @Test
    void rangeErrorIsAMessageKeyAndIgnoresOtherNames() {
        assertEquals("configForm.error.discoveryRange",
                DiscoverySettings.rangeError(DiscoverySettings.SHARE_PERCENT, 101).orElseThrow());
        assertTrue(DiscoverySettings.rangeError("site.pages.maxEntries", 999999).isEmpty());
    }

    @Test
    void boundsAreReportedForTheMessage() {
        assertEquals(1, DiscoverySettings.min(DiscoverySettings.WINDOW_DAYS));
        assertEquals(365, DiscoverySettings.max(DiscoverySettings.WINDOW_DAYS));
        assertEquals(60, DiscoverySettings.min(DiscoverySettings.REFRESH_SECONDS));
        assertEquals(86400, DiscoverySettings.max(DiscoverySettings.REFRESH_SECONDS));
    }
}
