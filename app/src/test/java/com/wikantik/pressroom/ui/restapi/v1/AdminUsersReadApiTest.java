package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What the React Users screens read where the JSP view models computed it (spec 2026-11-25, wave 4 cutover):
 * {@code GET /v1/admin/users/{userName}} (one account, no {@code q=} exact-match), the weblog count and the
 * last-active time on every {@code UserView}, and the create form's Enabled box.
 */
class AdminUsersReadApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PasswordEncoder previousEncoder;
    private UserManager users;
    private ApiTokenManager tokens;
    private Weblogger weblogger;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        previousEncoder = PressroomContext.getPasswordEncoder();
        PressroomContext.setPasswordEncoder(new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder())));
        weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        tokens = mock(ApiTokenManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getApiTokenManager()).thenReturn(tokens);
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        controller.commentService = mock(CommentService.class);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated("boss", "x",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        auth.setDetails(new WebAuthenticationDetails("127.0.0.1", null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        PressroomContext.setPasswordEncoder(previousEncoder);
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName("Screen " + userName);
        user.setEmailAddress(userName + "@example.test");
        user.setEnabled(Boolean.TRUE);
        user.setKind(kind);
        return user;
    }

    private JsonNode getJson(String url, int status) throws Exception {
        var response = mvc.perform(get(url).requestAttr("authenticatedUser", account("boss", User.Kind.PERSON)))
                .andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return JSON.readTree(response.getContentAsString());
    }

    /**
     * The site space is withheld from every API route that names it, so the Global Config front-door choices
     * come from this admin route: the site space's pages by slug, title and status; none while no site space exists.
     */
    @Test
    void sitePagesListsTheSiteSpacesPagesAndIsEmptyWithoutASiteSpace() throws Exception {
        var weblogs = mock(com.wikantik.pressroom.business.WeblogManager.class);
        var pages = mock(com.wikantik.pressroom.business.WeblogPageManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getWeblogPageManager()).thenReturn(pages);

        assertEquals(0, getJson("/v1/admin/site-pages", 200).size());

        var site = new com.wikantik.pressroom.pojos.Weblog();
        when(weblogs.getSiteSpace()).thenReturn(site);
        var welcome = new com.wikantik.pressroom.pojos.WeblogPage();
        welcome.setSlug("welcome");
        welcome.setTitle("Welcome");
        welcome.setStatus(com.wikantik.pressroom.pojos.WeblogPage.PubStatus.PUBLISHED);
        when(pages.getPages(site)).thenReturn(List.of(welcome));

        JsonNode body = getJson("/v1/admin/site-pages", 200);
        assertEquals(1, body.size());
        assertEquals("welcome", body.get(0).get("slug").asString());
        assertEquals("Welcome", body.get(0).get("title").asString());
        assertEquals("PUBLISHED", body.get(0).get("status").asString());
    }

    @Test
    void readOneReturnsTheAccountWithItsWeblogCountAndLastSignIn() throws Exception {
        User carol = account("carol", User.Kind.PERSON);
        carol.setLastLogin(new Date(1_700_000_000_000L));
        when(users.getUserByUserName("carol", null)).thenReturn(carol);
        when(users.getRoles(carol)).thenReturn(List.of("editor"));
        when(users.getWeblogPermissions(carol)).thenReturn(List.of(new WeblogPermission(), new WeblogPermission()));

        JsonNode body = getJson("/v1/admin/users/carol", 200);

        assertEquals("carol", body.get("userName").asString());
        assertEquals(2, body.get("weblogCount").asInt());
        assertEquals("2023-11-14T22:13:20Z", body.get("lastActive").asString());
        assertTrue(body.get("writer").asBoolean());
    }

    @Test
    void readOneOfAnUnknownAccountIsA404Problem() throws Exception {
        JsonNode body = getJson("/v1/admin/users/nobody", 404);

        assertEquals("user.notFound", body.get("code").asString());
    }

    @Test
    void aPersonWhoNeverSignedInHasNoLastActive() throws Exception {
        User dan = account("dan", User.Kind.PERSON);
        when(users.getUserByUserName("dan", null)).thenReturn(dan);

        JsonNode body = getJson("/v1/admin/users/dan", 200);

        assertFalse(body.has("lastActive"));
        assertEquals(0, body.get("weblogCount").asInt());
    }

    @Test
    void anAgentsLastActiveIsItsNewestTokenUse() throws Exception {
        User bot = account("bot", User.Kind.AGENT);
        ApiToken older = new ApiToken();
        older.setLastUsedAt(new Timestamp(1_600_000_000_000L));
        ApiToken newer = new ApiToken();
        newer.setLastUsedAt(new Timestamp(1_700_000_000_000L));
        when(users.getUserByUserName("bot", null)).thenReturn(bot);
        when(tokens.getTokens(bot)).thenReturn(List.of(older, newer, new ApiToken()));

        JsonNode body = getJson("/v1/admin/users/bot", 200);

        assertEquals("2023-11-14T22:13:20Z", body.get("lastActive").asString());
    }

    @Test
    void theListCarriesTheSameTwoFieldsPerRowWithNoCallPerRow() throws Exception {
        User carol = account("carol", User.Kind.PERSON);
        carol.setLastLogin(new Date(1_700_000_000_000L));
        when(users.getUsers(null, null, null, 0, 51)).thenReturn(List.of(carol));
        when(users.getWeblogPermissions(carol)).thenReturn(List.of(new WeblogPermission()));

        JsonNode row = getJson("/v1/admin/users", 200).get("items").get(0);

        assertEquals(1, row.get("weblogCount").asInt());
        assertEquals("2023-11-14T22:13:20Z", row.get("lastActive").asString());
    }

    /** The JSP's create form had an Enabled box; a typed password otherwise enables, a link leaves it disabled. */
    @Test
    void createHonoursTheEnabledFlagOnATypedPasswordAccount() throws Exception {
        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            var response = mvc.perform(post("/v1/admin/users")
                    .requestAttr("authenticatedUser", account("boss", User.Kind.PERSON))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"userName\":\"dave\",\"emailAddress\":\"dave@example.com\","
                            + "\"password\":\"long enough password\",\"enabled\":false}"))
                    .andReturn().getResponse();

            assertEquals(201, response.getStatus(), response.getContentAsString());
            org.mockito.ArgumentCaptor<User> added = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(users).addUser(added.capture());
            assertFalse(added.getValue().getEnabled());
            assertFalse(JSON.readTree(response.getContentAsString()).get("enabled").asBoolean());
            mailer.verifyNoInteractions();
        }
    }

    @Test
    void createWithNoEnabledFlagStillEnablesATypedPasswordAccount() throws Exception {
        var response = mvc.perform(post("/v1/admin/users")
                .requestAttr("authenticatedUser", account("boss", User.Kind.PERSON))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userName\":\"erin\",\"emailAddress\":\"erin@example.com\","
                        + "\"password\":\"long enough password\"}"))
                .andReturn().getResponse();

        assertEquals(201, response.getStatus(), response.getContentAsString());
        org.mockito.ArgumentCaptor<User> added = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).addUser(added.capture());
        assertTrue(added.getValue().getEnabled());
        verify(users, org.mockito.Mockito.never()).grantRole(eq("admin"), any());
    }
}
