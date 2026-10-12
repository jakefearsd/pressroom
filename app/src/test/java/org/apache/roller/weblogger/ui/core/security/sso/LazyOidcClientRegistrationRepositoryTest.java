package org.apache.roller.weblogger.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class LazyOidcClientRegistrationRepositoryTest {

    private static final String SECRET = "super-s3cret-value";
    private static final SsoSettings SETTINGS =
            new SsoSettings("id", SECRET, SsoSettings.GOOGLE_DISCOVERY, "Google");

    static ClientRegistration.Builder stubBuilder(String issuer) {
        return ClientRegistration.withRegistrationId("ignored")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri(issuer + "/auth").tokenUri(issuer + "/token")
                .jwkSetUri(issuer + "/jwks").issuerUri(issuer).userInfoUri(issuer + "/userinfo");
    }

    @Test
    void successIsCachedAndFetchedOnce() {
        AtomicInteger calls = new AtomicInteger();
        var repo = new LazyOidcClientRegistrationRepository(SETTINGS,
                iss -> { calls.incrementAndGet(); return stubBuilder(iss); });
        ClientRegistration r = repo.findByRegistrationId("oidc");
        assertSame(r, repo.findByRegistrationId("oidc"));
        assertTrue(repo.tryResolveOnce());
        assertEquals(1, calls.get());
        assertEquals("oidc", r.getRegistrationId());
        assertEquals("id", r.getClientId());
        assertEquals(ClientAuthenticationMethod.CLIENT_SECRET_BASIC, r.getClientAuthenticationMethod());
        assertEquals(Set.of("openid", "profile", "email"), r.getScopes());
        assertEquals("sub", r.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
        assertEquals("Google", r.getClientName());
    }

    @Test
    void failureTriesThreeTimesThenAnswersNullAndRecoversLater() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean up = new AtomicBoolean(false);
        var repo = new LazyOidcClientRegistrationRepository(SETTINGS, iss -> {
            calls.incrementAndGet();
            if (!up.get()) {
                throw new IllegalArgumentException("connect timed out");
            }
            return stubBuilder(iss);
        });
        assertNull(repo.findByRegistrationId("oidc"));
        assertEquals(3, calls.get());
        assertFalse(repo.tryResolveOnce());
        up.set(true);
        assertNotNull(repo.findByRegistrationId("oidc"));
    }

    @Test
    void unknownRegistrationIdAndDisabledSettingsAnswerNull() {
        AtomicInteger calls = new AtomicInteger();
        var enabled = new LazyOidcClientRegistrationRepository(SETTINGS,
                iss -> { calls.incrementAndGet(); return stubBuilder(iss); });
        assertNull(enabled.findByRegistrationId("google"));
        var disabled = new LazyOidcClientRegistrationRepository(
                new SsoSettings("", SECRET, SsoSettings.GOOGLE_DISCOVERY, "Google"),
                iss -> { calls.incrementAndGet(); return stubBuilder(iss); });
        assertNull(disabled.findByRegistrationId("oidc"));
        assertEquals(0, calls.get());
    }

    /**
     * The WARN names every class along the cause chain: discovery failures arrive
     * wrapped (an {@code IllegalArgumentException} around the connect error), and the
     * top class alone says nothing about why. Never a message: it could carry
     * provider text.
     */
    @Test
    void theWarnNamesTheCauseChainButNoMessage() {
        try (SsoLogCapture log = SsoLogCapture.start()) {
            var repo = new LazyOidcClientRegistrationRepository(SETTINGS, iss -> {
                throw new IllegalArgumentException("Unable to resolve the Configuration with the provided Issuer",
                        new IllegalStateException("I/O error on GET request",
                                new java.net.UnknownHostException("accounts.google.com: Name or service not known")));
            });
            assertNull(repo.findByRegistrationId("oidc"));

            List<String> warns = log.lines(Level.WARN);
            assertEquals(3, warns.size(), "one WARN per attempt: " + warns);
            for (String warn : warns) {
                assertTrue(warn.contains("IllegalArgumentException<-IllegalStateException<-UnknownHostException"),
                        warn);
                assertFalse(warn.contains("Unable to resolve") || warn.contains("I/O error")
                        || warn.contains("Name or service"), "never a message: " + warn);
            }
            assertFalse(log.anyThrowable(), "never the exception itself");
        }
    }

    /** A cause chain that loops, or runs long, still ends: the same guard as the failure handler's. */
    @Test
    void aLoopingOrLongCauseChainStillEnds() {
        IllegalStateException first = new IllegalStateException("a");
        IllegalArgumentException second = new IllegalArgumentException("b");
        first.initCause(second);
        second.initCause(first);
        RuntimeException longChain = new RuntimeException("top");
        Throwable link = longChain;
        for (int i = 0; i < 20; i++) {
            Throwable next = new UnsupportedOperationException("link " + i);
            link.initCause(next);
            link = next;
        }
        for (RuntimeException thrown : List.of(new RuntimeException("top", first), longChain)) {
            try (SsoLogCapture log = SsoLogCapture.start()) {
                new LazyOidcClientRegistrationRepository(SETTINGS, iss -> {
                    throw thrown;
                }).tryResolveOnce();
                List<String> warns = log.lines(Level.WARN);
                assertEquals(1, warns.size(), warns.toString());
                String names = warns.get(0).substring(warns.get(0).indexOf("RuntimeException"));
                assertTrue(names.split("<-").length <= 9, "at most eight causes: " + names);
            }
        }
        try (SsoLogCapture log = SsoLogCapture.start()) {
            new LazyOidcClientRegistrationRepository(SETTINGS, iss -> {
                throw new RuntimeException("top", first);
            }).tryResolveOnce();
            assertTrue(log.lines(Level.WARN).get(0)
                    .endsWith("RuntimeException<-IllegalStateException<-IllegalArgumentException"),
                    "the loop ends at the first repeat: " + log.lines(Level.WARN));
        }
    }

    @Test
    void theSecretAppearsInNoLogLineOrException() {
        List<String> captured = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("sso-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                captured.add(event.getMessage().getFormattedMessage());
                if (event.getThrown() != null) {
                    captured.add(event.getThrown().toString());
                }
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig("roller.sso", Level.INFO, false);
        lc.addAppender(appender, Level.INFO, null);
        ctx.getConfiguration().addLogger("roller.sso", lc);
        ctx.updateLoggers();
        try {
            var repo = new LazyOidcClientRegistrationRepository(SETTINGS,
                    iss -> { throw new IllegalStateException("boom"); });
            repo.findByRegistrationId("oidc");
        } finally {
            ctx.getConfiguration().removeLogger("roller.sso");
            ctx.updateLoggers();
            appender.stop();
        }
        assertFalse(captured.isEmpty());
        for (String line : captured) {
            assertFalse(line.contains(SECRET), line);
        }
    }
}
