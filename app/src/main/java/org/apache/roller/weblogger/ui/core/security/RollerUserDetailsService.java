package org.apache.roller.weblogger.ui.core.security;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.User;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;

/**
 * Spring Security UserDetailsService implemented using Weblogger API.
 */
public class RollerUserDetailsService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(RollerUserDetailsService.class);

    private final WebloggerProvider provider;

    /**
     * @param provider the business tier, asked for on every lookup rather than
     *                 held as a lazy facade: Spring Security calls this service
     *                 before the tier is bootstrapped (first-time setup), and
     *                 the provider throws there, which is mapped to a soft
     *                 failure below -- a lazy proxy would instead try to build
     *                 the graph.
     */
    public RollerUserDetailsService(WebloggerProvider provider) {
        this.provider = provider;
    }

    /**
     * @throws UsernameNotFoundException, DataAccessException
     */
    @Override
    public UserDetails loadUserByUsername(String userName) {
        Weblogger roller;
        try {
            roller = provider.getWeblogger();
        } catch (Exception e) {
            // Should only happen in case of 1st time startup, setup required
            log.debug("Ignorable error getting Roller instance", e);
            // Thowing a "soft" exception here allows setup to proceed
            throw new UsernameNotFoundException("User info not available yet.", e);
        }
        try {
            UserManager umgr = roller.getUserManager();
            User userData;
            try {
                userData = umgr.getUserByUserName(userName);
            } catch (WebloggerException ex) {
                throw new DataRetrievalFailureException("ERROR in user lookup", ex);
            }
            if (userData == null) {
                throw new UsernameNotFoundException("ERROR no user: " + userName);
            }
            if (userData.isSsoBound()) {
                // A bound account has no password; to password sign-in it is
                // indistinguishable from an unknown user.
                throw new UsernameNotFoundException("ERROR no password user: " + userName);
            }
            if (userData.isAgent()) {
                // Spec 2026-10-13: an agent never signs in -- form login,
                // remember-me and HTTP Basic all arrive here. Thrown as "not
                // found", which DaoAuthenticationProvider reports as the same
                // "Bad credentials" a wrong password gets.
                throw new UsernameNotFoundException("ERROR agent account: " + userName);
            }
            List<SimpleGrantedAuthority> authorities = getAuthorities(userData, umgr);
            return new org.springframework.security.core.userdetails.User(
                    userData.getUserName(), userData.getPassword(),
                    true, true, true, true, authorities);
        } catch (WebloggerException ex) {
            throw new DataAccessResourceFailureException("ERROR: fetching roles", ex);
        }
        

    }
        
    private List<SimpleGrantedAuthority> getAuthorities(User userData, UserManager umgr) throws WebloggerException {
        List<String> roles = umgr.getRoles(userData);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(roles.size());
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        return authorities;
    }
    
}
