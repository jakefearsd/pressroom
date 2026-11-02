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
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.business.analytics.SiteTraffic;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView;
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

    private Clock clock = Clock.systemDefaultZone();

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

    /** For tests: the clock (and so the zone) the age and the as-of time are shown in. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/siteTraffic.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(name = "period", required = false, defaultValue = "30d") String period) {
        populateCommonModel(request, model);
        String shown = TrafficView.periodOf(period);
        SiteTraffic site = weblogger.getAnalyticsManager().getSiteTraffic(shown);
        model.addAttribute("traffic", SiteTrafficView.of(site, shown, request.getLocale(),
                clock.getZone(), clock.instant()));
        return ".SiteTraffic";
    }
}
