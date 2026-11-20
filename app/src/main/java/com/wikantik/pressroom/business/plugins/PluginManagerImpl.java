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
import com.wikantik.pressroom.pojos.Weblog;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.util.Reflection;


/**
 * Plugin management for business layer and more generally applied plugins.
 */
public class PluginManagerImpl implements PluginManager {
    
    private static final Logger log = LoggerFactory.getLogger(PluginManagerImpl.class);
    
    // Plugin classes keyed by plugin name
    // Per instance, filled by the constructor and only read after: a static
    // map filled from a constructor was written by every manager built (each
    // bootstrap, each test) while an older one could be reading it.
    private final Map<String, Class<? extends WeblogEntryPlugin>> mPagePlugins = new LinkedHashMap<>();


    public PluginManagerImpl() {
        // load weblog entry plugins
        loadPagePluginClasses();
    }

    /**
     * Kept so {@code WebloggerBeanConfig} still compiles; the expander is
     * unused now that rendering lives only in {@code EntryRenderer}.
     *
     * @param expander ignored
     */
    public PluginManagerImpl(ShortcodeExpander expander) {
        this();
    }

    @Override
    public boolean hasPagePlugins() {
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
    
    /**
     * Initialize PagePlugins declared in pressroom.properties.
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
