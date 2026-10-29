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

import java.util.Collection;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;


/**
 * Page used to display Roller install instructions.
 */
@Controller
@RequestMapping("/roller-ui")
public class SetupController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);

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
        return "index.heading";
    }

    @GetMapping("/setup.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        try {
            WeblogManager mgr = weblogger.getWeblogManager();
            Collection<Weblog> weblogs = mgr.getWeblogs(true, null, null, null, 0, -1);
            model.addAttribute("weblogs", weblogs);
        } catch (WebloggerException ex) {
            log.error("Error getting weblogs", ex);
            addError(model, "frontpageConfig.weblogs.error", request);
        }

        try {
            long userCount = weblogger.getUserManager().getUserCount();
            long blogCount = weblogger.getWeblogManager().getWeblogCount();
            model.addAttribute("userCount", userCount);
            model.addAttribute("blogCount", blogCount);
        } catch (WebloggerException ex) {
            log.error("Error getting user/weblog counts", ex);
            model.addAttribute("userCount", 0L);
            model.addAttribute("blogCount", 0L);
        }

        // Reflect the currently stored front-page choice so the form below
        // shows what is actually configured -- otherwise a re-save silently
        // reverts it to whatever the <select> happens to default to.
        try {
            PropertiesManager mgr = weblogger.getPropertiesManager();
            RuntimeConfigProperty handleProp = mgr.getProperty("site.frontpage.weblog.handle");
            RuntimeConfigProperty aggregatedProp = mgr.getProperty("site.frontpage.weblog.aggregated");
            String frontpageWeblogHandle = handleProp != null ? handleProp.getValue() : null;
            boolean frontpageAggregated =
                    aggregatedProp != null && Boolean.parseBoolean(aggregatedProp.getValue());
            model.addAttribute("frontpageWeblogHandle", frontpageWeblogHandle);
            model.addAttribute("frontpageAggregated", frontpageAggregated);
        } catch (WebloggerException ex) {
            log.error("Error getting frontpage configuration", ex);
        }

        return ".Setup";
    }

    @PostMapping("/setup!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "frontpageBlog", required = false) String frontpageBlog,
                       @RequestParam(value = "aggregated", required = false) Boolean aggregated,
                       RedirectAttributes redirectAttributes) {

        populateCommonModel(request, model);

        PropertiesManager mgr = weblogger.getPropertiesManager();
        try {
            RuntimeConfigProperty frontpageBlogProp = mgr.getProperty("site.frontpage.weblog.handle");
            frontpageBlogProp.setValue(frontpageBlog);
            mgr.saveProperty(frontpageBlogProp);

            RuntimeConfigProperty aggregatedProp = mgr.getProperty("site.frontpage.weblog.aggregated");
            aggregatedProp.setValue(aggregated != null ? aggregated.toString() : "false");
            mgr.saveProperty(aggregatedProp);

            weblogger.flush();

            addFlashMessage(redirectAttributes, "frontpageConfig.values.saved", request);

        } catch (WebloggerException ex) {
            log.error("ERROR saving frontpage configuration", ex);
            addFlashError(redirectAttributes, "frontpageConfig.values.error", request);
        }

        return "redirect:/roller-ui/setup.rol";
    }
}
