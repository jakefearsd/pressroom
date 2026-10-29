package com.wikantik.pressroom.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.NullRememberMeServices;

/**
 * Adds reader sign-in to the UI security chain: Spring's {@code oauth2Login()}
 * on Roller's paths, plus {@link SsoStartFilter} in front of it. Exists as a
 * bean only when SSO is on ({@code SsoConfig}); {@code SecurityConfig} applies it
 * when it is there.
 *
 * <p>Four things here differ from {@code oauth2Login()}'s defaults, each because
 * the default is wrong for this chain:
 * <ul>
 *   <li><b>Its own authentication manager.</b> The UI chain sets Roller's
 *       password manager explicitly, and {@code HttpSecurity} then ignores the
 *       providers {@code oauth2Login()} registers -- the callback would reach a
 *       manager that cannot authenticate an OIDC sign-in, and every sign-in
 *       would fail. The sign-in filter gets a manager holding the OIDC provider
 *       alone, so it can authenticate nothing else, and the password manager
 *       gains nothing.</li>
 *   <li><b>No remember-me</b> (spec, Session and cookies). The chain's
 *       remember-me services would otherwise be applied to this filter too, and
 *       with {@code remember-me=on} on the callback -- Spring's default
 *       parameter, which {@code PressroomRememberMeServices} reads; not the login
 *       page's {@code _spring_security_remember_me} -- they look
 *       the reader up as a password user and fail the sign-in.</li>
 *   <li><b>No stored access token.</b> Roller never calls the provider after
 *       sign-in; the default keeps every reader's token in an in-memory map for
 *       the life of the process.</li>
 *   <li><b>Failures redirect, never 500.</b> An authorization request that
 *       cannot be built (an unknown registration, discovery down) and an
 *       unexpected exception from the account store both end at the failure
 *       handler, which answers {@code sso=failed} and releases the persistence
 *       session.</li>
 * </ul>
 */
public class SsoLogin {

    private final ClientRegistrationRepository registrations;
    private final OAuth2AuthorizationRequestResolver resolver;
    private final OAuth2UserService<OidcUserRequest, OidcUser> userService;
    private final AuthenticationSuccessHandler successHandler;
    private final AuthenticationFailureHandler failureHandler;
    private final SsoStartFilter startFilter;
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient;
    private final JwtDecoderFactory<ClientRegistration> jwtDecoderFactory;

    /**
     * Production wiring: the provider's real token endpoint and ID token keys.
     *
     * @param registrations  the provider registration
     * @param resolver       builds the authorization request (callback from the Absolute URL)
     * @param userService    binds the provider identity to a Roller reader
     * @param successHandler where a signed-in reader lands
     * @param failureHandler where a failed sign-in lands
     * @param startFilter    {@code /roller-ui/sso/start}
     */
    public SsoLogin(ClientRegistrationRepository registrations, OAuth2AuthorizationRequestResolver resolver,
            OAuth2UserService<OidcUserRequest, OidcUser> userService, AuthenticationSuccessHandler successHandler,
            AuthenticationFailureHandler failureHandler, SsoStartFilter startFilter) {
        this(registrations, resolver, userService, successHandler, failureHandler, startFilter, null, null);
    }

    /**
     * As above, with the token endpoint and the ID token decoder replaced. Public
     * because the wiring test lives beside {@code SecurityConfig}; production
     * passes neither.
     *
     * @param tokenResponseClient the token endpoint, or null for the provider's own
     * @param jwtDecoderFactory   the ID token decoder, or null for one built from the provider's JWK set
     */
    public SsoLogin(ClientRegistrationRepository registrations, OAuth2AuthorizationRequestResolver resolver,
            OAuth2UserService<OidcUserRequest, OidcUser> userService, AuthenticationSuccessHandler successHandler,
            AuthenticationFailureHandler failureHandler, SsoStartFilter startFilter,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient,
            JwtDecoderFactory<ClientRegistration> jwtDecoderFactory) {
        this.registrations = registrations;
        this.resolver = resolver;
        this.userService = userService;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
        this.startFilter = startFilter;
        this.tokenResponseClient = tokenResponseClient;
        this.jwtDecoderFactory = jwtDecoderFactory;
    }

    /** Adds the sign-in endpoints to {@code http}, the UI chain. */
    // javac rejects the diamond on this anonymous class: its generic postProcess(O) method cannot be inferred
    @SuppressWarnings("PMD.UseDiamondOperator")
    public void applyTo(HttpSecurity http) {
        AuthenticationManager signIn = signInManager();
        http.oauth2Login(oauth -> oauth
                .loginPage(SsoPaths.LOGIN_PAGE)
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                .authorizationEndpoint(a -> a.baseUri(SsoPaths.AUTHORIZE_BASE)
                        .authorizationRequestResolver(resolver))
                .redirectionEndpoint(r -> r.baseUri(SsoPaths.CALLBACK))
                // The manager below is what authenticates; this keeps the configurer's
                // own (unused) provider on the same user service rather than a default.
                .userInfoEndpoint(u -> u.oidcUserService(userService))
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                    @Override
                    public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationManager(signIn);
                        filter.setRememberMeServices(new NullRememberMeServices());
                        return filter;
                    }
                })
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                    @Override
                    public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler(failureHandler);
                        return filter;
                    }
                }));
        http.addFilterBefore(startFilter, OAuth2AuthorizationRequestRedirectFilter.class);
    }

    /**
     * The OIDC provider alone. Anything it throws that is not already an
     * authentication failure -- the account store's own exceptions, say --
     * becomes one, so the failure handler answers it rather than a 500 that
     * skips the handler and its persistence-session release.
     */
    private AuthenticationManager signInManager() {
        OidcAuthorizationCodeAuthenticationProvider oidc = new OidcAuthorizationCodeAuthenticationProvider(
                tokenResponseClient != null ? tokenResponseClient : new RestClientAuthorizationCodeTokenResponseClient(),
                userService);
        if (jwtDecoderFactory != null) {
            oidc.setJwtDecoderFactory(jwtDecoderFactory);
        }
        ProviderManager providers = new ProviderManager(oidc);
        return authentication -> {
            try {
                return providers.authenticate(authentication);
            } catch (AuthenticationException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new AuthenticationServiceException("reader sign-in failed", ex);
            }
        };
    }

    /** Keeps no authorized client: Roller uses the provider only to sign the reader in. */
    static final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            // Deliberately nothing: the access token is never used after sign-in.
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            // Nothing was ever saved.
        }
    }
}
