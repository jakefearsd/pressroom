package com.wikantik.pressroom.ui.restapi.v1;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * A contributor reads what the entry editor shows them, over the API (owner
 * decisions final review, I3: editor parity). The editor lists the weblog's
 * categories and tags, and its image chooser ({@code
 * MediaFileImageChooserController}, EDIT_DRAFT) lists the media files and
 * directories, so a contributor's token reads those routes; every change to
 * them stays a publisher's (POST), as on the Categories and Media Files pages.
 *
 * <p>The recipe of {@code EntriesWriteApiDispatchTest}: a real
 * {@code DispatcherServlet} from the production {@link WebMvcConfig}, so
 * {@code PressroomHandlerInterceptor} and {@code ApiScopeInterceptor} decide
 * before the real handlers run. {@code dora} holds EDIT_DRAFT on {@code own}.
 */
class ContributorReadsDispatchTest {

    private static final String OWN = "/v1/weblogs/own";
    private static final List<String> READS = List.of(OWN + "/categories", OWN + "/tags", OWN + "/media",
            OWN + "/media/m1", OWN + "/media/directories");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger tier;

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
        CategoriesApi categoriesApi() {
            return new CategoriesApi();
        }

        @Bean
        CategoriesReadApi categoriesReadApi() {
            return new CategoriesReadApi();
        }

        @Bean
        MediaApi mediaApi() {
            return new MediaApi();
        }

        @Bean
        MediaReadApi mediaReadApi() {
            return new MediaReadApi();
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
        Mockito.reset(tier.weblogManager(), tier.userManager(), tier.weblogEntryManager(),
                tier.mediaFileManager());
        Weblog weblog = new Weblog();
        weblog.setId("weblog-own");
        weblog.setHandle("own");
        weblog.setName("Blog own");
        weblog.setActive(Boolean.TRUE);
        weblog.setSiteSpace(false);
        when(tier.weblogManager().getWeblogByHandle("own")).thenReturn(weblog);
        User dora = new User();
        dora.setUserName("dora");
        when(tier.userManager().getUserByUserName("dora")).thenReturn(dora);
        when(tier.userManager().checkPermission(any(), any())).thenAnswer(call -> {
            if (call.getArgument(0) instanceof GlobalPermission asked) {
                return new GlobalPermission(List.of(GlobalPermission.WEBLOG)).implies(asked);
            }
            if (call.getArgument(0) instanceof WeblogPermission asked) {
                return "own".equals(asked.getObjectId())
                        && new WeblogPermission(weblog, List.of(WeblogPermission.EDIT_DRAFT)).implies(asked);
            }
            return false;
        });

        WeblogCategory travel = new WeblogCategory(weblog, "Travel", null, null);
        travel.setId("c1");
        when(tier.weblogEntryManager().getWeblogCategories(weblog)).thenReturn(List.of(travel));
        when(tier.weblogEntryManager().getWeblogCategory("c1")).thenReturn(travel);
        when(tier.weblogEntryManager().getTags(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new TagStat()));

        MediaFileDirectory images = new MediaFileDirectory(weblog, "images", null);
        images.setId("d1");
        images.setMediaFiles(new HashSet<>());
        MediaFile photo = new MediaFile();
        photo.setId("m1");
        photo.setName("photo.jpg");
        photo.setDirectory(images);
        images.getMediaFiles().add(photo);
        when(tier.mediaFileManager().searchMediaFiles(any(), any())).thenReturn(List.of(photo));
        when(tier.mediaFileManager().getMediaFile("m1")).thenReturn(photo);
        when(tier.mediaFileManager().getMediaFileDirectory("d1")).thenReturn(images);
        when(tier.mediaFileManager().getMediaFileDirectories(weblog)).thenReturn(List.of(images));
        when(tier.urlStrategy().getMediaFileURL(any(), any(), anyBoolean())).thenReturn("https://example.invalid/m1");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(ApiToken.Role role) {
        var auth = new UsernamePasswordAuthenticationToken("dora", null, List.of());
        auth.setDetails(new ApiPrincipal("dora", null, role, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static MvcResult send(String method, String path, String body) throws Exception {
        int query = path.indexOf('?');
        var builder = request(HttpMethod.valueOf(method), "/api" + path).servletPath("/api")
                .pathInfo(query < 0 ? path : path.substring(0, query));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ REST

    @Test
    void aContributorsPostTokenReadsWhatTheEditorShows() throws Exception {
        signIn(ApiToken.Role.POST);

        for (String path : READS) {
            MvcResult result = send("GET", path, null);
            assertEquals(200, result.getResponse().getStatus(), path + ": " + result.getResponse().getContentAsString());
        }
        assertEquals("Travel", body(send("GET", OWN + "/categories", null)).get(0).get("name").asString());
        assertEquals("m1", body(send("GET", OWN + "/media", null)).get("items").get(0).get("id").asString());
        assertEquals("photo.jpg", body(send("GET", OWN + "/media/m1", null)).get("name").asString());
        assertEquals("photo.jpg", body(send("GET", OWN + "/media?directoryId=d1", null))
                .get("items").get(0).get("name").asString());
    }

    @Test
    void aContributorsReadTokenReadsThemToo() throws Exception {
        signIn(ApiToken.Role.READ);

        for (String path : READS) {
            assertEquals(200, send("GET", path, null).getResponse().getStatus(), path);
        }
    }

    /** Changing categories and media stays a publisher's, as on the JSP pages: the interceptor refuses. */
    @Test
    void aContributorStillChangesNoCategoryOrMedia() throws Exception {
        signIn(ApiToken.Role.POST);

        List<String[]> writes = List.of(
                new String[] {"POST", OWN + "/categories", "{\"name\":\"New\"}"},
                new String[] {"PATCH", OWN + "/categories/c1", "{\"name\":\"Renamed\"}"},
                new String[] {"DELETE", OWN + "/categories/c1", null},
                new String[] {"POST", OWN + "/media/imports", "{\"url\":\"https://example.invalid/a.jpg\"}"},
                new String[] {"PATCH", OWN + "/media/m1", "{\"altText\":\"A\"}"},
                new String[] {"DELETE", OWN + "/media/m1", null},
                new String[] {"POST", OWN + "/media/directories", "{\"name\":\"more\"}"});
        for (String[] write : writes) {
            MvcResult result = send(write[0], write[1], write[2]);
            assertEquals(403, result.getResponse().getStatus(), write[0] + " " + write[1]);
            assertEquals("auth.noPermission", body(result).get("code").asString(), write[0] + " " + write[1]);
        }
        verify(tier.weblogEntryManager(), never()).saveWeblogCategory(any());
        verify(tier.mediaFileManager(), never()).updateMediaFile(any(), any());
        verify(tier.mediaFileManager(), never()).removeMediaFile(any(), any());
    }

    // ------------------------------------------------------------ MCP

    private static CallToolResult tool(String name, String action, Map<String, Object> args) {
        Map<String, Object> all = new HashMap<>(args);
        all.put("action", action);
        all.put("weblog", "own");
        ResolvedTool resolved = TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        McpToolExecutor executor = new McpToolExecutor((mcpRequest, call) -> dispatch(call));
        return executor.call(new MockHttpServletRequest("POST", "/api/mcp"), resolved, all);
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

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void mcpReadActionsAreAContributorsToo() {
        signIn(ApiToken.Role.POST);

        for (String[] call : List.of(new String[] {"categories", "list"}, new String[] {"categories", "list_tags"},
                new String[] {"media", "list"}, new String[] {"media", "list_directories"})) {
            CallToolResult result = tool(call[0], call[1], Map.of());
            assertFalse(result.isError(), call[0] + " " + call[1] + ": " + text(result));
        }
        CallToolResult one = tool("media", "get", Map.of("id", "m1"));
        assertFalse(one.isError(), text(one));
        assertTrue(text(one).contains("photo.jpg"), text(one));

        CallToolResult refused = tool("categories", "create", Map.of("name", "New"));
        assertTrue(refused.isError());
        assertTrue(text(refused).contains("auth.noPermission"), text(refused));
    }
}
