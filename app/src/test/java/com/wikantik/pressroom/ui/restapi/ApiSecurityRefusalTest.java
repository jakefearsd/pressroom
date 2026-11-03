package com.wikantik.pressroom.ui.restapi;

import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * What a refused REST caller gets, as opposed to a refused browser.
 *
 * <p>The interceptor decides between the two by the controller's package, so
 * these tests live in the api package rather than beside
 * PressroomHandlerInterceptorTest -- a controller declared anywhere else is a
 * browser controller by definition and cannot exercise this path. That is why
 * the api half had no tests: the other test class is structurally incapable of
 * reaching it.
 *
 * <p>The distinction is not cosmetic. Redirecting an api client to the login
 * page hands it a 302 and a page of HTML where it expected a problem
 * document -- the caller sees "success, weird body" rather than "denied", which
 * is the failure mode this whole branch exists to prevent.
 */
class ApiSecurityRefusalTest {

    private static final String CONTEXT = "/pressroom";

    private PressroomHandlerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;

    private User alice;
    private Weblog aliceBlog;

    @BeforeEach
    void setUp() throws Exception {
        request = new MockHttpServletRequest();
        request.setContextPath(CONTEXT);
        response = new MockHttpServletResponse();
        weblogger = MockWeblogger.attached();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        interceptor = new PressroomHandlerInterceptor(provider, weblogger.weblogger());

        alice = new User();
        alice.setUserName("alice");
        alice.setId("user-alice");

        aliceBlog = new Weblog();
        aliceBlog.setHandle("aliceblog");
        aliceBlog.setId("weblog-alice");

        when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
        when(weblogger.weblogManager().getWeblogByHandle("aliceblog")).thenReturn(aliceBlog);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        weblogger.detach();
    }

    private void signInAsAlice() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "x",
                        AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    /** An API route names its weblog by {@code {handle}}, as Spring's handler mapping exposes it. */
    private void nameWeblogInPath(String handle) {
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("handle", handle));
    }

    private static HandlerMethod handlerFor(Object controller) throws Exception {
        return new HandlerMethod(controller, controller.getClass().getMethod("handle"));
    }

    private ApiException refusalFrom(ApiController controller) {
        return assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, response, handlerFor(controller)));
    }

    // --- the four refusals -------------------------------------------------

    @Test
    void anAnonymousApiCallerIsToldToAuthenticateRatherThanRedirected() {
        ApiException refusal = refusalFrom(
                new ApiController(true, List.of(), false, List.of()));

        assertEquals(401, refusal.getStatus(),
                "an api client must be told it is unauthenticated");
        assertNull(response.getRedirectedUrl(),
                "and must never be redirected to a login page, which it cannot follow "
                        + "and would read as a successful response with an odd body");
    }

    @Test
    void anApiCallerWithoutTheRequiredGlobalPermissionIsForbidden() throws Exception {
        signInAsAlice();
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any()))
                .thenReturn(false);

        ApiException refusal = refusalFrom(
                new ApiController(true, List.of("admin"), false, List.of()));

        assertEquals(403, refusal.getStatus());
        assertNull(response.getRedirectedUrl(), "no redirect on the api path");
    }

    /**
     * Deliberately 404 rather than 403: no weblog was resolved, so there is
     * nothing to be forbidden from. This mirrors
     * BaseApiController.requireActionWeblog's contract for the same condition,
     * and the asymmetry is easy to "fix" into a 403 by someone tidying up.
     */
    @Test
    void anApiCallerWhoseWeblogDidNotResolveGetsNotFoundNotForbidden() throws Exception {
        signInAsAlice();

        ApiException refusal = refusalFrom(
                new ApiController(true, List.of(), true, List.of("post")));

        assertEquals(404, refusal.getStatus(),
                "an unresolved weblog is a missing resource, not a forbidden one");
    }

    /**
     * A caller holding no role on an existing weblog gets the same 404 as for
     * a handle that does not exist: a 403 here would confirm the handle, and
     * let any token holder probe which weblogs exist (README, Errors: 404,
     * never 403, for what the caller may not see).
     */
    @Test
    void anApiCallerWithNoRoleOnTheWeblogGetsNotFoundNotForbidden() throws Exception {
        signInAsAlice();
        nameWeblogInPath("aliceblog");
        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), any()))
                .thenReturn(false);

        ApiException refusal = refusalFrom(
                new ApiController(true, List.of(), true, List.of("post")));

        assertEquals(404, refusal.getStatus());
        assertEquals("No such weblog.", refusal.getMessage());
    }

    /** A member below the required level already knows the weblog exists: that stays 403. */
    @Test
    void aMemberBelowTheRequiredLevelIsForbidden() throws Exception {
        signInAsAlice();
        nameWeblogInPath("aliceblog");
        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), any()))
                .thenAnswer(call -> ((WeblogPermission) call.getArgument(0))
                        .getActionsAsList().equals(List.of(WeblogPermission.EDIT_DRAFT)));

        ApiException refusal = refusalFrom(
                new ApiController(true, List.of(), true, List.of("post")));

        assertEquals(403, refusal.getStatus());
    }

    // --- and what is not refused ------------------------------------------

    @Test
    void anApiControllerThatRequiresNoUserRunsForAnonymousCallers() throws Exception {
        assertTrue(interceptor.preHandle(request, response,
                        handlerFor(new ApiController(false, List.of(), false, List.of()))),
                "a public api endpoint is reachable without signing in");
        assertNull(response.getRedirectedUrl());
    }

    @Test
    void anApiCallerHoldingEveryRequirementProceeds() throws Exception {
        signInAsAlice();
        nameWeblogInPath("aliceblog");
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response,
                        handlerFor(new ApiController(true, List.of("login"), true, List.of("post")))),
                "nothing is refused when every requirement is met");
    }

    /** A controller in the api package, declaring whatever a test needs. */
    public static class ApiController implements UISecurityEnforced {
        private final boolean userRequired;
        private final List<String> globalActions;
        private final boolean weblogRequired;
        private final List<String> weblogActions;

        ApiController(boolean userRequired, List<String> globalActions,
                boolean weblogRequired, List<String> weblogActions) {
            this.userRequired = userRequired;
            this.globalActions = globalActions;
            this.weblogRequired = weblogRequired;
            this.weblogActions = weblogActions;
        }

        public String handle() {
            return "ok";
        }

        @Override
        public boolean isUserRequired() {
            return userRequired;
        }

        @Override
        public boolean isWeblogRequired() {
            return weblogRequired;
        }

        @Override
        public List<String> requiredGlobalPermissionActions() {
            return globalActions;
        }

        @Override
        public List<String> requiredWeblogPermissionActions() {
            return weblogActions;
        }
    }
}
