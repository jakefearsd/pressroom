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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The site's public home while no weblog is its front page: the site's
 * description and a list of its weblogs. index.jsp forwards {@code /} here
 * when {@code site.frontpage.weblog.handle} is empty; before, it forwarded to
 * the admin-only setup page, so every reader landed on the sign-in form.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class SiteDirectoryController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(SiteDirectoryController.class);

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "siteDirectory.title";
    }

    @GetMapping("/siteDirectory.rol")
    public String show(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        List<Weblog> weblogs;
        try {
            // Enabled, and active: "included in community listings".
            weblogs = weblogger.getWeblogManager()
                    .getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1).stream()
                    .sorted(Comparator.comparing(Weblog::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (WebloggerException ex) {
            // A display path: the home page still renders, just with no list.
            log.error("Error getting weblogs for the site directory", ex);
            weblogs = List.of();
        }
        model.addAttribute("weblogs", weblogs);
        // Only an admin is offered the way to pick a front-page weblog.
        User user = getAuthenticatedUser(request);
        model.addAttribute("userIsAdmin", user != null && isGlobalAdmin(user));
        return ".SiteDirectory";
    }
}
