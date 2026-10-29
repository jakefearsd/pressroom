package com.wikantik.pressroom.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.core.security.sso.ReaderAccounts;
import com.wikantik.pressroom.ui.core.security.sso.ReaderOidcUserService;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogin;
import com.wikantik.pressroom.ui.core.security.sso.SsoSettings;
import com.wikantik.pressroom.ui.core.security.sso.SsoStartFilter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link SsoConfig}: the settings bean always exists (the login page reads it),
 * and the sign-in wiring exists only when SSO is on (spec criterion 1).
 */
class SsoConfigTest {

    @Configuration
    @Import(SsoConfig.class)
    static class Harness {

        @Bean
        WebloggerProvider webloggerProvider() {
            return mock(WebloggerProvider.class);
        }

        /** Component-scanned in the running application; this context has no scan. */
        @Bean
        ReaderAccounts readerAccounts() {
            return mock(ReaderAccounts.class);
        }
    }

    private static AnnotationConfigApplicationContext context() {
        return new AnnotationConfigApplicationContext(Harness.class);
    }

    /**
     * Turns SSO on for the calling thread. Discovery is not an absolute URL, so
     * the probe's attempts fail at once without any network I/O.
     */
    private static MockedStatic<WebloggerConfig> ssoOn() {
        MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class, CALLS_REAL_METHODS);
        config.when(() -> WebloggerConfig.getProperty("sso.oidc.client.id")).thenReturn("client-id");
        config.when(() -> WebloggerConfig.getProperty("sso.oidc.client.secret")).thenReturn("client-secret");
        config.when(() -> WebloggerConfig.getProperty("sso.oidc.discovery.uri")).thenReturn("not-a-url");
        return config;
    }

    @Test
    void withSsoOffTheSettingsExistButNothingElseDoes() {
        try (AnnotationConfigApplicationContext ctx = context()) {
            assertFalse(ctx.getBean(SsoSettings.class).enabled());
            assertEquals(0, ctx.getBeansOfType(SsoLogin.class).size());
            assertEquals(0, ctx.getBeansOfType(SsoStartFilter.class).size());
            assertEquals(0, ctx.getBeansOfType(ReaderOidcUserService.class).size());
            assertEquals(0, ctx.getBeansOfType(FilterRegistrationBean.class).size());
        }
    }

    @Test
    void withSsoOnTheSignInIsWired() {
        try (MockedStatic<WebloggerConfig> _ = ssoOn(); AnnotationConfigApplicationContext ctx = context()) {
            assertTrue(ctx.getBean(SsoSettings.class).enabled());
            assertNotNull(ctx.getBean(SsoLogin.class));
            assertNotNull(ctx.getBean(ReaderOidcUserService.class));
            assertEquals(1, ctx.getBeansOfType(ReaderAccounts.class).size(),
                    "the component-scanned ReaderAccounts, never a second one");
        }
    }

    /** The same hazard {@code SecurityConfigTest#apiTokenAuthFilterIsNotAutoRegisteredAsAContainerWideFilter} pins. */
    @Test
    void ssoStartFilterIsNotAutoRegisteredAsAContainerWideFilter() {
        try (MockedStatic<WebloggerConfig> _ = ssoOn(); AnnotationConfigApplicationContext ctx = context()) {
            FilterRegistrationBean<?> registration =
                    ctx.getBean("ssoStartFilterRegistration", FilterRegistrationBean.class);
            assertFalse(registration.isEnabled(),
                    "ssoStartFilter runs inside the UI security chain only, never as a second filter on /*");
            assertSame(ctx.getBean(SsoStartFilter.class), registration.getFilter());
        }
    }

    /** {@link SsoConfig} and {@link SecurityConfig} together, as the component scan assembles them. */
    @Configuration
    @EnableWebSecurity
    @Import({Harness.class, SecurityConfig.class})
    static class ChainHarness {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    /**
     * The production beans, not hand-built ones, reach the UI chain: with SSO on
     * the start path is live, and with no Absolute URL configured it fails closed
     * (spec criterion 7) through the real {@code site.absoluteurl} lookup.
     */
    @Test
    void withSsoOnTheProductionBeansAnswerOnTheUiChain() throws Exception {
        try (MockedStatic<WebloggerConfig> config = ssoOn()) {
            config.when(() -> WebloggerConfig.getProperty("site.absoluteurl")).thenReturn("");
            AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
            ctx.setServletContext(new MockServletContext());
            ctx.register(ChainHarness.class);
            ctx.refresh();
            try {
                MockMvc mvc = webAppContextSetup(ctx).apply(springSecurity()).build();
                mvc.perform(get("/pressroom-ui/sso/start").param("return", "/blog/"))
                        .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=unavailable"));
                mvc.perform(get("/pressroom-ui/sso/callback"))
                        .andExpect(redirectedUrl("/pressroom-ui/login.rol?sso=failed"));
            } finally {
                ctx.close();
            }
        }
    }

    /**
     * The probe is stopped before the test ends: left running, its retries log
     * WARNs on {@code pressroom.sso} for several seconds, into whichever test class
     * runs next and counts that logger's lines.
     */
    @Test
    void theDiscoverySelfCheckStartsOnceTheApplicationIsReady() throws InterruptedException {
        Set<Thread> before = selfChecks();
        Set<Thread> started = Set.of();
        try (MockedStatic<WebloggerConfig> _ = ssoOn(); AnnotationConfigApplicationContext ctx = context()) {
            assertTrue(startedSince(before).isEmpty(), "nothing probes the provider during context refresh");
            ctx.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], ctx, Duration.ZERO));
            started = startedSince(before);
            assertEquals(1, started.size(), "one probe, on its own thread");
            assertTrue(started.iterator().next().isDaemon(), "a probe never holds the JVM up");
        } finally {
            for (Thread probe : started) {
                probe.interrupt();
                probe.join(10_000);
            }
        }
        for (Thread probe : started) {
            assertFalse(probe.isAlive(), "the probe stops when interrupted");
        }
    }

    private static Set<Thread> selfChecks() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "pressroom-sso-selfcheck".equals(t.getName()))
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<Thread> startedSince(Set<Thread> before) {
        Set<Thread> now = selfChecks();
        now.removeAll(before);
        return now;
    }
}
