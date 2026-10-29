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
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.controllers.BaseController;
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
@RequestMapping("/roller-ui/authoring")
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

        boolean sharedThemeCustomStylesheet = isSharedThemeCustomStylesheet(request);
        Weblog weblog = getActionWeblog(request);

        if (WeblogTheme.CUSTOM.equals(themeType)) {
            if (weblog.isSiteSpace()) {
                addError(model, "themeEditor.error.notOffered", request);
            } else if (!customThemeAllowed(request)) {
                addError(model, "themeEditor.error.customThemeNotAllowed", request);
            } else if (importTheme) {
                try {
                    ThemeManager themeMgr = weblogger.getThemeManager();
                    if (!StringUtils.isEmpty(selectedThemeId)) {
                        SharedTheme t = themeMgr.getTheme(selectedThemeId);
                        boolean skipStylesheet = sharedThemeCustomStylesheet
                                && selectedThemeId.equals(weblog.getEditorTheme());
                        themeMgr.importTheme(getActionWeblog(request), t, skipStylesheet);
                        addMessage(model, "themeEditor.setCustomTheme.success", t.getName(), request);
                    }
                } catch (Exception re) {
                    log.error("Error customizing theme for weblog - {}", weblog.getHandle(), re);
                    addError(model, "generic.error.check.logs", request);
                    loadThemeData(request, model);
                    return ".ThemeEdit";
                }
            }

            if (!hasErrors(model)) {
                try {
                    weblog.setEditorTheme(WeblogTheme.CUSTOM);
                    weblogger.getWeblogManager().saveWeblog(weblog);
                    weblogger.flush();
                    CacheManager.invalidate(weblog);
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

        } else if ("shared".equals(themeType)) {
            Theme newTheme = null;
            if (offered(weblog, selectedThemeId, currentThemeId(request))) {
                try {
                    ThemeManager themeMgr = weblogger.getThemeManager();
                    newTheme = themeMgr.getTheme(selectedThemeId);
                } catch (Exception ex) {
                    log.warn("Theme not found", ex);
                    addError(model, "themeEditor.error.notFound", request);
                }
            } else {
                // The picker leaves it out; this stops a form posted without the picker.
                addError(model, "themeEditor.error.notOffered", request);
            }

            if (!hasErrors(model)) {
                try {
                    String originalTheme = weblog.getEditorTheme();
                    WeblogManager mgr = weblogger.getWeblogManager();

                    WeblogTheme theme = currentTheme(request);
                    if (!originalTheme.equals(selectedThemeId) && theme != null && theme.getStylesheet() != null) {
                        WeblogTemplate stylesheet = mgr.getTemplateByAction(getActionWeblog(request), ComponentType.STYLESHEET);
                        if (stylesheet != null) {
                            mgr.removeTemplate(stylesheet);
                        }
                    }

                    weblog.setEditorTheme(selectedThemeId);
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
     * Whether this weblog may adopt a custom theme.
     *
     * <p>{@code themes.customtheme.allowed} gates two of the Design tab's
     * three items in {@code editor-menu.xml} -- {@code stylesheetEdit} and
     * {@code templates}, which only matter once a weblog is customised --
     * and the customise link on the main menu; {@code themeEdit} itself
     * (picking among the shared themes) stays unconditionally reachable,
     * since that part is safe and reversible. But a hidden menu entry stops
     * nobody who posts to {@code themeEdit!save.rol} directly. The switch
     * imports the shared theme's templates and is one-way, so an
     * installation that has turned the option off needs the refusal
     * enforced here, not merely rendered.
     *
     * <p>A weblog that is <em>already</em> custom stays editable whatever the
     * setting says. Turning the option off is meant to stop new
     * customisations; since there is no way back from custom, refusing here
     * would strand any weblog customised while it was on.
     *
     * <p>The site space never takes a custom theme: its pages render with one
     * of the four site themes, whatever the setting says.
     */
    private boolean customThemeAllowed(HttpServletRequest request) {
        Weblog weblog = getActionWeblog(request);
        return !weblog.isSiteSpace()
                && (WebloggerRuntimeConfig.getBooleanProperty("themes.customtheme.allowed")
                        || WeblogTheme.CUSTOM.equals(weblog.getEditorTheme()));
    }

    /**
     * Themes not meant to be picked from this page: {@code frontpage} is the
     * {@code $site}-wide aggregator theme (its templates render the
     * multi-blog directory named by {@code site.frontpage.weblog.handle},
     * not an individual weblog -- see {@code SiteModel}/{@code
     * ThemeMatrixIT}'s exclusion of it for the same reason) and breaks a
     * normal weblog that adopts it. {@code theme.xml} has no theme-level
     * "hidden"/"sitewide" flag to key off of (only per-template {@code
     * <hidden>}), so this is a hardcoded id rather than metadata-driven;
     * revisit if that descriptor ever grows one.
     */
    private static final String SITEWIDE_ONLY_THEME_ID = "frontpage";

    /**
     * The themes the site space (the hidden weblog owning the site's pages)
     * renders with: the three blog themes and {@code frontpage}, which on the
     * site space is the theme a new one starts on rather than a misfit.
     */
    private static final Set<String> SITE_THEME_IDS = Set.of("journal", "portfolio", "travel", "frontpage");

    /**
     * Whether the picker offers this shared theme to this weblog -- the one
     * rule both the list the form shows and the save behind it apply. The
     * site space is offered the site themes; any other weblog every theme
     * but {@code frontpage}, unless it is already on it and must not be
     * stranded.
     */
    private static boolean offered(Weblog weblog, String themeId, String currentThemeId) {
        if (weblog.isSiteSpace()) {
            return SITE_THEME_IDS.contains(themeId);
        }
        return !SITEWIDE_ONLY_THEME_ID.equals(themeId) || SITEWIDE_ONLY_THEME_ID.equals(currentThemeId);
    }

    /**
     * The theme the action weblog renders with, or null when it cannot be
     * resolved -- the same fail-soft answer the entity's deleted
     * {@code getTheme()} gave, so a half-configured weblog still reaches the
     * form. Resolved through the injected facade, never located.
     */
    private WeblogTheme currentTheme(HttpServletRequest request) {
        try {
            return weblogger.getThemeManager().getTheme(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error getting theme for weblog - {}", getActionWeblog(request).getHandle(), ex);
            return null;
        }
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
        List<SharedTheme> selectable = themeMgr.getEnabledThemesList().stream()
                .filter(t -> offered(weblog, t.getId(), currentThemeId))
                .toList();
        model.addAttribute("themes", selectable);
        model.addAttribute("customTheme", WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme()));
        model.addAttribute("customThemeAllowed", customThemeAllowed(request));
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

    private boolean isSharedThemeCustomStylesheet(HttpServletRequest request) {
        try {
            WeblogTheme theme = currentTheme(request);
            if (!WeblogTheme.CUSTOM.equals(getActionWeblog(request).getEditorTheme())
                    && theme != null && theme.getStylesheet() != null) {
                ThemeTemplate override = weblogger.getWeblogManager()
                        .getTemplateByLink(getActionWeblog(request), theme.getStylesheet().getLink());
                if (override != null) {
                    return true;
                }
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up stylesheet on weblog - {}", getActionWeblog(request).getHandle(), ex);
        }
        return false;
    }
}
