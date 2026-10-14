/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.roller.weblogger.boot;

import java.lang.reflect.Method;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.core.security.RollerUserDetailsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the authorization matrix {@code security.xml} used to encode and
 * {@link SecurityConfig} now reproduces in Java. Written before
 * {@code SecurityConfig} existed (Stage 1B Task 4, TDD step 1): with no
 * {@code SecurityFilterChain} bean in the context, Spring Security's default
 * secures every request and serves its own generated-password login form at
 * {@code /login} rather than {@code /roller-ui/login.rol}, so every case
 * below failed until {@code SecurityConfig} was implemented.
 *
 * <p><b>Why this does not use {@code @SpringBootTest}/{@code @AutoConfigureMockMvc}
 * (the shape the Stage 1B Task 4 brief called "pragmatic approved shape").</b>
 * That shape turned out to be a dead end for this codebase specifically:
 * {@code spring-test:7.0.8} (pulled in by the {@code spring-boot-dependencies}
 * 4.1.0 BOM) compiles {@code SpringExtension} against
 * {@code ExtensionContext.Store.computeIfAbsent(Object, Function, Class)} --
 * verified via {@code javap -c} against the actual jar, at
 * {@code SpringExtension.getTestContextManager()} -- a method that does not
 * exist on {@code junit-jupiter-api:5.14.3} (verified via {@code javap}: 5.14.3
 * only has {@code getOrComputeIfAbsent}; {@code computeIfAbsent} is new in
 * JUnit Jupiter 6.0). Every {@code @ExtendWith(SpringExtension.class)} test --
 * which is every {@code @SpringBootTest} -- throws {@code NoSuchMethodError}
 * at class level under this combination. The Global Constraints pin
 * junit-bom at 5.14.3 specifically to defer the JUnit 6 bump (a separate,
 * deliberate decision out of scope for this task), so bumping JUnit here to
 * unblock {@code SpringExtension} was not an option; see the app pom's
 * {@code spring-security-test} dependency comment for the same note.
 *
 * <p>The fix keeps the constraint and still exercises the production
 * {@link SecurityConfig} bean-for-bean: build a plain {@link
 * AnnotationConfigWebApplicationContext} by hand in {@code @BeforeAll} (no
 * {@code SpringExtension} involved at all -- ordinary JUnit 5 lifecycle
 * methods only, which need nothing past the 5.14.3 API), importing exactly
 * {@link SecurityConfig}. {@code @EnableWebSecurity} substitutes for the Boot
 * autoconfiguration that would otherwise register the
 * {@code springSecurityFilterChain} bean from {@link SecurityConfig}'s
 * {@code SecurityFilterChain}/{@code WebSecurityCustomizer} beans -- the same
 * mechanism Boot's own {@code SecurityFilterAutoConfiguration} delegates to
 * under the hood, so this is a faithful (if manually assembled) stand-in for
 * what the running application actually does, not a simplification of it.
 *
 * <p><b>Why a stub controller instead of the real {@code ui.controllers}
 * package.</b> An earlier version of this test component-scanned the real
 * controllers (plus {@code WebMvcConfig} and {@code WebloggerBeanConfig} to
 * satisfy their {@code @Autowired} dependencies) so every route below would
 * be handled by the actual production {@code @Controller}. That failed for a
 * reason unrelated to security: every real controller's {@code @Lazy
 * Weblogger weblogger} field is a Spring-managed lazy proxy, and several
 * controllers ({@code SetupController} among them) call a method on it
 * directly in {@code execute()} -- a path {@code RollerHandlerInterceptor}'s
 * "app not bootstrapped yet" early-return does not gate at all, since that
 * check only guards {@code TestUtils.weblogger()}, a separate
 * static accessor from the injected field. Touching the proxy forces
 * {@code WebloggerBeanConfig} to build the real bean graph, which requires
 * {@code WebloggerStartup.prepare()} to have run against a real database --
 * exactly what this class does not have and does not need, since what it is
 * actually pinning is {@link SecurityConfig}'s authorization decision, not
 * any controller's business logic. The routes below are still the real
 * literal path strings the production controllers declare (see the mapping
 * comment above the constants), so {@code SecurityConfig}'s
 * {@code requestMatchers(...)} patterns are still checked against real
 * routes -- only the handler behind each one is a stub that always returns
 * 200, isolating the one thing this class exists to verify.
 */
class SecurityConfigTest {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;

    /**
     * Returns 200 for every path {@code SecurityConfig}'s authorization rules
     * let through, so a non-3xx/403 result in the tests below can only be
     * explained by the security filter chain's own decision.
     */
    @Controller
    static class StubController {

        @GetMapping({
                "/roller-ui/login.rol",
                "/roller-ui/login-redirect.rol",
                "/roller-ui/profile.rol",
                "/roller-ui/profileTokens.rol",
                "/roller-ui/profileTokens!reveal.rol",
                "/roller-ui/createWeblog.rol",
                "/roller-ui/menu.rol",
                "/roller-ui/menu!accept.rol",
                "/roller-ui/authoring/entries.rol",
                "/roller-ui/authoring/overlay/mediaFileImageChooser.rol",
                "/roller-ui/admin/userAdmin.rol",
                "/roller-ui/admin/globalConfig.rol",
                "/roller-ui/setup.rol",
                "/rewrite-status",
        })
        @ResponseBody
        String ok() {
            return "ok";
        }
    }

    @Configuration
    @EnableWebSecurity
    @Import({SecurityConfig.class, StubController.class})
    static class TestConfig {

        /**
         * Spring Boot autoconfigures this in the running application;
         * this hand-assembled context has no autoconfiguration at all, so
         * apiTokenAuthFilter's ObjectMapper dependency needs a stand-in.
         */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * {@code apiTokenAuthFilter} takes the provider bean (component-scanned
         * in the running application); this hand-assembled context has no
         * scan, so stand one in. Never bootstrapped: nothing here reaches the
         * business tier.
         */
        @Bean
        WebloggerProvider webloggerProvider() {
            return mock(WebloggerProvider.class);
        }
    }

    @BeforeAll
    static void setUpContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();

        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * {@code ApiTokenAuthFilter} implements {@code jakarta.servlet.Filter}
     * (via {@code OncePerRequestFilter}) and is exposed as a plain
     * {@code @Bean} -- Boot auto-registers any such bean as a container-wide
     * filter on {@code /*} unless something disables that. Without the
     * {@code apiTokenAuthFilterRegistration} bean, the SAME filter instance
     * ran twice per request: once (correctly) inside
     * {@code apiSecurityFilterChain}'s own scoped chain, and once (wrongly)
     * on every single request to the whole application -- JSP admin pages,
     * static assets, everything -- applying the API's throttle keyed by
     * client IP to ordinary page loads. A busy admin page's sub-resources
     * alone are enough to cross the 120-request/60-second threshold and 429
     * the whole site for that client, nothing to do with {@code /api/**}
     * abuse. Only a real embedded servlet container running Boot's own
     * {@code ServletContextInitializerBeans} logic can see this at all --
     * {@code ApiIT} is what actually caught it, via a heavy admin-page
     * browser flow tripping 429s on {@code roller.js} and webfont requests.
     */
    @Test
    void apiTokenAuthFilterIsNotAutoRegisteredAsAContainerWideFilter() {
        FilterRegistrationBean<?> registration =
                context.getBean("apiTokenAuthFilterRegistration", FilterRegistrationBean.class);
        assertFalse(registration.isEnabled(),
                "apiTokenAuthFilter must not be auto-registered on /* -- it is already wired into "
                        + "apiSecurityFilterChain, scoped to /api/**, via addFilterBefore");
    }

    // Route -> SecurityConfig#securityFilterChain pattern it pins:
    //   /roller-ui/login-redirect.rol            -> "/roller-ui/login-redirect*"          (admin, editor)
    //   /roller-ui/profile.rol                   -> "/roller-ui/profile*"                 (admin, editor)
    //   /roller-ui/createWeblog.rol               -> "/roller-ui/createWeblog*"            (admin, editor)
    //   /roller-ui/menu.rol, /menu!accept.rol     -> "/roller-ui/menu*"                    (admin, editor)
    //   /roller-ui/authoring/entries.rol          -> "/roller-ui/authoring/**"              (admin, editor)
    //   /roller-ui/authoring/overlay/...          -> "/roller-ui/authoring/**"              (admin, editor)
    //   /roller-ui/admin/userAdmin.rol, .../globalConfig.rol -> "/roller-ui/admin/**"       (admin only)
    //   /roller-ui/setup.rol                      -> "/roller-ui/setup*"                    (admin only)
    //   /rewrite-status                           -> "/rewrite-status*"                     (admin only,
    //                                                  no controller behind it in production -- security.xml
    //                                                  protected it too even though nothing serves it)
    //   /roller-ui/login.rol                      -> permitAll() (must stay reachable to log in at all)
    //   /roller-ui/rendering/page/*               -> permitAll() (the public blog)

    // ----------------------------------------------------- anonymous access

    @ParameterizedTest
    @ValueSource(strings = {
            "/roller-ui/login-redirect.rol",
            "/roller-ui/profile.rol",
            "/roller-ui/profileTokens.rol",
            "/roller-ui/profileTokens!reveal.rol",
            "/roller-ui/createWeblog.rol",
            "/roller-ui/menu.rol",
            "/roller-ui/menu!accept.rol",
            "/roller-ui/authoring/entries.rol",
            "/roller-ui/authoring/overlay/mediaFileImageChooser.rol",
            "/roller-ui/admin/userAdmin.rol",
            "/roller-ui/admin/globalConfig.rol",
            "/roller-ui/setup.rol",
            "/rewrite-status",
    })
    void anonymousIsRedirectedToLoginOnEveryProtectedPattern(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/roller-ui/login.rol"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/roller-ui/login.rol",
            "/roller-ui/rendering/page/nonexistent-weblog",
    })
    void anonymousIsNotRedirectedOnPublicSurfaces(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(status < 300 || status >= 400,
                            "Expected " + path + " to be reachable by an anonymous request (not a 3xx "
                                    + "redirect to login), but got " + status);
                });
    }

    @Test
    void anonymousIsNotRedirectedOnStaticResourcePatterns() throws Exception {
        // security="none" territory (WebSecurityCustomizer.ignoring()): the
        // security filter chain must not even run, so these must not be
        // redirected to login even though nothing serves them as an MVC
        // handler in this test (DispatcherServlet 404s -- that is fine, the
        // point is "not 3xx-to-login").
        for (String path : new String[] {"/images/foo.png", "/scripts/foo.js", "/styles/foo.css"}) {
            mockMvc.perform(get(path))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        org.junit.jupiter.api.Assertions.assertTrue(status < 300 || status >= 400,
                                "Expected " + path + " to bypass the security filter chain entirely "
                                        + "(WebSecurityCustomizer.ignoring()), but got a redirect: " + status);
                    });
        }
    }

    // ------------------------------------------------------- editor access

    @ParameterizedTest
    @ValueSource(strings = {
            "/roller-ui/login-redirect.rol",
            "/roller-ui/profile.rol",
            "/roller-ui/profileTokens.rol",
            "/roller-ui/profileTokens!reveal.rol",
            "/roller-ui/createWeblog.rol",
            "/roller-ui/menu.rol",
            "/roller-ui/menu!accept.rol",
            "/roller-ui/authoring/entries.rol",
            "/roller-ui/authoring/overlay/mediaFileImageChooser.rol",
    })
    void editorAuthorityIsAllowedOnEditorSurfaces(String path) throws Exception {
        mockMvc.perform(get(path).with(user("editorUser").authorities(new SimpleGrantedAuthority("editor"))))
                .andExpect(status().isOk());
    }

    /** A reader (SSO) holds neither editor nor admin, so the token pages -- the reveal included -- refuse them. */
    @Test
    void aReaderCannotReachTheTokenPages() throws Exception {
        for (String path : new String[] {"/roller-ui/profileTokens.rol", "/roller-ui/profileTokens!reveal.rol"}) {
            mockMvc.perform(get(path).with(user("readerUser").authorities(new SimpleGrantedAuthority("reader"))))
                    .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/roller-ui/admin/userAdmin.rol",
            "/roller-ui/admin/globalConfig.rol",
            "/roller-ui/setup.rol",
            "/rewrite-status",
    })
    void editorAuthorityIsForbiddenOnAdminOnlySurfaces(String path) throws Exception {
        mockMvc.perform(get(path).with(user("editorUser").authorities(new SimpleGrantedAuthority("editor"))))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------- admin access

    @ParameterizedTest
    @ValueSource(strings = {
            "/roller-ui/admin/userAdmin.rol",
            "/roller-ui/admin/globalConfig.rol",
            "/roller-ui/setup.rol",
            "/rewrite-status",
    })
    void adminAuthorityIsAllowedOnAdminOnlySurfaces(String path) throws Exception {
        mockMvc.perform(get(path).with(user("adminUser").authorities(new SimpleGrantedAuthority("admin"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------- rememberme.enabled=true

    /**
     * {@code authenticationManager}'s remember-me provider is only added
     * when {@code rememberme.enabled=true} -- every test above runs under
     * the real (default {@code false}) config, so that branch needs its own
     * direct-call test. Bypasses the {@code AnnotationConfigWebApplicationContext}
     * entirely: unlike {@code securityFilterChain} below, {@code
     * authenticationManager} needs no {@code HttpSecurity} collaborator, so a
     * plain call against a fresh {@link SecurityConfig} is enough.
     */
    @Test
    void authenticationManagerAddsTheRememberMeProviderWhenEnabled() {
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getBooleanProperty("rememberme.enabled")).thenReturn(true);
            // Password encoding is not what these tests are about, but
            // passwordEncoder() reads the algorithm and a mockStatic returns
            // null for anything unstubbed. This used to pass only because the
            // mock's default `false` for the (now removed)
            // passwds.encryption.enabled sent it down the noop branch.
            mocked.when(() -> WebloggerConfig.getProperty("passwds.encryption.algorithm"))
                    .thenReturn("bcrypt");

            // RollerRememberMeAuthenticationProvider's own constructor refuses to
            // build with rememberme.enabled=true and the "springRocks" default
            // key (see its class-level guard) -- give it a real-looking one.
            mocked.when(() -> WebloggerConfig.getProperty("rememberme.key", "springRocks"))
                    .thenReturn("test-only-secret");

            SecurityConfig config = new SecurityConfig();
            PasswordEncoder encoder = config.passwordEncoder();
            RollerUserDetailsService userDetailsService = config.rollerUserDetailsService(mock(WebloggerProvider.class));

            AuthenticationManager manager = config.authenticationManager(userDetailsService, encoder);

            ProviderManager providerManager = assertInstanceOf(ProviderManager.class, manager);
            assertEquals(2, providerManager.getProviders().size(),
                    "rememberme.enabled=true must add the remember-me provider ahead of the DAO "
                            + "provider, not replace it");
        }
    }

    @Test
    void authenticationManagerOmitsTheRememberMeProviderWhenDisabled() {
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getBooleanProperty("rememberme.enabled")).thenReturn(false);
            // Password encoding is not what these tests are about, but
            // passwordEncoder() reads the algorithm and a mockStatic returns
            // null for anything unstubbed. This used to pass only because the
            // mock's default `false` for the (now removed)
            // passwds.encryption.enabled sent it down the noop branch.
            mocked.when(() -> WebloggerConfig.getProperty("passwds.encryption.algorithm"))
                    .thenReturn("bcrypt");

            SecurityConfig config = new SecurityConfig();
            PasswordEncoder encoder = config.passwordEncoder();
            RollerUserDetailsService userDetailsService = config.rollerUserDetailsService(mock(WebloggerProvider.class));

            AuthenticationManager manager = config.authenticationManager(userDetailsService, encoder);

            ProviderManager providerManager = assertInstanceOf(ProviderManager.class, manager);
            assertEquals(1, providerManager.getProviders().size());
        }
    }

    // -------------------------------------------------- CSRF exemption predicate

    /**
     * {@code isPublicAudiencePost} is a request predicate, not a mapped
     * route, so it is pinned directly by reflection rather than through
     * {@code mockMvc} -- the same shape {@code ControllerMetadataTest}'s
     * private-method style tests use elsewhere. This is what
     * {@code SecurityConfig}'s {@code .csrf(...).ignoringRequestMatchers(...)}
     * consults; a {@code true} here is what lets an anonymous contact-form or
     * subscribe POST through without a CSRF token.
     */
    @Test
    void audiencePostsAreRecognisedOnlyOnTheirExactPaths() throws Exception {
        assertTrue(isPublicAudiencePost(postRequest("/roller-ui/rendering/contact.rol")));
        assertTrue(isPublicAudiencePost(postRequest("/newsletter/subscribe")));

        assertFalse(isPublicAudiencePost(postRequest("/roller-ui/rendering/comment.rol")),
                "only the two named audience endpoints are exempt");
        assertFalse(isPublicAudiencePost(postRequest("/newsletter/subscribe/extra")),
                "the match must be exact, not a prefix");
    }

    @Test
    void nonPostRequestsAreNeverAudiencePostsEvenOnTheExactPath() throws Exception {
        assertFalse(isPublicAudiencePost(getRequest("/roller-ui/rendering/contact.rol")),
                "the predicate only ever exempts POST");
        assertFalse(isPublicAudiencePost(request("PUT", "/roller-ui/rendering/contact.rol")));
    }

    /**
     * HTTP methods are case-sensitive (RFC 9110), and so is Spring's dispatch:
     * a request spelled "post" reaches no POST handler, so it has no business
     * being exempted from CSRF either. The exemption is exactly POST.
     */
    @Test
    void theExemptionIsForExactlyPostNotAnotherSpellingOfIt() throws Exception {
        assertFalse(isPublicAudiencePost(request("post", "/roller-ui/rendering/contact.rol")));
        assertFalse(isPublicAudiencePost(request("Post", "/newsletter/subscribe")));
    }

    private static boolean isPublicAudiencePost(HttpServletRequest request) throws Exception {
        Method method = SecurityConfig.class.getDeclaredMethod("isPublicAudiencePost", HttpServletRequest.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, request);
    }

    private static HttpServletRequest postRequest(String path) {
        return request("POST", path);
    }

    private static HttpServletRequest getRequest(String path) {
        return request("GET", path);
    }

    private static HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        return request;
    }

    /**
     * Criterion 20 through the real filter chain: the form-login filter
     * publishes InteractiveAuthenticationSuccessEvent only because the
     * configurer post-processes it with the context's event publisher, and
     * LastLoginRecorder hears it only because SecurityConfig declares it.
     * Either wire coming loose leaves last_login null forever, silently.
     */
    @Test
    void aSuccessfulFormSignInRecordsTheLastLogin() throws Exception {
        WebloggerProvider provider = context.getBean(WebloggerProvider.class);
        MockWeblogger tier = MockWeblogger.create();
        User alice = new User();
        alice.setUserName("alice");
        alice.setPassword(TestUtils.TEST_PASSWORD_HASH);
        when(tier.userManager().getUserByUserName("alice")).thenReturn(alice);
        when(tier.userManager().getRoles(alice)).thenReturn(List.of("editor"));
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
        try {
            mockMvc.perform(formLogin("/roller_j_security_check")
                            .user("j_username", "alice")
                            .password("j_password", TestUtils.TEST_PASSWORD))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/roller-ui/login-redirect.rol"));

            verify(tier.userManager()).recordLogin("alice");
            assertEquals(1, tier.flushCount());
        } finally {
            reset(provider);
        }
    }
}
