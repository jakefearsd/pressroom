package org.apache.roller.weblogger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Every logger the production configuration names reaches standard output.
 *
 * <p>In the container the file appender writes inside the container's own
 * filesystem, so a logger routed only to it never shows in {@code docker
 * compose logs app} and its history goes with the container on every deploy.
 * The runbook sends operators to {@code compose logs}; this pins the routing
 * that makes that advice true. Standard output is also where SA-6 (the suite's
 * logging decision) expects a service's log.
 */
class LogRoutingTest {

    private static final Path CONFIG = Path.of("src/main/resources/log4j2.xml");

    @Test
    void everyNamedLoggerReachesTheConsole() throws Exception {
        Document config = config();
        Map<String, Set<String>> wrapped = wrappedAppenders(config);
        NodeList loggers = config.getElementsByTagName("Logger");
        assertTrue(loggers.getLength() > 0, "log4j2.xml names loggers");
        for (int i = 0; i < loggers.getLength(); i++) {
            Element logger = (Element) loggers.item(i);
            assertTrue(reaches(refs(logger), wrapped, "console"),
                    "logger " + logger.getAttribute("name") + " never reaches the console appender, "
                            + "so docker compose logs would not show it");
        }
    }

    @Test
    void theRootLoggerReachesTheConsole() throws Exception {
        Document config = config();
        Element root = (Element) config.getElementsByTagName("Root").item(0);
        assertNotNull(root, "log4j2.xml has a Root logger");
        assertTrue(reaches(refs(root), wrappedAppenders(config), "console"));
    }

    /** The console is standard output: Docker keeps stdout and stderr alike, but one stream keeps the order. */
    @Test
    void theConsoleIsStandardOutput() throws Exception {
        NodeList consoles = config().getElementsByTagName("Console");
        assertEquals(1, consoles.getLength(), "one console appender");
        Element console = (Element) consoles.item(0);
        assertEquals("console", console.getAttribute("name"));
        assertEquals("SYSTEM_OUT", console.getAttribute("target"));
    }

    /** The file stays: the IT harness and the SSO runbook read roller.log. */
    @Test
    void namedLoggersStillReachTheFile() throws Exception {
        Document config = config();
        Map<String, Set<String>> wrapped = wrappedAppenders(config);
        Element roller = (Element) config.getElementsByTagName("Logger").item(0);
        assertTrue(reaches(refs(roller), wrapped, "roller"), "org.apache.roller still writes roller.log");
    }

    /**
     * roller.log rolls on every start and at 8 MB, and nothing deleted the
     * rolled {@code .gz} files, so inside a long-lived container they
     * accumulated without bound. The rollover keeps a bounded set: a Delete
     * action matching the rolled files, capped by count.
     */
    @Test
    void rolledLogFilesAreBounded() throws Exception {
        NodeList files = config().getElementsByTagName("RollingFile");
        assertTrue(files.getLength() > 0, "roller.log is a rolling file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            NodeList strategies = file.getElementsByTagName("DefaultRolloverStrategy");
            assertEquals(1, strategies.getLength(), file.getAttribute("name") + " has no rollover strategy");
            Element strategy = (Element) strategies.item(0);
            NodeList deletes = strategy.getElementsByTagName("Delete");
            assertEquals(1, deletes.getLength(), file.getAttribute("name") + " never deletes rolled files");
            Element delete = (Element) deletes.item(0);
            NodeList globs = delete.getElementsByTagName("IfFileName");
            assertEquals(1, globs.getLength(), "the delete names which files it may remove");
            assertTrue(((Element) globs.item(0)).getAttribute("glob").endsWith(".log.gz"),
                    "the delete may remove only rolled logs");
            assertEquals(1, delete.getElementsByTagName("IfAccumulatedFileCount").getLength(),
                    "the delete keeps a bounded count");
        }
    }

    private static boolean reaches(Set<String> start, Map<String, Set<String>> wrapped, String target) {
        Deque<String> todo = new ArrayDeque<>(start);
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (name.equals(target)) {
                return true;
            }
            if (seen.add(name)) {
                todo.addAll(wrapped.getOrDefault(name, Set.of()));
            }
        }
        return false;
    }

    /** Appenders that forward to others (Async), by name, to the names they forward to. */
    private static Map<String, Set<String>> wrappedAppenders(Document config) {
        Map<String, Set<String>> wrapped = new HashMap<>();
        Element appenders = (Element) config.getElementsByTagName("Appenders").item(0);
        NodeList children = appenders.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) {
                wrapped.put(element.getAttribute("name"), refs(element));
            }
        }
        return wrapped;
    }

    private static Set<String> refs(Element element) {
        Set<String> names = new HashSet<>();
        NodeList refs = element.getElementsByTagName("AppenderRef");
        for (int i = 0; i < refs.getLength(); i++) {
            String ref = ((Element) refs.item(i)).getAttribute("ref");
            assertFalse(ref.isBlank(), "an AppenderRef without a ref");
            names.add(ref);
        }
        return names;
    }

    private static Document config() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(CONFIG.toFile());
        assertNotNull(document.getDocumentElement());
        return document;
    }
}
