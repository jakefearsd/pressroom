package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each controller asks {@code PressroomHandlerInterceptor} to enforce before its handlers run: a signed-in
 * user, the right level on the weblog, and which routes may take the site space. A wrong answer here is an
 * authorization change that no handler test notices, so each is pinned.
 */
class InterceptorContractTest {

    @Test
    void creatingAWeblogNeedsASignedInUserWhoNamesNoWeblog() {
        WeblogCreateApi api = new WeblogCreateApi();

        assertTrue(api.isUserRequired());
        assertFalse(api.isWeblogRequired());
        assertEquals(List.of(), api.requiredWeblogPermissionActions());
        assertEquals(List.of(GlobalPermission.LOGIN), api.requiredGlobalPermissionActions());
    }

    @Test
    void theInboxNeedsPublishRightsOnTheWeblogAndALogin() {
        SubmissionsApi api = new SubmissionsApi();

        assertTrue(api.isWeblogRequired());
        assertEquals(List.of(WeblogPermission.POST), api.requiredWeblogPermissionActions());
        assertEquals(List.of(GlobalPermission.LOGIN), api.requiredGlobalPermissionActions());
    }

    @Test
    void aWeblogAdminRouteNeedsWeblogAdminAndALogin() {
        WeblogThemesApi api = new WeblogThemesApi();

        assertEquals(List.of(WeblogPermission.ADMIN), api.requiredWeblogPermissionActions());
        assertEquals(List.of(GlobalPermission.LOGIN), api.requiredGlobalPermissionActions());
    }

    /** Only the Pages and Media routes the admin's site screens use may act on the site space. */
    @Test
    void onlyThePagesAndMediaRoutesMayTakeTheSiteSpace() {
        assertTrue(new PagesApi().allowsSiteSpace());
        assertTrue(new MediaApi().allowsSiteSpace());
        assertTrue(new MediaReadApi().allowsSiteSpace());
        assertFalse(new WeblogThemesApi().allowsSiteSpace());
        assertFalse(new SubmissionsApi().allowsSiteSpace());
    }
}
