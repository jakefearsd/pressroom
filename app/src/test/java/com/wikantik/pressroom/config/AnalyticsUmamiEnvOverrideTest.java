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
package com.wikantik.pressroom.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Characterisation pin, not new behaviour: the environment-override mechanism
 * is generic, so this is expected to pass at once. It records that the
 * camelCase {@code analytics.umami.*} keys exist in {@code pressroom.properties}
 * and that {@code PRESSROOM_ANALYTICS_UMAMI_APIURL} and friends reach them,
 * rather than setting a lower-case key the analytics client never reads.
 */
public class AnalyticsUmamiEnvOverrideTest {

    @Test
    public void environmentVariablesAddressTheCamelCaseKeys() throws Exception {
        Properties config = new Properties();
        try (InputStream in = WebloggerConfig.class.getResourceAsStream("pressroom.properties")) {
            assertNotNull(in);
            config.load(in);
        }
        WebloggerConfig.applyEnvironmentOverrides(config, Map.of(
                "PRESSROOM_ANALYTICS_UMAMI_APIURL", "http://umami:3000",
                "PRESSROOM_ANALYTICS_UMAMI_USERNAME", "svc",
                "PRESSROOM_ANALYTICS_UMAMI_PASSWORD", "p",
                "PRESSROOM_ANALYTICS_UMAMI_DASHBOARDURL", "https://stats.example.com"));
        assertEquals("http://umami:3000", config.getProperty("analytics.umami.apiUrl"));
        assertEquals("svc", config.getProperty("analytics.umami.username"));
        assertEquals("p", config.getProperty("analytics.umami.password"));
        assertEquals("https://stats.example.com", config.getProperty("analytics.umami.dashboardUrl"));
        assertNull(config.getProperty("analytics.umami.apiurl"));
        assertNull(config.getProperty("analytics.umami.dashboardurl"));
    }
}
