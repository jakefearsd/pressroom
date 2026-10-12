package org.apache.roller.weblogger.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** Spec flow step 6, "Errors", "Logging" and criterion 32. */
class SsoHandlersTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final Weblogger weblogger = mock(Weblogger.class);

    @BeforeEach
    void bootstrapped() {
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);
    }

    private static MockHttpServletRequest callback(String ctx, String storedReturn) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", ctx + "/roller-ui/sso/callback");
        req.setContextPath(ctx);
        MockHttpSession session = new MockHttpSession();
        if (storedReturn != null) {
            session.setAttribute(ReturnPaths.SESSION_ATTRIBUTE, storedReturn);
        }
        req.setSession(session);
        return req;
    }

    static Authentication reader(String userName, boolean newAccount) {
        OidcIdToken idToken = new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("iss", "https://accounts.google.com", "sub", "subject-1", "email", "jo@example.com"));
        RollerOidcUser user = new RollerOidcUser(new DefaultOidcUser(List.of(), idToken), userName,
                List.of(new SimpleGrantedAuthority("reader")), newAccount);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), SsoSettings.REGISTRATION_ID);
    }

    // ------------------------------------------------------------- success

    @Test
    void successRedirectsToTheStoredReturnPathAndClearsIt() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            MockHttpServletRequest req = callback("/roller", "/roller/blog/");
            MockHttpServletResponse res = new MockHttpServletResponse();
            new SsoSuccessHandler(provider).onAuthenticationSuccess(req, res, reader("reader-abcdefghij", true));

            assertEquals("/roller/blog/", res.getRedirectedUrl());
            assertNull(req.getSession().getAttribute(ReturnPaths.SESSION_ATTRIBUTE));
            List<String> infos = log.lines(Level.INFO);
            assertEquals(1, infos.size(), infos.toString());
            assertTrue(infos.get(0).contains("reader-abcdefghij"), infos.get(0));
            assertTrue(infos.get(0).contains("new"), infos.get(0));
            assertFalse(infos.get(0).contains("jo@example.com"), "never the email");
            assertFalse(infos.get(0).contains("id-token-value"), "never the ID token");
        }
    }

    @Test
    void anExistingAccountIsLoggedAsExisting() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            new SsoSuccessHandler(provider).onAuthenticationSuccess(callback("", "/blog/"),
                    new MockHttpServletResponse(), reader("reader-0123456789", false));
            assertTrue(log.lines(Level.INFO).get(0).contains("existing"), log.lines(Level.INFO).toString());
        }
    }

    @Test
    void aPrincipalThatIsNotARollerReaderIsLoggedAsExisting() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            new SsoSuccessHandler(provider).onAuthenticationSuccess(callback("", null), res,
                    new TestingAuthenticationToken("someone", null));
            assertEquals("/", res.getRedirectedUrl());
            assertTrue(log.lines(Level.INFO).get(0).contains("existing"));
        }
    }

    @Test
    void successWithoutAStoredPathGoesToTheSiteRoot() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SsoSuccessHandler(provider).onAuthenticationSuccess(callback("/roller", null), res,
                reader("reader-abcdefghij", false));
        assertEquals("/roller/", res.getRedirectedUrl());
    }

    @Test
    void successWithoutASessionGoesToTheSiteRoot() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/roller/roller-ui/sso/callback");
        req.setContextPath("/roller");
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SsoSuccessHandler(provider).onAuthenticationSuccess(req, res, reader("reader-abcdefghij", false));
        assertEquals("/roller/", res.getRedirectedUrl());
    }

    @Test
    void aStoredPathIsCheckedAgainBeforeItIsFollowed() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new SsoSuccessHandler(provider).onAuthenticationSuccess(callback("/roller", "https://evil.example/"), res,
                reader("reader-abcdefghij", false));
        assertEquals("/roller/", res.getRedirectedUrl());
    }

    // ------------------------------------------------------------- failure

    @ParameterizedTest
    @CsvSource({"sso_refused,refused", "sso_failed,failed", "authorization_request_not_found,failed",
                "invalid_request,failed", "invalid_token_response,failed", "access_denied,failed"})
    void failureMapsTheOAuth2ErrorToALoginCode(String oauthError, String code) throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error(oauthError, "provider description jo@example.com", null),
                    "message carrying a token abc.def.ghi");
            new SsoFailureHandler(provider).onAuthenticationFailure(callback("/roller", null), res, ex);

            assertEquals("/roller/roller-ui/login.rol?sso=" + code, res.getRedirectedUrl());
            List<String> warns = log.lines(Level.WARN);
            assertEquals(1, warns.size(), warns.toString());
            assertTrue(warns.get(0).contains(code), warns.get(0));
            assertTrue(warns.get(0).contains(oauthError), warns.get(0));
            assertFalse(warns.get(0).contains("jo@example.com"), "never the provider's description");
            assertFalse(warns.get(0).contains("abc.def.ghi"), "never the exception's message");
            assertFalse(log.anyThrowable(), "never the exception itself");
        }
    }

    @Test
    void aNonOAuth2FailureMapsToFailed() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            new SsoFailureHandler(provider).onAuthenticationFailure(callback("", null), res,
                    new BadCredentialsException("secret detail"));
            assertEquals("/roller-ui/login.rol?sso=failed", res.getRedirectedUrl());
            assertFalse(log.lines(Level.WARN).get(0).contains("secret detail"));
        }
    }

    @Test
    void aHostileErrorCodeCannotForgeALogLine() throws Exception {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            OAuth2AuthenticationException ex = new OAuth2AuthenticationException(
                    new OAuth2Error("x\r\nINFO Reader admin signed in" + "y".repeat(200)));
            new SsoFailureHandler(provider).onAuthenticationFailure(callback("", null),
                    new MockHttpServletResponse(), ex);
            String warn = log.lines(Level.WARN).get(0);
            assertFalse(warn.contains("\n") || warn.contains("\r"), warn);
            assertFalse(warn.contains(" admin "), warn);
            assertTrue(warn.length() < 200, "the error code is cut short: " + warn);
        }
    }

    // ------------------------------------------------- persistence session

    @Test
    void bothHandlersReleaseThePersistenceSessionExactlyOnce() throws Exception {
        new SsoSuccessHandler(provider).onAuthenticationSuccess(callback("", "/blog/"),
                new MockHttpServletResponse(), reader("reader-abcdefghij", false));
        verify(weblogger, times(1)).release();

        new SsoFailureHandler(provider).onAuthenticationFailure(callback("", null),
                new MockHttpServletResponse(), new BadCredentialsException("x"));
        verify(weblogger, times(2)).release();
    }

    @Test
    void bothHandlersReleaseExactlyOnceEvenWhenTheRedirectThrows() {
        MockHttpServletResponse broken = new MockHttpServletResponse() {
            @Override
            public void sendRedirect(String url) throws IOException {
                throw new IOException("client went away");
            }
        };
        assertThrows(IOException.class, () -> new SsoSuccessHandler(provider)
                .onAuthenticationSuccess(callback("", "/blog/"), broken, reader("reader-abcdefghij", false)));
        verify(weblogger, times(1)).release();

        assertThrows(IOException.class, () -> new SsoFailureHandler(provider)
                .onAuthenticationFailure(callback("", null), broken, new BadCredentialsException("x")));
        verify(weblogger, times(2)).release();
    }

    @Test
    void beforeBootstrapThereIsNothingToRelease() throws Exception {
        when(provider.isBootstrapped()).thenReturn(false);
        new SsoSuccessHandler(provider).onAuthenticationSuccess(callback("", "/blog/"),
                new MockHttpServletResponse(), reader("reader-abcdefghij", false));
        new SsoFailureHandler(provider).onAuthenticationFailure(callback("", null),
                new MockHttpServletResponse(), new BadCredentialsException("x"));
        verify(provider, never()).getWeblogger();
    }
}
