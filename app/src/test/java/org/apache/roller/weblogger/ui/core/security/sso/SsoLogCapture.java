package org.apache.roller.weblogger.ui.core.security.sso;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Captures the {@code roller.sso} logger for one test, then puts back whatever
 * configuration that logger had before.
 */
final class SsoLogCapture implements AutoCloseable {

    private static final String LOGGER = "roller.sso";

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final AbstractAppender appender;
    private final LoggerConfig previous;

    private SsoLogCapture() {
        appender = new AbstractAppender("sso-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        previous = config.getLoggers().get(LOGGER);
        if (previous != null) {
            config.removeLogger(LOGGER);
        }
        LoggerConfig capture = new LoggerConfig(LOGGER, Level.INFO, false);
        capture.addAppender(appender, Level.INFO, null);
        config.addLogger(LOGGER, capture);
        ctx.updateLoggers();
    }

    static SsoLogCapture start() {
        return new SsoLogCapture();
    }

    /** @return the formatted messages logged at {@code level} */
    List<String> lines(Level level) {
        synchronized (events) {
            return events.stream().filter(e -> e.getLevel() == level)
                    .map(e -> e.getMessage().getFormattedMessage()).toList();
        }
    }

    /** @return true when any captured event carries a throwable */
    boolean anyThrowable() {
        synchronized (events) {
            return events.stream().anyMatch(e -> e.getThrown() != null);
        }
    }

    @Override
    public void close() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.removeLogger(LOGGER);
        if (previous != null) {
            config.addLogger(LOGGER, previous);
        }
        ctx.updateLoggers();
        appender.stop();
    }
}
