package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen independence (spec 2026-11-20 jsp-rules-and-view-models, "Global
 * constraints"; replaces the per-task scan the first tasks kept). A rule class
 * or view model must serve the JSP controller, the REST API and a future React
 * screen alike, so its source imports no {@code jakarta.servlet.*}, no
 * {@code org.springframework.ui.*} or {@code org.springframework.web.*}, no
 * {@code ui.restapi} type and no JSP form bean ({@code *Bean} under
 * {@code ui.controllers}).
 *
 * <p>The one exemption is {@code ui.restapi.ProblemCode}, the code a rule
 * violation carries (the 2026-11-21 amendment); it is an enum with no web type.
 * Importing {@code ui.view} or other {@code ui.controllers} rule classes is
 * allowed: the rule is about web types and screens, not layering inside the UI
 * tier. The scanned set is every class in {@code ui.view} plus an explicit
 * list; a listed class that no longer exists fails, so a rename cannot
 * silently leave the scan.
 */
class ScreenIndependenceTest {

    private static final String MAIN = "src/main/java/com/wikantik/pressroom/ui/";

    private static final List<String> NAMED = List.of(
            "controllers/RuleViolation.java", "controllers/SiteSettingsRules.java",
            "controllers/SiteSettingsEffects.java", "controllers/AccountRules.java",
            "controllers/Memberships.java", "controllers/CategoryRules.java", "controllers/MediaRules.java",
            "controllers/ColumnWidths.java", "controllers/PageRules.java", "controllers/StrandChoice.java",
            "controllers/EntryPinRules.java", "controllers/EntryListQuery.java",
            "controllers/WeblogPermissionView.java", "controllers/editor/TrafficView.java",
            "controllers/admin/SiteTrafficView.java");

    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)");

    static boolean forbidden(String type) {
        return type.startsWith("jakarta.servlet.") || type.startsWith("org.springframework.ui.")
                || type.startsWith("org.springframework.web.")
                || type.startsWith("com.wikantik.pressroom.ui.controllers.") && type.endsWith("Bean")
                || type.startsWith("com.wikantik.pressroom.ui.restapi.")
                        && !"com.wikantik.pressroom.ui.restapi.ProblemCode".equals(type);
    }

    static List<String> violations(String name, String source) {
        List<String> found = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(source);
        while (matcher.find()) {
            if (forbidden(matcher.group(1))) {
                found.add(name + " imports " + matcher.group(1));
            }
        }
        return found;
    }

    private static Path resolve(String relative) {
        Path path = Path.of(MAIN + relative);
        return Files.exists(path) ? path : Path.of("app", MAIN + relative);
    }

    @Test
    void everyViewModelAndNamedRuleImportsNoScreenType() throws IOException {
        List<String> files = new ArrayList<>(NAMED);
        try (Stream<Path> view = Files.list(resolve("view"))) {
            view.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add("view/" + p.getFileName()));
        }
        assertTrue(files.size() > NAMED.size(), "ui.view has classes to scan");
        List<String> found = new ArrayList<>();
        long imports = 0;
        for (String file : files) {
            Path path = resolve(file);
            assertTrue(Files.exists(path), file + " is named in the scan but no longer exists");
            String source = Files.readString(path);
            imports += IMPORT.matcher(source).results().count();
            found.addAll(violations(file, source));
        }
        assertEquals(List.of(), found);
        assertTrue(imports > 30, "the scan read the files: " + imports + " imports");
    }

    @Test
    void theCheckRecognisesWhatItBars() {
        assertEquals(List.of(true, true, true, true, true, false, false, false, false),
                List.of("jakarta.servlet.http.HttpServletRequest", "org.springframework.ui.Model",
                        "org.springframework.web.bind.annotation.GetMapping",
                        "com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean",
                        "com.wikantik.pressroom.ui.restapi.ApiException",
                        "com.wikantik.pressroom.ui.restapi.ProblemCode", "java.util.List",
                        "com.wikantik.pressroom.ui.view.MainMenuView",
                        "com.wikantik.pressroom.pojos.Weblog")
                        .stream().map(ScreenIndependenceTest::forbidden).toList());
    }

    @Test
    void aPlantedServletImportFails() {
        String planted = "package x;\nimport java.util.List;\n"
                + "import jakarta.servlet.http.HttpServletRequest;\nclass Planted { }\n";
        assertEquals(List.of("Planted.java imports jakarta.servlet.http.HttpServletRequest"),
                violations("Planted.java", planted));
        assertFalse(violations("Ok.java", "import java.util.List;").iterator().hasNext());
    }

    @Test
    void aNamedClassThatNoLongerExistsFailsTheScan() {
        assertFalse(Files.exists(resolve("controllers/NoSuchRules.java")));
    }
}
