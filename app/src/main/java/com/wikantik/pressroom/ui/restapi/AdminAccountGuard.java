package com.wikantik.pressroom.ui.restapi;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;

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

    /** What an API route that would change an agent account answers (spec 2026-10-13, section 5). */
    public static final String AGENT_DETAIL = "Agent accounts are managed in the admin UI.";

    private AdminAccountGuard() {
    }

    public static void requireNotAdmin(UserManager users, User user) throws WebloggerException {
        GlobalPermission admin = new GlobalPermission(List.of(GlobalPermission.ADMIN));
        if (users.checkPermission(admin, user)) {
            throw ApiException.forbidden(DETAIL);
        }
    }

    /**
     * Agents' identity and credentials stay with people: no API route may
     * change an agent account, and so neither may MCP, a facade over the API.
     * Checked before {@link #requireNotAdmin}, so a site-admin agent is
     * refused as an agent.
     */
    public static void requireNotAgent(User user) {
        if (user.isAgent()) {
            throw ApiException.forbidden(AGENT_DETAIL);
        }
    }

    /**
     * Runtime settings the API reads but never writes. site.absoluteurl is
     * the base of every password-reset and set-password link the mailer
     * builds: a stolen or prompt-injected admin token that could point it at
     * another host would be handed the reset token of an admin account, the
     * takeover the rule above exists to stop. Global Config in the admin UI
     * is unchanged.
     */
    public static final Set<String> UI_ONLY_SETTINGS = Set.of("site.absoluteurl");

    /** Refuses, before anything is applied, a request naming a UI-only setting. */
    public static void requireApiManageable(Collection<String> settingNames) {
        for (String name : settingNames) {
            if (UI_ONLY_SETTINGS.contains(name)) {
                throw ApiException.forbidden(name + " is managed in the admin UI.");
            }
        }
    }
}
