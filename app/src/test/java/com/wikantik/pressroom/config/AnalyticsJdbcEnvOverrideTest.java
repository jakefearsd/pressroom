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

/**
 * The Umami reader's keys must exist in {@code pressroom.properties} in
 * camelCase, or {@code PRESSROOM_ANALYTICS_JDBC_CONNECTIONURL} would set a
 * lower-case key the reader never looks up.
 */
public class AnalyticsJdbcEnvOverrideTest {

    @Test
    public void environmentVariablesAddressTheCamelCaseKeys() throws Exception {
        Properties config = new Properties();
        try (InputStream in = WebloggerConfig.class.getResourceAsStream("pressroom.properties")) {
            assertNotNull(in);
            config.load(in);
        }
        WebloggerConfig.applyEnvironmentOverrides(config, Map.of(
                "PRESSROOM_ANALYTICS_JDBC_CONNECTIONURL", "jdbc:postgresql://h/umami",
                "PRESSROOM_ANALYTICS_JDBC_USERNAME", "u",
                "PRESSROOM_ANALYTICS_JDBC_PASSWORD", "p"));
        assertEquals("jdbc:postgresql://h/umami", config.getProperty("analytics.jdbc.connectionURL"));
        assertEquals("u", config.getProperty("analytics.jdbc.username"));
        assertEquals("p", config.getProperty("analytics.jdbc.password"));
        assertEquals(null, config.getProperty("analytics.jdbc.connectionurl"));
    }
}
