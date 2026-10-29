package com.wikantik.pressroom.boot;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.ui.core.security.sso.PressroomAuthorizationRequestResolver;
import com.wikantik.pressroom.ui.core.security.sso.PressroomOidcUser;
import com.wikantik.pressroom.ui.core.security.sso.SsoFailureHandler;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogin;
import com.wikantik.pressroom.ui.core.security.sso.SsoSettings;
import com.wikantik.pressroom.ui.core.security.sso.SsoStartFilter;
import com.wikantik.pressroom.ui.core.security.sso.SsoSuccessHandler;
import com.wikantik.pressroom.ui.core.security.sso.SsoTestRegistrations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Reader sign-in wired into the real {@link SecurityConfig} UI chain, on the
 * same hand-built context {@link SecurityConfigTest} uses (its javadoc says why
 * not {@code @SpringBootTest}).
 *
 * <p>The full round trip runs against a stub provider that is never contacted:
 * the token endpoint is a canned response and the ID token is signed by a key
 * generated here, verified by a decoder that knows that key. Everything Spring
 * does with them -- state, nonce, signature, issuer, audience, expiry, the
 * session id change, remember-me -- is the production code path.
 */
class SsoLoginWiringTest {

    private static final String ABSOLUTE_URL = "https://blog.example";
    private static final String READER = "reader-abcdefghij";
    private static final KeyPair KEYS = rsaKeys();

    /** Swapped by a test that needs the account store to misbehave; reset after each. */
    private static volatile OAuth2UserService<OidcUserRequest, OidcUser> users = SsoLoginWiringTest::aReader;

    private static AnnotationConfigWebApplicationContext onContext;
    private static AnnotationConfigWebApplicationContext offContext;
    private static MockMvc onMvc;
    private static MockMvc offMvc;

    @Controller
    static class StubController {

        @GetMapping({"/pressroom-ui/login.rol", "/blog/"})
        @ResponseBody
        String ok() {
            return "ok";
        }
    }

    @Configuration
    @EnableWebSecurity
    @Import({SecurityConfig.class, StubController.class})
    static class SsoOffConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * Not bootstrapped, as before the tier is up: {@code getWeblogger()} throws,
         * which is what the remember-me services would meet if a sign-in reached them.
         */
        @Bean
        WebloggerProvider webloggerProvider() {
            WebloggerProvider provider = mock(WebloggerProvider.class);
            when(provider.getWeblogger()).thenThrow(new IllegalStateException("not bootstrapped"));
            return provider;
        }
    }

    @Configuration
    @Import(SsoOffConfig.class)
    static class SsoOnConfig {

        @Bean
        SsoLogin ssoLogin(WebloggerProvider provider) {
            ClientRegistrationRepository registrations = SsoTestRegistrations.present();
            Supplier<String> absoluteUrl = () -> ABSOLUTE_URL;
            OAuth2UserService<OidcUserRequest, OidcUser> userService = request -> users.loadUser(request);
            return new SsoLogin(registrations,
                    new PressroomAuthorizationRequestResolver(registrations, absoluteUrl, provider),
                    userService,
                    new SsoSuccessHandler(provider),
                    new SsoFailureHandler(provider),
                    new SsoStartFilter(absoluteUrl, registrations, provider),
                    SsoLoginWiringTest::cannedTokenResponse,
                    SsoLoginWiringTest::testKeyDecoder);
        }
    }

    @BeforeAll
    static void setUpContexts() {
        onContext = context(SsoOnConfig.class);
        offContext = context(SsoOffConfig.class);
        onMvc = webAppContextSetup(onContext).apply(springSecurity()).build();
        offMvc = webAppContextSetup(offContext).apply(springSecurity()).build();
    }

    private static AnnotationConfigWebApplicationContext context(Class<?> config) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(config);
        context.refresh();
        return context;
    }

    @AfterAll
    static void tearDownContexts() {
        onContext.close();
        offContext.close();
    }

    @AfterEach
    void restoreTheAccountStore() {
        users = SsoLoginWiringTest::aReader;
    }

    // ------------------------------------------------------------ criterion 1

    @Test
    void ssoOffAnswers404OnAllThreePaths() throws Exception {
        for (String p : List.of("/pressroom-ui/sso/start", "/pressroom-ui/sso/authorize/oidc", "/pressroom-ui/sso/callback")) {
            offMvc.perform(get(p)).andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------ the authorize step

    @Test
    void authorizeRedirectsToTheProviderWithStateNoncePkceAndTheConfiguredCallback() throws Exception {
        MvcResult r = onMvc.perform(get("/pressroom-ui/sso/authorize/oidc").header("Host", "docker2.lan:8080"))
                .andExpect(status().is3xxRedirection()).andReturn();
        UriComponents u = UriComponentsBuilder.fromUriString(r.getResponse().getRedirectedUrl()).build();
        assertEquals("provider.test", u.getHost());
        assertEquals("https://blog.example/pressroom-ui/sso/callback",
                URLDecoder.decode(u.getQueryParams().getFirst("redirect_uri"), UTF_8));
        assertNotNull(u.getQueryParams().getFirst("state"));
        assertNotNull(u.getQueryParams().getFirst("nonce"));
        assertEquals("S256", u.getQueryParams().getFirst("code_challenge_method"));
    }

    /**
     * The provider returns the browser to the Absolute URL's host, so a sign-in
     * started on any other host (here the LAN address) moves there first, before a
     * session exists on the host it was started from.
     */
    @Test
    void aStartOnAnotherHostMovesToTheAbsoluteUrlBeforeAnySessionExists() throws Exception {
        MvcResult r = onMvc.perform(get("http://docker2.lan:8080/pressroom-ui/sso/start").param("return", "/blog/"))
                .andExpect(redirectedUrl(ABSOLUTE_URL + "/pressroom-ui/sso/start?return=%2Fblog%2F"))
                .andReturn();
        assertNull(r.getRequest().getSession(false), "a session on a host the provider never returns to");
    }

    @Test
    void authorizeForAnUnknownRegistrationFailsClosedRatherThanErroring() throws Exception {
        onMvc.perform(get("/pressroom-ui/sso/authorize/other"))
                .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"));
    }

    @Test
    void anonymousOnAProtectedPageStillGoesToThePasswordLoginPage() throws Exception {
        onMvc.perform(get("/pressroom-ui/menu.rol")).andExpect(redirectedUrl("/pressroom-ui/login.rol"));
    }

    // ------------------------------------------------- criteria 10 and 11

    @Test
    void callbackWithAForgedStateFailsClosed() throws Exception {
        onMvc.perform(get("/pressroom-ui/sso/callback").param("code", "c").param("state", "forged"))
                .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"))
                .andExpect(unauthenticated());
    }

    @Test
    void callbackWithAForgedStateFailsClosedEvenWithASignInInProgress() throws Exception {
        MockHttpSession session = new MockHttpSession();
        authorize(session);
        onMvc.perform(get("/pressroom-ui/sso/callback").session(session).param("code", "c").param("state", "forged"))
                .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"))
                .andExpect(unauthenticated());
    }

    @Test
    void callbackWithNeitherCodeNorStateFailsClosed() throws Exception {
        onMvc.perform(get("/pressroom-ui/sso/callback"))
                .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"))
                .andExpect(unauthenticated());
    }

    // -------------------------------------------- the full round trip

    /**
     * Review Focus 3. The UI chain's remember-me services would otherwise be
     * applied to the sign-in filter too: with the parameter present they look
     * the principal up as a password user, which a reader is not, and the
     * sign-in ends at {@code sso=failed} with the reader already signed in.
     *
     * <p>Both parameter names are sent. {@code _spring_security_remember_me} is
     * the one the login page's checkbox posts, but {@code PressroomRememberMeServices}
     * sets no parameter name and so reads Spring's default, {@code remember-me};
     * the first alone would leave this test passing with no protection in place.
     *
     * <p>The callback names its session the way a browser's cookie does. Spring
     * rotates the id only when the requested one is valid, and since spring-test
     * 7.0.10 a mock request that names no session id has none that is valid.
     */
    @Test
    void callbackNeverSetsARememberMeCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();
        startOnTheAbsoluteUrlsHost(session);
        String state = authorize(session);
        String before = session.getId();

        MvcResult r = onMvc.perform(get("/pressroom-ui/sso/callback").session(session)
                        .with(request -> {
                            request.setRequestedSessionId(before);
                            return request;
                        })
                        .param("code", "c").param("state", state)
                        .param("_spring_security_remember_me", "on")
                        .param(AbstractRememberMeServices.DEFAULT_PARAMETER, "on"))
                .andExpect(redirectedUrl("/blog/"))
                .andExpect(authenticated().withUsername(READER)
                        .withAuthorities(List.of(new SimpleGrantedAuthority("reader"))))
                .andReturn();

        for (Cookie cookie : r.getResponse().getCookies()) {
            assertNotEquals(AbstractRememberMeServices.SPRING_SECURITY_REMEMBER_ME_COOKIE_KEY, cookie.getName(),
                    "no remember-me for single sign-on");
        }
        assertNotEquals(before, r.getRequest().getSession().getId(), "sign-in changes the session id");
    }

    /**
     * Roller uses the provider only to sign the reader in. Spring's default keeps
     * every reader's access token in an in-memory map for the life of the process;
     * after a real sign-in, the chain's repository must hold nothing for the reader.
     */
    @Test
    void aSignInKeepsNoAccessToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String state = authorize(session);
        MvcResult r = onMvc.perform(get("/pressroom-ui/sso/callback").session(session)
                        .param("code", "c").param("state", state))
                .andExpect(authenticated().withUsername(READER))
                .andReturn();

        Authentication reader = new TestingAuthenticationToken(READER, null, "reader");
        assertNull(signInFilterClients().loadAuthorizedClient(SsoSettings.REGISTRATION_ID, reader, r.getRequest()),
                "no authorized client (access token) is kept after sign-in");
    }

    // ----------------------------------------------------------- criterion 4

    /**
     * Criterion 4: the client secret appears in no log line, response or exception
     * message, across a failed sign-in (a forged {@code state} while a real sign-in
     * is pending) and a successful one (the signed ID token round trip).
     *
     * <p>Every logger is captured at every level, so a library's DEBUG or TRACE line
     * counts as well as Roller's own: a sign-in an operator is debugging must not
     * leak the secret either. Looked for as itself and as the Basic credential that
     * carries it ({@code client_secret_basic}). Each phase must have logged
     * something, or the absence would prove nothing.
     */
    @Test
    void theClientSecretAppearsInNoLogLineResponseOrExceptionMessage() throws Exception {
        List<String> secretForms = List.of(SsoTestRegistrations.CLIENT_SECRET,
                Base64.getEncoder().encodeToString((SsoTestRegistrations.CLIENT_ID + ":"
                        + SsoTestRegistrations.CLIENT_SECRET).getBytes(UTF_8)));
        List<MvcResult> results = new ArrayList<>();
        try (SsoLogCapture log = SsoLogCapture.everyLogger()) {
            MockHttpSession failed = new MockHttpSession();
            results.add(startOnTheAbsoluteUrlsHost(failed));
            results.add(authorizeStep(failed));
            results.add(onMvc.perform(get("/pressroom-ui/sso/callback").session(failed)
                            .param("code", "c").param("state", "forged"))
                    .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"))
                    .andExpect(unauthenticated())
                    .andReturn());
            List<String> afterFailure = log.everything();
            assertTrue(afterFailure.stream().anyMatch(line -> line.contains("Reader sign-in refused or failed")),
                    "the failed sign-in's WARN was not captured: " + afterFailure.size() + " lines");

            MockHttpSession succeeded = new MockHttpSession();
            results.add(startOnTheAbsoluteUrlsHost(succeeded));
            MvcResult authorized = authorizeStep(succeeded);
            results.add(authorized);
            results.add(onMvc.perform(get("/pressroom-ui/sso/callback").session(succeeded)
                            .param("code", "c").param("state", stateOf(authorized)))
                    .andExpect(redirectedUrl("/blog/"))
                    .andExpect(authenticated().withUsername(READER))
                    .andReturn());
            List<String> lines = log.everything();
            assertTrue(lines.size() > afterFailure.size(), "the successful sign-in logged nothing to look at");

            for (String line : lines) {
                for (String secret : secretForms) {
                    assertFalse(line.contains(secret), "a log line carries the client secret: " + line);
                }
            }
        }
        for (MvcResult result : results) {
            String response = everythingIn(result);
            for (String secret : secretForms) {
                assertFalse(response.contains(secret), "a response carries the client secret: " + response);
            }
        }
    }

    @Test
    void anAccountStoreThatThrowsFailsClosed() throws Exception {
        users = request -> {
            throw new IllegalStateException("database unreachable");
        };
        MockHttpSession session = new MockHttpSession();
        String state = authorize(session);
        onMvc.perform(get("/pressroom-ui/sso/callback").session(session).param("code", "c").param("state", state))
                .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"))
                .andExpect(unauthenticated());
    }

    // ------------------------------------------------------------- fixtures

    /** The authorized-client repository the built chain's sign-in filter actually uses. */
    private static OAuth2AuthorizedClientRepository signInFilterClients() {
        FilterChainProxy proxy = onContext.getBean(FilterChainProxy.class);
        for (SecurityFilterChain chain : proxy.getFilterChains()) {
            for (Filter filter : chain.getFilters()) {
                if (filter instanceof OAuth2LoginAuthenticationFilter signIn) {
                    return (OAuth2AuthorizedClientRepository)
                            ReflectionTestUtils.getField(signIn, "authorizedClientRepository");
                }
            }
        }
        throw new AssertionError("no OAuth2LoginAuthenticationFilter in the built chain");
    }

    /** Runs the authorization step in {@code session}; returns the state it sent to the provider. */
    private static String authorize(MockHttpSession session) throws Exception {
        return stateOf(authorizeStep(session));
    }

    /** The authorization step in {@code session}: the redirect to the provider. */
    private static MvcResult authorizeStep(MockHttpSession session) throws Exception {
        return onMvc.perform(get("/pressroom-ui/sso/authorize/oidc").session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
    }

    /** @return the {@code state} an authorization step sent to the provider */
    private static String stateOf(MvcResult authorizeStep) {
        String state = UriComponentsBuilder.fromUriString(authorizeStep.getResponse().getRedirectedUrl()).build()
                .getQueryParams().getFirst("state");
        assertNotNull(state);
        return URLDecoder.decode(state, UTF_8);
    }

    /**
     * The start step on the Absolute URL's own host, returning to {@code /blog/}. The
     * port is explicit: MockMvc keeps port 80 for an https URI with none, where Tomcat
     * (and the forwarded-header filter) would report 443.
     */
    private static MvcResult startOnTheAbsoluteUrlsHost(MockHttpSession session) throws Exception {
        return onMvc.perform(get("https://blog.example:443/pressroom-ui/sso/start").session(session)
                        .param("return", "/blog/"))
                .andExpect(redirectedUrl("/pressroom-ui/sso/authorize/oidc"))
                .andReturn();
    }

    /** Everything a response shows: status, headers, cookies, redirect, forward, body, and any exception. */
    private static String everythingIn(MvcResult result) throws Exception {
        StringBuilder text = new StringBuilder();
        text.append(result.getResponse().getStatus()).append('\n');
        for (String name : result.getResponse().getHeaderNames()) {
            text.append(name).append(": ").append(result.getResponse().getHeaders(name)).append('\n');
        }
        for (Cookie cookie : result.getResponse().getCookies()) {
            text.append(cookie.getName()).append('=').append(cookie.getValue()).append('\n');
        }
        text.append(result.getResponse().getRedirectedUrl()).append('\n')
                .append(result.getResponse().getForwardedUrl()).append('\n')
                .append(result.getResponse().getErrorMessage()).append('\n')
                .append(result.getResponse().getContentAsString()).append('\n');
        if (result.getResolvedException() != null) {
            text.append(result.getResolvedException());
        }
        return text.toString();
    }

    private static OidcUser aReader(OidcUserRequest request) {
        return new PressroomOidcUser(new DefaultOidcUser(List.of(), request.getIdToken()), READER,
                List.of(new SimpleGrantedAuthority("reader")), true);
    }

    /**
     * The provider's token endpoint, answered locally. The ID token carries the
     * nonce hash of the authorization request it answers, as a real provider's does.
     */
    private static OAuth2AccessTokenResponse cannedTokenResponse(OAuth2AuthorizationCodeGrantRequest grant) {
        String nonce = grant.getAuthorizationExchange().getAuthorizationRequest()
                .getAttribute(OidcParameterNames.NONCE);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SsoTestRegistrations.PROVIDER)
                .subject("subject-1")
                .audience(List.of(SsoTestRegistrations.CLIENT_ID))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(OidcParameterNames.NONCE, sha256(nonce))
                .build();
        String idToken = NimbusJwtEncoder
                .withKeyPair((RSAPublicKey) KEYS.getPublic(), (RSAPrivateKey) KEYS.getPrivate()).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
        return OAuth2AccessTokenResponse.withToken("access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(3600)
                .additionalParameters(Map.of(OidcParameterNames.ID_TOKEN, idToken))
                .build();
    }

    /** Production's decoder fetches the provider's JWK set; this one knows the test key. */
    private static NimbusJwtDecoder testKeyDecoder(ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
        return decoder;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static KeyPair rsaKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
