package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The session-only parts of {@code /v1/admin/users} (SA-18, spec 2026-11-25 react admin migration, Wave 4):
 * the administrator flag, a typed password, the {@code writer} role grant (owner decision 2026-11-25), the
 * self-lock-out refusals of {@code UserEditController.refuseSelfLockout}, and the comment-cache expiry of a
 * rename. A token meets 403 {@code auth.sessionOnly} before anything is applied; resending a stored value
 * passes (the email rule's pattern). The routes' callers are swept by the matrix; these run the handlers.
 */
class AdminUsersSessionOnlyApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PasswordEncoder previousEncoder;
    private UserManager users;
    private Weblogger weblogger;
    private CommentService comments;
    private MockMvc mvc;
    private User actor;

    @BeforeEach
    void setUp() {
        previousEncoder = PressroomContext.getPasswordEncoder();
        PressroomContext.setPasswordEncoder(new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder())));
        weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        comments = mock(CommentService.class);
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        controller.commentService = comments;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        actor = person("boss");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        PressroomContext.setPasswordEncoder(previousEncoder);
    }

    private static void signIn(Object details) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("boss", "x",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void asSession() {
        signIn(new WebAuthenticationDetails("127.0.0.1", null));
    }

    private static void asToken() {
        signIn(new ApiPrincipal("boss", null, ApiToken.Role.ADMIN, "token-1"));
    }

    private static User person(String userName) {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName("Screen " + userName);
        user.setFullName("Full " + userName);
        user.setEmailAddress(userName + "@example.test");
        user.setLocale("en_US");
        user.setTimeZone("America/New_York");
        user.setEnabled(Boolean.TRUE);
        user.setKind(User.Kind.PERSON);
        return user;
    }

    private User stored(User user) throws Exception {
        when(users.getUserByUserName(user.getUserName(), null)).thenReturn(user);
        return user;
    }

    private void holdsAdmin(User user, boolean admin) throws Exception {
        when(users.checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(admin);
        when(users.getRoles(user)).thenReturn(admin ? List.of("admin") : List.of());
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.requestAttr("authenticatedUser", actor).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder);
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static void refused(ResultActions result, int status, String code) throws Exception {
        assertEquals(status, result.andReturn().getResponse().getStatus(),
                result.andReturn().getResponse().getContentAsString());
        JsonNode problem = body(result);
        String got = problem.path("code").asString("");
        if (got.equals("request.invalidFields")) {
            got = problem.path("errors").path(0).path("code").asString("");
        }
        assertEquals(code, got);
    }

    private static int status(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    // --- the administrator flag

    @Test
    void aTokenCannotChangeTheAdministratorFlag() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asToken();

        refused(send(patch("/v1/admin/users/carol"), "{\"administrator\":true}"), 403, "auth.sessionOnly");

        verify(users, never()).grantRole(any(), any());
        verify(users, never()).saveUser(any());
    }

    @Test
    void aTokenResendingTheStoredAdministratorFlagPasses() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asToken();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"), "{\"administrator\":false,\"fullName\":\"New Name\"}")));
        verify(users, never()).grantRole(any(), any());
        verify(users, never()).revokeRole(any(), any());
    }

    @Test
    void aSessionGrantsTheAdministratorRoleAndItIsReportedAmongTheRoles() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        when(users.getRoles(carol)).thenReturn(List.of("admin"));
        asSession();

        ResultActions result = send(patch("/v1/admin/users/carol"), "{\"administrator\":true}");

        assertEquals(200, status(result));
        verify(users).grantRole("admin", carol);
        verify(weblogger).flush();
        assertEquals("admin", body(result).path("globalRoles").path(0).asString());
    }

    @Test
    void aSessionRevokesTheAdministratorRoleFromAnotherAdmin() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, true);
        asSession();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"), "{\"administrator\":false}")));
        verify(users).revokeRole("admin", carol);
    }

    @Test
    void anAdminCannotUntickTheirOwnAdministratorRole() throws Exception {
        stored(actor);
        holdsAdmin(actor, true);
        asSession();

        refused(send(patch("/v1/admin/users/boss"), "{\"administrator\":false,\"fullName\":\"Other\"}"), 409,
                "user.cantChangeOwnRole");

        verify(users, never()).revokeRole(any(), any());
        verify(users, never()).saveUser(any());
        assertEquals("Full boss", actor.getFullName());
    }

    @Test
    void anAdminCannotDisableTheirOwnAccount() throws Exception {
        stored(actor);
        holdsAdmin(actor, true);
        asSession();

        refused(send(patch("/v1/admin/users/boss"), "{\"enabled\":false}"), 409, "user.cantDisableSelf");

        verify(users, never()).saveUser(any());
        assertTrue(actor.getEnabled());
    }

    @Test
    void aLockOutRoleLookupThatFailsRefusesTheChange() throws Exception {
        stored(actor);
        when(users.checkPermission(any(GlobalPermission.class), eq(actor)))
                .thenThrow(new com.wikantik.pressroom.WebloggerException("store down"));
        asSession();

        assertEquals(500, status(send(patch("/v1/admin/users/boss"), "{\"administrator\":false}")));
        verify(users, never()).saveUser(any());
    }

    @Test
    void aSingleSignOnAccountNeverHoldsAdmin() throws Exception {
        User carol = stored(person("carol"));
        carol.setSsoIssuer("https://accounts.google.com");
        carol.setSsoSubject("sub-1");
        holdsAdmin(carol, false);
        asSession();

        ResultActions result = send(patch("/v1/admin/users/carol"), "{\"administrator\":true}");

        refused(result, 400, "user.ssoAdmin");
        assertEquals("administrator", body(result).path("errors").path(0).path("field").asString());
        verify(users, never()).grantRole(any(), any());
    }

    // --- the writer role (owner decision 2026-11-25)

    private User ssoCarol(boolean editor) throws Exception {
        User carol = stored(person("carol"));
        carol.setSsoIssuer("https://accounts.google.com");
        carol.setSsoSubject("sub-1");
        holdsAdmin(carol, false);
        when(users.hasRole("editor", carol)).thenReturn(editor);
        return carol;
    }

    @Test
    void aTokenCannotChangeTheWriterRole() throws Exception {
        ssoCarol(false);
        asToken();

        refused(send(patch("/v1/admin/users/carol"), "{\"writer\":true}"), 403, "auth.sessionOnly");

        verify(users, never()).grantRole(any(), any());
        verify(users, never()).saveUser(any());
    }

    @Test
    void aTokenResendingTheStoredWriterValuePasses() throws Exception {
        ssoCarol(true);
        asToken();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"), "{\"writer\":true,\"fullName\":\"New Name\"}")));
        verify(users, never()).revokeRole(any(), any());
    }

    @Test
    void aSessionGrantsTheWriterRole() throws Exception {
        User carol = ssoCarol(false);
        asSession();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"), "{\"writer\":true}")));
        verify(users).grantRole("editor", carol);
    }

    @Test
    void aTokenSendingWriterToANonSsoAccountStillMeetsTheSessionRuleFirst() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        when(users.hasRole("editor", carol)).thenReturn(false);
        asToken();

        refused(send(patch("/v1/admin/users/carol"), "{\"writer\":true}"), 403, "auth.sessionOnly");
    }

    // --- typed password

    @Test
    void aTokenCannotSetAPassword() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asToken();

        refused(send(patch("/v1/admin/users/carol"), "{\"password\":\"long enough password\"}"), 403,
                "auth.sessionOnly");

        verify(users, never()).saveUser(any());
        assertEquals(null, carol.getPassword());
    }

    @Test
    void aSessionSetsAnotherUsersPasswordAndEndsTheirSessions() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asSession();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"), "{\"password\":\"long enough password\"}")));

        assertNotNull(carol.getPassword());
        verify(users).saveUser(carol);
    }

    @Test
    void aTooShortPasswordIsRefusedOnItsFieldAndNothingIsApplied() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asSession();

        ResultActions result = send(patch("/v1/admin/users/carol"), "{\"password\":\"short\",\"fullName\":\"Other\"}");

        refused(result, 400, "password.tooShort");
        assertEquals("password", body(result).path("errors").path(0).path("field").asString());
        verify(users, never()).saveUser(any());
        verify(weblogger, never()).flush();
        assertEquals(null, carol.getPassword());
    }

    @Test
    void aPasswordCannotBeSetOnASingleSignOnAccountOrAnAgent() throws Exception {
        User carol = stored(person("carol"));
        carol.setSsoIssuer("https://accounts.google.com");
        carol.setSsoSubject("sub-1");
        holdsAdmin(carol, false);
        asSession();
        refused(send(patch("/v1/admin/users/carol"), "{\"password\":\"long enough password\"}"), 400, "user.ssoBound");

        User bot = stored(person("bot"));
        bot.setKind(User.Kind.AGENT);
        holdsAdmin(bot, false);
        refused(send(patch("/v1/admin/users/bot"), "{\"password\":\"long enough password\"}"), 400, "user.notAPerson");
        verify(users, never()).saveUser(any());
    }

    @Test
    void anAdminCannotSetTheirOwnPasswordHereBecauseTheProfileRouteAsksForTheCurrentOne() throws Exception {
        stored(actor);
        holdsAdmin(actor, true);
        asSession();

        refused(send(patch("/v1/admin/users/boss"), "{\"password\":\"long enough password\",\"fullName\":\"X\"}"),
                409, "user.ownPasswordUseProfile");

        assertEquals(null, actor.getPassword());
        verify(users, never()).saveUser(any());
        verify(weblogger, never()).flush();
    }

    // --- create

    @Test
    void aTokenCannotCreateAnAccountWithAPasswordOrTheAdministratorFlag() throws Exception {
        asToken();
        String base = "\"userName\":\"dave\",\"emailAddress\":\"dave@example.com\"";

        refused(send(post("/v1/admin/users"), "{" + base + ",\"password\":\"long enough password\"}"), 403,
                "auth.sessionOnly");
        refused(send(post("/v1/admin/users"), "{" + base + ",\"administrator\":true}"), 403, "auth.sessionOnly");

        verify(users, never()).addUser(any());
    }

    @Test
    void aSessionCreatesAnEnabledAccountWithATypedPasswordAndNoMailAtAll() throws Exception {
        asSession();
        when(users.getUserByUserName(any(), eq(null))).thenReturn(null);
        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            ResultActions result = send(post("/v1/admin/users"),
                    "{\"userName\":\"dave\",\"emailAddress\":\"dave@example.com\","
                            + "\"password\":\"long enough password\",\"administrator\":true}");

            assertEquals(201, status(result));
            org.mockito.ArgumentCaptor<User> added = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(users).addUser(added.capture());
            assertTrue(added.getValue().getEnabled());
            assertNotNull(added.getValue().getPassword());
            verify(users).grantRole("admin", added.getValue());
            mailer.verifyNoInteractions();
        }
    }

    @Test
    void aTooShortPasswordOnCreateIsRefusedBeforeTheAccountExists() throws Exception {
        asSession();
        ResultActions result = send(post("/v1/admin/users"),
                "{\"userName\":\"dave\",\"emailAddress\":\"dave@example.com\",\"password\":\"short\"}");

        refused(result, 400, "password.tooShort");
        verify(users, never()).addUser(any());
    }

    // --- rename expires the comment pages

    @Test
    void aChangedScreenNameExpiresTheWeblogsShowingTheAccountsComments() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asToken();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"), "{\"screenName\":\"Carol C\"}")));

        verify(comments).expireWhereShown(carol);
    }

    @Test
    void anUnchangedScreenNameExpiresNothing() throws Exception {
        User carol = stored(person("carol"));
        holdsAdmin(carol, false);
        asToken();

        assertEquals(200, status(send(patch("/v1/admin/users/carol"),
                "{\"screenName\":\"Screen carol\",\"fullName\":\"Carol\"}")));

        verify(comments, never()).expireWhereShown(any());
    }
}
