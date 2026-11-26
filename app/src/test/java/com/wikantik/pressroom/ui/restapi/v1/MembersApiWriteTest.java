package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session-only membership writes ({@code POST/PATCH/DELETE .../members}, SA-18), judged by the rules the
 * Members page applies ({@code Memberships}). Who may call them (a session, a weblog admin) is the
 * interceptors' and is swept by {@code SessionOnlyRoutesTest} and the authorization matrix; these tests run
 * the handlers.
 */
class MembersApiWriteTest {

    private static final String URL = "/v1/weblogs/myblog/members";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWeblogger tier;
    private UserManager users;
    private Weblog weblog;
    private User owner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        tier = MockWeblogger.attached();
        users = tier.userManager();
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        owner = account("owen");
        MembersApi controller = new MembersApi();
        controller.weblogger = tier.weblogger();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        groupBlogging(true);
        when(users.checkPermission(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    private void groupBlogging(boolean on) throws Exception {
        when(tier.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", Boolean.toString(on)));
    }

    private User account(String userName) throws Exception {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName(userName);
        user.setKind(User.Kind.PERSON);
        when(users.getUserByUserName(userName)).thenReturn(user);
        return user;
    }

    private WeblogPermission holds(String userName, String... actions) {
        WeblogPermission permission = new WeblogPermission(weblog, List.of(actions));
        permission.setUserName(userName);
        return permission;
    }

    private void members(WeblogPermission... all) throws Exception {
        when(users.getWeblogPermissions(weblog)).thenReturn(List.of(all));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String body) throws Exception {
        builder.requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", owner);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static void code(ResultActions result, int status, String code) throws Exception {
        assertEquals(status, result.andReturn().getResponse().getStatus());
        JsonNode problem = body(result);
        String got = problem.path("code").asString("");
        if (got.equals("request.invalidFields")) {
            got = problem.path("errors").path(0).path("code").asString("");
        }
        assertEquals(code, got);
    }

    // --- grant

    @Test
    void grantGivesAnExistingAccountTheLevelAndReportsIt() throws Exception {
        User carol = account("carol");
        members(holds("owen", "admin"), holds("carol", "post"));

        ResultActions result = send(post(URL), "{\"userName\":\"carol\",\"permission\":\"POST\"}");

        result.andExpect(status().isCreated());
        assertEquals("POST", body(result).path("permission").asString());
        assertEquals("carol", body(result).path("userName").asString());
        verify(users).grantWeblogPermission(weblog, carol, List.of("post"));
        verify(tier.weblogger()).flush();
    }

    @Test
    void grantRefusesWhileGroupBloggingIsOff() throws Exception {
        groupBlogging(false);
        account("carol");

        code(send(post(URL), "{\"userName\":\"carol\",\"permission\":\"POST\"}"), 409, "member.groupBloggingOff");
        verify(users, never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantNamesEveryFieldThatIsWrong() throws Exception {
        ResultActions result = send(post(URL), "{\"userName\":\"nobody\",\"permission\":\"OWNER\"}");

        result.andExpect(status().isBadRequest());
        JsonNode errors = body(result).path("errors");
        assertEquals("userName", errors.path(0).path("field").asString());
        assertEquals("member.userUnknown", errors.path(0).path("code").asString());
        assertEquals("permission", errors.path(1).path("field").asString());
        assertEquals("member.levelInvalid", errors.path(1).path("code").asString());
        verify(users, never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void grantRefusesABlankUserNameAndAMissingLevel() throws Exception {
        ResultActions result = send(post(URL), "{\"userName\":\" \"}");

        result.andExpect(status().isBadRequest());
        assertEquals(2, body(result).path("errors").size());
    }

    @Test
    void grantRefusesAReaderAccount() throws Exception {
        User reader = account("reggie");
        when(users.checkPermission(any(), eq(reader))).thenReturn(false);

        code(send(post(URL), "{\"userName\":\"reggie\",\"permission\":\"POST\"}"), 400, "member.readerAccount");
    }

    // --- change

    @Test
    void changeGivesTheMemberTheOneLevelAsked() throws Exception {
        User carol = account("carol");
        when(users.getWeblogPermissions(weblog))
                .thenReturn(List.of(holds("owen", "admin"), holds("carol", "post")))
                .thenReturn(List.of(holds("owen", "admin"), holds("carol", "edit_draft")));

        ResultActions result = send(patch(URL + "/carol"), "{\"permission\":\"EDIT_DRAFT\"}");

        result.andExpect(status().isOk());
        assertEquals("EDIT_DRAFT", body(result).path("permission").asString());
        verify(users).revokeWeblogPermission(weblog, carol, WeblogPermission.ALL_ACTIONS);
        verify(users).grantWeblogPermission(weblog, carol, List.of("edit_draft"));
    }

    @Test
    void changeToTheLevelHeldChangesNothing() throws Exception {
        account("carol");
        members(holds("owen", "admin"), holds("carol", "post"));

        send(patch(URL + "/carol"), "{\"permission\":\"POST\"}").andExpect(status().isOk());

        verify(users, never()).revokeWeblogPermission(any(), any(), any());
        verify(tier.weblogger(), never()).flush();
    }

    @Test
    void changeRefusesALevelThatIsNotOneOfTheThree() throws Exception {
        account("carol");
        members(holds("owen", "admin"), holds("carol", "post"));

        code(send(patch(URL + "/carol"), "{\"permission\":\"-1\"}"), 400, "member.levelInvalid");
    }

    @Test
    void changeOfAnAccountThatHoldsNoRoleHereIsNotFound() throws Exception {
        account("carol");
        members(holds("owen", "admin"));

        code(send(patch(URL + "/carol"), "{\"permission\":\"POST\"}"), 404, "member.notFound");
        code(send(patch(URL + "/ghost"), "{\"permission\":\"POST\"}"), 404, "member.notFound");
    }

    @Test
    void nobodyLowersTheirOwnRole() throws Exception {
        members(holds("owen", "admin"), holds("amy", "admin"));
        account("amy");

        code(send(patch(URL + "/owen"), "{\"permission\":\"POST\"}"), 409, "member.selfChange");
        verify(users, never()).revokeWeblogPermission(any(), any(), any());
    }

    @Test
    void theOnlyAdminCannotBeLoweredByAnotherAccountEither() throws Exception {
        // An admin acting for a weblog whose only admin is somebody else: lowering that admin leaves none.
        User amy = account("amy");
        members(holds("amy", "admin"), holds("carol", "post"));
        owner = account("carol");

        code(send(patch(URL + "/amy"), "{\"permission\":\"POST\"}"), 409, "member.lastAdmin");
        verify(users, never()).revokeWeblogPermission(any(), eq(amy), any());
    }

    // --- remove

    @Test
    void removeTakesTheMembershipAway() throws Exception {
        User carol = account("carol");
        members(holds("owen", "admin"), holds("carol", "post"));

        send(delete(URL + "/carol"), null).andExpect(status().isNoContent());

        verify(users).revokeWeblogPermission(weblog, carol, WeblogPermission.ALL_ACTIONS);
        verify(tier.weblogger()).flush();
    }

    @Test
    void removeRefusesRemovingYourselfAndTheLastAdmin() throws Exception {
        members(holds("owen", "admin"), holds("carol", "post"));
        account("carol");

        code(send(delete(URL + "/owen"), null), 409, "member.selfChange");

        owner = account("carol");
        code(send(delete(URL + "/owen"), null), 409, "member.lastAdmin");
        verify(users, never()).revokeWeblogPermission(any(), any(), any());
    }

    @Test
    void removeOfANonMemberIsNotFound() throws Exception {
        members(holds("owen", "admin"));
        account("carol");

        code(send(delete(URL + "/carol"), null), 404, "member.notFound");
    }

    @Test
    void aChangeOfLevelIsCommitted() throws Exception {
        account("carol");
        members(holds("owen", "admin"), holds("carol", "post"));

        send(patch(URL + "/carol"), "{\"permission\":\"EDIT_DRAFT\"}").andExpect(status().isOk());

        verify(tier.weblogger()).flush();
    }
}
