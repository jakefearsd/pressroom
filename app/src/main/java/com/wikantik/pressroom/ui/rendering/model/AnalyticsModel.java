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

import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsHost;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;

/**
 * {@code $analytics}: what the showAnalyticsTrackingCode macro needs to build
 * the tracker tag, for the weblog it is given. Reads only the analytics
 * manager's in-memory registrations, so a render never waits on (or calls)
 * Umami, and keeps nothing per weblog: pages are cached by the render caches,
 * not here.
 */
public class AnalyticsModel implements Model {

    private Weblogger weblogger;

    @Override
    public String getModelName() {
        return "analytics";
    }

    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {
        weblogger = ModelLoader.requireWeblogger(initData);
    }

    /**
     * The website id to track this weblog under.
     *
     * @return the id, or null when analytics is not configured or the weblog is not registered
     */
    public String websiteId(WeblogWrapper weblog) {
        if (weblog == null || weblog.getId() == null) {
            return null;
        }
        AnalyticsManager manager = weblogger.getAnalyticsManager();
        if (!manager.getStatus().configured() || !manager.isRegistered(weblog.getId())) {
            return null;
        }
        String id = manager.websiteIdFor(weblog.getId());
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * The hostname the tracker should count: the weblog's custom domain, else
     * the site URL's host, lower-cased and without a port.
     *
     * @return the host, or null when none can be derived
     */
    public String trackedHost(WeblogWrapper weblog) {
        if (weblog == null) {
            return null;
        }
        return AnalyticsHost.of(weblog.getCustomDomain(), WebloggerRuntimeConfig.getAbsoluteContextURL());
    }
}
