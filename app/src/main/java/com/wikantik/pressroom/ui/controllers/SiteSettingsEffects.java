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

package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * What a stored save of site settings must do afterwards, for the Global
 * Config screen and {@code PATCH /api/v1/admin/config} alike (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 1). {@link
 * SiteSettingsRules} decides whether the save may happen; this runs once it
 * has, and been flushed.
 */
public final class SiteSettingsEffects {

    /**
     * The settings a rendered page's footer link, header or the front door
     * depend on: a change to any of them must expire every cached page.
     */
    public static final List<String> RENDERED_PAGE_INPUTS = List.of(SiteSettingsRules.PRIVACY_PAGE,
            SiteSettingsRules.FRONT_DOOR_BLOG, SiteSettingsRules.FRONT_DOOR_PAGE, "site.absoluteurl",
            "site.name", "site.shortName", "site.description", "site.verification.bing");

    private SiteSettingsEffects() {
    }

    /**
     * After {@code values} were stored over {@code before} (name to the value
     * each had; none reads as ""): a changed rendered-page input moves every
     * weblog's {@code lastModified}, the site space's included, and clears the
     * site-wide cache; then a changed discovery setting rebuilds the discovery
     * snapshot. A failure to touch the weblogs throws before the refresh.
     */
    public static void afterSave(Weblogger weblogger, Map<String, String> values, Map<String, String> before)
            throws WebloggerException {
        // Rendered pages expire only through weblog.lastModified, and a
        // settings save does not move it, so a changed privacy link would
        // stay out of every cached page.
        if (RENDERED_PAGE_INPUTS.stream().anyMatch(name -> SiteSettingsRules.changed(name, values, before))) {
            touchEveryWeblog(weblogger);
        }
        // The discovery snapshot holds the share and window it was built
        // with until the next scheduled refresh, up to an hour away.
        if (values.keySet().stream().anyMatch(name -> name.startsWith(DiscoverySettings.PREFIX)
                && SiteSettingsRules.changed(name, values, before))) {
            weblogger.getDiscoveryManager().refresh();
        }
    }

    private static void touchEveryWeblog(Weblogger weblogger) throws WebloggerException {
        WeblogManager mgr = weblogger.getWeblogManager();
        List<Weblog> touched = new ArrayList<>();
        // The site space is no weblog to the listing, yet its pages carry the
        // same footer and are the front door, so it is touched explicitly.
        List<Weblog> everything = new ArrayList<>(mgr.getWeblogs(null, null, null, null, 0, -1));
        Weblog site = mgr.getSiteSpace();
        if (site != null) {
            everything.add(site);
        }
        for (Weblog weblog : everything) {
            weblog.touchLastModified();
            mgr.saveWeblog(weblog);
            touched.add(weblog);
        }
        weblogger.flush();
        // SiteWideCache ignores lastModified and is dropped only here; one
        // call clears it wholesale.
        for (Weblog weblog : touched) {
            CacheManager.invalidate(weblog);
        }
    }
}
