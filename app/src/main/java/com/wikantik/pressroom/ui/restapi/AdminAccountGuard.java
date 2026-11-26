package com.wikantik.pressroom.ui.restapi;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.auth.Caller;

/**
 * Admin accounts and the admin role are read-only through the API -- and so
 * through MCP, which is a façade over it. Disabling, deleting or
 * re-addressing an admin account (a new email plus a password reset is a
 * takeover), and granting or revoking the role, happen only in the admin UI.
 * Every API route that changes a user must call this first: today
 * AdminApi.patchUser; later, user deletion, self-service profile edits and
 * role changes (spec 2026-10-11-mcp-endpoint-design.md, "Admin accounts").
 *
 * <p>The rule is about the caller kind: only {@link Caller.Kind#SESSION}, the
 * admin UI's own login, may do these things. A token, a password (HTTP Basic),
 * an unrecognised mechanism and an anonymous caller are all refused, so a new
 * way in is closed until it is named here. No session can reach {@code /api}
 * today, so every API caller is refused.
 */
public final class AdminAccountGuard {

    /** What an API route that would change a site administrator's account answers, in English. */
    public static final String DETAIL = ApiException.problem(ProblemCode.ACCOUNT_ADMIN_UI_ONLY).getMessage();

    /** What an API route that would change an agent account answers (spec 2026-10-13, section 5), in English. */
    public static final String AGENT_DETAIL = ApiException.problem(ProblemCode.ACCOUNT_AGENT_UI_ONLY).getMessage();

    private AdminAccountGuard() {
    }

    /** Whether {@code caller} is the admin UI's own login: the only kind these guards let through. */
    private static boolean isSession(Caller caller) {
        return caller.isSession();
    }

    public static void requireNotAdmin(Caller caller, UserManager users, User user) throws WebloggerException {
        if (isSession(caller)) {
            return;
        }
        GlobalPermission admin = new GlobalPermission(List.of(GlobalPermission.ADMIN));
        if (users.checkPermission(admin, user)) {
            throw ApiException.problem(ProblemCode.ACCOUNT_ADMIN_UI_ONLY);
        }
    }

    /**
     * Agents' identity and credentials stay with people: no API route may
     * change an agent account, and so neither may MCP, a facade over the API.
     * Checked before {@link #requireNotAdmin}, so a site-admin agent is
     * refused as an agent.
     */
    public static void requireNotAgent(Caller caller, User user) {
        if (!isSession(caller) && user.isAgent()) {
            throw ApiException.problem(ProblemCode.ACCOUNT_AGENT_UI_ONLY);
        }
    }

    /**
     * Another user's email address is the recovery channel for their account,
     * so changing it is the admin UI's alone (owner decision 2026-11-21): a
     * site-admin token that could re-address an ordinary account could
     * take it over with a password reset. Refuses, before anything is
     * applied, a non-session caller whose request carries an
     * {@code emailAddress} that differs from the stored one; resending the
     * stored address unchanged, or sending none, passes.
     *
     * @param requested the request's {@code emailAddress}, or null when absent
     */
    public static void requireEmailChangeAllowed(Caller caller, User user, String requested) {
        if (isSession(caller) || requested == null) {
            return;
        }
        if (!requested.trim().equals(user.getEmailAddress())) {
            throw ApiException.problem(ProblemCode.USER_EMAIL_UI_ONLY);
        }
    }

    /**
     * The administrator flag and the editor role are lasting access that revoking a token does not remove,
     * so changing either is the admin app's alone (SA-18; owner decision 2026-11-25 for the writer role).
     * Refuses, before anything is applied, a non-session caller whose request carries a value differing
     * from the stored one; resending the stored value, or sending none, passes (the email rule's pattern).
     *
     * @param requested the request's value, or null when absent
     * @param stored what the account holds now
     */
    public static void requireSessionToChange(Caller caller, Boolean requested, boolean stored) {
        if (isSession(caller) || requested == null || requested == stored) {
            return;
        }
        throw ApiException.problem(ProblemCode.AUTH_SESSION_ONLY);
    }

    /** A typed password is the admin app's alone: refuses a non-session caller that sent one, even an empty one. */
    public static void requireSessionToSetPassword(Caller caller, String password) {
        if (!isSession(caller) && password != null) {
            throw ApiException.problem(ProblemCode.AUTH_SESSION_ONLY);
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
    public static void requireApiManageable(Caller caller, Collection<String> settingNames) {
        if (isSession(caller)) {
            return;
        }
        for (String name : settingNames) {
            if (UI_ONLY_SETTINGS.contains(name)) {
                throw ApiException.problem(ProblemCode.SETTING_UI_ONLY, name);
            }
        }
    }
}
