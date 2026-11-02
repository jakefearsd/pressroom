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
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The discovery traffic source is the analytics cache, and nothing else. */
class CachedTrafficSourceTest {

    /**
     * An analytics manager that answers only the two reads discovery may make
     * and fails the test on any other call: sync, reconcile and requestSync
     * are the ones that reach Umami, and discovery must never trigger them.
     */
    private static AnalyticsManager readOnlyManager(boolean configured, Map<String, Long> views,
                                                    List<Object> windows) {
        return (AnalyticsManager) Proxy.newProxyInstance(AnalyticsManager.class.getClassLoader(),
                new Class<?>[] {AnalyticsManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getStatus" -> new AnalyticsStatus(configured, null, 0, 0, List.of(), null, null);
                    case "entryViewsAllBlogs" -> {
                        windows.add(args[0]);
                        yield views;
                    }
                    default -> throw new AssertionError("discovery called AnalyticsManager." + method.getName());
                });
    }

    private static Weblogger tierWith(AnalyticsManager manager) {
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getAnalyticsManager()).thenReturn(manager);
        return weblogger;
    }

    @Test
    void scoresComeFromTheCache() throws WebloggerException {
        Map<String, Long> views = Map.of("e1", 40L, "e2", 2L);
        CachedTrafficSource source = new CachedTrafficSource(
                tierWith(readOnlyManager(true, views, new ArrayList<>())));

        assertEquals(views, source.viewsByEntry(30));
    }

    @Test
    void theWindowIsPassedThrough() throws WebloggerException {
        List<Object> windows = new ArrayList<>();
        CachedTrafficSource source = new CachedTrafficSource(
                tierWith(readOnlyManager(true, Map.of(), windows)));

        source.viewsByEntry(45);

        assertEquals(List.of(45), windows);
    }

    @Test
    void configuredFollowsAnalytics() {
        assertTrue(new CachedTrafficSource(tierWith(readOnlyManager(true, Map.of(), new ArrayList<>())))
                .isConfigured());
        assertFalse(new CachedTrafficSource(tierWith(readOnlyManager(false, Map.of(), new ArrayList<>())))
                .isConfigured());
    }
}
