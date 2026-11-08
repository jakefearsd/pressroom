package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.restapi.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may read a blog's traffic over the API (spec 2026-11-08-agent-api-parity-design, T4 and T8), decided
 * by the real {@code PressroomHandlerInterceptor} for the real {@link TrafficApi} handler: a blog admin
 * proceeds, a member who can post is forbidden, a caller with no role is told the weblog does not exist,
 * and the site space is refused like an unknown weblog even to a global admin (the Traffic page offers
 * it to one; the API never does).
 */
class TrafficApiSecurityTest {

    private PressroomHandlerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        request = new MockHttpServletRequest("GET", "/api/v1/weblogs/blog/traffic");
        request.setServletPath("/api");
        response = new MockHttpServletResponse();
        weblogger = MockWeblogger.attached();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        interceptor = new PressroomHandlerInterceptor(provider, weblogger.weblogger());

        User alice = new User();
        alice.setUserName("alice");
        alice.setId("user-alice");
        when(weblogger.userManager().getUserByUserName("alice")).thenReturn(alice);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", "x",
                AuthorityUtils.createAuthorityList("ROLE_USER")));

        blog = new Weblog();
        blog.setHandle("blog");
        blog.setId("weblog-blog");
        when(weblogger.weblogManager().getWeblogByHandle("blog")).thenReturn(blog);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("handle", "blog"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        weblogger.detach();
    }

    /** Alice holds exactly these weblog actions (each check asks for one level). */
    private void aliceHolds(String... actions) throws Exception {
        List<String> held = List.of(actions);
        when(weblogger.userManager().checkPermission(any(WeblogPermission.class), any()))
                .thenAnswer(call -> held.containsAll(((WeblogPermission) call.getArgument(0)).getActionsAsList()));
    }

    private boolean preHandle() throws Exception {
        TrafficApi controller = new TrafficApi();
        HandlerMethod handler = new HandlerMethod(controller,
                TrafficApi.class.getMethod("get", HttpServletRequest.class, String.class));
        return interceptor.preHandle(request, response, handler);
    }

    @Test
    void aBlogAdminProceeds() throws Exception {
        aliceHolds(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST, WeblogPermission.ADMIN);

        assertTrue(preHandle());
        assertEquals(blog, request.getAttribute("actionWeblog"));
    }

    /** T4: a member who can post knows the weblog exists, so is forbidden rather than told it is missing. */
    @Test
    void aMemberWhoCanPostIsForbidden() throws Exception {
        aliceHolds(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST);

        ApiException refusal = assertThrows(ApiException.class, this::preHandle);

        assertEquals(403, refusal.getStatus());
    }

    /** T4: a caller with no role on the weblog gets the 404 an unknown handle gets. */
    @Test
    void aCallerWithNoRoleIsToldTheWeblogDoesNotExist() throws Exception {
        aliceHolds();

        ApiException refusal = assertThrows(ApiException.class, this::preHandle);

        assertEquals(404, refusal.getStatus());
        assertEquals("No such weblog.", refusal.getMessage());
    }

    /** T8: the site space is withheld from every API handler, so even a global admin gets the 404. */
    @Test
    void theSiteSpaceIsRefusedLikeAnUnknownWeblog() throws Exception {
        blog.setSiteSpace(true);
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);

        ApiException refusal = assertThrows(ApiException.class, this::preHandle);

        assertEquals(404, refusal.getStatus());
        assertEquals("No such weblog.", refusal.getMessage());
        assertEquals(null, request.getAttribute("actionWeblog"));
    }
}
