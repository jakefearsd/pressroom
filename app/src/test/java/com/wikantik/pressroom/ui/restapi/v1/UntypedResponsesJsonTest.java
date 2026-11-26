package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The exact JSON of the five responses that answered a {@code Map} before
 * they answered records (spec 2026-11-20-api-contract-design, Task 4,
 * criterion 5): {@code GET /v1/ping}, {@code GET /v1/me} for a token caller,
 * a pinned token and a non-token caller, and the three maintenance actions.
 *
 * <p>Characterisation tests: written first against the old {@code Map} code
 * and passing there, then left unchanged across the swap to records. They
 * serialise through the message converters Spring Boot configures, as the
 * application does, not a standalone MockMvc's defaults.
 *
 * <p>On the old code a part built with {@code Map.of} had no defined member
 * order (the maintenance actions; {@code /me}'s {@code tokenScope} and each
 * {@code weblogs} item: it changed from one JVM to the next), so those were
 * compared member by member, and only the top level of {@code /me} (a
 * {@code LinkedHashMap}) by order. The records fix every order as the old
 * code declared it ({@code action}, {@code weblog}; {@code weblog}, {@code
 * role}; {@code handle}, {@code name}, {@code permission}), so the test now
 * also compares the exact string.
 */
class UntypedResponsesJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static Weblogger weblogger;

    private User user;

    @Configuration
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            WebMvcAutoConfiguration.class})
    static class Config {
        @Bean
        Weblogger weblogger() {
            return mock(Weblogger.class);
        }

        @Bean
        MetaApi metaApi() {
            return new MetaApi();
        }

        @Bean
        AdminActionsApi adminActionsApi() {
            return new AdminActionsApi();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        weblogger = context.getBean(Weblogger.class);
        mockMvc = webAppContextSetup(context).build();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        Mockito.reset(weblogger);
        UserManager userManager = mock(UserManager.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getIndexManager()).thenReturn(mock(IndexManager.class));
        when(weblogger.getMediaFileManager()).thenReturn(mock(MediaFileManager.class));

        user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice A.");
        when(userManager.checkPermission(any(), eq(user))).thenReturn(false);
        when(userManager.checkPermission(argThat(p -> p instanceof GlobalPermission g
                && g.getActionsAsList().equals(List.of(GlobalPermission.ADMIN))), eq(user))).thenReturn(true);

        Weblog travels = weblog("travels", "Travels");
        Weblog food = weblog("food", null);
        when(weblogManager.getWeblogByHandle("travels")).thenReturn(travels);
        when(weblogManager.getWeblogByHandle("food")).thenReturn(food);
        when(userManager.getWeblogPermissions(user)).thenReturn(List.of(
                new WeblogPermission(travels, user, List.of(WeblogPermission.ADMIN)),
                new WeblogPermission(food, user, List.of(WeblogPermission.POST))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ping() throws Exception {
        assertSameJson("{\"status\":\"ok\"}", body(get("/v1/ping"), 200));
    }

    @Test
    void meForAnUnpinnedToken() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", null, ApiToken.Role.POST, "token-1"));

        assertSameJson("{\"userName\":\"alice\",\"screenName\":\"Alice A.\",\"kind\":\"PERSON\",\"globalAdmin\":true,"
                + "\"tokenScope\":{\"weblog\":\"\",\"role\":\"POST\"},"
                + "\"weblogs\":[{\"handle\":\"food\",\"name\":\"\",\"permission\":\"POST\"},"
                + "{\"handle\":\"travels\",\"name\":\"Travels\",\"permission\":\"ADMIN\"}]}",
                body(get("/v1/me").requestAttr("authenticatedUser", user), 200));
    }

    @Test
    void meForAPinnedToken() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", "travels", ApiToken.Role.ADMIN, "token-1"));

        assertSameJson("{\"userName\":\"alice\",\"screenName\":\"Alice A.\",\"kind\":\"PERSON\",\"globalAdmin\":true,"
                + "\"tokenScope\":{\"weblog\":\"travels\",\"role\":\"ADMIN\"},"
                + "\"weblogs\":[{\"handle\":\"travels\",\"name\":\"Travels\",\"permission\":\"ADMIN\"}]}",
                body(get("/v1/me").requestAttr("authenticatedUser", user), 200));
    }

    /** No token, so no ceiling: {@code tokenScope} is an empty object, never null or absent. */
    @Test
    void meForANonTokenCaller() throws Exception {
        user.setKind(User.Kind.AGENT);

        assertSameJson("{\"userName\":\"alice\",\"screenName\":\"Alice A.\",\"kind\":\"AGENT\",\"globalAdmin\":true,"
                + "\"tokenScope\":{},"
                + "\"weblogs\":[{\"handle\":\"food\",\"name\":\"\",\"permission\":\"POST\"},"
                + "{\"handle\":\"travels\",\"name\":\"Travels\",\"permission\":\"ADMIN\"}]}",
                body(get("/v1/me").requestAttr("authenticatedUser", user), 200));
    }

    /**
     * An account with no display name: {@code screenName} is present and null,
     * never dropped and never an empty string (the member is optional in the
     * document, so a client reads null as "none"). Characterisation test:
     * written after the swap to records and passing at once.
     */
    @Test
    void meForAnAccountWithNoScreenName() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", "travels", ApiToken.Role.ADMIN, "token-1"));
        user.setScreenName(null);

        assertSameJson("{\"userName\":\"alice\",\"screenName\":null,\"kind\":\"PERSON\",\"globalAdmin\":true,"
                + "\"tokenScope\":{\"weblog\":\"travels\",\"role\":\"ADMIN\"},"
                + "\"weblogs\":[{\"handle\":\"travels\",\"name\":\"Travels\",\"permission\":\"ADMIN\"}]}",
                body(get("/v1/me").requestAttr("authenticatedUser", user), 200));
    }

    @Test
    void theMaintenanceActions() throws Exception {
        Weblog weblog = weblog("myblog", "My blog");
        for (String action : List.of("flush-cache", "rebuild-index", "regenerate-renditions")) {
            String count = "regenerate-renditions".equals(action) ? ",\"count\":0" : "";
            assertSameJson("{\"action\":\"" + action + "\",\"weblog\":\"myblog\"" + count + "}",
                    body(post("/v1/admin/weblogs/myblog/actions/" + action).requestAttr("actionWeblog", weblog),
                            202));
        }
    }

    /**
     * The same members with the same values, the top-level members in the
     * same order, and then the exact string, every order fixed.
     */
    private static void assertSameJson(String expected, String actual) {
        JsonNode want = MAPPER.readTree(expected);
        JsonNode got = MAPPER.readTree(actual);
        assertEquals(want, got, actual);
        assertEquals(want.propertyNames().toString(), got.propertyNames().toString(), actual);
        assertEquals(expected, actual);
    }

    private static String body(org.springframework.test.web.servlet.RequestBuilder request, int status)
            throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }

    private static Weblog weblog(String handle, String name) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setName(name);
        return weblog;
    }

    private static void authenticateWithToken(ApiPrincipal apiPrincipal) {
        var auth = new UsernamePasswordAuthenticationToken(apiPrincipal.userName(), null, List.of());
        auth.setDetails(apiPrincipal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
