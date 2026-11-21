package com.wikantik.pressroom.ui.restapi;

import java.util.List;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.auth.Caller;
import com.wikantik.pressroom.ui.restapi.auth.Caller.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guards refuse by caller kind: SESSION (the admin UI's own login) may
 * pass; every other kind, including the unrecognised and the anonymous, is
 * refused with the same detail the API has always given.
 */
class AdminAccountGuardTest {

    private static Caller caller(Kind kind) {
        return new Caller(kind, null);
    }

    private static UserManager managerWhereEveryoneIsAdmin() throws Exception {
        UserManager users = mock(UserManager.class);
        when(users.checkPermission(argThat(p -> p instanceof GlobalPermission
                && ((GlobalPermission) p).getActionsAsList().contains(GlobalPermission.ADMIN)), any())).thenReturn(true);
        return users;
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = "SESSION", mode = EnumSource.Mode.EXCLUDE)
    void anAdminAccountIsRefusedWith403ForEveryCallerButASession(Kind kind) throws Exception {
        UserManager users = managerWhereEveryoneIsAdmin();

        ApiException ex = assertThrows(ApiException.class,
                () -> AdminAccountGuard.requireNotAdmin(caller(kind), users, new User()));

        assertEquals(403, ex.getStatus());
        assertEquals("Admin accounts are managed in the admin UI.", ex.getMessage());
        assertEquals("Admin accounts are managed in the admin UI.", AdminAccountGuard.DETAIL);
    }

    @Test
    void aSessionMayChangeAnAdminAccount() throws Exception {
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAdmin(
                caller(Kind.SESSION), managerWhereEveryoneIsAdmin(), new User()));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aNonAdminAccountPassesForEveryCaller(Kind kind) throws Exception {
        UserManager users = mock(UserManager.class);
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAdmin(caller(kind), users, new User()));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = "SESSION", mode = EnumSource.Mode.EXCLUDE)
    void anAgentAccountIsRefusedWith403ForEveryCallerButASession(Kind kind) {
        User agent = new User();
        agent.setKind(User.Kind.AGENT);

        ApiException ex = assertThrows(ApiException.class,
                () -> AdminAccountGuard.requireNotAgent(caller(kind), agent));

        assertEquals(403, ex.getStatus());
        assertEquals("Agent accounts are managed in the admin UI.", ex.getMessage());
    }

    @Test
    void aSessionMayChangeAnAgentAccount() {
        User agent = new User();
        agent.setKind(User.Kind.AGENT);
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAgent(caller(Kind.SESSION), agent));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aPersonPassesTheAgentGuardForEveryCaller(Kind kind) {
        assertDoesNotThrow(() -> AdminAccountGuard.requireNotAgent(caller(kind), new User()));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = "SESSION", mode = EnumSource.Mode.EXCLUDE)
    void aUiOnlySettingIsRefusedWith403ForEveryCallerButASession(Kind kind) {
        ApiException ex = assertThrows(ApiException.class,
                () -> AdminAccountGuard.requireApiManageable(caller(kind), List.of("site.name", "site.absoluteurl")));

        assertEquals(403, ex.getStatus());
        assertEquals("site.absoluteurl is managed in the admin UI.", ex.getMessage());
    }

    @Test
    void aSessionMayWriteAUiOnlySetting() {
        assertDoesNotThrow(() -> AdminAccountGuard.requireApiManageable(
                caller(Kind.SESSION), List.of("site.absoluteurl")));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anOrdinarySettingPassesForEveryCaller(Kind kind) {
        assertDoesNotThrow(() -> AdminAccountGuard.requireApiManageable(caller(kind), List.of("site.name")));
    }
}
