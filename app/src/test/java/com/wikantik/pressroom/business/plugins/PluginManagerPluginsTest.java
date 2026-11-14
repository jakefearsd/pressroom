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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.plugins.entry.WeblogEntryPlugin;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Loading configured page plugins and applying them. The registry belongs to
 * the manager that loaded it; it was once a static map, filled by every
 * manager constructed and cleared by none, so a plugin one test configured
 * rewrote every later render in the JVM.
 */
class PluginManagerPluginsTest {

    /** Public with a public no-arg constructor: {@code Reflection.newInstance} needs both. */
    public static final class ShoutPlugin implements WeblogEntryPlugin {
        @Override public String getName() { return "Shout"; }
        @Override public String getDescription() { return "upper-cases"; }
        @Override public void init(Weblog weblog) { }
        @Override public String render(WeblogEntry entry, String str) { return str.toUpperCase(Locale.ROOT); }
    }

    /** Fails to initialise for a weblog: it must be left out, not break the others. */
    public static final class BrokenPlugin implements WeblogEntryPlugin {
        @Override public String getName() { return "Broken"; }
        @Override public String getDescription() { return "never initialises"; }
        @Override public void init(Weblog weblog) throws WebloggerException { throw new WebloggerException("no"); }
        @Override public String render(WeblogEntry entry, String str) { return "BROKEN"; }
    }

    private static ShortcodeExpander passThrough() {
        ShortcodeExpander expander = mock(ShortcodeExpander.class);
        when(expander.expand(any(), any())).thenAnswer(call -> call.getArgument(1));
        return expander;
    }

    @Test
    void onlyClassesThatArePluginsAreRegisteredAndAppliedInTheirOutput() {
        String configured = String.join(",",
                ShoutPlugin.class.getName(),
                BrokenPlugin.class.getName(),
                "java.lang.StringBuilder",
                "no.such.Plugin");
        try (var _ = StaticConfigOverride.set("plugins.page", configured)) {
            PluginManagerImpl manager = new PluginManagerImpl(passThrough());

            assertTrue(manager.hasPagePlugins());
            Map<String, WeblogEntryPlugin> plugins = manager.getWeblogEntryPlugins(new Weblog());
            assertEquals(List.of("Shout"), List.copyOf(plugins.keySet()),
                    "a plugin that fails init is left out; a non-plugin class is never registered");

            String rendered = manager.applyWeblogEntryPlugins(plugins, new WeblogEntry(), "hello");
            assertTrue(rendered.contains("HELLO"), "got: " + rendered);
        }
    }

    /** A manager built without plugins configured has none, whatever an earlier one loaded. */
    @Test
    void aManagerSeesOnlyThePluginsItLoaded() {
        try (var _ = StaticConfigOverride.set("plugins.page", ShoutPlugin.class.getName())) {
            assertTrue(new PluginManagerImpl(passThrough()).hasPagePlugins());
        }

        assertFalse(new PluginManagerImpl(passThrough()).hasPagePlugins(),
                "the plugin an earlier manager loaded leaked into this one");
    }

    @Test
    void withNoPluginsTheTextStillGoesThroughMarkdown() {
        PluginManagerImpl manager = new PluginManagerImpl(passThrough());
        assertFalse(manager.hasPagePlugins());

        String rendered = manager.applyWeblogEntryPlugins(Map.of(), new WeblogEntry(), "*em*");

        assertTrue(rendered.contains("<em>em</em>"), "got: " + rendered);
    }
}
