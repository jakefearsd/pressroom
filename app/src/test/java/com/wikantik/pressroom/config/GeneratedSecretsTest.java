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
package com.wikantik.pressroom.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.Level;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.Test;

/**
 * {@link GeneratedSecrets}: secrets the site makes for itself at first start
 * and keeps in {@code pressroom_properties}, out of the settings screens.
 */
class GeneratedSecretsTest {

    private static final String NAME = GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET;

    @Test
    void aMissingSecretIsGeneratedAndItsValueNeverLogged() {
        Map<String, RuntimeConfigProperty> props = new HashMap<>();
        try (SsoLogCapture logs = SsoLogCapture.everyLogger()) {
            GeneratedSecrets.seedMissing(props);

            String value = props.get(NAME).getValue();
            assertTrue(value.matches("[A-Za-z0-9_-]{43}"), "256 random bits: " + value);
            for (Level level : Level.values()) {
                for (String line : logs.lines(level)) {
                    assertFalse(line.contains(value), line);
                }
            }
            assertTrue(logs.lines(Level.INFO).stream().anyMatch(l -> l.contains(NAME)), "the name is logged");
        }
    }

    @Test
    void eachInstallationGetsItsOwnSecret() {
        Map<String, RuntimeConfigProperty> first = new HashMap<>();
        Map<String, RuntimeConfigProperty> second = new HashMap<>();
        GeneratedSecrets.seedMissing(first);
        GeneratedSecrets.seedMissing(second);

        assertNotEquals(first.get(NAME).getValue(), second.get(NAME).getValue());
    }

    @Test
    void aStoredSecretIsKeptAndABlankOneReplaced() {
        Map<String, RuntimeConfigProperty> props = new HashMap<>();
        props.put(NAME, new RuntimeConfigProperty(NAME, "kept-value"));
        GeneratedSecrets.seedMissing(props);
        assertEquals("kept-value", props.get(NAME).getValue(), "a restart must not invalidate every sent link");

        RuntimeConfigProperty blank = new RuntimeConfigProperty(NAME, "  ");
        props.put(NAME, blank);
        GeneratedSecrets.seedMissing(props);
        assertEquals(43, props.get(NAME).getValue().length());
        assertTrue(props.get(NAME) == blank, "the existing row is filled, not replaced");
    }

    /** A deployer's value (pressroom-custom.properties or the PRESSROOM_ variable) seeds the row instead. */
    @Test
    void aConfiguredValueSeedsTheRow() throws Exception {
        String previous = overrideStartupProperty(NAME, "deployer-chosen-secret");
        try {
            Map<String, RuntimeConfigProperty> props = new HashMap<>();
            GeneratedSecrets.seedMissing(props);
            assertEquals("deployer-chosen-secret", props.get(NAME).getValue());
        } finally {
            overrideStartupProperty(NAME, previous);
        }
    }

    /** Bootstrap runs the seeding: the started tier holds the row. */
    @Test
    void theStartedTierHoldsTheSecret() throws Exception {
        TestUtils.setupWeblogger();
        RuntimeConfigProperty row = TestUtils.weblogger().getPropertiesManager().getProperty(NAME);
        assertNotNull(row);
        assertFalse(row.getValue().isBlank());
    }

    /** The secret is not a setting: no settings screen or API lists it. */
    @Test
    void theSecretIsNotARuntimeSetting() {
        String defs = WebloggerRuntimeConfig.getRuntimeConfigDefsAsString();
        assertFalse(defs.contains(NAME), "runtimeConfigDefs.xml lists only settings an admin may see");
        assertEquals(List.of(NAME), GeneratedSecrets.names());
    }

    private static String overrideStartupProperty(String name, String value) throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Properties config = (Properties) field.get(null);
        String previous = config.getProperty(name);
        if (value == null) {
            config.remove(name);
        } else {
            config.setProperty(name, value);
        }
        return previous;
    }
}
