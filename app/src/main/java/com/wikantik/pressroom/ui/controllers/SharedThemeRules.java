package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.util.cache.CacheManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which shared themes a weblog may switch to, and the switch itself: one home
 * for the theme page ({@code ThemeEditController}) and {@code PATCH
 * /api/v1/weblogs/{handle}}, so the two cannot offer different themes or
 * switch differently.
 */
public final class SharedThemeRules {

    private static final Logger LOG = LoggerFactory.getLogger(SharedThemeRules.class);

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
     * The theme {@code weblog} renders with now, or null when it cannot be
     * resolved (a theme since disabled or removed). Null is the safe answer
     * for {@link #switchTo}, which then keeps the stylesheet copy, and a blog
     * in that state is exactly one that needs a switch, so it is not refused.
     */
    public static WeblogTheme currentTheme(ThemeManager themes, Weblog weblog) {
        try {
            return themes.getTheme(weblog);
        } catch (WebloggerException ex) {
            LOG.error("Error getting theme for weblog - {}", weblog.getHandle(), ex);
            return null;
        }
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

    /** The site-relative path of {@code theme}'s preview image ({@code /themes/<id>/<file>}), or null when it has none. */
    public static String previewPath(SharedTheme theme) {
        return theme.getPreviewImage() == null ? null
                : "/themes/" + theme.getId() + "/" + theme.getPreviewImage().getPath();
    }

    /**
     * Whether {@code weblog} may adopt a custom theme. {@code themes.customtheme.allowed} gates the
     * switch, which imports a shared theme's templates and is one way, so it is enforced here, not only
     * by the menu. A weblog already custom stays editable whatever the setting says (turning the option
     * off stops new customisations; refusing would strand the ones made while it was on). The site space
     * never takes a custom theme.
     */
    public static boolean customThemeAllowed(Weblog weblog) {
        return !weblog.isSiteSpace()
                && (WebloggerRuntimeConfig.getBooleanProperty("themes.customtheme.allowed")
                        || WeblogTheme.CUSTOM.equals(weblog.getEditorTheme()));
    }

    /**
     * The first rule a switch to a custom theme breaks, or null: the site space and a site that has
     * custom themes off ({@code themeEditor.error.notOffered} / {@code ...customThemeNotAllowed} for the
     * screen, {@code theme.customisationDisabled} for the API), then an {@code importThemeId} that is not
     * one of the themes offered (blank means import nothing). The screen's rule is {@link #offered} (its
     * picker lists the enabled themes, so an unknown id never reaches it); with {@code mustBeEnabled} the id
     * must also be an enabled theme, for a client that types the id.
     */
    public static RuleViolation customViolation(ThemeManager themes, Weblog weblog, String importThemeId,
            boolean mustBeEnabled) {
        if (weblog.isSiteSpace()) {
            return RuleViolation.invalid("theme", "themeEditor.error.notOffered", List.of(),
                    ProblemCode.THEME_CUSTOMISATION_DISABLED);
        }
        if (!customThemeAllowed(weblog)) {
            return RuleViolation.invalid("theme", "themeEditor.error.customThemeNotAllowed", List.of(),
                    ProblemCode.THEME_CUSTOMISATION_DISABLED);
        }
        if (importThemeId == null || importThemeId.isEmpty()) {
            return null;
        }
        String current = WeblogTheme.CUSTOM.equals(weblog.getEditorTheme()) ? null
                : themeId(currentTheme(themes, weblog));
        List<String> offered = offeredThemes(themes, weblog, current).stream().map(SharedTheme::getId).toList();
        boolean refused = mustBeEnabled ? !offered.contains(importThemeId) : !offered(weblog, importThemeId, current);
        if (refused) {
            return RuleViolation.invalid("importTheme", "themeEditor.error.notOffered", List.of(),
                    ProblemCode.THEME_NOT_OFFERED, String.join(", ", offered));
        }
        return null;
    }

    private static String themeId(WeblogTheme theme) {
        return theme == null ? null : theme.getId();
    }

    /**
     * Imports the shared theme {@code themeId}'s templates into {@code weblog} and returns the theme.
     * Re-importing the current theme keeps a customised stylesheet; the check throws rather than answering
     * "not customised" when it cannot run, so a failure refuses instead of overwriting the stylesheet.
     */
    public static SharedTheme importTemplates(Weblogger weblogger, Weblog weblog, String themeId)
            throws WebloggerException {
        ThemeManager themes = weblogger.getThemeManager();
        SharedTheme theme = themes.getTheme(themeId);
        boolean skipStylesheet = themeId.equals(weblog.getEditorTheme())
                && hasCustomisedSharedStylesheet(weblogger, weblog);
        themes.importTheme(weblog, theme, skipStylesheet);
        return theme;
    }

    /** Puts {@code weblog} on its own templates ({@link WeblogTheme#CUSTOM}), saved, flushed and its pages expired. */
    public static void enableCustom(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        CacheManager.invalidate(weblog);
    }

    /**
     * Whether {@code weblog}, on a shared theme, holds its own copy of that theme's stylesheet. Throws
     * when the theme or the template cannot be looked up: the import decides from this whether to
     * overwrite the stylesheet, so a lookup that could not run must not read as "no".
     */
    public static boolean hasCustomisedSharedStylesheet(Weblogger weblogger, Weblog weblog)
            throws WebloggerException {
        if (WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())) {
            return false;
        }
        WeblogTheme theme = weblogger.getThemeManager().getTheme(weblog);
        return theme != null && theme.getStylesheet() != null
                && weblogger.getWeblogManager().getTemplateByLink(weblog, theme.getStylesheet().getLink()) != null;
    }
}
