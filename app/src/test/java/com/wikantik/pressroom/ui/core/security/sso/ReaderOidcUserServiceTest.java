package com.wikantik.pressroom.ui.core.security.sso;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderOidcUserServiceTest {

    private static final Map<String, Object> GOOD = Map.of("email", "a@b.c", "email_verified", true,
            "name", "A", "roles", List.of("admin"));

    private static OidcUser oidcUser(String iss, String sub, Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>(claims);
        all.put("iss", iss);
        all.put("sub", sub);
        OidcIdToken token = new OidcIdToken("tok", Instant.now(), Instant.now().plusSeconds(60), all);
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), token);
    }

    private static OidcUserRequest request() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("oidc")
                .clientId("id").clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/cb").scope("openid")
                .authorizationUri("http://localhost/auth").tokenUri("http://localhost/token")
                .jwkSetUri("http://localhost/jwks").build();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at",
                Instant.now(), Instant.now().plusSeconds(60));
        OidcIdToken id = new OidcIdToken("tok", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("iss", "https://issuer.test", "sub", "sub-1"));
        return new OidcUserRequest(reg, access, id);
    }

    private static ReaderOidcUserService service(ReaderAccounts accounts) {
        OidcUser fromProvider = oidcUser("https://issuer.test", "sub-1", GOOD);
        return new ReaderOidcUserService(accounts, req -> fromProvider);
    }

    @Test
    void principalNameIsTheRollerUsernameAndAuthoritiesAreRollerRoles() throws Exception {
        ReaderAccounts accounts = mock(ReaderAccounts.class);
        User u = new User();
        u.setUserName("reader-abcdefghij");
        when(accounts.signIn(any())).thenReturn(new ReaderAccounts.SignIn(u, List.of("reader"), true));
        OidcUser result = service(accounts).loadUser(request());
        assertEquals("reader-abcdefghij", result.getName());
        assertEquals(Set.of("reader"), AuthorityUtils.authorityListToSet(result.getAuthorities()));
        assertTrue(((PressroomOidcUser) result).isNewAccount());
        assertEquals("sub-1", result.getSubject());
        assertDoesNotThrow(() -> new ObjectOutputStream(OutputStream.nullOutputStream()).writeObject(result));
    }

    @Test
    void aRefusalBecomesAnSsoRefusedOAuth2Error() throws Exception {
        ReaderAccounts accounts = mock(ReaderAccounts.class);
        when(accounts.signIn(any())).thenThrow(new ReaderSignInRefused("account disabled"));
        OAuth2AuthenticationException ex = assertThrows(OAuth2AuthenticationException.class,
                () -> service(accounts).loadUser(request()));
        assertEquals("sso_refused", ex.getError().getErrorCode());
    }

    @Test
    void aStoreFailureBecomesSsoFailed() throws Exception {
        ReaderAccounts accounts = mock(ReaderAccounts.class);
        when(accounts.signIn(any())).thenThrow(new WebloggerException("db down"));
        OAuth2AuthenticationException ex = assertThrows(OAuth2AuthenticationException.class,
                () -> service(accounts).loadUser(request()));
        assertEquals("sso_failed", ex.getError().getErrorCode());
    }

    @Test
    void claimsArePassedThroughWithIssuerAndSubjectFromTheIdToken() throws Exception {
        ReaderAccounts accounts = mock(ReaderAccounts.class);
        User u = new User();
        u.setUserName("reader-abcdefghij");
        when(accounts.signIn(any())).thenReturn(new ReaderAccounts.SignIn(u, List.of("reader"), false));
        service(accounts).loadUser(request());
        ArgumentCaptor<ReaderClaims> captor = ArgumentCaptor.forClass(ReaderClaims.class);
        verify(accounts).signIn(captor.capture());
        ReaderClaims c = captor.getValue();
        assertEquals("https://issuer.test", c.issuer());
        assertEquals("sub-1", c.subject());
        assertEquals("a@b.c", c.email());
        assertTrue(c.emailVerified());
        assertEquals("A", c.name());
    }

    @Test
    void aProviderUserWithoutAnIssuerIsRefusedAndNeverReachesTheStore() {
        ReaderAccounts accounts = mock(ReaderAccounts.class);
        OidcUser noIssuer = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"),
                new OidcIdToken("tok", Instant.now(), Instant.now().plusSeconds(60), Map.of("sub", "sub-1")));
        OAuth2AuthenticationException ex = assertThrows(OAuth2AuthenticationException.class,
                () -> new ReaderOidcUserService(accounts, req -> noIssuer).loadUser(request()));
        assertEquals("sso_refused", ex.getError().getErrorCode());
        org.mockito.Mockito.verifyNoInteractions(accounts);
    }
}
