package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * A weblog's owner reads and changes its settings over the API, as on the
 * Weblog Settings and Theme pages: {@code GET}/{@code PATCH
 * /v1/weblogs/{handle}} take {@code WeblogPermission.ADMIN} on that weblog
 * (spec 2026-11-20 API repairs, task 3), while the list stays site-admin only
 * and every route keeps the {@code ADMIN}-role token requirement.
 *
 * <p>Dispatched through a real {@code DispatcherServlet} built from the
 * production {@link WebMvcConfig}, so {@code PressroomHandlerInterceptor} and
 * {@code ApiScopeInterceptor} decide in their real order, the recipe of
 * {@code WeblogParameterDispatchTest}. The permission stub answers with the
 * real {@code implies} ladders over the grants below.
 *
 * <p>User {@code owen} holds the site's ordinary {@code weblog} global role
 * and: {@code ADMIN} on {@code own} and {@code other}, {@code POST} on
 * {@code member}, nothing on {@code foreign}. User {@code root} is a site
 * admin. {@code site} is the site space.
 */
class WeblogSettingsApiDispatchTest {

    private static final Map<String, String> OWEN_ROLES = Map.of(
            "own", WeblogPermission.ADMIN,
            "other", WeblogPermission.ADMIN,
            "member", WeblogPermission.POST);

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger weblogger;

    /** This test's weblogs by handle, rebuilt for each test. */
    private static final Map<String, Weblog> WEBLOGS = new java.util.HashMap<>();

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
            return new PressroomHandlerInterceptor(provider, weblogger.weblogger());
        }

        @Bean
        WeblogsApi weblogsApi() {
            return new WeblogsApi();
        }

        @Bean
        WeblogSettingsApi weblogSettingsApi() {
            return new WeblogSettingsApi();
        }

        @Bean
        WeblogReadApi weblogReadApi() {
            return new WeblogReadApi();
        }

        @Bean
        com.wikantik.pressroom.business.Weblogger weblogger() {
            return weblogger.weblogger();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void setUpContext() {
        weblogger = MockWeblogger.attached();
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
        weblogger.detach();
    }

    @BeforeEach
    void stubTheSite() throws Exception {
        Mockito.reset(weblogger.weblogManager(), weblogger.userManager(), weblogger.themeManager());
        for (String handle : List.of("own", "other", "member", "foreign", "site")) {
            Weblog weblog = new Weblog();
            weblog.setId("weblog-" + handle);
            weblog.setHandle(handle);
            weblog.setName("Blog " + handle);
            weblog.setActive(Boolean.TRUE);
            weblog.setEntryDisplayCount(15);
            weblog.setEditorTheme("journal");
            weblog.setSiteSpace("site".equals(handle));
            WEBLOGS.put(handle, weblog);
            when(weblogger.weblogManager().getWeblogByHandle(handle)).thenReturn(weblog);
        }
        for (String name : List.of("owen", "root")) {
            User user = new User();
            user.setUserName(name);
            when(weblogger.userManager().getUserByUserName(name)).thenReturn(user);
        }
        when(weblogger.userManager().checkPermission(any(), any())).thenAnswer(call -> {
            User user = call.getArgument(1);
            if ("root".equals(user.getUserName())) {
                return true;
            }
            if (call.getArgument(0) instanceof GlobalPermission asked) {
                return new GlobalPermission(List.of(GlobalPermission.WEBLOG)).implies(asked);
            }
            if (call.getArgument(0) instanceof WeblogPermission asked) {
                String level = OWEN_ROLES.get(asked.getObjectId());
                return level != null
                        && new WeblogPermission(WEBLOGS.get(asked.getObjectId()), List.of(level)).implies(asked);
            }
            return false;
        });
        List<SharedTheme> enabled = new java.util.ArrayList<>();
        for (String id : List.of("journal", "travel")) {
            SharedTheme theme = mock(SharedTheme.class);
            when(theme.getId()).thenReturn(id);
            enabled.add(theme);
        }
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(enabled);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user, String pin, ApiToken.Role role) {
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        auth.setDetails(new ApiPrincipal(user, pin, role, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static ResultActions getWeblog(String handle) throws Exception {
        return getWeblog(handle, "");
    }

    private static ResultActions getWeblog(String handle, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/weblogs/" + handle + query)
                .servletPath("/api").pathInfo("/v1/weblogs/" + handle));
    }

    /** PATCH with the site settings the shared settings rules read, and the cache expiry stubbed. */
    private static ResultActions patchWeblog(String handle, String json) throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(WebloggerRuntimeConfig.class);
             MockedStatic<CacheManager> _ = mockStatic(CacheManager.class)) {
            config.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(30);
            config.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn("https://blog.example.com");
            return mockMvc.perform(patch("/api/v1/weblogs/" + handle)
                    .servletPath("/api").pathInfo("/v1/weblogs/" + handle)
                    .contentType(MediaType.APPLICATION_JSON).content(json));
        }
    }

    /** One string field of the JSON response. */
    private static org.springframework.test.web.servlet.ResultMatcher field(String name, String expected) {
        return result -> assertEquals(expected, new tools.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).path(name).asString(), name);
    }

    private static Weblog weblog(String handle) {
        return WEBLOGS.get(handle);
    }

    private static void assertNothingSaved() throws Exception {
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    // --- AC1: the owner reads and changes the blog, pinned or not -----------------------------

    @Test
    void anOwnerReadsTheirWeblogWithAnUnpinnedAdminToken() throws Exception {
        signIn("owen", null, ApiToken.Role.ADMIN);

        getWeblog("own").andExpect(status().isOk()).andExpect(field("handle", "own"));
    }

    @Test
    void anOwnerChangesSettingsAndTheSharedThemeWithAnUnpinnedAdminToken() throws Exception {
        signIn("owen", null, ApiToken.Role.ADMIN);

        patchWeblog("own", "{\"name\":\"Renamed\",\"theme\":\"travel\"}")
                .andExpect(status().isOk())
                .andExpect(field("name", "Renamed"))
                .andExpect(field("theme", "travel"));

        assertEquals("Renamed", weblog("own").getName());
        assertEquals("travel", weblog("own").getEditorTheme());
        verify(weblogger.weblogManager()).saveWeblog(weblog("own"));
    }

    @Test
    void anOwnerReadsAndChangesTheirWeblogWithAnAdminTokenPinnedToIt() throws Exception {
        signIn("owen", "own", ApiToken.Role.ADMIN);

        getWeblog("own").andExpect(status().isOk()).andExpect(field("handle", "own"));
        patchWeblog("own", "{\"tagline\":\"Pinned\",\"theme\":\"travel\"}")
                .andExpect(status().isOk())
                .andExpect(field("tagline", "Pinned"))
                .andExpect(field("theme", "travel"));
        verify(weblogger.weblogManager()).saveWeblog(weblog("own"));
    }

    // --- AC2: no role is 404; a member below ADMIN reads (the editor needs it) but may not change -----

    @Test
    void aWeblogTheOwnerHoldsNoRoleOnIsNotFound() throws Exception {
        signIn("owen", null, ApiToken.Role.ADMIN);

        getWeblog("foreign").andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(field("detail", "No such weblog."));
        patchWeblog("foreign", "{\"name\":\"Taken\"}").andExpect(status().isNotFound());
        assertNothingSaved();
    }

    @Test
    void aMemberBelowAdminReadsTheWeblogButMayNotChangeIt() throws Exception {
        signIn("owen", null, ApiToken.Role.ADMIN);

        // The admin frame and the entry editor read it as any member; MembersIT found the editor blank on a 403.
        getWeblog("member").andExpect(status().isOk()).andExpect(field("handle", "member"));
        patchWeblog("member", "{\"name\":\"Taken\"}").andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertNothingSaved();
    }

    // --- AC3: a pin elsewhere is 404, whatever the owner's role there ------------------------

    @Test
    void aTokenPinnedToAnotherWeblogGetsNotFoundEvenWhereItsOwnerIsAdmin() throws Exception {
        signIn("owen", "own", ApiToken.Role.ADMIN);

        getWeblog("other").andExpect(status().isNotFound());
        patchWeblog("other", "{\"name\":\"Taken\"}").andExpect(status().isNotFound());
        getWeblog("member").andExpect(status().isNotFound());
        getWeblog("foreign").andExpect(status().isNotFound());
        assertNothingSaved();
    }

    // --- AC4: the token role is unchanged -----------------------------------------------------

    @Test
    void aReadOrPostTokenIsRefusedThePatch() throws Exception {
        for (ApiToken.Role role : List.of(ApiToken.Role.READ, ApiToken.Role.POST)) {
            signIn("owen", null, role);

            patchWeblog("own", "{\"name\":\"Renamed\"}")
                    .andExpect(status().isForbidden())
                    .andExpect(field("detail", "This token is not scoped for administration."));
        }
        assertNothingSaved();
    }

    // --- AC5: the list stays site-admin only --------------------------------------------------

    @Test
    void theListStaysSiteAdminOnly() throws Exception {
        signIn("owen", null, ApiToken.Role.ADMIN);
        mockMvc.perform(get("/api/v1/weblogs").servletPath("/api").pathInfo("/v1/weblogs"))
                .andExpect(status().isForbidden());
        verify(weblogger.weblogManager(), never()).getWeblogs(any(), any(), any(), any(), Mockito.anyInt(),
                Mockito.anyInt());

        signIn("root", null, ApiToken.Role.ADMIN);
        mockMvc.perform(get("/api/v1/weblogs").servletPath("/api").pathInfo("/v1/weblogs"))
                .andExpect(status().isOk());
    }

    // --- the boundaries that must not move ----------------------------------------------------

    /** A site admin still reaches any weblog by handle: global admin implies every weblog permission. */
    @Test
    void aSiteAdminStillReachesAnyWeblog() throws Exception {
        signIn("root", null, ApiToken.Role.ADMIN);

        getWeblog("foreign").andExpect(status().isOk()).andExpect(field("handle", "foreign"));
    }

    /** The site space stays withheld from the API, site admin or not. */
    @Test
    void theSiteSpaceIsNotFoundEvenForASiteAdmin() throws Exception {
        signIn("root", null, ApiToken.Role.ADMIN);

        getWeblog("site").andExpect(status().isNotFound());
        patchWeblog("site", "{\"name\":\"Taken\"}").andExpect(status().isNotFound());
        assertNothingSaved();
    }

    /** The weblog checked is the {@code {handle}} path variable; a {@code weblog} parameter is ignored. */
    @Test
    void theWeblogParameterCannotStandInForTheHandle() throws Exception {
        signIn("owen", null, ApiToken.Role.ADMIN);

        getWeblog("foreign", "?weblog=own").andExpect(status().isNotFound());
    }
}
