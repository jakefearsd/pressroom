package com.wikantik.pressroom.boot;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The API chains' CSRF handler: the default one, except that a {@code _csrf} parameter never counts. */
class HeaderOnlyCsrfTokenRequestHandlerTest {

    private final SecurityConfig.HeaderOnlyCsrfTokenRequestHandler handler =
            new SecurityConfig.HeaderOnlyCsrfTokenRequestHandler();
    private final CsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "secret-token");

    /** Without this, Spring Security never learns the token and every later check sees none. */
    @Test
    void handlingPublishesTheTokenOnTheRequestForTheCheckThatFollows() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");

        handler.handle(request, new MockHttpServletResponse(), () -> token);

        assertNotNull(request.getAttribute(CsrfToken.class.getName()));
        assertNotNull(request.getAttribute("_csrf"));
    }

    @Test
    void aTokenInTheParameterIsNeverRead() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.setParameter("_csrf", token.getToken());

        assertNull(handler.resolveCsrfTokenValue(request, token));
    }

    /** The header carries the masked form the page was given, which is what {@code handle} publishes. */
    @Test
    void aMaskedTokenInTheHeaderIsRead() {
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/api/v1/x");
        handler.handle(page, new MockHttpServletResponse(), () -> token);
        String masked = ((CsrfToken) page.getAttribute(CsrfToken.class.getName())).getToken();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
        request.addHeader("X-CSRF-TOKEN", masked);
        request.setParameter("_csrf", "ignored");

        assertEquals(token.getToken(), handler.resolveCsrfTokenValue(request, token));
    }
}
