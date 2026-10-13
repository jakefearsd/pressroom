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
package org.apache.roller.weblogger.business.themes;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.StaticConfigOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ThemeManagerImpl} refuses to start without a readable theme
 * directory: a blog server that cannot find its themes should fail at boot,
 * not render every page from nothing. Loading behaviour with real themes is
 * in {@code ThemeManagerTest}.
 */
class ThemeManagerStartupTest {

    @TempDir
    Path themes;

    private static ThemeManagerImpl manager() {
        return new ThemeManagerImpl(mock(Weblogger.class));
    }

    @Test
    void aMissingOrBlankThemeDirectorySettingStopsStartup() {
        try (var _ = StaticConfigOverride.set("themes.dir", null)) {
            RuntimeException e = assertThrows(RuntimeException.class, ThemeManagerStartupTest::manager);
            assertTrue(e.getMessage().contains("couldn't get themes directory"), e.getMessage());
        }
        try (var _ = StaticConfigOverride.set("themes.dir", "   ")) {
            assertThrows(RuntimeException.class, ThemeManagerStartupTest::manager);
        }
    }

    @Test
    void aThemeDirectoryThatDoesNotExistOrIsAFileStopsStartup() throws Exception {
        try (var _ = StaticConfigOverride.set("themes.dir", themes.resolve("nowhere").toString())) {
            RuntimeException e = assertThrows(RuntimeException.class, ThemeManagerStartupTest::manager);
            assertTrue(e.getMessage().contains("couldn't access theme dir"), e.getMessage());
        }
        Path file = Files.writeString(themes.resolve("not-a-dir"), "x");
        try (var _ = StaticConfigOverride.set("themes.dir", file.toString())) {
            assertThrows(RuntimeException.class, ThemeManagerStartupTest::manager);
        }
    }

    @Test
    void aTrailingSlashIsAcceptedAndAnEmptyDirectoryLoadsNoThemes() throws Exception {
        Files.createDirectory(themes.resolve(".hidden"));
        try (var _ = StaticConfigOverride.set("themes.dir", themes + File.separator)) {
            ThemeManagerImpl mgr = manager();
            mgr.initialize();

            assertEquals(0, mgr.getEnabledThemesList().size(),
                    "no theme folders (a dot-folder is not one) means no themes");
        }
    }
}
