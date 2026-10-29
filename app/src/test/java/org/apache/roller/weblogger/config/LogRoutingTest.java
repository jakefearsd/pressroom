package org.apache.roller.weblogger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.roller.weblogger.build.ProjectNames;
import org.apache.roller.weblogger.build.RepoFiles;
import org.apache.roller.weblogger.business.WeblogRedirectManager;
import org.apache.roller.weblogger.ui.controllers.AdminLog;
import org.apache.roller.weblogger.ui.restapi.mcp.McpToolExecutor;
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

    /**
     * Loggers deliberately without their own {@code <Logger>}: they reach the
     * console through Root. Characterised 2026-10-29, routing unchanged.
     */
    private static final Map<String, String> ROOT_ONLY_BY_DESIGN = Map.of(
            "roller.mcp", "characterised 2026-10-29: reaches the console through Root",
            "roller.media.import", "characterised 2026-10-29: reaches the console through Root",
            "roller.redirects", "characterised 2026-10-29: reaches the console through Root");

    private static final Set<String> KNOWN_CONSTANTS =
            Set.of("NAME", "LOG_NAME", "WeblogRedirectManager.LOG_NAME", "AdminLog.NAME");

    private static final Pattern GET_LOGGER = Pattern.compile("getLogger\\(\\s*([^()]*?)\\s*\\)");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /**
     * Characterisation: the root package's logger is what gives the whole
     * application its file log and level; a package move that misses it drops
     * everything to Root.
     */
    @Test
    void theProjectRootPackageHasItsOwnLoggerToFileAndConsole() throws Exception {
        Document config = config();
        Element logger = loggerNamed(config, ProjectNames.currentRoot());
        assertNotNull(logger, "log4j2.xml has no Logger named " + ProjectNames.currentRoot());
        Map<String, Set<String>> wrapped = wrappedAppenders(config);
        assertTrue(reaches(refs(logger), wrapped, "roller"), "the root package logger reaches roller.log");
        assertTrue(reaches(refs(logger), wrapped, "console"), "the root package logger reaches the console");
    }

    /** Characterisation: every logger name the code uses is routed or consciously left to Root. */
    @Test
    void everyNamedLoggerHasADecidedRoute() throws Exception {
        Set<String> names = new TreeSet<>(List.of(AdminLog.NAME, McpToolExecutor.LOG_NAME,
                WeblogRedirectManager.LOG_NAME));
        for (String argument : getLoggerArguments()) {
            Matcher literal = STRING_LITERAL.matcher(argument);
            if (literal.matches()) {
                names.add(literal.group(1));
            }
        }
        assertTrue(names.size() >= 6, "found the named loggers: " + names);
        Document config = config();
        for (String name : names) {
            boolean routed = loggerNamed(config, name) != null;
            assertTrue(routed || ROOT_ONLY_BY_DESIGN.containsKey(name),
                    "logger " + name + " has no <Logger> in log4j2.xml and is not listed in ROOT_ONLY_BY_DESIGN");
            assertFalse(routed && ROOT_ONLY_BY_DESIGN.containsKey(name),
                    "logger " + name + " now has a route; drop it from ROOT_ONLY_BY_DESIGN");
        }
        for (String listed : ROOT_ONLY_BY_DESIGN.keySet()) {
            assertTrue(names.contains(listed), "ROOT_ONLY_BY_DESIGN names " + listed + ", which no code uses");
        }
    }

    /** Characterisation: a constant-named logger cannot slip past the check above. */
    @Test
    void everyNonLiteralGetLoggerArgumentIsAKnownConstant() {
        for (String argument : getLoggerArguments()) {
            if (argument.endsWith(".class") || STRING_LITERAL.matcher(argument).matches()) {
                continue;
            }
            assertTrue(KNOWN_CONSTANTS.contains(argument),
                    "getLogger(" + argument + ") is not a class, a literal or a known constant; "
                            + "add it to everyNamedLoggerHasADecidedRoute's inputs");
        }
    }

    /** The argument text of every getLogger call in tracked main Java (calls may span lines). */
    private static List<String> getLoggerArguments() {
        List<String> arguments = new ArrayList<>();
        for (Path file : RepoFiles.trackedFiles()) {
            String path = file.toString().replace('\\', '/');
            if (path.startsWith("app/src/main/java/") && path.endsWith(".java")) {
                Matcher m = GET_LOGGER.matcher(RepoFiles.read(file));
                while (m.find()) {
                    arguments.add(m.group(1));
                }
            }
        }
        return arguments;
    }

    private static Element loggerNamed(Document config, String name) {
        NodeList loggers = config.getElementsByTagName("Logger");
        for (int i = 0; i < loggers.getLength(); i++) {
            Element logger = (Element) loggers.item(i);
            if (name.equals(logger.getAttribute("name"))) {
                return logger;
            }
        }
        return null;
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
