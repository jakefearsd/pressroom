package org.apache.roller.weblogger.ui.core;

import jakarta.servlet.http.HttpSessionEvent;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who a session belongs to, and when it stops belonging to them: a login that
 * the server has since invalidated, an account that is disabled, a user
 * lookup that fails, and the servlet container's session events. A session
 * that cannot be tied to an enabled user must read as "nobody", never as the
 * last person who used it.
 */
class RollerSessionLifecycleTest {

    private final UserManager users = mock(UserManager.class);
    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final MockHttpSession httpSession = new MockHttpSession();
    private final RollerLoginSessionManager logins = RollerLoginSessionManager.getInstance();
    private final String name = "lifecycle-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);
    }

    @AfterEach
    void tearDown() {
        logins.invalidate(name);
    }

    private User user(boolean enabled) throws WebloggerException {
        User user = new User();
        user.setUserName(name);
        user.setEnabled(enabled);
        when(users.getUserByUserName(name)).thenReturn(user);
        return user;
    }

    private MockHttpServletRequest requestWithPrincipal() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(httpSession);
        request.setUserPrincipal(() -> name);
        return request;
    }

    @Test
    void aRequestWithNoHttpSessionHasNoRollerSession() {
        assertNull(RollerSession.getRollerSession(new MockHttpServletRequest(), provider));
    }

    @Test
    void anEnabledUsersLoginIsRegisteredAndReusedOnTheNextRequest() throws Exception {
        user(true);

        RollerSession first = RollerSession.getRollerSession(requestWithPrincipal(), provider);
        RollerSession second = RollerSession.getRollerSession(requestWithPrincipal(), provider);

        assertSame(first, second);
        assertSame(first, logins.get(name));
        assertNotNull(first.getAuthenticatedUser(users));
    }

    @Test
    void aDisabledAccountIsNeverAttachedToTheSession() throws Exception {
        user(false);

        RollerSession result = RollerSession.getRollerSession(requestWithPrincipal(), provider);

        assertNull(result.getAuthenticatedUser(users));
        assertNull(logins.get(name));
    }

    @Test
    void aPrincipalWhoseUserDoesNotExistLeavesTheSessionAnonymous() throws Exception {
        when(users.getUserByUserName(name)).thenReturn(null);

        RollerSession result = RollerSession.getRollerSession(requestWithPrincipal(), provider);

        assertNull(result.getAuthenticatedUser(users));
    }

    @Test
    void aUserLookupThatFailsLeavesTheSessionAnonymousRatherThanThrowing() throws Exception {
        when(users.getUserByUserName(name)).thenThrow(new WebloggerException("store down"));

        RollerSession result = RollerSession.getRollerSession(requestWithPrincipal(), provider);

        assertNotNull(result);
        assertNull(result.getAuthenticatedUser(users));
    }

    @Test
    void aLoginTheServerHasInvalidatedIsReplacedByAFreshSession() throws Exception {
        user(true);
        RollerSession first = RollerSession.getRollerSession(requestWithPrincipal(), provider);
        logins.invalidate(name);

        // no principal now: the replacement must not quietly inherit the old user
        MockHttpServletRequest again = new MockHttpServletRequest();
        again.setSession(httpSession);
        RollerSession second = RollerSession.getRollerSession(again, provider);

        assertNotSame(first, second);
        assertNull(second.getAuthenticatedUser(users));
    }

    @Test
    void theContainersSessionEventsCreateAndPurgeTheRollerSession() {
        RollerSession listener = new RollerSession();
        listener.sessionCreated(new HttpSessionEvent(httpSession));
        assertNotNull(httpSession.getAttribute(RollerSession.ROLLER_SESSION));

        listener.sessionWillPassivate(new HttpSessionEvent(httpSession));
        assertNull(httpSession.getAttribute(RollerSession.ROLLER_SESSION), "passivation purges the session");

        listener.sessionCreated(new HttpSessionEvent(httpSession));
        listener.sessionDestroyed(new HttpSessionEvent(httpSession));
        assertNull(httpSession.getAttribute(RollerSession.ROLLER_SESSION));
    }

    @Test
    void aSessionCannotBeResolvedToAUserWithoutAManagerOrWhenTheLookupFails() throws Exception {
        user(true);
        RollerSession session = new RollerSession();
        session.setAuthenticatedUser(users.getUserByUserName(name));
        assertNull(session.getAuthenticatedUser(null), "no manager yet: nobody");

        when(users.getUserByUserName(name)).thenThrow(new WebloggerException("store down"));
        assertNull(session.getAuthenticatedUser(users), "an unanswerable lookup is nobody, not the old user");
    }
}
