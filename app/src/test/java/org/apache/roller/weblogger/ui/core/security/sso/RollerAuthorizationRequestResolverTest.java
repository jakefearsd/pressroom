package org.apache.roller.weblogger.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;

/** Spec criterion 7 and Review Focus 2: the callback comes from the Absolute URL, never the request. */
class RollerAuthorizationRequestResolverTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final Weblogger weblogger = mock(Weblogger.class);

    @BeforeEach
    void bootstrapped() {
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);
    }

    private RollerAuthorizationRequestResolver resolver(Supplier<String> absoluteUrl) {
        return resolver(absoluteUrl, SsoTestRegistrations.present());
    }

    private RollerAuthorizationRequestResolver resolver(Supplier<String> absoluteUrl,
            ClientRegistrationRepository repo) {
        return new RollerAuthorizationRequestResolver(repo, absoluteUrl, provider);
    }

    private static MockHttpServletRequest authorize() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/roller-ui/sso/authorize/oidc");
        req.setServerName("docker2.lan");          // a host the provider would refuse
        req.setServerPort(8080);
        return req;
    }

    @ParameterizedTest
    @CsvSource(value = {
        "https://blog.example|https://blog.example/roller-ui/sso/callback",
        "https://blog.example/|https://blog.example/roller-ui/sso/callback",
        "https://blog.example//|https://blog.example/roller-ui/sso/callback",
        "http://localhost:8080/roller|http://localhost:8080/roller/roller-ui/sso/callback",
        "http://localhost:8080/roller/|http://localhost:8080/roller/roller-ui/sso/callback"}, delimiter = '|')
    void trailingSlashIsNotDoubled(String absolute, String expectedCallback) {
        OAuth2AuthorizationRequest r = resolver(() -> absolute).resolve(authorize());
        assertNotNull(r);
        assertEquals(expectedCallback, r.getRedirectUri());
        assertNotNull(r.getAttributes().get(PkceParameterNames.CODE_VERIFIER),
                "PKCE for a confidential client");
        assertNotNull(r.getAttributes().get(OidcParameterNames.NONCE));
        assertNotNull(r.getState());
        assertEquals(Set.of("openid", "profile", "email"), r.getScopes());
    }

    @Test
    void underAContextPathTheAuthorizationEndpointStillMatches() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/roller/roller-ui/sso/authorize/oidc");
        req.setContextPath("/roller");
        OAuth2AuthorizationRequest r = resolver(() -> "https://blog.example/roller").resolve(req);
        assertNotNull(r);
        assertEquals("https://blog.example/roller/roller-ui/sso/callback", r.getRedirectUri());
    }

    @Test
    void noAbsoluteUrlResolvesNothing() {
        for (String absolute : new String[] {"", "  ", null}) {
            AtomicInteger lookups = new AtomicInteger();
            ClientRegistrationRepository repo = id -> {
                lookups.incrementAndGet();
                return SsoTestRegistrations.registration();
            };
            assertNull(resolver(() -> absolute, repo).resolve(authorize()),
                    "no redirect to a provider without a configured callback");
            assertEquals(0, lookups.get(), "and no discovery either");
        }
    }

    @Test
    void ordinaryRequestsNeverReadTheAbsoluteUrl() {
        AtomicInteger reads = new AtomicInteger();
        RollerAuthorizationRequestResolver resolver = resolver(() -> {
            reads.incrementAndGet();
            return "https://blog.example";
        });
        assertNull(resolver.resolve(new MockHttpServletRequest("GET", "/roller-ui/login.rol")));
        assertNull(resolver.resolve(new MockHttpServletRequest("GET", "/blog/entry/x")));
        assertEquals(0, reads.get(), "this resolver runs on every request through the UI chain");
        verify(weblogger, never()).release();
    }

    @Test
    void releasesThePersistenceSessionExactlyOnceOnTheAuthorizationEndpoint() {
        resolver(() -> "https://blog.example").resolve(authorize());
        verify(weblogger, times(1)).release();
    }

    @Test
    void releasesThePersistenceSessionExactlyOnceEvenWhenTheSupplierThrows() {
        RollerAuthorizationRequestResolver resolver = resolver(() -> {
            throw new IllegalStateException("store down");
        });
        assertThrows(IllegalStateException.class, () -> resolver.resolve(authorize()));
        verify(weblogger, times(1)).release();
    }

    @Test
    void beforeBootstrapThereIsNothingToRelease() {
        when(provider.isBootstrapped()).thenReturn(false);
        assertNotNull(resolver(() -> "https://blog.example").resolve(authorize()));
        verify(provider, never()).getWeblogger();
    }

    @Test
    void resolvingByRegistrationIdAlsoUsesTheConfiguredCallback() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/anything");
        OAuth2AuthorizationRequest r = resolver(() -> "https://blog.example/").resolve(req, "oidc");
        assertNotNull(r);
        assertEquals("https://blog.example/roller-ui/sso/callback", r.getRedirectUri());
        assertNull(resolver(() -> "").resolve(req, "oidc"));
        verify(weblogger, times(2)).release();
    }
}
