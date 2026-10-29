package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.wikantik.pressroom.business.runnable.PressroomTask;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.rendering.RendererFactory;
import com.wikantik.pressroom.ui.rendering.model.Model;
import com.wikantik.pressroom.util.cache.CacheFactory;
import org.junit.jupiter.api.Test;

/**
 * Every class named in a configuration property loads, is concrete, has the
 * public no-argument constructor {@code Reflection.newInstance} needs, and is
 * the type the consumer casts it to.
 *
 * <p>Characterisation tests, written against the current configuration: they
 * pass today and exist so the Phase 1 package move cannot leave a stale name
 * in {@code pressroom.properties}, {@code velocity.properties} or a test
 * override, where it would only fail at runtime (a lenient or log-and-continue
 * loader). The splitter test is the one genuinely new behaviour.
 *
 * <p>Proven to bite: changing {@code model.MenuModel} to {@code model.MenuModl}
 * in {@code rendering.pageModels} fails
 * {@code everyConfiguredClassLoadsAndHasTheExpectedType} (class not found);
 * pointing {@code cache.defaultFactory} at
 * {@code com.wikantik.pressroom.util.cache.CacheManager}, which loads but
 * is not a {@code CacheFactory}, fails it as well (wrong type). Both restored.
 */
class ConfiguredClassListTest {

    private static final String LOADER_BASE = "org.apache.velocity.runtime.resource.loader.ResourceLoader";
    private static final Path PRESSROOM_RESOURCES = Path.of("app/src/main/resources");
    private static final Path TEST_RESOURCES = Path.of("app/src/test/resources");
    private static final Path VELOCITY = Path.of("app/src/main/webapp/WEB-INF/velocity.properties");

    private static final Pattern CLASS_TOKEN =
            Pattern.compile("[a-z]\\w*(\\.[a-z]\\w*)+\\.[A-Z][\\w$]*");

    /**
     * Velocity 1.x leftover that Velocity 2 ignores and whose class no longer
     * exists on the classpath; it names a class nothing instantiates, so it is
     * the one class-valued key deliberately not loaded.
     */
    private static final java.util.Set<String> DEAD_KEYS = java.util.Set.of("runtime.log.logsystem.class");

    /** Key pattern ({@code <name>} and {@code <n>} stand for the variable part) to required type. */
    private static final Map<String, Class<?>> EXPECTED_TYPES = expectedTypes();

    private static Map<String, Class<?>> expectedTypes() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("tasks.<name>.class", PressroomTask.class);
        m.put("cache.defaultFactory", CacheFactory.class);
        m.put("rendering.pressroomRendererFactories", RendererFactory.class);
        m.put("rendering.userRendererFactories", RendererFactory.class);
        for (String k : List.of("pageModels", "feedModels", "searchModels", "previewModels", "siteModels")) {
            m.put("rendering." + k, Model.class);
        }
        m.put("lucene.analyzer.class", load("org.apache.lucene.analysis.Analyzer"));
        m.put("eclipselink.logging.logger", load("org.eclipse.persistence.logging.SessionLog"));
        m.put("resource.loader.<n>.class", load(LOADER_BASE));
        m.put("introspector.uberspect.class", load("org.apache.velocity.util.introspection.Uberspect"));
        return m;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** Splits a comma-separated class list, trimming and dropping blanks. */
    static List<String> classList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** A loaded properties file with its origin, for failure messages. */
    private record Source(String origin, Properties props) {
    }

    private static Properties parse(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    private static Properties pressroomProperties() {
        try (InputStream in = WebloggerConfig.class.getResourceAsStream("pressroom.properties")) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Key patterns overridden or defined in a file: any file naming a checked key joins the scan. */
    private static boolean touchesCheckedKey(Properties p) {
        return p.stringPropertyNames().stream().anyMatch(k -> patternFor(k) != null);
    }

    /** The EXPECTED_TYPES key a concrete key maps to, or null. */
    static String patternFor(String key) {
        if (EXPECTED_TYPES.containsKey(key) || DEAD_KEYS.contains(key)) {
            return key;
        }
        if (key.matches("tasks\\.[^.]+\\.class")) {
            return "tasks.<name>.class";
        }
        if (key.matches("resource\\.loader\\.[^.]+\\.class")) {
            return "resource.loader.<n>.class";
        }
        return null;
    }

    private static List<Source> sources() {
        List<Source> all = new ArrayList<>();
        all.add(new Source("pressroom.properties", pressroomProperties()));
        all.add(new Source("velocity.properties", parse(RepoFiles.read(VELOCITY))));
        for (Path root : List.of(PRESSROOM_RESOURCES, TEST_RESOURCES)) {
            try (Stream<Path> walk = Files.walk(RepoFiles.repoRoot().resolve(root))) {
                for (Path f : walk.filter(x -> x.toString().endsWith(".properties")).sorted().toList()) {
                    Path rel = RepoFiles.repoRoot().relativize(f);
                    if (rel.endsWith("pressroom.properties")) {
                        continue;
                    }
                    Properties p = parse(RepoFiles.read(rel));
                    if (touchesCheckedKey(p)) {
                        all.add(new Source(rel.toString(), p));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return all;
    }

    @Test
    void parsesContinuationsAndEmptyValues() {
        Properties p = parse("k=a.B,\\\n  c.D ,");
        assertEquals(List.of("a.B", "c.D"), classList(p.getProperty("k")));
        assertEquals(List.of(), classList(""));
    }

    @Test
    void everyConfiguredClassLoadsAndHasTheExpectedType() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Source s : sources()) {
            for (String key : s.props().stringPropertyNames()) {
                String pattern = patternFor(key);
                if (pattern == null) {
                    continue;
                }
                Class<?> type = EXPECTED_TYPES.get(pattern);
                if (type == null) {
                    continue; // DEAD_KEYS
                }
                for (String name : classList(s.props().getProperty(key))) {
                    checked++;
                    String where = s.origin() + " " + key + " -> " + name;
                    try {
                        Class<?> c = Class.forName(name);
                        if (Modifier.isAbstract(c.getModifiers()) || c.isInterface()) {
                            problems.add(where + " is not concrete");
                        } else if (!type.isAssignableFrom(c)) {
                            problems.add(where + " is not a " + type.getName());
                        } else {
                            // Reflection.newInstance uses the declared constructor and
                            // setAccessible, so non-public is fine there; Velocity
                            // instantiates its loaders itself and needs a public one.
                            if (type.getName().equals(LOADER_BASE)) {
                                c.getConstructor();
                            } else {
                                c.getDeclaredConstructor();
                            }
                        }
                    } catch (ClassNotFoundException | LinkageError e) {
                        problems.add(where + " does not load: " + e);
                    } catch (NoSuchMethodException e) {
                        problems.add(where + " has no suitable no-argument constructor");
                    }
                }
            }
        }
        assertTrue(checked > 15, "expected to check the whole configuration, checked " + checked);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyEnabledTaskAndLoaderHasAClass() {
        List<String> problems = new ArrayList<>();
        for (Source s : sources()) {
            Properties p = s.props();
            for (String task : classList(p.getProperty("tasks.enabled"))) {
                if (!hasValue(p, "tasks." + task + ".class") && !hasValue(pressroomProperties(), "tasks." + task + ".class")) {
                    problems.add(s.origin() + ": enabled task " + task + " has no class");
                }
            }
            for (String loader : classList(p.getProperty("resource.loaders"))) {
                if (!"class".equals(loader) && !hasValue(p, "resource.loader." + loader + ".class")) {
                    problems.add(s.origin() + ": loader " + loader + " has no class");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static boolean hasValue(Properties p, String key) {
        String v = p.getProperty(key);
        return v != null && !v.isBlank();
    }

    @Test
    void everyClassValuedPropertyIsChecked() {
        List<String> unchecked = new ArrayList<>();
        for (Source s : sources()) {
            for (String key : s.props().stringPropertyNames()) {
                Matcher m = CLASS_TOKEN.matcher(s.props().getProperty(key));
                if (m.find() && patternFor(key) == null) {
                    unchecked.add(s.origin() + " " + key + "=" + s.props().getProperty(key));
                }
            }
        }
        assertTrue(unchecked.isEmpty(),
                "class-valued keys missing from EXPECTED_TYPES:\n" + String.join("\n", unchecked));
    }
}
