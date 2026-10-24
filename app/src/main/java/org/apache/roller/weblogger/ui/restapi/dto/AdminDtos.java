package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.core.CreateWeblogBean;
import org.apache.roller.weblogger.ui.controllers.editor.WeblogConfigBean;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;

/**
 * Views for the admin surface: user accounts, weblog administration, and
 * runtime configuration, for the automation API.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserView(String userName, String screenName, String emailAddress,
                            boolean enabled, List<String> globalRoles, boolean sso, String kind) {
    }

    /**
     * The body of a create-user request. Deliberately carries no password
     * field -- {@link org.apache.roller.weblogger.ui.restapi.v1.AdminApi}
     * creates the account disabled and emails a set-password link instead,
     * so no plaintext password ever crosses this API.
     */
    public record UserCreate(String userName, String screenName, String emailAddress) {
    }

    /**
     * The body of a user PATCH. A null field leaves the corresponding
     * property unchanged; {@code userName} is the path variable and is not
     * itself patchable.
     */
    public record UserPatch(Boolean enabled, String screenName, String emailAddress) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigEntry(String name, String value, String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeblogView(String id, String handle, String name, String tagline, String copyright,
                              String emailAddress, String locale, String timeZone,
                              int entryDisplayCount, boolean active, boolean commentsEnabled, boolean commentNotify,
                              String editorTheme,
                              String creatorUserName, String customDomain, String icon, String about,
                              String analyticsSiteId, String analyticsShareUrl, String newsletterListUuid,
                              String businessId, String placeType, String placeLocality, String placeRegion,
                              String placeCountry, Double placeLat, Double placeLng, String bookingUrl) {
    }

    /** The body of POST /weblogs. handle, name and theme are required; the rest default to the user's own. */
    public record WeblogCreate(
            @Schema(description = "The weblog's handle: its URL path. Letters and digits.") String handle,
            @Schema(description = "The weblog's name.") String name,
            @Schema(description = "An enabled theme id, for example journal; a wrong one answers with the list.")
            String theme,
            String tagline,
            @Schema(description = "The footer's copyright notice, plain text, at most 255 characters.")
            String copyright, String emailAddress,
            @Schema(description = "A locale such as en_US. On create, defaults to the user's own.") String locale,
            @Schema(description = "A time zone id such as Europe/Berlin. On create, defaults to the user's own.")
            String timeZone) {
    }

    /** The create form's bean, filled as the page pre-fills it: omitted email, locale and zone are the user's. */
    public static CreateWeblogBean toBean(WeblogCreate body, User user) {
        CreateWeblogBean bean = new CreateWeblogBean();
        bean.setHandle(body.handle() == null ? null : body.handle().trim());
        bean.setName(body.name());
        bean.setTheme(body.theme());
        bean.setDescription(body.tagline());
        bean.setCopyright(body.copyright());
        bean.setEmailAddress(body.emailAddress() != null ? body.emailAddress() : user.getEmailAddress());
        bean.setLocale(body.locale() != null ? body.locale() : user.getLocale());
        bean.setTimeZone(body.timeZone() != null ? body.timeZone() : user.getTimeZone());
        return bean;
    }

    /**
     * The body of a weblog PATCH. A null field leaves the corresponding
     * property unchanged; removing a value is the {@code clear} list. An
     * explicit blank {@code customDomain} still clears the domain (the
     * JSP editor's convention), but {@code clear} is the way to say it.
     */
    public record WeblogPatch(String name, String tagline, String copyright, String emailAddress, String locale,
                               String timeZone, Integer entryDisplayCount, Boolean active,
                               String customDomain, String icon, String about,
                               String analyticsSiteId, String analyticsShareUrl, String newsletterListUuid,
                               @Schema(description = "Id of an existing business profile") String businessId,
                               @Schema(description = "LodgingBusiness, or omit; needs placeLocality") String placeType,
                               String placeLocality, String placeRegion,
                               @Schema(description = "Two-letter country code") String placeCountry,
                               @Schema(description = "Decimal degrees, given as a pair; stored to two decimals")
                               Double placeLat,
                               @Schema(description = "Decimal degrees, given as a pair; stored to two decimals")
                               Double placeLng,
                               String bookingUrl,
                               @Schema(description = "Allow signed-in readers to comment. Not clearable.")
                               Boolean commentsEnabled,
                               @Schema(description = "Email the owners when a comment needs approval. Not clearable.")
                               Boolean commentNotify,
                               @Schema(description = "Fields to remove: tagline, copyright, customDomain, icon, about, "
                                       + "analyticsSiteId, analyticsShareUrl, newsletterListUuid, businessId, "
                                       + "placeType, placeLocality, placeRegion, placeCountry, placeLat with "
                                       + "placeLng, bookingUrl. Null never removes anything.")
                               List<String> clear) {
    }

    public static final List<String> WEBLOG_CLEARABLE = List.of("tagline", "copyright", "customDomain", "icon", "about",
            "analyticsSiteId", "analyticsShareUrl", "newsletterListUuid", "businessId", "placeType",
            "placeLocality", "placeRegion", "placeCountry", "placeLat", "placeLng", "bookingUrl");
    public static final List<List<String>> WEBLOG_PAIRS = List.of(List.of("placeLat", "placeLng"));

    public static UserView toView(User user, List<String> roles) {
        return new UserView(user.getUserName(), user.getScreenName(), user.getEmailAddress(),
                Boolean.TRUE.equals(user.getEnabled()), roles, user.isSsoBound(), user.getKind().name());
    }

    public static WeblogView toView(Weblog weblog) {
        return new WeblogView(weblog.getId(), weblog.getHandle(), weblog.getName(),
                weblog.getTagline(), weblog.getCopyright(), weblog.getEmailAddress(), weblog.getLocale(),
                weblog.getTimeZone(), weblog.getEntryDisplayCount(),
                Boolean.TRUE.equals(weblog.getActive()),
                Boolean.TRUE.equals(weblog.getCommentsEnabled()),
                !Boolean.FALSE.equals(weblog.getCommentNotify()), weblog.getEditorTheme(),
                weblog.getCreatorUserName(), weblog.getCustomDomain(), weblog.getIconPath(),
                weblog.getAbout(), weblog.getAnalyticsSiteId(), weblog.getAnalyticsShareUrl(),
                weblog.getNewsletterListUuid(),
                weblog.getBusiness() == null ? null : weblog.getBusiness().getId(),
                weblog.getPlaceType(), weblog.getPlaceLocality(), weblog.getPlaceRegion(),
                weblog.getPlaceCountry(),
                weblog.getPlaceLat() == null ? null : weblog.getPlaceLat().doubleValue(),
                weblog.getPlaceLng() == null ? null : weblog.getPlaceLng().doubleValue(),
                weblog.getBookingUrl());
    }

    /**
     * Lays a PATCH over the settings form's bean, loaded from the weblog, so the
     * form's own copyTo stores the result: the API and the form cannot store a
     * value differently. Column and blank checks that were here before stay here
     * (name, tagline, emailAddress, locale, timeZone lengths); everything the
     * settings rules judge is left to WeblogSettingsRules. Returns the names the
     * request touched (set or cleared), which is what the caller reports
     * violations against.
     */
    public static Set<String> applyPatch(WeblogConfigBean bean, WeblogPatch patch, Set<String> cleared) {
        if (patch.name() != null) {
            String name = patch.name().trim();
            if (name.isBlank()) {
                throw ApiException.invalidField("name", "name cannot be blank.");
            }
            ColumnLimits.requireMaxLength("name", name, ColumnLimits.WEBLOG_NAME);
            bean.setName(name);
        }
        if (patch.tagline() != null) {
            ColumnLimits.requireMaxLength("tagline", patch.tagline(), ColumnLimits.TAGLINE);
            bean.setTagline(patch.tagline());
        }
        if (patch.copyright() != null) {
            ColumnLimits.requireMaxLength("copyright", patch.copyright(), ColumnLimits.COPYRIGHT);
            bean.setCopyright(patch.copyright());
        }
        if (patch.emailAddress() != null) {
            ColumnLimits.requireMaxLength("emailAddress", patch.emailAddress(), ColumnLimits.WEBLOG_EMAIL_ADDRESS);
            bean.setEmailAddress(patch.emailAddress());
        }
        if (patch.locale() != null) {
            ColumnLimits.requireMaxLength("locale", patch.locale(), ColumnLimits.LOCALE);
            bean.setLocale(patch.locale());
        }
        if (patch.timeZone() != null) {
            ColumnLimits.requireMaxLength("timeZone", patch.timeZone(), ColumnLimits.TIME_ZONE);
            bean.setTimeZone(patch.timeZone());
        }
        if (patch.entryDisplayCount() != null) {
            bean.setEntryDisplayCount(patch.entryDisplayCount());
        }
        if (patch.active() != null) {
            bean.setActive(patch.active());
        }
        if (patch.commentsEnabled() != null) {
            bean.setCommentsEnabled(patch.commentsEnabled());
        }
        if (patch.commentNotify() != null) {
            bean.setCommentNotify(patch.commentNotify());
        }
        setIfPresent(patch.customDomain(), bean::setCustomDomain);
        setIfPresent(patch.icon(), bean::setIcon);
        setIfPresent(patch.about(), bean::setAbout);
        setIfPresent(patch.analyticsSiteId(), bean::setAnalyticsSiteId);
        setIfPresent(patch.analyticsShareUrl(), bean::setAnalyticsShareUrl);
        setIfPresent(patch.newsletterListUuid(), bean::setNewsletterListUuid);
        setIfPresent(patch.businessId(), bean::setBusinessId);
        setIfPresent(patch.placeType(), bean::setPlaceType);
        setIfPresent(patch.placeLocality(), bean::setPlaceLocality);
        setIfPresent(patch.placeRegion(), bean::setPlaceRegion);
        setIfPresent(patch.placeCountry(), bean::setPlaceCountry);
        if (patch.placeLat() != null) {
            bean.setPlaceLat(twoDecimals("placeLat", patch.placeLat()));
        }
        if (patch.placeLng() != null) {
            bean.setPlaceLng(twoDecimals("placeLng", patch.placeLng()));
        }
        setIfPresent(patch.bookingUrl(), bean::setBookingUrl);
        for (String name : cleared) {
            clearOnBean(bean, name);
        }
        Set<String> touched = new HashSet<>(ClearFields.setFields(patch));
        touched.addAll(cleared);
        return touched;
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /** The column is numeric(5,2)/(6,2): store what the database would keep, as the form's value would be. */
    private static String twoDecimals(String field, Double value) {
        if (!Double.isFinite(value)) {
            throw ApiException.invalidField(field, field + " must be a finite number.");
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void clearOnBean(WeblogConfigBean bean, String name) {
        switch (name) {
            case "tagline" -> bean.setTagline(null);
            case "copyright" -> bean.setCopyright(null);
            case "customDomain" -> bean.setCustomDomain(null);
            case "icon" -> bean.setIcon(null);
            case "about" -> bean.setAbout(null);
            case "analyticsSiteId" -> bean.setAnalyticsSiteId(null);
            case "analyticsShareUrl" -> bean.setAnalyticsShareUrl(null);
            case "newsletterListUuid" -> bean.setNewsletterListUuid(null);
            case "businessId" -> bean.setBusinessId(null);
            case "placeType" -> bean.setPlaceType(null);
            case "placeLocality" -> bean.setPlaceLocality(null);
            case "placeRegion" -> bean.setPlaceRegion(null);
            case "placeCountry" -> bean.setPlaceCountry(null);
            case "placeLat" -> bean.setPlaceLat(null);
            case "placeLng" -> bean.setPlaceLng(null);
            case "bookingUrl" -> bean.setBookingUrl(null);
            default -> throw new IllegalArgumentException("Not clearable: " + name);
        }
    }

    /**
     * Accepts a property name only if runtimeConfigDefs.xml declares it.
     *
     * <p>The declaration file IS the allowlist -- there is no second hardcoded
     * list to drift from it. A startup-scoped setting is absent from
     * runtimeConfigDefs.xml by definition, so it is rejected here for free.
     */
    public static void requireRuntimeProperty(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("A property name is required.");
        }
        if (findPropertyDef(name) == null) {
            throw ApiException.invalidField(name,
                    "'" + name + "' is not a runtime-settable property.");
        }
    }

    /**
     * The declared definition for a runtime property name, or {@code null}
     * when {@code runtimeConfigDefs.xml} declares no such property -- which
     * is also the case, by construction, for every startup-scoped setting.
     *
     * <p>{@code RuntimeConfigDefs} groups its {@code PropertyDef}s two levels
     * down ({@code ConfigDef} -> {@code DisplayGroup} -> {@code PropertyDef}),
     * not directly, so this walks all three rather than assuming a flatter
     * shape.
     */
    public static PropertyDef findPropertyDef(String name) {
        if (name == null || name.isBlank() || WebloggerRuntimeConfig.getRuntimeConfigDefs() == null) {
            return null;
        }
        return WebloggerRuntimeConfig.getRuntimeConfigDefs().getConfigDefs().stream()
                .flatMap(configDef -> configDef.getDisplayGroups().stream())
                .flatMap(group -> group.getPropertyDefs().stream())
                .filter(def -> name.equals(def.getName()))
                .findFirst()
                .orElse(null);
    }
}
