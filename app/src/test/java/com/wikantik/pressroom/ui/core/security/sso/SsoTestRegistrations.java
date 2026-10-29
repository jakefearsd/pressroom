package com.wikantik.pressroom.ui.core.security.sso;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * A client registration shaped like the one {@link LazyOidcClientRegistrationRepository}
 * builds from discovery, but pointing at {@code http://provider.test}, which nothing
 * ever contacts: tests stub the token endpoint and the ID token decoder.
 */
public final class SsoTestRegistrations {

    /** The stub provider's issuer. */
    public static final String PROVIDER = "http://provider.test";
    /** The stub registration's client id: the ID token's audience. */
    public static final String CLIENT_ID = "pressroom-test";
    /** The stub registration's client secret, distinctive so a test can look for it anywhere. */
    public static final String CLIENT_SECRET = "stub-client-secret-7c41e9";

    private SsoTestRegistrations() {
    }

    /** @return the {@code oidc} registration */
    public static ClientRegistration registration() {
        return ClientRegistration.withRegistrationId(SsoSettings.REGISTRATION_ID)
                .clientId(CLIENT_ID)
                .clientSecret(CLIENT_SECRET)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/pressroom-ui/sso/callback")
                .scope("openid", "profile", "email")
                .authorizationUri(PROVIDER + "/authorize")
                .tokenUri(PROVIDER + "/token")
                .jwkSetUri(PROVIDER + "/jwks")
                .issuerUri(PROVIDER)
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    /** @return a repository that knows only the {@code oidc} registration */
    public static ClientRegistrationRepository present() {
        ClientRegistration registration = registration();
        return id -> SsoSettings.REGISTRATION_ID.equals(id) ? registration : null;
    }
}
