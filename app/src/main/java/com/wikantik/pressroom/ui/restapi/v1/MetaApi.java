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
import com.wikantik.pressroom.business.Weblogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

/**
 * Liveness for the API surface. Mapped at {@code /v1} because the container
 * strips the {@code /api} prefix -- see ServletRegistrationConfig's
 * API_URL_PATTERNS.
 */
@Tag(name = "Meta", description = "Liveness, and who the caller is.")
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
                        Objects.toString(weblog.getName(), ""), highest(permission)));
            }
        }
        weblogs.sort(Comparator.comparing(MetaDtos.WeblogMembership::handle));
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
