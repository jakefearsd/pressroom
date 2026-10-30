package com.wikantik.pressroom.ui.restapi;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The docs page is the API's front door. This pins the two things a reader
 * cannot recover for themselves: that /api/v1 is unstable while Pressroom is
 * 0.x, and that a token is minted by the CLI rather than in the UI.
 */
class OpenApiDocumentTest {

    private static String docs() throws Exception {
        Path path = Path.of("..", "docs", "api", "README.md");
        if (!Files.exists(path)) {
            path = Path.of("docs", "api", "README.md");
        }
        assertTrue(Files.exists(path), "docs/api/README.md must exist");
        return Files.readString(path);
    }

    @Test
    void theInstabilityOfV1IsStated() throws Exception {
        String text = docs().toLowerCase();
        assertTrue(text.contains("unstable"),
                "a client author must be told v1 can change while Pressroom is 0.x");
    }

    @Test
    void theBootstrapPathIsDocumented() throws Exception {
        assertTrue(docs().contains("pressroom-api auth login"),
                "there is no UI for minting a token -- the CLI is the only route");
    }

    /**
     * The local path matters as much as the general one: there is no UI for
     * minting a token, and the dev credential is generated rather than typed,
     * so a reader cannot guess either from the OpenAPI document.
     */
    @Test
    void theLocalBootstrapPathIsDocumented() throws Exception {
        String text = docs();
        assertTrue(text.contains("./pressroom token"),
                "the README must record how to get a token locally");
        assertTrue(text.contains(".pressroom-dev-secret"),
                "the README must say where the dev credential lives");
    }
}
