package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.UserTokenManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.UserToken;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the admin routes, and of AdminAccountGuard, carries its own
 * code, with the English it always had (spec 2026-11-20-api-contract-design,
 * Task 3). The English half passed before Task 3 (characterisation); the code
 * half is what it adds.
 */
class AdminProblemCodesTest {

    private Weblogger weblogger;
    private UserManager users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getPropertiesManager()).thenReturn(mock(PropertiesManager.class));
        when(weblogger.getDiscoveryManager()).thenReturn(mock(DiscoveryManager.class));
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        controller.commentService = org.mockito.Mockito.mock(com.wikantik.pressroom.business.comments.CommentService.class);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /** The admin UI's own login: the one caller kind that may change another user's email address. */
    private static void asSession() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("boss", "x",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        auth.setDetails(new WebAuthenticationDetails("127.0.0.1", null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private JsonNode createUser(ResultMatcher status, String body) throws Exception {
        return answer(status, post("/v1/admin/users").content(body));
    }

    private JsonNode config(String body) throws Exception {
        return answer(status().isBadRequest(), patch("/v1/admin/config").content(body));
    }

    private User user(String name) throws Exception {
        User user = new User();
        user.setUserName(name);
        when(users.getUserByUserName(name, null)).thenReturn(user);
        return user;
    }

    @Test
    void theUserCreateFieldRefusalsHaveTheirCodes() throws Exception {
        oneField(createUser(status().isBadRequest(), "{\"emailAddress\":\"a@example.com\"}"),
                "userName", "field.required", "userName is required.");
        oneField(createUser(status().isBadRequest(), "{\"userName\":\"a b!\",\"emailAddress\":\"a@example.com\"}"),
                "userName", "user.nameHasDisallowedCharacters", "userName contains characters that are not allowed.");
        oneField(createUser(status().isBadRequest(), "{\"userName\":\"alice\",\"emailAddress\":\"nope\"}"),
                "emailAddress", "user.emailRequired", "A valid emailAddress is required.");
    }

    @Test
    void aTakenUserNameIsUserNameInUse() throws Exception {
        user("alice");
        oneField(createUser(status().isConflict(), "{\"userName\":\"alice\",\"emailAddress\":\"a@example.com\"}"),
                "userName", "user.nameInUse", "A user named 'alice' already exists.");
    }

    @Test
    void theMailRefusalsOfACreateHaveTheirCodes() throws Exception {
        String alice = "{\"userName\":\"alice\",\"emailAddress\":\"a@example.com\"}";
        PasswordEncoder before = PressroomContext.getPasswordEncoder();
        PressroomContext.setPasswordEncoder(new BCryptPasswordEncoder());
        try (MockedStatic<PasswordLinkMailer> mailer = mockStatic(PasswordLinkMailer.class)) {
            mailer.when(PasswordLinkMailer::isReady).thenReturn(false);
            problem(createUser(status().isBadRequest(), alice), "user.mailNotConfigured",
                    "Mail is not configured, so a set-password link cannot be delivered. "
                            + "An account with no way to reach a usable password must not be created.");

            UserTokenManager tokens = mock(UserTokenManager.class);
            when(weblogger.getUserTokenManager()).thenReturn(tokens);
            when(tokens.issueToken(any(User.class), eq(UserToken.Purpose.PASSWORD_SET))).thenReturn("raw");
            mailer.when(PasswordLinkMailer::isReady).thenReturn(true);
            mailer.when(() -> PasswordLinkMailer.sendLink(any(User.class), anyString(), anyString()))
                    .thenThrow(new MessagingException("down"));
            problem(createUser(status().isBadGateway(), alice), "user.passwordLinkNotSent",
                    "The account was created (disabled), but the set-password link could not be sent. "
                            + "Resend it from the UserAdmin screen's \"send set-password link\" action.");
        } finally {
            PressroomContext.setPasswordEncoder(before);
        }
    }

    @Test
    void theUserPatchRefusalsHaveTheirCodes() throws Exception {
        asSession();
        problem(answer(status().isNotFound(), patch("/v1/admin/users/nobody").content("{}")),
                "user.notFound", "No such user.");
        user("bob");
        oneField(answer(status().isBadRequest(), patch("/v1/admin/users/bob").content("{\"emailAddress\":\"x\"}")),
                "emailAddress", "user.emailInvalid", "emailAddress is not a valid address.");
        oneField(answer(status().isBadRequest(), patch("/v1/admin/users/bob").content("{\"screenName\":\" \"}")),
                "screenName", "field.blank", "screenName cannot be blank.");
    }

    @Test
    void aNonSessionCallerChangingAnotherUsersEmailIsRefusedAndNothingIsSaved() throws Exception {
        User bob = user("bob");
        bob.setEmailAddress("bob@example.com");
        bob.setScreenName("Bob");

        problem(answer(status().isForbidden(), patch("/v1/admin/users/bob")
                        .content("{\"emailAddress\":\"new@example.com\"}")),
                "user.emailUiOnly", "Email addresses are changed in the admin UI.");
        problem(answer(status().isForbidden(), patch("/v1/admin/users/bob")
                        .content("{\"emailAddress\":\"new@example.com\",\"screenName\":\"Robert\"}")),
                "user.emailUiOnly", "Email addresses are changed in the admin UI.");

        assertEquals("bob@example.com", bob.getEmailAddress());
        assertEquals("Bob", bob.getScreenName());
        verify(users, never()).saveUser(any());
    }

    @Test
    void aNonSessionCallerMayResendTheStoredEmailAndChangeTheScreenName() throws Exception {
        User bob = user("bob");
        bob.setEmailAddress("bob@example.com");

        answer(status().isOk(), patch("/v1/admin/users/bob")
                .content("{\"emailAddress\":\"bob@example.com\",\"screenName\":\"Robert\"}"));

        assertEquals("Robert", bob.getScreenName());
        verify(users).saveUser(bob);
    }

    @Test
    void aSessionMayChangeAnotherUsersEmail() throws Exception {
        asSession();
        User bob = user("bob");
        bob.setEmailAddress("bob@example.com");

        answer(status().isOk(), patch("/v1/admin/users/bob").content("{\"emailAddress\":\"new@example.com\"}"));

        assertEquals("new@example.com", bob.getEmailAddress());
        verify(users).saveUser(bob);
    }

    @Test
    void theAccountGuardsHaveTheirCodes() throws Exception {
        User agent = user("robot");
        agent.setKind(User.Kind.AGENT);
        problem(answer(status().isForbidden(), patch("/v1/admin/users/robot").content("{}")),
                "account.agentManagedInAdminUi", "Agent accounts are managed in the admin UI.");

        user("root");
        when(users.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(true);
        problem(answer(status().isForbidden(), patch("/v1/admin/users/root").content("{}")),
                "account.adminManagedInAdminUi", "Admin accounts are managed in the admin UI.");

        problem(answer(status().isForbidden(), patch("/v1/admin/config").content("{\"site.absoluteurl\":\"x\"}")),
                "setting.managedInAdminUi", "site.absoluteurl is managed in the admin UI.");
    }

    @Test
    void theConfigRefusalsHaveTheirCodes() throws Exception {
        problem(config("{}"), "config.propertiesRequired", "At least one property is required.");
        problem(config("{\"\":\"x\"}"), "config.propertyNameRequired", "A property name is required.");
        oneField(config("{\"no.such.property\":\"x\"}"), "no.such.property",
                "config.propertyUnknown", "'no.such.property' is not a runtime-settable property.");
        oneField(config("{\"groupblogging.enabled\":null}"), "groupblogging.enabled",
                "config.valueNull", "'groupblogging.enabled' cannot be set to null.");
        oneField(config("{\"groupblogging.enabled\":\"maybe\"}"), "groupblogging.enabled",
                "config.notBoolean", "'groupblogging.enabled' must be true or false.");
        oneField(config("{\"site.pages.maxEntries\":\"abc\"}"), "site.pages.maxEntries",
                "config.notInteger", "'site.pages.maxEntries' must be an integer.");
        oneField(config("{\"uploads.file.maxsize\":\"abc\"}"), "uploads.file.maxsize",
                "config.notNumber", "'uploads.file.maxsize' must be a number.");
        oneField(config("{\"site.discovery.refreshSeconds\":\"30\"}"), "site.discovery.refreshSeconds",
                "config.outOfRange", "'site.discovery.refreshSeconds' must be between 60 and 86400.");
    }
}
