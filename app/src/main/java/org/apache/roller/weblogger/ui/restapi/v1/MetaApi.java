package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.apache.roller.weblogger.ui.restapi.auth.WeblogScopeExempt;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

/**
 * Liveness for the API surface. Mapped at {@code /v1} because the container
 * strips the {@code /api} prefix -- see ServletRegistrationConfig's
 * API_URL_PATTERNS.
 */
@RestController
@RequestMapping("/v1")
public class MetaApi {

    /**
     * {@code @Lazy} is load-bearing, as on {@code BaseApiController}: this
     * controller is constructed at context refresh, before the business tier
     * has been bootstrapped; only {@link #me} ever touches it.
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
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Who the caller is, and -- when authenticated via a scoped API token --
     * what that token's ceiling is. {@code authenticatedUser} is the request
     * attribute {@code RollerHandlerInterceptor} sets for every request,
     * Bearer- or Basic-authenticated alike.
     *
     * <p>{@code @WeblogScopeExempt} for the same reason as {@link #ping()}:
     * this route names no weblog, and a weblog-scoped token must still be
     * able to ask who it is.
     */
    @WeblogScopeExempt
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) throws WebloggerException {
        User user = (User) request.getAttribute("authenticatedUser");
        if (user == null) {
            throw ApiException.forbidden("Not authenticated.");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ApiPrincipal principal =
                auth != null && auth.getDetails() instanceof ApiPrincipal p ? p : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", user.getUserName());
        body.put("screenName", user.getScreenName());
        body.put("kind", user.getKind().name());
        body.put("globalAdmin", isGlobalAdmin(user));
        body.put("tokenScope", principal == null ? Map.of() : Map.of(
                "weblog", principal.scopeWeblog() == null ? "" : principal.scopeWeblog(),
                "role", principal.scopeRole().name()));
        body.put("weblogs", weblogsFor(user, principal));
        return ResponseEntity.ok(body);
    }

    /**
     * The weblogs this token can act on: the one it is limited to, or every
     * weblog its user is a member of. This tree has no pending invitations
     * (WeblogPermission carries no such flag), so membership is the permission
     * row. A failed lookup propagates as a 500, never as an empty list.
     */
    private List<Map<String, String>> weblogsFor(User user, ApiPrincipal principal) throws WebloggerException {
        List<Map<String, String>> weblogs = new ArrayList<>();
        for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(user)) {
            String handle = permission.getObjectId();
            if (principal != null && principal.scopeWeblog() != null
                    && !principal.scopeWeblog().equals(handle)) {
                continue;
            }
            Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle);
            if (weblog != null) {
                weblogs.add(Map.of(
                        "handle", weblog.getHandle(),
                        "name", Objects.toString(weblog.getName(), ""),
                        "permission", highest(permission)));
            }
        }
        weblogs.sort(Comparator.comparing(w -> w.get("handle")));
        return weblogs;
    }

    /** The strongest action a permission row grants, as the API names it. */
    private static String highest(WeblogPermission permission) {
        List<String> actions = permission.getActionsAsList();
        if (actions.contains(WeblogPermission.ADMIN)) {
            return "ADMIN";
        }
        return actions.contains(WeblogPermission.POST) ? "POST" : "EDIT_DRAFT";
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
