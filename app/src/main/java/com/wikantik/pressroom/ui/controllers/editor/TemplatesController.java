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

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.CustomTemplates;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Templates listing page.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class TemplatesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(TemplatesController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "templates";
    }

    @Override
    public String getPageTitle() {
        return "pagesForm.title";
    }

    @GetMapping("/templates.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadTemplatesList(request, model);
        return ".Templates";
    }

    @PostMapping("/templates!add.rol")
    public String add(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes,
                      @RequestParam(value = "newTmplName", required = false) String newTmplName,
                      @RequestParam(value = "newTmplAction", required = false) ComponentType newTmplAction) {
        populateCommonModel(request, model);

        myValidate(newTmplName, newTmplAction, request, model);

        if (!hasErrors(model)) {
            try {
                WeblogTemplate newTemplate = CustomTemplates.add(weblogger, getActionWeblog(request), newTmplName,
                        newTmplAction, getText("pageForm.newTemplateContent", request));
                // Post/redirect/get: rendering the list straight from this POST
                // meant a reload re-submitted the add (and answered "already
                // exists" for the template it had just created).
                addFlashMessage(redirectAttributes, "pagesForm.added",
                        StringEscapeUtils.escapeHtml4(newTemplate.getName()), request);
                return "redirect:/pressroom-ui/authoring/templates.rol?weblog="
                        + getActionWeblog(request).getHandle();

            } catch (WebloggerException ex) {
                log.error("Error adding new template for weblog - {}", getActionWeblog(request).getHandle(), ex);
                addError(model, "generic.error.check.logs", request);
            }
        }

        // Refused or failed: render in place with what the reader typed, so
        // the highlighted field holds their text rather than coming back empty.
        model.addAttribute("newTmplName", newTmplName);
        model.addAttribute("newTmplAction", newTmplAction);
        loadTemplatesList(request, model);
        return ".Templates";
    }

    @PostMapping("/templates!remove.rol")
    public String remove(HttpServletRequest request, Model model,
                         @RequestParam(value = "removeId", required = false) String removeId) {
        populateCommonModel(request, model);

        // Ownership-checked: removeId is client input, and getTemplate is a
        // global by-id lookup. Without this an editor on one weblog could
        // delete another weblog's theme templates -- and the isRequired()
        // guard below would be consulting the WRONG weblog's theme while
        // doing it.
        WeblogTemplate template = lookupTemplate(removeId, request);
        if (template == null) {
            log.warn("Refusing to delete template {}: not owned by weblog {}",
                    removeId, getActionWeblog(request).getHandle());
            // One cause, one banner. This used to report here AND fall through
            // to the generic arm at the bottom, stacking two errors on a screen
            // where the reader has no way to tell they describe the same thing.
            addError(model, "editPages.remove.error", request);
            loadTemplatesList(request, model);
            return ".Templates";
        }

        // The name has to be read before the row goes, or the success
        // message names an emptied object.
        String removedName = template.getName();
        try {
            if (CustomTemplates.remove(weblogger, getActionWeblog(request), template)) {
                addMessage(model, "pagesForm.removed", StringEscapeUtils.escapeHtml4(removedName), request);
            } else {
                addError(model, "editPages.remove.requiredTemplate", request);
            }
        } catch (Exception ex) {
            log.error("Error removing page - {}", removeId, ex);
            addError(model, "editPages.remove.error", request);
        }

        loadTemplatesList(request, model);
        return ".Templates";
    }

    private void loadTemplatesList(HttpServletRequest request, Model model) {
        try {
            List<WeblogTemplate> pages = CustomTemplates.pages(weblogger, getActionWeblog(request));
            model.addAttribute("templates", pages);
            model.addAttribute("availableActions", CustomTemplates.availableActions(getActionWeblog(request), pages));
            model.addAttribute("customTheme", WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme()));

        } catch (WebloggerException ex) {
            log.error("Error getting templates for weblog - {}", getActionWeblog(request).getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }
    }

    private void myValidate(String newTmplName, ComponentType newTmplAction,
                            HttpServletRequest request, Model model) {
        try {
            // Fail closed: the caller adds this template only when no errors were recorded, so a check that
            // could not run must not read as "no errors" and create the duplicate it exists to prevent.
            for (RuleViolation v : CustomTemplates.newProblems(weblogger, getActionWeblog(request), newTmplName,
                    newTmplAction)) {
                if (v.args().isEmpty()) {
                    addFieldError(model, fieldId(v), v.messageKey(), request);
                } else {
                    addFieldError(model, fieldId(v), v.messageKey(),
                            new Object[]{StringEscapeUtils.escapeHtml4(String.valueOf(v.args().get(0)))}, request);
                }
            }
        } catch (WebloggerException ex) {
            log.error("Error checking the new template", ex);
            addError(model, "generic.error.check.logs", request);
        }
    }

    /** The form's field id for the rule's API field name. */
    private static String fieldId(RuleViolation violation) {
        return CustomTemplates.NAME.equals(violation.field()) ? "newTmplName" : "newTmplAction";
    }
}
