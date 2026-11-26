package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.testing.AdminLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code /v1/admin/agents} (SA-18, session only): what {@code AgentController} does for the New agent form,
 * the agent page's Details form, its Enable or Disable button and its token form, through the shared
 * {@code AgentAccounts} and {@code TokenOps}. Who may call the routes (a session) is the interceptors' and is
 * swept elsewhere; these run the handlers, each audit line included.
 */
class AgentsApiTest {

    private static final String URL = "/v1/admin/agents";

    private SessionOnlyApiSupport support;
    private UserManager users;
    private ApiTokenManager tokens;
    private User bot;

    @BeforeEach
    void setUp() throws Exception {
        support = new SessionOnlyApiSupport(new AgentsApi());
        users = support.tier.userManager();
        tokens = support.tier.apiTokenManager();
        bot = agent("bot");
    }

    @AfterEach
    void tearDown() {
        support.detach();
    }

    private User agent(String userName) throws Exception {
        User user = new User();
        user.setId("id-" + userName);
        user.setUserName(userName);
        user.setScreenName(userName);
        user.setEmailAddress(userName + "@agents.invalid");
        user.setKind(User.Kind.AGENT);
        user.setEnabled(Boolean.TRUE);
        when(users.getUserByUserName(userName, null)).thenReturn(user);
        return user;
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
            String body) throws Exception {
        return support.send(b, body);
    }

    private static void refused(ResultActions r, int status, String code) throws Exception {
        SessionOnlyApiSupport.refused(r, status, code);
    }

    private static JsonNode body(ResultActions r) throws Exception {
        return SessionOnlyApiSupport.body(r);
    }

    private static int status(ResultActions r) {
        return SessionOnlyApiSupport.status(r);
    }

    private ApiToken token(String id, User owner) {
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setUser(owner);
        token.setLabel("ci");
        token.setScopeRole(ApiToken.Role.POST);
        token.setCreated(new Timestamp(1_000_000L));
        return token;
    }

    // --- list

    @Test
    void listPagesTheAgentsAndFiltersByEnabledAndText() throws Exception {
        User off = agent("off");
        off.setEnabled(Boolean.FALSE);
        when(users.getAccounts(User.Kind.AGENT, "bo")).thenReturn(List.of(bot, off));
        when(users.checkPermission(any(GlobalPermission.class), eq(bot))).thenReturn(true);

        JsonNode page = body(send(get(URL + "?q=bo&enabled=true&limit=1"), null));

        assertEquals(1, page.path("items").size());
        assertEquals("bot", page.path("items").path(0).path("userName").asString());
        assertTrue(page.path("items").path(0).path("siteAdmin").asBoolean());
        assertFalse(page.path("hasMore").asBoolean());
        assertTrue(page.path("items").path(0).path("tokens").isMissingNode());
    }

    @Test
    void listReportsMoreAndRefusesABadRange() throws Exception {
        User second = agent("second");
        when(users.getAccounts(User.Kind.AGENT, null)).thenReturn(List.of(bot, second));

        assertTrue(body(send(get(URL + "?limit=1"), null)).path("hasMore").asBoolean());
        refused(send(get(URL + "?limit=-1"), null), 400, "paging.invalidRange");
    }

    // --- create

    @Test
    void createMakesAnEnabledAgentAndWritesTheAuditLines() throws Exception {
        when(users.getUserByUserName("robo", null)).thenReturn(null);
        when(users.getUserByUserName("robo")).thenReturn(null);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            ResultActions result = send(post(URL),
                    "{\"userName\":\"robo\",\"name\":\" Robo \",\"notes\":\"seo\",\"siteAdmin\":true}");

            assertEquals(201, status(result), result.andReturn().getResponse().getContentAsString());
            ArgumentCaptor<User> added = ArgumentCaptor.forClass(User.class);
            verify(users).addAgent(added.capture(), eq(true));
            assertEquals("Robo", added.getValue().getScreenName());
            assertEquals("Robo", added.getValue().getFullName());
            assertEquals("seo", added.getValue().getNotes());
            assertEquals(null, added.getValue().getEmailAddress());
            verify(support.tier.weblogger()).flush();
            assertEquals(List.of("agent.created agent=robo siteAdmin=true by=owen",
                    "siteAdmin.granted account=robo by=owen"), admin.lines());
        }
    }

    @Test
    void createNamesEveryBrokenField() throws Exception {
        agent("taken");
        ResultActions result = send(post(URL), "{\"userName\":\"taken\",\"name\":\"\",\"emailAddress\":\"nope\","
                + "\"notes\":\"" + "n".repeat(5000) + "\"}");

        assertEquals(409 == status(result) ? 409 : 400, status(result));
        refused(send(post(URL), "{\"userName\":\"bad name!\",\"name\":\"\",\"emailAddress\":\"nope\"}"), 400,
                "user.nameHasDisallowedCharacters");
        ResultActions fields = send(post(URL), "{\"userName\":\"fresh\",\"name\":\"  \",\"emailAddress\":\"nope\","
                + "\"notes\":\"" + "n".repeat(5000) + "\"}");
        assertEquals(400, status(fields));
        JsonNode errors = body(fields).path("errors");
        assertEquals("name", errors.path(0).path("field").asString());
        assertEquals("field.blank", errors.path(0).path("code").asString());
        assertEquals("emailAddress", errors.path(1).path("field").asString());
        assertEquals("notes", errors.path(2).path("field").asString());
        assertEquals("field.tooLong", errors.path(2).path("code").asString());
        verify(users, never()).addAgent(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void createRefusesATakenUserNameAs409() throws Exception {
        agent("taken");

        refused(send(post(URL), "{\"userName\":\"taken\",\"name\":\"T\"}"), 409, "user.nameInUse");
        verify(users, never()).addAgent(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aTakenCheckThatCannotRunRefusesTheCreate() throws Exception {
        when(users.getUserByUserName("robo", null)).thenThrow(new WebloggerException("store down"));

        assertEquals(500, status(send(post(URL), "{\"userName\":\"robo\",\"name\":\"R\"}")));
        verify(users, never()).addAgent(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // --- get

    @Test
    void getShowsTheAgentItsRoleAndItsLiveTokens() throws Exception {
        when(users.checkPermission(any(GlobalPermission.class), eq(bot))).thenReturn(false);
        when(tokens.getLiveTokens(bot)).thenReturn(List.of(token("t1", bot)));

        JsonNode view = body(send(get(URL + "/bot"), null));

        assertEquals("bot", view.path("userName").asString());
        assertFalse(view.path("siteAdmin").asBoolean());
        assertTrue(view.path("enabled").asBoolean());
        assertEquals("t1", view.path("tokens").path(0).path("id").asString());
    }

    @Test
    void aPersonOrAnUnknownNameIsNoSuchAgent() throws Exception {
        User person = agent("pat");
        person.setKind(User.Kind.PERSON);

        refused(send(get(URL + "/pat"), null), 404, "agent.notFound");
        refused(send(get(URL + "/ghost"), null), 404, "agent.notFound");
        refused(send(patch(URL + "/pat"), "{\"name\":\"x\"}"), 404, "agent.notFound");
        refused(send(post(URL + "/pat/tokens"), "{\"label\":\"x\",\"role\":\"READ\"}"), 404, "agent.notFound");
        refused(send(delete(URL + "/pat/tokens/t1"), null), 404, "agent.notFound");
    }

    @Test
    void aRoleThatCannotBeReadFailsTheReadNeverShowsUnticked() throws Exception {
        when(users.checkPermission(any(GlobalPermission.class), eq(bot))).thenThrow(new WebloggerException("down"));

        assertEquals(500, status(send(get(URL + "/bot"), null)));
    }

    // --- patch

    @Test
    void patchSavesTheDetailsAndGrantsTheAdminRoleWithItsLine() throws Exception {
        when(users.hasRole("admin", bot)).thenReturn(false);
        when(users.checkPermission(any(GlobalPermission.class), eq(bot))).thenReturn(true);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            ResultActions result = send(patch(URL + "/bot"),
                    "{\"name\":\" Bot Two \",\"notes\":\"writes\",\"siteAdmin\":true}");

            assertEquals(200, status(result), result.andReturn().getResponse().getContentAsString());
            assertEquals("Bot Two", bot.getScreenName());
            assertEquals("Bot Two", bot.getFullName());
            assertEquals("writes", bot.getNotes());
            verify(users).saveUser(bot);
            verify(users).grantRole("admin", bot);
            assertEquals(List.of("siteAdmin.granted account=bot by=owen"), admin.lines());
        }
    }

    @Test
    void patchLeavesWhatItDoesNotNameAndAnEmptyNotesClears() throws Exception {
        bot.setNotes("old");
        when(users.hasRole("admin", bot)).thenReturn(true);
        when(users.checkPermission(any(GlobalPermission.class), eq(bot))).thenReturn(true);

        assertEquals(200, status(send(patch(URL + "/bot"), "{\"notes\":\"\"}")));

        assertEquals(null, bot.getNotes());
        assertEquals("bot", bot.getScreenName());
        verify(users, never()).revokeRole(any(), any());
        verify(users, never()).grantRole(any(), any());
    }

    @Test
    void patchRevokesTheAdminRoleAndDisablesWithItsLines() throws Exception {
        when(users.hasRole("admin", bot)).thenReturn(true);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            assertEquals(200, status(send(patch(URL + "/bot"), "{\"siteAdmin\":false,\"enabled\":false}")));

            verify(users).revokeRole("admin", bot);
            assertFalse(bot.getEnabled());
            assertEquals(List.of("siteAdmin.removed account=bot by=owen",
                    "account.disabled account=bot by=owen"), admin.lines());
        }
    }

    @Test
    void patchRefusesABlankOrTooLongNameBeforeAnythingIsSaved() throws Exception {
        refused(send(patch(URL + "/bot"), "{\"name\":\"  \"}"), 400, "field.blank");
        refused(send(patch(URL + "/bot"), "{\"name\":\"" + "x".repeat(256) + "\"}"), 400, "field.tooLong");
        verify(users, never()).saveUser(any());
        assertEquals("bot", bot.getScreenName());
    }

    // --- tokens

    @Test
    void mintIssuesATokenForTheAgentAndShowsTheSecretOnce() throws Exception {
        ApiToken persisted = token("t9", bot);
        when(tokens.issueToken(eq(bot), eq("ci"), eq(null), eq(ApiToken.Role.POST), eq(null), eq(support.admin)))
                .thenReturn(new ApiTokenManager.Issued("prs_secret", persisted));

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            ResultActions result = send(post(URL + "/bot/tokens"), "{\"label\":\" ci \",\"role\":\"post\"}");

            assertEquals(201, status(result), result.andReturn().getResponse().getContentAsString());
            assertEquals("prs_secret", body(result).path("token").asString());
            assertEquals("t9", body(result).path("info").path("id").asString());
            assertEquals(1, result.andReturn().getResponse().getContentAsString().split("prs_secret", -1).length - 1);
            verify(support.tier.weblogger()).flush();
            assertEquals(1, admin.lines().size());
            assertTrue(admin.lines().get(0).startsWith("token.minted id=t9 label=ci owner=bot role=POST"),
                    admin.lines().toString());
            assertFalse(admin.lines().get(0).contains("prs_secret"));
        }
    }

    @Test
    void mintPinsToAWeblogTheAgentIsAMemberOfAndAnyForASiteAdminAgent() throws Exception {
        Weblog blog = new Weblog();
        blog.setHandle("myblog");
        when(support.tier.weblogManager().getWeblogByHandle("myblog", null)).thenReturn(blog);
        ApiToken persisted = token("t9", bot);
        when(tokens.issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued("prs_secret", persisted));

        refused(send(post(URL + "/bot/tokens"), "{\"label\":\"ci\",\"role\":\"READ\",\"weblog\":\"myblog\"}"), 400,
                "token.weblogNotMember");

        when(users.getWeblogPermission(blog, bot)).thenReturn(new com.wikantik.pressroom.pojos.WeblogPermission(blog,
                List.of("post")));
        assertEquals(201, status(send(post(URL + "/bot/tokens"),
                "{\"label\":\"ci\",\"role\":\"READ\",\"weblog\":\"myblog\"}")));
        verify(tokens).issueToken(eq(bot), eq("ci"), eq("myblog"), eq(ApiToken.Role.READ), eq(null), eq(support.admin));
    }

    @Test
    void mintNamesTheBrokenFieldsAndMintsNothing() throws Exception {
        ResultActions result = send(post(URL + "/bot/tokens"), "{\"label\":\"\",\"role\":\"OWNER\","
                + "\"expiresAt\":\"2001-01-01T00:00:00Z\"}");

        assertEquals(400, status(result));
        JsonNode errors = body(result).path("errors");
        assertEquals("label", errors.path(0).path("field").asString());
        assertEquals("role", errors.path(1).path("field").asString());
        assertEquals("token.roleUnknown", errors.path(1).path("code").asString());
        assertEquals("expiresAt", errors.path(2).path("field").asString());
        verify(tokens, never()).issueToken(any(), anyString(), any(), any(), any(), any());

        refused(send(post(URL + "/bot/tokens"), "{\"label\":\"" + "l".repeat(256) + "\",\"role\":\"READ\"}"), 400,
                "field.tooLong");
    }

    @Test
    void mintAcceptsAFutureExpiry() throws Exception {
        ApiToken persisted = token("t9", bot);
        Instant when = Instant.now().plus(30, ChronoUnit.DAYS);
        org.mockito.Mockito.doReturn(new ApiTokenManager.Issued("prs_s", persisted)).when(tokens)
                .issueToken(eq(bot), eq("ci"), eq(null), eq(ApiToken.Role.ADMIN), eq(Timestamp.from(when)),
                        eq(support.admin));

        assertEquals(201, status(send(post(URL + "/bot/tokens"),
                "{\"label\":\"ci\",\"role\":\"ADMIN\",\"expiresAt\":\"" + when + "\"}")));
    }

    @Test
    void revokeRevokesOnlyTheAgentsOwnTokenAndLogsTheFirstTime() throws Exception {
        ApiToken mine = token("t1", bot);
        User other = agent("other");
        ApiToken theirs = token("t2", other);
        when(tokens.getToken("t1")).thenReturn(mine);
        when(tokens.getToken("t2")).thenReturn(theirs);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            assertEquals(204, status(send(delete(URL + "/bot/tokens/t1"), null)));
            verify(tokens).revoke(mine);
            assertEquals(1, admin.lines().size());
            assertTrue(admin.lines().get(0).startsWith("token.revoked id=t1"), admin.lines().toString());
        }
        refused(send(delete(URL + "/bot/tokens/t2"), null), 404, "token.notFound");
        refused(send(delete(URL + "/bot/tokens/nope"), null), 404, "token.notFound");
        verify(tokens, never()).revoke(theirs);
    }

    @Test
    void revokeOfAnAlreadyRevokedTokenSucceedsAndLogsNothing() throws Exception {
        ApiToken mine = token("t1", bot);
        mine.setRevokedAt(new Timestamp(2_000_000L));
        when(tokens.getToken("t1")).thenReturn(mine);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            assertEquals(204, status(send(delete(URL + "/bot/tokens/t1"), null)));
            assertEquals(List.of(), admin.lines());
        }
    }

    @Test
    void aRevokeThatTheStoreFailsIsAnErrorNeverNoSuchToken() throws Exception {
        ApiToken mine = token("t1", bot);
        when(tokens.getToken("t1")).thenReturn(mine);
        org.mockito.Mockito.doThrow(new WebloggerException("down")).when(tokens).revoke(mine);

        assertEquals(500, status(send(delete(URL + "/bot/tokens/t1"), null)));
    }
}
