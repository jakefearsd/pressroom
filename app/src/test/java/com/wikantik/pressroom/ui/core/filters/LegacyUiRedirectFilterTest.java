package com.wikantik.pressroom.ui.core.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The old admin UI path answers a permanent redirect to the new one. The
 * request URIs here feed the old path in on purpose (rebrand:keep).
 */
class LegacyUiRedirectFilterTest {

    private final LegacyUiRedirectFilter filter = new LegacyUiRedirectFilter();

    private MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain)
            throws IOException, ServletException {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    private static MockHttpServletRequest request(String method, String ctx, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setContextPath(ctx);
        return req;
    }

    @Test
    void getAtRootRedirectsPermanently() throws Exception {
        MockHttpServletResponse res = run(request("GET", "", "/roller-ui/login.rol"), new MockFilterChain()); // rebrand:keep
        assertEquals(301, res.getStatus());
        assertEquals("/pressroom-ui/login.rol", res.getHeader("Location"));
    }

    @Test
    void headIsPermanentToo() throws Exception {
        MockHttpServletResponse res = run(request("HEAD", "", "/roller-ui/login.rol"), new MockFilterChain()); // rebrand:keep
        assertEquals(301, res.getStatus());
    }

    @Test
    void keepsTheQueryString() throws Exception {
        MockHttpServletRequest req = request("GET", "", "/roller-ui/entries.rol"); // rebrand:keep
        req.setQueryString("weblog=a&page=2");
        MockHttpServletResponse res = run(req, new MockFilterChain());
        assertEquals("/pressroom-ui/entries.rol?weblog=a&page=2", res.getHeader("Location"));
    }

    @Test
    void keepsTheContextPath() throws Exception {
        MockHttpServletResponse res = run(request("GET", "/pressroom", "/pressroom/roller-ui/x.rol"), // rebrand:keep
                new MockFilterChain());
        assertEquals("/pressroom/pressroom-ui/x.rol", res.getHeader("Location"));
    }

    @Test
    void postKeepsItsMethod() throws Exception {
        MockHttpServletResponse res = run(request("POST", "", "/roller-ui/x.rol"), new MockFilterChain()); // rebrand:keep
        assertEquals(308, res.getStatus());
        assertEquals("/pressroom-ui/x.rol", res.getHeader("Location"));
    }

    @Test
    void aDoubleSlashStaysHostRelativeAfterTheContextPath() throws Exception {
        MockHttpServletResponse res = run(request("GET", "", "/roller-ui//evil.com/x"), new MockFilterChain()); // rebrand:keep
        assertEquals(301, res.getStatus());
        assertEquals("/pressroom-ui//evil.com/x", res.getHeader("Location"),
                "one leading slash only: never a scheme-relative //evil.com");
    }

    @Test
    void aPostWithAContextPathIs308() throws Exception {
        MockHttpServletResponse res = run(request("POST", "/pressroom", "/pressroom/roller-ui/x.rol"), // rebrand:keep
                new MockFilterChain());
        assertEquals(308, res.getStatus());
        assertEquals("/pressroom/pressroom-ui/x.rol", res.getHeader("Location"));
    }

    @Test
    void aPostKeepsItsQueryString() throws Exception {
        MockHttpServletRequest req = request("POST", "", "/roller-ui/x.rol"); // rebrand:keep
        req.setQueryString("a=1&b=2");
        MockHttpServletResponse res = run(req, new MockFilterChain());
        assertEquals(308, res.getStatus());
        assertEquals("/pressroom-ui/x.rol?a=1&b=2", res.getHeader("Location"));
    }

    @Test
    void bareDirectory() throws Exception {
        MockHttpServletResponse res = run(request("GET", "", "/roller-ui"), new MockFilterChain()); // rebrand:keep
        assertEquals(301, res.getStatus());
        assertEquals("/pressroom-ui", res.getHeader("Location"));
    }

    @Test
    void leavesEverythingElseAlone() throws Exception {
        for (String uri : new String[] {"/pressroom-ui/x.rol", "/rollerblog/x", "/weblog/roller-ui/x", "/roller-uix/y"}) { // rebrand:keep
            MockHttpServletRequest req = request("GET", "", uri);
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = run(req, chain);
            assertSame(req, chain.getRequest(), uri);
            assertNull(res.getHeader("Location"), uri);
            assertEquals(200, res.getStatus(), uri);
        }
    }
}
