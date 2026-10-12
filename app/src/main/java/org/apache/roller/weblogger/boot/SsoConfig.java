package org.apache.roller.weblogger.boot;

import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.ui.core.security.sso.LazyOidcClientRegistrationRepository;
import org.apache.roller.weblogger.ui.core.security.sso.OidcDiscoverySelfCheck;
import org.apache.roller.weblogger.ui.core.security.sso.ReaderAccounts;
import org.apache.roller.weblogger.ui.core.security.sso.ReaderOidcUserService;
import org.apache.roller.weblogger.ui.core.security.sso.RollerAuthorizationRequestResolver;
import org.apache.roller.weblogger.ui.core.security.sso.SsoFailureHandler;
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogin;
import org.apache.roller.weblogger.ui.core.security.sso.SsoSettings;
import org.apache.roller.weblogger.ui.core.security.sso.SsoStartFilter;
import org.apache.roller.weblogger.ui.core.security.sso.SsoSuccessHandler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Reader sign-in (single sign-on). Found by the application's component scan,
 * like {@link SecurityConfig}.
 *
 * <p>{@link SsoSettings} always exists: the login page reads it to decide
 * whether to show the provider button. Everything else exists only when SSO is
 * on (client id and secret both set); with it off there is no {@link SsoLogin}
 * bean, {@link SecurityConfig} adds nothing to the chain, and the sign-in paths
 * answer 404 (spec criterion 1).
 */
@Configuration(proxyBeanMethods = false)
public class SsoConfig {

    /** Spec, Configuration: the self-check's first retry waits this long, the second twice as long. */
    static final long SELF_CHECK_DELAY_MILLIS = 2000;

    @Bean
    public SsoSettings ssoSettings() {
        return SsoSettings.fromConfig();
    }

    /**
     * The configured Absolute URL: the Global Config value, else
     * {@code ROLLER_SITE_ABSOLUTEURL}. Never {@code getAbsoluteContextURL()},
     * whose last fallback is latched from whatever host the first request used.
     */
    static String configuredAbsoluteUrl() {
        return WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl");
    }

    /** True when SSO is on. */
    static final class SsoEnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return SsoSettings.fromConfig().enabled();
        }
    }

    /** The sign-in itself, present only when SSO is on. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(SsoEnabledCondition.class)
    static class SsoEnabledConfig {

        @Bean
        LazyOidcClientRegistrationRepository ssoClientRegistrations(SsoSettings settings) {
            return new LazyOidcClientRegistrationRepository(settings);
        }

        @Bean
        RollerAuthorizationRequestResolver ssoAuthorizationRequestResolver(
                LazyOidcClientRegistrationRepository registrations, WebloggerProvider webloggerProvider) {
            return new RollerAuthorizationRequestResolver(registrations, SsoConfig::configuredAbsoluteUrl,
                    webloggerProvider);
        }

        /** @param readerAccounts the component-scanned binding, not a second instance */
        @Bean
        ReaderOidcUserService readerOidcUserService(ReaderAccounts readerAccounts) {
            return new ReaderOidcUserService(readerAccounts);
        }

        @Bean
        SsoSuccessHandler ssoSuccessHandler(WebloggerProvider webloggerProvider) {
            return new SsoSuccessHandler(webloggerProvider);
        }

        @Bean
        SsoFailureHandler ssoFailureHandler(WebloggerProvider webloggerProvider) {
            return new SsoFailureHandler(webloggerProvider);
        }

        /**
         * Takes the {@link WebloggerProvider} rather than a {@code @Lazy Weblogger}
         * for the reason {@code SecurityConfig#apiTokenAuthFilter} gives: the
         * security chain runs ahead of {@code BootstrapFilter} and can see a
         * request before the tier is up.
         */
        @Bean
        SsoStartFilter ssoStartFilter(LazyOidcClientRegistrationRepository registrations,
                WebloggerProvider webloggerProvider) {
            return new SsoStartFilter(SsoConfig::configuredAbsoluteUrl, registrations, webloggerProvider);
        }

        /**
         * Stops Boot registering {@code ssoStartFilter} a second time as a
         * container-wide filter on {@code /*}; it runs inside the UI security
         * chain only. Same hazard, same fix, as
         * {@code SecurityConfig#apiTokenAuthFilterRegistration}.
         */
        @Bean
        FilterRegistrationBean<SsoStartFilter> ssoStartFilterRegistration(SsoStartFilter ssoStartFilter) {
            FilterRegistrationBean<SsoStartFilter> registration = new FilterRegistrationBean<>(ssoStartFilter);
            registration.setEnabled(false);
            return registration;
        }

        @Bean
        SsoLogin ssoLogin(LazyOidcClientRegistrationRepository registrations,
                RollerAuthorizationRequestResolver ssoAuthorizationRequestResolver,
                ReaderOidcUserService readerOidcUserService, SsoSuccessHandler ssoSuccessHandler,
                SsoFailureHandler ssoFailureHandler, SsoStartFilter ssoStartFilter) {
            return new SsoLogin(registrations, ssoAuthorizationRequestResolver, readerOidcUserService,
                    ssoSuccessHandler, ssoFailureHandler, ssoStartFilter);
        }

        /**
         * Probes discovery once the application is up, off the boot thread; it
         * only logs (spec criterion 3). An anonymous class rather than a lambda
         * so the event type is visible to Spring's listener matching.
         */
        @Bean
        ApplicationListener<ApplicationReadyEvent> ssoDiscoverySelfCheck(
                LazyOidcClientRegistrationRepository registrations) {
            return new ApplicationListener<>() {
                @Override
                public void onApplicationEvent(ApplicationReadyEvent event) {
                    new OidcDiscoverySelfCheck(registrations, SELF_CHECK_DELAY_MILLIS).start();
                }
            };
        }
    }
}
