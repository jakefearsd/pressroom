/*
* Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  The ASF licenses this file to You
* under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.  For additional information regarding
* copyright in this work, please see the NOTICE file in the top level
* directory of this distribution.
*/
package com.wikantik.pressroom.ui;

import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every message code a JSP asks for exists in the default resource
 * bundle.
 *
 * <p>Spring's {@code <spring:message>} throws {@code JspTagException} when a code
 * is missing, which becomes an HTTP 500 for the whole page. Nothing catches that
 * at build time: the JSP compiles, the code is just a string literal, and the
 * page only dies when somebody opens it.
 *
 * <p>That is not hypothetical. {@code UserEdit.jsp} asked for
 * {@code userAdmin.tip.username} and {@code userAdmin.tip.userEnabled} while the
 * bundle defined {@code userAdmin.tip.userName} and {@code userAdmin.tip.enabled}
 * -- a capital N and a renamed key. The admin "Create User" page returned 500 for
 * everyone until a browser integration test happened to open it.
 *
 * <p>Only the default bundle is checked. Translations are allowed to lag; a
 * missing translation falls back to the default, but a missing default is a 500.
 */
public class MessageKeyTest {

    private static final Path JSP_ROOT = Paths.get("src/main/webapp/WEB-INF");

    private static final String DEFAULT_BUNDLE = "/ApplicationResources.properties";

    /**
     * Matches {@code code='some.key'} / {@code code="some.key"} on spring:message
     * and fmt:message tags. Only literal codes are checked -- a code assembled
     * from EL cannot be resolved statically, and those are rare enough to accept.
     */
    private static final Pattern MESSAGE_CODE = Pattern.compile(
            "<(?:spring|fmt):message\\b[^>]*\\bcode\\s*=\\s*[\"']([A-Za-z0-9_.]+)[\"']");

    @Test
    public void everyMessageCodeUsedInAJspExists() throws IOException {
        Properties bundle = loadDefaultBundle();
        Set<String> missing = new TreeSet<>();
        List<String> where = new ArrayList<>();
        int checked = 0;

        for (Path jsp : templateFiles()) {
            String content = Files.readString(jsp, StandardCharsets.UTF_8);
            Matcher matcher = MESSAGE_CODE.matcher(content);
            while (matcher.find()) {
                String code = matcher.group(1);
                checked++;
                if (!bundle.containsKey(code)) {
                    missing.add(code);
                    where.add(code + "  (" + JSP_ROOT.relativize(jsp) + ")");
                }
            }
        }

        assertTrue(checked > 0,
                "Found no <spring:message> codes under " + JSP_ROOT.toAbsolutePath()
                        + " -- the scan is not looking where it thinks it is.");

        assertTrue(missing.isEmpty(),
                "JSPs reference message codes that ApplicationResources.properties does not "
                        + "define. spring:message throws JspTagException for a missing code, so "
                        + "each of these renders as HTTP 500:\n  "
                        + String.join("\n  ", where));
    }

    /** The React admin's sources (spec A5): {@code t('key')} literals count as uses, and must exist. */
    private static final Path REACT_ROOT = Paths.get("frontend/src/admin");

    /** {@code t('some.key')} / {@code t("some.key")}: the React admin's message call, literal keys only. */
    private static final Pattern REACT_MESSAGE_KEY = Pattern.compile("\\bt\\(\\s*[\"']([A-Za-z0-9_.]+)[\"']");

    @Test
    public void everyMessageKeyUsedInTheReactAdminExists() throws IOException {
        Properties bundle = loadDefaultBundle();
        Map<String, String> missing = new TreeMap<>();
        reactKeyUsages(REACT_ROOT).forEach((key, file) -> {
            if (!bundle.containsKey(key)) {
                missing.put(key, file);
            }
        });

        assertTrue(missing.isEmpty(), "src/admin calls t() with keys ApplicationResources.properties does not "
                + "define (the app would show the raw key): " + missing);
    }

    /** Guards the guard, with a fixture: the React scan finds literals in .ts and .tsx and nothing else. */
    @Test
    public void theReactScanFindsLiteralKeysInTsAndTsxAndCountsThemAsUses(@TempDir Path fixture) throws IOException {
        Files.createDirectories(fixture.resolve("api"));
        Files.writeString(fixture.resolve("Screen.tsx"),
                "export const A = () => <p>{t('react.only.key')} {t( \"second.key\" )} {format('not.a.key')}</p>;");
        Files.writeString(fixture.resolve("api/util.ts"), "const x = t('third.key'); const y = t(dynamic);");
        Files.writeString(fixture.resolve("ignored.js"), "t('js.key')");

        Map<String, String> found = reactKeyUsages(fixture);

        assertEquals(Set.of("react.only.key", "second.key", "third.key"), found.keySet());
        assertEquals("Screen.tsx", found.get("react.only.key"));
        String sources = reactSourceText(fixture);
        assertTrue(isWholeKeyReferenced("react.only.key", sources, keyTokens(sources)),
                "a key used only by React is not an orphan");
    }

    private static Map<String, String> reactKeyUsages(Path root) throws IOException {
        Map<String, String> usages = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return usages;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(MessageKeyTest::isReactSource).toList()) {
                Matcher matcher = REACT_MESSAGE_KEY.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    usages.putIfAbsent(matcher.group(1), root.relativize(p).toString());
                }
            }
        }
        return usages;
    }

    private static String reactSourceText(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        if (Files.isDirectory(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path p : paths.filter(MessageKeyTest::isReactSource).toList()) {
                    all.append(Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        return all.toString();
    }

    private static boolean isReactSource(Path p) {
        String n = p.getFileName().toString();
        return Files.isRegularFile(p) && (n.endsWith(".ts") || n.endsWith(".tsx")) && !n.endsWith(".d.ts");
    }

    /**
     * Guards the guard: if the scan stopped finding Login.jsp, the check above
     * would pass while proving nothing.
     */
    @Test
    public void theScanCoversTheAdminPages() throws IOException {
        Path userEdit = JSP_ROOT.resolve("jsps/core/Login.jsp");
        assertTrue(Files.exists(userEdit), "Expected " + userEdit.toAbsolutePath());
        assertTrue(templateFiles().contains(userEdit),
                userEdit + " is not picked up by the template scan");

        String content = Files.readString(userEdit, StandardCharsets.UTF_8);
        assertTrue(MESSAGE_CODE.matcher(content).find(),
                userEdit + " no longer uses spring:message; update this test to follow it.");
    }

    /**
     * The Java-side twin of {@link #everyMessageCodeUsedInAJspExists()}: every
     * key a controller names with a literal must exist in the base bundle too.
     *
     * <p>This half fails <em>quietly</em> where the JSP half fails loudly.
     * {@code BaseController.getText} passes the key itself as the default
     * message, so a missing key is not an exception and not a log line -- the
     * page simply shows {@code mediaFile.edit.title} where its heading should
     * be. The same mechanism is what lets a raw English sentence be passed
     * where a key belongs and look like it works: {@code addError(model,
     * "Error updating configuration", request)} renders in English on an English
     * install and in English on every other install too, untranslatable by
     * construction.
     *
     * <p>Covered call shapes, all literal-key only (see {@link
     * MessageUsageScanner}): {@code getText}, {@code addError} / {@code
     * addMessage} / {@code addFlashError} / {@code addFlashMessage} in both
     * their {@code BaseController} and {@code PressroomMessages} forms, a {@code
     * getPageTitle()} that returns a literal, {@code
     * addAttribute("pageTitle", "...")}, a shared rule's {@code
     * RuleViolation.invalid/conflict(field, "key", List.of(...), ...)}, and a
     * rule class's "key or null" return ({@code return cond ? "key" : null;}).
     */
    @Test
    public void everyMessageKeyNamedInJavaExists() throws IOException {
        Properties bundle = loadDefaultBundle();
        Set<String> missing = new TreeSet<>();
        List<String> where = new ArrayList<>();
        int checked = 0;

        for (MessageUsageScanner.Usage usage : MessageUsageScanner.scanJavaSources()) {
            checked++;
            if (!bundle.containsKey(usage.key())) {
                missing.add(usage.key());
                where.add(usage.key() + "  (" + usage.where() + ")");
            }
        }

        assertTrue(checked > 200,
                "Only " + checked + " literal message keys were found under "
                        + MessageUsageScanner.JAVA_ROOT.toAbsolutePath()
                        + " -- the scan is not looking where it thinks it is.");

        assertEquals(EXPECTED_MISSING_JAVA_KEYS, missing,
                "Java code names message keys that ApplicationResources.properties does not "
                        + "define. These do not throw -- getText() falls back to the key itself -- "
                        + "so each one either prints a raw key or hard-codes untranslatable "
                        + "English on a real screen:\n  " + String.join("\n  ", where));
    }

    /**
     * Empty, and it stays empty.
     *
     * <p>Task 7 filled this with nineteen offenders -- three genuine keys that
     * had never been added to the bundle, and sixteen English sentences handed
     * to {@code addError} where a key belongs -- and Task 8 repaired every one.
     * The assertion is equality rather than containment, so an empty set is a
     * live gate: the next raw sentence passed to {@code addError} fails the
     * build instead of quietly shipping untranslatable English.
     */
    private static final Set<String> EXPECTED_MISSING_JAVA_KEYS = Set.of();

    /**
     * Bundle keys nothing refers to are dead weight in eight translated files --
     * and, worse, a magnet for the near-miss defect this whole class exists for:
     * an orphan called {@code userAdmin.title} sits one dot away from the {@code
     * userAdmin.title.editUser} that is actually used.
     *
     * <p><b>Matching is on whole keys, not substrings.</b> The obvious
     * {@code allSources.contains(key)} reports a key as used whenever any
     * <em>longer</em> key containing it is used, so it hid seven orphans behind
     * their own siblings: {@code categoriesForm.root} behind {@code
     * categoriesForm.rootPrompt}, {@code error.upload.file} behind {@code
     * error.upload.filemax}, {@code pageForm.template} behind {@code
     * pageForm.templateLanguage}, and so on. A key is counted as referenced only
     * when it appears delimited by something that is not another key character
     * -- letters, digits, {@code _} and {@code .} all continue a key.
     *
     * <p>Note what whole-key matching does <em>not</em> make an orphan: the
     * one-word key {@code error} looks like the same near-miss and is not one --
     * {@code tiles-errorpage.jsp} really does say {@code <spring:message
     * code="error"/>}. It is listed here because it was proposed for deletion on
     * the strength of the pattern rather than the reference, and deleting it
     * would have turned every error page into a second error.
     *
     * <p>Keys addressed from XML rather than from a literal are excluded by
     * <em>reading the XML</em> ({@link #dynamicallyAddressedKeys()}) rather than
     * by a hand-maintained count, because the count drifted: it was written as
     * "31 + 16 + 2 = 49" while the tabbedmenu bucket had grown to 17 -- the
     * seventeenth, {@code tabbedmenu.website}, being invisible only because
     * substring matching found it inside {@code tabbedmenu.website.members}.
     * Two mechanisms feed it:
     * <ul>
     *   <li>{@code runtimeConfigDefs.xml} {@code key="configForm.x"} attributes.
     *       GlobalConfig.jsp renders display groups and properties generically
     *       via {@code ${dg.key}} / property metadata, never a literal code.</li>
     *   <li>{@code admin-menu.xml} / {@code editor-menu.xml} {@code name="..."}
     *       attributes: {@code MenuHelper} copies the XML {@code name} into
     *       {@code MenuTab}/{@code MenuTabItem} {@code key}, which the JSPs render
     *       as {@code <spring:message code="${tab.key}">} -- see {@code
     *       tiles/bannerStatus.jsp} and {@code admin/GlobalConfig.jsp}.</li>
     *   <li>{@code ProblemCode}: each API problem code names its {@code
     *       api.problem.*} key, which the API renders by the code, never by
     *       a literal key ({@code ProblemCodeCatalogTest} holds every such key
     *       to a code, so none is orphaned unseen).</li>
     * </ul>
     */
    @Test
    public void reportsBundleKeysNoJspOrControllerUses() throws IOException {
        Properties bundle = loadDefaultBundle();
        assertFalse(bundle.isEmpty(), "Default bundle is empty");
        String allSources = readAllSources();
        Set<String> tokens = keyTokens(allSources);
        Set<String> dynamic = dynamicallyAddressedKeys();

        Set<String> orphans = new TreeSet<>();
        for (String key : bundle.stringPropertyNames()) {
            if (dynamic.contains(key) || UNEXPLAINED_UNREFERENCED_KEYS.contains(key)) {
                continue;
            }
            if (!isWholeKeyReferenced(key, allSources, tokens)) {
                orphans.add(key);
            }
        }

        assertEquals(EXPECTED_ORPHANS, orphans,
                "Bundle keys with no whole-key reference anywhere in the JSPs, tags, Velocity "
                        + "templates or Java sources, and not addressed from runtimeConfigDefs.xml "
                        + "or the menu XML either. Delete them from all eight bundles rather than "
                        + "adding them here:\n  " + String.join("\n  ", orphans));
    }

    /**
     * Empty, and it stays empty.
     *
     * <p>Task 7 found seven orphans, every one a shorter prefix of a key that
     * IS used -- which is exactly why substring matching had never seen them --
     * and Task 22 deleted all seven from the base bundle and the seven
     * translations. Equality rather than containment: the next key that loses
     * its last reference fails the build here instead of quietly riding along
     * in eight files.
     *
     * <p>What is deliberately NOT in this history: {@code error}. It was
     * proposed for deletion on the strength of the same prefix pattern, and
     * {@code tiles-errorpage.jsp} really does render it -- deleting it would
     * have turned every error page into a second error. See this test's own
     * javadoc.
     */
    private static final Set<String> EXPECTED_ORPHANS = Set.of();

    /**
     * {@code macro.weblog.datetime.toStringFormat} and {@code
     * macro.weblog.time.toStringFormat}: no literal reference anywhere, and NOT
     * addressed via either XML mechanism. Pre-existing and long-standing; carried
     * here rather than in {@link #EXPECTED_ORPHANS} because deleting them has
     * never been anyone's task and would be a guess about a formatting hook, not
     * a cleanup. See W1 Task 6's report.
     */
    private static final Set<String> UNEXPLAINED_UNREFERENCED_KEYS = Set.of(
            "macro.weblog.datetime.toStringFormat",
            "macro.weblog.time.toStringFormat");

    /**
     * Guards the speed-up in {@link #isWholeKeyReferenced}: it must answer
     * exactly what {@link #wholeKeyPattern} answers, which is the rule this
     * class documents. Written against the regex definition before the token
     * lookup existed, as a characterisation of it.
     *
     * <p>The cases are the ones the rule exists for (a key that is a prefix
     * or a suffix of a longer key, a key at either end of the text, a key
     * beside punctuation that is not a key character) plus a key containing
     * a non-key character, which the token lookup cannot see and must hand to
     * the regex.
     */
    @Test
    public void tokenLookupAgreesWithTheWholeKeyRule() {
        String text = "userAdmin.title.editUser x error.upload.filemax \"pageForm.template\" "
                + "<spring:message code='error'/> a-b.c tabbedmenu.website.members "
                + "_lead.key trail.key_ (paren.key) end.key";
        Set<String> tokens = keyTokens(text);
        List<String> keys = List.of(
                "userAdmin.title", "userAdmin.title.editUser", "title.editUser",
                "error.upload.file", "error.upload.filemax", "upload", "pageForm.template",
                "error", "a", "b.c", "a-b.c", "a-b", "tabbedmenu.website",
                "lead.key", "_lead.key", "trail.key", "trail.key_", "paren.key",
                "end.key", "userAdmin.title.editUser x", "missing.key");
        for (String key : keys) {
            assertEquals(wholeKeyPattern(key).matcher(text).find(),
                    isWholeKeyReferenced(key, text, tokens),
                    "token lookup and the whole-key regex disagree on '" + key + "'");
        }
    }

    /**
     * A key character is a letter, a digit, {@code _} or {@code .}; a reference
     * counts only when the key is not flanked by one, so {@code userAdmin.title}
     * does not match inside {@code userAdmin.title.editUser}.
     */
    private static Pattern wholeKeyPattern(String key) {
        return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(key) + "(?![A-Za-z0-9_.])");
    }

    private static final Pattern KEY_RUN = Pattern.compile("[A-Za-z0-9_.]+");

    /**
     * Every maximal run of key characters in {@code text}. An occurrence of a
     * key made only of key characters is flanked by non-key characters exactly
     * when it IS one of these runs, so one pass over the sources replaces a
     * regex scan per bundle key (that scan was ~24 s locally and ~40 s on CI,
     * the slowest class in the unit suite).
     */
    private static Set<String> keyTokens(String text) {
        Set<String> tokens = new HashSet<>();
        Matcher m = KEY_RUN.matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /**
     * {@link #wholeKeyPattern}'s answer, from the token set when the key is made
     * only of key characters, and from the regex itself when it is not (a key
     * containing, say, {@code -} spans several runs).
     */
    private static boolean isWholeKeyReferenced(String key, String text, Set<String> tokens) {
        if (KEY_RUN.matcher(key).matches()) {
            return tokens.contains(key);
        }
        return wholeKeyPattern(key).matcher(text).find();
    }

    /**
     * Message keys named by an XML attribute instead of by a literal in code:
     * {@code key="..."} in {@code runtimeConfigDefs.xml} and {@code name="..."}
     * in the two menu files.
     */
    private static Set<String> dynamicallyAddressedKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        collectAttribute(keys, Paths.get(RESOURCE_ROOT, "config/runtimeConfigDefs.xml"), "key");
        collectAttribute(keys, Paths.get(RESOURCE_ROOT, "ui/menu/admin-menu.xml"), "name");
        collectAttribute(keys, Paths.get(RESOURCE_ROOT, "ui/menu/editor-menu.xml"), "name");
        for (ProblemCode code : ProblemCode.values()) {
            keys.add(code.key());
        }
        return keys;
    }

    private static void collectAttribute(Set<String> into, Path xml, String attribute) throws IOException {
        assertTrue(Files.exists(xml), "Expected " + xml.toAbsolutePath()
                + " -- the dynamic-key exclusion is reading a file that no longer exists.");
        Matcher matcher = Pattern.compile("\\b" + attribute + "\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(xml, StandardCharsets.UTF_8));
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private static final String RESOURCE_ROOT =
            "src/main/resources/com/wikantik/pressroom";


    private Properties loadDefaultBundle() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(DEFAULT_BUNDLE)) {
            assertTrue(in != null, DEFAULT_BUNDLE + " not found on the classpath");
            props.load(in);
        }
        return props;
    }

    private String readAllSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : List.of(Paths.get("src/main/webapp"), Paths.get("src/main/java"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".jsp") || name.endsWith(".tag")
                            || name.endsWith(".vm") || name.endsWith(".java")) {
                        all.append(Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        all.append(reactSourceText(REACT_ROOT));
        return all.toString();
    }

    private List<Path> templateFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(JSP_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".jsp") || n.endsWith(".tag");
                    })
                    .toList();
        }
    }
}
