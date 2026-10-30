/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.ui.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.function.Supplier;
import java.util.List;
import java.io.IOException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Spring MVC interceptor that replaces the Struts 2 UISecurityInterceptor,
 * UIActionPrepareInterceptor, and UIWeblogInterceptor.
 *
 * <p>For every request handled by a Spring MVC controller it:
 * <ol>
 *   <li>Resolves the authenticated Pressroom {@link User} from Spring Security
 *       and stores it as request attribute "authenticatedUser".</li>
 *   <li>Resolves the target {@link Weblog} -- for a REST handler from the
 *       {@code {handle}} path variable only, for a JSP handler from the
 *       "weblog" request parameter (see {@link #resolveWeblogHandle}) -- and
 *       stores it as request attribute "actionWeblog", unless it is the site
 *       space and this request may not act on it ({@link #siteSpaceOffered}).</li>
 *   <li>Enforces security rules declared by {@link UISecurityEnforced}.</li>
 *   <li>Calls {@link UIActionPreparable#myPrepare()} if applicable.</li>
 * </ol>
 */
@Component
public class PressroomHandlerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PressroomHandlerInterceptor.class);

    private static final String LOGIN_URL = "/pressroom-ui/login.rol";
    private static final String ACCESS_DENIED_URL = "/pressroom-ui/access-denied.rol";

    private final WebloggerProvider provider;
    private final Weblogger weblogger;

    /**
     * @param provider  answers "is the business tier up?" -- the install and
     *                  setup controllers must run before it is, so every
     *                  check below is skipped until then
     * @param weblogger the facade the checks run against. {@code @Lazy} is
     *                  load-bearing: this interceptor is constructed at
     *                  context refresh, before {@code WebloggerStartup.prepare()}
     *                  has run, and a non-lazy injection would build the
     *                  business tier then and there.
     */
    public PressroomHandlerInterceptor(WebloggerProvider provider, @Lazy Weblogger weblogger) {
        this.provider = provider;
        this.weblogger = weblogger;
    }

    /**
     * The access check for a controller that asks for one.
     *
     * <p>Extracted from preHandle, which reached cyclomatic complexity 28 with
     * this inline and nested four deep. Four separate denials each repeated the
     * same shape -- throw for an api caller, redirect for a browser, return
     * false either way -- which is now {@link #deny}.
     *
     * @return true to let the request continue; false when the response has
     *         already been committed to a redirect
     */
    private boolean enforceSecurity(UISecurityEnforced secured, boolean apiHandler,
                                    HttpServletRequest request, HttpServletResponse response,
                                    User authenticatedUser, Weblog actionWeblog)
            throws Exception {

        if (!secured.isUserRequired()) {
            return true;
        }

        if (authenticatedUser == null) {
            log.debug("DENIED: required user not found, redirecting to login");
            return deny(apiHandler, () -> ApiException.unauthorized("Authentication required."),
                    LOGIN_URL, request, response);
        }

        UserManager umgr = weblogger.getUserManager();

        List<String> globalActions = secured.requiredGlobalPermissionActions();
        if (globalActions != null && !globalActions.isEmpty()) {
            GlobalPermission perm = new GlobalPermission(globalActions);
            if (!umgr.checkPermission(perm, authenticatedUser)) {
                log.debug("DENIED: user {} does not have global permission = {}",
                        authenticatedUser.getUserName(), perm);
                return deny(apiHandler, PressroomHandlerInterceptor::forbidden,
                        ACCESS_DENIED_URL, request, response);
            }
        }

        if (!secured.isWeblogRequired()) {
            return true;
        }

        if (actionWeblog == null) {
            log.warn("User {} unable to process action because no weblog was defined "
                            + "(check that the form provides the weblog value).",
                    authenticatedUser.getUserName());
            // Not found, not forbidden: no weblog was even resolved to check a
            // permission against, so there is nothing to be "forbidden" from --
            // matches BaseApiController.requireActionWeblog's identical
            // contract for the same condition.
            return deny(apiHandler, () -> ApiException.notFound("No such weblog."),
                    ACCESS_DENIED_URL, request, response);
        }

        List<String> weblogActions = secured.requiredWeblogPermissionActions();
        if (weblogActions != null && !weblogActions.isEmpty()) {
            WeblogPermission required = new WeblogPermission(actionWeblog, weblogActions);
            if (!umgr.checkPermission(required, authenticatedUser)) {
                log.debug("DENIED: user {} does not have weblog permission = {}",
                        authenticatedUser.getUserName(), required);
                // An api caller holding no role on the weblog at all gets the 404 an
                // unknown handle gets: a 403 would confirm the handle exists. A member
                // below the required level already knows it does, so stays 403.
                boolean member = !apiHandler || umgr.checkPermission(
                        new WeblogPermission(actionWeblog, List.of(WeblogPermission.EDIT_DRAFT)),
                        authenticatedUser);
                return deny(apiHandler,
                        member ? PressroomHandlerInterceptor::forbidden : () -> ApiException.notFound("No such weblog."),
                        ACCESS_DENIED_URL, request, response);
            }
        }

        return true;
    }

    /**
     * Turns one refused request away.
     *
     * <p>An api caller gets a problem response built from {@code apiFailure};
     * a browser gets a redirect. The failure is supplied lazily so that the
     * browser path never builds an exception it will not throw.
     */
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "redirectUrl is always LOGIN_URL or ACCESS_DENIED_URL, constants of this "
                    + "class, behind the container's own context path.")
    private boolean deny(boolean apiHandler, Supplier<ApiException> apiFailure,
                         String redirectUrl, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {

        if (apiHandler) {
            throw apiFailure.get();
        }
        response.sendRedirect(request.getContextPath() + redirectUrl);
        return false;
    }


    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // Only process Spring MVC handler methods (not static resources, etc.)
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        Object controller = handlerMethod.getBean();

        // Skip all checks if the application hasn't been bootstrapped yet
        // (install/setup controllers need to run before bootstrap)
        if (!provider.isBootstrapped()) {
            return true;
        }

        // --- 1. Resolve authenticated user from Spring Security ---
        User authenticatedUser = resolveAuthenticatedUser();
        if (authenticatedUser != null) {
            request.setAttribute("authenticatedUser", authenticatedUser);
        }

        // --- 2. Resolve weblog from URI template (API) or request parameter (JSP) ---
        boolean apiHandler = isApiHandler(handlerMethod);
        String weblogHandle = resolveWeblogHandle(request, apiHandler);
        Weblog actionWeblog = null;
        if (weblogHandle != null && !weblogHandle.isBlank()) {
            try {
                actionWeblog = weblogger.getWeblogManager().getWeblogByHandle(weblogHandle);
            } catch (Exception e) {
                log.warn("Error looking up weblog with handle: {}", weblogHandle, e);
            }
        }
        if (actionWeblog != null && actionWeblog.isSiteSpace()
                && !siteSpaceOffered(controller, apiHandler, authenticatedUser)) {
            log.debug("DENIED: the site space is not offered to this handler or caller");
            actionWeblog = null;
        }
        if (actionWeblog != null) {
            request.setAttribute("actionWeblog", actionWeblog);
        }

        // --- 3. Enforce security if controller implements UISecurityEnforced ---
        if (controller instanceof UISecurityEnforced secured
                && !enforceSecurity(secured, apiHandler, request, response,
                        authenticatedUser, actionWeblog)) {
            return false;
        }

        // --- 4. Call myPrepare() if controller implements UIActionPreparable ---
        if (controller instanceof UIActionPreparable preparable) {
            preparable.myPrepare();
        }

        return true;
    }

    /**
     * Whether this request may act on the site space, the hidden weblog that
     * owns the site's pages: only a global admin, only through a JSP handler
     * that {@linkplain UISecurityEnforced#allowsSiteSpace offers it} (Pages,
     * Media, Theme), never through the API.
     *
     * <p>When the answer is no, preHandle withholds the site space as though
     * its handle named no weblog: the action weblog stays unset, so a handler
     * that needs one is refused exactly as for an unknown handle (a redirect,
     * or the API's 404), and one that needs none never sees it. Withholding
     * here rather than refusing inside {@link #enforceSecurity} is what covers
     * the second kind: {@code WeblogsApi} requires no action weblog yet reads
     * it, so a refusal that only ran for weblog-requiring handlers would have
     * let an admin token read and rewrite the site space's settings.
     *
     * <p>A global admin holds every weblog permission implicitly, so without
     * this the site space would open every blog screen to one. A check that
     * cannot run withholds it.
     */
    private boolean siteSpaceOffered(Object controller, boolean apiHandler, User user) {
        if (apiHandler || user == null
                || !(controller instanceof UISecurityEnforced secured) || !secured.allowsSiteSpace()) {
            return false;
        }
        try {
            return weblogger.getUserManager().checkPermission(
                    new GlobalPermission(List.of(GlobalPermission.ADMIN)), user);
        } catch (Exception e) {
            // Fail closed: an admin check that could not run is not one that passed.
            log.error("Could not check whether {} may open the site space", user.getUserName(), e);
            return false;
        }
    }

    /**
     * The action weblog's handle for this request.
     *
     * <p>The JSP UI submits it as a {@code weblog} request parameter and has
     * no {@code {handle}} URI template variable on any of its routes; the
     * REST API carries it as a {@code {handle}} template variable, and a
     * REST handler method reads that same variable directly to decide what
     * it acts on. Spring MVC populates URI_TEMPLATE_VARIABLES_ATTRIBUTE
     * during getHandler(), before any interceptor runs, so both are readable
     * here -- which is what lets one interceptor enforce permissions for
     * both surfaces instead of the API growing a second, divergent
     * implementation.
     *
     * <p><b>An API handler names its weblog only by {@code {handle}}; the
     * {@code weblog} parameter is never read for one.</b> It is JSP
     * vocabulary, not REST vocabulary -- no API handler reads it. Two ways
     * reading it went wrong:
     * <ul>
     *   <li>On a {@code {handle}}-carrying route, preferring the parameter
     *       would let this resolver (and therefore the permission check and
     *       ApiScopeInterceptor's scope ceiling) agree with each other while
     *       both disagree with the handler, which reads {@code {handle}}
     *       directly: a caller could pass the checks for one weblog while
     *       the handler acted on another.</li>
     *   <li>On a route with no {@code {handle}} at all ({@code POST
     *       /v1/weblogs}, {@code /v1/admin/**}, {@code GET /v1/weblogs}),
     *       falling back to the parameter resolved an action weblog the
     *       handler never acts on. ApiScopeInterceptor accepts any resolved
     *       weblog equal to a limited token's, so {@code ?weblog=<its
     *       handle>} turned that route's default 404 into a pass: a token
     *       limited to one weblog could create weblogs, and a limited ADMIN
     *       token could list every user. {@code
     *       WeblogParameterDispatchTest} pins both.</li>
     * </ul>
     * A JSP handler has no {@code {handle}} route, so for it the parameter
     * is the only source, unchanged.
     *
     * <p>Package-visible and static so it can be tested without a container.
     *
     * @param apiHandler true for a handler under {@code ui.restapi}
     *                   ({@link #isApiHandler})
     */
    static String resolveWeblogHandle(HttpServletRequest request, boolean apiHandler) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            Object fromPath = map.get("handle");
            if (fromPath instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        if (apiHandler) {
            return null;
        }
        String handle = request.getParameter("weblog");
        if (handle != null && !handle.isBlank()) {
            return handle;
        }
        return null;
    }

    /** The package every REST API controller lives under. */
    private static final String API_PACKAGE_PREFIX = ApiExceptionHandler.class.getPackageName();

    /**
     * True when a permission failure on this request must be answered as
     * problem+json rather than a browser redirect.
     *
     * <p>Decided from the handler's own bean-type package, never the
     * request URI -- the same discriminator {@code ApiScopeInterceptor}'s
     * {@code @AdminScoped} check already uses, and for the same reason
     * (see that class's javadoc): {@code getRequestURI()} is undecoded per
     * the servlet spec while Spring routes on the decoded path, so a
     * string test like {@code startsWith("/api/")} can be defeated by
     * encoding while the request still reaches an API-mapped controller.
     *
     * <p>This is the fix for a real bug, not speculative hardening: before
     * this method existed, EVERY permission failure -- API or JSP alike --
     * was answered with a 302 redirect to a login/access-denied JSP page.
     * An automation client following that redirect receives an HTTP 200
     * carrying an HTML form: a *success* status with no data, which is
     * worse to debug than a clean 401/403 and can be mistaken for an empty
     * result. {@code EntriesApiDispatchTest} is what caught this, end to
     * end, through a real dispatch -- exactly the class of bug a unit test
     * of {@code WeblogOwnership}/{@code ApiException} in isolation cannot
     * see.
     */
    private static boolean isApiHandler(HandlerMethod handlerMethod) {
        String pkg = handlerMethod.getBeanType().getPackageName();
        // startsWith(prefix + ".") rather than a bare startsWith(prefix): a
        // sibling package that merely shares the string prefix (e.g. a
        // hypothetical "ui.restapiv2") must not be misclassified.
        return API_PACKAGE_PREFIX.equals(pkg) || pkg.startsWith(API_PACKAGE_PREFIX + ".");
    }

    /**
     * A permission failure's detail is intentionally generic -- it must
     * never name the weblog, the permission, or the user, any of which
     * would tell an unauthorized caller more than "no" about a resource
     * they cannot act on.
     */
    private static ApiException forbidden() {
        return ApiException.forbidden("You do not have permission to perform this action.");
    }

    /**
     * Resolve the Pressroom User from the Spring Security context.
     * Returns null if no user is authenticated.
     *
     * <p>A reader signed in through the provider carries a {@link PressroomOidcUser},
     * whose name is the Pressroom username. Only that principal type is trusted for
     * one: a bare OIDC user's name is the provider's subject, never a username.
     */
    private User resolveAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        String username = null;
        if (principal instanceof UserDetails userDetails) {
            username = userDetails.getUsername();
        } else if (principal instanceof PressroomOidcUser reader) {
            username = reader.getName();
        } else if (principal instanceof String name && !"anonymousUser".equals(name)) {
            // Some authentication tokens store the username directly as a String
            username = name;
        }

        if (username == null) {
            return null;
        }

        try {
            return weblogger.getUserManager().getUserByUserName(username);
        } catch (Exception e) {
            // Fail closed: a store that cannot answer leaves the user null and enforceSecurity then refuses
            // the request. Do not return a cached or default user here.
            log.error("Error looking up user: {}", username, e);
            return null;
        }
    }
}
