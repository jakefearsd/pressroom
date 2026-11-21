package com.wikantik.pressroom.ui.restapi.v1;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.ui.restapi.mcp.ApiCall;
import com.wikantik.pressroom.ui.restapi.mcp.ApiReply;
import com.wikantik.pressroom.ui.restapi.mcp.ApiRouteTables;
import com.wikantik.pressroom.ui.restapi.mcp.McpToolCatalog;
import com.wikantik.pressroom.ui.restapi.mcp.McpToolExecutor;
import com.wikantik.pressroom.ui.restapi.mcp.ResolvedTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * A contributor's token writes entries through the real interceptors and the
 * real handlers (spec 2026-11-21 owner decisions, Task 3, criteria 2 to 6).
 * {@code ApiAuthorizationMatrixTest} stops at a sentinel before any handler
 * runs, so it can show that a contributor reaches the entry writes but not
 * what the write then saves; these cells carry the status decision, over
 * REST and through the MCP {@code entries} tool, one test per action family.
 *
 * <p>Dispatched through a real {@code DispatcherServlet} built from the
 * production {@link WebMvcConfig} (the recipe of {@code
 * WeblogSettingsApiDispatchTest}), so {@code PressroomHandlerInterceptor} and
 * {@code ApiScopeInterceptor} decide in their real order before the handler.
 * {@code dora} holds EDIT_DRAFT on {@code own} (a contributor), {@code abe}
 * POST (a publisher); both hold the ordinary global {@code weblog} role.
 */
class EntriesWriteApiDispatchTest {

    private static final Map<String, String> ROLES = Map.of("dora", WeblogPermission.EDIT_DRAFT,
            "abe", WeblogPermission.POST);
    private static final String ENTRIES = "/v1/weblogs/own/entries";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger tier;
    private static Weblog weblog;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        WebMvcConfig webMvcConfig(PressroomHandlerInterceptor pressroomHandlerInterceptor) {
            return new WebMvcConfig(pressroomHandlerInterceptor);
        }

        @Bean
        PressroomHandlerInterceptor pressroomHandlerInterceptor() {
            WebloggerProvider provider = mock(WebloggerProvider.class);
            when(provider.isBootstrapped()).thenReturn(true);
            return new PressroomHandlerInterceptor(provider, tier.weblogger());
        }

        @Bean
        EntriesWriteApi entriesWriteApi() {
            return new EntriesWriteApi();
        }

        @Bean
        EntriesTrashApi entriesTrashApi() {
            return new EntriesTrashApi();
        }

        @Bean
        RemoteImageFetcher remoteImageFetcher() {
            return mock(RemoteImageFetcher.class);
        }

        @Bean
        com.wikantik.pressroom.business.Weblogger weblogger() {
            return tier.weblogger();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void setUpContext() {
        tier = MockWeblogger.attached();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mockMvc = webAppContextSetup(context).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
        tier.detach();
    }

    @BeforeEach
    void stubTheSite() throws Exception {
        Mockito.reset(tier.weblogManager(), tier.userManager(), tier.weblogEntryManager());
        weblog = new Weblog();
        weblog.setId("weblog-own");
        weblog.setHandle("own");
        weblog.setName("Blog own");
        weblog.setActive(Boolean.TRUE);
        weblog.setTimeZone("UTC");
        weblog.setSiteSpace(false);
        when(tier.weblogManager().getWeblogByHandle("own")).thenReturn(weblog);
        for (String name : ROLES.keySet()) {
            User user = new User();
            user.setUserName(name);
            when(tier.userManager().getUserByUserName(name)).thenReturn(user);
        }
        when(tier.userManager().checkPermission(any(), any())).thenAnswer(call -> {
            String name = ((User) call.getArgument(1)).getUserName();
            if (call.getArgument(0) instanceof GlobalPermission asked) {
                return new GlobalPermission(List.of(GlobalPermission.WEBLOG)).implies(asked);
            }
            if (call.getArgument(0) instanceof WeblogPermission asked) {
                return "own".equals(asked.getObjectId())
                        && new WeblogPermission(weblog, List.of(ROLES.get(name))).implies(asked);
            }
            return false;
        });
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user, ApiToken.Role role) {
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        auth.setDetails(new ApiPrincipal(user, null, role, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static MvcResult send(String method, String path, String body) throws Exception {
        var builder = request(HttpMethod.valueOf(method), "/api" + path).servletPath("/api").pathInfo(path);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static WeblogEntry stored(String creator, PubStatus status) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(weblog);
        entry.setCreatorUserName(creator);
        entry.setTitle("Stored");
        entry.setText("Stored text.");
        entry.setStatus(status);
        when(tier.weblogEntryManager().getWeblogEntry("e1")).thenReturn(entry);
        return entry;
    }

    private static void storedRevisionOf(WeblogEntry entry) throws Exception {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId("r1");
        revision.setWeblogEntry(entry);
        revision.setTitle("Then");
        revision.setText("Then text.");
        when(tier.weblogEntryManager().getRevision("r1")).thenReturn(revision);
    }

    // ------------------------------------------------------------ REST

    @Test
    void aContributorsPostTokenCreatesADraft() throws Exception {
        signIn("dora", ApiToken.Role.POST);

        MvcResult result = send("POST", ENTRIES, "{\"title\":\"T\",\"text\":\"x\"}");

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("DRAFT", body(result).get("status").asString());
    }

    /** The cell the matrix cannot hold: past the interceptors, the handler decides the status. */
    @Test
    void aContributorCannotPublishTheirCreateIsSubmittedForReview() throws Exception {
        signIn("dora", ApiToken.Role.POST);

        MvcResult result = send("POST", ENTRIES, "{\"title\":\"T\",\"text\":\"x\",\"status\":\"PUBLISHED\"}");

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("PENDING", body(result).get("status").asString());
    }

    @Test
    void aPublishersTokenStillPublishes() throws Exception {
        signIn("abe", ApiToken.Role.POST);

        MvcResult result = send("POST", ENTRIES, "{\"title\":\"T\",\"text\":\"x\",\"status\":\"PUBLISHED\"}");

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("PUBLISHED", body(result).get("status").asString());
    }

    @Test
    void aContributorsPatchOfAPublishedEntryIsForbiddenWithItsCode() throws Exception {
        signIn("dora", ApiToken.Role.POST);
        stored("dora", PubStatus.PUBLISHED);

        MvcResult result = send("PATCH", ENTRIES + "/e1", "{\"text\":\"Changed\"}");

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("entry.notChangeable", body(result).get("code").asString());
        verify(tier.weblogEntryManager(), never()).saveWeblogEntry(any(), any(), any());
    }

    /** The token is the ceiling: a READ token writes nothing, whoever holds it. */
    @Test
    void aContributorsReadTokenWritesNothing() throws Exception {
        signIn("dora", ApiToken.Role.READ);
        stored("dora", PubStatus.DRAFT);

        for (String[] write : List.of(new String[] {"POST", ENTRIES, "{\"title\":\"T\",\"text\":\"x\"}"},
                new String[] {"PATCH", ENTRIES + "/e1", "{\"text\":\"x\"}"},
                new String[] {"DELETE", ENTRIES + "/e1", null},
                new String[] {"POST", ENTRIES + "/preview", "{\"text\":\"x\"}"})) {
            MvcResult result = send(write[0], write[1], write[2]);
            assertEquals(403, result.getResponse().getStatus(), write[0] + " " + write[1]);
            assertEquals("token.readOnly", body(result).get("code").asString(), write[0] + " " + write[1]);
        }
        verify(tier.weblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** As on the JSP Trash page, which takes POST: restore and delete-forever stay a publisher's. */
    @Test
    void aContributorCannotRestoreOrDeleteForeverEvenTheirOwn() throws Exception {
        signIn("dora", ApiToken.Role.POST);
        stored("dora", PubStatus.TRASHED);

        for (String route : List.of("/e1/restore", "/e1/delete-forever")) {
            MvcResult result = send("POST", ENTRIES + route, null);
            assertEquals(403, result.getResponse().getStatus(), route);
            assertEquals("auth.noPermission", body(result).get("code").asString(), route);
        }
        verify(tier.weblogEntryManager(), never()).restoreWeblogEntry(any());
        verify(tier.weblogEntryManager(), never()).removeWeblogEntry(any());
    }

    // ------------------------------------------------------------ MCP: one test per action family

    private static CallToolResult tool(String action, Map<String, Object> args) {
        Map<String, Object> all = new HashMap<>(args);
        all.put("action", action);
        all.put("weblog", "own");
        ResolvedTool entries = TOOLS.stream().filter(t -> t.name().equals("entries")).findFirst().orElseThrow();
        McpToolExecutor executor = new McpToolExecutor((mcpRequest, call) -> dispatch(call));
        return executor.call(new MockHttpServletRequest("POST", "/api/mcp"), entries, all);
    }

    private static ApiReply dispatch(ApiCall call) {
        try {
            MvcResult result = send(call.method(), call.path(), call.jsonBody());
            return new ApiReply(result.getResponse().getStatus(), result.getResponse().getContentType(),
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode reply(CallToolResult result) {
        return JSON.readTree(((TextContent) result.content().get(0)).text());
    }

    @Test
    void mcpCreateAndUpdateSubmitAContributorsPublishForReview() throws Exception {
        signIn("dora", ApiToken.Role.POST);
        CallToolResult created = tool("create", Map.of("title", "T", "text", "x", "status", "PUBLISHED"));
        assertFalse(created.isError(), reply(created).toString());
        assertEquals("PENDING", reply(created).get("status").asString());

        stored("dora", PubStatus.DRAFT);
        CallToolResult updated = tool("update", Map.of("id", "e1", "status", "SCHEDULED",
                "pubTime", "2099-01-01T09:00"));
        assertFalse(updated.isError(), reply(updated).toString());
        assertEquals("PENDING", reply(updated).get("status").asString());
    }

    @Test
    void mcpTrashTakesTheEditorsRule() throws Exception {
        signIn("dora", ApiToken.Role.POST);
        stored("abe", PubStatus.DRAFT);

        CallToolResult refused = tool("trash", Map.of("id", "e1"));

        assertTrue(refused.isError());
        assertEquals("entry.notChangeable", reply(refused).get("code").asString());
        verify(tier.weblogEntryManager(), never()).trashWeblogEntry(any());
    }

    @Test
    void mcpRestoreAndDeleteForeverStayAPublishers() throws Exception {
        signIn("dora", ApiToken.Role.POST);
        stored("dora", PubStatus.TRASHED);

        for (String action : List.of("restore", "delete_forever")) {
            CallToolResult refused = tool(action, Map.of("id", "e1"));
            assertTrue(refused.isError(), action);
            assertEquals(403, reply(refused).get("status").asInt(), action);
        }
    }

    @Test
    void mcpRevisionActionsTakeTheEditorsRule() throws Exception {
        signIn("dora", ApiToken.Role.POST);
        storedRevisionOf(stored("dora", PubStatus.PUBLISHED));

        for (String action : List.of("restore_revision", "keep_revision", "unkeep_revision")) {
            CallToolResult refused = tool(action, Map.of("id", "e1", "revisionId", "r1"));
            assertTrue(refused.isError(), action);
            assertEquals("entry.notChangeable", reply(refused).get("code").asString(), action);
        }
        verify(tier.weblogEntryManager(), never()).restoreRevision(any(), any(), any());
        verify(tier.weblogEntryManager(), never()).setRevisionKept(any(), anyBoolean());
    }

    @Test
    void mcpPreviewIsAContributorsToo() throws Exception {
        signIn("dora", ApiToken.Role.POST);

        CallToolResult preview = tool("preview", Map.of("text", "**Words**"));

        assertFalse(preview.isError(), reply(preview).toString());
        assertTrue(reply(preview).get("html").asString().contains("<strong>Words</strong>"));
    }
}
