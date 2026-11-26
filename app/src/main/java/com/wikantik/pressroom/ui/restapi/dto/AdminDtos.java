package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.business.discovery.DiscoveryStatus;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.EntryOrder;
import com.wikantik.pressroom.pojos.PageWidth;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.AccountRoles;
import com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * Views for the admin surface: user accounts, weblog administration, and
 * runtime configuration, for the automation API.
 */
public final class AdminDtos {

    /**
     * The one text for {@code theme}, which create and update both take: the
     * MCP weblogs tool has one property per argument name and keeps the first
     * description it meets (docs/dev/api.md, MCP endpoint).
     */
    public static final String THEME_DESCRIPTION = "A shared theme id, for example journal: required on create; "
            + "on update it switches the blog to that theme. A wrong one answers with the ones offered. Custom "
            + "themes are managed in the admin UI. Not clearable.";

    private AdminDtos() {
    }

    /** One page of user accounts. */
    public record UserPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<UserView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore) {
    }

    /** One page of weblogs. */
    public record WeblogListPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<WeblogView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserView(String userName, String screenName, String emailAddress,
                            boolean enabled, List<String> globalRoles, boolean sso, String kind,
                            @Schema(description = "The account's full name.")
                            String fullName,
                            @Schema(description = "The account's locale, for example en_US.")
                            String locale,
                            @Schema(description = "The account's time zone id, for example Europe/Berlin.")
                            String timeZone,
                            @Schema(description = "Whether the account holds the editor (author) role. Settable "
                                    + "only on a single-sign-on account.")
                            boolean writer) {
    }

    /** One weblog an account belongs to, and what it may do there. */
    public record UserWeblogsPage(
            @Schema(description = "The weblogs the account holds a permission on, by handle.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<MetaDtos.WeblogMembership> items) {
    }

    /**
     * The body of a create-user request. Deliberately carries no password
     * field -- {@link com.wikantik.pressroom.ui.restapi.v1.AdminApi}
     * creates the account disabled and emails a set-password link instead,
     * so no plaintext password ever crosses this API.
     */
    public record UserCreate(
            @Schema(description = "The account's user name, which it signs in with; it never changes.")
            String userName,
            @Schema(description = "The account's display name.")
            String screenName,
            @Schema(description = "The account's email address; the set-password link is sent to it.")
            String emailAddress,
            @Schema(description = "The account's full name. Defaults to the screen name.")
            String fullName,
            @Schema(description = "The account's locale, one the settings form offers, for example en_US.")
            String locale,
            @Schema(description = "The account's time zone id, for example Europe/Berlin.")
            String timeZone) {
    }

    /**
     * The body of a user PATCH. A null field leaves the corresponding
     * property unchanged; {@code userName} is the path variable and is not
     * itself patchable.
     */
    public record UserPatch(
            @Schema(description = "Whether the account may sign in.")
            Boolean enabled,
            @Schema(description = "The account's display name.")
            String screenName,
            @Schema(description = "The account's email address. On a single-sign-on account, replaced at its next "
                    + "sign-in.")
            String emailAddress,
            @Schema(description = "The account's full name.")
            String fullName,
            @Schema(description = "The account's locale, one the settings form offers, for example en_US.")
            String locale,
            @Schema(description = "The account's time zone id, for example Europe/Berlin.")
            String timeZone,
            @Schema(description = "Grant or revoke the editor (author) role. Only a single-sign-on account has "
                    + "one to edit; any other is refused.")
            Boolean writer) {
    }

    /**
     * The answer to a maintenance action ({@code AdminActionsApi}): which
     * action was started, on which weblog. It was a {@code Map}; the record
     * fixes the member order as {@code action}, {@code weblog}.
     */
    public record MaintenanceAccepted(
            @Schema(description = "flush-cache, rebuild-index or regenerate-renditions: the action started.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String action,
            @Schema(description = "The handle of the weblog it was started on.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String weblog) {
    }

    /** Front-page discovery, as the Maintenance screen shows it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiscoveryStatusView(
            @Schema(description = "DISABLED (no analytics to read), NEVER (not read yet), OK or FAILED.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String state,
            @Schema(description = "When the last refresh was tried.") Instant lastAttempt,
            @Schema(description = "When a refresh last succeeded.") Instant lastSuccess,
            @Schema(description = "Entries with a popularity score.", requiredMode = Schema.RequiredMode.REQUIRED)
            int scoredEntries,
            @Schema(description = "Entries eligible for the front page.", requiredMode = Schema.RequiredMode.REQUIRED)
            int candidates,
            @Schema(description = "Why the last refresh failed, when it did.") String lastError) {
    }

    /** One weblog that failed to register with the analytics service. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyticsFailureView(
            @Schema(description = "The weblog's handle.", requiredMode = Schema.RequiredMode.REQUIRED) String handle,
            @Schema(description = "What went wrong.") String error,
            @Schema(description = "When.") Instant at) {
    }

    /** Analytics registration and read status, from the manager's memory; no analytics call is made. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyticsStatusView(
            @Schema(description = "Whether an analytics service is set up.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean configured,
            @Schema(description = "Whether the last call reached it; absent before any call.") Boolean reachable,
            @Schema(description = "Weblogs registered.", requiredMode = Schema.RequiredMode.REQUIRED) int registered,
            @Schema(description = "Weblogs that should be.", requiredMode = Schema.RequiredMode.REQUIRED) int total,
            @Schema(description = "The weblogs that failed to register.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<AnalyticsFailureView> failures,
            @Schema(description = "When traffic was last read.") Instant lastRead,
            @Schema(description = "Why the last read failed, when it did.") String lastReadError) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfigEntry(String name, String value, String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeblogView(String id, String handle, String name, String tagline, String copyright,
                              String emailAddress, String locale, String timeZone,
                              int entryDisplayCount, boolean active, boolean commentsEnabled, boolean commentNotify,
                              @Schema(description = "Show the more-from-the-site block on this blog. Default true.")
                              boolean showMoreFromSite,
                              @Schema(description = "Show the share row under this blog's posts. Default true.")
                              boolean shareRowEnabled,
                              @Schema(description = "NEWEST (the default) or OLDEST: the order of this blog's "
                                      + "listings. Feeds stay newest first.")
                              String entryOrder,
                              @Schema(description = "WIDE (the default) or STANDARD: STANDARD keeps the theme's "
                                      + "narrower page and line length on large screens.")
                              String pageWidth,
                              @Schema(description = "Id of the published page shown at the blog's root; absent "
                                      + "when the root lists posts (they then live at /posts).")
                              String frontPage,
                              @Schema(description = "The same value as theme, under its older name.",
                                      deprecated = true)
                              String editorTheme,
                              @Schema(description = "The blog's theme: a shared theme id such as journal, or "
                                      + "custom for a customised theme.")
                              String theme,
                              String creatorUserName, String customDomain, String icon, String about,
                              String newsletterListUuid,
                              String businessId, String placeType, String placeLocality, String placeRegion,
                              String placeCountry, Double placeLat, Double placeLng, String bookingUrl,
                              @Schema(description = "The weblog's absolute web address, as readers reach it "
                                      + "(its custom domain when it has one); absent when it cannot be built.")
                              String absoluteUrl) {
    }

    /** The body of POST /weblogs. handle, name and theme are required; the rest default to the user's own. */
    public record WeblogCreate(
            @Schema(description = "The weblog's handle: its URL path. Letters and digits.") String handle,
            @Schema(description = "The weblog's name.") String name,
            @Schema(description = THEME_DESCRIPTION)
            String theme,
            @Schema(description = "A line under the weblog's name.")
            String tagline,
            @Schema(description = "The footer's copyright notice, plain text, at most 255 characters.")
            String copyright,
            @Schema(description = "The weblog's contact email address. On create, defaults to the user's own.")
            String emailAddress,
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
    public record WeblogPatch(@Schema(description = "The weblog's name.") String name,
                               @Schema(description = "A line under the weblog's name. Remove with clear.")
                               String tagline,
                               @Schema(description = "The footer's copyright notice, plain text, at most 255 "
                                       + "characters. Remove with clear.")
                               String copyright,
                               @Schema(description = "The weblog's contact email address.") String emailAddress,
                               @Schema(description = "A locale such as en_US.") String locale,
                               @Schema(description = "A time zone id such as Europe/Berlin.") String timeZone,
                               @Schema(description = "How many entries a page of the weblog shows: at least 1, "
                                       + "at most the site's limit.")
                               Integer entryDisplayCount,
                               @Schema(description = "False takes the weblog offline.") Boolean active,
                               @Schema(description = "A hostname the weblog is served at, such as "
                                       + "blog.example.com; an empty string or clear removes it.")
                               String customDomain,
                               @Schema(description = "The path of the weblog's icon image, at most 255 "
                                       + "characters. Remove with clear.")
                               String icon,
                               @Schema(description = "A short text about this weblog, at most 255 "
                                       + "characters. Remove with clear.")
                               String about,
                               @Schema(description = "The UUID of the newsletter list readers subscribe to. "
                                       + "Remove with clear.")
                               String newsletterListUuid,
                               @Schema(description = "Id of an existing business profile") String businessId,
                               @Schema(description = "LodgingBusiness, or omit; needs placeLocality") String placeType,
                               @Schema(description = "The place's town or city, at most 128 characters")
                               String placeLocality,
                               @Schema(description = "The place's region, at most 128 characters")
                               String placeRegion,
                               @Schema(description = "Two-letter country code") String placeCountry,
                               @Schema(description = "Decimal degrees, given as a pair; stored to two decimals")
                               Double placeLat,
                               @Schema(description = "Decimal degrees, given as a pair; stored to two decimals")
                               Double placeLng,
                               @Schema(description = "An http or https URL where the place is booked, at most "
                                       + "255 characters")
                               String bookingUrl,
                               @Schema(description = "Allow signed-in readers to comment. Not clearable.")
                               Boolean commentsEnabled,
                               @Schema(description = "Email the owners when a comment needs approval. Not clearable.")
                               Boolean commentNotify,
                               @Schema(description = "Show popular posts from the site's other blogs on this blog. Not clearable.")
                               Boolean showMoreFromSite,
                               @Schema(description = "Show the share row under this blog's posts. Not clearable.")
                               Boolean shareRowEnabled,
                               @Schema(description = "NEWEST or OLDEST (any case): the order of this blog's listings. "
                                       + "Feeds stay newest first. Not clearable.")
                               String entryOrder,
                               @Schema(description = "WIDE or STANDARD (any case): STANDARD keeps the theme's narrower "
                                       + "page and line length on large screens. Not clearable.")
                               String pageWidth,
                               @Schema(description = "Id of a published page of this blog to show at its root; the "
                                       + "post list then moves to /posts. Remove with clear.")
                               String frontPage,
                               @Schema(description = THEME_DESCRIPTION)
                               String theme,
                               @Schema(description = "Fields to remove: tagline, copyright, customDomain, icon, about, "
                                       + "newsletterListUuid, businessId, frontPage, "
                                       + "placeType, placeLocality, placeRegion, placeCountry, placeLat with "
                                       + "placeLng, bookingUrl. Null never removes anything.")
                               List<String> clear) {
    }

    /** What {@code clear} may name on a weblog, and how each is cleared on the settings bean. */
    public static final ClearPolicy<WeblogConfigBean> WEBLOG_CLEAR = ClearPolicy.<WeblogConfigBean>builder()
            .field("tagline", b -> b.setTagline(null))
            .field("copyright", b -> b.setCopyright(null))
            .field("customDomain", b -> b.setCustomDomain(null))
            .field("icon", b -> b.setIcon(null))
            .field("about", b -> b.setAbout(null))
            .field("newsletterListUuid", b -> b.setNewsletterListUuid(null))
            .field("businessId", b -> b.setBusinessId(null))
            .field("frontPage", b -> b.setFrontPageId(null))
            .field("placeType", b -> b.setPlaceType(null))
            .field("placeLocality", b -> b.setPlaceLocality(null))
            .field("placeRegion", b -> b.setPlaceRegion(null))
            .field("placeCountry", b -> b.setPlaceCountry(null))
            .field("placeLat", b -> b.setPlaceLat(null))
            .field("placeLng", b -> b.setPlaceLng(null))
            .field("bookingUrl", b -> b.setBookingUrl(null))
            .pair("placeLat", "placeLng")
            .build();
    public static final List<String> WEBLOG_CLEARABLE = WEBLOG_CLEAR.names();
    /** The settings bean's name for the API's {@code frontPage}. */
    public static final String FRONT_PAGE_BEAN_FIELD = "frontPageId";
    public static final List<List<String>> WEBLOG_PAIRS = List.of(List.of("placeLat", "placeLng"));

    public static DiscoveryStatusView toView(DiscoveryStatus status) {
        return new DiscoveryStatusView(status.state().name(), status.lastAttempt(), status.lastSuccess(),
                status.scoredEntries(), status.candidates(), status.lastError());
    }

    public static AnalyticsStatusView toView(AnalyticsStatus status) {
        return new AnalyticsStatusView(status.configured(), status.reachable(), status.registered(), status.total(),
                status.failures().stream()
                        .map(f -> new AnalyticsFailureView(f.handle(), f.error(), f.at())).toList(),
                status.lastRead(), status.lastReadError());
    }

    public static UserView toView(User user, List<String> roles) {
        return new UserView(user.getUserName(), user.getScreenName(), user.getEmailAddress(),
                Boolean.TRUE.equals(user.getEnabled()), roles, user.isSsoBound(), user.getKind().name(),
                user.getFullName(), user.getLocale(), user.getTimeZone(), roles.contains(AccountRoles.EDITOR));
    }

    public static WeblogView toView(Weblog weblog, String absoluteUrl) {
        return new WeblogView(weblog.getId(), weblog.getHandle(), weblog.getName(),
                weblog.getTagline(), weblog.getCopyright(), weblog.getEmailAddress(), weblog.getLocale(),
                weblog.getTimeZone(), weblog.getEntryDisplayCount(),
                Boolean.TRUE.equals(weblog.getActive()),
                Boolean.TRUE.equals(weblog.getCommentsEnabled()),
                !Boolean.FALSE.equals(weblog.getCommentNotify()),
                !Boolean.FALSE.equals(weblog.getShowMoreFromSite()),
                !Boolean.FALSE.equals(weblog.getShareRowEnabled()), weblog.entryOrder().name(),
                weblog.pageWidth().name(), weblog.getFrontPageId(), weblog.getEditorTheme(),
                weblog.getEditorTheme(), weblog.getCreatorUserName(), weblog.getCustomDomain(), weblog.getIconPath(),
                weblog.getAbout(), weblog.getNewsletterListUuid(),
                weblog.getBusiness() == null ? null : weblog.getBusiness().getId(),
                weblog.getPlaceType(), weblog.getPlaceLocality(), weblog.getPlaceRegion(),
                weblog.getPlaceCountry(),
                weblog.getPlaceLat() == null ? null : weblog.getPlaceLat().doubleValue(),
                weblog.getPlaceLng() == null ? null : weblog.getPlaceLng().doubleValue(),
                weblog.getBookingUrl(), absoluteUrl);
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
        FieldErrors errors = new FieldErrors();
        if (patch.name() != null) {
            String name = patch.name().trim();
            errors.check("name", () -> {
                if (name.isBlank()) {
                    throw ApiException.problemOnField("name", ProblemCode.FIELD_BLANK, "name");
                }
                ColumnLimits.requireMaxLength("name", name, ColumnLimits.WEBLOG_NAME);
            });
            bean.setName(name);
        }
        if (patch.tagline() != null) {
            errors.check("tagline", () -> ColumnLimits.requireMaxLength("tagline", patch.tagline(),
                    ColumnLimits.TAGLINE));
            bean.setTagline(patch.tagline());
        }
        if (patch.copyright() != null) {
            errors.check("copyright", () -> ColumnLimits.requireMaxLength("copyright", patch.copyright(),
                    ColumnLimits.COPYRIGHT));
            bean.setCopyright(patch.copyright());
        }
        if (patch.emailAddress() != null) {
            errors.check("emailAddress", () -> ColumnLimits.requireMaxLength("emailAddress", patch.emailAddress(),
                    ColumnLimits.WEBLOG_EMAIL_ADDRESS));
            bean.setEmailAddress(patch.emailAddress());
        }
        if (patch.locale() != null) {
            errors.check("locale", () -> ColumnLimits.requireMaxLength("locale", patch.locale(),
                    ColumnLimits.LOCALE));
            bean.setLocale(patch.locale());
        }
        if (patch.timeZone() != null) {
            errors.check("timeZone", () -> ColumnLimits.requireMaxLength("timeZone", patch.timeZone(),
                    ColumnLimits.TIME_ZONE));
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
        if (patch.showMoreFromSite() != null) {
            bean.setShowMoreFromSite(patch.showMoreFromSite());
        }
        if (patch.shareRowEnabled() != null) {
            bean.setShareRowEnabled(patch.shareRowEnabled());
        }
        setIfPresent(enumName(patch.entryOrder(), EntryOrder::parse), bean::setEntryOrder);
        setIfPresent(enumName(patch.pageWidth(), PageWidth::parse), bean::setPageWidth);
        setIfPresent(patch.frontPage(), bean::setFrontPageId);
        setIfPresent(patch.customDomain(), bean::setCustomDomain);
        setIfPresent(patch.icon(), bean::setIcon);
        setIfPresent(patch.about(), bean::setAbout);
        setIfPresent(patch.newsletterListUuid(), bean::setNewsletterListUuid);
        setIfPresent(patch.businessId(), bean::setBusinessId);
        setIfPresent(patch.placeType(), bean::setPlaceType);
        setIfPresent(patch.placeLocality(), bean::setPlaceLocality);
        setIfPresent(patch.placeRegion(), bean::setPlaceRegion);
        setIfPresent(patch.placeCountry(), bean::setPlaceCountry);
        if (patch.placeLat() != null) {
            errors.value("placeLat", () -> twoDecimals("placeLat", patch.placeLat()))
                    .ifPresent(bean::setPlaceLat);
        }
        if (patch.placeLng() != null) {
            errors.value("placeLng", () -> twoDecimals("placeLng", patch.placeLng()))
                    .ifPresent(bean::setPlaceLng);
        }
        setIfPresent(patch.bookingUrl(), bean::setBookingUrl);
        WEBLOG_CLEAR.apply(bean, cleared);
        errors.throwIfAny();
        Set<String> touched = new HashSet<>(ClearFields.fieldsWithValue(patch));
        touched.addAll(cleared);
        if (touched.contains("frontPage")) {
            // The bean (and so every rule violation) names it frontPageId.
            touched.add(FRONT_PAGE_BEAN_FIELD);
        }
        return touched;
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * A stored-enum field as the bean takes it: the enum name when it parses,
     * the value as given when it does not (so the settings rules refuse it),
     * null when the patch omits it.
     */
    private static String enumName(String given,
            Function<String, Optional<? extends Enum<?>>> parse) {
        return given == null ? null : parse.apply(given).<String>map(Enum::name).orElse(given);
    }

    /** The column is numeric(5,2)/(6,2): store what the database would keep, as the form's value would be. */
    private static String twoDecimals(String field, Double value) {
        if (!Double.isFinite(value)) {
            throw ApiException.problemOnField(field, ProblemCode.FIELD_NOT_FINITE, field);
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
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
            throw ApiException.problem(ProblemCode.CONFIG_PROPERTY_NAME_REQUIRED);
        }
        if (findPropertyDef(name) == null) {
            throw ApiException.problemOnField(name, ProblemCode.CONFIG_PROPERTY_UNKNOWN, name);
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
