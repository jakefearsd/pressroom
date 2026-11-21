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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The problems PressroomHandlerInterceptor raises for an API handler carry
 * their own codes, with the English they always had (spec
 * 2026-11-20-api-contract-design, Task 3, criterion 1: the interceptor's API
 * refusals). Each is thrown to ApiExceptionHandler, which localises it.
 * The test lives in ui.restapi so that its probe handler is an API handler,
 * as the interceptor tells one: by its package.
 */
class PressroomHandlerInterceptorProblemCodesTest {

    /**
     * An API handler, by its package, declaring exactly the requirements a
     * test gives it. Only {@code getBean()} is read; {@link #handle} is never
     * invoked. Nested in a test, so ReaderRefusalSweepTest skips it.
     */
    public static class SecuredProbeApi implements UISecurityEnforced {

        private final List<String> globalActions;
        private final boolean weblogRequired;
        private final List<String> weblogActions;

        SecuredProbeApi(List<String> globalActions, boolean weblogRequired, List<String> weblogActions) {
            this.globalActions = globalActions;
            this.weblogRequired = weblogRequired;
            this.weblogActions = weblogActions;
        }

        public String handle() {
            return "ok";
        }

        @Override
        public boolean isUserRequired() {
            return true;
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

    private MockWeblogger weblogger;
    private PressroomHandlerInterceptor interceptor;
    private User alice;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        interceptor = new PressroomHandlerInterceptor(provider, weblogger.weblogger());
        alice = new User();
        alice.setUserName("alice");
        alice.setId("user-alice");
        blog = new Weblog();
        blog.setHandle("blog");
        blog.setId("weblog-blog");
        when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
        when(weblogger.weblogManager().getWeblogByHandle("blog")).thenReturn(blog);
        UserDetails details = mock(UserDetails.class);
        when(details.getUsername()).thenReturn("alice");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                details, "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        weblogger.detach();
    }

    private ApiException refused(SecuredProbeApi controller, String handle) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/probe");
        request.setServletPath("/api");
        if (handle != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("handle", handle));
        }
        HandlerMethod handler = new HandlerMethod(controller, SecuredProbeApi.class.getMethod("handle"));
        return assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), handler));
    }

    private static void assertRefusal(ApiException refusal, int status, String code, String english) {
        assertEquals(status, refusal.getStatus());
        assertEquals(code, refusal.toProblem(null).code());
        assertEquals(english, refusal.getMessage());
    }

    @Test
    void aMissingGlobalPermissionIsAuthNoPermission() throws Exception {
        assertRefusal(refused(new SecuredProbeApi(List.of(GlobalPermission.ADMIN), false, null), null),
                403, "auth.noPermission", "You do not have permission to perform this action.");
    }

    @Test
    void noWeblogIsWeblogNotFound() throws Exception {
        assertRefusal(refused(new SecuredProbeApi(null, true, null), null),
                404, "weblog.notFound", "No such weblog.");
    }

    @Test
    void aNonMemberIsWeblogNotFoundAndAMemberBelowTheLevelIsAuthNoPermission() throws Exception {
        SecuredProbeApi adminOnly = new SecuredProbeApi(null, true, List.of(WeblogPermission.ADMIN));
        assertRefusal(refused(adminOnly, "blog"), 404, "weblog.notFound", "No such weblog.");

        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), eq(alice)))
                .thenAnswer(inv -> WeblogPermission.EDIT_DRAFT.equals(((WeblogPermission) inv.getArgument(0)).getActions()));
        assertRefusal(refused(adminOnly, "blog"), 403, "auth.noPermission",
                "You do not have permission to perform this action.");
    }
}
