package com.wikantik.pressroom.ui.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link MainMenuView}: no servlet, no model; a failed lookup shows less. */
class MainMenuViewTest {

    private final MockWeblogger mock = MockWeblogger.create();
    private final User user = user();

    @Test
    void anAdminSeesTheirBlogsAndTheSiteSpace() throws Exception {
        Weblog site = new Weblog();
        when(mock.userManager().getWeblogPermissions(user)).thenReturn(List.of(new WeblogPermission()));
        when(mock.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(true);
        when(mock.weblogManager().getOrCreateSiteSpace(user)).thenReturn(site);

        MainMenuView view = MainMenuView.of(user, mock.weblogger());

        assertEquals(1, view.weblogs().size());
        assertTrue(view.siteAdmin());
        assertSame(site, view.siteSpace());
    }

    @Test
    void aNonAdminGetsNoSiteSpaceAndItIsNeverFetched() throws Exception {
        when(mock.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(false);

        MainMenuView view = MainMenuView.of(user, mock.weblogger());

        assertFalse(view.siteAdmin());
        assertNull(view.siteSpace());
        verify(mock.weblogManager(), never()).getOrCreateSiteSpace(any());
    }

    @Test
    void everyFailedLookupShowsLess() throws Exception {
        when(mock.userManager().getWeblogPermissions(user)).thenThrow(new WebloggerException("down"));
        when(mock.userManager().checkPermission(any(GlobalPermission.class), eq(user)))
                .thenThrow(new WebloggerException("down"));

        MainMenuView view = MainMenuView.of(user, mock.weblogger());

        assertTrue(view.weblogs().isEmpty());
        assertFalse(view.siteAdmin());
        assertNull(view.siteSpace());
    }

    @Test
    void aSiteSpaceThatCannotBeLoadedLeavesOutOnlyTheSiteSection() throws Exception {
        when(mock.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(true);
        when(mock.weblogManager().getOrCreateSiteSpace(user)).thenThrow(new WebloggerException("down"));

        MainMenuView view = MainMenuView.of(user, mock.weblogger());

        assertTrue(view.siteAdmin());
        assertNull(view.siteSpace());
    }

    private static User user() {
        User u = new User();
        u.setUserName("jake");
        return u;
    }
}
