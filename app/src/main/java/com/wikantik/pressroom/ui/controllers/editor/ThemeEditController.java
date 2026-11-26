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

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.SharedThemeRules;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Action for controlling theme selection.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class ThemeEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ThemeEditController.class);

    /** The site's theme is chosen here, among the four site themes ({@link #offered}). */
    @Override
    public boolean allowsSiteSpace() {
        return true;
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "themeEdit";
    }

    @Override
    public String getPageTitle() {
        return "themeEditor.title";
    }

    @GetMapping("/themeEdit.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        loadThemeData(request, model);

        if (WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {
            model.addAttribute("themeId", null);
            model.addAttribute("selectedThemeId", null);
            model.addAttribute("importTheme", false);
        } else {
            String themeId = currentThemeId(request);
            model.addAttribute("themeId", themeId);
            model.addAttribute("selectedThemeId", themeId);
        }
        model.addAttribute("currentThemeName", currentThemeName(request));

        return ".ThemeEdit";
    }

    @PostMapping("/themeEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @RequestParam(value = "themeType", required = false) String themeType,
                       @RequestParam(value = "selectedThemeId", required = false) String selectedThemeId,
                       @RequestParam(value = "importTheme", required = false, defaultValue = "false") boolean importTheme) {
        populateCommonModel(request, model);

        Weblog weblog = getActionWeblog(request);

        if (WeblogTheme.CUSTOM.equals(themeType)) {
            if (!importedForCustom(request, model, weblog, selectedThemeId, importTheme)) {
                loadThemeData(request, model);
                return ".ThemeEdit";
            }
            if (!hasErrors(model)) {
                enableCustomTheme(request, model, weblog);
            }
        } else if ("shared".equals(themeType)) {
            switchToSharedTheme(request, model, weblog, selectedThemeId);
        }

        loadThemeData(request, model);
        // Re-populate form
        if (WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())) {
            model.addAttribute("themeId", null);
            model.addAttribute("selectedThemeId", null);
        } else {
            String themeId = currentThemeId(request);
            model.addAttribute("themeId", themeId);
            model.addAttribute("selectedThemeId", themeId);
        }
        model.addAttribute("currentThemeName", currentThemeName(request));

        return ".ThemeEdit";
    }

    /**
     * The checks before a switch to a custom theme, and the import of the
     * chosen shared theme's templates when asked for. A refusal is an error
     * on the model. Returns false only when the import itself failed, which
     * ends the request at once.
     */
    private boolean importedForCustom(HttpServletRequest request, Model model, Weblog weblog,
            String selectedThemeId, boolean importTheme) {
        String importId = importTheme ? selectedThemeId : null;
        RuleViolation refusal = SharedThemeRules.customViolation(weblogger.getThemeManager(), weblog, importId, false);
        if (refusal != null) {
            addError(model, refusal.messageKey(), request);
        } else if (!StringUtils.isEmpty(importId)) {
            try {
                SharedTheme t = SharedThemeRules.importTemplates(weblogger, weblog, importId);
                addMessage(model, "themeEditor.setCustomTheme.success", t.getName(), request);
            } catch (Exception re) {
                log.error("Error customizing theme for weblog - {}", weblog.getHandle(), re);
                addError(model, "generic.error.check.logs", request);
                return false;
            }
        }
        return true;
    }

    private void enableCustomTheme(HttpServletRequest request, Model model, Weblog weblog) {
        try {
            SharedThemeRules.enableCustom(weblogger, weblog);
            // NOT themeEditor.setTheme.success, whose {0} is a theme NAME: this
            // branch used to feed it the WeblogTheme.CUSTOM storage constant, so
            // the banner read "Theme set to custom". Nor setCustomTheme.success,
            // which names the shared theme an import came from and has no meaning
            // on the path where nothing was imported.
            addMessage(model, "themeEditor.setCustomTheme.enabled", request);
            addMessage(model, "themeEditor.setCustomTheme.instructions", request);
        } catch (WebloggerException re) {
            log.error("Error saving weblog - {}", weblog.getHandle(), re);
            addError(model, "generic.error.check.logs", request);
        }
    }

    private void switchToSharedTheme(HttpServletRequest request, Model model, Weblog weblog,
            String selectedThemeId) {
        if (!offered(weblog, selectedThemeId, currentThemeId(request))) {
            // The picker leaves it out; this stops a form posted without the picker.
            addError(model, "themeEditor.error.notOffered", request);
            return;
        }
        Theme newTheme;
        try {
            newTheme = weblogger.getThemeManager().getTheme(selectedThemeId);
        } catch (Exception ex) {
            log.warn("Theme not found", ex);
            addError(model, "themeEditor.error.notFound", request);
            return;
        }
        if (hasErrors(model)) {
            return;
        }
        try {
            String originalTheme = weblog.getEditorTheme();
            SharedThemeRules.switchTo(weblogger.getWeblogManager(), weblog, selectedThemeId,
                    currentTheme(request));
            weblogger.getWeblogManager().saveWeblog(weblog);
            weblogger.flush();
            CacheManager.invalidate(weblog);

            if (!originalTheme.equals(selectedThemeId)) {
                addMessage(model, "themeEditor.setTheme.success", newTheme.getName(), request);
            }
        } catch (WebloggerException re) {
            log.error("Error saving weblog - {}", weblog.getHandle(), re);
            addError(model, "generic.error.check.logs", request);
        }
    }

    /** {@link SharedThemeRules#offered}, the rule the API's theme switch applies too. */
    private static boolean offered(Weblog weblog, String themeId, String currentThemeId) {
        return SharedThemeRules.offered(weblog, themeId, currentThemeId);
    }

    /**
     * The theme the action weblog renders with, or null when it cannot be
     * resolved -- the same fail-soft answer the entity's deleted
     * {@code getTheme()} gave, so a half-configured weblog still reaches the
     * form. Resolved through the injected facade, never located.
     */
    private WeblogTheme currentTheme(HttpServletRequest request) {
        return SharedThemeRules.currentTheme(weblogger.getThemeManager(), getActionWeblog(request));
    }

    /** The current theme's id; null when it cannot be resolved, like {@link #currentThemeName}. */
    private String currentThemeId(HttpServletRequest request) {
        WeblogTheme theme = currentTheme(request);
        return theme == null ? null : theme.getId();
    }

    /** What the form shows as "your current theme"; null when there is none to name. */
    private String currentThemeName(HttpServletRequest request) {
        WeblogTheme theme = currentTheme(request);
        return theme == null ? null : theme.getName();
    }

    private void loadThemeData(HttpServletRequest request, Model model) {
        ThemeManager themeMgr = weblogger.getThemeManager();
        Weblog weblog = getActionWeblog(request);
        String currentThemeId = WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())
                ? null : currentThemeId(request);
        List<SharedTheme> selectable = SharedThemeRules.offeredThemes(themeMgr, weblog, currentThemeId);
        model.addAttribute("themes", selectable);
        model.addAttribute("customTheme", WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme()));
        model.addAttribute("customThemeAllowed", SharedThemeRules.customThemeAllowed(getActionWeblog(request)));
        model.addAttribute("sharedThemeCustomStylesheet", isSharedThemeCustomStylesheet(request));

        // firstCustomization check
        try {
            model.addAttribute("firstCustomization",
                    weblogger.getWeblogManager()
                            .getTemplateByAction(getActionWeblog(request), ComponentType.WEBLOG) == null);
        } catch (WebloggerException ex) {
            log.error("Error looking up weblog template", ex);
        }
    }

    /**
     * Whether the page should say the shared theme's stylesheet was
     * customised. A display path: a lookup that fails reads as "no", and the
     * import itself asks {@link SharedThemeRules#hasCustomisedSharedStylesheet} again.
     */
    private boolean isSharedThemeCustomStylesheet(HttpServletRequest request) {
        try {
            return SharedThemeRules.hasCustomisedSharedStylesheet(weblogger, getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error looking up stylesheet on weblog - {}", getActionWeblog(request).getHandle(), ex);
            return false;
        }
    }
}
