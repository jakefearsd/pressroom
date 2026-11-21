package com.wikantik.pressroom.ui.view;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AccountList}: today's normalisation, without a servlet or a model. */
class AccountListTest {

    private final MockWeblogger mock = MockWeblogger.create();

    @Test
    void kindAndTextAreNormalisedAndPassedToTheStore() throws Exception {
        AccountList list = AccountList.of(mock.weblogger(), "agents", "  bo ");

        assertEquals("agents", list.kindFilter());
        assertEquals("bo", list.textFilter());
        verify(mock.userManager()).getAccounts(User.Kind.AGENT, "bo");

        assertEquals("people", AccountList.of(mock.weblogger(), "people", null).kindFilter());
        verify(mock.userManager()).getAccounts(User.Kind.PERSON, null);

        AccountList all = AccountList.of(mock.weblogger(), "nonsense", "");
        assertEquals("", all.kindFilter());
        assertEquals("", all.textFilter());
        verify(mock.userManager()).getAccounts(null, null);
        assertEquals("", AccountList.of(mock.weblogger(), null, null).kindFilter());
    }

    @Test
    void rowsCarryRoleWeblogCountAndLastActive() throws Exception {
        User person = account("jake", User.Kind.PERSON);
        person.setLastLogin(new Date(1_000_000L));
        User agent = account("bot", User.Kind.AGENT);
        User reader = account("reader", User.Kind.PERSON);
        when(mock.userManager().getAccounts(null, null)).thenReturn(List.of(person, agent, reader));
        when(mock.userManager().getRoles(person)).thenReturn(List.of("editor", "admin"));
        when(mock.userManager().getRoles(agent)).thenReturn(List.of("editor"));
        when(mock.userManager().getRoles(reader)).thenReturn(List.of());
        when(mock.userManager().getWeblogPermissions(person)).thenReturn(List.of(new WeblogPermission()));
        ApiToken old = new ApiToken();
        old.setLastUsedAt(new Timestamp(1_000L));
        ApiToken newest = new ApiToken();
        newest.setLastUsedAt(new Timestamp(5_000L));
        when(mock.apiTokenManager().getTokens(agent)).thenReturn(List.of(old, newest, new ApiToken()));

        List<AccountRow> rows = AccountList.of(mock.weblogger(), null, null).accounts();

        assertEquals(AccountRow.Role.SITE_ADMIN, rows.get(0).getRole());
        assertEquals(1, rows.get(0).getWeblogCount());
        assertEquals(1_000_000L, rows.get(0).getLastActive().getTime());
        assertEquals(AccountRow.Role.AUTHOR, rows.get(1).getRole());
        assertEquals(5_000L, rows.get(1).getLastActive().getTime());
        assertEquals(AccountRow.Role.READER, rows.get(2).getRole());
        assertNull(rows.get(2).getLastActive());
    }

    @Test
    void aStoreFailureIsAnEmptyList() throws Exception {
        when(mock.userManager().getAccounts(null, null)).thenThrow(new WebloggerException("down"));

        assertTrue(AccountList.of(mock.weblogger(), null, null).accounts().isEmpty());
    }

    private static User account(String name, User.Kind kind) {
        User user = new User();
        user.setUserName(name);
        user.setKind(kind);
        return user;
    }
}
