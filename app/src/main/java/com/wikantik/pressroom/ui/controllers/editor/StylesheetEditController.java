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


import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.ThemeStylesheets;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Action which handles editing for a weblog stylesheet override template.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class StylesheetEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(StylesheetEditController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "stylesheetEdit";
    }

    @Override
    public String getPageTitle() {
        return "stylesheetEdit.title";
    }

    @GetMapping("/stylesheetEdit.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        WeblogTemplate template = loadTemplate(request);
        model.addAttribute("template", template);
        model.addAttribute("sharedThemeStylesheet", isSharedThemeStylesheet(request));
        model.addAttribute("customTheme", isCustomTheme(request));
        model.addAttribute("sharedTheme", !isCustomTheme(request));

        if (template != null) {
            try {
                model.addAttribute("contentsStandard", ThemeStylesheets.contents(template));
            } catch (WebloggerException e) {
                log.error("Error loading template renditions for stylesheet", e);
            }
        }
        return ".StylesheetEdit";
    }

    @PostMapping("/stylesheetEdit!copyStylesheet.rol")
    public String copyStylesheet(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        Weblog weblog = getActionWeblog(request);
        try {
            // A theme without a stylesheet used to fall through to an NPE; it is a generic error here.
            if (ThemeStylesheets.sharedThemeHasStylesheet(weblogger, weblog)) {
                if (ThemeStylesheets.copyFromShared(weblogger, weblog)) {
                    addMessage(model, "stylesheetEdit.create.success", request);
                }
                addMessage(model, "stylesheetEdit.revert.success", request);
            } else {
                log.error("The weblog's theme has no stylesheet to copy");
                addError(model, "generic.error.check.logs", request);
            }
        } catch (WebloggerException ex) {
            log.error("Error copying the shared stylesheet", ex);
            addError(model, "generic.error.check.logs", request);
        }
        return execute(request, model);
    }

    @PostMapping("/stylesheetEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "contentsStandard", required = false) String contentsStandard) {
        populateCommonModel(request, model);

        WeblogTemplate template = loadTemplate(request);

        if (!hasErrors(model) && template != null) {
            try {
                ThemeStylesheets.save(weblogger, template, contentsStandard);
                addMessage(model, "stylesheetEdit.save.success", request);
            } catch (WebloggerException ex) {
                log.error("Error updating stylesheet template", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        model.addAttribute("template", template);
        model.addAttribute("contentsStandard", contentsStandard);
        model.addAttribute("sharedThemeStylesheet", isSharedThemeStylesheet(request));
        model.addAttribute("customTheme", isCustomTheme(request));
        model.addAttribute("sharedTheme", !isCustomTheme(request));
        return ".StylesheetEdit";
    }

    @PostMapping("/stylesheetEdit!revert.rol")
    public String revert(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        // loadTemplate returns null when the weblog has no stylesheet override (or the shared theme has no
        // stylesheet at all): nothing to revert, and no null to dereference.
        WeblogTemplate template = loadTemplate(request);
        if (template != null && !isCustomTheme(request) && !hasErrors(model)) {
            try {
                ThemeStylesheets.revert(weblogger, getActionWeblog(request), template);
                addMessage(model, "stylesheetEdit.revert.success", request);
            } catch (WebloggerException ex) {
                log.error("Error updating stylesheet template", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        return execute(request, model);
    }

    @PostMapping("/stylesheetEdit!delete.rol")
    public String delete(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        WeblogTemplate template = loadTemplate(request);
        if (template != null && !isCustomTheme(request) && !hasErrors(model)) {
            try {
                ThemeStylesheets.remove(weblogger, getActionWeblog(request), template);
                addMessage(model, "stylesheetEdit.default.success", request);
                template = null;
            } catch (Exception e) {
                log.error("Error deleting stylesheet template", e);
                addError(model, "generic.error.check.logs", request);
            }
        }

        model.addAttribute("template", template);
        model.addAttribute("sharedThemeStylesheet", isSharedThemeStylesheet(request));
        model.addAttribute("customTheme", isCustomTheme(request));
        model.addAttribute("sharedTheme", !isCustomTheme(request));
        return ".StylesheetEdit";
    }

    private WeblogTemplate loadTemplate(HttpServletRequest request) {
        try {
            return ThemeStylesheets.find(weblogger, getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error looking up stylesheet on weblog - {}", getActionWeblog(request).getHandle(), ex);
        }
        return null;
    }

    private boolean isCustomTheme(HttpServletRequest request) {
        return ThemeStylesheets.isCustomTheme(getActionWeblog(request));
    }

    private boolean isSharedThemeStylesheet(HttpServletRequest request) {
        try {
            return ThemeStylesheets.sharedThemeHasStylesheet(weblogger, getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error checking shared theme stylesheet", ex);
        }
        return false;
    }
}
