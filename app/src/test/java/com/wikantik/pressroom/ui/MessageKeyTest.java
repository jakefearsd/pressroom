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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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

    /**
     * Guards the guard: if the scan stopped finding UserEdit.jsp, the check above
     * would pass while proving nothing.
     */
    @Test
    public void theScanCoversTheAdminPages() throws IOException {
        Path userEdit = JSP_ROOT.resolve("jsps/admin/UserEdit.jsp");
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
     * getPageTitle()} that returns a literal, and {@code
     * addAttribute("pageTitle", "...")}.
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
     * </ul>
     */
    @Test
    public void reportsBundleKeysNoJspOrControllerUses() throws IOException {
        Properties bundle = loadDefaultBundle();
        assertFalse(bundle.isEmpty(), "Default bundle is empty");
        String allSources = readAllSources();
        Set<String> dynamic = dynamicallyAddressedKeys();

        Set<String> orphans = new TreeSet<>();
        for (String key : bundle.stringPropertyNames()) {
            if (dynamic.contains(key) || UNEXPLAINED_UNREFERENCED_KEYS.contains(key)) {
                continue;
            }
            if (!wholeKeyPattern(key).matcher(allSources).find()) {
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
     * A key character is a letter, a digit, {@code _} or {@code .}; a reference
     * counts only when the key is not flanked by one, so {@code userAdmin.title}
     * does not match inside {@code userAdmin.title.editUser}.
     */
    private static Pattern wholeKeyPattern(String key) {
        return Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(key) + "(?![A-Za-z0-9_.])");
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
