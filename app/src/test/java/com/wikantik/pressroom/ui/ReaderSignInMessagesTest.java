package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader sign-in surfaces' words (0.18.0, Part D: the sign-in page's
 * {@code login.reader.*} and the blog buttons' {@code reader.signIn.*}) are in
 * all eight bundles, English until translated (docs/dev/admin-ui.md, "New
 * message keys go into all eight bundles"). Missing from a translation, a
 * German visitor got an English heading above a German "Benutzername".
 */
class ReaderSignInMessagesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static final List<String> PREFIXES = List.of("login.reader.", "reader.signIn.",
            // 0.18.0 Part E: Save, Follow, the rows and the "You" menu; the account page's reader lists
            "reader.state.", "reader.menu.", "account.saved.", "account.following.", "account.history.",
            "account.comments.answeredBy");

    private static final List<String> TRANSLATIONS = List.of("de", "es", "fr", "ja", "ko", "ru", "zh_CN");

    private static Properties load(String fileName) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(RESOURCES.resolve(fileName))) {
            props.load(in);
        }
        return props;
    }

    @Test
    void everyReaderSignInKeyIsInAllEightBundles() throws IOException {
        Properties base = load("ApplicationResources.properties");
        Set<String> keys = new TreeSet<>();
        for (String key : base.stringPropertyNames()) {
            if (PREFIXES.stream().anyMatch(key::startsWith)) {
                keys.add(key);
            }
        }
        assertTrue(keys.size() >= 50, "found only " + keys + " -- the scan is not looking where it thinks it is");

        List<String> missing = new ArrayList<>();
        for (String language : TRANSLATIONS) {
            Properties translation = load("ApplicationResources_" + language + ".properties");
            for (String key : keys) {
                if (!translation.containsKey(key)) {
                    missing.add(language + ": " + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "keys missing from a translation:\n  " + String.join("\n  ", missing));
    }
}
