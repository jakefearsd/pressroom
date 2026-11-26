package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.testing.AdminLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code /v1/admin/tokens} (SA-18, session only): Admin, API tokens. Every user's tokens with owner, kind and
 * issuer, a toggle adding revoked and expired ones, and revoking any of them ({@code AdminTokensController}).
 */
class SiteTokensApiTest {

    private SessionOnlyApiSupport support;
    private ApiTokenManager tokens;
    private User pat;
    private User bot;

    @BeforeEach
    void setUp() throws Exception {
        support = new SessionOnlyApiSupport(new SiteTokensApi());
        tokens = support.tier.apiTokenManager();
        pat = account("pat", "id-pat", User.Kind.PERSON);
        bot = account("bot", "id-bot", User.Kind.AGENT);
    }

    @AfterEach
    void tearDown() {
        support.detach();
    }

    private User account(String name, String id, User.Kind kind) throws Exception {
        User user = new User();
        user.setId(id);
        user.setUserName(name);
        user.setKind(kind);
        when(support.tier.userManager().getUser(id)).thenReturn(user);
        return user;
    }

    private static ApiToken token(String id, User owner, String issuedBy) {
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setUser(owner);
        token.setLabel("label-" + id);
        token.setScopeRole(ApiToken.Role.POST);
        token.setScopeWeblog("myblog");
        token.setCreated(new Timestamp(1_000_000L));
        token.setIssuedBy(issuedBy);
        return token;
    }

    @Test
    void listShowsEveryLiveTokenWithOwnerKindAndIssuer() throws Exception {
        when(tokens.getAllTokens(false)).thenReturn(List.of(token("t1", pat, "id-pat"), token("t2", bot, "id-pat")));

        JsonNode page = SessionOnlyApiSupport.body(support.send(get("/v1/admin/tokens"), null));

        assertEquals(2, page.path("items").size());
        JsonNode first = page.path("items").path(0);
        assertEquals("t1", first.path("id").asString());
        assertEquals("pat", first.path("owner").asString());
        assertEquals("PERSON", first.path("ownerKind").asString());
        assertEquals("pat", first.path("issuedBy").asString());
        assertEquals("myblog", first.path("scopeWeblog").asString());
        assertTrue(first.path("live").asBoolean());
        assertEquals("AGENT", page.path("items").path(1).path("ownerKind").asString());
    }

    @Test
    void allAddsRevokedAndExpiredOnesAndMarksThemNotLive() throws Exception {
        ApiToken revoked = token("t3", pat, null);
        revoked.setRevokedAt(new Timestamp(2_000_000L));
        when(tokens.getAllTokens(true)).thenReturn(List.of(revoked));
        when(tokens.getAllTokens(false)).thenReturn(List.of());

        assertEquals(0, SessionOnlyApiSupport.body(support.send(get("/v1/admin/tokens"), null)).path("items").size());
        JsonNode all = SessionOnlyApiSupport.body(support.send(get("/v1/admin/tokens?all=true"), null));

        assertEquals(1, all.path("items").size());
        assertFalse(all.path("items").path(0).path("live").asBoolean());
        assertTrue(all.path("items").path(0).path("issuedBy").isMissingNode());
    }

    @Test
    void anIssuerWhoseAccountIsGoneShowsTheStoredId() throws Exception {
        when(tokens.getAllTokens(false)).thenReturn(List.of(token("t1", pat, "id-gone")));

        JsonNode page = SessionOnlyApiSupport.body(support.send(get("/v1/admin/tokens"), null));

        assertEquals("id-gone", page.path("items").path(0).path("issuedBy").asString());
    }

    @Test
    void aStoreThatCannotListAnswersAnErrorNeverAnEmptyList() throws Exception {
        when(tokens.getAllTokens(false)).thenThrow(new WebloggerException("down"));

        assertEquals(500, SessionOnlyApiSupport.status(support.send(get("/v1/admin/tokens"), null)));
    }

    @Test
    void revokeRevokesAnyUsersTokenAndLogsIt() throws Exception {
        ApiToken theirs = token("t2", bot, "id-pat");
        when(tokens.getToken("t2")).thenReturn(theirs);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            ResultActions result = support.send(delete("/v1/admin/tokens/t2"), null);

            assertEquals(204, SessionOnlyApiSupport.status(result));
            verify(tokens).revoke(theirs);
            assertEquals(1, admin.lines().size());
            assertTrue(admin.lines().get(0).startsWith("token.revoked id=t2"), admin.lines().toString());
        }
    }

    @Test
    void revokeOfAnUnknownTokenIs404AndAStoreFailureIs500() throws Exception {
        SessionOnlyApiSupport.refused(support.send(delete("/v1/admin/tokens/nope"), null), 404, "token.notFound");

        ApiToken stuck = token("t4", pat, null);
        when(tokens.getToken("t4")).thenReturn(stuck);
        org.mockito.Mockito.doThrow(new WebloggerException("down")).when(tokens).revoke(stuck);
        assertEquals(500, SessionOnlyApiSupport.status(support.send(delete("/v1/admin/tokens/t4"), null)));
        verify(tokens, never()).revoke(token("nope", pat, null));
    }
}
