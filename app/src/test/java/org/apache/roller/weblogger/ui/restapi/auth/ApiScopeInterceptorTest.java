package org.apache.roller.weblogger.ui.restapi.auth;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ApiScopeInterceptorDispatchTest} is the end-to-end gate (a real
 * request through the real {@code WebMvcConfig} registration and a real
 * {@code {handle}}-carrying route). This class is the fast, precise unit
 * coverage of {@link ApiScopeInterceptor}'s own decision logic in isolation
 * -- in particular the {@link AdminScoped}/{@link WeblogScopeExempt}
 * annotation checks, which need a real resolved {@link HandlerMethod} (not a
 * URI) to have anything to inspect.
 */
class ApiScopeInterceptorTest {

    private final ApiScopeInterceptor interceptor = new ApiScopeInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ---- stand-in controllers, wrapped as real HandlerMethods so the
    // annotation checks (read off the resolved handler, never the URI) have
    // something real to inspect ----

    static class PlainApi {
        void handle() { }
    }

    @AdminScoped
    static class AdminApi {
        void handle() { }
    }

    static class WeblogLessApi {
        @WeblogScopeExempt
        void exempt() { }
        void notExempt() { }
    }

    private static final HandlerMethod PLAIN = handlerMethod(new PlainApi(), "handle");
    private static final HandlerMethod ADMIN = handlerMethod(new AdminApi(), "handle");
    private static final HandlerMethod EXEMPT = handlerMethod(new WeblogLessApi(), "exempt");
    private static final HandlerMethod NOT_EXEMPT = handlerMethod(new WeblogLessApi(), "notExempt");

    private static HandlerMethod handlerMethod(Object bean, String methodName) {
        try {
            Method method = bean.getClass().getDeclaredMethod(methodName);
            return new HandlerMethod(bean, method);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    /**
     * {@code actionWeblog} is the request attribute RollerHandlerInterceptor
     * sets, not a URI template variable -- see the class javadoc on
     * {@link ApiScopeInterceptor} for why the check reads it from there.
     */
    private static MockHttpServletRequest request(String method, Weblog actionWeblog,
                                                  String scopeWeblog, ApiToken.Role role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/probe");
        if (actionWeblog != null) {
            request.setAttribute("actionWeblog", actionWeblog);
        }
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scopeWeblog, role, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return request;
    }

    @Test
    void aTokenScopedToOneWeblogCannotReachAnother() {
        MockHttpServletRequest request = request("GET", weblog("other"), "testblog", ApiToken.Role.POST);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), PLAIN));
        // 404, not 403: a 403 would confirm that 'other' exists.
        assertEquals(404, thrown.getStatus());
    }

    @Test
    void aTokenScopedToTheResolvedWeblogPasses() {
        MockHttpServletRequest request = request("GET", weblog("testblog"), "testblog", ApiToken.Role.READ);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), PLAIN));
    }

    @Test
    void aReadTokenCannotWrite() {
        MockHttpServletRequest request = request("POST", weblog("testblog"), "testblog", ApiToken.Role.READ);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), PLAIN));
        assertEquals(403, thrown.getStatus());
    }

    /**
     * HTTP methods are case-sensitive (RFC 9110): only GET and HEAD, spelled
     * exactly, are reads. A read token sending "get" or "head" is refused as a
     * write rather than waved through as a read that Spring would then route
     * to no GET handler.
     */
    @Test
    void onlyExactGetAndHeadAreReads() {
        for (String spelling : List.of("get", "Head")) {
            MockHttpServletRequest request = request(spelling, weblog("testblog"), "testblog", ApiToken.Role.READ);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> interceptor.preHandle(request, new MockHttpServletResponse(), PLAIN), spelling);
            assertEquals(403, thrown.getStatus(), spelling);
        }
        assertTrue(interceptor.preHandle(
                request("HEAD", weblog("testblog"), "testblog", ApiToken.Role.READ), new MockHttpServletResponse(), PLAIN));
    }

    /**
     * The allowlist fix: a null role must not default to write-permitted.
     * Under the old {@code role == READ} denylist, {@code null == READ} is
     * false, so a null role was wrongly treated as permitted to write.
     */
    @Test
    void aNullRoleCannotWrite() {
        MockHttpServletRequest request = request("POST", weblog("testblog"), "testblog", null);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), PLAIN));
        assertEquals(403, thrown.getStatus());
    }

    @Test
    void aPostTokenMayWriteContentButNotReachAnAdminScopedController() {
        MockHttpServletRequest write = request("POST", weblog("testblog"), "testblog", ApiToken.Role.POST);
        assertTrue(interceptor.preHandle(write, new MockHttpServletResponse(), PLAIN));

        MockHttpServletRequest adminRequest = request("POST", null, null, ApiToken.Role.POST);
        ApiException thrown = assertThrows(ApiException.class, () ->
                interceptor.preHandle(adminRequest, new MockHttpServletResponse(), ADMIN));
        assertEquals(403, thrown.getStatus());
    }

    @Test
    void anUnscopedAdminTokenPasses() {
        MockHttpServletRequest request = request("POST", null, null, ApiToken.Role.ADMIN);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), ADMIN));
    }

    /**
     * Session-authenticated requests (there are none today, but the UI could
     * grow one) carry no ApiPrincipal. The interceptor must not then invent a
     * ceiling -- it defers entirely to RollerHandlerInterceptor.
     */
    @Test
    void aRequestWithNoApiPrincipalIsLeftAlone() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/ping");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), PLAIN));
    }

    /**
     * A scoped token is a ceiling: a route with no weblog for the ceiling to
     * check against must deny by default, not allow by accident. This is
     * the fail-open shape that let a POST-scoped token reach {@code
     * /v1/tokens} (no weblog anywhere in that route) and enumerate or
     * revoke every token its owner held, including an ADMIN one.
     */
    @Test
    void aScopedTokenIsDeniedOnARouteWithNoWeblogToCheck() {
        MockHttpServletRequest request = request("GET", null, "testblog", ApiToken.Role.READ);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), NOT_EXEMPT));
        assertEquals(404, thrown.getStatus());
    }

    /**
     * The denial says why. "No such weblog." on {@code /v1/admin/config} sent
     * the publishing agent hunting for a missing weblog, when the cause was
     * its own token's pin. Naming the pin leaks nothing: the holder can read
     * it from {@code /v1/me}, and the route's shape, not any stored data,
     * decides this branch.
     */
    @Test
    void theDenialOnARouteWithNoWeblogNamesTheTokensPin() {
        MockHttpServletRequest request = request("GET", null, "testblog", ApiToken.Role.ADMIN);

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), NOT_EXEMPT));
        assertEquals("This token is limited to the weblog 'testblog', and this route is not "
                + "part of one weblog. Use a token with no weblog limit.", thrown.getMessage());
    }

    /**
     * Characterisation, passing before the message above existed: a route
     * whose {@code {handle}} named a weblog the resolver did not find also
     * arrives with no action weblog, and it must keep the answer an existing
     * weblog outside the pin gets. Explaining the pin there would tell a
     * pinned token which handles exist.
     */
    @Test
    void aRouteNamingAnUnknownWeblogStillAnswersNoSuchWeblog() {
        MockHttpServletRequest request = request("GET", null, "testblog", ApiToken.Role.ADMIN);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("handle", "ghost"));

        ApiException thrown = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), NOT_EXEMPT));
        assertEquals(404, thrown.getStatus());
        assertEquals("No such weblog.", thrown.getMessage());
    }

    /** The explicit, narrow exception: a route marked @WeblogScopeExempt still works. */
    @Test
    void aScopedTokenReachesAnExplicitlyExemptWeblogLessRoute() {
        MockHttpServletRequest request = request("GET", null, "testblog", ApiToken.Role.READ);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), EXEMPT));
    }

    /** An unscoped token was never limited by the weblog check; the exemption is irrelevant to it. */
    @Test
    void anUnscopedTokenReachesAWeblogLessRouteRegardless() {
        MockHttpServletRequest request = request("GET", null, null, ApiToken.Role.READ);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), NOT_EXEMPT));
    }

    /**
     * The exemption exists specifically for these two routes -- "an
     * explicit allowlist of weblog-less routes that must still work:
     * /v1/me and /v1/ping. A CLI self-check must never be locked out by
     * its own scope." An annotation nobody applied is decorative, not an
     * allowlist, so this pins that MetaApi's methods actually carry it.
     */
    @Test
    void metaApiPingAndMeAreWeblogScopeExempt() throws NoSuchMethodException {
        Class<?> metaApi = org.apache.roller.weblogger.ui.restapi.v1.MetaApi.class;

        assertTrue(metaApi.getMethod("ping").isAnnotationPresent(WeblogScopeExempt.class),
                "MetaApi.ping() must be reachable by a weblog-scoped token");
        assertTrue(metaApi.getMethod("me", jakarta.servlet.http.HttpServletRequest.class)
                        .isAnnotationPresent(WeblogScopeExempt.class),
                "MetaApi.me() must be reachable by a weblog-scoped token");
    }
}
