package com.wikantik.pressroom.ui.core;

import jakarta.servlet.http.HttpSessionEvent;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
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
class PressroomSessionLifecycleTest {

    private final UserManager users = mock(UserManager.class);
    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final MockHttpSession httpSession = new MockHttpSession();
    private final PressroomLoginSessionManager logins = PressroomLoginSessionManager.getInstance();
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
        assertNull(PressroomSession.getRollerSession(new MockHttpServletRequest(), provider));
    }

    @Test
    void anEnabledUsersLoginIsRegisteredAndReusedOnTheNextRequest() throws Exception {
        user(true);

        PressroomSession first = PressroomSession.getRollerSession(requestWithPrincipal(), provider);
        PressroomSession second = PressroomSession.getRollerSession(requestWithPrincipal(), provider);

        assertSame(first, second);
        assertSame(first, logins.get(name));
        assertNotNull(first.getAuthenticatedUser(users));
    }

    @Test
    void aDisabledAccountIsNeverAttachedToTheSession() throws Exception {
        user(false);

        PressroomSession result = PressroomSession.getRollerSession(requestWithPrincipal(), provider);

        assertNull(result.getAuthenticatedUser(users));
        assertNull(logins.get(name));
    }

    @Test
    void aPrincipalWhoseUserDoesNotExistLeavesTheSessionAnonymous() throws Exception {
        when(users.getUserByUserName(name)).thenReturn(null);

        PressroomSession result = PressroomSession.getRollerSession(requestWithPrincipal(), provider);

        assertNull(result.getAuthenticatedUser(users));
    }

    @Test
    void aUserLookupThatFailsLeavesTheSessionAnonymousRatherThanThrowing() throws Exception {
        when(users.getUserByUserName(name)).thenThrow(new WebloggerException("store down"));

        PressroomSession result = PressroomSession.getRollerSession(requestWithPrincipal(), provider);

        assertNotNull(result);
        assertNull(result.getAuthenticatedUser(users));
    }

    @Test
    void aLoginTheServerHasInvalidatedIsReplacedByAFreshSession() throws Exception {
        user(true);
        PressroomSession first = PressroomSession.getRollerSession(requestWithPrincipal(), provider);
        logins.invalidate(name);

        // no principal now: the replacement must not quietly inherit the old user
        MockHttpServletRequest again = new MockHttpServletRequest();
        again.setSession(httpSession);
        PressroomSession second = PressroomSession.getRollerSession(again, provider);

        assertNotSame(first, second);
        assertNull(second.getAuthenticatedUser(users));
    }

    @Test
    void theContainersSessionEventsCreateAndPurgeTheRollerSession() {
        PressroomSession listener = new PressroomSession();
        listener.sessionCreated(new HttpSessionEvent(httpSession));
        assertNotNull(httpSession.getAttribute(PressroomSession.ROLLER_SESSION));

        listener.sessionWillPassivate(new HttpSessionEvent(httpSession));
        assertNull(httpSession.getAttribute(PressroomSession.ROLLER_SESSION), "passivation purges the session");

        listener.sessionCreated(new HttpSessionEvent(httpSession));
        listener.sessionDestroyed(new HttpSessionEvent(httpSession));
        assertNull(httpSession.getAttribute(PressroomSession.ROLLER_SESSION));
    }

    @Test
    void aSessionCannotBeResolvedToAUserWithoutAManagerOrWhenTheLookupFails() throws Exception {
        user(true);
        PressroomSession session = new PressroomSession();
        session.setAuthenticatedUser(users.getUserByUserName(name));
        assertNull(session.getAuthenticatedUser(null), "no manager yet: nobody");

        when(users.getUserByUserName(name)).thenThrow(new WebloggerException("store down"));
        assertNull(session.getAuthenticatedUser(users), "an unanswerable lookup is nobody, not the old user");
    }
}
