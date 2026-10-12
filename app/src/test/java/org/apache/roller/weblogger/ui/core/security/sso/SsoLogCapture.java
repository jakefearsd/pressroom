package org.apache.roller.weblogger.ui.core.security.sso;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Captures log events for one test, then puts back whatever configuration the
 * loggers had before: the {@code roller.sso} logger alone ({@link #start()}),
 * or every logger at every level ({@link #everyLogger()}).
 */
public final class SsoLogCapture implements AutoCloseable {

    private static final String LOGGER = "roller.sso";
    private static final String APPENDER = "sso-capture";

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final AbstractAppender appender;
    private final Runnable restore;

    private SsoLogCapture(boolean everyLogger) {
        appender = new AbstractAppender(APPENDER, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        restore = everyLogger ? attachEverywhere(config) : replaceSsoLogger(config);
        ctx.updateLoggers();
    }

    /** Captures {@code roller.sso} at INFO, as production configures it. */
    static SsoLogCapture start() {
        return new SsoLogCapture(false);
    }

    /**
     * Captures every logger at every level: the root and each configured logger
     * are lowered to {@code ALL} for the test, so a library's DEBUG or TRACE line
     * is captured as well as Roller's own. For a test that asserts what no log
     * line may hold.
     */
    public static SsoLogCapture everyLogger() {
        return new SsoLogCapture(true);
    }

    private Runnable replaceSsoLogger(Configuration config) {
        LoggerConfig previous = config.getLoggers().get(LOGGER);
        if (previous != null) {
            config.removeLogger(LOGGER);
        }
        LoggerConfig capture = new LoggerConfig(LOGGER, Level.INFO, false);
        capture.addAppender(appender, Level.INFO, null);
        config.addLogger(LOGGER, capture);
        return () -> {
            config.removeLogger(LOGGER);
            if (previous != null) {
                config.addLogger(LOGGER, previous);
            }
        };
    }

    private Runnable attachEverywhere(Configuration config) {
        // The explicit level, which may be null (inherit): restoring getLevel() would pin
        // an inheriting logger at whatever its parent said at the time.
        Map<LoggerConfig, Level> levels = new IdentityHashMap<>();
        levels.put(config.getRootLogger(), config.getRootLogger().getExplicitLevel());
        config.getLoggers().values().forEach(logger -> levels.put(logger, logger.getExplicitLevel()));
        levels.keySet().forEach(logger -> {
            logger.setLevel(Level.ALL);
            logger.addAppender(appender, Level.ALL, null);
        });
        return () -> levels.forEach((logger, level) -> {
            logger.removeAppender(APPENDER);
            logger.setLevel(level);
        });
    }

    /** @return the formatted messages logged at {@code level} */
    public List<String> lines(Level level) {
        synchronized (events) {
            return events.stream().filter(e -> e.getLevel() == level)
                    .map(e -> e.getMessage().getFormattedMessage()).toList();
        }
    }

    /**
     * @return every captured event as text: its formatted message, and each
     *         throwable along its cause chain as {@code toString()} gives it
     *         (class and message)
     */
    public List<String> everything() {
        List<String> text = new ArrayList<>();
        synchronized (events) {
            for (LogEvent event : events) {
                text.add(event.getMessage().getFormattedMessage());
                Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Throwable t = event.getThrown(); t != null && seen.add(t); t = t.getCause()) {
                    text.add(t.toString());
                }
            }
        }
        return text;
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
        restore.run();
        ctx.updateLoggers();
        appender.stop();
    }
}
