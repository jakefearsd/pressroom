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
package com.wikantik.pressroom.business.plugins;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * {@code plugins.page} is unset in the test configuration (the registry is
 * presently empty in production too -- see CLAUDE.md's Plugin System
 * section), so {@link PluginManagerImpl#hasPagePlugins()} reports none
 * registered. {@code mPagePlugins} is a static final field initialized
 * inline and never reassigned, so it can never be null -- this pins that
 * {@code hasPagePlugins()} answers from the collection's emptiness alone.
 */
class PluginManagerImplTest {

    @Test
    void hasPagePluginsIsFalseWhenNoneAreRegistered() {
        PluginManagerImpl mgr = new PluginManagerImpl(mock(ShortcodeExpander.class));
        assertFalse(mgr.hasPagePlugins());
    }

    /**
     * Regression test for a swallowed exception: {@code loadPagePluginClasses()}
     * used to log {@code "unable to create {}"} with only the offending class
     * name, dropping the caught {@link ReflectiveOperationException} entirely.
     * Points {@code plugins.page} at a class that cannot possibly exist (via
     * reflection into {@link WebloggerConfig}'s backing {@code Properties} --
     * there is no public setter for an arbitrary key) so the constructor's
     * {@code Class.forName} lookup fails, captures the real log4j2 event, and
     * asserts the exception is attached -- {@code getThrown()} would be null
     * against the pre-fix call.
     */
    @Test
    void aReflectiveFailureLoadingAPagePluginIsLoggedWithTheException() throws Exception {
        Field configField = WebloggerConfig.class.getDeclaredField("config");
        configField.setAccessible(true);
        Properties config = (Properties) configField.get(null);
        String previous = config.getProperty("plugins.page");
        config.setProperty("plugins.page",
                "com.wikantik.pressroom.business.plugins.NoSuchPluginClassXYZ");

        List<LogEvent> captured = new ArrayList<>();
        Appender appender = new AbstractAppender("PluginManagerImplTest-capture", null, null, false,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                captured.add(event.toImmutable());
            }
        };
        appender.start();

        LoggerContext context = LoggerContext.getContext(false);
        LoggerConfig loggerConfig = context.getConfiguration()
                .getLoggerConfig(PluginManagerImpl.class.getName());
        loggerConfig.addAppender(appender, null, null);
        try {
            new PluginManagerImpl(mock(ShortcodeExpander.class));
        } finally {
            loggerConfig.removeAppender("PluginManagerImplTest-capture");
            appender.stop();
            if (previous == null) {
                config.remove("plugins.page");
            } else {
                config.setProperty("plugins.page", previous);
            }
        }

        List<LogEvent> errors = captured.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(1, errors.size(),
                "Expected exactly one ERROR line from the unresolvable plugin class name.");
        assertNotNull(errors.get(0).getThrown(),
                "The caught ReflectiveOperationException must be attached to the log record, "
                        + "not discarded.");
    }
}
