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

package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterion 25 (spec 2026-10-13): every message key the agent-accounts work
 * adds is in all eight bundles. MessageKeyTest checks only the base bundle
 * and ApplicationResourcesTest runs nothing, so without this a translation
 * silently falls back to English -- or, for a key a later task forgets,
 * to nothing it can be checked against.
 */
class AgentAccountMessagesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static final List<String> PREFIXES =
            List.of("userList.", "personForm.", "userWeblogs.", "apiTokens.", "agent.");

    private static final Set<String> NAMED_KEYS = Set.of("tabbedmenu.admin.apiTokens");

    private static final List<String> TRANSLATIONS = List.of("de", "es", "fr", "ja", "ko", "ru", "zh_CN");

    private static Properties load(String fileName) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(RESOURCES.resolve(fileName))) {
            props.load(in);
        }
        return props;
    }

    @Test
    void everyAgentAccountsKeyIsInAllEightBundles() throws IOException {
        Properties base = load("ApplicationResources.properties");
        Set<String> keys = new TreeSet<>();
        for (String key : base.stringPropertyNames()) {
            if (NAMED_KEYS.contains(key) || PREFIXES.stream().anyMatch(key::startsWith)) {
                keys.add(key);
            }
        }
        assertTrue(keys.size() >= 20,
                "found only " + keys + " -- the scan is not looking where it thinks it is");

        List<String> missing = new ArrayList<>();
        for (String language : TRANSLATIONS) {
            Properties translation = load("ApplicationResources_" + language + ".properties");
            for (String key : keys) {
                if (!translation.containsKey(key)) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys missing from a translation:\n  " + String.join("\n  ", missing));
    }
}
