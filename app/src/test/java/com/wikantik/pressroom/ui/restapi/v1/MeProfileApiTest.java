package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Map;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Profile page's writes for the signed-in caller ({@code PATCH /v1/me/profile}, {@code PUT /v1/me/password};
 * SA-18, session only), by the rules {@code ProfileController} applies: the account's names, email, locale and
 * time zone; a password change that asks for the current password, refuses a single-sign-on account and holds
 * the new one to the floor; a rename expires the weblogs showing the account's comments. Who may call them
 * (a session) is the interceptors' and is swept elsewhere.
 */
class MeProfileApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PasswordEncoder previousEncoder;
    private PasswordEncoder encoder;
    private Weblogger weblogger;
    private UserManager users;
    private CommentService comments;
    private MockMvc mvc;
    private User me;

    @BeforeEach
    void setUp() {
        previousEncoder = PressroomContext.getPasswordEncoder();
        // Strength 4, the least bcrypt allows: this class tests the profile API,
        // not the hash, and the default 10 cost a second of the suite in setUp.
        encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(4)));
        PressroomContext.setPasswordEncoder(encoder);
        weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        comments = mock(CommentService.class);
        MeProfileApi controller = new MeProfileApi();
        controller.weblogger = weblogger;
        controller.commentService = comments;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        me = new User();
        me.setUserName("pat");
        me.setScreenName("Pat");
        me.setFullName("Pat Smith");
        me.setEmailAddress("pat@example.com");
        me.setLocale("en_US");
        me.setTimeZone("America/New_York");
        me.setKind(User.Kind.PERSON);
        me.resetPassword("old password");
    }

    @AfterEach
    void tearDown() {
        PressroomContext.setPasswordEncoder(previousEncoder);
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.requestAttr("authenticatedUser", me).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(builder);
    }

    private static int status(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static void refused(ResultActions result, int status, String code, String field) throws Exception {
        assertEquals(status, status(result), result.andReturn().getResponse().getContentAsString());
        JsonNode problem = body(result);
        JsonNode first = problem.path("errors").path(0);
        assertEquals(code, problem.path("code").asString().equals("request.invalidFields")
                ? first.path("code").asString() : problem.path("code").asString());
        if (field != null) {
            assertEquals(field, first.path("field").asString());
        }
    }

    // --- profile

    @Test
    void patchChangesTheNamedFieldsAndAnswersTheProfile() throws Exception {
        ResultActions result = send(patch("/v1/me/profile"),
                "{\"screenName\":\" Patty \",\"fullName\":\"Patricia Smith\",\"emailAddress\":\"patty@example.com\","
                        + "\"locale\":\"en_GB\",\"timeZone\":\"Europe/London\"}");

        assertEquals(200, status(result), result.andReturn().getResponse().getContentAsString());
        assertEquals("Patty", me.getScreenName());
        assertEquals("Patricia Smith", me.getFullName());
        assertEquals("patty@example.com", me.getEmailAddress());
        assertEquals("en_GB", me.getLocale());
        assertEquals("Europe/London", me.getTimeZone());
        verify(users).saveUser(me);
        verify(weblogger).flush();
        assertEquals("Patty", body(result).path("screenName").asString());
        assertEquals("PERSON", body(result).path("kind").asString());
    }

    @Test
    void patchLeavesWhatItDoesNotName() throws Exception {
        assertEquals(200, status(send(patch("/v1/me/profile"), "{\"fullName\":\"Pat S\"}")));

        assertEquals("Pat", me.getScreenName());
        assertEquals("pat@example.com", me.getEmailAddress());
        assertEquals("en_US", me.getLocale());
    }

    @Test
    void patchNamesEveryBrokenFieldAndSavesNothing() throws Exception {
        ResultActions result = send(patch("/v1/me/profile"),
                "{\"screenName\":\"\",\"emailAddress\":\"nope\",\"locale\":\"xx_YY\",\"timeZone\":\"Mars/Base\"}");

        refused(result, 400, "field.blank", "screenName");
        JsonNode errors = body(result).path("errors");
        assertEquals("emailAddress", errors.path(1).path("field").asString());
        assertEquals("locale", errors.path(2).path("field").asString());
        assertEquals("timeZone", errors.path(3).path("field").asString());
        verify(users, never()).saveUser(any());
        verify(weblogger, never()).flush();
    }

    @Test
    void aChangedScreenNameExpiresTheWeblogsShowingTheComments() throws Exception {
        assertEquals(200, status(send(patch("/v1/me/profile"), "{\"screenName\":\"Patty\"}")));
        verify(comments).expireWhereShown(me);
    }

    @Test
    void anUnchangedScreenNameExpiresNothing() throws Exception {
        assertEquals(200, status(send(patch("/v1/me/profile"), "{\"screenName\":\"Pat\",\"fullName\":\"X\"}")));
        verify(comments, never()).expireWhereShown(any());
    }

    @Test
    void withNoSignedInUserTheRouteIs401() throws Exception {
        ResultActions result = mvc.perform(patch("/v1/me/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertEquals(401, status(result));
    }

    // --- password

    @Test
    void aPasswordChangeNeedsTheCurrentPasswordAndSetsTheNewOne() throws Exception {
        String before = me.getPassword();

        ResultActions result = send(put("/v1/me/password"),
                "{\"currentPassword\":\"old password\",\"newPassword\":\"a brand new one\"}");

        assertEquals(204, status(result));
        assertNotEquals(before, me.getPassword());
        assertEquals(true, encoder.matches("a brand new one", me.getPassword()));
        verify(users).saveUser(me);
        verify(weblogger).flush();
    }

    @Test
    void aWrongOrMissingCurrentPasswordChangesNothing() throws Exception {
        String before = me.getPassword();

        refused(send(put("/v1/me/password"), "{\"currentPassword\":\"nope\",\"newPassword\":\"a brand new one\"}"),
                400, "profile.currentPasswordWrong", "currentPassword");
        refused(send(put("/v1/me/password"), "{\"newPassword\":\"a brand new one\"}"), 400,
                "profile.currentPasswordWrong", "currentPassword");
        assertEquals(before, me.getPassword());
        verify(users, never()).saveUser(any());
    }

    @Test
    void aTooShortOrMissingNewPasswordIsRefusedOnItsField() throws Exception {
        refused(send(put("/v1/me/password"), "{\"currentPassword\":\"old password\",\"newPassword\":\"short\"}"),
                400, "password.tooShort", "newPassword");
        refused(send(put("/v1/me/password"), "{\"currentPassword\":\"old password\"}"), 400, "field.required",
                "newPassword");
        verify(users, never()).saveUser(any());
    }

    @Test
    void aSingleSignOnAccountHasNoPasswordToChange() throws Exception {
        me.setSsoIssuer("https://accounts.google.com");
        me.setSsoSubject("sub-1");

        refused(send(put("/v1/me/password"),
                "{\"currentPassword\":\"old password\",\"newPassword\":\"a brand new one\"}"), 400, "user.ssoBound",
                "newPassword");
        verify(users, never()).saveUser(any());
    }

    @Test
    void anAgentHasNoPasswordToChange() throws Exception {
        me.setKind(User.Kind.AGENT);

        refused(send(put("/v1/me/password"),
                "{\"currentPassword\":\"old password\",\"newPassword\":\"a brand new one\"}"), 400, "user.notAPerson",
                "newPassword");
    }

    /** The session that changed the password stays signed in: its credential stamp is renewed to the new hash. */
    @Test
    void aPasswordChangeRenewsTheChangingSessionsCredentialStamp() throws Exception {
        String stampKey = com.wikantik.pressroom.ui.core.security.CredentialStampFilter.class.getName() + ".stamp";
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(stampKey, "stamp-of-the-old-password");

        mvc.perform(put("/v1/me/password").session(session).requestAttr("authenticatedUser", me)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old password\",\"newPassword\":\"a brand new one\"}"))
                .andReturn();

        Object stamp = session.getAttribute(stampKey);
        assertNotEquals("stamp-of-the-old-password", stamp);
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(me.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8))), stamp);
    }
}
