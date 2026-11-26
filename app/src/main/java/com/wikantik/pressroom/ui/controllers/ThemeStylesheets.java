package com.wikantik.pressroom.ui.controllers;

import java.util.Date;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * A weblog's own copy of its theme's stylesheet, shared by the Stylesheet page and the session-only
 * stylesheet routes (SA-18). A weblog on a shared theme may copy the theme's stylesheet to edit it,
 * revert the copy to the theme's text, or delete it to follow the theme again; a weblog on a custom theme
 * owns its stylesheet and only edits it. Every write persists, flushes and expires the weblog's pages.
 */
public final class ThemeStylesheets {

    private ThemeStylesheets() {
    }

    /** Whether the weblog is on its own templates, where the stylesheet is simply one of them. */
    public static boolean isCustomTheme(Weblog weblog) {
        return WeblogTheme.CUSTOM.equals(weblog.getEditorTheme());
    }

    /** The stylesheet the weblog's theme defines, or null when it defines none. */
    private static ThemeTemplate themeStylesheet(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        if (isCustomTheme(weblog)) {
            return weblogger.getThemeManager().getTheme(weblog).getStylesheet();
        }
        SharedTheme theme = weblogger.getThemeManager().getTheme(weblog.getEditorTheme());
        return theme.getStylesheet();
    }

    /** Whether the weblog's shared theme defines a stylesheet to copy; false on a custom theme. */
    public static boolean sharedThemeHasStylesheet(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        return !isCustomTheme(weblog) && themeStylesheet(weblogger, weblog) != null;
    }

    /** The weblog's own stylesheet template, or null when it has none. A lookup that fails throws. */
    public static WeblogTemplate find(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        ThemeTemplate stylesheet = themeStylesheet(weblogger, weblog);
        return stylesheet == null ? null
                : weblogger.getWeblogManager().getTemplateByLink(weblog, stylesheet.getLink());
    }

    /** The stylesheet's CSS; empty when the template holds no STANDARD rendition. */
    public static String contents(WeblogTemplate template) throws WebloggerException {
        CustomTemplateRendition standard = template.getTemplateRendition(RenditionType.STANDARD);
        return standard == null ? "" : standard.getTemplate();
    }

    /**
     * Creates the weblog's copy of its shared theme's stylesheet when it has none, and sets the copy's text
     * to the theme's (the Copy button: it reverts as it copies). The caller has checked the theme defines
     * a stylesheet ({@link #sharedThemeHasStylesheet}). True when the copy was created, false when the
     * weblog already had one (which is reverted all the same).
     */
    public static boolean copyFromShared(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        WeblogManager weblogs = weblogger.getWeblogManager();
        ThemeTemplate stylesheet = themeStylesheet(weblogger, weblog);
        WeblogTemplate copy = weblogs.getTemplateByLink(weblog, stylesheet.getLink());
        boolean created = copy == null;
        if (created) {
            copy = new WeblogTemplate();
            copy.setWeblog(weblog);
            copy.setAction(ComponentType.STYLESHEET);
            copy.setName(stylesheet.getName());
            copy.setDescription(stylesheet.getDescription());
            copy.setLink(stylesheet.getLink());
            copy.setHidden(false);
            copy.setNavbar(false);
            copy.setLastModified(new Date());
            TemplateRendition shared = stylesheet.getTemplateRendition(RenditionType.STANDARD);
            if (shared != null) {
                CustomTemplateRendition standard = new CustomTemplateRendition(copy, RenditionType.STANDARD);
                standard.setTemplate(shared.getTemplate());
                standard.setTemplateLanguage(shared.getTemplateLanguage());
                weblogs.saveTemplateRendition(standard);
            }
            weblogs.saveTemplate(copy);
            weblogger.flush();
        }
        // Re-read what was just saved, as the page always has, so the revert acts on the stored template.
        WeblogTemplate stored = created ? weblogs.getTemplateByLink(weblog, stylesheet.getLink()) : copy;
        if (stored == null) {
            throw new WebloggerException("The stylesheet copy cannot be read back");
        }
        revert(weblogger, weblog, stored);
        return created;
    }

    /** Stores {@code css} as the text of {@code template}, the weblog's own stylesheet. A null text is empty. */
    public static void save(Weblogger weblogger, WeblogTemplate template, String css) throws WebloggerException {
        WeblogManager weblogs = weblogger.getWeblogManager();
        template.setLastModified(new Date());
        template.setAction(ComponentType.STYLESHEET);
        CustomTemplateRendition standard = template.getTemplateRendition(RenditionType.STANDARD);
        if (standard == null) {
            // The first time a weblog overrides its theme's stylesheet: the column is NOT NULL, hence the
            // fallback for an absent text; the submitted CSS is stored, not an empty stylesheet.
            standard = new CustomTemplateRendition(template, RenditionType.STANDARD);
        }
        standard.setTemplate(css == null ? "" : css);
        weblogs.saveTemplateRendition(standard);
        weblogs.saveTemplate(template);
        weblogger.flush();
        CacheManager.invalidate(template);
    }

    /** Sets {@code template}'s text to the shared theme's own, keeping the copy. Only for a shared theme. */
    public static void revert(Weblogger weblogger, Weblog weblog, WeblogTemplate template)
            throws WebloggerException {
        WeblogManager weblogs = weblogger.getWeblogManager();
        template.setLastModified(new Date());
        CustomTemplateRendition existing = template.getTemplateRendition(RenditionType.STANDARD);
        if (existing != null) {
            TemplateRendition shared = weblogger.getThemeManager().getTheme(weblog.getEditorTheme())
                    .getStylesheet().getTemplateRendition(RenditionType.STANDARD);
            existing.setTemplate(shared.getTemplate());
            weblogs.saveTemplateRendition(existing);
        }
        weblogs.saveTemplate(template);
        weblogger.flush();
        CacheManager.invalidate(template);
    }

    /** Removes the weblog's copy, so it follows its shared theme's stylesheet again. Only for a shared theme. */
    public static void remove(Weblogger weblogger, Weblog weblog, WeblogTemplate template)
            throws WebloggerException {
        WeblogManager weblogs = weblogger.getWeblogManager();
        weblogs.removeTemplate(template);
        weblogs.saveWeblog(weblog);
        CacheManager.invalidate(template);
        weblogger.flush();
    }
}
