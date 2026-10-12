package org.apache.roller.weblogger.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OidcDiscoverySelfCheckTest {

    private static final SsoSettings SETTINGS =
            new SsoSettings("id", "secret", SsoSettings.GOOGLE_DISCOVERY, "Google");

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private AbstractAppender appender;

    @BeforeEach
    void capture() {
        appender = new AbstractAppender("sso-selfcheck-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig("roller.sso", Level.INFO, false);
        lc.addAppender(appender, Level.INFO, null);
        ctx.getConfiguration().addLogger("roller.sso", lc);
        ctx.updateLoggers();
    }

    @AfterEach
    void release() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().removeLogger("roller.sso");
        ctx.updateLoggers();
        appender.stop();
    }

    private long count(Level level) {
        synchronized (events) {
            return events.stream().filter(e -> e.getLevel() == level).count();
        }
    }

    @Test
    void logsAWarnPerFailedAttemptAndNeverThrows() throws Exception {
        var repo = new LazyOidcClientRegistrationRepository(SETTINGS,
                iss -> { throw new IllegalArgumentException("unreachable"); });
        Thread t = new OidcDiscoverySelfCheck(repo, 1).start();
        assertTrue(t.isDaemon());
        assertEquals("roller-sso-selfcheck", t.getName());
        t.join(5000);
        assertFalse(t.isAlive());
        // Exactly one WARN per failed attempt (the repository's line); no summary WARN.
        assertEquals(3, count(Level.WARN));
        synchronized (events) {
            assertTrue(events.stream().allMatch(e -> e.getMessage().getFormattedMessage().contains("discovery")));
        }
    }

    @Test
    void logsOneInfoOnSuccessAndStops() throws Exception {
        var repo = new LazyOidcClientRegistrationRepository(SETTINGS,
                LazyOidcClientRegistrationRepositoryTest::stubBuilder);
        Thread t = new OidcDiscoverySelfCheck(repo, 1).start();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(1, count(Level.INFO));
        assertEquals(0, count(Level.WARN));
    }
}
