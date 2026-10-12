package org.apache.roller.weblogger.ui.core.security.sso;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The signed-in reader: provider claims, but Roller's username and Roller's roles.
 * The principal name is the Roller username, never the provider subject, and no
 * provider claim can add an authority.
 */
public final class RollerOidcUser implements OidcUser, Serializable {

    private static final long serialVersionUID = 1L;

    private final OidcUser delegate;
    private final String rollerUserName;
    private final List<GrantedAuthority> authorities;
    private final boolean newAccount;

    /**
     * @param delegate       the user the provider returned
     * @param rollerUserName the Roller username
     * @param authorities    the Roller roles, as authorities
     * @param newAccount     true when this sign-in created the account
     */
    public RollerOidcUser(OidcUser delegate, String rollerUserName,
            Collection<? extends GrantedAuthority> authorities, boolean newAccount) {
        this.delegate = delegate;
        this.rollerUserName = rollerUserName;
        this.authorities = authorities.stream()
                .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a.getAuthority()))
                .toList();
        this.newAccount = newAccount;
    }

    /** @return true when this sign-in created the account */
    public boolean isNewAccount() {
        return newAccount;
    }

    @Override
    public String getName() {
        return rollerUserName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }
}
