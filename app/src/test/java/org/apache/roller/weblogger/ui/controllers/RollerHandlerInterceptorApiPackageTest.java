package org.apache.roller.weblogger.ui.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.mcp.McpEndpointServlet;
import org.apache.roller.weblogger.ui.restapi.v1.EntriesApi;
import org.apache.roller.weblogger.ui.restapiv2.SiblingOfRestapi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

/**
 * Which handlers {@code RollerHandlerInterceptor} treats as REST API handlers.
 *
 * <p>Characterisation test (Phase 0 of the Pressroom rebrand, written before
 * the package prefix became a class reference): it passes against the old
 * string-based code. The classification is observable as the answer to an
 * anonymous caller on a user-required handler: an API handler throws a 401
 * {@link ApiException}, any other handler gets a redirect to login. The
 * handler's bean type is what is classified, so a mocked {@link HandlerMethod}
 * carries the package under test.
 */
class RollerHandlerInterceptorApiPackageTest {

    private static final String LOGIN = "/roller-ui/login.rol";

    private RollerHandlerInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setContextPath("/roller");
        response = new MockHttpServletResponse();
        weblogger = MockWeblogger.attached();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        interceptor = new RollerHandlerInterceptor(provider, weblogger.weblogger());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        weblogger.detach();
    }

    private HandlerMethod handlerWithBeanType(Class<?> beanType) {
        HandlerMethod hm = mock(HandlerMethod.class);
        when(hm.getBean()).thenReturn(new RollerHandlerInterceptorTest.SecuredController(
                true, List.of(), false, List.of()));
        when(hm.getBeanType()).thenAnswer(inv -> beanType);
        return hm;
    }

    private void assertApi(Class<?> beanType) {
        ApiException ex = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, response, handlerWithBeanType(beanType)),
                beanType.getName() + " must be treated as an API handler");
        assertEquals(401, ex.getStatus());
    }

    private void assertNotApi(Class<?> beanType) throws Exception {
        assertFalse(interceptor.preHandle(request, response, handlerWithBeanType(beanType)));
        assertEquals("/roller" + LOGIN, response.getRedirectedUrl(),
                beanType.getName() + " must be answered with a browser redirect");
    }

    @Test
    void aV1ControllerIsAnApiHandler() {
        assertApi(EntriesApi.class);
    }

    @Test
    void theMcpEndpointPackageIsAnApiHandler() {
        assertApi(McpEndpointServlet.class);
    }

    @Test
    void theRestapiPackageItselfIsAnApiHandler() {
        assertApi(ApiExceptionHandler.class);
    }

    @Test
    void aJspControllerIsNotAnApiHandler() throws Exception {
        assertNotApi(RollerHandlerInterceptorTest.SecuredController.class);
    }

    @Test
    void aPackageThatMerelyStartsWithTheApiPrefixTextIsNotAnApiHandler() throws Exception {
        assertNotApi(SiblingOfRestapi.class);
    }
}
