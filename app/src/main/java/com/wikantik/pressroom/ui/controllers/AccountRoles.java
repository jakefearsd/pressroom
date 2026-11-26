package com.wikantik.pressroom.ui.controllers;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;

/**
 * Granting and revoking an account's global roles, for the person screen and
 * {@code PATCH /api/v1/admin/users}: one rule for "make holding the role match
 * what was asked".
 */
public final class AccountRoles {

    /** The role that lets an account write posts; the only one a single-sign-on account's page edits. */
    public static final String EDITOR = "editor";

    private AccountRoles() {
    }

    /**
     * Grants or revokes {@code role} so that holding it matches {@code wanted}; nothing happens when it
     * already does.
     *
     * @return the admin-log event for a change to the admin role, else null
     */
    public static String sync(UserManager mgr, User user, String role, boolean wanted, boolean held)
            throws WebloggerException {
        if (wanted == held) {
            return null;
        }
        if (wanted) {
            mgr.grantRole(role, user);
        } else {
            mgr.revokeRole(role, user);
        }
        if (!"admin".equals(role)) {
            return null;
        }
        return wanted ? "siteAdmin.granted" : "siteAdmin.removed";
    }
}
