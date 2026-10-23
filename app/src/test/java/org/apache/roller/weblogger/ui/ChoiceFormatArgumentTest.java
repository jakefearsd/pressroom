/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message whose pattern formats a number ({@code {0,choice,1#1 entry|1<{0}
 * entries}}) is only ever given a number.
 *
 * <p>The plural forms in the bundle are choice formats, and
 * {@code MessageFormat} cannot format a {@code String} as a choice: it throws
 * {@code IllegalArgumentException} while the page or the flash message is
 * being built. {@code BaseController}'s four-argument {@code addMessage} /
 * {@code addError} / {@code addFlashMessage} overloads take a {@code String}
 * parameter, so every controller that calls them with
 * {@code String.valueOf(count)} -- which is most of them -- must not be handed
 * a choice key. Those messages say their count without a plural ("Entries
 * published: 3."). A choice key is reached from Java only through the
 * {@code Object[]} overload, and from a JSP only through a {@code arguments}
 * attribute that is a single EL expression: a value with literal text or a
 * comma is split into {@code String}s by {@code <spring:message>}.
 *
 * <p>Call sites are {@link MessageUsageScanner}'s, so a key it cannot see --
 * one chosen by a ternary, as {@code EntriesController}'s bulk messages are --
 * is not checked here: that is one more reason those stay plural-free.
 */
class ChoiceFormatArgumentTest {

    private static final Pattern NUMERIC_PLACEHOLDER =
            Pattern.compile("\\{\\d+\\s*,\\s*(?:choice|number)\\b");

    private static final Pattern MESSAGE_TAG_AT =
            Pattern.compile("<spring:message\\b[^>]*?\\barguments\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);

    private static final Pattern SINGLE_EL = Pattern.compile("\\s*\\$\\{[^}]*}\\s*");

    @Test
    void everyChoiceKeyIsGivenANumber() throws IOException {
        Set<String> choiceKeys = choiceKeys();
        assertTrue(choiceKeys.size() >= 4,
                "expected the plural keys (comments.heading, trash.emptyConfirm, ...), found " + choiceKeys);

        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (MessageUsageScanner.Usage usage : MessageUsageScanner.scanAll()) {
            if (!choiceKeys.contains(usage.key()) || usage.argCount() == 0) {
                continue;
            }
            checked++;
            String file = usage.where().substring(0, usage.where().lastIndexOf(':'));
            int line = Integer.parseInt(usage.where().substring(usage.where().lastIndexOf(':') + 1));
            boolean jsp = file.endsWith(".jsp") || file.endsWith(".tag");
            Path root = jsp ? MessageUsageScanner.WEBAPP_ROOT : MessageUsageScanner.JAVA_ROOT;
            String source = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
            if (passesAString(source, line, usage.key(), jsp)) {
                violations.add(usage.where() + "  " + usage.key());
            }
        }

        assertTrue(checked >= 3, "only " + checked + " call sites of a choice key were found");
        assertEquals(List.of(), violations,
                "a choice-format message is given a String, which MessageFormat cannot format as a "
                        + "choice -- pass a number (new Object[] {...} in Java, a single ${...} in a JSP), "
                        + "or word the message without a plural");
    }

    /** The rule itself, on planted call sites. */
    @Test
    void theRuleTellsANumberFromAString() {
        String java = """
                addFlashMessage(redirectAttributes, "k", String.valueOf(n), request);
                addError(model, "k",
                        new Object[] {name, uses}, request);
                """;
        assertTrue(passesAString(java, 1, "k", false));
        assertFalse(passesAString(java, 2, "k", false));

        String jsp = """
                <spring:message code="k" arguments="${fn:length(rows)}" var="x"/>
                <spring:message code="k" arguments="${a},${b}"/>
                <spring:message code="k" arguments="3 rows"/>
                """;
        assertFalse(passesAString(jsp, 1, "k", true));
        assertTrue(passesAString(jsp, 2, "k", true));
        assertTrue(passesAString(jsp, 3, "k", true));
    }

    private static boolean passesAString(String source, int line, String key, boolean jsp) {
        int start = startOfLine(source, line);
        if (jsp) {
            Matcher tag = MESSAGE_TAG_AT.matcher(source);
            return tag.find(start) && !SINGLE_EL.matcher(tag.group(1)).matches();
        }
        int keyAt = source.indexOf("\"" + key + "\"", start);
        int end = source.indexOf(';', keyAt);
        return !source.substring(keyAt, end).contains("new Object[]");
    }

    private static int startOfLine(String source, int line) {
        int index = 0;
        for (int i = 1; i < line; i++) {
            index = source.indexOf('\n', index) + 1;
        }
        return index;
    }

    private static Set<String> choiceKeys() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = Files.newInputStream(
                Path.of("src/main/resources/ApplicationResources.properties"))) {
            bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> keys = new TreeSet<>();
        for (String key : bundle.stringPropertyNames()) {
            if (NUMERIC_PLACEHOLDER.matcher(bundle.getProperty(key)).find()) {
                keys.add(key);
            }
        }
        return keys;
    }
}
