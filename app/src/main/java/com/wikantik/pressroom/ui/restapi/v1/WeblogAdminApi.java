package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;

/**
 * Base of the controllers that mirror an admin page with {@code BaseController}'s defaults: the weblog's
 * {@code ADMIN} permission on the {@code {handle}} weblog, with the global {@code login} action, so the
 * blog's owner reaches it and a site admin reaches every blog through the implied permission.
 * {@code PressroomHandlerInterceptor} enforces it (404 for a caller who may not see the weblog, 403 for a
 * member below ADMIN) before any handler runs.
 */
abstract class WeblogAdminApi extends BaseApiController implements UISecurityEnforced {

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.LOGIN);
    }
}
