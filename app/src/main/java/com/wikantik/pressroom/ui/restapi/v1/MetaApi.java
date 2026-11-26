package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.Memberships;
import com.wikantik.pressroom.ui.controllers.WeblogCreationRules;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;
import com.wikantik.pressroom.ui.restapi.dto.ThemeDtos;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.dto.MetaDtos;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.ui.restapi.auth.Caller;
import com.wikantik.pressroom.ui.restapi.auth.WeblogScopeExempt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.boot.SsoConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.core.security.sso.SsoSettings;
import com.wikantik.pressroom.ui.restapi.ApiBodyLimitFilter;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.business.Weblogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

/**
 * Liveness, site facts and the caller's identity and profile. Mapped at {@code /v1} because the container
 * strips the {@code /api} prefix -- see ServletRegistrationConfig's
 * API_URL_PATTERNS.
 */
@Tag(name = "Meta", description = "Liveness, site facts, and who the caller is.")
@RestController
@RequestMapping("/v1")
public class MetaApi {

    /**
     * {@code @Lazy} is load-bearing, as on {@code BaseApiController}: this
     * controller is constructed at context refresh, before the business tier
     * has been bootstrapped; only the routes that read site or account data ({@code me}, {@code meta},
     * {@code me/profile}) touch it.
     */
    @Autowired
    @Lazy
    private Weblogger weblogger;

    /**
     * A weblog-scoped token has no weblog to check on this route -- exempt,
     * or {@code ApiScopeInterceptor}'s deny-by-default would lock a CLI's
     * own self-check out of its own scope.
     */
    @WeblogScopeExempt
    @Operation(operationId = "pingGet")
    @GetMapping("/ping")
    public ResponseEntity<MetaDtos.PingView> ping() {
        return ResponseEntity.ok(MetaDtos.PING_OK);
    }

    /**
     * Who the caller is, and -- when authenticated via a scoped API token --
     * what that token's ceiling is. {@code authenticatedUser} is the request
     * attribute {@code PressroomHandlerInterceptor} sets for every
     * authenticated request. Only a token reaches it: HTTP Basic is refused
     * here by the security chain (it authenticates only the token resource).
     *
     * <p>{@code @WeblogScopeExempt} for the same reason as {@link #ping()}:
     * this route names no weblog, and a weblog-scoped token must still be
     * able to ask who it is.
     */
    @WeblogScopeExempt
    @Operation(operationId = "meGet")
    @GetMapping("/me")
    public ResponseEntity<MetaDtos.MeView> me(HttpServletRequest request) throws WebloggerException {
        User user = (User) request.getAttribute("authenticatedUser");
        if (user == null) {
            throw ApiException.authenticationRequired();
        }
        ApiPrincipal principal = Caller.current().principal();
        MetaDtos.TokenScope scope = principal == null ? MetaDtos.NO_TOKEN_SCOPE : new MetaDtos.TokenScope(
                principal.scopeWeblog() == null ? "" : principal.scopeWeblog(), principal.scopeRole().name());
        return ResponseEntity.ok(new MetaDtos.MeView(user.getUserName(), user.getScreenName(), user.getKind().name(),
                isGlobalAdmin(user), scope, weblogsFor(user, principal)));
    }

    /**
     * The site facts a client needs before it draws a screen. The same for every
     * authenticated caller, and read afresh each time (nothing is cached), so a
     * switch an operator flips shows in the next response. Authentication is
     * required (401 otherwise) though no permission is: the version is public,
     * but nothing needs these facts before sign-in.
     */
    @WeblogScopeExempt
    @Operation(operationId = "metaGet", summary = "The site's version, address, feature switches and limits.")
    @GetMapping("/meta")
    public ResponseEntity<MetaDtos.MetaView> meta(HttpServletRequest request) {
        requireUser(request);
        String version = weblogger.getVersion();
        String url = SsoConfig.configuredAbsoluteUrl();
        String siteUrl = url == null || url.isBlank() ? null : url.strip().replaceAll("/+$", "");
        MetaDtos.Features features = new MetaDtos.Features(
                WebloggerRuntimeConfig.getBooleanProperty("themes.customtheme.allowed"),
                WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"),
                WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation"),
                WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled"),
                SsoSettings.fromConfig().enabled(),
                weblogger.getAnalyticsManager().getStatus().configured());
        MetaDtos.Limits limits = new MetaDtos.Limits(ApiBodyLimitFilter.MAX_BODY_BYTES, Paging.MAX_LIMIT,
                ColumnLimits.ENTRY_TEXT);
        return ResponseEntity.ok(new MetaDtos.MetaView(version == null || version.isBlank() ? null : version,
                siteUrl == null || siteUrl.isEmpty() ? null : siteUrl, features, limits));
    }

    /**
     * What the create-weblog screen offers: the shared themes a new weblog may start on (with the preview
     * the screen shows), and the locales and time zones its dropdowns list, exactly as the JSP's do. The
     * same for every authenticated caller, read afresh; no permission is asked (a caller who may not create
     * a weblog is told so by {@code POST /v1/weblogs}, as the screen's refusal is).
     */
    @WeblogScopeExempt
    @Operation(operationId = "metaWeblogOptionsGet",
            summary = "The themes, locales and time zones the create-weblog screen offers.")
    @GetMapping("/meta/weblog-options")
    public ResponseEntity<MetaDtos.WeblogOptions> weblogOptions(HttpServletRequest request) {
        requireUser(request);
        List<ThemeDtos.ThemeChoice> themes = WeblogCreationRules.offeredThemes(weblogger.getThemeManager()).stream()
                .map(ThemeDtos::toChoice).toList();
        String preselected = themes.stream().map(ThemeDtos.ThemeChoice::id)
                .filter(WeblogCreationRules.DEFAULT_THEME_ID::equals).findFirst().orElse(null);
        List<MetaDtos.LocaleChoice> locales = UIUtils.getLocales().stream()
                .map(l -> new MetaDtos.LocaleChoice(l.toString(), l.getDisplayName())).toList();
        return ResponseEntity.ok(new MetaDtos.WeblogOptions(themes, preselected, locales, UIUtils.getTimeZones()));
    }

    /**
     * The caller's own account, read-only: the fields the Profile page shows,
     * never a password field. Changing any of it is session-only policy.
     */
    @WeblogScopeExempt
    @Operation(operationId = "meProfileGet", summary = "The caller's own account details (read-only).")
    @GetMapping("/me/profile")
    public ResponseEntity<MetaDtos.ProfileView> profile(HttpServletRequest request) {
        User user = requireUser(request);
        return ResponseEntity.ok(new MetaDtos.ProfileView(user.getUserName(), user.getScreenName(),
                user.getFullName(), user.getEmailAddress(), user.getLocale(), user.getTimeZone(),
                user.getKind().name()));
    }

    private static User requireUser(HttpServletRequest request) {
        User user = (User) request.getAttribute("authenticatedUser");
        if (user == null) {
            throw ApiException.authenticationRequired();
        }
        return user;
    }

    /**
     * The weblogs this token can act on: the one it is limited to, or every
     * weblog its user is a member of. This tree has no pending invitations
     * (WeblogPermission carries no such flag), so membership is the permission
     * row. A failed lookup propagates as a 500, never as an empty list.
     */
    private List<MetaDtos.WeblogMembership> weblogsFor(User user, ApiPrincipal principal)
            throws WebloggerException {
        List<MetaDtos.WeblogMembership> weblogs = new ArrayList<>();
        for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(user)) {
            String handle = permission.getObjectId();
            if (principal != null && principal.scopeWeblog() != null
                    && !principal.scopeWeblog().equals(handle)) {
                continue;
            }
            Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle);
            if (weblog != null) {
                weblogs.add(new MetaDtos.WeblogMembership(weblog.getHandle(),
                        Objects.toString(weblog.getName(), ""), Memberships.strongestLevel(permission)));
            }
        }
        weblogs.sort(Comparator.comparing(MetaDtos.WeblogMembership::handle));
        return weblogs;
    }

    /**
     * Whether the user holds the global {@code admin} action; a check that
     * cannot be answered is a denial (was {@code User.hasGlobalPermission}).
     */
    private boolean isGlobalAdmin(User user) {
        try {
            return weblogger.getUserManager().checkPermission(
                    new GlobalPermission(List.of(GlobalPermission.ADMIN)), user);
        } catch (WebloggerException e) {
            return false;
        }
    }
}
