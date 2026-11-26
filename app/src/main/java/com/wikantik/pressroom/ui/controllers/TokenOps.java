package com.wikantik.pressroom.ui.controllers;

import java.sql.Timestamp;
import java.util.Locale;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;

/**
 * The audited writes behind minting and revoking an API token for someone, in one place for the surfaces that
 * do it as a site admin: an agent's page and Admin, API tokens ({@link TokenFormController}) and the
 * session-only {@code /v1/admin/agents} and {@code /v1/admin/tokens} routes (SA-18). Each judges its own form
 * or body first; this is what happens once the request is accepted, so the {@code pressroom.admin} lines and
 * the "no such token" rule cannot drift between them. The secret is never logged: the mint's line names the
 * token by {@link AdminLog#tokenDetails}.
 */
public final class TokenOps {

    /** What {@link #revoke} did. */
    public enum RevokeOutcome {
        /** The token is revoked (now, or already was). */
        REVOKED,
        /** No such id, or a token outside the required owner: "no such token". */
        NOT_FOUND
    }

    private TokenOps() {
    }

    /** The role named exactly as the enum spells it (upper case), or null: the token form's own match. */
    public static ApiToken.Role roleOf(String role) {
        for (ApiToken.Role candidate : ApiToken.Role.values()) {
            if (candidate.name().equals(role)) {
                return candidate;
            }
        }
        return null;
    }

    /** {@link #roleOf} after upper-casing, the way the API accepts a role. */
    public static ApiToken.Role roleIgnoringCase(String role) {
        return role == null ? null : roleOf(role.toUpperCase(Locale.ROOT));
    }

    /**
     * Mints a token for {@code owner}, recording {@code actor} as its issuer, flushing and writing the
     * {@code token.minted} line. The caller has judged the label, role, pin and expiry.
     */
    public static ApiTokenManager.Issued mint(Weblogger weblogger, User owner, User actor, String label,
            String pin, ApiToken.Role role, Timestamp expiresAt) throws WebloggerException {
        ApiTokenManager.Issued issued = weblogger.getApiTokenManager()
                .issueToken(owner, label, pin, role, expiresAt, actor);
        weblogger.flush();
        AdminLog.record("token.minted", actor, AdminLog.tokenDetails(issued.token()));
        return issued;
    }

    /**
     * Revokes a token, writing the {@code token.revoked} line the first time. Revoking an already-revoked
     * token succeeds and changes nothing. A store failure propagates: revoke is the safety action, so an
     * admin is never told a token that may still be live does not exist.
     *
     * @param requiredOwner the account the token must belong to, or null for a site admin, who may revoke any
     */
    public static RevokeOutcome revoke(Weblogger weblogger, User actor, String tokenId, User requiredOwner)
            throws WebloggerException {
        ApiTokenManager tokens = weblogger.getApiTokenManager();
        ApiToken token = tokenId == null ? null : tokens.getToken(tokenId);
        if (token == null
                || (requiredOwner != null && !token.getUser().getId().equals(requiredOwner.getId()))) {
            return RevokeOutcome.NOT_FOUND;
        }
        boolean wasLive = token.getRevokedAt() == null;
        tokens.revoke(token);
        weblogger.flush();
        if (wasLive) {
            AdminLog.record("token.revoked", actor, AdminLog.tokenDetails(token));
        }
        return RevokeOutcome.REVOKED;
    }
}
