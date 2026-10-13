package org.apache.roller.weblogger.ui.restapi.v1;

import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.Timestamp;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Token-mints-token is a privilege-escalation path: it turns any leaked token
 * into a permanent one, and into one whose scope the thief chooses. Minting is
 * therefore Basic-only, and this is the test that says so -- the security
 * chain permits Basic on this path, so nothing else would catch a regression
 * here.
 */
class TokensApiTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mockMvc(TokensApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private void authenticateWithABearerToken() {
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", null, ApiToken.Role.ADMIN, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * Basic auth's principal is a {@code UserDetails}, and carries no
     * {@code ApiPrincipal} in its details -- that absence is exactly what
     * lets {@code TokensApi} tell a Basic-authenticated caller apart from a
     * Bearer-authenticated one.
     */
    private void authenticateWithBasicAuth() {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("owner").password("n/a").authorities(List.of()).build();
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Weblogger mockedWeblogger() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        ApiTokenManager tokenManager = mock(ApiTokenManager.class);
        when(weblogger.getApiTokenManager()).thenReturn(tokenManager);
        UserManager userManager = mock(UserManager.class);
        // An author: holds the global login action. aReaderCannotMintAToken flips it.
        when(userManager.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(true);
        when(weblogger.getUserManager()).thenReturn(userManager);
        return weblogger;
    }

    @Test
    void aBearerAuthenticatedCallerCannotMintAToken() throws Exception {
        authenticateWithABearerToken();

        mockMvc(new TokensApi(mockedWeblogger()))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"escalation\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * Revoking an id the caller does not own answers 404, not 403 -- a 403
     * would confirm the id exists and let one user enumerate another's tokens.
     *
     * <p>Basic-authenticated, not Bearer: a Bearer caller is refused before
     * ownership is even checked (see {@code aBearerAuthenticatedCallerCannotRevokeATokenEither}).
     */
    @Test
    void revokingSomeoneElsesTokenIs404() throws Exception {
        authenticateWithBasicAuth();

        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());
        when(weblogger.getApiTokenManager().revoke(any(), anyString())).thenReturn(false);

        mockMvc(new TokensApi(weblogger))
                .perform(delete("/v1/tokens/{id}", "someone-elses-id"))
                .andExpect(status().isNotFound());
    }

    /**
     * A leaked, merely-POST-scoped token must not be able to reshape its
     * owner's whole token set -- enumerate every token via GET, or revoke
     * any of them (including an ADMIN one) via DELETE -- any more than it
     * can mint a new one. The whole {@code /v1/tokens} resource is
     * Basic-only, the same as issue().
     */
    @Test
    void aBearerAuthenticatedCallerCannotListTokens() throws Exception {
        authenticateWithABearerToken();
        // The Bearer caller resolves to a real, valid user -- so the only
        // way this can 403 is the explicit Basic-only guard, not an
        // incidental user-lookup miss.
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("agent")).thenReturn(new User());

        mockMvc(new TokensApi(weblogger))
                .perform(get("/v1/tokens"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void aBearerAuthenticatedCallerCannotRevokeATokenEither() throws Exception {
        authenticateWithABearerToken();
        // The Bearer caller resolves to a real user AND owns the token being
        // revoked -- so the only way this can 403 (rather than a 204
        // success) is the explicit Basic-only guard.
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("agent")).thenReturn(new User());
        when(weblogger.getApiTokenManager().revoke(any(), anyString())).thenReturn(true);

        mockMvc(new TokensApi(weblogger))
                .perform(delete("/v1/tokens/{id}", "some-id"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * The bootstrap path: how every token, including the first one, comes
     * into existence. Basic-authenticated (no ApiPrincipal), a valid role,
     * a mocked issueToken returning a known raw secret.
     *
     * <p>The assertion that matters most is the last one: the raw secret
     * must appear exactly once, in {@code token}, and never inside {@code
     * info} -- a token echoed into both would be a real leak that a looser
     * assertion (e.g. just checking {@code token} is present) would miss.
     *
     * <p>{@code issueToken} returns the persisted row directly (an {@code
     * ApiTokenManager.Issued}), not just the raw secret -- there is no
     * digest read-back here to go wrong, on purpose: that read-back used to
     * be able to miss and NPE away an already-committed, now-undiscoverable
     * credential (see {@code ApiTokenManager.Issued}'s own javadoc).
     */
    @Test
    void mintingATokenReturnsTheSecretOnceAndNeverInTheMetadataView() throws Exception {
        authenticateWithBasicAuth();

        Weblogger weblogger = mockedWeblogger();
        User owner = new User();
        owner.setId("user-1");
        owner.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);

        String rawToken = "rlr_knownRawSecretForTest";
        ApiToken persisted = new ApiToken();
        persisted.setId("token-1");
        persisted.setUser(owner);
        persisted.setLabel("seo-agent");
        persisted.setScopeWeblog("testblog");
        persisted.setScopeRole(ApiToken.Role.POST);
        persisted.setCreated(new Timestamp(System.currentTimeMillis()));
        when(weblogger.getApiTokenManager()
                .issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued(rawToken, persisted));

        String response = mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"seo-agent\",\"weblog\":\"testblog\",\"role\":\"post\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode body = new tools.jackson.databind.ObjectMapper().readTree(response);
        assertEquals(rawToken, body.get("token").asString());
        assertEquals("token-1", body.get("info").get("id").asString());
        assertEquals("seo-agent", body.get("info").get("label").asString());
        assertEquals("POST", body.get("info").get("scopeRole").asString());

        // The raw secret appears exactly once in the whole body -- nowhere
        // in the metadata view, under any field name.
        assertEquals(1, countOccurrences(response, rawToken));

        // issueToken() only begins a transaction; without this the mint
        // response above would be rolled back by the time the caller could
        // ever use it. See TokensApi's class javadoc.
        verify(weblogger).flush();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /**
     * The success path for revoke() -- Basic-authenticated, owns the token,
     * {@code ApiTokenManager.revoke} reports true. Nothing else in this
     * class ever reaches a 204, so this is also the only unit test that
     * exercises {@code weblogger.flush()} on this path: {@code revoke}, like
     * {@code issue}, only begins a JPA transaction and does not commit it on
     * its own ({@code JPAPersistenceStrategy}) -- omitting the flush left a
     * "successful" revoke rolled back by {@code PersistenceSessionFilter}'s
     * end-of-request release, so the answer was 204 while the token stayed
     * fully usable. See {@code TokensApi}'s class javadoc.
     */
    @Test
    void revokingYourOwnTokenSucceedsAndCommits() throws Exception {
        authenticateWithBasicAuth();

        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());
        when(weblogger.getApiTokenManager().revoke(any(), anyString())).thenReturn(true);

        mockMvc(new TokensApi(weblogger))
                .perform(delete("/v1/tokens/{id}", "token-1"))
                .andExpect(status().isNoContent());

        verify(weblogger).flush();
    }

    @Test
    void anUnknownRoleIsRejected() throws Exception {
        mockMvc(new TokensApi(mockedWeblogger()))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Whole-branch review, Must Fix 2: roller_api_token.label is VARCHAR(255)
     * NOT NULL (V026) and issueToken copies it straight through --
     * parseRole above already degrades a missing role to a 400; label never
     * got the equivalent, so a mint with no label used to reach the manager
     * and die on the NOT NULL constraint, an opaque 500 on the bootstrap
     * endpoint (how every token, including the very first one, comes into
     * existence).
     */
    @Test
    void mintingWithABlankLabelIsBadRequest() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"   \",\"role\":\"POST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
        verify(weblogger, never()).flush();
    }

    @Test
    void mintingWithAMissingLabelIsBadRequest() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"role\":\"POST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /** roller_api_token.label is VARCHAR(255) -- the same guard, over-length rather than blank. */
    @Test
    void mintingWithALabelLongerThanTheColumnIsBadRequest() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());
        String tooLong = "a".repeat(256);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"" + tooLong + "\",\"role\":\"POST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /** roller_api_token.scope_weblog is VARCHAR(255) too. */
    @Test
    void mintingWithAWeblogLongerThanTheColumnIsBadRequest() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());
        String tooLong = "a".repeat(256);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"ok\",\"weblog\":\"" + tooLong + "\",\"role\":\"POST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /**
     * Deferred item folded into Must Fix 2: revoke() used to read the caller
     * with {@code currentUser()}, which returns null rather than throwing
     * when there is no authentication at all -- a null then reached {@code
     * ApiTokenManager.revoke} as its owner argument. {@code requireUser()}
     * closes that: no authentication is a clean 403, not undefined manager
     * behaviour on a null owner.
     */
    @Test
    void revokeWithNoAuthenticationAtAllIsForbidden() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(new TokensApi(weblogger))
                .perform(delete("/v1/tokens/{id}", "some-id"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getApiTokenManager(), never()).revoke(any(), anyString());
        verify(weblogger, never()).flush();
    }

    /**
     * A reader (SSO sign-in, role "reader") holds no global login action and
     * so cannot use the API at all: not mint, list or revoke (criterion 24).
     */
    @Test
    void aReaderCannotMintAToken() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User reader = new User();
        reader.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(reader);
        when(weblogger.getUserManager().checkPermission(any(GlobalPermission.class), any(User.class)))
                .thenReturn(false);

        String body = mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("This account cannot use the API."), body);

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    @Test
    void aBasicCallerListsOnlyTheMetadataOfTheirOwnTokens() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = new User();
        owner.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);
        ApiToken token = new ApiToken();
        token.setId("t-1");
        token.setUser(owner);
        token.setLabel("ci");
        token.setScopeRole(ApiToken.Role.POST);
        token.setCreated(new Timestamp(System.currentTimeMillis()));
        when(weblogger.getApiTokenManager().getTokens(owner)).thenReturn(List.of(token));

        String response = mockMvc(new TokensApi(weblogger))
                .perform(get("/v1/tokens"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode body = new tools.jackson.databind.ObjectMapper().readTree(response);
        assertEquals(1, body.size());
        assertEquals("t-1", body.get(0).get("id").asString());
        assertEquals("ci", body.get(0).get("label").asString());
    }

    @Test
    void anExpiryInTheRequestIsHandedToTheManager() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = new User();
        owner.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);
        ApiToken persisted = new ApiToken();
        persisted.setId("t-2");
        persisted.setUser(owner);
        persisted.setLabel("temp");
        persisted.setScopeRole(ApiToken.Role.POST);
        persisted.setCreated(new Timestamp(System.currentTimeMillis()));
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued("rlr_x", persisted));

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"temp\",\"role\":\"post\",\"expiresAt\":\"2031-01-02T03:04:05Z\"}"))
                .andExpect(status().isCreated());

        verify(weblogger.getApiTokenManager()).issueToken(any(), org.mockito.ArgumentMatchers.eq("temp"), any(),
                org.mockito.ArgumentMatchers.eq(ApiToken.Role.POST),
                org.mockito.ArgumentMatchers.eq(Timestamp.from(java.time.Instant.parse("2031-01-02T03:04:05Z"))));
    }

    @Test
    void aPlainStringPrincipalIsLookedUpByName() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner", null, List.of()));
        Weblogger weblogger = mockedWeblogger();
        User owner = new User();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);
        when(weblogger.getApiTokenManager().getTokens(owner)).thenReturn(List.of());

        mockMvc(new TokensApi(weblogger)).perform(get("/v1/tokens"))
                .andExpect(status().isOk())
                .andExpect(r -> assertEquals("[]", r.getResponse().getContentAsString()));
    }

    @Test
    void theAnonymousPrincipalIsNotAUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));
        Weblogger weblogger = mockedWeblogger();

        mockMvc(new TokensApi(weblogger)).perform(get("/v1/tokens"))
                .andExpect(status().isForbidden());

        verify(weblogger.getUserManager(), never()).getUserByUserName(anyString());
    }

    /** A user lookup that fails is "not authenticated" (403), never a token list or a 500. */
    @Test
    void aFailedUserLookupIsTreatedAsNotAuthenticated() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner"))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("db down"));

        mockMvc(new TokensApi(weblogger)).perform(get("/v1/tokens"))
                .andExpect(status().isForbidden());

        verify(weblogger.getApiTokenManager(), never()).getTokens(any());
    }
}
