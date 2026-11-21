package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Characterisation tests (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 2, criterion 1): written before the account rules moved into
 * {@code AccountRules}, and passing on the code they replaced. They pin what
 * {@code POST /v1/admin/users} and {@code PATCH /v1/admin/users/{userName}}
 * answer for the username, email address and screen name: the status and,
 * for a refusal, the whole problem body.
 *
 * <p>Criterion 4 changed the address answers on purpose, in a commit of its
 * own, each new expectation seen to fail first: an address is judged by
 * commons {@code EmailValidator}, as the person and agent screens judge it,
 * not by the API's old regex. So {@code a@b.c} (a one-letter top-level
 * domain) and {@code new@example.test} (a reserved name on no top-level-domain
 * list) are refused, with the details the regex's refusals had, and a quoted
 * local part such as {@code "a b"@example.com} is accepted.
 */
class AdminUsersCharacterisationTest {

    /** A 258-character address every email check accepts but for its length. */
    private static final String LONG_ADDRESS = "abcdefghij@" + ("x".repeat(60) + ".").repeat(4) + "com";

    private Weblogger weblogger;
    private UserManager users;
    private MockMvc mvc;
    private MockedStatic<PasswordLinkMailer> mailer;
    private User alice;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        // Mail is never ready here, so a create that passes every field check is refused for that
        // instead of creating anything.
        mailer = mockStatic(PasswordLinkMailer.class);
        mailer.when(PasswordLinkMailer::isReady).thenReturn(false);
        alice = new User();
        alice.setUserName("alice");
        alice.setScreenName("Alice");
        alice.setEmailAddress("alice@example.com");
        alice.setEnabled(Boolean.TRUE);
        when(users.getUserByUserName("alice", null)).thenReturn(alice);
    }

    @AfterEach
    void tearDown() {
        mailer.close();
    }

    // --- POST /users: userName ---

    @Test
    void aBlankOrMissingUserNameIsRequired() throws Exception {
        String required = invalid("/v1/admin/users", "userName", "userName is required.", "field.required");

        assertEquals(required, created("{\"userName\":\"  \",\"emailAddress\":\"new@example.com\"}", 400));
        assertEquals(required, created("{\"emailAddress\":\"new@example.com\"}", 400));
    }

    @Test
    void aUserNameOverItsColumnIsTooLong() throws Exception {
        assertEquals(invalid("/v1/admin/users", "userName", "'userName' must be 255 characters or fewer.",
                        "field.tooLong"),
                created("{\"userName\":\"" + "j".repeat(256) + "\",\"emailAddress\":\"new@example.com\","
                        + "\"screenName\":\"Jake\"}", 400));
    }

    /** With no screen name sent, it defaults to the username, so a long username is refused twice. */
    @Test
    void aLongUserNameIsALongDefaultScreenNameToo() throws Exception {
        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"'userName' must be 255 characters or fewer. "
                + "'screenName' must be 255 characters or fewer.\",\"instance\":\"/v1/admin/users\","
                + "\"errors\":[{\"field\":\"userName\",\"message\":\"'userName' must be 255 characters or fewer.\","
                + "\"code\":\"field.tooLong\"},{\"field\":\"screenName\","
                + "\"message\":\"'screenName' must be 255 characters or fewer.\",\"code\":\"field.tooLong\"}],"
                + "\"code\":\"request.invalidFields\"}",
                created("{\"userName\":\"" + "j".repeat(256) + "\",\"emailAddress\":\"new@example.com\"}", 400));
    }

    @Test
    void aUserNameOutsideTheAllowedCharactersIsRefused() throws Exception {
        assertEquals(invalid("/v1/admin/users", "userName", "userName contains characters that are not allowed.",
                        "user.nameHasDisallowedCharacters"),
                created("{\"userName\":\"jake fear\",\"emailAddress\":\"new@example.com\"}", 400));
    }

    @Test
    void aUserNameTooLongAndOfBadCharactersIsTooLong() throws Exception {
        assertEquals(invalid("/v1/admin/users", "userName", "'userName' must be 255 characters or fewer.",
                        "field.tooLong"),
                created("{\"userName\":\"" + "j ".repeat(150) + "x\",\"emailAddress\":\"new@example.com\","
                        + "\"screenName\":\"Jake\"}", 400));
    }

    @Test
    void aUserNameIsJudgedTrimmed() throws Exception {
        assertEquals(mailNotConfigured(),
                created("{\"userName\":\"  jake \",\"emailAddress\":\"new@example.com\"}", 400));
        verify(users, atLeastOnce()).getUserByUserName("jake", null);
    }

    @Test
    void aUserNameTakenInAnyCaseIsAConflict() throws Exception {
        String conflict = "{\"type\":\"https://pressroom.invalid/problems/conflict\",\"title\":\"Conflict\","
                + "\"status\":409,\"detail\":\"A user named 'Alice' already exists.\",\"instance\":\"/v1/admin/users\","
                + "\"errors\":[{\"field\":\"userName\",\"message\":\"A user named 'Alice' already exists.\","
                + "\"code\":\"user.nameInUse\"}],\"code\":\"user.nameInUse\"}";

        assertEquals(conflict, created("{\"userName\":\"Alice\",\"emailAddress\":\"new@example.com\"}", 409));
    }

    @Test
    void theTakenCheckFollowsTheFieldChecks() throws Exception {
        String body = created("{\"userName\":\"alice\",\"emailAddress\":\"nope\"}", 400);

        assertEquals(invalid("/v1/admin/users", "emailAddress", "A valid emailAddress is required.",
                "user.emailRequired"), body);
    }

    @Test
    void aTakenCheckThatCannotRunIsTheOpaqueFailure() throws Exception {
        when(users.getUserByUserName("jake", null)).thenThrow(new WebloggerException("down"));

        assertEquals("{\"type\":\"https://pressroom.invalid/problems/internal-error\",\"title\":\"Internal error\","
                + "\"status\":500,\"detail\":\"The request could not be completed.\",\"instance\":\"/v1/admin/users\","
                + "\"code\":\"server.internalError\"}",
                created("{\"userName\":\"jake\",\"emailAddress\":\"new@example.com\"}", 500));
    }

    // --- POST /users: emailAddress and screenName ---

    @Test
    void aMissingBlankOrMalformedAddressIsRequired() throws Exception {
        String required = invalid("/v1/admin/users", "emailAddress", "A valid emailAddress is required.",
                "user.emailRequired");

        assertEquals(required, created("{\"userName\":\"jake\"}", 400));
        assertEquals(required, created("{\"userName\":\"jake\",\"emailAddress\":\"  \"}", 400));
        assertEquals(required, created("{\"userName\":\"jake\",\"emailAddress\":\"not-an-address\"}", 400));
        assertEquals(required, created("{\"userName\":\"jake\",\"emailAddress\":\"a b@example.com\"}", 400));
    }

    /** Criterion 4: the validator knows top-level domains, so these are refused with today's detail. */
    @Test
    void anAddressOnNoTopLevelDomainIsRequired() throws Exception {
        String required = invalid("/v1/admin/users", "emailAddress", "A valid emailAddress is required.",
                "user.emailRequired");

        assertEquals(required, created("{\"userName\":\"jake\",\"emailAddress\":\"a@b.c\"}", 400));
        assertEquals(required, created("{\"userName\":\"jake\",\"emailAddress\":\"new@example.test\"}", 400));
    }

    /** Criterion 4: a quoted local part, which the regex refused for its space, is a valid address. */
    @Test
    void aQuotedLocalPartPassesTheAddressCheck() throws Exception {
        assertEquals(mailNotConfigured(),
                created("{\"userName\":\"jake\",\"emailAddress\":\"\\\"a b\\\"@example.com\"}", 400));
    }

    @Test
    void anAddressIsJudgedTrimmed() throws Exception {
        assertEquals(mailNotConfigured(),
                created("{\"userName\":\"jake\",\"emailAddress\":\"  new@example.com \"}", 400));
    }

    @Test
    void anAddressOverItsColumnIsTooLong() throws Exception {
        assertEquals(invalid("/v1/admin/users", "emailAddress", "'emailAddress' must be 255 characters or fewer.",
                        "field.tooLong"),
                created("{\"userName\":\"jake\",\"emailAddress\":\"" + LONG_ADDRESS + "\"}", 400));
    }

    @Test
    void aScreenNameOverItsColumnIsTooLong() throws Exception {
        assertEquals(invalid("/v1/admin/users", "screenName", "'screenName' must be 255 characters or fewer.",
                        "field.tooLong"),
                created("{\"userName\":\"jake\",\"emailAddress\":\"new@example.com\",\"screenName\":\""
                        + "s".repeat(256) + "\"}", 400));
    }

    /** The screen name defaults to the username, so a blank username is the only refusal. */
    @Test
    void aBlankUserNameIsNotAlsoABlankScreenName() throws Exception {
        assertEquals(invalid("/v1/admin/users", "userName", "userName is required.", "field.required"),
                created("{\"userName\":\" \",\"emailAddress\":\"new@example.com\",\"screenName\":\" \"}", 400));
    }

    @Test
    void everyBadFieldIsNamedInOneRefusal() throws Exception {
        assertEquals("{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"userName contains characters that are not allowed. "
                + "A valid emailAddress is required. 'screenName' must be 255 characters or fewer.\","
                + "\"instance\":\"/v1/admin/users\",\"errors\":[{\"field\":\"userName\","
                + "\"message\":\"userName contains characters that are not allowed.\","
                + "\"code\":\"user.nameHasDisallowedCharacters\"},{\"field\":\"emailAddress\","
                + "\"message\":\"A valid emailAddress is required.\",\"code\":\"user.emailRequired\"},"
                + "{\"field\":\"screenName\",\"message\":\"'screenName' must be 255 characters or fewer.\","
                + "\"code\":\"field.tooLong\"}],\"code\":\"request.invalidFields\"}",
                created("{\"userName\":\"a-b\",\"emailAddress\":\"nope\",\"screenName\":\"" + "s".repeat(256)
                        + "\"}", 400));
    }

    // --- PATCH /users/{userName} ---

    @Test
    void aMalformedOrBlankAddressOnAPatchIsInvalid() throws Exception {
        String invalid = invalid("/v1/admin/users/alice", "emailAddress", "emailAddress is not a valid address.",
                "user.emailInvalid");

        assertEquals(invalid, patched("{\"emailAddress\":\"not-an-address\"}", 400));
        assertEquals(invalid, patched("{\"emailAddress\":\"  \"}", 400));
        assertEquals(invalid, patched("{\"emailAddress\":\"a b@example.com\"}", 400));
        assertEquals("alice@example.com", alice.getEmailAddress());
        verify(users, never()).saveUser(any());
    }

    /** Criterion 4: the validator knows top-level domains, so these are refused with today's detail. */
    @Test
    void anAddressOnNoTopLevelDomainIsInvalidOnAPatch() throws Exception {
        String invalid = invalid("/v1/admin/users/alice", "emailAddress", "emailAddress is not a valid address.",
                "user.emailInvalid");

        assertEquals(invalid, patched("{\"emailAddress\":\"a@b.c\"}", 400));
        assertEquals(invalid, patched("{\"emailAddress\":\"new@example.test\"}", 400));
        assertEquals("alice@example.com", alice.getEmailAddress());
        verify(users, never()).saveUser(any());
    }

    @Test
    void aPatchedAddressIsStoredTrimmed() throws Exception {
        patched("{\"emailAddress\":\"  new@example.com \"}", 200);

        assertEquals("new@example.com", alice.getEmailAddress());
    }

    @Test
    void aPatchedAddressOverItsColumnIsTooLong() throws Exception {
        assertEquals(invalid("/v1/admin/users/alice", "emailAddress",
                        "'emailAddress' must be 255 characters or fewer.", "field.tooLong"),
                patched("{\"emailAddress\":\"" + LONG_ADDRESS + "\"}", 400));
    }

    @Test
    void aBlankPatchedScreenNameIsRefused() throws Exception {
        assertEquals(invalid("/v1/admin/users/alice", "screenName", "screenName cannot be blank.", "field.blank"),
                patched("{\"screenName\":\" \"}", 400));
    }

    @Test
    void aPatchedScreenNameOverItsColumnIsTooLong() throws Exception {
        assertEquals(invalid("/v1/admin/users/alice", "screenName", "'screenName' must be 255 characters or fewer.",
                        "field.tooLong"),
                patched("{\"screenName\":\"" + "s".repeat(256) + "\"}", 400));
    }

    @Test
    void aPatchedScreenNameIsStoredTrimmed() throws Exception {
        patched("{\"screenName\":\"  Alice B \"}", 200);

        assertEquals("Alice B", alice.getScreenName());
    }

    // --- helpers ---

    private String created(String json, int status) throws Exception {
        String body = mvc.perform(post("/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(r -> assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString()))
                .andReturn().getResponse().getContentAsString();
        verify(users, never()).addUser(any());
        return body;
    }

    private String patched(String json, int status) throws Exception {
        return mvc.perform(patch("/v1/admin/users/alice").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(r -> assertEquals(status, r.getResponse().getStatus(), r.getResponse().getContentAsString()))
                .andReturn().getResponse().getContentAsString();
    }

    private static String invalid(String instance, String field, String message, String code) {
        return "{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"" + message + "\",\"instance\":\"" + instance + "\","
                + "\"errors\":[{\"field\":\"" + field + "\",\"message\":\"" + message + "\",\"code\":\"" + code
                + "\"}],\"code\":\"" + code + "\"}";
    }

    private static String mailNotConfigured() {
        return "{\"type\":\"https://pressroom.invalid/problems/invalid-request\",\"title\":\"Invalid request\","
                + "\"status\":400,\"detail\":\"Mail is not configured, so a set-password link cannot be delivered. "
                + "An account with no way to reach a usable password must not be created.\","
                + "\"instance\":\"/v1/admin/users\",\"code\":\"user.mailNotConfigured\"}";
    }
}
