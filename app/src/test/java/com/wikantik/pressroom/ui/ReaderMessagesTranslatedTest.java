package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.1 criterion 21: what a reader sees around a post (the byline and
 * "Updated" line, the share row, Nearby, Keep reading) is translated in each
 * of the seven locale files, so a German blog does not end in English.
 * Admin-only keys may stay English and are not held here.
 */
class ReaderMessagesTranslatedTest {

    private static final List<String> LOCALES = List.of("de", "es", "fr", "ja", "ko", "ru", "zh_CN");
    private static final List<String> READER_PREFIXES = List.of("afterEntry.", "entryMeta.");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*(\\d+)\\s*[,}]");

    private static Properties load(String suffix) throws IOException {
        Properties props = new Properties();
        try (InputStream in = ReaderMessagesTranslatedTest.class
                .getResourceAsStream("/ApplicationResources" + suffix + ".properties")) {
            assertTrue(in != null, "bundle not found: " + suffix);
            // As ResourceBundle reads them: UTF-8 (a \\uXXXX escape reads the same either way).
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        return props;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    void everyReaderKeyAroundAPostIsTranslatedInEveryLocale() throws IOException {
        Properties english = load("");
        Set<String> keys = new TreeSet<>();
        for (String key : english.stringPropertyNames()) {
            if (READER_PREFIXES.stream().anyMatch(key::startsWith)) {
                keys.add(key);
            }
        }
        assertTrue(keys.size() >= 13, "the reader keys were not found: " + keys);

        Set<String> problems = new TreeSet<>();
        for (String locale : LOCALES) {
            Properties translated = load("_" + locale);
            for (String key : keys) {
                String value = translated.getProperty(key);
                String source = english.getProperty(key);
                if (value == null || value.isBlank()) {
                    problems.add(locale + ": " + key + " is missing");
                } else if (value.equals(source)) {
                    problems.add(locale + ": " + key + " is still the English text");
                } else if (!placeholders(value).equals(placeholders(source))) {
                    problems.add(locale + ": " + key + " changes the placeholders");
                } else if (value.indexOf('�') >= 0 || value.indexOf('Ã') >= 0) {
                    problems.add(locale + ": " + key + " is mis-encoded: " + value);
                }
            }
        }
        assertEquals(Set.of(), problems);
    }
}
