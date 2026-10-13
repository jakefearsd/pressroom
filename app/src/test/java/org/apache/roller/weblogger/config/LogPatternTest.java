package org.apache.roller.weblogger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The production log pattern writes one line per log call.
 *
 * <p>Reader-supplied values reach log messages: a sign-in failure's reason, a
 * display name, a request path. A CR or LF inside one would otherwise start a
 * new line in {@code roller.log} that reads as a separate event, a forged
 * entry an operator cannot tell from a real one. find-sec-bugs reports this as
 * CRLF_INJECTION_LOGS at every call site; the fix is made once, here, in the
 * layout, and the family is excluded from the SpotBugs gate on the strength of
 * this test.
 */
class LogPatternTest {

    private static final Path CONFIG = Path.of("src/main/resources/log4j2.xml");

    @Test
    void aMessageWithLineBreaksIsWrittenAsOneLine() throws Exception {
        String written = format(event(
                "Reader eve\r\nINFO  2026-10-12 12:00:00 [main] RollerSecurity - admin granted", null));

        assertEquals(1, written.lines().count(), "one log call, one line: " + written);
        assertTrue(written.contains("eve\\r\\nINFO"),
                "the breaks are shown escaped, not silently dropped: " + written);
    }

    /** Stack traces stay readable: the encoding applies to the message, not to the trace. */
    @Test
    void aStackTraceIsStillWrittenOverSeveralLines() throws Exception {
        String written = format(event("Comment store failed", new IllegalStateException("boom")));

        assertTrue(written.lines().count() > 2, "the trace follows the message line: " + written);
        assertTrue(written.contains("java.lang.IllegalStateException: boom"), written);
        assertTrue(written.lines().anyMatch(l -> l.trim().startsWith("at ")), written);
    }

    /** An appender with its own pattern would bypass the encoding without any test noticing. */
    @Test
    void everyLayoutUsesTheSharedPattern() throws Exception {
        NodeList layouts = config().getElementsByTagName("PatternLayout");
        assertTrue(layouts.getLength() > 0, "log4j2.xml has pattern layouts");
        for (int i = 0; i < layouts.getLength(); i++) {
            assertEquals("${logging.pattern}", ((Element) layouts.item(i)).getAttribute("pattern"),
                    "every PatternLayout in log4j2.xml uses ${logging.pattern}");
        }
    }

    private static String format(LogEvent event) throws Exception {
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern(configuredPattern())
                .withConfiguration(new DefaultConfiguration())
                .build();
        return layout.toSerializable(event);
    }

    private static LogEvent event(String message, Throwable thrown) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("roller.sso")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .build();
    }

    private static String configuredPattern() throws Exception {
        NodeList properties = config().getElementsByTagName("property");
        for (int i = 0; i < properties.getLength(); i++) {
            Element property = (Element) properties.item(i);
            if ("logging.pattern".equals(property.getAttribute("name"))) {
                String pattern = property.getTextContent().trim();
                assertFalse(pattern.isEmpty(), "logging.pattern is empty");
                return pattern;
            }
        }
        throw new AssertionError("log4j2.xml defines no logging.pattern property");
    }

    private static Document config() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(CONFIG.toFile());
        assertNotNull(document.getDocumentElement());
        return document;
    }
}
