package org.apache.roller.weblogger.ui.core.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A URL the Spring firewall rejects (encoded slashes, path traversal) must
 * read as a plain 404 to the client, not as the 500 the container would
 * otherwise produce, while any other failure still reaches the container.
 */
class SpringFirewallExceptionFilterTest {

    private final SpringFirewallExceptionFilter filter = new SpringFirewallExceptionFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/blog/%2e%2e/secret");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void aRequestTheFirewallRejectsIsAnsweredWith404() throws Exception {
        doThrow(new RequestRejectedException("The request was rejected because the URL contained a potentially malicious String"))
                .when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString(), "nothing about the rejection is echoed to the client");
    }

    @Test
    void anAcceptableRequestPassesStraightThrough() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
        assertNull(response.getErrorMessage());
    }

    @Test
    void anyOtherFailureIsNotSwallowedAsA404() throws Exception {
        ServletException boom = new ServletException("render failed");
        doThrow(boom).when(chain).doFilter(request, response);

        ServletException thrown = assertThrows(ServletException.class, () -> filter.doFilter(request, response, chain));

        assertSame(boom, thrown);
        assertEquals(200, response.getStatus(), "the filter must not have answered for it");
    }
}
