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

import java.util.Date;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.*;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.util.cache.CacheManager;
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
@RequestMapping("/roller-ui/authoring")
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
                if (template.getTemplateRendition(RenditionType.STANDARD) != null) {
                    model.addAttribute("contentsStandard",
                            template.getTemplateRendition(RenditionType.STANDARD).getTemplate());
                } else {
                    model.addAttribute("contentsStandard", "");
                }
            } catch (WebloggerException e) {
                log.error("Error loading template renditions for stylesheet", e);
            }
        }
        return ".StylesheetEdit";
    }

    @PostMapping("/stylesheetEdit!copyStylesheet.rol")
    public String copyStylesheet(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        WeblogManager weblogManager = weblogger.getWeblogManager();
        ThemeManager themeManager = weblogger.getThemeManager();

        ThemeTemplate stylesheet;
        try {
            SharedTheme themeName = themeManager.getTheme(getActionWeblog(request).getEditorTheme());
            stylesheet = themeName.getStylesheet();
        } catch (WebloggerException ex) {
            // Letting this fall through with stylesheet == null used to
            // guarantee an NPE a few lines below (stylesheet.getLink()) --
            // fail the same way the second try block already does instead.
            log.error("Error finding stylesheet in shared theme", ex);
            addError(model, "generic.error.check.logs", request);
            return revert(request, model);
        }

        try {
            WeblogTemplate tmpl = weblogManager.getTemplateByLink(getActionWeblog(request), stylesheet.getLink());

            if (tmpl == null) {
                WeblogTemplate stylesheetTmpl = new WeblogTemplate();
                stylesheetTmpl.setWeblog(getActionWeblog(request));
                stylesheetTmpl.setAction(ComponentType.STYLESHEET);
                stylesheetTmpl.setName(stylesheet.getName());
                stylesheetTmpl.setDescription(stylesheet.getDescription());
                stylesheetTmpl.setLink(stylesheet.getLink());
                stylesheetTmpl.setHidden(false);
                stylesheetTmpl.setNavbar(false);
                stylesheetTmpl.setLastModified(new Date());

                TemplateRendition sCode = stylesheet.getTemplateRendition(RenditionType.STANDARD);
                if (sCode != null) {
                    CustomTemplateRendition standardRendition = new CustomTemplateRendition(
                            stylesheetTmpl, RenditionType.STANDARD);
                    standardRendition.setTemplate(sCode.getTemplate());
                    standardRendition.setTemplateLanguage(sCode.getTemplateLanguage());
                    weblogManager.saveTemplateRendition(standardRendition);
                }

                weblogManager.saveTemplate(stylesheetTmpl);
                weblogger.flush();
                addMessage(model, "stylesheetEdit.create.success", request);
            }
        } catch (WebloggerException ex) {
            log.error("Error finding/adding stylesheet template", ex);
            addError(model, "generic.error.check.logs", request);
        }

        // Revert to reload from shared theme
        return revert(request, model);
    }

    @PostMapping("/stylesheetEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "contentsStandard", required = false) String contentsStandard) {
        populateCommonModel(request, model);

        WeblogManager weblogManager = weblogger.getWeblogManager();
        WeblogTemplate template = loadTemplate(request);

        if (!hasErrors(model) && template != null) {
            try {
                template.setLastModified(new Date());
                template.setAction(ComponentType.STYLESHEET);

                if (template.getTemplateRendition(RenditionType.STANDARD) != null) {
                    CustomTemplateRendition tc = template.getTemplateRendition(RenditionType.STANDARD);
                    tc.setTemplate(contentsStandard);
                    weblogManager.saveTemplateRendition(tc);
                } else {
                    // Store the submitted stylesheet, not an empty one: this is
                    // the branch taken the first time a weblog overrides its
                    // theme's stylesheet, and writing "" here discarded the
                    // user's CSS while still reporting success. The column is
                    // NOT NULL, hence the fallback for an absent field.
                    CustomTemplateRendition tc = new CustomTemplateRendition(template, RenditionType.STANDARD);
                    tc.setTemplate(contentsStandard == null ? "" : contentsStandard);
                    weblogManager.saveTemplateRendition(tc);
                }

                weblogManager.saveTemplate(template);
                weblogger.flush();
                CacheManager.invalidate(template);
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

        // loadTemplate returns null when the weblog has no stylesheet override
        // (or the shared theme has no stylesheet at all). Only WebloggerException
        // is caught below, so without this guard the null dereference on the
        // next line escapes as a 500. delete() already guards the same way.
        WeblogTemplate template = loadTemplate(request);
        if (template != null && !isCustomTheme(request) && !hasErrors(model)) {
            try {
                WeblogManager weblogManager = weblogger.getWeblogManager();
                ThemeManager tmgr = weblogger.getThemeManager();
                Theme theme = tmgr.getTheme(getActionWeblog(request).getEditorTheme());

                template.setLastModified(new Date());

                if (template.getTemplateRendition(RenditionType.STANDARD) != null) {
                    TemplateRendition templateCode = theme.getStylesheet().getTemplateRendition(RenditionType.STANDARD);
                    CustomTemplateRendition existingTemplateCode = template.getTemplateRendition(RenditionType.STANDARD);
                    existingTemplateCode.setTemplate(templateCode.getTemplate());
                    weblogManager.saveTemplateRendition(existingTemplateCode);
                }
                weblogManager.saveTemplate(template);
                weblogger.flush();
                CacheManager.invalidate(template);
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
                WeblogManager weblogManager = weblogger.getWeblogManager();
                weblogManager.removeTemplate(template);
                weblogManager.saveWeblog(getActionWeblog(request));
                CacheManager.invalidate(template);
                weblogger.flush();
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
            ThemeTemplate stylesheet;
            if (!isCustomTheme(request)) {
                ThemeManager themeManager = weblogger.getThemeManager();
                SharedTheme themeName = themeManager.getTheme(getActionWeblog(request).getEditorTheme());
                stylesheet = themeName.getStylesheet();
            } else {
                stylesheet = weblogger.getThemeManager().getTheme(getActionWeblog(request)).getStylesheet();
            }
            if (stylesheet != null) {
                return weblogger.getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request), stylesheet.getLink());
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up stylesheet on weblog - {}", getActionWeblog(request).getHandle(), ex);
        }
        return null;
    }

    private boolean isCustomTheme(HttpServletRequest request) {
        return WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme());
    }

    private boolean isSharedThemeStylesheet(HttpServletRequest request) {
        try {
            if (!isCustomTheme(request)) {
                ThemeManager themeManager = weblogger.getThemeManager();
                SharedTheme themeName = themeManager.getTheme(getActionWeblog(request).getEditorTheme());
                return themeName.getStylesheet() != null;
            }
        } catch (WebloggerException ex) {
            log.error("Error checking shared theme stylesheet", ex);
        }
        return false;
    }
}
