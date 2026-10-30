package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The product is called Pressroom wherever a person reads it. Every locale's
 * message file is checked for the old name in any value, and the default
 * locale's login title, installer banner, footer version and pending-review
 * email subject must name Pressroom.
 */
class ProductNameVisibleTest {

    private static final String OLD_NAME = "Rol" + "ler";

    private static Path resourcesDir() {
        return Path.of(System.getProperty("user.dir"), "src", "main", "resources");
    }

    private static Properties load(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        return props;
    }

    @Test
    void noMessageValueInAnyLocaleNamesTheOldProduct() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(resourcesDir(), "ApplicationResources*.properties")) {
            for (Path file : files) {
                Properties props = load(file);
                for (String key : props.stringPropertyNames()) {
                    if (props.getProperty(key).contains(OLD_NAME)) {
                        offenders.add(file.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "message values still name the old product: " + offenders);
    }

    @Test
    void defaultLocaleNamesPressroomWhereThePageShowsTheProduct() throws IOException {
        Properties props = load(resourcesDir().resolve("ApplicationResources.properties"));
        for (String key : List.of("loginPage.title", "installer.bannerTitleLeft", "footer.version",
                "footer.version.revision", "index.heading", "weblogEntry.pendingEntrySubject")) {
            String value = props.getProperty(key);
            assertTrue(value != null && value.contains("Pressroom"), key + " must say Pressroom, was: " + value);
        }
    }
}
