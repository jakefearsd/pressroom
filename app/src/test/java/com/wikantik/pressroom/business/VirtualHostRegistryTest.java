package com.wikantik.pressroom.business;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Host-header normalisation, which decides whether a request resolves at all,
 * and the registry's behaviour as an instance that is handed its
 * {@code WeblogManager} (DI wave, plan Task 4) rather than locating it.
 */
class VirtualHostRegistryTest {

    @Test
    void aPortIsStripped() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("b.example.com:8443"));
    }

    @Test
    void caseIsFolded() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("B.Example.COM"));
    }

    /** A fully-qualified name with the root label is the same host. */
    @Test
    void aTrailingDotIsStripped() {
        assertEquals("b.example.com", VirtualHostRegistry.normalise("b.example.com."));
    }

    @Test
    void nullAndBlankNormaliseToNull() {
        assertNull(VirtualHostRegistry.normalise(null));
        assertNull(VirtualHostRegistry.normalise("   "));
    }

    /**
     * An IPv6 literal Host header is bracketed and contains colons that are not
     * a port separator. Stripping at the first colon would corrupt it, so the
     * brackets are kept and only a port after the closing bracket is removed.
     */
    @Test
    void anIpv6LiteralKeepsItsColons() {
        assertEquals("[::1]", VirtualHostRegistry.normalise("[::1]:8080"));
        assertEquals("[::1]", VirtualHostRegistry.normalise("[::1]"));
    }

    // ------------------------------------------------------------ log capture

    /** Captures the log events VirtualHostRegistry's own logger actually emits. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        CapturingAppender() {
            super("virtual-host-registry-test-appender", null, null, false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private CapturingAppender appender;
    private Logger logger;
    private Level previousLevel;

    private void startCapturingLogs() {
        logger = (Logger) LogManager.getLogger(VirtualHostRegistry.class);
        previousLevel = logger.getLevel();
        appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void stopCapturingLogs() {
        if (logger != null) {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
        }
        if (appender != null) {
            appender.stop();
        }
    }

    // ------------------------------------------ the injected-manager instance

    private static Weblog weblogWithDomain(String handle, String domain) {
        Weblog w = new Weblog();
        w.setHandle(handle);
        w.setCustomDomain(domain);
        return w;
    }

    private static WeblogManager managerWith(Weblog... weblogs) throws WebloggerException {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(null, null, null, null, 0, -1)).thenReturn(List.of(weblogs));
        return manager;
    }

    @Test
    void resolvesAHostThroughTheManagerItWasGiven() throws WebloggerException {
        VirtualHostRegistry registry =
                new VirtualHostRegistry(managerWith(weblogWithDomain("b", "B.Example.com:443")));

        assertEquals("b", registry.handleFor("b.example.com"));
        assertEquals("b.example.com", registry.hostFor("b"));
        assertNull(registry.handleFor("other.example.com"));
        assertNull(registry.hostFor("nobody"));
        assertNull(registry.hostFor(null));
    }

    /** The map is built once and cached; {@code invalidate()} is what rebuilds it. */
    @Test
    void invalidateRebuildsFromTheManagerOnTheNextRead() throws WebloggerException {
        WeblogManager manager = managerWith(weblogWithDomain("b", "b.example.com"));
        VirtualHostRegistry registry = new VirtualHostRegistry(manager);

        registry.handleFor("b.example.com");
        registry.handleFor("b.example.com");
        verify(manager, times(1)).getWeblogs(null, null, null, null, 0, -1);

        registry.invalidate();
        registry.handleFor("b.example.com");
        verify(manager, times(2)).getWeblogs(null, null, null, null, 0, -1);
    }

    /**
     * M9: once the tier is up, an exception here means every custom domain
     * stops resolving site-wide -- that must not be a debug-only line nobody
     * enables in production. (The pre-bootstrap "expected, stay quiet" case is
     * the callers' guard -- the filters and the mapper ask
     * {@code WebloggerProvider.isBootstrapped()} before touching the registry.)
     */
    @Test
    void mapFailureLogsAtWarn() throws WebloggerException {
        startCapturingLogs();
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(null, null, null, null, 0, -1))
                .thenThrow(new WebloggerException("boom"));

        assertNull(new VirtualHostRegistry(manager).handleFor("anything.example.com"));

        assertTrue(appender.events.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "map() must log at WARN, so a live failure is not silently invisible at the "
                        + "default log level");
    }

    /** A failed build is not cached: the next read tries the manager again. */
    @Test
    void aFailedBuildIsNotCached() throws WebloggerException {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(null, null, null, null, 0, -1))
                .thenThrow(new WebloggerException("boom"))
                .thenReturn(List.of(weblogWithDomain("b", "b.example.com")));
        VirtualHostRegistry registry = new VirtualHostRegistry(manager);

        assertNull(registry.handleFor("b.example.com"));
        assertEquals("b", registry.handleFor("b.example.com"));
    }

}
