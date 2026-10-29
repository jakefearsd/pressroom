package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.testsupport.DispatchProbeController;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The deferred verification Task 5's reviewer could not do from a diff:
 * that a real {@code /api/v1/weblogs/{handle}/...} request reaches
 * {@code PressroomHandlerInterceptor} with {@code actionWeblog} resolved from
 * the path variable, AND that a {@code UISecurityEnforced} controller's
 * declared {@code WeblogPermission} is actually enforced -- not just that
 * {@code WeblogOwnership}/{@code ApiException} work as pure functions or
 * that a probe controller's static metadata looks right.
 *
 * <p>This dispatches through a real {@code DispatcherServlet} built from the
 * production {@link WebMvcConfig} bean (not a hand copy of its registration
 * calls) and the real {@link EntriesApi}, with {@code .servletPath("/api")}
 * reproducing exactly what {@code ServletRegistrationConfig}'s {@code
 * "/api/*"} prefix mapping does on a real container -- same recipe as
 * {@code ApiScopeInterceptorDispatchTest}, which is the class that first
 * proved this pattern necessary (a mocked {@code InterceptorRegistry} cannot
 * catch a servlet-prefix-stripping bug; only a real dispatch can).
 *
 * <p>Authenticated with a Basic-auth-shaped principal (a {@code
 * UserDetails}, no {@code ApiPrincipal} in its details) rather than a Bearer
 * token, so {@code ApiScopeInterceptor}'s scope ceiling is a no-op here and
 * what these tests exercise is squarely {@code PressroomHandlerInterceptor}'s
 * own {@code UISecurityEnforced} enforcement -- the piece Task 5's review
 * left unconfirmed.
 *
 * <p><b>Fix round 1:</b> this class is also what caught the bug it was
 * written to catch. {@code PressroomHandlerInterceptor} used to answer every
 * permission failure with a 302 redirect to a JSP page
 * ({@code LOGIN_URL}/{@code ACCESS_DENIED_URL}) regardless of who was
 * asking -- correct for a browser, but an automation client following that
 * redirect gets an HTTP 200 carrying an HTML login form: a *success* status
 * with no data, which is worse to debug than a clean error and can be
 * mistaken for an empty result. {@code PressroomHandlerInterceptor} now
 * decides from the {@code HandlerMethod}'s bean-type package -- the same
 * discriminator {@code ApiScopeInterceptor}'s {@code @AdminScoped} check
 * already uses, and not a URI string test, which Task 5's review showed can
 * be defeated by encoding -- and throws {@code ApiException.unauthorized}/
 * {@code .forbidden} for a handler under {@code ui.restapi} instead of
 * redirecting. {@link DispatchProbeController} (package {@code
 * testsupport}, deliberately neither {@code ui.restapi} nor {@code
 * ui.controllers} -- see its own javadoc for why both are avoided) pins
 * that every other, JSP-era handler still gets the original redirect
 * unchanged.
 */
class EntriesApiDispatchTest {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger weblogger;

    @Configuration
    @EnableWebMvc
    static class TestConfig {
        @Bean
        WebMvcConfig webMvcConfig(PressroomHandlerInterceptor rollerHandlerInterceptor) {
            return new WebMvcConfig(rollerHandlerInterceptor);
        }

        /** Bootstrapped, and backed by the MockWeblogger facade installed in @BeforeAll. */
        @Bean
        PressroomHandlerInterceptor rollerHandlerInterceptor() {
            WebloggerProvider provider = mock(WebloggerProvider.class);
            when(provider.isBootstrapped()).thenReturn(true);
            return new PressroomHandlerInterceptor(provider, weblogger.weblogger());
        }

        @Bean
        EntriesApi entriesApi() {
            return new EntriesApi();
        }

        @Bean
        DispatchProbeController dispatchProbeController() {
            return new DispatchProbeController();
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
    static void setUpContext() throws Exception {
        weblogger = MockWeblogger.attached();

        Weblog blog = new Weblog();
        blog.setId("weblog-1");
        blog.setHandle("myblog");
        when(weblogger.weblogManager().getWeblogByHandle("myblog")).thenReturn(blog);

        User contributor = new User();
        contributor.setUserName("contributor");
        when(weblogger.userManager().getUserByUserName("contributor")).thenReturn(contributor);

        when(weblogger.weblogEntryManager().getWeblogEntries(any(WeblogEntrySearchCriteria.class)))
                .thenReturn(List.of());

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

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String userName) {
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(userName).password("n/a").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /**
     * The positive case: a real dispatch to the container-mapped
     * {@code /api/v1/weblogs/{handle}/entries} URL, for a user who holds
     * EDIT_DRAFT on "myblog" (EntriesApi's declared requirement), reaches
     * the real handler and gets a real 200 -- proof that {@code {handle}}
     * was resolved to the weblog {@code EntriesApi.list} actually queried
     * against (getWeblogEntries was stubbed against no particular weblog,
     * but WeblogManager.getWeblogByHandle("myblog") is what had to be
     * reached for actionWeblog to be non-null at all; requireActionWeblog
     * would 404 otherwise).
     */
    @Test
    void theWeblogIsResolvedFromThePathVariableAndAPermittedCallerReachesTheHandler() throws Exception {
        authenticateAs("contributor");
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/v1/weblogs/myblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/myblog/entries"))
                .andExpect(status().isOk());
    }

    /**
     * The finding this test exists to close: a caller who lacks the
     * WeblogPermission EntriesApi declares must be refused before the
     * handler runs, on a real {@code {handle}}-carrying REST route -- not
     * merely in a unit test of WeblogOwnership or ApiException in
     * isolation. Refused with a clean problem+json, NOT a 3xx redirect
     * to an HTML page an automation client cannot parse -- the fix round 1
     * bug this test was rewritten to catch (see the class javadoc). A caller
     * holding no role on the weblog at all gets 404, as for an unknown
     * handle, so the refusal never confirms the weblog exists
     * (ApiSecurityRefusalTest pins the member-below-level 403).
     */
    @Test
    void aCallerLackingTheRequiredWeblogPermissionIsRefused() throws Exception {
        authenticateAs("contributor");
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/weblogs/myblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/myblog/entries"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * Control: an unauthenticated request is refused even earlier (401, not
     * a redirect to a login page), proving the 200 above is the permission
     * check actually deciding something rather than every request passing
     * regardless.
     */
    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/weblogs/myblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/myblog/entries"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * The redirect-preservation half of fix round 1: a JSP-era handler
     * (a package outside {@code ui.restapi}) must keep redirecting exactly
     * as before. Proving this pinned, rather than merely assumed, is the
     * point -- the whole admin UI depends on it.
     */
    @Test
    void aNonApiHandlerStillRedirectsOnAuthenticationFailure() throws Exception {
        mockMvc.perform(get("/roller-ui/dispatchProbe"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * A third branch beyond the two the fix round was scoped around: a
     * {@code {handle}} that resolves to no weblog at all (typo, deleted
     * weblog) is neither "unauthenticated" nor "authenticated but lacking a
     * permission" -- there is no weblog to check a permission against in
     * the first place. 404, not 403, mirrors
     * {@code BaseApiController.requireActionWeblog}'s identical contract
     * for the same condition, and matches this wave's existing convention
     * of not letting a 403 confirm a resource's existence
     * ({@code ApiScopeInterceptor.checkWeblogScope} makes the same choice
     * for its own weblog-scope mismatch). Added on my own judgment rather
     * than the coordinator's literal instruction, which named only the
     * other two branches -- called out explicitly in the task report.
     */
    @Test
    void anUnresolvableHandleIsNotFoundRatherThanRedirected() throws Exception {
        authenticateAs("contributor");

        mockMvc.perform(get("/api/v1/weblogs/nosuchblog/entries")
                        .servletPath("/api").pathInfo("/v1/weblogs/nosuchblog/entries"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
