package com.wikantik.pressroom.ui.restapi.v1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The React admin's typed client is generated from a committed copy of
 * {@code /api/v1/openapi.json} ({@code frontend/src/admin/api/openapi.json}),
 * so API drift breaks the build here rather than in a browser. The live
 * document is the one {@link OpenApiContractTest} generates (springdoc over
 * every API controller, the application's own {@code springdoc.*} properties).
 *
 * <p>After an intended API change, rerun with
 * {@code -Dopenapi.snapshot.update=true} and commit the rewritten file.
 */
class OpenApiSnapshotTest {

    private static final Path SNAPSHOT = Path.of("frontend", "src", "admin", "api", "openapi.json");
    private static final String UPDATE_PROPERTY = "openapi.snapshot.update";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theCommittedSnapshotIsTheLiveDocument() throws Exception {
        JsonNode live;
        try (AnnotationConfigWebApplicationContext context = OpenApiContractTest.newContext()) {
            live = MAPPER.readTree(OpenApiContractTest.documentJson(context));
        }
        check(live, SNAPSHOT, Boolean.getBoolean(UPDATE_PROPERTY));
    }

    /** Compares as JSON (key order and whitespace ignored); {@code update} rewrites the file instead. */
    static void check(JsonNode live, Path file, boolean update) throws IOException {
        if (update) {
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(live) + "\n",
                    StandardCharsets.UTF_8);
            return;
        }
        JsonNode committed = Files.exists(file) ? MAPPER.readTree(Files.readString(file)) : null;
        assertEquals(committed, live, "/api/v1/openapi.json differs from " + file + ". If the API change is"
                + " intended, rerun with -D" + UPDATE_PROPERTY + "=true to rewrite the snapshot, then commit it"
                + " (the React admin's types are generated from it).");
    }

    @Test
    void aChangedApiFailsWithTheRefreshInstructionsAndUpdateRewritesTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("openapi.json");
        Files.writeString(file, "{\"openapi\":\"3.1.0\",\"paths\":{}}");
        JsonNode changed = MAPPER.readTree("{\"openapi\":\"3.1.0\",\"paths\":{\"/v1/x\":{}}}");

        AssertionError failure = assertThrows(AssertionError.class, () -> check(changed, file, false));
        assertTrue(failure.getMessage().contains("-Dopenapi.snapshot.update=true"), failure.getMessage());

        check(changed, file, true);
        check(changed, file, false);
        assertEquals(changed, MAPPER.readTree(Files.readString(file)));
    }

    @Test
    void keyOrderAndWhitespaceDoNotCount(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("openapi.json");
        Files.writeString(file, "{ \"b\": 1,\n \"a\": {\"y\": 2, \"x\": 3} }");

        check(MAPPER.readTree("{\"a\":{\"x\":3,\"y\":2},\"b\":1}"), file, false);
    }

    @Test
    void aMissingSnapshotFailsRatherThanPassing(@TempDir Path dir) {
        Path file = dir.resolve("absent.json");

        assertThrows(AssertionError.class, () -> check(MAPPER.readTree("{}"), file, false));
    }
}
