package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the packaging, dev-tooling and CI names of the Pressroom rebrand: the
 * WAR, the {@code ./pressroom} dev script, its git-ignored secret, the dev
 * compose project and volume, and the image names in the release workflow.
 */
class PackagingNamesTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();

    private static String read(String relative) throws IOException {
        return Files.readString(REPO.resolve(relative));
    }

    @Test
    void theWarIsNamedPressroom() throws IOException {
        assertTrue(read("app/pom.xml").contains("<finalName>pressroom</finalName>"));
        assertTrue(read("Dockerfile").contains("/app/pressroom.war"));
    }

    @Test
    void theDevScriptIsNamedPressroomAndKeepsItsSecretThere() throws IOException {
        Path script = REPO.resolve("pressroom");
        assertTrue(Files.isExecutable(script), "./pressroom must exist and be executable");
        assertFalse(Files.exists(REPO.resolve("roller")), "the old ./roller script must be gone"); // rebrand:keep
        assertTrue(read("pressroom").contains("/.pressroom-dev-secret"));
        assertTrue(read(".gitignore").contains(".pressroom-dev-secret"));
    }

    @Test
    void theDevComposeUsesPressroomVolumes() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(compose.contains("pressroom-pgdata18:/var/lib/postgresql"));
        assertTrue(compose.contains("name: pressroom"), "the compose project name");
    }

    @Test
    void theReleaseWorkflowNamesTheImagesExplicitly() throws IOException {
        String release = read(".github/workflows/release.yml");
        assertTrue(release.contains("IMAGE_REPO=${{ github.repository_owner }}/pressroom"));
        assertTrue(release.contains("ghcr.io/${{ env.IMAGE_REPO }}-caddy:"));
        assertTrue(release.contains("/app/pressroom.war"));
        assertFalse(release.contains("GITHUB_REPOSITORY,,"), "the image name must not follow the repository name");
    }

    @Test
    void theItHarnessUsesPressroomNames() throws IOException {
        String pom = read("it-selenium/pom.xml");
        assertTrue(pom.contains("pressroom-it-postgres"));
        assertTrue(pom.contains("pressroom-it-keycloak"));
        assertTrue(pom.contains("pressroom-it-realm.json"));
        assertTrue(Files.exists(REPO.resolve("it-selenium/src/test/resources/pressroom-it.properties")));
        assertTrue(Files.exists(REPO.resolve("it-selenium/src/test/resources/keycloak/pressroom-it-realm.json")));
        assertTrue(read("it-selenium/src/test/script/start-app.sh").contains("pressroom.log"));
    }
}
