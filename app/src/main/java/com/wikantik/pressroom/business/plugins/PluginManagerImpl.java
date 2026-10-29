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

package com.wikantik.pressroom.business.plugins;

import com.wikantik.pressroom.business.plugins.entry.WeblogEntryPlugin;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.Weblog;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MarkdownRenderer;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.util.HTMLSanitizer;
import com.wikantik.pressroom.util.Reflection;


/**
 * Plugin management for business layer and more generally applied plugins.
 */
public class PluginManagerImpl implements PluginManager {
    
    private static final Logger log = LoggerFactory.getLogger(PluginManagerImpl.class);
    
    // Plugin classes keyed by plugin name
    private static final Map<String, Class<? extends WeblogEntryPlugin>> mPagePlugins = new LinkedHashMap<>();


    private final ShortcodeExpander expander;

    /**
     * @param expander the shortcode registry the business render seam expands
     *                 with -- the same instance {@code EntryRenderer} uses, so
     *                 the two render paths cannot drift on which shortcodes
     *                 exist
     */
    public PluginManagerImpl(ShortcodeExpander expander) {
        this.expander = expander;
        // load weblog entry plugins
        loadPagePluginClasses();
    }
    
    
    @Override
    public boolean hasPagePlugins() {
        // mPagePlugins is a static final field initialized inline at
        // declaration and never reassigned (only ever mutated in place via
        // put()), so it can never be null -- the null check below was dead
        // on arrival, not merely mis-positioned relative to the size() call
        // above.
        log.debug("mPluginClasses.size(): {}", mPagePlugins.size());
        return !mPagePlugins.isEmpty();
    }
    
    
    /**
     * Create and init plugins for processing entries in a specified website.
     */
    @Override
    public Map<String, WeblogEntryPlugin> getWeblogEntryPlugins(Weblog website) {
        
        Map<String, WeblogEntryPlugin> ret = new LinkedHashMap<>();
        
        for (Class<? extends WeblogEntryPlugin> pluginClass : mPagePlugins.values()) {
            try {
                WeblogEntryPlugin plugin = Reflection.newInstance(pluginClass);
                plugin.init(website);
                ret.put(plugin.getName(), plugin);
            } catch (ReflectiveOperationException | WebloggerException e) {
                log.error("Unable to init() PagePlugin: ", e);
            }
        }
        return ret;
    }
    
    @Override
    public String applyWeblogEntryPlugins(Map<String, WeblogEntryPlugin> pagePlugins, WeblogEntry entry, String str) {

        String ret = str;

        // Per-entry opt-in died with weblogentry.plugins (V021, the entry
        // editor's last plugin checkbox). Every plugin the caller passes in
        // is now applied unconditionally, the same way shortcodes always
        // have been -- there is no more per-entry list to filter against. An
        // empty map (the only case left in production, since plugins.page is
        // no longer configured) is a first-class no-op, not an error.
        for (WeblogEntryPlugin pagePlugin : pagePlugins.values()) {
            ret = pagePlugin.render(entry, ret);
        }

        // Shortcodes are NOT opt-in the way named plugins are: they expand
        // unconditionally in every render path, before sanitization
        // (see docs/superpowers/plans/2026-08-01-stage2-wave1-media-seo.md).
        ret = expander.expand(entry, ret);

        // ...and markdown converts after them, so this seam matches
        // EntryRenderer exactly. Every entry is markdown; leaving one of
        // the two render seams behind is precisely the drift the shared
        // shortcode parsers exist to prevent.
        ret = MarkdownRenderer.render(ret);

        return HTMLSanitizer.conditionallySanitize(ret);
    }
    
    
    /**
     * Initialize PagePlugins declared in roller.properties.
     * By using the full class name we also allow for the implementation of
     * "external" Plugins (maybe even packaged seperately). These classes are
     * then later instantiated by PageHelper.
     */
    private void loadPagePluginClasses() {
        log.debug("Initializing page plugins");
        
        String pluginStr = WebloggerConfig.getProperty("plugins.page");
        log.debug(pluginStr);
        if (pluginStr != null) {
            String[] plugins = StringUtils.stripAll(StringUtils.split(pluginStr, ","));
            for (String plugin : plugins) {
                log.debug("try {}", plugin);
                try {
                    Class<?> clazz = Class.forName(plugin);
                    
                    if (Reflection.implementsInterface(clazz, WeblogEntryPlugin.class)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends WeblogEntryPlugin> pluginClass = (Class<? extends WeblogEntryPlugin>)clazz;
                        WeblogEntryPlugin weblogEntryPlugin = Reflection.newInstance(pluginClass);
                        mPagePlugins.put(weblogEntryPlugin.getName(), pluginClass);
                    } else {
                        log.warn("{} is not a PagePlugin", clazz);
                    }
                } catch (ReflectiveOperationException e) {
                    log.error("unable to create {}", plugin, e);
                }
            }
        }
    }

    @Override
    public void release() {
        // no op
    }
    
}
