package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.testing.AdminLogCapture;
import com.wikantik.pressroom.ui.controllers.AdminLog;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        return weblogger;
    }

    /**
     * No authenticated caller at all is a 401, as everywhere else in the API
     * (README, Errors): a 403 would say "you, specifically, may not".
     * Unreachable behind the security chain today; pinned so the answer
     * stays right if the chain ever lets one through.
     */
    @Test
    void noAuthenticatedCallerIsA401() throws Exception {
        String body = mockMvc(new TokensApi(mockedWeblogger()))
                .perform(get("/v1/tokens"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("Authentication required."), body);
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
        when(weblogger.getWeblogManager().getWeblogByHandle("testblog", null)).thenReturn(new Weblog());

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

    /** Final review M4: the API's revoke line carries what the UI's does, and still never a secret. */
    @Test
    void anApiRevokeIsLoggedWithTheTokensDetailsButNotItsSecret() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        ApiToken token = issued(owner, "t-7", "rlr_neverInALog").token();
        token.setTokenSha256("digest-neverInALog");
        when(weblogger.getApiTokenManager().getToken("t-7")).thenReturn(token);
        when(weblogger.getApiTokenManager().revoke(owner, "t-7")).thenReturn(true);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            mockMvc(new TokensApi(weblogger))
                    .perform(delete("/v1/tokens/{id}", "t-7"))
                    .andExpect(status().isNoContent());

            assertEquals(List.of("token.revoked " + AdminLog.tokenDetails(token) + " by=owner"), admin.lines());
            assertTrue(admin.lines().get(0).contains("label=ci owner=owner role=POST"), admin.lines().toString());
            assertFalse(admin.lines().get(0).contains("neverInALog"), admin.lines().get(0));
        }
    }

    /** A revoke of a token that is not the caller's writes no line at all. */
    @Test
    void aRefusedRevokeLogsNothing() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        basicOwner(weblogger);
        when(weblogger.getApiTokenManager().revoke(any(), anyString())).thenReturn(false);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            mockMvc(new TokensApi(weblogger))
                    .perform(delete("/v1/tokens/{id}", "someone-elses-id"))
                    .andExpect(status().isNotFound());

            assertEquals(List.of(), admin.lines());
        }
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
     * closes that: no authentication is a clean 401 (it was a 403 until the
     * fit-and-finish pass; README promises 401 for no credential), not
     * undefined manager behaviour on a null owner.
     */
    @Test
    void revokeWithNoAuthenticationAtAllIsUnauthorized() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(new TokensApi(weblogger))
                .perform(delete("/v1/tokens/{id}", "some-id"))
                .andExpect(status().isUnauthorized())
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
                .andExpect(status().isUnauthorized());

        verify(weblogger.getUserManager(), never()).getUserByUserName(anyString());
    }

    /** A user lookup that fails is "not authenticated" (401), never a token list or a 500. */
    @Test
    void aFailedUserLookupIsTreatedAsNotAuthenticated() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner"))
                .thenThrow(new com.wikantik.pressroom.WebloggerException("db down"));

        mockMvc(new TokensApi(weblogger)).perform(get("/v1/tokens"))
                .andExpect(status().isUnauthorized());

        verify(weblogger.getApiTokenManager(), never()).getTokens(any());
    }


    private static User basicOwner(Weblogger weblogger) throws Exception {
        User owner = new User();
        owner.setUserName("owner");
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(owner);
        return owner;
    }

    /** Only a check for the global admin action answers false; LOGIN stays true. */
    private static void notASiteAdmin(Weblogger weblogger, User owner) throws Exception {
        when(weblogger.getUserManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(owner)))
                .thenReturn(false);
    }

    private static ApiTokenManager.Issued issued(User owner, String id, String raw) {
        ApiToken persisted = new ApiToken();
        persisted.setId(id);
        persisted.setUser(owner);
        persisted.setLabel("ci");
        persisted.setScopeRole(ApiToken.Role.POST);
        persisted.setCreated(new Timestamp(System.currentTimeMillis()));
        return new ApiTokenManager.Issued(raw, persisted);
    }

    /** Criterion 12: a weblog the caller is not a member of is a 400 on weblog. */
    @Test
    void mintingWithAForeignWeblogIsBadRequestOnWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        notASiteAdmin(weblogger, owner);
        Weblog foreign = new Weblog();
        foreign.setHandle("foreign");
        when(weblogger.getWeblogManager().getWeblogByHandle("foreign", null)).thenReturn(foreign);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\",\"weblog\":\"foreign\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("weblog"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /** Criterion 12: an unknown weblog likewise -- even for a site admin. */
    @Test
    void mintingWithAnUnknownWeblogIsBadRequestOnWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        basicOwner(weblogger);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\",\"weblog\":\"nosuch\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("weblog"));
    }

    @Test
    void aMemberMayPinTheirOwnWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        notASiteAdmin(weblogger, owner);
        Weblog mine = new Weblog();
        mine.setHandle("mine");
        when(weblogger.getWeblogManager().getWeblogByHandle("mine", null)).thenReturn(mine);
        when(weblogger.getUserManager().getWeblogPermission(mine, owner))
                .thenReturn(new WeblogPermission(mine, owner, List.of(WeblogPermission.POST)));
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(issued(owner, "t-7", "rlr_mine"));

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"ci\",\"role\":\"POST\",\"weblog\":\"mine\"}"))
                .andExpect(status().isCreated());
    }

    /** Review focus 5: a site admin may pin any existing weblog. */
    @Test
    void aSiteAdminMayPinAnyExistingWeblog() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        when(weblogger.getWeblogManager().getWeblogByHandle("anyblog", null)).thenReturn(new Weblog());
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(issued(owner, "t-8", "rlr_any"));

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"ci\",\"role\":\"ADMIN\",\"weblog\":\"anyblog\"}"))
                .andExpect(status().isCreated());
    }

    /** Criterion 12: an expiry that is not in the future is a 400 on expiresAt. */
    @Test
    void anExpiryInThePastIsBadRequestOnExpiresAt() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        basicOwner(weblogger);

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens").contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"POST\",\"expiresAt\":\"2020-01-02T03:04:05Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("expiresAt"));

        verify(weblogger.getApiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any());
    }

    /** Criterion 12: the listing shows when a token was revoked, and nothing for a live one. */
    @Test
    void theListShowsWhenATokenWasRevoked() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        ApiToken live = issued(owner, "t-live", "rlr_a").token();
        ApiToken revoked = issued(owner, "t-revoked", "rlr_b").token();
        revoked.setRevokedAt(Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
        when(weblogger.getApiTokenManager().getTokens(owner)).thenReturn(List.of(live, revoked));

        String response = mockMvc(new TokensApi(weblogger))
                .perform(get("/v1/tokens"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode body = new tools.jackson.databind.ObjectMapper().readTree(response);
        assertFalse(body.get(0).has("revokedAt"), response);
        assertTrue(body.get(1).has("revokedAt"), response);
    }

    /**
     * Spec 2026-10-13, section 6; review focus 1: a mint is logged on
     * roller.admin, never with its secret. Only our own line is asserted
     * here: the response body IS the secret, and Spring MVC's DEBUG "Writing
     * [...]" line would carry it under a capture of every logger -- which is
     * why production keeps org.springframework at INFO. The UI mint, which
     * never writes the secret into a response body, is held to every logger
     * at every level in Task 13.
     */
    @Test
    void anApiMintIsLoggedWithoutItsSecret() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        User owner = basicOwner(weblogger);
        when(weblogger.getApiTokenManager().issueToken(any(), anyString(), any(), any(), any()))
                .thenReturn(issued(owner, "t-9", "rlr_neverInALog"));

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            mockMvc(new TokensApi(weblogger))
                    .perform(post("/v1/tokens").contentType("application/json")
                            .content("{\"label\":\"ci\",\"role\":\"POST\"}"))
                    .andExpect(status().isCreated());

            assertEquals(1, admin.lines().size(), admin.lines().toString());
            String line = admin.lines().get(0);
            for (String part : new String[] {"token.minted", "id=t-9", "label=ci", "owner=owner", "by=owner"}) {
                assertTrue(line.contains(part), part + " in " + line);
            }
            assertFalse(line.contains("rlr_neverInALog"), line);
        }
    }

    @Test
    void anUnknownRoleNamesTheRoleField() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("role"));
    }

    @Test
    void aBlankLabelNamesTheLabelField() throws Exception {
        authenticateWithBasicAuth();
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getUserManager().getUserByUserName("owner")).thenReturn(new User());

        mockMvc(new TokensApi(weblogger))
                .perform(post("/v1/tokens")
                        .contentType("application/json")
                        .content("{\"label\":\"   \",\"role\":\"POST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("label"));
    }
}
