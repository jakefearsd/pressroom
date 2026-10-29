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
package com.wikantik.pressroom.boot;

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.core.security.LastLoginRecorder;
import com.wikantik.pressroom.ui.core.security.PressroomRememberMeAuthenticationProvider;
import com.wikantik.pressroom.ui.core.security.PressroomRememberMeServices;
import com.wikantik.pressroom.ui.core.security.PressroomUserDetailsService;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogin;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import com.wikantik.pressroom.ui.restapi.auth.ApiAccessDeniedHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiAuthenticationEntryPoint;
import com.wikantik.pressroom.ui.restapi.auth.ApiTokenAuthFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * Java-config replacement for {@code security.xml} (deleted by this class,
 * along with {@code PressroomApplication}'s {@code @ImportResource} that
 * temporarily loaded it as Stage 1B Task 3 scaffolding).
 *
 * <p>{@code security.xml} could not simply move unchanged (see the Task 3
 * report's Deviation 1): Spring Security 7.1 dropped the
 * {@code AccessDecisionManager}/{@code SecurityMetadataSource} path
 * entirely, forcing that file onto the {@code AuthorizationManager} path
 * (bare {@code hasRole()}/{@code hasAnyRole()} SpEL plus a
 * {@code GrantedAuthorityDefaults(rolePrefix = "")} bean) as an interim fix.
 * This class reproduces those semantics directly in Java, using
 * {@code hasAuthority}/{@code hasAnyAuthority} against Roller's verbatim
 * {@code "admin"}/{@code "editor"} authority strings instead -- no
 * {@code GrantedAuthorityDefaults} bean is needed at all, since
 * {@code hasAuthority}/{@code hasAnyAuthority} never apply Spring Security's
 * default {@code ROLE_} prefix in the first place (only {@code hasRole}/
 * {@code hasAnyRole} do).
 *
 * <p>Two authorization surfaces, matching the XML's two {@code <http>}
 * elements: a {@link WebSecurityCustomizer} that reproduces
 * {@code security="none"} for the three static-asset patterns (the XML's own
 * {@code use-authorization-manager} comment explains why {@code ignoring()}
 * is the right replacement, not a {@code permitAll()} rule -- it skips the
 * whole filter chain rather than just the authorization check), and the one
 * real {@link SecurityFilterChain} below for everything else.
 */
@Configuration
public class SecurityConfig {

    /**
     * Publishes the same {@link PasswordEncoder} {@link PressroomContext}
     * always exposed via {@code getPasswordEncoder()}, but built here instead
     * of from {@code PressroomContext.initializeSecurityFeatures} (deleted):
     * {@link #authenticationManager} needs it during context refresh, well
     * before {@code PressroomLifecycle.start()} -- a {@code SmartLifecycle} bean
     * that only starts once refresh has completed -- would otherwise have
     * built it. {@link PressroomContext#setPasswordEncoder} keeps the static
     * accessor {@code User} reads from working exactly as before.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        PasswordEncoder encoder = PressroomContext.createPasswordEncoder();
        PressroomContext.setPasswordEncoder(encoder);
        return encoder;
    }

    @Bean
    public PressroomUserDetailsService rollerUserDetailsService(WebloggerProvider webloggerProvider) {
        return new PressroomUserDetailsService(webloggerProvider);
    }

    @Bean
    public PressroomRememberMeServices rollerRememberMeServices(PressroomUserDetailsService userDetailsService) {
        return new PressroomRememberMeServices(userDetailsService);
    }

    /**
     * Stamps last_login on every form, remember-me and SSO sign-in (spec
     * 2026-10-13, section 2). A bean so the context's event multicaster
     * delivers the filters' InteractiveAuthenticationSuccessEvent to it.
     */
    @Bean
    public LastLoginRecorder lastLoginRecorder(WebloggerProvider webloggerProvider) {
        return new LastLoginRecorder(webloggerProvider);
    }

    /**
     * {@code security.xml}'s {@code <authentication-manager>}: a
     * {@link ProviderManager} of [conditional remember-me provider, DAO
     * provider], in that order. The remember-me provider is registered only
     * when {@code rememberme.enabled=true} -- {@code security.xml} always
     * declared {@code rememberMeAuthenticationProvider}, and
     * {@code PressroomContext.initializeSecurityFeatures} (deleted) removed it
     * from the live provider list at startup when the property was off; this
     * replaces that runtime mutation with an upfront conditional, since the
     * provider is now built here rather than looked up by name afterward.
     * ({@link PressroomRememberMeAuthenticationProvider}'s own constructor
     * refuses to build at all if {@code rememberme.enabled=true} but the key
     * is still the {@code springRocks} default, so guarding construction on
     * the same property is safe -- it is never constructed with a bad key.)
     *
     * <p>{@link DaoAuthenticationProvider} has exactly one constructor
     * (verified via {@code javap} against {@code spring-security-core-7.1.0.jar}:
     * {@code DaoAuthenticationProvider(UserDetailsService)}) -- {@code
     * setPasswordEncoder(PasswordEncoder)} is, contrary to this task's
     * planning assumption, still present in 7.1.0, not removed. The encoder
     * is still applied in one atomic step immediately after construction,
     * inside this {@code @Bean} method, rather than via the old pattern of a
     * separate by-name bean lookup mutating an already-published provider
     * later at startup.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            PressroomUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {

        List<AuthenticationProvider> providers = new ArrayList<>();
        if (WebloggerConfig.getBooleanProperty("rememberme.enabled")) {
            providers.add(new PressroomRememberMeAuthenticationProvider());
        }

        DaoAuthenticationProvider daoProvider = new DaoAuthenticationProvider(userDetailsService);
        daoProvider.setPasswordEncoder(passwordEncoder);
        providers.add(daoProvider);

        return new ProviderManager(providers);
    }

    /**
     * Reproduces {@code security.xml}'s
     * {@code <http pattern="/images/**" security="none"/>} (and the matching
     * {@code /scripts/**}, {@code /styles/**} elements): these three patterns
     * skip the security filter chain entirely rather than merely being
     * granted {@code permitAll()} inside it -- the closest available
     * replacement for {@code security="none"} now that the XML namespace
     * parser is gone.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/images/**", "/scripts/**", "/styles/**");
    }

    /**
     * Overrides Boot's auto-configured {@code pathPatternRequestMatcherBuilder}
     * bean (Boot's own is {@code @ConditionalOnMissingBean}, confirmed via
     * {@code javap} against {@code spring-boot-security-4.1.0.jar}'s
     * {@code ServletWebSecurityAutoConfiguration$PathPatternRequestMatcherBuilderConfiguration}),
     * which derives its {@code basePath} from
     * {@code DispatcherServletPath.getPath()} -- unconditionally, except for
     * the literal value {@code "/"} (also confirmed via {@code javap}: its
     * bytecode is a single {@code if (!path.equals("/")) builder.basePath(path)}).
     * {@link ServletRegistrationConfig#dispatcherServletRegistration} maps
     * {@code DispatcherServlet} to {@code *.rol} (transcribing web.xml
     * verbatim, per Task 3), so that path is {@code "*.rol"}, not {@code "/"};
     * Boot's auto-configured builder then calls
     * {@code PathPatternRequestMatcher.Builder.basePath("*.rol")}, which
     * throws {@code IllegalArgumentException: basePath must start with '/'}
     * at context-refresh time -- confirmed live: {@code java -jar} failed to
     * start with exactly that exception until this bean was added. Every
     * {@code requestMatchers(String...)} call above and in
     * {@link #webSecurityCustomizer} routes through this builder (verified
     * via {@code javap} against {@code spring-security-config-7.1.0.jar}:
     * {@code AbstractRequestMatcherRegistry} resolves it from the
     * application context), so this is not an edge case -- it breaks the
     * entire authorization configuration on every real startup. The
     * patterns declared throughout this class are already the full request
     * path (e.g. {@code /roller-ui/menu*}), matching what
     * {@code *.rol}-mapped {@code DispatcherServlet} itself sees (a suffix
     * servlet mapping consumes no path prefix, unlike a {@code /prefix/*}
     * mapping), so the fix is simply to keep the builder's own default
     * (no {@code basePath} at all), not to reproduce Boot's broken
     * derivation.
     */
    @Bean
    public PathPatternRequestMatcher.Builder pathPatternRequestMatcherBuilder() {
        return PathPatternRequestMatcher.withDefaults();
    }

    /**
     * Reproduces {@code security.xml}'s single real {@code <http>} element.
     *
     * <p>Authorization patterns are translated from the XML's Ant-style
     * {@code **} suffixes to {@code PathPatternRequestMatcher} equivalents
     * (Ant matchers are removed in Spring Security 7; passing plain strings
     * to {@code requestMatchers(String...)} builds
     * {@code PathPatternRequestMatcher} instances under the hood --
     * confirmed via {@code javap} against {@code spring-security-config-7.1.0.jar}:
     * {@code AbstractRequestMatcherRegistry} holds a
     * {@code PathPatternRequestMatcher.Builder} field). Every pattern below
     * was checked against the controllers that actually declare the routes
     * it must cover (see the Task 4 report's semantics table):
     * <ul>
     *   <li>{@code /roller-ui/login-redirect**}, {@code profile**},
     *       {@code createWeblog**}, {@code menu**}, {@code setup**} matched
     *       {@code X.rol} and {@code X!action.rol} in the XML -- both are a
     *       single path segment, so the PathPattern equivalent is a bare
     *       {@code *} suffix ({@code /roller-ui/profile*}, etc.), not
     *       {@code **} (which would also match extra path segments the old
     *       Ant pattern's single-segment glue never did).</li>
     *   <li>{@code /roller-ui/authoring/**} and {@code /roller-ui/admin/**}
     *       keep their {@code **} verbatim -- both are true multi-segment
     *       subtrees (authoring covers e.g. {@code authoring/overlay/*} and
     *       {@code authoring/preview/*} too), which {@code **} still means
     *       under {@code PathPatternRequestMatcher}.</li>
     *   <li>{@code /rewrite-status*} already used a single {@code *} in the
     *       XML and is carried over unchanged.</li>
     * </ul>
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, AuthenticationManager authenticationManager,
            PressroomRememberMeServices rememberMeServices, ObjectProvider<SsoLogin> ssoLogin) throws Exception {

        http
            .authenticationManager(authenticationManager)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/roller-ui/login-redirect*",
                        "/roller-ui/profile*",
                        "/roller-ui/createWeblog*",
                        "/roller-ui/menu*",
                        "/roller-ui/authoring/**")
                    .hasAnyAuthority("admin", "editor")
                .requestMatchers(
                        "/roller-ui/admin/**",
                        "/roller-ui/setup*",
                        "/rewrite-status*")
                    .hasAuthority("admin")
                // Catch-all, must be last (first-match-wins): the public
                // blog and every other admin-UI page are anonymous by
                // default. See security.xml's own comment on this same rule
                // for why an implicit deny-by-default here would be wrong.
                .anyRequest().permitAll())
            // The CSRF exemption in this application: submitting the
            // contact/subscribe forms.
            //
            // isPublicAudiencePost is deliberately narrow -- see its own
            // comment below for what it matches and why the exemption is
            // safe. A second exemption used to sit here for anonymous comment
            // posting; it was removed with the comment subsystem, and
            // isPublicAudiencePost is now the only entry in this list.
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    SecurityConfig::isPublicAudiencePost))
            .formLogin(form -> form
                .loginPage("/roller-ui/login.rol")
                .loginProcessingUrl("/roller_j_security_check")
                .usernameParameter("j_username")
                .passwordParameter("j_password")
                // Where a successful login lands when there is no saved
                // request to return to. Without this Spring Security falls
                // back to "/", which forwards to the admin-only setup page
                // when no frontpage weblog is configured -- so every non-admin
                // user was sent to a 403 the moment they signed in
                // successfully. login-redirect.jsp already exists for exactly
                // this job: straight to the editor when the user has one
                // weblog, otherwise the menu.
                .defaultSuccessUrl("/roller-ui/login-redirect.rol")
                .failureUrl("/roller-ui/login.rol?error=true"))
            // security.xml registered no <logout> element at all -- logout is
            // handled entirely at the application level (/logout.rol forwards
            // to logout-redirect.jsp, which calls session.invalidate()).
            // Boot's default LogoutFilter must stay off, or a bare /logout
            // would appear and behave differently from the app's own.
            .logout(AbstractHttpConfigurer::disable)
            .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
            // Roller's media-file editor uses iframes.
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));

        // Reader sign-in (single sign-on): the bean exists only when SSO is on --
        // see SsoConfig. With it off, nothing is added and the sign-in paths 404.
        ssoLogin.ifAvailable(sso -> sso.applyTo(http));

        return http.build();
    }

    /**
     * The automation API's own chain.
     *
     * <p>Separate from the UI chain rather than folded into it. The UI chain's
     * csrf.ignoringRequestMatchers list holds exactly one narrow entry and its
     * narrowness is the point; a matcher-scoped chain disables CSRF only where
     * no cookie-authenticated request can reach.
     *
     * <p>Stateless: an API call must never mint a session cookie. {@code
     * httpBasic()} is enabled chain-wide because POST /api/v1/tokens -- the
     * mint endpoint, which by definition cannot require an existing token to
     * prove identity -- needs it; this chain does not, and cannot cleanly,
     * restrict Basic to that one path or forbid it to a Bearer-authenticated
     * caller. "A Bearer caller must not be able to mint another token" is a
     * real requirement (a leaked token could otherwise mint itself a
     * replacement forever), but it is enforced in {@code TokensApi} instead,
     * where the request's {@code ApiPrincipal} is readable and the
     * authentication mechanism can actually be inspected -- see {@code
     * TokensApiTest#aBearerAuthenticatedCallerCannotMintAToken}.
     *
     * <p>{@code exceptionHandling(...)} is not optional here. Without an
     * explicit {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler},
     * Spring Security's defaults commit the response themselves -- a Basic
     * challenge, or Boot's own {@code /error} page -- entirely outside
     * {@code DispatcherServlet}, so every ordinary 401 (a missing, malformed,
     * unknown, expired or revoked Bearer token: {@code ApiTokenAuthFilter}
     * never rejects, it just leaves the context empty for this chain's
     * {@code authorizeHttpRequests} to answer) and every 403 would bypass
     * {@code ApiExceptionHandler} and not be problem+json. This is the exact
     * same structural bug the throttle write above exists to avoid, just one
     * layer further out -- see {@link ApiAuthenticationEntryPoint}/
     * {@link ApiAccessDeniedHandler}.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, ApiTokenAuthFilter apiTokenAuthFilter,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ApiAccessDeniedHandler apiAccessDeniedHandler,
            AuthenticationManager authenticationManager) throws Exception {

        http
            .securityMatcher("/api/**")
            .authenticationManager(authenticationManager)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/tokens").authenticated()
                .requestMatchers("/api/v1/ping").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(apiAuthenticationEntryPoint)
                .accessDeniedHandler(apiAccessDeniedHandler))
            .httpBasic(basic -> { })
            .addFilterBefore(apiTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .logout(AbstractHttpConfigurer::disable)
            .rememberMe(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * Shared by every writer outside {@code ApiExceptionHandler}'s reach --
     * see {@link ApiProblemWriter}'s own javadoc for the full why.
     */
    @Bean
    public ApiProblemWriter apiProblemWriter(ObjectMapper objectMapper) {
        return new ApiProblemWriter(objectMapper);
    }

    /**
     * The token manager is supplied through the {@link WebloggerProvider}
     * rather than a {@code @Lazy Weblogger} proxy on purpose: this filter sits
     * in the Security chain (order 40), AHEAD of {@code BootstrapFilter}
     * (order 50), so it is one of the few places that can see a request before
     * the tier is up. {@code provider.getWeblogger()} throws there -- which the
     * filter already catches and treats as "not authenticated" -- whereas a
     * {@code @Lazy} proxy would try to build the graph.
     */
    @Bean
    public ApiTokenAuthFilter apiTokenAuthFilter(ApiProblemWriter apiProblemWriter,
                                                 WebloggerProvider webloggerProvider) {
        return new ApiTokenAuthFilter(
                () -> webloggerProvider.getWeblogger().getApiTokenManager(),
                webloggerProvider, apiProblemWriter);
    }

    /**
     * Stops Boot from auto-registering {@code apiTokenAuthFilter} as a
     * SECOND, container-wide servlet filter on {@code /*}.
     *
     * <p>Any bean whose type implements {@code jakarta.servlet.Filter} --
     * {@code ApiTokenAuthFilter} does, via {@code OncePerRequestFilter} --
     * gets auto-registered by Boot's own
     * {@code ServletContextInitializerBeans} unless something else already
     * claims that bean instance. {@code apiSecurityFilterChain}'s {@code
     * .addFilterBefore(apiTokenAuthFilter, ...)} wires the SAME instance
     * into Spring Security's OWN internal chain, scoped correctly by that
     * chain's {@code securityMatcher("/api/**")} -- but it does not stop
     * Boot's separate auto-registration, which knows nothing about Security
     * matchers and maps to every URL. Both existed simultaneously: every
     * request to the whole application -- JSP admin pages, static assets,
     * webjars, everything -- ran this filter's throttle check a SECOND time,
     * keyed by client IP for anything without a {@code Bearer} header. A
     * single admin page load easily costs 120+ sub-resource requests, so
     * this had the API's 60-second/120-request throttle answering 429 to
     * ordinary page loads across the entire site, not just {@code /api/**}
     * abuse -- caught by {@code ApiIT}, the only layer that boots a real
     * embedded servlet container and therefore the only layer that can see
     * Boot's own auto-registration behaviour at all.
     *
     * <p>{@code setEnabled(false)} on a {@link FilterRegistrationBean}
     * wrapping the SAME filter instance is the standard fix: Boot sees the
     * filter is already claimed by an explicit registration and skips its
     * own auto-registration, and the explicit registration itself never
     * maps the filter onto the servlet container either -- leaving
     * {@code apiSecurityFilterChain}'s internal wiring as the only place
     * this filter ever actually runs.
     */
    @Bean
    public FilterRegistrationBean<ApiTokenAuthFilter> apiTokenAuthFilterRegistration(
            ApiTokenAuthFilter apiTokenAuthFilter) {
        FilterRegistrationBean<ApiTokenAuthFilter> registration =
                new FilterRegistrationBean<>(apiTokenAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public ApiAuthenticationEntryPoint apiAuthenticationEntryPoint(ApiProblemWriter apiProblemWriter) {
        return new ApiAuthenticationEntryPoint(apiProblemWriter);
    }

    @Bean
    public ApiAccessDeniedHandler apiAccessDeniedHandler(ApiProblemWriter apiProblemWriter) {
        return new ApiAccessDeniedHandler(apiProblemWriter);
    }

    /**
     * The audience endpoints: an anonymous reader submitting the contact
     * form or the subscribe form. The forms are injected onto cached pages
     * that cannot carry a per-session token, the requests carry no ambient
     * authority, and the real defences are the honeypot, the timing check
     * and the throttle.
     */
    private static boolean isPublicAudiencePost(HttpServletRequest request) {
        // Exactly POST: methods are case-sensitive (RFC 9110), as Spring's dispatch is.
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "/roller-ui/rendering/contact.rol".equals(path)
                || "/newsletter/subscribe".equals(path);
    }
}
