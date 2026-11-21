package com.wikantik.pressroom.ui.controllers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen independence for the classes Task 1 of spec
 * 2026-11-20-jsp-rules-and-view-models-design added ("Global constraints"):
 * no servlet, Spring MVC or JSP form-bean type, and from {@code ui.restapi}
 * only {@code ProblemCode} (the 2026-11-21 amendment: a violation carries its
 * problem code). Task 7's scan of every rule class and view model takes these
 * over.
 */
class SiteSettingsScreenIndependenceTest {

    private static final String ROOT = "src/main/java/com/wikantik/pressroom/ui/controllers/";

    private static final List<String> FILES = List.of("RuleViolation.java", "SiteSettingsRules.java",
            "SiteSettingsEffects.java", "AccountRules.java", "Memberships.java",
            "CategoryRules.java", "MediaRules.java", "ColumnWidths.java");

    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)");

    private static boolean forbidden(String type) {
        return type.startsWith("jakarta.servlet.") || type.startsWith("org.springframework.ui.")
                || type.startsWith("org.springframework.web.") || type.endsWith("Bean")
                || type.startsWith("com.wikantik.pressroom.ui.restapi.")
                        && !"com.wikantik.pressroom.ui.restapi.ProblemCode".equals(type);
    }

    @Test
    void theSiteSettingsRulesImportNoScreenType() throws IOException {
        int imports = 0;
        for (String file : FILES) {
            Path path = Path.of(ROOT + file);
            Matcher matcher = IMPORT.matcher(Files.readString(Files.exists(path) ? path : Path.of("app", ROOT + file)));
            while (matcher.find()) {
                imports++;
                assertFalse(forbidden(matcher.group(1)), file + " imports " + matcher.group(1));
            }
        }
        assertTrue(imports > 10, "the scan read the files: " + imports + " imports");
    }

    @Test
    void theCheckRecognisesWhatItBars() {
        assertEquals(List.of(true, true, true, true, true, false, false),
                List.of("jakarta.servlet.http.HttpServletRequest", "org.springframework.ui.Model",
                        "org.springframework.web.bind.annotation.GetMapping",
                        "com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean",
                        "com.wikantik.pressroom.ui.restapi.ApiException",
                        "com.wikantik.pressroom.ui.restapi.ProblemCode", "java.util.List")
                        .stream().map(SiteSettingsScreenIndependenceTest::forbidden).toList());
    }
}
