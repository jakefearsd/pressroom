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

package com.wikantik.pressroom.ui.controllers.editor;

import java.time.Clock;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * A blog's Traffic page: headline numbers, a trend chart and report tabs,
 * read from the local snapshot only. No request here (or anywhere) calls
 * Umami; the numbers are as fresh as the last scheduled read, and the page
 * says how fresh that is.
 *
 * <p>Only blog admins may open it: {@link #requiredWeblogPermissionActions()}
 * is what {@code PressroomHandlerInterceptor} enforces, so a member who can
 * post but not administer is refused before this method runs.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class TrafficController extends BaseController {

    private static final String DASHBOARD_URL_KEY = "analytics.umami.dashboardUrl";

    private Clock clock = Clock.systemUTC();

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "traffic";
    }

    @Override
    public String getPageTitle() {
        return "traffic.title";
    }

    /** For tests: the clock the age of a stale snapshot is measured against. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/traffic.rol")
    public String execute(HttpServletRequest request, Model model,
                          @RequestParam(name = "period", required = false, defaultValue = "30d") String period,
                          @RequestParam(name = "tab", required = false, defaultValue = "posts") String tab,
                          @RequestParam(name = "sort", required = false, defaultValue = "views") String sort) {
        populateCommonModel(request, model);
        Weblog weblog = getActionWeblog(request);
        AnalyticsManager analytics = weblogger.getAnalyticsManager();

        String shownPeriod = TrafficView.periodOf(period);
        BlogTraffic traffic = analytics.getBlogTraffic(weblog.getId(), shownPeriod);
        if (traffic == null) {
            traffic = new BlogTraffic(TrafficState.NO_DATA, null, null, null, null, null, null, null);
        }
        String umami = TrafficView.umamiUrl(WebloggerConfig.getProperty(DASHBOARD_URL_KEY),
                analytics.websiteIdFor(weblog.getId()));
        model.addAttribute("traffic", TrafficView.of(traffic, shownPeriod, TrafficView.tabOf(tab),
                TrafficView.sortOf(sort), umami, request.getLocale(),
                weblog.getTimeZoneInstance().toZoneId(), clock.instant()));
        return ".Traffic";
    }
}
