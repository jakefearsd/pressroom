package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The bodies of {@code GET /v1/ping} and {@code GET /v1/me}. They were
 * {@code Map}s, which openapi.json could only describe as a map of anything;
 * as records they serialise to the same JSON (spec
 * 2026-11-20-api-contract-design, Task 4; {@code UntypedResponsesJsonTest}).
 */
public final class MetaDtos {

    /** The liveness answer, always the same. */
    public static final PingView PING_OK = new PingView("ok");

    /** The scope of a caller that did not authenticate with a token: serialises as {@code {}}. */
    public static final TokenScope NO_TOKEN_SCOPE = new TokenScope(null, null);

    private MetaDtos() {
    }

    /** The liveness answer. */
    public record PingView(
            @Schema(description = "Always ok: the API is answering.", requiredMode = Schema.RequiredMode.REQUIRED)
            String status) {
    }

    /** Who the caller is, what its token may do, and the weblogs it can act on. */
    public record MeView(
            @Schema(description = "The account's user name.", requiredMode = Schema.RequiredMode.REQUIRED)
            String userName,
            @Schema(description = "The account's display name.")
            String screenName,
            @Schema(description = "PERSON or AGENT: whether the account is a person or an agent.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String kind,
            @Schema(description = "True when the account is a site admin.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean globalAdmin,
            @Schema(description = "The token's ceiling: its role, and the weblog it is limited to. An empty "
                    + "object for a caller that did not authenticate with a token.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            TokenScope tokenScope,
            @Schema(description = "The weblogs this token can act on, sorted by handle: the one a pinned token "
                    + "is limited to, or every weblog the account belongs to.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<WeblogMembership> weblogs) {
    }

    /**
     * A token's ceiling. Both members are null, and so the object is empty,
     * for a caller that did not authenticate with a token.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenScope(
            @Schema(description = "The weblog the token is limited to; an empty string when it is not limited "
                    + "to one.")
            String weblog,
            @Schema(description = "READ, POST or ADMIN: the token's role.")
            String role) {
    }

    /** One weblog the caller can act on. */
    public record WeblogMembership(
            @Schema(description = "The weblog's handle.", requiredMode = Schema.RequiredMode.REQUIRED)
            String handle,
            @Schema(description = "The weblog's name; an empty string when it has none.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "ADMIN, POST or EDIT_DRAFT: the highest permission the account holds on it.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String permission) {
    }

    /**
     * What the main menu shows: the weblogs the caller belongs to with the facts each row carries, and
     * the site space's handle for a site admin.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyWeblogsView(
            @Schema(description = "The weblogs this caller can act on, sorted by handle: the one a pinned token "
                    + "is limited to, or every weblog the account belongs to.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<MyWeblog> weblogs,
            @Schema(description = "The handle of the site space, the hidden weblog that holds the site's own "
                    + "pages (created on a site admin's first read); present for a site admin with an unpinned "
                    + "token only.")
            String siteSpace) {
    }

    /** One row of the main menu. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyWeblog(
            @Schema(description = "The weblog's handle.", requiredMode = Schema.RequiredMode.REQUIRED)
            String handle,
            @Schema(description = "The weblog's name; an empty string when it has none.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "The weblog's about text, plain; absent when it has none.")
            String about,
            @Schema(description = "The weblog's theme: a shared theme id, or custom.")
            String theme,
            @Schema(description = "The weblog's absolute web address, as readers reach it.")
            String absoluteUrl,
            @Schema(description = "ADMIN, POST or EDIT_DRAFT: the highest permission the account holds on it.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String permission,
            @Schema(description = "The permission actions the account's row holds, as stored: admin, post and "
                    + "edit_draft. A weblog's creator holds admin alone.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> actions,
            @Schema(description = "Whether the account may moderate this weblog's comments.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean canModerate,
            @Schema(description = "The number of entries.", requiredMode = Schema.RequiredMode.REQUIRED)
            long entryCount,
            @Schema(description = "Comments awaiting approval; 0 for an account that may not moderate.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            long pendingComments) {
    }

    /** The site facts a client needs before it draws a screen; the same for every authenticated caller. */
    public record MetaView(
            @Schema(description = "The Pressroom version, as the footer shows it; null when unknown.")
            String version,
            @Schema(description = "The site's absolute URL without a trailing slash; null when none is configured.")
            String siteUrl,
            @Schema(description = "Which optional features the site has switched on.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Features features,
            @Schema(description = "Sizes the API holds every caller to.", requiredMode = Schema.RequiredMode.REQUIRED)
            Limits limits) {
    }

    /** Feature switches, each read afresh from its setting on every request. */
    public record Features(
            @Schema(description = "True when weblog owners may use custom templates and stylesheets.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean customThemes,
            @Schema(description = "True when a weblog may have more than one member.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean groupBlogging,
            @Schema(description = "True when any user, not only a site admin, may create a weblog.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean userWeblogCreation,
            @Schema(description = "True when media uploads are switched on.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean uploads,
            @Schema(description = "True when readers can sign in (single sign-on is configured).",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean readerSignIn,
            @Schema(description = "True when an analytics service is configured; false is what makes a traffic "
                    + "read answer NOT_CONFIGURED.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean analytics) {
    }

    /** The API's size limits. */
    public record Limits(
            @Schema(description = "The largest JSON request body, in bytes; a larger one is refused with 413.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int maxBodyBytes,
            @Schema(description = "The largest page a list answers; a bigger limit is capped to it.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int maxListLimit,
            @Schema(description = "The most characters an entry's text may hold.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int entryTextMaxChars) {
    }

    /** The caller's own account, read-only; no password, hash or token. */
    public record ProfileView(
            @Schema(description = "The account's user name.", requiredMode = Schema.RequiredMode.REQUIRED)
            String userName,
            @Schema(description = "The account's display name.")
            String screenName,
            @Schema(description = "The account holder's full name.")
            String fullName,
            @Schema(description = "The account's email address.")
            String emailAddress,
            @Schema(description = "The account's locale, such as en_US.")
            String locale,
            @Schema(description = "The account's time zone, such as America/Denver.")
            String timeZone,
            @Schema(description = "PERSON or AGENT: whether the account is a person or an agent.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String kind) {
    }

    /** One locale, as the create and settings forms offer it. */
    public record LocaleChoice(
            @Schema(description = "The locale id the forms post, such as en_US.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            @Schema(description = "The locale's display name, such as English (United States).",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name) {
    }

    /** What the create-weblog screen offers: shared themes, locales and time zones. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeblogOptions(
            @Schema(description = "The shared themes a new weblog may start on, in the theme manager's order.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<ThemeDtos.ThemeChoice> themes,
            @Schema(description = "The theme the form preselects (journal), absent when it is not enabled.")
            String defaultTheme,
            @Schema(description = "Every locale the forms offer, sorted by display name.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<LocaleChoice> locales,
            @Schema(description = "Every time zone id the forms offer, sorted.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> timeZones) {
    }
}
