package com.wikantik.pressroom.config;

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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import com.wikantik.pressroom.build.ProjectNames;
import com.wikantik.pressroom.build.RepoFiles;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.ui.controllers.AdminLog;
import com.wikantik.pressroom.ui.restapi.mcp.McpToolExecutor;
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

    /** The file stays: the IT harness and the SSO runbook read pressroom.log. */
    @Test
    void namedLoggersStillReachTheFile() throws Exception {
        Document config = config();
        Map<String, Set<String>> wrapped = wrappedAppenders(config);
        Element pressroom = (Element) config.getElementsByTagName("Logger").item(0);
        assertTrue(reaches(refs(pressroom), wrapped, "pressroom"), "com.wikantik.pressroom still writes pressroom.log");
    }

    /**
     * pressroom.log rolls on every start and at 8 MB, and nothing deleted the
     * rolled {@code .gz} files, so inside a long-lived container they
     * accumulated without bound. The rollover keeps a bounded set: a Delete
     * action matching the rolled files, capped by count.
     */
    @Test
    void rolledLogFilesAreBounded() throws Exception {
        NodeList files = config().getElementsByTagName("RollingFile");
        assertTrue(files.getLength() > 0, "pressroom.log is a rolling file");
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
            "pressroom.mcp", "characterised 2026-10-29: reaches the console through Root",
            "pressroom.media.import", "characterised 2026-10-29: reaches the console through Root",
            "pressroom.redirects", "characterised 2026-10-29: reaches the console through Root");

    private static final Pattern GET_LOGGER = Pattern.compile("getLogger\\(\\s*([^()]*?)\\s*\\)");

    private static final Pattern CONSTANT_ARGUMENT = Pattern.compile("(?:(\\w+)\\.)?(NAME|LOG_NAME)");

    private static final Pattern SUPERTYPES = Pattern.compile("\\b(?:extends|implements)\\s+([\\w\\s,<>.]+?)\\s*\\{");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** What one scan of Java source found: the logger names, the calls it could not classify, the call count. */
    private static final class Scan {
        final Set<String> names = new TreeSet<>();
        final List<String> problems = new ArrayList<>();
        int calls;
        int classified;
    }

    /**
     * Characterisation: the root package's logger is what gives the whole
     * application its file log and level; a package move that misses it drops
     * everything to Root.
     *
     * <p>Proven to bite: renaming the {@code com.wikantik.pressroom} Logger to
     * {@code com.wikantik.pressroomx} in log4j2.xml fails this test.
     */
    @Test
    void theProjectRootPackageHasItsOwnLoggerToFileAndConsole() throws Exception {
        Document config = config();
        Element logger = loggerNamed(config, ProjectNames.currentRoot());
        assertNotNull(logger, "log4j2.xml has no Logger named " + ProjectNames.currentRoot());
        Map<String, Set<String>> wrapped = wrappedAppenders(config);
        assertTrue(reaches(refs(logger), wrapped, "pressroom"), "the root package logger reaches pressroom.log");
        assertTrue(reaches(refs(logger), wrapped, "console"), "the root package logger reaches the console");
    }

    /**
     * Characterisation: every logger name the code uses is routed or consciously left to Root.
     *
     * <p>Proven to bite: deleting the {@code pressroom.sso} Logger from log4j2.xml fails this test.
     */
    @Test
    void everyNamedLoggerHasADecidedRoute() throws Exception {
        Scan scan = scanTrackedMainJava();
        // cross-check: the three constants by symbol, so a renamed constant breaks compilation here
        assertTrue(scan.names.containsAll(List.of(AdminLog.NAME, McpToolExecutor.LOG_NAME,
                WeblogRedirectManager.LOG_NAME)), "the scan resolves the known constants: " + scan.names);
        assertTrue(scan.names.size() >= 6, "found the named loggers: " + scan.names);
        Document config = config();
        List<String> missing = missingRoutes(scan.names, config);
        assertTrue(missing.isEmpty(), "no decided route: " + missing);
        for (String name : scan.names) {
            assertFalse(loggerNamed(config, name) != null && ROOT_ONLY_BY_DESIGN.containsKey(name),
                    "logger " + name + " now has a route; drop it from ROOT_ONLY_BY_DESIGN");
        }
        for (String listed : ROOT_ONLY_BY_DESIGN.keySet()) {
            assertTrue(scan.names.contains(listed), "ROOT_ONLY_BY_DESIGN names " + listed + ", which no code uses");
        }
    }

    /**
     * Characterisation: no getLogger call escapes classification. Each is a
     * class, a string literal, or a NAME/LOG_NAME constant whose value is
     * resolved from source; anything else (a nested call, an unknown
     * constant) fails with file:line.
     */
    @Test
    void everyGetLoggerCallIsClassified() {
        Scan scan = scanTrackedMainJava();
        assertTrue(scan.problems.isEmpty(), "unclassified getLogger calls: " + scan.problems);
        assertEquals(scan.calls, scan.classified, "every getLogger( occurrence is classified");
        assertTrue(scan.calls > 100, "the scan saw the tracked sources");
    }

    /** The scanner itself: a new bare constant is resolved and routed-checked; a nested call is reported. */
    @Test
    void theScannerResolvesNewConstantsAndRejectsWhatItCannotRead() throws Exception {
        Scan constant = new Scan();
        scanSource("Foo.java", "class Foo { static final String LOG_NAME = \"pressroom.foo\";\n"
                + " Logger l = LoggerFactory.getLogger(LOG_NAME); }", name -> null, constant);
        assertTrue(constant.problems.isEmpty(), "a bare constant resolves: " + constant.problems);
        assertEquals(Set.of("pressroom.foo"), constant.names);
        assertEquals(List.of("pressroom.foo"), missingRoutes(constant.names, config()),
                "an unrouted new logger name is reported");

        Scan qualified = new Scan();
        scanSource("Bar.java", "class Bar { Logger l = LoggerFactory.getLogger(Other.NAME); }",
                cls -> "Other".equals(cls) ? "interface Other { String NAME = \"pressroom.other\"; }" : null, qualified);
        assertEquals(Set.of("pressroom.other"), qualified.names);

        Scan unresolved = new Scan();
        scanSource("Baz.java", "class Baz { Logger l = LoggerFactory.getLogger(NAME); }", name -> null, unresolved);
        assertEquals(1, unresolved.problems.size(), "a constant with no value in source is a problem");

        Scan nested = new Scan();
        scanSource("Qux.java", "class Qux {\n Logger l = LoggerFactory.getLogger(Foo.class.getName()); }",
                name -> null, nested);
        assertEquals(1, nested.calls);
        assertEquals(0, nested.classified);
        assertEquals(1, nested.problems.size());
        assertTrue(nested.problems.get(0).contains("Qux.java:2"), nested.problems.get(0));
    }

    private static List<String> missingRoutes(Set<String> names, Document config) {
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (loggerNamed(config, name) == null && !ROOT_ONLY_BY_DESIGN.containsKey(name)) {
                missing.add(name);
            }
        }
        return missing;
    }

    private static Scan scanTrackedMainJava() {
        Map<String, Path> byClass = new HashMap<>();
        List<Path> sources = new ArrayList<>();
        for (Path file : RepoFiles.trackedFiles()) {
            String path = file.toString().replace('\\', '/');
            if (path.startsWith("app/src/main/java/") && path.endsWith(".java")) {
                sources.add(file);
                byClass.put(file.getFileName().toString().replace(".java", ""), file);
            }
        }
        Scan scan = new Scan();
        for (Path file : sources) {
            scanSource(file.toString(), RepoFiles.read(file), cls -> {
                Path other = byClass.get(cls);
                return other == null ? null : RepoFiles.read(other);
            }, scan);
        }
        return scan;
    }

    /** Classifies every {@code getLogger(} in the source; {@code sourceOf} maps a class's simple name to its source. */
    private static void scanSource(String label, String source, Function<String, String> sourceOf, Scan scan) {
        int from = 0;
        while ((from = source.indexOf("getLogger(", from)) >= 0) {
            int at = from;
            from += "getLogger(".length();
            scan.calls++;
            String where = label + ":" + (1 + (int) source.substring(0, at).chars().filter(c -> c == '\n').count());
            Matcher call = GET_LOGGER.matcher(source);
            if (!call.find(at) || call.start() != at) {
                scan.problems.add(where + " getLogger argument not readable (nested parentheses?)");
                continue;
            }
            String argument = call.group(1);
            Matcher literal = STRING_LITERAL.matcher(argument);
            Matcher constant = CONSTANT_ARGUMENT.matcher(argument);
            if (argument.endsWith(".class")) {
                scan.classified++;
            } else if (literal.matches()) {
                scan.names.add(literal.group(1));
                scan.classified++;
            } else if (constant.matches()) {
                String owner = constant.group(1);
                String value = constantValue(owner == null ? source : sourceOf.apply(owner), constant.group(2));
                if (value == null && owner == null) {
                    value = inheritedConstant(source, constant.group(2), sourceOf);
                }
                if (value == null) {
                    scan.problems.add(where + " constant " + argument + " has no string value in source");
                } else {
                    scan.names.add(value);
                    scan.classified++;
                }
            } else {
                scan.problems.add(where + " getLogger(" + argument + ") is not a class, literal or NAME/LOG_NAME");
            }
        }
    }

    /** A bare constant may be inherited from a supertype named in the class header. */
    private static String inheritedConstant(String source, String constant, Function<String, String> sourceOf) {
        Matcher header = SUPERTYPES.matcher(source);
        while (header.find()) {
            for (String type : header.group(1).split("[,\\s]+")) {
                String simple = type.replaceAll("<.*", "");
                String value = simple.isEmpty() ? null : constantValue(sourceOf.apply(simple), constant);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String constantValue(String source, String constant) {
        if (source == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\b" + constant + "\\s*=\\s*\"([^\"]+)\"").matcher(source);
        return m.find() ? m.group(1) : null;
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
