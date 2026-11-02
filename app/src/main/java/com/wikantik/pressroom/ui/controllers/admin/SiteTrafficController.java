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
package com.wikantik.pressroom.ui.controllers.admin;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.analytics.SiteTraffic;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The server admin's Traffic page: one row per blog, a total for views and
 * visits (never visitors), and the site's top posts and sources. It reads the
 * local snapshots only; no request here calls Umami.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class SiteTrafficController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(SiteTrafficController.class);

    private Clock clock = Clock.systemUTC();

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "siteTraffic";
    }

    @Override
    public String getPageTitle() {
        return "siteTraffic.title";
    }

    /** For tests: the clock the age of a stale snapshot is measured against. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The site space's time zone, as the rest of the feature reads it; UTC when it has none. */
    private ZoneId siteZone() {
        try {
            Weblog siteSpace = weblogger.getWeblogManager().getWeblogByHandle(Weblog.SITE_SPACE_HANDLE);
            if (siteSpace != null && siteSpace.getTimeZone() != null) {
                return siteSpace.getTimeZoneInstance().toZoneId();
            }
        } catch (WebloggerException ex) {
            log.warn("Could not read the site space's time zone; showing UTC", ex);
        }
        return ZoneOffset.UTC;
    }

    @GetMapping("/siteTraffic.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(name = "period", required = false, defaultValue = "30d") String period) {
        populateCommonModel(request, model);
        String shown = TrafficView.periodOf(period);
        SiteTraffic site = weblogger.getAnalyticsManager().getSiteTraffic(shown);
        model.addAttribute("traffic", SiteTrafficView.of(site, shown, request.getLocale(),
                siteZone(), clock.instant()));
        return ".SiteTraffic";
    }
}
