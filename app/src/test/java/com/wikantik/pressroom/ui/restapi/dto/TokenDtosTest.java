package com.wikantik.pressroom.ui.restapi.dto;

import java.sql.Timestamp;
import com.wikantik.pressroom.pojos.ApiToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a token list shows: never the secret, and an absent scope or date stays absent. */
class TokenDtosTest {

    @Test
    void anUnscopedUnusedTokenShowsOnlyWhatItHas() {
        ApiToken t = new ApiToken();
        t.setId("t1");
        t.setLabel("ci");

        var view = TokenDtos.toView(t);

        assertEquals("t1", view.id());
        assertEquals("ci", view.label());
        assertNull(view.scopeWeblog());
        assertNull(view.scopeRole());
        assertNull(view.lastUsedAt());
        assertNull(view.expiresAt());
        assertNull(view.revokedAt());
    }

    @Test
    void aScopedUsedExpiringRevokedTokenShowsAll() {
        ApiToken t = new ApiToken();
        t.setId("t2");
        t.setScopeWeblog("blog");
        t.setScopeRole(ApiToken.Role.POST);
        Timestamp used = new Timestamp(1_700_000_000_000L);
        Timestamp expires = new Timestamp(1_800_000_000_000L);
        Timestamp revoked = new Timestamp(1_750_000_000_000L);
        t.setLastUsedAt(used);
        t.setExpiresAt(expires);
        t.setRevokedAt(revoked);

        var view = TokenDtos.toView(t);

        assertEquals("blog", view.scopeWeblog());
        assertEquals("POST", view.scopeRole());
        assertEquals(used.toInstant(), view.lastUsedAt());
        assertEquals(expires.toInstant(), view.expiresAt());
        assertEquals(revoked.toInstant(), view.revokedAt());
    }
}
