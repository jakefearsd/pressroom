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

package com.wikantik.pressroom.ui.rendering.model;

import java.time.Clock;
import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Colophon;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;

/**
 * Model which provides access to application config data like site
 * config properties.
 */
public class ConfigModel implements Model {
    
    /** Template context name to be used for model */
    @Override
    public String getModelName() {
        return "config";
    }
    
    
    /**
     * The business-tier facade, handed in through {@code initData} (plan Task
     * 10). Only the build-info accessors read it; every site.* accessor goes
     * through {@code WebloggerRuntimeConfig}.
     */
    private Weblogger weblogger = null;

    /** Init model: needs the facade and nothing else -- no request, no url strategy. */
    @Override
    public void init(Map<String, Object> map) throws WebloggerException {
        weblogger = ModelLoader.requireWeblogger(map);
    }
    
    
    public String getSiteName() {
        return getProperty("site.name");
    }
    
    public String getSiteShortName() {
        return getProperty("site.shortName");
    }
    
    public String getSiteDescription() {
        return getProperty("site.description");
    }
    
    public String getSiteEmail() {
        return getProperty("site.adminemail");
    }
    
    public boolean getFeedHistoryEnabled() {
        return getBooleanProperty("site.newsfeeds.history.enabled");
    }
    
    public int getFeedSize() {
        return getIntProperty("site.newsfeeds.defaultEntries");
    }
    
    public int getFeedMaxSize() {
        return getIntProperty("site.newsfeeds.defaultEntries");
    }
    
    /** @deprecated Trackbacks have been removed. Always returns false. */
    @Deprecated
    public boolean getTrackbacksEnabled() {
        return false;
    }
    
    
    /** Get Roller version string */
    public String getPressroomVersion() {
        return weblogger.getVersion();
    }

    /**
     * The raster tile template {@code #showMapAssets} hands to Leaflet for
     * the {@code [map]} shortcode, e.g.
     * {@code https://tile.openstreetmap.org/{z}/{x}/{y}.png}.
     *
     * <p>Unlike every other accessor here this reads the <em>static</em>
     * config (pressroom.properties / the deployer's override file), not the
     * runtime properties table: a runtime property would need a
     * {@code configForm.*} message key that appears only in
     * runtimeConfigDefs.xml, which MessageKeyTest's orphan ratchet counts as
     * unused. Deployers who want their own tile server set
     * {@code travel.map.tileUrl} in their config file.
     */
    public String getMapTileUrl() {
        return WebloggerConfig.getProperty("travel.map.tileUrl");
    }

    /**
     * Where the reverse proxy mounts the Umami tracker on the blog's own
     * origin, e.g. {@code /analytics}. {@code #showAnalyticsTrackingCode}
     * builds the {@code <script src>} and {@code data-host-url} from this.
     *
     * <p>Same static-config precedent as {@link #getMapTileUrl}: this
     * describes the reverse proxy in front of the JVM, which does not change
     * while the JVM runs, so it is read from pressroom.properties /
     * pressroom-custom.properties rather than the hot runtime properties table.
     */
    public String getAnalyticsBasePath() {
        return WebloggerConfig.getProperty("analytics.umami.basePath");
    }

    /**
     * The tracker script's filename under {@link #getAnalyticsBasePath}, e.g.
     * {@code script.js}. Overridable for installations that renamed the
     * tracker (Umami's {@code UMAMI_SCRIPT_NAME}) to dodge content blockers.
     * Startup-scoped for the same reason as {@link #getAnalyticsBasePath}.
     */
    public String getAnalyticsScriptName() {
        return WebloggerConfig.getProperty("analytics.umami.scriptName");
    }

    /**
     * The footer's facts for a blog page: pass {@code $model.weblog} (the
     * template wrapper) or a raw {@link Weblog}; null gives the site-level
     * colophon. Never throws.
     */
    public Colophon getColophon(Object weblog) {
        Weblog pojo = null;
        if (weblog instanceof WeblogWrapper wrapper) {
            pojo = wrapper.getPojo();
        } else if (weblog instanceof Weblog w) {
            pojo = w;
        }
        return Colophon.of(weblogger, pojo, Clock.systemUTC());
    }

    private String getProperty(String name) {
        return WebloggerRuntimeConfig.getProperty(name);
    }
    
    
    private int getIntProperty(String name) {
        return WebloggerRuntimeConfig.getIntProperty(name);
    }
    
    
    private boolean getBooleanProperty(String name) {
        return WebloggerRuntimeConfig.getBooleanProperty(name);
    }
    
}

