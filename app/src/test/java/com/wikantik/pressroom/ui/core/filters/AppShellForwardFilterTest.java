package com.wikantik.pressroom.ui.core.filters;

import java.io.IOException;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A deep link or reload under the React admin gets the shell; its own files do not. */
class AppShellForwardFilterTest {

    private final AppShellForwardFilter filter = new AppShellForwardFilter();

    /** Returns the forward target, or null when the request went down the chain. */
    private String run(String method, String ctx, String path) throws IOException, ServletException {
        MockHttpServletRequest req = new MockHttpServletRequest(method, ctx + path);
        req.setContextPath(ctx);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        if (res.getForwardedUrl() == null) {
            assertNotNull(chain.getRequest(), "passed down the chain");
        } else {
            assertNull(chain.getRequest(), "not passed down the chain");
        }
        return res.getForwardedUrl();
    }

    @Test
    void theAppRootAndDeepLinksForwardToTheShellAtRoot() throws Exception {
        assertEquals("/pressroom-ui/app.rol", run("GET", "", "/pressroom-ui/app/"));
        assertEquals("/pressroom-ui/app.rol", run("GET", "", "/pressroom-ui/app"));
        assertEquals("/pressroom-ui/app.rol", run("GET", "", "/pressroom-ui/app/weblogs/x/traffic"));
    }

    @Test
    void theSameUnderAContextPath() throws Exception {
        assertEquals("/pressroom-ui/app.rol", run("GET", "/pressroom", "/pressroom-ui/app/"));
        assertEquals("/pressroom-ui/app.rol", run("GET", "/pressroom", "/pressroom-ui/app/weblogs/x/traffic"));
    }

    @Test
    void theBundleItsStylesheetItsMapAndItsAssetsAreServedAsFiles() throws Exception {
        assertNull(run("GET", "", "/pressroom-ui/app/admin.js"));
        assertNull(run("GET", "/pressroom", "/pressroom-ui/app/admin.css"));
        assertNull(run("GET", "", "/pressroom-ui/app/admin.js.map"));
        assertNull(run("GET", "", "/pressroom-ui/app/assets/chunk-1.js"));
    }

    @Test
    void onlyGetsForwardAndOtherPathsAreLeftAlone() throws Exception {
        assertNull(run("POST", "", "/pressroom-ui/app/weblogs/x"));
        assertNull(run("GET", "", "/pressroom-ui/app.rol"));
        assertNull(run("GET", "", "/pressroom-ui/application/x"));
        assertNull(run("GET", "", "/pressroom-ui/menu.rol"));
        assertNull(run("GET", "", "/myblog"));
    }
}
