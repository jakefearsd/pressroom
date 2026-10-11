package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MetaApi}: {@code GET /v1/ping} liveness, and {@code GET /v1/me} --
 * who the caller is, whether they are a global admin, and the ceiling of the
 * API token they authenticated with (empty for Basic auth).
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 *
 * <p>Standalone MockMvc, like the other {@code *ApiTest}s: the
 * {@code authenticatedUser} request attribute stands in for what
 * {@code RollerHandlerInterceptor} sets, and the security context for what
 * the Basic/Bearer filters establish.
 */
class MetaApiTest {

    private static final String AUTHENTICATED_USER = "authenticatedUser";

    private UserManager userManager;
    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        userManager = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(userManager);

        MetaApi api = new MetaApi();
        // Field-injected (@Autowired @Lazy) in production; no constructor.
        ReflectionTestUtils.setField(api, "weblogger", weblogger);
        mockMvc = MockMvcBuilders.standaloneSetup(api)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice A.");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pingAnswersOkWithoutAnyCaller() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/v1/ping"))
                .andExpect(status().isOk()));

        assertEquals("ok", body.get("status").asString());
    }

    @Test
    void meWithoutAnAuthenticatedUserIsA403Problem() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/v1/me"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")));

        assertEquals("Not authenticated.", body.get("detail").asString());
    }

    /**
     * A Basic-authenticated caller carries no {@link ApiPrincipal}, so there
     * is no token ceiling to report: {@code tokenScope} is an empty object.
     */
    @Test
    void aBasicAuthenticatedAdminIsReportedWithNoTokenScope() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(true);

        JsonNode body = me();

        assertEquals("alice", body.get("userName").asString());
        assertEquals("Alice A.", body.get("screenName").asString());
        assertTrue(body.get("globalAdmin").asBoolean());
        assertTrue(body.get("tokenScope").isObject());
        assertTrue(body.get("tokenScope").isEmpty(), body.toString());
    }

    @Test
    void aWeblogPinnedTokenReportsItsWeblogAndRole() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", "travelblog", ApiToken.Role.POST, "token-1"));
        givenGlobalAdmin(false);

        JsonNode body = me();

        assertFalse(body.get("globalAdmin").asBoolean());
        assertEquals("travelblog", body.get("tokenScope").get("weblog").asString());
        assertEquals("POST", body.get("tokenScope").get("role").asString());
    }

    /** An unpinned token reports its weblog as the empty string, not null. */
    @Test
    void anUnpinnedTokenReportsAnEmptyWeblog() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", null, ApiToken.Role.READ, "token-1"));
        givenGlobalAdmin(false);

        JsonNode body = me();

        assertEquals("", body.get("tokenScope").get("weblog").asString());
        assertEquals("READ", body.get("tokenScope").get("role").asString());
    }

    @Test
    void noSecurityContextAtAllStillAnswersWithAnEmptyTokenScope() throws Exception {
        givenGlobalAdmin(false);

        JsonNode body = me();

        assertEquals("alice", body.get("userName").asString());
        assertTrue(body.get("tokenScope").isEmpty(), body.toString());
    }

    /**
     * A permission check that cannot be answered is a denial: the caller is
     * reported as not a global admin rather than the request failing, and
     * never as an admin.
     */
    @Test
    void aPermissionLookupFailureReportsNotGlobalAdmin() throws Exception {
        authenticateWithBasicAuth();
        when(userManager.checkPermission(any(), eq(user)))
                .thenThrow(new WebloggerException("store unreachable"));

        JsonNode body = me();

        assertFalse(body.get("globalAdmin").asBoolean());
    }

    // ---------------------------------------------------------------- helpers

    /** {@code GET /v1/me} as {@link #user}; asserts 200 and returns the body. */
    private JsonNode me() throws Exception {
        return json(mockMvc.perform(get("/v1/me").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isOk()));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Only a check for the global {@code admin} action answers {@code isAdmin}. */
    private void givenGlobalAdmin(boolean isAdmin) throws WebloggerException {
        when(userManager.checkPermission(any(), eq(user))).thenReturn(false);
        when(userManager.checkPermission(
                argThat(p -> p instanceof GlobalPermission g
                        && g.getActionsAsList().equals(List.of(GlobalPermission.ADMIN))),
                eq(user)))
                .thenReturn(isAdmin);
    }

    private static void authenticateWithBasicAuth() {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("n/a").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static void authenticateWithToken(ApiPrincipal apiPrincipal) {
        var auth = new UsernamePasswordAuthenticationToken(apiPrincipal.userName(), null, List.of());
        auth.setDetails(apiPrincipal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
