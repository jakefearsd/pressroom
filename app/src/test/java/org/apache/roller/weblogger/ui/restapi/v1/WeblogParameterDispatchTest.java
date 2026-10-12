package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.boot.WebMvcConfig;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.testsupport.WeblogParameterProbeController;
import org.apache.roller.weblogger.ui.controllers.RollerHandlerInterceptor;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
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
import org.springframework.security.core.userdetails.UserDetails;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * An API route names its weblog only by its {@code {handle}} path variable;
 * a {@code weblog} request parameter is JSP vocabulary and is ignored for an
 * API handler.
 *
 * <p>The defect this pins: {@code RollerHandlerInterceptor} fell back to
 * {@code ?weblog=} on any route without a {@code {handle}}, and
 * {@code ApiScopeInterceptor} accepts any resolved weblog that equals a
 * limited token's. So a token limited to weblog A could reach a route that
 * has no weblog at all -- {@code POST /v1/weblogs} (create), and for an
 * ADMIN-role token held by a site admin every {@code @AdminScoped} list --
 * just by appending {@code ?weblog=A}, where without it the ceiling answers
 * 404.
 *
 * <p>Dispatched through a real {@code DispatcherServlet} built from the
 * production {@link WebMvcConfig} (so {@code RollerHandlerInterceptor} and
 * {@code ApiScopeInterceptor} run in their real order) to the real
 * {@link WeblogCreateApi}, {@link WeblogsApi} and {@link AdminApi}, with
 * {@code .servletPath("/api")} as {@code ServletRegistrationConfig}'s
 * {@code "/api/*"} mapping produces -- the recipe of
 * {@code ApiScopeInterceptorDispatchTest}. The JSP half runs through the
 * same dispatcher to {@link WeblogParameterProbeController}, which still
 * gets its weblog from the parameter.
 */
class WeblogParameterDispatchTest {

    private static final String CREATE_BODY = "{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"}";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger weblogger;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        WebMvcConfig webMvcConfig(RollerHandlerInterceptor rollerHandlerInterceptor) {
            return new WebMvcConfig(rollerHandlerInterceptor);
        }

        @Bean
        RollerHandlerInterceptor rollerHandlerInterceptor() {
            WebloggerProvider provider = mock(WebloggerProvider.class);
            when(provider.isBootstrapped()).thenReturn(true);
            return new RollerHandlerInterceptor(provider, weblogger.weblogger());
        }

        @Bean
        WeblogCreateApi weblogCreateApi() {
            return new WeblogCreateApi();
        }

        @Bean
        WeblogsApi weblogsApi() {
            return new WeblogsApi();
        }

        @Bean
        AdminApi adminApi() {
            return new AdminApi();
        }

        @Bean
        WeblogParameterProbeController weblogParameterProbeController() {
            return new WeblogParameterProbeController();
        }

        @Bean
        org.apache.roller.weblogger.business.Weblogger weblogger() {
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

    /** User "agent", a site admin holding every permission, and weblog "A". */
    @BeforeEach
    void stubTheSite() throws Exception {
        Mockito.reset(weblogger.weblogManager(), weblogger.userManager(), weblogger.themeManager());
        Weblog weblogA = new Weblog();
        weblogA.setId("weblog-a");
        weblogA.setHandle("A");
        when(weblogger.weblogManager().getWeblogByHandle("A")).thenReturn(weblogA);
        User agent = new User();
        agent.setUserName("agent");
        agent.setEmailAddress("agent@example.com");
        agent.setLocale("en_US");
        agent.setTimeZone("America/New_York");
        when(weblogger.userManager().getUserByUserName("agent")).thenReturn(agent);
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);
        SharedTheme journal = mock(SharedTheme.class);
        when(journal.getId()).thenReturn("journal");
        when(weblogger.themeManager().getEnabledThemesList()).thenReturn(List.of(journal));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWithToken(String scopeWeblog, ApiToken.Role role) {
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scopeWeblog, role, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** POST /api/v1/weblogs{query} with the site settings creation reads. */
    private static ResultActions create(String query) throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class);
             MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation"))
                    .thenReturn(true);
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"))
                    .thenReturn(true);
            config.when(() -> WebloggerConfig.getProperty("username.allowedChars")).thenReturn("A-Za-z0-9");
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.rollerProtectedUrls"))
                    .thenReturn("api,page,search");
            return mockMvc.perform(post("/api/v1/weblogs" + query)
                    .servletPath("/api").pathInfo("/v1/weblogs")
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));
        }
    }

    /** The finding: the weblog parameter must not satisfy a limited token's ceiling on create. */
    @Test
    void aLimitedTokenCannotCreateAWeblogByNamingItsOwnWeblogInTheQuery() throws Exception {
        authenticateWithToken("A", ApiToken.Role.POST);

        create("?weblog=A")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    /** Control: without the parameter the limited token was always refused the same way. */
    @Test
    void aLimitedTokenCannotCreateAWeblog() throws Exception {
        authenticateWithToken("A", ApiToken.Role.POST);

        create("").andExpect(status().isNotFound());
        verify(weblogger.weblogManager(), never()).addWeblog(any());
    }

    /**
     * Control: an unlimited token's create still runs end to end, parameter
     * or not -- so the 404s above are the ceiling deciding, not the route
     * failing for everyone.
     */
    @Test
    void anUnlimitedTokenCreatesAWeblogAndTheParameterChangesNothing() throws Exception {
        authenticateWithToken(null, ApiToken.Role.POST);

        create("?weblog=A").andExpect(status().isCreated());
        verify(weblogger.weblogManager()).addWeblog(any());
    }

    /** A limited ADMIN token held by a site admin: an @AdminScoped route without {handle} stays 404. */
    @Test
    void aLimitedAdminTokenCannotListUsersByNamingItsWeblogInTheQuery() throws Exception {
        authenticateWithToken("A", ApiToken.Role.ADMIN);

        mockMvc.perform(get("/api/v1/admin/users?weblog=A")
                        .servletPath("/api").pathInfo("/v1/admin/users"))
                .andExpect(status().isNotFound());
        verify(weblogger.userManager(), never()).getUsers(any(), any(), any(), Mockito.anyInt(),
                Mockito.anyInt());
    }

    @Test
    void aLimitedAdminTokenCannotListEveryWeblogByNamingItsWeblogInTheQuery() throws Exception {
        authenticateWithToken("A", ApiToken.Role.ADMIN);

        mockMvc.perform(get("/api/v1/weblogs?weblog=A")
                        .servletPath("/api").pathInfo("/v1/weblogs"))
                .andExpect(status().isNotFound());
        verify(weblogger.weblogManager(), never()).getWeblogs(any(), any(), any(), any(), Mockito.anyInt(),
                Mockito.anyInt());
    }

    /** Control: the same list for an unlimited ADMIN token is answered. */
    @Test
    void anUnlimitedAdminTokenListsEveryWeblog() throws Exception {
        authenticateWithToken(null, ApiToken.Role.ADMIN);

        mockMvc.perform(get("/api/v1/weblogs?weblog=A")
                        .servletPath("/api").pathInfo("/v1/weblogs"))
                .andExpect(status().isOk());
    }

    /**
     * Characterisation, expected to pass before and after the fix: a JSP
     * route still takes its weblog from the {@code weblog} parameter, through
     * the same dispatcher and interceptor.
     */
    @Test
    void aJspRouteStillResolvesItsWeblogFromTheParameter() throws Exception {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername("agent").password("n/a").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        mockMvc.perform(get("/roller-ui/weblogParameterProbe?weblog=A"))
                .andExpect(status().isOk())
                .andExpect(r -> assertEquals("weblog:A", r.getResponse().getContentAsString()));
    }
}
