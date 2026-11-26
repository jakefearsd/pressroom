package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.controllers.editor.TemplateEditBean;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * A weblog's own templates, shared by the Templates and Template Edit pages and the session-only
 * template routes (SA-18): the list, what may be added, the rules a new or edited template meets, and the
 * writes. The rules return {@link RuleViolation}s and touch no screen; the writes persist, flush and
 * expire the weblog's pages, and leave the wording of the answer to the surface.
 *
 * <p>Template output is not sanitized, so every caller is an interactive weblog admin: the API's routes
 * are session-only.
 */
public final class CustomTemplates {

    public static final String NAME = "name";
    public static final String LINK = "link";
    public static final String ACTION = "action";

    private CustomTemplates() {
    }

    /** The weblog's templates the page lists: all but the theme's stylesheet copy, which has its own page. */
    public static List<WeblogTemplate> pages(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        List<WeblogTemplate> pages = new ArrayList<>(weblogger.getWeblogManager().getTemplates(weblog));
        WeblogTheme theme = weblogger.getThemeManager().getTheme(weblog);
        if (theme.getStylesheet() != null) {
            pages.remove(weblogger.getWeblogManager().getTemplateByLink(weblog, theme.getStylesheet().getLink()));
        }
        return pages;
    }

    /**
     * The kinds of template the weblog may add now, as the page's dropdown offers them: a custom theme
     * takes each special page it has none of, a shared theme only a default page; a custom page is always
     * open.
     */
    public static Map<ComponentType, String> availableActions(Weblog weblog, List<WeblogTemplate> pages) {
        Map<ComponentType, String> actions = new EnumMap<>(ComponentType.class);
        actions.put(ComponentType.CUSTOM, ComponentType.CUSTOM.getReadableName());
        if (WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())) {
            for (ComponentType type : List.of(ComponentType.PERMALINK, ComponentType.SEARCH, ComponentType.WEBLOG,
                    ComponentType.TAGSINDEX)) {
                actions.put(type, type.getReadableName());
            }
            for (WeblogTemplate page : pages) {
                if (page.getAction() != ComponentType.CUSTOM) {
                    actions.remove(page.getAction());
                }
            }
        } else {
            actions.put(ComponentType.WEBLOG, ComponentType.WEBLOG.getReadableName());
            for (WeblogTemplate page : pages) {
                if (page.getAction() == ComponentType.WEBLOG) {
                    actions.remove(ComponentType.WEBLOG);
                    break;
                }
            }
        }
        return actions;
    }

    /**
     * What a new template must satisfy: a name of at most 255 characters that no template of the weblog
     * has, and an action. A lookup that fails throws: "could not check" is not "free".
     */
    public static List<RuleViolation> newProblems(Weblogger weblogger, Weblog weblog, String name,
            ComponentType action) throws WebloggerException {
        List<RuleViolation> found = new ArrayList<>();
        if (StringUtils.isEmpty(name)) {
            found.add(RuleViolation.invalid(NAME, "Template.error.nameNull", List.of(), ProblemCode.FIELD_REQUIRED,
                    NAME));
        } else if (name.length() > PressroomConstants.TEXTWIDTH_255) {
            found.add(RuleViolation.invalid(NAME, "Template.error.nameSize", List.of(), ProblemCode.FIELD_TOO_LONG,
                    NAME, Integer.toString(PressroomConstants.TEXTWIDTH_255)));
        }
        if (action == null) {
            found.add(RuleViolation.invalid(ACTION, "Template.error.actionNull", List.of(),
                    ProblemCode.FIELD_REQUIRED, ACTION));
        }
        if (!StringUtils.isEmpty(name) && weblogger.getWeblogManager().getTemplateByName(weblog, name) != null) {
            found.add(taken(NAME, name));
        }
        return found;
    }

    /**
     * What an edit must satisfy: a changed name or link is not another template's. (An unchanged one is
     * not judged again.) A lookup that fails throws.
     */
    public static List<RuleViolation> editProblems(Weblogger weblogger, Weblog weblog, WeblogTemplate template,
            String name, String link) throws WebloggerException {
        List<RuleViolation> found = new ArrayList<>();
        if (!template.getName().equals(name)
                && weblogger.getWeblogManager().getTemplateByName(weblog, name) != null) {
            found.add(taken(NAME, name));
        }
        if (!StringUtils.isEmpty(link) && !link.equals(template.getLink())
                && weblogger.getWeblogManager().getTemplateByLink(weblog, link) != null) {
            found.add(taken(LINK, link));
        }
        return found;
    }

    private static RuleViolation taken(String field, String value) {
        return RuleViolation.conflict(field, "pagesForm.error.alreadyExists", List.of(value),
                ProblemCode.TEMPLATE_NAME_IN_USE, value);
    }

    /**
     * Adds a template holding {@code starterContent}; the caller has run {@link #newProblems}. A default
     * page is named as the engine expects and re-saves the weblog. Persists and flushes; the new
     * template's name is {@code getName()} on the result.
     */
    public static WeblogTemplate add(Weblogger weblogger, Weblog weblog, String name, ComponentType action,
            String starterContent) throws WebloggerException {
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(weblog);
        template.setAction(action);
        template.setName(name);
        template.setHidden(false);
        template.setNavbar(false);
        template.setLastModified(new Date());
        if (action == ComponentType.CUSTOM) {
            template.setLink(name);
        }
        if (action == ComponentType.WEBLOG) {
            template.setName(WeblogTemplate.DEFAULT_PAGE);
        }
        WeblogManager weblogs = weblogger.getWeblogManager();
        weblogs.saveTemplate(template);

        CustomTemplateRendition standard = new CustomTemplateRendition(template, RenditionType.STANDARD);
        standard.setTemplate(starterContent);
        standard.setTemplateLanguage(TemplateLanguage.VELOCITY);
        weblogs.saveTemplateRendition(standard);
        if (WeblogTemplate.DEFAULT_PAGE.equals(template.getName())) {
            weblogs.saveWeblog(weblog);
        }
        weblogger.flush();
        return template;
    }

    /**
     * Stores {@code bean} on {@code template} as the Template Edit page does: the markup on its STANDARD
     * rendition, a custom template's name, link and flags, and the output content type (absent or
     * {@code autoContentType} means the engine chooses). Persists, flushes and expires the template's pages.
     */
    public static void save(Weblogger weblogger, WeblogTemplate template, TemplateEditBean bean)
            throws WebloggerException {
        bean.copyTo(template);
        // The bean writes the edited markup onto the STANDARD rendition (creating one if the template had
        // none) but persists nothing; the rendition is saved before the template itself.
        weblogger.getWeblogManager().saveTemplateRendition(template.getTemplateRendition(RenditionType.STANDARD));
        template.setLastModified(new Date());
        if (bean.getAutoContentType() == null || !bean.getAutoContentType()) {
            template.setOutputContentType(bean.getManualContentType());
        } else {
            template.setOutputContentType(null);
        }
        weblogger.getWeblogManager().saveTemplate(template);
        weblogger.flush();
        CacheManager.invalidate(template);
    }

    /**
     * Removes {@code template}, unless the weblog is on a custom theme and the template is one it
     * requires: then nothing changes and the answer is false. Removing the default page takes the weblog's
     * copy of the theme stylesheet with it. Persists, flushes and expires the template's pages.
     */
    public static boolean remove(Weblogger weblogger, Weblog weblog, WeblogTemplate template)
            throws WebloggerException {
        if (template.isRequired() && WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())) {
            return false;
        }
        WeblogManager weblogs = weblogger.getWeblogManager();
        if (WeblogTemplate.DEFAULT_PAGE.equals(template.getName())) {
            removeStylesheetCopy(weblogger, weblog);
        }
        CacheManager.invalidate(template);
        weblogs.removeTemplate(template);
        weblogger.flush();
        return true;
    }

    private static void removeStylesheetCopy(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        ThemeTemplate stylesheet = weblogger.getThemeManager().getTheme(weblog).getStylesheet();
        if (stylesheet == null) {
            return;
        }
        WeblogTemplate copy = weblogger.getWeblogManager().getTemplateByLink(weblog, stylesheet.getLink());
        if (copy != null) {
            weblogger.getWeblogManager().removeTemplate(copy);
        }
    }
}
