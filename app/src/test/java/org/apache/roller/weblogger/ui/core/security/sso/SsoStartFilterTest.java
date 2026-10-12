package org.apache.roller.weblogger.ui.core.security.sso;

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
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
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

    private static MockHttpServletRequest get(String ctx, String uri, String returnPath) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
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
        MockHttpServletRequest req = get("/roller", "/roller/roller-ui/sso/start", "/roller/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, chain);
        assertEquals("/roller/roller-ui/sso/authorize/oidc", res.getRedirectedUrl());
        assertEquals("/roller/blog/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
        assertNull(chain.getRequest(), "the start step ends the request");
    }

    @Test
    void atTheRootContextTheAuthorizationEndpointHasNoPrefix() throws Exception {
        MockHttpServletRequest req = get("", "/roller-ui/sso/start", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(req, res, new MockFilterChain());
        assertEquals("/roller-ui/sso/authorize/oidc", res.getRedirectedUrl());
        assertEquals("/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
    }

    @Test
    void aReturnOffThisSiteIsStoredAsTheSiteRoot() throws Exception {
        MockHttpServletRequest req = get("/roller", "/roller/roller-ui/sso/start", "https://evil.example/");
        filter(() -> "https://blog.example", registrationPresent())
                .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("/roller/", req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
    }

    @Test
    void noAbsoluteUrlAnswersUnavailableAndRedirectsToNoProvider() throws Exception {
        for (String absolute : new String[] {"", "   ", null}) {
            AtomicInteger lookups = new AtomicInteger();
            ClientRegistrationRepository repo = id -> {
                lookups.incrementAndGet();
                return SsoTestRegistrations.registration();
            };
            MockHttpServletRequest req = get("/roller", "/roller/roller-ui/sso/start", "/roller/blog/");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter(() -> absolute, repo).doFilter(req, res, new MockFilterChain());
            assertEquals("/roller/roller-ui/login.rol?sso=unavailable", res.getRedirectedUrl());
            assertNull(req.getSession(false), "no session is created for a sign-in that cannot start");
            assertEquals(0, lookups.get(), "no discovery for a sign-in that cannot complete");
        }
    }

    @Test
    void unresolvableRegistrationAnswersUnavailable() throws Exception {
        MockHttpServletRequest req = get("", "/roller-ui/sso/start", "/blog/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", id -> null).doFilter(req, res, new MockFilterChain());
        assertEquals("/roller-ui/login.rol?sso=unavailable", res.getRedirectedUrl());
    }

    @Test
    void otherPathsPassThroughWithoutReadingConfiguration() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        MockHttpServletRequest req = get("/roller", "/roller/roller-ui/login.rol", null);
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
        MockHttpServletRequest req = get("", "/x/roller-ui/sso/start", null);
        MockFilterChain chain = new MockFilterChain();
        filter(() -> "https://blog.example", registrationPresent())
                .doFilter(req, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @Test
    void nonGetPassesThrough() throws Exception {
        MockHttpServletRequest req = get("", "/roller-ui/sso/start", "/blog/");
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
                get("", "/roller-ui/sso/start", "/blog/"), new MockHttpServletResponse(), new MockFilterChain());
        verify(weblogger, times(1)).release();
    }

    @Test
    void releasesThePersistenceSessionExactlyOnceEvenWhenTheSupplierThrows() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            MockHttpServletRequest req = get("/roller", "/roller/roller-ui/sso/start", "/roller/blog/");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter(() -> {
                throw new IllegalStateException("database said: secret detail");
            }, registrationPresent()).doFilter(req, res, new MockFilterChain());

            assertEquals("/roller/roller-ui/login.rol?sso=unavailable", res.getRedirectedUrl(),
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
                get("", "/roller-ui/sso/start", null), new MockHttpServletResponse(), new MockFilterChain());
        verify(weblogger, times(1)).release();
    }

    @Test
    void beforeBootstrapThereIsNothingToRelease() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(() -> "https://blog.example", registrationPresent()).doFilter(
                get("", "/roller-ui/sso/start", null), res, new MockFilterChain());
        assertEquals("/roller-ui/sso/authorize/oidc", res.getRedirectedUrl());
        verify(provider, never()).getWeblogger();
    }
}
