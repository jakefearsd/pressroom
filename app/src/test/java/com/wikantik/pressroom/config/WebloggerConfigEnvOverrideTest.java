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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ROLLER_* environment overlay -- the mechanism that lets the
 * production image take its whole configuration from .env, with no
 * bind-mounted properties file (see
 * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
 *
 * <p>The overlay is tested through an explicit Map rather than the real
 * environment because System.getenv() cannot be modified from a test and
 * WebloggerConfig does its work in a static initializer that runs once per
 * JVM.
 */
class WebloggerConfigEnvOverrideTest {

    private static Properties props(String... keysAndValues) {
        Properties p = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    private static Map<String, String> env(String... keysAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    @Test
    void environmentBeatsAValueAlreadyLoadedFromAFile() {
        Properties config = props("database.jdbc.username", "from-file");

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("ROLLER_DATABASE_JDBC_USERNAME", "from-env"));

        assertEquals("from-env", config.getProperty("database.jdbc.username"));
    }

    @Test
    void aKnownKeyKeepsItsOriginalSpelling() {
        // database.jdbc.driverClass is camelCase in roller.properties. A naive
        // lowercase mapping would create a SECOND key that nothing ever reads,
        // and the override would appear to do nothing.
        Properties config = props("database.jdbc.driverClass", "org.h2.Driver");

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("ROLLER_DATABASE_JDBC_DRIVERCLASS", "org.postgresql.Driver"));

        assertEquals("org.postgresql.Driver", config.getProperty("database.jdbc.driverClass"));
        assertNull(config.getProperty("database.jdbc.driverclass"),
                "must not create a lowercase twin of an existing key");
    }

    @Test
    void anUnknownKeyIsCreatedFromTheDerivedName() {
        // mail.port does not appear in roller.properties at all, and
        // uploads.dir is present only as a comment. An allowlist restricted to
        // keys with active defaults could not set either one.
        Properties config = new Properties();

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("ROLLER_MAIL_PORT", "587",
                            "ROLLER_UPLOADS_DIR", "/data/uploads"));

        assertEquals("587", config.getProperty("mail.port"));
        assertEquals("/data/uploads", config.getProperty("uploads.dir"));
    }

    @Test
    void variablesWithoutThePrefixAreIgnored() {
        Properties config = new Properties();

        WebloggerConfig.applyEnvironmentOverrides(
                config, env("POSTGRES_PASSWORD", "hunter2",
                            "PATH", "/usr/bin",
                            "ROLLER_", "empty-name"));

        assertTrue(config.isEmpty(),
                "only ROLLER_<something> variables may reach the configuration");
    }

    @Test
    void anEmptyValueIsAppliedRatherThanSkipped() {
        // Clearing mail.username is a legitimate thing to express in .env.
        Properties config = props("mail.username", "someone");

        WebloggerConfig.applyEnvironmentOverrides(config, env("ROLLER_MAIL_USERNAME", ""));

        assertEquals("", config.getProperty("mail.username"));
    }

    @Test
    void keysDifferingOnlyInCaseAreRejected() {
        Properties config = props("some.key", "a", "some.KEY", "b");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> WebloggerConfig.applyEnvironmentOverrides(config, env()));

        assertTrue(thrown.getMessage().contains("differ only in case"), thrown.getMessage());
    }

    @Test
    void secretValuesAreMaskedForLogging() {
        assertEquals("********", WebloggerConfig.maskSecret("database.jdbc.password", "hunter2"));
        assertEquals("********", WebloggerConfig.maskSecret("newsletter.listmonk.apitoken", "abc"));
        assertEquals("********", WebloggerConfig.maskSecret("some.SECRET.thing", "abc"));
        assertEquals("smtp.example.com", WebloggerConfig.maskSecret("mail.hostname", "smtp.example.com"));
        assertEquals("", WebloggerConfig.maskSecret("database.jdbc.password", ""));
    }
}
