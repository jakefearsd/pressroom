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

import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A property read is logged at DEBUG, and a secret-looking key (a database
 * or analytics password, an API token) must never put its value in the log:
 * the value is masked as the startup dump masks it.
 */
class WebloggerConfigLoggingTest {

    private static final String SECRET_KEY = "database.jdbc.password";

    @Test
    void aSecretIsMaskedWhenFetched() {
        String secret;
        List<String> lines;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            secret = WebloggerConfig.getProperty(SECRET_KEY);
            WebloggerConfig.getProperty(SECRET_KEY, "fallback");
            lines = capture.lines(Level.DEBUG);
        }
        assertTrue(secret != null && !secret.isBlank(), "precondition: the test tier has a database password");

        List<String> fetches = lines.stream().filter(line -> line.contains(SECRET_KEY)).toList();
        assertTrue(fetches.size() >= 2, "both reads are still logged: " + lines);
        for (String line : fetches) {
            assertFalse(line.contains(secret), "the secret reached the log: " + line);
            assertTrue(line.contains("********"), "masked: " + line);
        }
    }

    @Test
    void anOrdinaryValueIsStillLogged() {
        List<String> lines;
        String value;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            value = WebloggerConfig.getProperty("site.discovery.missing.key", "plain-default");
            lines = capture.lines(Level.DEBUG);
        }
        assertTrue("plain-default".equals(value));
        assertTrue(lines.stream().anyMatch(line -> line.contains("site.discovery.missing.key")
                && line.contains("plain-default")), "a non-secret read is logged as it was: " + lines);
    }
}
