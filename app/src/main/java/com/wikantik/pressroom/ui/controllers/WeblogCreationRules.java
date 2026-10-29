package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.config.ProtectedPaths;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;

/**
 * Who may create a weblog, and what a new weblog's handle, name, theme,
 * locale and zone must be: one home for the Create Weblog page and
 * {@code POST /api/v1/weblogs}. Creation only: a weblog that already exists
 * under a handle now reserved keeps working.
 */
public final class WeblogCreationRules {

    /** handle, name and tagline are varchar(255). */
    public static final int MAX_TEXT = 255;
    /** The $site-wide aggregator theme; it breaks any weblog that adopts it. */
    private static final String SITEWIDE_ONLY_THEME_ID = "frontpage";

    /** One refusal; status is the API's (400, 403 or 409); field is the form field id, null for the page. */
    public record Violation(String field, String messageKey, String detail, int status) { }

    /** The site's settings and managers the rules consult. */
    public record Context(boolean creationAllowed, boolean groupBlogging, String allowedChars,
                          Set<String> reservedPaths, List<String> themeIds,
                          UserManager users, WeblogManager weblogs,
                          WeblogPageManager pages) {
        public Context {
            reservedPaths = Set.copyOf(reservedPaths);
            themeIds = List.copyOf(themeIds);
        }

        public static Context current(Weblogger weblogger) {
            String allowed = WebloggerConfig.getProperty("username.allowedChars");
            Set<String> reserved = ProtectedPaths.site();
            List<String> themes = weblogger.getThemeManager().getEnabledThemesList().stream()
                    .map(SharedTheme::getId)
                    .filter(id -> !SITEWIDE_ONLY_THEME_ID.equals(id))
                    .toList();
            return new Context(
                    WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation"),
                    WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"),
                    StringUtils.isBlank(allowed) ? UIUtils.DEFAULT_ALLOWED_CHARS : allowed,
                    reserved, themes, weblogger.getUserManager(), weblogger.getWeblogManager(),
                    weblogger.getWeblogPageManager());
        }
    }

    private WeblogCreationRules() {
    }

    /** Null when this user may create a weblog, else the 403 refusal. A lookup that fails throws. */
    public static Violation mayCreate(User user, Context context) throws WebloggerException {
        if (!context.creationAllowed()) {
            return new Violation(null, "createWebsite.disabled", "Weblog creation is turned off on this site.", 403);
        }
        if (!context.groupBlogging() && !context.users().getWeblogPermissions(user).isEmpty()) {
            return new Violation(null, "createWebsite.oneBlogLimit", "This site allows one weblog per user.", 403);
        }
        return null;
    }

    /** Every broken field rule, handle first. A lookup that fails throws. */
    public static List<Violation> check(CreateWeblogBean bean, Context context) throws WebloggerException {
        List<Violation> found = new ArrayList<>();
        checkHandle(bean.getHandle(), context, found);
        if (StringUtils.isBlank(bean.getName())) {
            found.add(new Violation("name", "createWeblog.error.nameRequired", "name is required.", 400));
        } else if (bean.getName().trim().length() > MAX_TEXT) {
            found.add(new Violation("name", "createWeblog.error.tooLong",
                    "'name' must be " + MAX_TEXT + " characters or fewer.", 400));
        }
        if (bean.getDescription() != null && bean.getDescription().length() > MAX_TEXT) {
            found.add(new Violation("description", "createWeblog.error.tooLong",
                    "'tagline' must be " + MAX_TEXT + " characters or fewer.", 400));
        }
        if (StringUtils.trimToNull(bean.getCopyright()) != null
                && bean.getCopyright().trim().length() > MAX_TEXT) {
            found.add(new Violation("copyright", "createWeblog.error.tooLong",
                    "'copyright' must be " + MAX_TEXT + " characters or fewer.", 400));
        }
        if (bean.getTheme() == null || !context.themeIds().contains(bean.getTheme())) {
            found.add(new Violation("theme", "createWeblog.error.invalidTheme",
                    "theme must be one of: " + String.join(", ", context.themeIds()) + ".", 400));
        }
        String locale = StringUtils.trimToNull(bean.getLocale());
        if (locale != null && UIUtils.getLocales().stream().map(Locale::toString).noneMatch(locale::equals)) {
            found.add(new Violation("locale", "websiteSettings.locale.invalid",
                    "locale must be one the settings form offers, for example en_US.", 400));
        }
        String zone = StringUtils.trimToNull(bean.getTimeZone());
        if (zone != null && !UIUtils.getTimeZones().contains(zone)) {
            found.add(new Violation("timeZone", "websiteSettings.timeZone.invalid",
                    "timeZone must be a time zone id, for example Europe/Berlin.", 400));
        }
        return found;
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a handle only after the check above has confined it to "
                    + "username.allowedChars (A-Za-z0-9 unless the operator widens it), to compare it with "
                    + "the reserved paths.")
    private static void checkHandle(String handle, Context context, List<Violation> found)
            throws WebloggerException {
        if (StringUtils.isBlank(handle)) {
            found.add(new Violation("handle", "createWeblog.error.invalidHandle", "handle is required.", 400));
        } else if (!CharSetUtils.keep(handle, context.allowedChars()).equals(handle)
                || handle.length() > MAX_TEXT) {
            found.add(new Violation("handle", "createWeblog.error.invalidHandle",
                    "handle may use only " + context.allowedChars() + ", at most " + MAX_TEXT + " characters.",
                    400));
        } else if (context.reservedPaths().contains(handle.toLowerCase(Locale.ROOT))) {
            found.add(new Violation("handle", "createWeblog.error.reservedHandle",
                    "handle \"" + handle + "\" is reserved for the site's own pages.", 400));
        } else if (context.weblogs().getWeblogByHandle(handle, null) != null) {
            // null, not the one-argument lookup: that answers only for visible
            // weblogs, and a disabled one still holds its handle (ws_handle_uq).
            found.add(new Violation("handle", "createWeblog.error.handleExists",
                    "A weblog with that handle already exists.", 409));
        } else if (holdsSitePage(handle, context)) {
            // A site page is served at /<slug>; a draft holds its slug too,
            // or publishing it later would collide with a blog made meanwhile.
            found.add(new Violation("handle", "createWeblog.error.handleIsSitePage",
                    "A page of the site already uses that address.", 409));
        }
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a slug or handle only to compare it with handles and protected paths, "
                    + "which are ASCII; a mismatch can only miss a clash, never let one name stand for "
                    + "another, and the database unique index on the handle remains the backstop.")
    private static boolean holdsSitePage(String handle, Context context) throws WebloggerException {
        Weblog site = context.weblogs().getSiteSpace();
        if (site == null) {
            return false;
        }
        for (WeblogPage page : context.pages().getPages(site)) {
            if (page.getSlug() != null
                    && page.getSlug().toLowerCase(Locale.ROOT).equals(handle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Creates the weblog exactly as the page always has: addWeblog with the user as creator. */
    public static Weblog create(CreateWeblogBean bean, User user, WeblogManager weblogs)
            throws WebloggerException {
        Weblog weblog = new Weblog(bean.getHandle(), user.getUserName(), bean.getName().trim(),
                bean.getDescription(), bean.getEmailAddress(), bean.getTheme(),
                bean.getLocale(), bean.getTimeZone());
        weblog.setCopyright(StringUtils.trimToNull(bean.getCopyright()));
        weblogs.addWeblog(weblog);
        return weblog;
    }
}
