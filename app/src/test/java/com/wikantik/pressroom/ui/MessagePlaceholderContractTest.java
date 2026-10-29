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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every call site must pass exactly as many arguments as its message declares
 * {@code &#123;n&#125;} placeholders.
 *
 * <p>Both directions are silent defects in production, and neither shows up as
 * an exception:
 *
 * <ul>
 *   <li><b>Too few.</b> {@code alwaysUseMessageFormat} is {@code false} on the
 *       {@code ResourceBundleMessageSource} (see {@code WebMvcConfig}), so a
 *       no-argument lookup never runs the value through {@code MessageFormat}
 *       at all -- the raw pattern reaches the page and a reader sees a literal
 *       {@code &#123;0&#125;}. With arguments but too few, {@code MessageFormat}
 *       leaves the surplus placeholder in place, same visible result.</li>
 *   <li><b>Too many.</b> The extra argument is dropped without complaint, so
 *       the value the code went to the trouble of computing -- a category name,
 *       a username -- never reaches the reader. That is how {@code
 *       categoryForm.created} came to read "Category  created", with the double
 *       space where the name used to be.</li>
 * </ul>
 *
 * <p>Scanning is {@link MessageUsageScanner}'s job, shared with {@link
 * MessageKeyTest} so the two ratchets agree on what a call site is. Keys built
 * dynamically are invisible to it by construction and are simply not checked;
 * the small set of call sites whose argument list cannot be counted statically
 * is pinned by {@link #theUncountableCallSitesAreTheKnownTwo()} rather than
 * left to grow.
 */
public class MessagePlaceholderContractTest {

    private static final String DEFAULT_BUNDLE = "/ApplicationResources.properties";

    /**
     * {@code &#123;0&#125;} and also {@code &#123;0,date,...&#125;} -- the format
     * type is part of the placeholder, so a pattern matching only the bare form
     * would read a formatted placeholder as taking no arguments at all.
     *
     * <p>No bundle value carries the formatted form today: the last one,
     * {@code weblogEntryQuery.date.toStringFormat}, was deleted in task M1
     * when {@code <rc:date>} took over every admin timestamp (a date format
     * is not a translatable string; see
     * {@code JspConsistencyTest.noAdminTimestampIsFormattedWithFmtFormatDate},
     * which now forbids the key from coming back). The alternation stays: it
     * costs nothing and the next {@code &#123;0,number,...&#125;} anyone adds
     * would otherwise be miscounted silently.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*(\\d+)\\s*[,}]");

    /**
     * Messages deliberately allowed to declare a placeholder that no
     * server-side call site fills, because the raw pattern is handed to the
     * browser in a {@code data-template} attribute and JavaScript substitutes
     * {@code &#123;0&#125;} itself with a value only the browser knows:
     * {@code EntryEdit.jsp} / {@code PageEdit.jsp}'s {@code
     * roller-draft.js} substitution with the draft's local timestamp;
     * (task B3) every {@code .selection-bar}'s {@code .selection-count} span
     * on {@code Entries.jsp}/{@code Submissions.jsp}/{@code MediaFileView.jsp},
     * where {@code roller.js}'s delegated checkbox handler substitutes the
     * live count of checked rows; and (task A8) {@code editor.status.words},
     * which {@code EditorSurface.jsp} hands to the browser in
     * {@code #editorStatus}'s {@code data-words-template} attribute and
     * {@code rollerUpdateWordCount()} fills with both {@code &#123;0&#125;}
     * (word count) and {@code &#123;1&#125;} (reading minutes) -- neither is
     * known until {@code rollerEditor.stats()} runs in the browser.
     */
    private static final Set<String> CLIENT_SIDE_SUBSTITUTION = Set.of(
            "weblogEdit.draftRecovery.message",
            "selection.count",
            "editor.status.words");

    /**
     * Empty, and it stays empty.
     *
     * <p>Task 7 populated this with eleven legacy mismatches and Task 8 repaired
     * every one of them. It is kept as a set rather than deleted because the
     * assertion below is equality, not containment: a newly-introduced mismatch
     * is not absorbed by an empty expectation, and if a future wave ever has to
     * carry one deliberately, this is where it goes -- with a reason.
     *
     * <p>One defect this ratchet structurally cannot see, recorded so nobody
     * goes looking for a failure that was never going to fire: an argument whose
     * <em>value</em> is wrong. {@code MembersController} used to pass a
     * hardcoded {@code "1"} into "Changed permission for &#123;0&#125; user(s)"
     * -- one placeholder, one argument, contract satisfied, message wrong. It
     * was fixed by hand in Task 8 (a singular key carrying the username), not by
     * this test.
     */
    private static final Set<String> EXPECTED_LEGACY_OFFENDERS = Set.of();

    /**
     * Call sites whose argument count cannot be read off the source: both pass a
     * local {@code String[] args} whose length is not visible at the call.
     * (Both happen to be correct -- two-element arrays against two-placeholder
     * messages -- but the scanner cannot prove it, so it declines to guess.)
     * Pinned so the silently-skipped set cannot grow into a hole in the ratchet.
     */
    private static final Set<String> UNCOUNTABLE_CALL_SITES = Set.of(
            "business/FileContentManagerImpl.java -> error.upload.filemax",
            "business/FileContentManagerImpl.java -> error.upload.forbiddenFile");

    @Test
    public void everyCallSitePassesTheArgumentsItsMessageDeclares() throws IOException {
        Properties bundle = loadDefaultBundle();
        assertFalse(bundle.isEmpty(), "Default bundle is empty");
        Map<String, Integer> declaredEverywhere = highestPlaceholderPerKeyAcrossAllBundles();

        Map<String, List<String>> violations = new TreeMap<>();
        int checked = 0;

        for (MessageUsageScanner.Usage usage : MessageUsageScanner.scanAll()) {
            String value = bundle.getProperty(usage.key());
            if (value == null || usage.argCount() == MessageUsageScanner.UNRESOLVED
                    || CLIENT_SIDE_SUBSTITUTION.contains(usage.key())) {
                continue;
            }
            checked++;
            int declared = declaredEverywhere.getOrDefault(usage.key(), -1) + 1;
            if (usage.argCount() != declared) {
                violations.computeIfAbsent(usage.key(), k -> new ArrayList<>())
                        .add(usage.where() + " passes " + usage.argCount()
                                + ", the bundles declare " + declared + " (base value: \"" + value + "\")");
            }
        }

        assertTrue(checked > 500,
                "Only " + checked + " call sites were checked -- the scan is not looking "
                        + "where it thinks it is.");

        assertEquals(EXPECTED_LEGACY_OFFENDERS, violations.keySet(),
                "Message keys whose call sites disagree with their placeholders. Too few "
                        + "arguments prints a literal {n} on the page; too many silently drops "
                        + "the value. Neither throws:\n" + render(violations));
    }

    @Test
    public void theUncountableCallSitesAreTheKnownTwo() throws IOException {
        Set<String> uncountable = new TreeSet<>();
        for (MessageUsageScanner.Usage usage : MessageUsageScanner.scanAll()) {
            if (usage.argCount() == MessageUsageScanner.UNRESOLVED) {
                uncountable.add(shortSite(usage));
            }
        }
        assertEquals(UNCOUNTABLE_CALL_SITES, uncountable,
                "The set of call sites this scanner silently skips has changed. Every entry "
                        + "here is a hole in everyCallSitePassesTheArgumentsItsMessageDeclares(), "
                        + "so it may only grow with a stated reason.");
    }

    /**
     * Guards the allowlist: if {@code weblogEdit.draftRecovery.message} ever
     * stopped declaring a placeholder, the exemption above would be silently
     * excusing nothing and should be deleted rather than left behind.
     */
    @Test
    public void theClientSideSubstitutionAllowlistStillHasSomethingToExcuse() throws IOException {
        Properties bundle = loadDefaultBundle();
        for (String key : CLIENT_SIDE_SUBSTITUTION) {
            String value = bundle.getProperty(key);
            assertTrue(value != null && highestPlaceholderIndex(value) >= 0,
                    key + " is allowlisted for client-side {0} substitution but no longer "
                            + "declares a placeholder -- drop it from the allowlist.");
        }
    }


    /**
     * The highest {@code &#123;n&#125;} index each key declares in ANY shipped
     * bundle, not just the base one.
     *
     * <p>The contract a call site has to satisfy is "every translation of this
     * message", not "the English one". A translator who writes {@code
     * "Utilisateur [&#123;0&#125;] modifié"} against a base value carrying no
     * placeholder has made the call site wrong on a French install and nowhere
     * else -- and reading only the base bundle is precisely how that stays
     * invisible. Taking the maximum makes the base bundle the floor rather than
     * the whole answer: fixing such a mismatch means either passing the argument
     * everywhere or removing the placeholder from the translation, and both show
     * up here.
     */
    private static Map<String, Integer> highestPlaceholderPerKeyAcrossAllBundles() throws IOException {
        Map<String, Integer> highest = new TreeMap<>();
        Set<String> scanned = new TreeSet<>();
        try (Stream<Path> paths = Files.list(RESOURCE_ROOT)) {
            for (Path bundle : paths.filter(MessagePlaceholderContractTest::isMessageBundle).toList()) {
                scanned.add(bundle.getFileName().toString());
                Properties props = new Properties();
                try (Reader reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                for (String key : props.stringPropertyNames()) {
                    highest.merge(key, highestPlaceholderIndex(props.getProperty(key)), Math::max);
                }
            }
        }
        assertFalse(highest.isEmpty(), "No message bundles found under " + RESOURCE_ROOT.toAbsolutePath());
        assertEquals(SHIPPED_BUNDLES, scanned,
                "The set of shipped message bundles changed. A non-empty result is not "
                        + "enough of a floor here: this scan takes the MAXIMUM placeholder "
                        + "count across every translation, so a scan that quietly found only "
                        + "the base bundle would still pass while checking one file. Update "
                        + "this list (and MessageFormatRegressionTest's twin) deliberately.");
        return highest;
    }

    /**
     * Mirrors {@code MessageFormatRegressionTest.theScanCoversEveryTranslation}'s
     * pinned list. Both ratchets read every translation and both are silently
     * weakened by a scan that stops finding them, so both pin the set by name.
     */
    private static final Set<String> SHIPPED_BUNDLES = Set.of(
            "ApplicationResources.properties",
            "ApplicationResources_de.properties",
            "ApplicationResources_es.properties",
            "ApplicationResources_fr.properties",
            "ApplicationResources_ja.properties",
            "ApplicationResources_ko.properties",
            "ApplicationResources_ru.properties",
            "ApplicationResources_zh_CN.properties");

    private static boolean isMessageBundle(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("ApplicationResources") && name.endsWith(".properties");
    }

    private static final Path RESOURCE_ROOT = Paths.get("src/main/resources");

    private static String shortSite(MessageUsageScanner.Usage usage) {
        String path = usage.where().substring(0, usage.where().lastIndexOf(':'));
        int pkg = path.indexOf("com/wikantik/pressroom/");
        if (pkg >= 0) {
            path = path.substring(pkg + "com/wikantik/pressroom/".length());
        }
        return path.replace('\\', '/') + " -> " + usage.key();
    }

    private static int highestPlaceholderIndex(String value) {
        int highest = -1;
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest;
    }

    private static String render(Map<String, List<String>> violations) {
        StringBuilder out = new StringBuilder();
        violations.forEach((key, sites) -> {
            out.append("  ").append(key).append('\n');
            sites.forEach(site -> out.append("      ").append(site).append('\n'));
        });
        return out.toString();
    }

    private Properties loadDefaultBundle() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(DEFAULT_BUNDLE)) {
            assertTrue(in != null, DEFAULT_BUNDLE + " not found on the classpath");
            props.load(in);
        }
        return props;
    }
}
