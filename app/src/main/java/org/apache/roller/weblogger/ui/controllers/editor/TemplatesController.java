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

package org.apache.roller.weblogger.ui.controllers.editor;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.pojos.*;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.cache.CacheManager;
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
@RequestMapping("/roller-ui/authoring")
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
                WeblogTemplate newTemplate = new WeblogTemplate();
                newTemplate.setWeblog(getActionWeblog(request));
                newTemplate.setAction(newTmplAction);
                newTemplate.setName(newTmplName);
                newTemplate.setHidden(false);
                newTemplate.setNavbar(false);
                newTemplate.setLastModified(new Date());

                if (ComponentType.CUSTOM.equals(newTmplAction)) {
                    newTemplate.setLink(newTmplName);
                }

                if (ComponentType.WEBLOG.equals(newTmplAction)) {
                    newTemplate.setName(WeblogTemplate.DEFAULT_PAGE);
                }

                weblogger.getWeblogManager().saveTemplate(newTemplate);

                CustomTemplateRendition standardRendition = new CustomTemplateRendition(newTemplate, RenditionType.STANDARD);
                standardRendition.setTemplate(getText("pageForm.newTemplateContent", request));
                standardRendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
                weblogger.getWeblogManager().saveTemplateRendition(standardRendition);

                if (WeblogTemplate.DEFAULT_PAGE.equals(newTemplate.getName())) {
                    weblogger.getWeblogManager().saveWeblog(getActionWeblog(request));
                }

                weblogger.flush();
                // Post/redirect/get: rendering the list straight from this POST
                // meant a reload re-submitted the add (and answered "already
                // exists" for the template it had just created).
                addFlashMessage(redirectAttributes, "pagesForm.added",
                        StringEscapeUtils.escapeHtml4(newTemplate.getName()), request);
                return "redirect:/roller-ui/authoring/templates.rol?weblog="
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
            if (!template.isRequired()
                    || !WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {

                WeblogManager mgr = weblogger.getWeblogManager();

                if (WeblogTemplate.DEFAULT_PAGE.equals(template.getName())) {
                    WeblogTheme theme = weblogger.getThemeManager().getTheme(getActionWeblog(request));
                    ThemeTemplate stylesheet = theme.getStylesheet();
                    if (stylesheet != null && theme.getStylesheet() != null
                            && stylesheet.getLink().equals(
                            theme.getStylesheet().getLink())) {
                        WeblogTemplate css = mgr.getTemplateByLink(getActionWeblog(request), stylesheet.getLink());
                        if (css != null) {
                            mgr.removeTemplate(css);
                        }
                    }
                }

                CacheManager.invalidate(template);
                mgr.removeTemplate(template);
                weblogger.flush();
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
            List<WeblogTemplate> raw = weblogger.getWeblogManager().getTemplates(getActionWeblog(request));
            List<WeblogTemplate> pages = new ArrayList<>(raw);

            WeblogTheme theme = weblogger.getThemeManager().getTheme(getActionWeblog(request));
            if (theme.getStylesheet() != null) {
                pages.remove(weblogger.getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request),
                                theme.getStylesheet().getLink()));
            }
            model.addAttribute("templates", pages);

            Map<ComponentType, String> actionsMap = new EnumMap<>(ComponentType.class);
            actionsMap.put(ComponentType.CUSTOM, ComponentType.CUSTOM.getReadableName());

            if (WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {
                actionsMap.put(ComponentType.PERMALINK, ComponentType.PERMALINK.getReadableName());
                actionsMap.put(ComponentType.SEARCH, ComponentType.SEARCH.getReadableName());
                actionsMap.put(ComponentType.WEBLOG, ComponentType.WEBLOG.getReadableName());
                actionsMap.put(ComponentType.TAGSINDEX, ComponentType.TAGSINDEX.getReadableName());

                for (WeblogTemplate tmpPage : pages) {
                    if (!ComponentType.CUSTOM.equals(tmpPage.getAction())) {
                        actionsMap.remove(tmpPage.getAction());
                    }
                }
            } else {
                actionsMap.put(ComponentType.WEBLOG, ComponentType.WEBLOG.getReadableName());
                for (WeblogTemplate tmpPage : pages) {
                    if (ComponentType.WEBLOG.equals(tmpPage.getAction())) {
                        actionsMap.remove(ComponentType.WEBLOG);
                        break;
                    }
                }
            }
            model.addAttribute("availableActions", actionsMap);
            model.addAttribute("customTheme", WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme()));

        } catch (WebloggerException ex) {
            log.error("Error getting templates for weblog - {}", getActionWeblog(request).getHandle(), ex);
            addError(model, "generic.error.check.logs", request);
        }
    }

    private void myValidate(String newTmplName, ComponentType newTmplAction,
                            HttpServletRequest request, Model model) {
        if (StringUtils.isEmpty(newTmplName)) {
            addFieldError(model, "newTmplName", "Template.error.nameNull", request);
        } else if (newTmplName.length() > RollerConstants.TEXTWIDTH_255) {
            addFieldError(model, "newTmplName", "Template.error.nameSize", request);
        }

        if (newTmplAction == null) {
            addFieldError(model, "newTmplAction", "Template.error.actionNull", request);
        }

        try {
            WeblogTemplate existingPage = weblogger.getWeblogManager()
                    .getTemplateByName(getActionWeblog(request), newTmplName);
            if (existingPage != null) {
                addFieldError(model, "newTmplName", "pagesForm.error.alreadyExists",
                        new Object[]{StringEscapeUtils.escapeHtml4(newTmplName)}, request);
            }
        } catch (WebloggerException ex) {
            // Fail closed: the caller adds this template only when no errors
            // were recorded, so swallowing here creates the duplicate the
            // check exists to prevent.
            log.error("Error checking for existing template", ex);
            addError(model, "generic.error.check.logs", request);
        }
    }
}
