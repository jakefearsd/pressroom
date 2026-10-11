package org.apache.roller.weblogger.ui.restapi;

import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;

/**
 * Admin accounts and the admin role are read-only through the API -- and so
 * through MCP, which is a façade over it. Disabling, deleting or
 * re-addressing an admin account (a new email plus a password reset is a
 * takeover), and granting or revoking the role, happen only in the admin UI.
 * Every API route that changes a user must call this first: today
 * AdminApi.patchUser; later, user deletion, self-service profile edits and
 * role changes (spec 2026-10-11-mcp-endpoint-design.md, "Admin accounts").
 */
public final class AdminAccountGuard {

    public static final String DETAIL = "Admin accounts are managed in the admin UI.";

    private AdminAccountGuard() {
    }

    public static void requireNotAdmin(UserManager users, User user) throws WebloggerException {
        GlobalPermission admin = new GlobalPermission(List.of(GlobalPermission.ADMIN));
        if (users.checkPermission(admin, user)) {
            throw ApiException.forbidden(DETAIL);
        }
    }
}
