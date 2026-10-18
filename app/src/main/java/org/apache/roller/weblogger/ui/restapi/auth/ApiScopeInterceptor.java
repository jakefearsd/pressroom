package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies the token's ceiling on top of the permission checks
 * RollerHandlerInterceptor already performs.
 *
 * <p>This narrows; it never grants. A request that passes here still has to
 * satisfy the caller's real GlobalPermission/WeblogPermission.
 *
 * <p><b>Must be registered (see {@code WebMvcConfig}) AFTER
 * RollerHandlerInterceptor.</b> The weblog-scope check below reads
 * {@code request.getAttribute("actionWeblog")} -- the SAME weblog
 * RollerHandlerInterceptor resolved and will enforce permissions against --
 * rather than re-deriving its own answer from the {@code {handle}} URI
 * template variable. Comparing against the path directly would let a
 * request whose {@code weblog} query parameter names a DIFFERENT weblog
 * than its path segment pass the ceiling check while the permission check
 * (and the business logic behind it) acts on the query-parameter weblog
 * instead: the ceiling and the enforcement would be looking at two
 * different targets, and a token scoped to weblog B could act on weblog A
 * by naming B in the path and A in {@code ?weblog=}.
 * {@code ApiScopeInterceptorDispatchTest} dispatches real requests through
 * the real {@code WebMvcConfig} registration and pins both the ordering and
 * this exact scenario.
 *
 * <p>For an API handler RollerHandlerInterceptor resolves that weblog from
 * the {@code {handle}} path variable only, never from {@code ?weblog=}: a
 * route with no {@code {handle}} has no action weblog, so a limited token
 * meets the default 404 below however the query string is dressed
 * ({@code WeblogParameterDispatchTest}).
 */
public class ApiScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        ApiPrincipal principal = currentPrincipal();
        if (principal == null) {
            return true;
        }

        if (principal.scopeWeblog() != null) {
            checkWeblogScope(request, handler, principal.scopeWeblog());
        }

        ApiToken.Role role = principal.scopeRole();
        if (isAdminScoped(handler) && role != ApiToken.Role.ADMIN) {
            throw ApiException.forbidden("This token is not scoped for administration.");
        }
        if (isWrite(request) && !mayWrite(role)) {
            throw ApiException.forbidden("This token is read-only.");
        }
        return true;
    }

    /**
     * A scoped token is a ceiling, so a route this check cannot evaluate
     * must not silently become unlimited. If RollerHandlerInterceptor
     * resolved a weblog, the token's scope must match it (404, not 403 -- a
     * 403 would confirm the weblog exists). If it resolved none at all --
     * the route names its resource some other way, as {@code /v1/tokens}
     * does -- the default is deny, with an explicit, narrow exception for
     * the handful of routes that must work for every token regardless of
     * scope (see {@link WeblogScopeExempt}).
     */
    private static void checkWeblogScope(HttpServletRequest request, Object handler, String scopeWeblog) {
        if (request.getAttribute("actionWeblog") instanceof Weblog weblog) {
            if (!scopeWeblog.equals(weblog.getHandle())) {
                throw ApiException.notFound("No such weblog.");
            }
            return;
        }
        if (!isWeblogScopeExempt(handler)) {
            // A {handle} the resolver could not find keeps the answer an
            // existing weblog outside the pin gets, or the wording would tell
            // which handles exist. Only a route with no {handle} at all, decided
            // by its shape rather than by stored data, may say why.
            throw ApiException.notFound(namesAWeblog(request)
                    ? "No such weblog."
                    : "This token is limited to the weblog '" + scopeWeblog + "', and this route is not "
                            + "part of one weblog. Use a token with no weblog limit.");
        }
    }

    private static boolean namesAWeblog(HttpServletRequest request) {
        return request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars
                && vars.containsKey("handle");
    }

    private static ApiPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof ApiPrincipal p ? p : null;
    }

    /**
     * Decided from the resolved handler, never the request URI.
     * {@code getRequestURI()} is undecoded per the servlet spec while Spring
     * routes on decoded, semicolon-stripped segments, so a string match
     * (e.g. {@code startsWith("/v1/admin/")}) can be defeated by
     * {@code /v1/%61dmin/users} or {@code /v1/admin;x=1/users} while the
     * request still reaches a controller mapped at {@code /v1/admin/users}.
     */
    private static boolean isAdminScoped(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && handlerMethod.getBeanType().isAnnotationPresent(AdminScoped.class);
    }

    private static boolean isWeblogScopeExempt(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && handlerMethod.getMethod().isAnnotationPresent(WeblogScopeExempt.class);
    }

    /**
     * Allowlist, not denylist: only a role affirmatively known to write is
     * treated as permitted to. A denylist keyed on {@code role == READ}
     * defaults an unexpected {@code null} or a future enum value to
     * write-permitted; this defaults it to read-only instead.
     */
    private static boolean mayWrite(ApiToken.Role role) {
        return role == ApiToken.Role.POST || role == ApiToken.Role.ADMIN;
    }

    private static boolean isWrite(HttpServletRequest request) {
        String method = request.getMethod();
        // Exactly GET or HEAD: methods are case-sensitive (RFC 9110), as Spring's dispatch is.
        return !("GET".equals(method) || "HEAD".equals(method));
    }
}
