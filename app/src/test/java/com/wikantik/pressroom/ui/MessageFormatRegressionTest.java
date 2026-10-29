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
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for the doubled-apostrophe bug: {@code ApplicationResources
 * .properties} was written under the (Struts2-era) assumption that every
 * message is always run through {@code java.text.MessageFormat}, so
 * apostrophes were doubled ({@code ''}) throughout. Spring's {@code
 * ResourceBundleMessageSource} -- see {@code messageSource()} in {@code
 * boot/WebMvcConfig.java} -- defaults {@code alwaysUseMessageFormat} to
 * {@code false}, which only runs a lookup through {@code MessageFormat} when
 * the caller passes arguments. A no-argument lookup (the overwhelming
 * majority of this bundle's keys: {@code <spring:message code="foo"/>} with
 * no {@code arguments}, {@code addError(model, key, request)}, etc.) is
 * returned verbatim, so a doubled apostrophe there renders literally --
 * "you''ve" on the page instead of "you've".
 *
 * <p>This test exercises every key in the default bundle through the exact
 * same bean construction as production ({@link #messageSource()} mirrors
 * {@code WebMvcConfig#messageSource()} byte for byte) and fails if any
 * resolved message contains a literal {@code ''}, or if {@code
 * MessageFormat} throws (an unbalanced quote or a stray {@code {}/}}).
 * Keys that declare {@code {0}}, {@code {1}}, ... placeholders are called
 * WITH dummy arguments (so a legitimately-doubled apostrophe in a
 * parameterized message is exercised the way it is really used); every
 * other key is called with none, matching how the JSPs and controllers in
 * this codebase actually invoke {@code <spring:message>} /
 * {@code addError}/{@code addMessage} for that key.
 */
public class MessageFormatRegressionTest {

    private static final String DEFAULT_BUNDLE = "/ApplicationResources.properties";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)\\}");

    /**
     * Mirrors {@code com.wikantik.pressroom.boot.WebMvcConfig#messageSource()}.
     * If that bean's configuration ever changes (e.g. {@code
     * alwaysUseMessageFormat(true)}), this must change with it so the test
     * keeps exercising what production actually does.
     */
    private ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("ApplicationResources");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }

    @Test
    public void everyKeyResolvesCleanlyWithNoLiteralDoubledApostrophe() throws IOException {
        Properties bundle = loadDefaultBundle();
        assertFalse(bundle.isEmpty(), "Default bundle is empty");

        ResourceBundleMessageSource messageSource = messageSource();
        List<String> failures = new ArrayList<>();

        for (String key : bundle.stringPropertyNames()) {
            String raw = bundle.getProperty(key);
            Object[] args = dummyArgsFor(raw);

            String resolved;
            try {
                resolved = messageSource.getMessage(key, args, Locale.ENGLISH);
            } catch (IllegalArgumentException ex) {
                // MessageFormat itself throws IllegalArgumentException for a
                // malformed pattern (unbalanced '/{/}); NoSuchMessageException
                // would mean the key is missing entirely, which can't happen
                // since we just read it out of this same bundle.
                failures.add(key + " -- MessageFormat rejected the pattern: " + ex.getMessage());
                continue;
            } catch (NoSuchMessageException ex) {
                failures.add(key + " -- unexpectedly not found: " + ex.getMessage());
                continue;
            }

            if (resolved.contains("''")) {
                failures.add(key + " -- resolves with a literal doubled apostrophe: \"" + resolved + "\"");
            }
        }

        assertTrue(failures.isEmpty(),
                "The following keys in " + DEFAULT_BUNDLE + " do not round-trip cleanly through the "
                        + "real MessageSource bean (see messageSource() in boot/WebMvcConfig.java). A "
                        + "doubled apostrophe ('') only belongs on a key that is always invoked WITH "
                        + "arguments -- see the file's own header comment:\n  "
                        + String.join("\n  ", failures));
    }

    /**
     * Guards the guard: if the bundle stopped shipping any apostrophes at
     * all, the assertion above would pass vacuously.
     */
    @Test
    public void theBundleActuallyContainsApostrophes() throws IOException {
        Properties bundle = loadDefaultBundle();
        boolean anyApostrophe = bundle.stringPropertyNames().stream()
                .map(bundle::getProperty)
                .anyMatch(v -> v.indexOf('\'') >= 0);
        assertTrue(anyApostrophe,
                "Expected at least one bundle value to contain an apostrophe -- "
                        + "this test would otherwise pass without checking anything.");
    }

    /**
     * The base-bundle test above runs one bundle through the real bean. These
     * two run the <em>rule</em> over all eight, because a translator's
     * apostrophe is exactly as broken as an English one and nothing else looks
     * at the translations at all.
     *
     * <p>Direction one: a value that declares a placeholder WILL be run through
     * {@code MessageFormat}, and there a lone {@code '} opens a quoted section.
     * {@code Edition de l'utilisateur [{0}]} does not render the username -- it
     * renders {@code Edition de lutilisateur [{0}]}, apostrophe eaten and
     * placeholder preserved, which is the opposite of what was wanted on both
     * counts.
     */
    @Test
    public void noValueWithAPlaceholderContainsALoneApostrophe() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Path bundle : allBundles()) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (hasPlaceholder(value) && stripDoubledApostrophes(value).indexOf('\'') >= 0) {
                    offenders.add(offender(bundle, key));
                }
            }
        }
        assertEquals(EXPECTED_LONE_APOSTROPHES, offenders,
                "These values declare a {n} placeholder -- so MessageFormat runs -- and contain "
                        + "a lone apostrophe, which MessageFormat swallows along with whatever "
                        + "follows it. Double them ('') to render one:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * Direction two, and the reason this file exists: with {@code
     * alwaysUseMessageFormat} false a value with no placeholder is never run
     * through {@code MessageFormat} at all, so a doubled {@code ''} is not an
     * escape -- it is two apostrophes on the page. "Recréer l''index".
     */
    @Test
    public void noValueWithoutAPlaceholderContainsADoubledApostrophe() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (Path bundle : allBundles()) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (!hasPlaceholder(value) && value.contains("''")) {
                    offenders.add(offender(bundle, key));
                }
            }
        }
        assertEquals(EXPECTED_DOUBLED_APOSTROPHES, offenders,
                "These values carry no {n} placeholder, so they are returned verbatim and a "
                        + "doubled apostrophe renders literally. Use a single one:\n  "
                        + String.join("\n  ", offenders));
    }

    /**
     * Guards the two ratchets above: if the bundle scan stopped finding the
     * translations, both would pass while looking at one file.
     */
    @Test
    public void theScanCoversEveryTranslation() throws IOException {
        Set<String> names = new TreeSet<>();
        allBundles().forEach(p -> names.add(p.getFileName().toString()));
        assertEquals(Set.of(
                "ApplicationResources.properties",
                "ApplicationResources_de.properties",
                "ApplicationResources_es.properties",
                "ApplicationResources_fr.properties",
                "ApplicationResources_ja.properties",
                "ApplicationResources_ko.properties",
                "ApplicationResources_ru.properties",
                "ApplicationResources_zh_CN.properties"), names,
                "The set of shipped message bundles changed; update this list so the "
                        + "apostrophe ratchets keep covering all of them.");
    }

    /**
     * Empty, and it stays empty. Task 7 recorded six lone apostrophes in values
     * that declare a placeholder (all French); Task 8 doubled every one. A new
     * one fails the build rather than being absorbed.
     */
    private static final Set<String> EXPECTED_LONE_APOSTROPHES = Set.of();

    /**
     * Empty, and it stays empty. Task 7 recorded four doubled apostrophes in
     * values with no placeholder -- three French, one Simplified Chinese -- and
     * Task 8 singled every one.
     */
    private static final Set<String> EXPECTED_DOUBLED_APOSTROPHES = Set.of();

    private static final Path RESOURCE_ROOT = Paths.get("src/main/resources");

    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\{\\s*\\d+\\s*[,}]");

    private static boolean hasPlaceholder(String value) {
        return ANY_PLACEHOLDER.matcher(value).find();
    }

    private static String stripDoubledApostrophes(String value) {
        return value.replace("''", "");
    }

    private static String offender(Path bundle, String key) {
        return bundle.getFileName() + "#" + key;
    }

    private static List<Path> allBundles() throws IOException {
        try (Stream<Path> paths = Files.list(RESOURCE_ROOT)) {
            return paths.filter(p -> p.getFileName().toString().startsWith("ApplicationResources")
                            && p.getFileName().toString().endsWith(".properties"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Read as UTF-8, not with the {@code InputStream} overload: every one of
     * these files is UTF-8 or pure ASCII on disk, and {@code load(InputStream)}
     * would decode the French and German ones as ISO-8859-1.
     */
    private static Properties load(Path bundle) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    /**
     * A placeholder whose format type takes a number -- {@code {0,choice,...}}
     * (a plural: "1 comment", "3 comments") or {@code {0,number,...}}.
     * {@code MessageFormat} refuses a String there, so the dummy argument for
     * it is a number, as it is at the real call site.
     */
    private static final Pattern NUMERIC_PLACEHOLDER = Pattern.compile("\\{(\\d+)\\s*,\\s*(?:choice|number)\\b");

    /**
     * Dummy arguments matching the highest {@code {n}} placeholder referenced
     * in the raw (undecoded) message text, so a key declaring {@code {0}} and
     * {@code {1}} is called with two arguments, and a key with none is
     * called with none -- mirroring how each key is actually invoked
     * throughout the codebase (arguments present only when the message
     * needs them). An argument is a String, except a number where the
     * placeholder's format type needs one ({@link #NUMERIC_PLACEHOLDER}).
     */
    private static Object[] dummyArgsFor(String raw) {
        int highest = -1;
        Matcher matcher = PLACEHOLDER.matcher(raw);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        Set<Integer> numeric = new TreeSet<>();
        Matcher typed = NUMERIC_PLACEHOLDER.matcher(raw);
        while (typed.find()) {
            int index = Integer.parseInt(typed.group(1));
            numeric.add(index);
            highest = Math.max(highest, index);
        }
        if (highest < 0) {
            return new Object[0];
        }
        Object[] args = new Object[highest + 1];
        for (int i = 0; i <= highest; i++) {
            args[i] = numeric.contains(i) ? Integer.valueOf(2) : "X" + i;
        }
        return args;
    }

    private Properties loadDefaultBundle() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream(DEFAULT_BUNDLE)) {
            if (in == null) {
                fail(DEFAULT_BUNDLE + " not found on the classpath");
            }
            props.load(in);
        }
        return props;
    }
}
