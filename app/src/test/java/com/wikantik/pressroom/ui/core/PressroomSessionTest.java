package com.wikantik.pressroom.ui.core;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * The per-session object and its static factory. Since the DI wave (plan Task
 * 6b) neither reaches the static locator: the factory is handed a
 * {@link WebloggerProvider} (it must not resolve a principal before the tier is
 * up), and the user lookup is handed the {@link UserManager} to resolve with.
 */
class PressroomSessionTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpSession session;

    @Mock
    private Principal principal;

    @Mock
    private Weblogger roller;

    @Mock
    private WebloggerProvider provider;

    @Mock
    private UserManager userManager;

    @Mock
    private User user;

    private PressroomSession rollerSession;
    private PressroomLoginSessionManager sessionManager;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        sessionManager = PressroomLoginSessionManager.getInstance();
        rollerSession = new PressroomSession();

        when(request.getSession(false)).thenReturn(session);
        when(roller.getUserManager()).thenReturn(userManager);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(roller);
    }

    @Test
    void testGetRollerSessionNewSession() {
        when(session.getAttribute(PressroomSession.ROLLER_SESSION)).thenReturn(null);
        when(request.getUserPrincipal()).thenReturn(null);

        PressroomSession result = PressroomSession.getRollerSession(request, provider);

        // Verify new session was created
        assertNotNull(result);
        // Verify session was stored in HTTP session
        verify(session).setAttribute(eq(PressroomSession.ROLLER_SESSION), any(PressroomSession.class));
    }

    @Test
    void testGetRollerSessionExistingValidSession() {
        when(session.getAttribute(PressroomSession.ROLLER_SESSION)).thenReturn(rollerSession);
        when(request.getUserPrincipal()).thenReturn(null);

        PressroomSession result = PressroomSession.getRollerSession(request, provider);

        // Verify session was retrieved
        assertNotNull(result);
        // Verify returned session matches existing one
        assertEquals(rollerSession, result);
    }

    @Test
    void testGetRollerSessionInvalidatedSession() throws Exception {
        String username = "testuser";
        when(session.getAttribute(PressroomSession.ROLLER_SESSION)).thenReturn(rollerSession);
        when(request.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(username);
        when(userManager.getUserByUserName(username)).thenReturn(user);
        when(user.getUserName()).thenReturn(username);

        rollerSession.setAuthenticatedUser(user);
        sessionManager.invalidate(username);

        // Force creation of new session
        when(session.getAttribute(PressroomSession.ROLLER_SESSION)).thenReturn(null);

        PressroomSession result = PressroomSession.getRollerSession(request, provider);

        assertNotNull(result);
        assertNotEquals(rollerSession, result);
    }

    /** A principal on a bootstrapped tier is resolved through the PROVIDER's facade. */
    @Test
    void aPrincipalIsResolvedThroughTheProvidersUserManager() throws Exception {
        String username = "testuser";
        when(session.getAttribute(PressroomSession.ROLLER_SESSION)).thenReturn(null);
        when(request.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(username);
        when(userManager.getUserByUserName(username)).thenReturn(user);
        when(user.getUserName()).thenReturn(username);
        when(user.getEnabled()).thenReturn(Boolean.TRUE);

        PressroomSession result = PressroomSession.getRollerSession(request, provider);

        assertEquals(user, result.getAuthenticatedUser(userManager));
        verify(userManager, atLeastOnce()).getUserByUserName(username);
    }

    /**
     * Under SSO a principal can arrive before the tier is up; the lookup must
     * be skipped, and the provider's facade must not even be asked for.
     */
    @Test
    void beforeBootstrapAPrincipalIsNotResolved() {
        when(provider.isBootstrapped()).thenReturn(false);
        when(session.getAttribute(PressroomSession.ROLLER_SESSION)).thenReturn(null);
        when(request.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("testuser");

        PressroomSession result = PressroomSession.getRollerSession(request, provider);

        assertNotNull(result);
        assertNull(result.getAuthenticatedUser(userManager));
        verify(provider, never()).getWeblogger();
    }

    @Test
    void testSetAuthenticatedUser() throws Exception {
        String username = "testuser";
        when(user.getUserName()).thenReturn(username);

        rollerSession.setAuthenticatedUser(user);

        // Verify session was registered in manager
        assertNotNull(sessionManager.get(username));
        // Verify registered session matches current one
        assertEquals(rollerSession, sessionManager.get(username));
    }

    @Test
    void testGetAuthenticatedUser() throws Exception {
        String username = "testuser";
        when(user.getUserName()).thenReturn(username);
        when(userManager.getUserByUserName(username)).thenReturn(user);

        rollerSession.setAuthenticatedUser(user);
        User result = rollerSession.getAuthenticatedUser(userManager);

        // Verify authenticated user was retrieved
        assertNotNull(result);
        // Verify retrieved user matches original user
        assertEquals(user, result);
    }

    /** With no manager to resolve against (pre-bootstrap) there is no user. */
    @Test
    void getAuthenticatedUserWithNoManagerIsNull() {
        when(user.getUserName()).thenReturn("testuser");
        rollerSession.setAuthenticatedUser(user);

        assertNull(rollerSession.getAuthenticatedUser(null));
    }

    @Test
    void testConcurrentSessionHandling() throws Exception {
        String username = "testuser";
        when(user.getUserName()).thenReturn(username);

        PressroomSession session1 = new PressroomSession();
        PressroomSession session2 = new PressroomSession();

        session1.setAuthenticatedUser(user);
        session2.setAuthenticatedUser(user);

        // Verify most recent session is stored
        assertEquals(session2, sessionManager.get(username));
        // Verify old session was replaced
        assertNotEquals(session1, sessionManager.get(username));
    }

    @Test
    void testSessionTimeoutBehavior() throws Exception {
        String username = "testuser";
        when(user.getUserName()).thenReturn(username);
        when(userManager.getUserByUserName(username))
              .thenReturn(user)  // First call returns user
              .thenReturn(null); // Subsequent calls return null

        rollerSession.setAuthenticatedUser(user);
        sessionManager.invalidate(username);

        // Force UserManager to return null after invalidation
        when(userManager.getUserByUserName(username)).thenReturn(null);

        assertNull(sessionManager.get(username));
        assertNull(rollerSession.getAuthenticatedUser(userManager));
    }
}
