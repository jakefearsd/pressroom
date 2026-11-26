package com.wikantik.pressroom.ui.restapi.v1;

import jakarta.mail.MessagingException;
import java.util.List;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.UserTokenManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.UserToken;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Users screen's token-safe gaps (spec 2026-11-25 react admin migration, Wave 4): the list's {@code q} and
 * {@code kind} filters, the profile fields on the user body, the set-password link and a user's weblogs. The
 * session-only parts of the screen (admin flag, memberships, another user's email) are not here and stay
 * refused to tokens.
 */
class AdminUsersAccountFieldsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Object previousPasswordEncoder;

    @BeforeEach
    void installPasswordEncoder() {
        previousPasswordEncoder = PressroomContext.getPasswordEncoder();
        PressroomContext.setPasswordEncoder(new DelegatingPasswordEncoder("bcrypt",
                java.util.Map.of("bcrypt", new BCryptPasswordEncoder())));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        PressroomContext.setPasswordEncoder((PasswordEncoder) previousPasswordEncoder);
    }

    private static void asSession() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("boss", "x",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        auth.setDetails(new WebAuthenticationDetails("127.0.0.1", null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Weblogger weblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(mock(UserManager.class));
        when(weblogger.getApiTokenManager()).thenReturn(mock(com.wikantik.pressroom.business.ApiTokenManager.class));
        return weblogger;
    }

    private static MockMvc mvc(Weblogger weblogger) {
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        controller.commentService = mock(com.wikantik.pressroom.business.comments.CommentService.class);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
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

    private static User agent(String userName) {
        User user = person(userName);
        user.setKind(User.Kind.AGENT);
        return user;
    }

    private static JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    private static List<String> errorFields(String problem) throws Exception {
        List<String> fields = new java.util.ArrayList<>();
        for (JsonNode error : json(problem).get("errors")) {
            fields.add(error.get("field").asString());
        }
        return fields;
    }

    // ------------------------------------------------------------------ list filters

    @Test
    void qAndKindAskTheAccountListTheScreenUses() throws Exception {
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        when(users.getAccounts(User.Kind.AGENT, "bot")).thenReturn(List.of(agent("botty"), agent("robot")));

        JsonNode page = json(mvc(weblogger).perform(get("/v1/admin/users").param("kind", "agents").param("q", " bot "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals(2, page.get("items").size());
        assertEquals("agent", page.get("items").get(0).get("kind").asString().toLowerCase());
        assertFalse(page.get("hasMore").asBoolean());
        verify(users, never()).getUsers(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void peopleAndAllMapToTheScreensKinds() throws Exception {
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        when(users.getAccounts(User.Kind.PERSON, null)).thenReturn(List.of(person("alice")));
        when(users.getAccounts(null, "al")).thenReturn(List.of(person("alice"), agent("alba")));

        assertEquals(1, json(mvc(weblogger).perform(get("/v1/admin/users").param("kind", "people"))
                .andReturn().getResponse().getContentAsString()).get("items").size());
        assertEquals(2, json(mvc(weblogger).perform(get("/v1/admin/users").param("kind", "all").param("q", "al"))
                .andReturn().getResponse().getContentAsString()).get("items").size());
    }

    @Test
    void aFilteredListIsPagedAndFilteredByEnabledInTheSameWay() throws Exception {
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        User off = person("b");
        off.setEnabled(Boolean.FALSE);
        when(users.getAccounts(null, "x")).thenReturn(List.of(person("a"), off, person("c"), person("d")));

        JsonNode page = json(mvc(weblogger).perform(get("/v1/admin/users").param("q", "x").param("enabled", "true")
                .param("offset", "1").param("limit", "1")).andReturn().getResponse().getContentAsString());

        assertEquals(1, page.get("items").size());
        assertEquals("c", page.get("items").get(0).get("userName").asString());
        assertTrue(page.get("hasMore").asBoolean());
    }

    @Test
    void anUnknownKindIsAnInvalidParameter() throws Exception {
        Weblogger weblogger = weblogger();

        String problem = mvc(weblogger).perform(get("/v1/admin/users").param("kind", "robots"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertEquals("parameter.invalidValue", json(problem).get("code").asString());
        assertEquals(List.of("kind"), errorFields(problem));
    }

    // ------------------------------------------------------------------ profile fields

    @Test
    void theViewCarriesTheProfileFieldsAndTheWriterFlag() throws Exception {
        Weblogger weblogger = weblogger();
        User alice = person("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(alice);
        when(weblogger.getUserManager().getRoles(alice)).thenReturn(List.of("editor"));

        JsonNode view = json(mvc(weblogger).perform(patch("/v1/admin/users/alice")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getContentAsString());

        assertEquals("Full alice", view.get("fullName").asString());
        assertEquals("en_US", view.get("locale").asString());
        assertEquals("America/New_York", view.get("timeZone").asString());
        assertTrue(view.get("writer").asBoolean());
    }

    @Test
    void patchSetsFullNameLocaleAndTimeZone() throws Exception {
        Weblogger weblogger = weblogger();
        User alice = person("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(alice);

        mvc(weblogger).perform(patch("/v1/admin/users/alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\" Alice Liddell \",\"locale\":\"de_DE\",\"timeZone\":\"Europe/Berlin\"}"))
                .andExpect(status().isOk());

        assertEquals("Alice Liddell", alice.getFullName());
        assertEquals("de_DE", alice.getLocale());
        assertEquals("Europe/Berlin", alice.getTimeZone());
        verify(weblogger.getUserManager()).saveUser(alice);
    }

    @Test
    void patchRefusesBadProfileFieldsTogetherAndSavesNothing() throws Exception {
        Weblogger weblogger = weblogger();
        User alice = person("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(alice);

        String problem = mvc(weblogger).perform(patch("/v1/admin/users/alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\" \",\"locale\":\"xx_YY\",\"timeZone\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertEquals(List.of("fullName", "locale", "timeZone"), errorFields(problem));
        assertEquals("Full alice", alice.getFullName());
        assertEquals("en_US", alice.getLocale());
        verify(weblogger.getUserManager(), never()).saveUser(any());
    }

    @Test
    void patchRefusesATooLongFullNameWithTheColumnWidth() throws Exception {
        Weblogger weblogger = weblogger();
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(person("alice"));

        String problem = mvc(weblogger).perform(patch("/v1/admin/users/alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + "n".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertEquals("field.tooLong", json(problem).get("errors").get(0).get("code").asString());
    }

    @Test
    void writerIsGrantedAndRevokedOnASingleSignOnAccount() throws Exception {
        // Session only since wave 4 (owner decision 2026-11-25): the role outlives a revoked token.
        asSession();
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        User reader = person("reader");
        reader.setSsoSubject("google-123");
        when(users.getUserByUserName("reader", null)).thenReturn(reader);
        when(users.hasRole("editor", reader)).thenReturn(false);

        mvc(weblogger).perform(patch("/v1/admin/users/reader").contentType(MediaType.APPLICATION_JSON)
                .content("{\"writer\":true}")).andExpect(status().isOk());
        verify(users).grantRole("editor", reader);

        when(users.hasRole("editor", reader)).thenReturn(true);
        mvc(weblogger).perform(patch("/v1/admin/users/reader").contentType(MediaType.APPLICATION_JSON)
                .content("{\"writer\":false}")).andExpect(status().isOk());
        verify(users).revokeRole("editor", reader);
    }

    @Test
    void writerOnAnAccountThatIsNotSingleSignOnIsRefusedAndSavesNothing() throws Exception {
        // As a session, so the account's own rule is what answers (a token meets 403 first).
        asSession();
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        when(users.getUserByUserName("alice", null)).thenReturn(person("alice"));

        String problem = mvc(weblogger).perform(patch("/v1/admin/users/alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"writer\":true,\"fullName\":\"Changed\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertEquals("user.writerSsoOnly", json(problem).get("errors").get(0).get("code").asString());
        assertEquals(List.of("writer"), errorFields(problem));
        verify(users, never()).grantRole(any(), any());
        verify(users, never()).saveUser(any());
    }

    @Test
    void createTakesFullNameLocaleAndTimeZoneAndRefusesBadOnes() throws Exception {
        Weblogger weblogger = weblogger();
        UserTokenManager tokens = mock(UserTokenManager.class);
        when(weblogger.getUserTokenManager()).thenReturn(tokens);
        when(tokens.issueToken(any(User.class), eq(UserToken.Purpose.PASSWORD_SET))).thenReturn("raw");

        String problem = mvc(weblogger).perform(post("/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"alice\",\"emailAddress\":\"a@example.com\",\"fullName\":\" \","
                        + "\"locale\":\"xx_YY\",\"timeZone\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals(List.of("fullName", "locale", "timeZone"), errorFields(problem));

        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            mailer.when(PasswordLinkMailer::isReady).thenReturn(true);
            JsonNode view = json(mvc(weblogger).perform(post("/v1/admin/users").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userName\":\"alice\",\"emailAddress\":\"a@example.com\",\"fullName\":\"Alice L\","
                            + "\"locale\":\"de_DE\",\"timeZone\":\"Europe/Berlin\"}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
            assertEquals("Alice L", view.get("fullName").asString());
            assertEquals("de_DE", view.get("locale").asString());
            assertEquals("Europe/Berlin", view.get("timeZone").asString());
        }
    }

    // ------------------------------------------------------------------ password link

    @Test
    void passwordLinkIssuesATokenAndMailsIt() throws Exception {
        Weblogger weblogger = weblogger();
        UserTokenManager tokens = mock(UserTokenManager.class);
        when(weblogger.getUserTokenManager()).thenReturn(tokens);
        User alice = person("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(alice);
        when(tokens.issueToken(alice, UserToken.Purpose.PASSWORD_SET)).thenReturn("raw-token");

        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            mailer.when(PasswordLinkMailer::isReady).thenReturn(true);

            mvc(weblogger).perform(post("/v1/admin/users/alice/password-link")).andExpect(status().isNoContent());

            mailer.verify(() -> PasswordLinkMailer.sendLink(eq(alice), eq("raw-token"), any()));
        }
        verify(weblogger).flush();
    }

    @Test
    void passwordLinkNeedsMail() throws Exception {
        Weblogger weblogger = weblogger();
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(person("alice"));

        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            mailer.when(PasswordLinkMailer::isReady).thenReturn(false);

            String problem = mvc(weblogger).perform(post("/v1/admin/users/alice/password-link"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

            assertEquals("user.mailNotConfigured", json(problem).get("code").asString());
            mailer.verify(() -> PasswordLinkMailer.sendLink(any(), any(), any()), never());
        }
    }

    @Test
    void passwordLinkIsRefusedForAnSsoAccountAndAnAgentAndAnUnknownUser() throws Exception {
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        User sso = person("sso");
        sso.setSsoSubject("google-1");
        when(users.getUserByUserName("sso", null)).thenReturn(sso);
        asSession();
        when(users.getUserByUserName("bot", null)).thenReturn(agent("bot"));

        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            mailer.when(PasswordLinkMailer::isReady).thenReturn(true);
            assertEquals("user.ssoBound", json(mvc(weblogger).perform(post("/v1/admin/users/sso/password-link"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString())
                    .get("code").asString());
            assertEquals("user.notAPerson", json(mvc(weblogger).perform(post("/v1/admin/users/bot/password-link"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString())
                    .get("code").asString());
            mvc(weblogger).perform(post("/v1/admin/users/nobody/password-link")).andExpect(status().isNotFound());
        }
    }

    @Test
    void aTokenCannotMailALinkForAnAgentOrAnAdminAccount() throws Exception {
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        when(users.getUserByUserName("bot", null)).thenReturn(agent("bot"));
        User boss = person("boss");
        when(users.getUserByUserName("boss", null)).thenReturn(boss);
        when(users.checkPermission(any(), eq(boss))).thenReturn(true);

        mvc(weblogger).perform(post("/v1/admin/users/bot/password-link")).andExpect(status().isForbidden());
        mvc(weblogger).perform(post("/v1/admin/users/boss/password-link")).andExpect(status().isForbidden());
    }

    @Test
    void aMailFailureIsABadGatewayAndNothingIsChanged() throws Exception {
        Weblogger weblogger = weblogger();
        UserTokenManager tokens = mock(UserTokenManager.class);
        when(weblogger.getUserTokenManager()).thenReturn(tokens);
        User alice = person("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(alice);
        when(tokens.issueToken(alice, UserToken.Purpose.PASSWORD_SET)).thenReturn("raw");

        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            mailer.when(PasswordLinkMailer::isReady).thenReturn(true);
            mailer.when(() -> PasswordLinkMailer.sendLink(any(), any(), any()))
                    .thenThrow(new MessagingException("smtp down"));

            String problem = mvc(weblogger).perform(post("/v1/admin/users/alice/password-link"))
                    .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();

            assertEquals("user.passwordLinkFailed", json(problem).get("code").asString());
        }
    }

    // ------------------------------------------------------------------ a user's weblogs

    @Test
    void weblogsListsTheMembershipsByHandleWithTheirLevel() throws Exception {
        Weblogger weblogger = weblogger();
        UserManager users = weblogger.getUserManager();
        WeblogManager weblogs = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        User alice = person("alice");
        when(users.getUserByUserName("alice", null)).thenReturn(alice);
        WeblogPermission zed = permission("zed", WeblogPermission.POST);
        WeblogPermission ace = permission("ace", WeblogPermission.ADMIN);
        WeblogPermission gone = permission("gone", WeblogPermission.POST);
        when(users.getWeblogPermissions(alice)).thenReturn(List.of(zed, ace, gone));
        when(weblogs.getWeblogByHandle("zed", null)).thenReturn(blog("zed", "Zed Blog"));
        when(weblogs.getWeblogByHandle("ace", null)).thenReturn(blog("ace", null));

        JsonNode body = json(mvc(weblogger).perform(get("/v1/admin/users/alice/weblogs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals(2, body.get("items").size());
        assertEquals("ace", body.get("items").get(0).get("handle").asString());
        assertEquals("ADMIN", body.get("items").get(0).get("permission").asString());
        assertEquals("", body.get("items").get(0).get("name").asString());
        assertEquals("zed", body.get("items").get(1).get("handle").asString());
        assertEquals("POST", body.get("items").get(1).get("permission").asString());
    }

    @Test
    void weblogsOfAnUnknownUserIsNotFound() throws Exception {
        mvc(weblogger()).perform(get("/v1/admin/users/nobody/weblogs")).andExpect(status().isNotFound());
    }

    private static WeblogPermission permission(String handle, String action) {
        WeblogPermission permission = new WeblogPermission();
        permission.setObjectId(handle);
        permission.setActionsAsList(List.of(action));
        return permission;
    }

    private static Weblog blog(String handle, String name) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(name);
        return weblog;
    }
}
