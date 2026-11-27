package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys the 0.18.0 entries filter and editor summary added are in all eight bundles (English until translated). */
class EditorLayoutMessagesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    private static final List<String> KEYS =
            List.of("weblogEdit.summary.add", "weblogEdit.summary.tip", "weblogEntryQuery.moreFilters");

    private static final List<String> BUNDLES = List.of("", "_de", "_es", "_fr", "_ja", "_ko", "_ru", "_zh_CN");

    @Test
    void newKeysAreInEveryBundle() throws IOException {
        for (String suffix : BUNDLES) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(RESOURCES.resolve("ApplicationResources" + suffix + ".properties"))) {
                props.load(in);
            }
            for (String key : KEYS) {
                assertTrue(props.containsKey(key), key + " missing from bundle '" + suffix + "'");
            }
        }
    }
}
