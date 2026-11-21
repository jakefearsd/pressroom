package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After spec 2026-11-20-api-contract-design, Task 3 (criterion 1), no API
 * code raises a problem or a field error from a literal or concatenated
 * detail: Task 2's scan ({@link ContentProblemSourceScanTest}) widened to
 * every source file under {@code ui.restapi} and to
 * {@code PressroomHandlerInterceptor}, whose API refusals are thrown as
 * problems. ApiException's literal factories are package-private now, kept
 * only for the unit tests of the mechanism itself; this scan is what keeps
 * the package's own main code off them. A guard asserts the walk found the
 * files the task names.
 */
class ApiProblemSourceScanTest {

    private static final String RESTAPI = "src/main/java/com/wikantik/pressroom/ui/restapi";
    private static final String INTERCEPTOR =
            "src/main/java/com/wikantik/pressroom/ui/controllers/PressroomHandlerInterceptor.java";

    /**
     * The mechanism itself: the literal factories, the wire field error and
     * the literal {@code add} are declared here, so they name what the scan
     * looks for. Every other file is scanned.
     */
    private static final Set<String> MECHANISM = Set.of("ApiException.java", "ApiProblem.java", "FieldErrors.java");

    private static Path path(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : Path.of("app", relative);
    }

    private static List<Path> scanned() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(path(RESTAPI))) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !MECHANISM.contains(p.getFileName().toString()))
                    .sorted()
                    .forEach(files::add);
        }
        files.add(path(INTERCEPTOR));
        return files;
    }

    /** The source without its comments, which may name a factory in prose. */
    private static String code(Path file) throws IOException {
        return Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    void theScanFindsEveryFileTheTaskNames() throws IOException {
        List<String> names = scanned().stream().map(p -> p.getFileName().toString()).toList();
        for (String named : List.of("MediaApi.java", "MediaImports.java", "MediaDtos.java", "CategoriesApi.java",
                "CommentsApi.java", "RedirectsApi.java", "TokensApi.java", "AdminApi.java", "AdminDtos.java",
                "AdminAccountGuard.java", "WeblogsApi.java", "WeblogSettingsApi.java", "WeblogCreateApi.java",
                "AuditApi.java", "TrafficApi.java", "MetaApi.java", "McpToolExecutor.java", "McpEndpointServlet.java",
                "ApiAuthenticationEntryPoint.java", "ApiAccessDeniedHandler.java", "ApiTokenAuthFilter.java",
                "ApiScopeInterceptor.java", "ApiBodyLimitFilter.java", "ApiExceptionHandler.java",
                "PressroomHandlerInterceptor.java", "EntriesApi.java", "ColumnLimits.java")) {
            assertTrue(names.contains(named), named + " was not scanned");
        }
        assertTrue(names.size() > 70, "the walk found only " + names.size() + " files");
    }

    @Test
    void noApiFileRaisesAProblemFromALiteralDetail() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : scanned()) {
            for (String violation : ContentProblemSourceScanTest.violations(code(file))) {
                found.add(file.getFileName() + ": " + violation);
            }
        }
        assertEquals(List.of(), found);
    }
}
