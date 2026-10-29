package com.wikantik.pressroom.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.logging.log4j.Level;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** Spec flow step 2, criteria 3, 6, 7 and 32. */
class SsoStartFilterTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final Weblogger weblogger = mock(Weblogger.class);

    @BeforeEach
    void bootstrapped() {
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);
    }

    /** A start request on the Absolute URL's own host, {@code https://blog.example}. */
    private static MockHttpServletRequest get(String ctx, String uri, String returnPath) {
        return on("https", "blog.example", 443, ctx, uri, returnPath);
    }

    /** A start request as the browser addressed it: scheme, host and port. */
    private static MockHttpServletRequest on(String scheme, String host, int port, String ctx, String uri,
            String returnPath) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setScheme(scheme);
        req.setSecure("https".equals(scheme));
        req.setServerName(host);
        req.setServerPort(port);
        req.setContextPath(ctx);
        if (returnPath != null) {
            req.setParameter("return", returnPath);
        }
        return req;
    }

    private SsoStartFilter filter(Supplier<String> absoluteUrl, ClientRegistrationRepository repo) {
        return new SsoStartFilter(absoluteUrl, repo, provider);
    }

    private static ClientRegistrationRepository registrationPresent() {
        return SsoTestRegistrations.present();
    }

    @Test
    void storesTheReturnPathAndRedirectsToTheAuthorizationEndpoint() throws Exception {
        MockHttpServletRequest req = get("/roller", "/roller/pressroom-ui/sso/start", "/roller/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, chain);
        assertEquals("/roller/pressroom-ui/sso/authorize/oidc", res.getRedirectedUrl());
        assertEquals("/roller/blog/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
        assertNull(chain.getRequest(), "the start step ends the request");
    }

    @Test
    void atTheRootContextTheAuthorizationEndpointHasNoPrefix() throws Exception {
        MockHttpServletRequest req = get("", "/pressroom-ui/sso/start", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, new MockFilterChain());
        assertEquals("/pressroom-ui/sso/authorize/oidc", res.getRedirectedUrl());
        assertEquals("/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
    }

    @Test
    void aReturnOffThisSiteIsStoredAsTheSiteRoot() throws Exception {
        MockHttpServletRequest req = get("/roller", "/roller/pressroom-ui/sso/start", "https://evil.example/");
        filter(() -> "https://blog.example", registrationPresent())
                .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("/roller/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
    }

    // ------------------------------------------- the Absolute URL's own host (I1)
    //
    // Spring keeps the authorization request, and this filter the return path, in
    // the session of the host the browser used; the provider always returns the
    // browser to the Absolute URL's host. A sign-in started anywhere else would
    // arrive at a session holding neither, so it moves there before it starts.

    @Test
    void aSignInStartedOnAnotherHostMovesToTheAbsoluteUrlFirst() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        ClientRegistrationRepository repo = id -> {
            lookups.incrementAndGet();
            return SsoTestRegistrations.registration();
        };
        MockHttpServletRequest req = on("http", "docker2.lan", 8080, "", "/pressroom-ui/sso/start", "/blog/?p=1&q=a b");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(() -> "https://blog.example", repo).doFilter(req, res, chain);

        assertEquals("https://blog.example/pressroom-ui/sso/start?return=%2Fblog%2F%3Fp%3D1%26q%3Da+b",
                res.getRedirectedUrl());
        assertNull(req.getSession(false), "no session on a host the provider never returns to");
        assertEquals(0, lookups.get(), "the start on the Absolute URL's host resolves the provider");
        assertNull(chain.getRequest(), "the start step ends the request");
        verify(weblogger, times(1)).release();
    }

    @Test
    void theSchemeAloneDifferingIsAnotherHost() throws Exception {
        MockHttpServletRequest req = on("http", "blog.example", 80, "", "/pressroom-ui/sso/start", "/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, new MockFilterChain());
        assertEquals("https://blog.example/pressroom-ui/sso/start?return=%2Fblog%2F", res.getRedirectedUrl());
        assertNull(req.getSession(false));
    }

    @Test
    void thePortAloneDifferingIsAnotherHost() throws Exception {
        MockHttpServletRequest req = on("https", "blog.example", 8443, "", "/pressroom-ui/sso/start", "/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, new MockFilterChain());
        assertEquals("https://blog.example/pressroom-ui/sso/start?return=%2Fblog%2F", res.getRedirectedUrl());
        assertNull(req.getSession(false));
    }

    @Test
    void aDefaultPortIsTheSameAsNoPort() throws Exception {
        String[][] cases = {
            {"https://blog.example", "https", "443"},
            {"https://blog.example:443", "https", "443"},
            {"http://blog.example", "http", "80"},
            {"http://blog.example:80/", "http", "80"},
            {"https://BLOG.example", "https", "443"},
        };
        for (String[] c : cases) {
            MockHttpServletRequest req = on(c[1], "blog.example", Integer.parseInt(c[2]), "",
                    "/pressroom-ui/sso/start", "/blog/");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter(() -> c[0], registrationPresent()).doFilter(req, res, new MockFilterChain());
            assertEquals("/pressroom-ui/sso/authorize/oidc", res.getRedirectedUrl(), c[0]);
            assertEquals("/blog/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE), c[0]);
        }
    }

    @Test
    void aTrailingSlashOnTheAbsoluteUrlIsNotDoubled() throws Exception {
        MockHttpServletRequest req = on("http", "docker2.lan", 8080, "", "/pressroom-ui/sso/start", "/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> " https://blog.example/ ", registrationPresent()).doFilter(req, res, new MockFilterChain());
        assertEquals("https://blog.example/pressroom-ui/sso/start?return=%2Fblog%2F", res.getRedirectedUrl());
    }

    @Test
    void anAbsoluteUrlCarryingTheContextPathKeepsIt() throws Exception {
        MockHttpServletRequest req = on("http", "127.0.0.1", 8080, "/roller", "/roller/pressroom-ui/sso/start",
                "/roller/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "http://localhost:8080/roller/", registrationPresent())
                .doFilter(req, res, new MockFilterChain());
        assertEquals("http://localhost:8080/roller/pressroom-ui/sso/start?return=%2Froller%2Fblog%2F",
                res.getRedirectedUrl());
        assertNull(req.getSession(false));
    }

    @Test
    void onTheAbsoluteUrlsOwnHostUnderAContextPathTheStartIsUnchanged() throws Exception {
        MockHttpServletRequest req = on("http", "localhost", 8080, "/roller", "/roller/pressroom-ui/sso/start",
                "/roller/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "http://localhost:8080/roller", registrationPresent())
                .doFilter(req, res, new MockFilterChain());
        assertEquals("/roller/pressroom-ui/sso/authorize/oidc", res.getRedirectedUrl());
        assertEquals("/roller/blog/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
    }

    @Test
    void theReturnCarriedToTheAbsoluteUrlIsSanitisedFirst() throws Exception {
        MockHttpServletRequest req = on("http", "docker2.lan", 8080, "/roller", "/roller/pressroom-ui/sso/start",
                "https://evil.example/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example/roller", registrationPresent())
                .doFilter(req, res, new MockFilterChain());
        assertEquals("https://blog.example/roller/pressroom-ui/sso/start?return=%2Froller%2F", res.getRedirectedUrl());
    }

    @Test
    void anAbsoluteUrlWithNoSchemeOrHostAnswersUnavailable() throws Exception {
        for (String absolute : new String[] {"blog.example", "/roller", "https://", "http://bad host/"}) {
            try (SsoLogCapture log = SsoLogCapture.start()) {
                MockHttpServletRequest req = get("", "/pressroom-ui/sso/start", "/blog/");
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter(() -> absolute, registrationPresent()).doFilter(req, res, new MockFilterChain());
                assertEquals("/pressroom-ui/login.rol?sso=unavailable", res.getRedirectedUrl(),
                        absolute + ": a host that cannot be compared is not a host that matched");
                assertNull(req.getSession(false), absolute);
                assertEquals(1, log.lines(Level.WARN).size(), absolute + " " + log.lines(Level.WARN));
            }
        }
    }

    @Test
    void noAbsoluteUrlAnswersUnavailableAndRedirectsToNoProvider() throws Exception {
        for (String absolute : new String[] {"", "   ", null}) {
            AtomicInteger lookups = new AtomicInteger();
            ClientRegistrationRepository repo = id -> {
                lookups.incrementAndGet();
                return SsoTestRegistrations.registration();
            };
            MockHttpServletRequest req = get("/roller", "/roller/pressroom-ui/sso/start", "/roller/blog/");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter(() -> absolute, repo).doFilter(req, res, new MockFilterChain());
            assertEquals("/roller/pressroom-ui/login.rol?sso=unavailable", res.getRedirectedUrl());
            assertNull(req.getSession(false), "no session is created for a sign-in that cannot start");
            assertEquals(0, lookups.get(), "no discovery for a sign-in that cannot complete");
        }
    }

    @Test
    void unresolvableRegistrationAnswersUnavailable() throws Exception {
        MockHttpServletRequest req = get("", "/pressroom-ui/sso/start", "/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", id -> null).doFilter(req, res, new MockFilterChain());
        assertEquals("/pressroom-ui/login.rol?sso=unavailable", res.getRedirectedUrl());
    }

    @Test
    void otherPathsPassThroughWithoutReadingConfiguration() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        MockHttpServletRequest req = get("/roller", "/roller/pressroom-ui/login.rol", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(() -> {
            reads.incrementAndGet();
            return "https://blog.example";
        }, registrationPresent()).doFilter(req, res, chain);
        assertNotNull(chain.getRequest(), "the chain continues");
        assertNull(res.getRedirectedUrl());
        assertEquals(0, reads.get(), "an ordinary request never reads the Absolute URL");
        verify(weblogger, never()).release();
    }

    @Test
    void theStartPathUnderAnotherPrefixIsNotTheStartPath() throws Exception {
        MockHttpServletRequest req = get("", "/x/pressroom-ui/sso/start", null);
        MockFilterChain chain = new MockFilterChain();
        filter(() -> "https://blog.example", registrationPresent())
                .doFilter(req, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @Test
    void nonGetPassesThrough() throws Exception {
        MockHttpServletRequest req = get("", "/pressroom-ui/sso/start", "/blog/");
        req.setMethod("POST");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, chain);
        assertNotNull(chain.getRequest());
        assertNull(res.getRedirectedUrl());
        verify(weblogger, never()).release();
    }

    @Test
    void releasesThePersistenceSessionExactlyOnceOnSuccess() throws Exception {
        filter(() -> "https://blog.example", registrationPresent()).doFilter(
                get("", "/pressroom-ui/sso/start", "/blog/"), new MockHttpServletResponse(), new MockFilterChain());
        verify(weblogger, times(1)).release();
    }

    @Test
    void releasesThePersistenceSessionExactlyOnceEvenWhenTheSupplierThrows() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            MockHttpServletRequest req = get("/roller", "/roller/pressroom-ui/sso/start", "/roller/blog/");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter(() -> {
                throw new IllegalStateException("database said: secret detail");
            }, registrationPresent()).doFilter(req, res, new MockFilterChain());

            assertEquals("/roller/pressroom-ui/login.rol?sso=unavailable", res.getRedirectedUrl(),
                    "a check that could not run is not a check that passed");
            verify(weblogger, times(1)).release();

            List<String> warns = log.lines(Level.WARN);
            assertEquals(1, warns.size(), warns.toString());
            assertTrue(warns.get(0).contains("unavailable"), warns.get(0));
            assertFalse(warns.get(0).contains("secret detail"), "never the exception's text");
            assertFalse(log.anyThrowable(), "never the exception itself");
        }
    }

    @Test
    void releasesThePersistenceSessionExactlyOnceWhenUnavailable() throws Exception {
        filter(() -> "", registrationPresent()).doFilter(
                get("", "/pressroom-ui/sso/start", null), new MockHttpServletResponse(), new MockFilterChain());
        verify(weblogger, times(1)).release();
    }

    @Test
    void beforeBootstrapThereIsNothingToRelease() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(
                get("", "/pressroom-ui/sso/start", null), res, new MockFilterChain());
        assertEquals("/pressroom-ui/sso/authorize/oidc", res.getRedirectedUrl());
        verify(provider, never()).getWeblogger();
    }
}
