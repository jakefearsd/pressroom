package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The JSP UI passes the weblog as a {@code weblog} request parameter and has
 * no {@code {handle}} URI template variable on any of its routes; the REST
 * API carries it as a {@code {handle}} template variable, and a REST
 * controller's own handler method reads that variable directly. One
 * resolution helper serves both, so there is a single authorization path
 * rather than a parallel one for the API -- which only works if this
 * helper's answer always agrees with what the REST handler itself will act
 * on.
 *
 * <p>So for an API handler the parameter is never read at all: an API route
 * names its weblog only by {@code {handle}}. Reading {@code ?weblog=} on an
 * API route without one let a limited token satisfy its own ceiling on a
 * route that acts on no weblog (see {@code WeblogParameterDispatchTest}).
 * And where both are present the path variable wins, so the permission
 * check and the scope ceiling can never point at a different weblog than
 * the one the handler receives.
 */
class RollerHandlerInterceptorPathVariableTest {

    @Test
    void thePathVariableWinsWhenBothArePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request, true));
        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request, false));
    }

    @Test
    void thePathVariableIsUsedWhenTheParameterIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "frompath"));

        assertEquals("frompath", RollerHandlerInterceptor.resolveWeblogHandle(request, true));
    }

    /** The JSP admin UI's whole shape: no {@code {handle}} route exists, so the parameter must still work. */
    @Test
    void aJspHandlerUsesTheRequestParameterWhenThereIsNoPathVariable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");

        assertEquals("fromparam", RollerHandlerInterceptor.resolveWeblogHandle(request, false));
    }

    /** An API route names its weblog only by {@code {handle}}; the JSP parameter is not its vocabulary. */
    @Test
    void anApiHandlerIgnoresTheRequestParameter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");

        assertNull(RollerHandlerInterceptor.resolveWeblogHandle(request, true));
    }

    @Test
    void anApiHandlerWithABlankPathVariableDoesNotFallThroughToTheParameter() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "   "));

        assertNull(RollerHandlerInterceptor.resolveWeblogHandle(request, true));
    }

    /** A blank path variable is treated as absent, the same way a blank parameter always has been. */
    @Test
    void aBlankPathVariableFallsThroughToTheParameterForAJspHandler() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "fromparam");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("handle", "   "));

        assertEquals("fromparam", RollerHandlerInterceptor.resolveWeblogHandle(request, false));
    }

    @Test
    void aBlankParameterFallsThroughWhenThereIsNoPathVariable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("weblog", "   ");

        assertNull(RollerHandlerInterceptor.resolveWeblogHandle(request, false));
    }

    @Test
    void nullWhenNeitherIsPresent() {
        assertNull(RollerHandlerInterceptor.resolveWeblogHandle(new MockHttpServletRequest(), false));
        assertNull(RollerHandlerInterceptor.resolveWeblogHandle(new MockHttpServletRequest(), true));
    }
}
