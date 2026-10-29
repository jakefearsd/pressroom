package org.apache.roller.weblogger.ui.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps rendering coverage from silently missing a shipped template. Velocity
 * is lenient (an unresolved reference prints as literal text), so after a
 * rename every theme and every {@code WEB-INF/velocity} template must be
 * rendered by some test that would notice. A new theme directory or a new
 * {@code .vm} fails here until it is named.
 *
 * <p>This is a characterisation test: it passes on arrival against today's
 * tree and pins the current set of themes and templates.
 *
 * <p>Proven to bite: with an empty {@code themes/scratch/} directory holding
 * one file, {@code everyBundledThemeIsRendered} failed (expected [frontpage,
 * journal, portfolio, travel] but was [frontpage, journal, portfolio,
 * scratch, travel]); with an empty stray {@code WEB-INF/velocity/templates/stray.vm},
 * {@code everyVelocityTemplateHasANamedRenderingTest} failed ("every .vm
 * under ... must be in RENDERED_BY"). Both files were deleted afterwards.
 */
class RenderedTemplateCoverageTest {

    private static final Path THEMES = Path.of("src/main/webapp/themes");
    private static final Path VELOCITY = Path.of("src/main/webapp/WEB-INF/velocity");

    /** Each shared template and the test class that renders it through a real servlet or VelocityRenderer. */
    private static final Map<String, Class<?>> RENDERED_BY = new TreeMap<>();

    /**
     * Shipped templates no test renders. Empty is the goal; an entry is a
     * known gap, not coverage. noop_decorator.vm has no reference anywhere
     * in main code, themes or tests (the decorator mechanism was never wired
     * up, see VelocityRenderer lines 141-143). Deleting it is a follow-up
     * outside Phase 0.
     */
    private static final Set<String> KNOWN_UNRENDERED =
            Set.of("templates/weblog/noop_decorator.vm");

    static {
        // Macro libraries (velocimacro.library.path); loaded and called by every themed page render.
        Class<?> leak = named("org.apache.roller.weblogger.ui.rendering.ThemeReferenceLeakTest");
        RENDERED_BY.put("weblog.vm", leak);
        // Library with no macro of its own to call (a comment); loaded on every engine init, so any theme render reaches it.
        RENDERED_BY.put("roller-custom.vm", leak);
        RENDERED_BY.put("feeds.vm",
                named("org.apache.roller.weblogger.ui.rendering.servlets.FeedServletRenderingTest"));
        // error-page.vm and error-parse.vm: the 500 bodies are asserted by marker comment.
        Class<?> errors = named("org.apache.roller.weblogger.ui.rendering.servlets.RenderErrorStatusTest");
        RENDERED_BY.put("templates/error-page.vm", errors);
        RENDERED_BY.put("templates/error-parse.vm", errors);
        // weblog-entries-atom.vm: GET .../entries/atom on an ordinary weblog.
        RENDERED_BY.put("templates/feeds/weblog-entries-atom.vm",
                named("org.apache.roller.weblogger.ui.rendering.servlets.FeedServletRenderingTest"));
        // site-entries-atom.vm: the "site atom feed" scan on the aggregated front-page weblog.
        RENDERED_BY.put("templates/feeds/site-entries-atom.vm", leak);
        // page.vm: static-page fallback of a CUSTOM theme with no templates.
        RENDERED_BY.put("templates/weblog/page.vm",
                named("org.apache.roller.weblogger.ui.rendering.FallbackPageTitleTest"));
        // preview.vm: the PreviewServlet shell.
        RENDERED_BY.put("templates/weblog/preview.vm",
                named("org.apache.roller.weblogger.ui.rendering.servlets.PreviewShellRenderingTest"));
    }

    private static Class<?> named(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("rendering test " + className + " does not exist", e);
        }
    }

    @Test
    void everyBundledThemeIsRendered() throws IOException {
        Set<String> expected = new TreeSet<>(ThemeReferenceLeakTest.CONTENT_THEMES);
        expected.add("frontpage");
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(THEMES)) {
            dirs.filter(Files::isDirectory).forEach(d -> actual.add(d.getFileName().toString()));
        }
        assertEquals(expected, actual,
                "every directory under " + THEMES + " must be rendered by ThemeReferenceLeakTest");
    }

    @Test
    void everyVelocityTemplateHasANamedRenderingTest() throws IOException {
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.walk(VELOCITY)) {
            files.filter(f -> f.toString().endsWith(".vm"))
                    .forEach(f -> actual.add(VELOCITY.relativize(f).toString().replace('\\', '/')));
        }
        Set<String> accountedFor = new TreeSet<>(RENDERED_BY.keySet());
        accountedFor.addAll(KNOWN_UNRENDERED);
        assertEquals(accountedFor, actual,
                "every .vm under " + VELOCITY + " must be in RENDERED_BY (or KNOWN_UNRENDERED)");
        List<String> both = new ArrayList<>(RENDERED_BY.keySet());
        both.retainAll(KNOWN_UNRENDERED);
        assertEquals(List.of(), both, "a template is either rendered or a known gap");
    }
}
