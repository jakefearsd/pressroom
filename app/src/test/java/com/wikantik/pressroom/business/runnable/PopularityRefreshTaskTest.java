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
package com.wikantik.pressroom.business.runnable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link PopularityRefreshTask}: interval follows the runtime setting, run rebuilds the snapshot. */
class PopularityRefreshTaskTest {

    private PropertiesManager previouslyAttached;
    private Weblogger weblogger;
    private DiscoveryManager discovery;

    @BeforeEach
    void setUp() {
        previouslyAttached = WebloggerRuntimeConfig.attach(null);
        weblogger = mock(Weblogger.class);
        discovery = mock(DiscoveryManager.class);
        when(weblogger.getDiscoveryManager()).thenReturn(discovery);
    }

    @AfterEach
    void restore() {
        WebloggerRuntimeConfig.attach(previouslyAttached);
    }

    private static void storeRefreshSeconds(String value) throws WebloggerException {
        PropertiesManager manager = mock(PropertiesManager.class);
        when(manager.getProperty(DiscoverySettings.REFRESH_SECONDS))
                .thenReturn(new RuntimeConfigProperty(DiscoverySettings.REFRESH_SECONDS, value));
        WebloggerRuntimeConfig.attach(manager);
    }

    private PopularityRefreshTask task() throws Exception {
        PopularityRefreshTask task = new PopularityRefreshTask();
        task.init(weblogger, PopularityRefreshTask.NAME);
        return task;
    }

    @Test
    void intervalFollowsRuntimeSetting() throws Exception {
        PopularityRefreshTask task = task();
        storeRefreshSeconds("5400");
        assertEquals(90, task.getInterval());
        storeRefreshSeconds("90");
        assertEquals(2, task.getInterval(), "a part minute rounds up");
        storeRefreshSeconds("60");
        assertEquals(1, task.getInterval());
    }

    @Test
    void intervalFallsBackToTheDefaultWhenUnset() throws Exception {
        assertEquals(60, task().getInterval());
    }

    @Test
    void runCallsRefreshAndReleases() throws Exception {
        task().runTask();

        verify(discovery).refresh();
        verify(weblogger).release();
    }

    @Test
    void aFailingRefreshStillReleasesAndDoesNotPropagate() throws Exception {
        doThrow(new IllegalStateException("boom")).when(discovery).refresh();

        assertDoesNotThrow(() -> task().runTask());
        verify(weblogger).release();
    }

    @Test
    void taskIsEnabledByDefault() throws IOException {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/wikantik/pressroom/config/pressroom.properties")) {
            p.load(in);
        }
        assertTrue(java.util.List.of(p.getProperty("tasks.enabled").split("\\s*,\\s*")).contains("PopularityRefreshTask"));
        assertEquals(PopularityRefreshTask.class.getName(), p.getProperty("tasks.PopularityRefreshTask.class"));
        assertEquals("immediate", p.getProperty("tasks.PopularityRefreshTask.startTime"));
        assertEquals("10", p.getProperty("tasks.PopularityRefreshTask.leaseTime"));
    }
}
