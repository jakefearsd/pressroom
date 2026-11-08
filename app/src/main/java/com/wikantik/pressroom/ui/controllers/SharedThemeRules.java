package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;

/**
 * Which shared themes a weblog may switch to, and the switch itself: one home
 * for the theme page ({@code ThemeEditController}) and {@code PATCH
 * /api/v1/weblogs/{handle}}, so the two cannot offer different themes or
 * switch differently.
 */
public final class SharedThemeRules {

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

    private SharedThemeRules() {
    }

    /**
     * Whether the picker offers this shared theme to this weblog -- the one
     * rule both the list the form shows and the save behind it apply. The
     * site space is offered the site themes; any other weblog every theme
     * but {@code frontpage}, unless it is already on it and must not be
     * stranded.
     */
    public static boolean offered(Weblog weblog, String themeId, String currentThemeId) {
        if (weblog.isSiteSpace()) {
            return SITE_THEME_IDS.contains(themeId);
        }
        return !SITEWIDE_ONLY_THEME_ID.equals(themeId) || SITEWIDE_ONLY_THEME_ID.equals(currentThemeId);
    }

    /** The enabled shared themes {@link #offered} to this weblog, in the theme manager's order. */
    public static List<SharedTheme> offeredThemes(ThemeManager themes, Weblog weblog, String currentThemeId) {
        return themes.getEnabledThemesList().stream()
                .filter(t -> offered(weblog, t.getId(), currentThemeId))
                .toList();
    }

    /**
     * Puts {@code weblog} on the shared theme {@code themeId}, which the
     * caller has checked is offered. Switching away from a theme drops the
     * weblog's own copy of that theme's stylesheet, which would otherwise
     * dress the new theme in the old one's styles; re-choosing the current
     * theme keeps it. {@code current} is the theme the weblog renders with
     * now, or null when it could not be resolved (nothing is dropped then).
     * The caller saves the weblog and expires its pages.
     */
    public static void switchTo(WeblogManager weblogs, Weblog weblog, String themeId, WeblogTheme current)
            throws WebloggerException {
        if (!themeId.equals(weblog.getEditorTheme()) && current != null && current.getStylesheet() != null) {
            WeblogTemplate stylesheet = weblogs.getTemplateByAction(weblog, ComponentType.STYLESHEET);
            if (stylesheet != null) {
                weblogs.removeTemplate(stylesheet);
            }
        }
        weblog.setEditorTheme(themeId);
    }
}
