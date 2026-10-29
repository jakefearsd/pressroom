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

import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two directory settings the web container may fill in at start-up, and
 * the guard that keeps it from overwriting a directory an operator chose.
 */
class WebloggerConfigDirectoriesTest {

    private static final String WEBAPP = "${webapp.context}";

    @Test
    void theContainerFillsInTheUploadsAndThemesDirsOnlyWhenTheyAreLeftAtTheWebappPlaceholder() {
        try (var _ = StaticConfigOverride.set("uploads.dir", WEBAPP);
             var _ = StaticConfigOverride.set("themes.dir", WEBAPP)) {
            WebloggerConfig.setUploadsDir("/srv/uploads");
            WebloggerConfig.setThemesDir("/srv/themes");

            assertEquals("/srv/uploads", WebloggerConfig.getProperty("uploads.dir"));
            assertEquals("/srv/themes", WebloggerConfig.getProperty("themes.dir"));
        }
    }

    @Test
    void aDirectoryTheOperatorConfiguredIsNeverReplacedByTheContainersGuess() {
        try (var _ = StaticConfigOverride.set("uploads.dir", "/data/uploads");
             var _ = StaticConfigOverride.set("themes.dir", "/data/themes")) {
            WebloggerConfig.setUploadsDir("/srv/uploads");
            WebloggerConfig.setThemesDir("/srv/themes");

            assertEquals("/data/uploads", WebloggerConfig.getProperty("uploads.dir"));
            assertEquals("/data/themes", WebloggerConfig.getProperty("themes.dir"));
        }
    }

    @Test
    void theKeysListsEverySettingIncludingOnesAddedAtRuntime() {
        try (var _ = StaticConfigOverride.set("qa.config.probe", "1")) {
            assertTrue(Collections.list(WebloggerConfig.keys()).contains("qa.config.probe"));
        }
    }
}
