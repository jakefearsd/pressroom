/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the end state of the 2026-08-22 "retire the static service locator"
 * wave (spec acceptance criteria 1, 2 and 12). While the wave was in flight
 * this test was also its migration ledger -- an explicit allowlist of the
 * main-source files still reaching the tier through the static, shrunk by
 * every task; the ledger emptied at Task 19 and the shim was deleted at Task
 * 20, so the rules below are now unconditional.
 *
 * <p>Source scan, same family as {@code QualityGatePomTest} and
 * {@code ControllerMetadataTest}: nothing here needs a container. Java
 * sources are scanned with comments and javadoc stripped first (a mention in
 * prose is not a call site); JSPs and other webapp files are scanned as plain
 * text.
 *
 * <ol>
 *   <li>Nothing under {@code app/src/main/java}, {@code app/src/test/java}
 *       or {@code app/src/main/webapp} names the deleted locator class
 *       ({@link #LOCATOR}). There is no static to reach the business tier
 *       through; a class that needs it injects {@code Weblogger} or
 *       {@code WebloggerProvider}, and a test hands it a {@code MockWeblogger}
 *       facade or {@code TestUtils.weblogger()}.</li>
 *   <li>No main-source {@code static} field has a business-tier type
 *       ({@link #BUSINESS_TIER_TYPES}), except the two named residuals in
 *       {@link #STATIC_RESIDUALS}. This is the rule that stops the locator
 *       coming back under another name.</li>
 *   <li>No JPA entity or domain pojo under {@code pojos/} references a
 *       business-tier type. Entities are data plus invariants; behaviour
 *       that needs a collaborator belongs in a service (spec Decision 5).
 *       {@code pojos/wrapper/} is deliberately <em>outside</em> this rule:
 *       the wrappers are the Velocity presentation adapter and hold the
 *       {@code URLStrategy} and the facade by design.</li>
 * </ol>
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md},
 * Decisions 2, 5, 7, 8 and 9.
 */
class StaticServiceLocatorTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path MAIN = REPO.resolve("app/src/main/java");
    private static final Path TEST = REPO.resolve("app/src/test/java");
    private static final Path WEBAPP = REPO.resolve("app/src/main/webapp");
    private static final String PKG = "app/src/main/java/com/wikantik/pressroom/";

    /**
     * The deleted static shim's simple name. This test is the one file that
     * may spell it -- in its rule and its failure messages -- so it excludes
     * itself from rule 1 by path rather than by obfuscating the token.
     */
    static final String LOCATOR = "WebloggerFactory";
    private static final String SELF =
            "app/src/test/java/com/wikantik/pressroom/StaticServiceLocatorTest.java";

    /**
     * The two statics the spec permits to hold a business-tier reference
     * after the wave:
     * <ul>
     *   <li>{@code WebloggerRuntimeConfig} -- the one {@code PropertiesManager}
     *       behind the runtime-config facade, attached at bootstrap. Spec
     *       Decision 8; retired by Stage 2 (configuration as beans).</li>
     *   <li>{@code PressroomVelocity} -- the Velocity engine, which carries the
     *       facade as an application attribute for the two resource loaders
     *       Velocity instantiates itself. Spec Decision 4. (Holds no such
     *       field today; the allowance is named by the spec.)</li>
     * </ul>
     */
    static final Set<String> STATIC_RESIDUALS = Set.of(
            PKG + "config/WebloggerRuntimeConfig.java",
            PKG + "ui/rendering/velocity/PressroomVelocity.java");

    /**
     * The business-tier types, named explicitly rather than by a
     * {@code *Manager} wildcard (which would catch {@code CacheManager},
     * {@code EntityManager}, ...): the facade, its provider, the URL strategy,
     * the vhost registry, and every return type of a {@code getXManager()}
     * on {@code com.wikantik.pressroom.business.Weblogger}.
     */
    static final List<String> BUSINESS_TIER_TYPES = List.of(
            "Weblogger", "WebloggerProvider", "URLStrategy", "VirtualHostRegistry",
            "UserManager", "WeblogManager", "WeblogEntryManager", "PropertiesManager",
            "ThreadManager", "IndexManager", "ThemeManager", "PluginManager",
            "MediaFileManager", "FileContentManager", "WeblogPageManager", "EventManager",
            "FormSubmissionManager", "UserTokenManager", "ApiTokenManager", "EntryRenderer");

    /** What an entity may not name: the types above plus the render seam. */
    private static final Pattern POJO_BANNED = Pattern.compile(
            "\\b(" + String.join("|", BUSINESS_TIER_TYPES) + "|ShortcodeExpander)\\b");

    private static final Pattern LOCATOR_REFERENCE = Pattern.compile("\\b" + LOCATOR + "\\b");

    /**
     * A field declaration: {@code static} (any modifiers around it), then the
     * declared type, optional generics, the name, then {@code ;} or {@code =}.
     * Declared-type match only; a {@code static Supplier<Weblogger>} is not a
     * field of type {@code Weblogger} and is not what this rule is about.
     */
    private static final Pattern STATIC_BUSINESS_FIELD = Pattern.compile(
            "\\bstatic\\b[^;=(){}]*?\\b(" + String.join("|", BUSINESS_TIER_TYPES)
                    + ")\\b\\s*(<[^>]*>)?\\s+\\w+\\s*[;=]");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void nothingNamesTheDeletedLocator() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : javaSources(MAIN).entrySet()) {
            if (LOCATOR_REFERENCE.matcher(e.getValue()).find()) {
                problems.add(e.getKey() + " reaches the business tier through " + LOCATOR
                        + ", which no longer exists. Inject the dependency instead "
                        + "(constructor, @Lazy Weblogger, WebloggerProvider, or the init hook "
                        + "for models/tasks).");
            }
        }
        for (Map.Entry<String, String> e : javaSources(TEST).entrySet()) {
            if (e.getKey().equals(SELF)) {
                continue;
            }
            if (LOCATOR_REFERENCE.matcher(e.getValue()).find()) {
                problems.add(e.getKey() + " names " + LOCATOR + ", which no longer exists. "
                        + "Hand the class under test a MockWeblogger facade, or use "
                        + "TestUtils.weblogger() for the suite's real tier.");
            }
        }
        for (Map.Entry<String, String> e : webappFiles().entrySet()) {
            if (LOCATOR_REFERENCE.matcher(e.getValue()).find()) {
                problems.add(e.getKey() + " names " + LOCATOR + " in a scriptlet or EL; "
                        + "JSPs reach the tier through the WebloggerProvider bean "
                        + "(WebApplicationContextUtils), as footer.jsp and login-redirect.jsp do.");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void noMainSourceHoldsABusinessTierReferenceInAStaticField() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : javaSources(MAIN).entrySet()) {
            String file = e.getKey();
            if (STATIC_RESIDUALS.contains(file)) {
                continue;
            }
            Matcher m = STATIC_BUSINESS_FIELD.matcher(e.getValue());
            while (m.find()) {
                problems.add(file + " declares a static field of business-tier type "
                        + m.group(1) + " (`" + m.group().trim() + "`). A static holder is the "
                        + "service locator under another name: inject it. The only permitted "
                        + "residuals are " + STATIC_RESIDUALS + " (spec Decisions 4 and 8).");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void entitiesDoNotReferenceTheBusinessTier() throws IOException {
        String pojos = PKG + "pojos/";
        String wrappers = PKG + "pojos/wrapper/";
        Set<String> referencing = new TreeSet<>();
        for (Map.Entry<String, String> e : javaSources(MAIN).entrySet()) {
            String file = e.getKey();
            if (!file.startsWith(pojos) || file.startsWith(wrappers)) {
                continue;
            }
            Matcher m = POJO_BANNED.matcher(e.getValue());
            if (m.find()) {
                referencing.add(file + " (" + distinctMatches(m) + ")");
            }
        }

        List<String> problems = new ArrayList<>();
        for (String entry : referencing) {
            problems.add(entry + ": an entity may not reach a manager, the facade, the URL "
                    + "strategy or the render seam. Entities are data plus invariants -- "
                    + "move the behaviour to a service (spec Decision 5).");
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    @Test
    void theScanActuallySeesTheTree() throws IOException {
        // Guards the other three against a silently-empty walk (a moved source
        // root would make every rule pass vacuously).
        Map<String, String> main = javaSources(MAIN);
        assertTrue(main.size() > 400, "expected the full main source tree, saw " + main.size());
        assertTrue(main.containsKey(PKG + "business/SpringWebloggerProvider.java"),
                "expected to see the provider bean that replaced the locator");
        Map<String, String> test = javaSources(TEST);
        assertTrue(test.size() > 300, "expected the full test source tree, saw " + test.size());
        assertTrue(test.containsKey(SELF), "expected to see this test itself at " + SELF);
        Map<String, String> webapp = webappFiles();
        assertTrue(webapp.keySet().stream().anyMatch(f -> f.endsWith("/footer.jsp")),
                "expected the webapp scan to reach the JSP tiles, saw " + webapp.size() + " files");
    }

    // ---------------------------------------------------------------------

    /** Repo-relative path -> source with block and line comments stripped. */
    private static Map<String, String> javaSources(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.filter(x -> x.toString().endsWith(".java"))::iterator) {
                out.put(relative(p), stripComments(Files.readString(p)));
            }
        }
        if (out.isEmpty()) {
            fail("no sources found under " + root);
        }
        return out;
    }

    /** Repo-relative path -> raw text of every JSP, tag file, Velocity template and JS under the webapp. */
    private static Map<String, String> webappFiles() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(WEBAPP)) {
            for (Path p : (Iterable<Path>) paths.filter(StaticServiceLocatorTest::isTextual)::iterator) {
                out.put(relative(p), Files.readString(p));
            }
        }
        if (out.isEmpty()) {
            fail("no webapp files found under " + WEBAPP);
        }
        return out;
    }

    private static boolean isTextual(Path p) {
        String s = p.toString();
        return s.endsWith(".jsp") || s.endsWith(".jspf") || s.endsWith(".tag")
                || s.endsWith(".vm") || s.endsWith(".js") || s.endsWith(".xml");
    }

    private static String relative(Path p) {
        return REPO.relativize(p).toString().replace('\\', '/');
    }

    private static String stripComments(String source) {
        String noBlocks = BLOCK_COMMENT.matcher(source).replaceAll("");
        return LINE_COMMENT.matcher(noBlocks).replaceAll("");
    }

    private static String distinctMatches(Matcher firstFound) {
        Set<String> names = new TreeSet<>();
        names.add(firstFound.group(1));
        while (firstFound.find()) {
            names.add(firstFound.group(1));
        }
        return String.join(", ", names);
    }
}
