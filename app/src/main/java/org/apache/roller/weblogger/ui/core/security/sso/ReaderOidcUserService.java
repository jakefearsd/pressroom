package org.apache.roller.weblogger.ui.core.security.sso;

import org.apache.roller.weblogger.WebloggerException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.net.URL;
import java.util.List;

/** Turns the provider's OIDC user into a Roller reader principal. */
public class ReaderOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    /** OAuth2 error code: Roller declined the sign-in. */
    public static final String REFUSED = "sso_refused";
    /** OAuth2 error code: the account store failed. */
    public static final String FAILED = "sso_failed";

    private final ReaderAccounts accounts;
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    /** @param accounts the reader account binding */
    public ReaderOidcUserService(ReaderAccounts accounts) {
        this(accounts, new OidcUserService());
    }

    ReaderOidcUserService(ReaderAccounts accounts, OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
        this.accounts = accounts;
        this.delegate = delegate;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser fromProvider = delegate.loadUser(userRequest);
        OidcIdToken idToken = fromProvider == null ? null : fromProvider.getIdToken();
        URL issuer = idToken == null ? null : idToken.getIssuer();
        // Refused, not failed: with no (issuer, subject) there is no identity to bind an account to.
        if (issuer == null || fromProvider.getSubject() == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(REFUSED, "no issuer or subject", null));
        }
        ReaderClaims claims = ReaderClaims.from(issuer.toString(), fromProvider.getSubject(),
                fromProvider.getClaims());
        try {
            ReaderAccounts.SignIn signIn = accounts.signIn(claims);
            List<GrantedAuthority> authorities = signIn.roles().stream()
                    .map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
            return new RollerOidcUser(fromProvider, signIn.user().getUserName(), authorities, signIn.created());
        } catch (ReaderSignInRefused refused) {
            throw new OAuth2AuthenticationException(new OAuth2Error(REFUSED, refused.reason(), null),
                    refused.reason(), refused);
        } catch (WebloggerException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error(FAILED), "reader account store failed", ex);
        }
    }
}
