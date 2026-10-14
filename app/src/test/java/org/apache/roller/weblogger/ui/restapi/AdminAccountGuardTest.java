package org.apache.roller.weblogger.ui.restapi;

import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAccountGuardTest {

    @Test
    void anAdminAccountIsRefusedWith403() throws Exception {
        UserManager users = mock(UserManager.class);
        User admin = new User();
        when(users.checkPermission(argThat(p -> p instanceof GlobalPermission
                && ((GlobalPermission) p).getActionsAsList().contains(GlobalPermission.ADMIN)), any())).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> AdminAccountGuard.requireNotAdmin(users, admin));
        assertEquals(403, ex.getStatus());
        assertEquals("Admin accounts are managed in the admin UI.", AdminAccountGuard.DETAIL);
    }

    @Test
    void aNonAdminAccountPasses() throws Exception {
        UserManager users = mock(UserManager.class);
        when(users.checkPermission(any(), any())).thenReturn(false);
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAdmin(users, new User()));
    }

    /** Spec 2026-10-13, section 5: agent accounts are managed in the admin UI only. */
    @Test
    void anAgentAccountIsRefusedWith403() {
        User agent = new User();
        agent.setKind(User.Kind.AGENT);

        ApiException ex = assertThrows(ApiException.class, () -> AdminAccountGuard.requireNotAgent(agent));

        assertEquals(403, ex.getStatus());
        assertEquals("Agent accounts are managed in the admin UI.", ex.getMessage());
    }

    @Test
    void aPersonPassesTheAgentGuard() {
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAgent(new User()));
    }
}
