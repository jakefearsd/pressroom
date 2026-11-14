package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins how {@code release.yml} uses the GitHub Actions layer cache, which no
 * local build exercises: the workflow runs only on a {@code v*.*.*} tag.
 *
 * <p>Both image builds once wrote the cache's default scope, so each export
 * replaced the other's. The release logs showed the app and caddy builds
 * importing the same cache manifest, and Caddy's xcaddy compile (~80 s) ran
 * from scratch on every release although nothing it reads had changed, while
 * two ~90 s exports bought nothing. Each Dockerfile now has a scope of its
 * own, filled by an {@code images} job that runs beside the unit suite, and
 * the publishing job reads it.
 *
 * <p>The speed-up must not open a path to publishing untested code, so this
 * also pins that the publishing job still waits for the unit suite and that
 * the job running beside it pushes nothing and holds no write grant.
 */
class ReleaseWorkflowCacheTest {

    private static final Path RELEASE =
            Path.of(System.getProperty("user.dir")).getParent().resolve(".github/workflows/release.yml");

    private static final Pattern SCOPE = Pattern.compile("(?:^|,)scope=([^,\\s]+)");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jobs() throws IOException {
        Map<String, Object> workflow = new Yaml().load(Files.readString(RELEASE));
        return (Map<String, Object>) workflow.get("jobs");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buildSteps(Map<String, Object> job) {
        List<Map<String, Object>> builds = new ArrayList<>();
        for (Map<String, Object> step : (List<Map<String, Object>>) job.get("steps")) {
            Object uses = step.get("uses");
            if (uses instanceof String u && u.startsWith("docker/build-push-action@")) {
                builds.add((Map<String, Object>) step.get("with"));
            }
        }
        return builds;
    }

    private static String dockerfile(Map<String, Object> with) {
        Object file = with.get("file");
        return file == null ? "Dockerfile" : file.toString();
    }

    private static String scope(String cacheSpec, String where) {
        assertTrue(cacheSpec.startsWith("type=gha"), where + ": expected a gha cache, got " + cacheSpec);
        Matcher m = SCOPE.matcher(cacheSpec);
        assertTrue(m.find(), where + ": a gha cache with no scope= shares the default scope with "
                + "every other build in the repository, and the builds overwrite each other: " + cacheSpec);
        return m.group(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachImageHasItsOwnCacheScopeAndEveryBuildUsesIt() throws IOException {
        Map<String, String> scopeByDockerfile = new HashMap<>();
        int builds = 0;
        for (Map.Entry<String, Object> job : jobs().entrySet()) {
            for (Map<String, Object> with : buildSteps((Map<String, Object>) job.getValue())) {
                builds++;
                String where = job.getKey() + "/" + dockerfile(with);
                Object from = with.get("cache-from");
                assertNotNull(from, where + " reads no cache");
                String scope = scope(from.toString(), where + " cache-from");
                Object to = with.get("cache-to");
                if (to != null) {
                    assertEquals(scope, scope(to.toString(), where + " cache-to"),
                            where + " must write the scope it reads");
                }
                String previous = scopeByDockerfile.putIfAbsent(dockerfile(with), scope);
                if (previous != null) {
                    assertEquals(previous, scope, where + " must use the same scope in every job");
                }
            }
        }
        assertEquals(4, builds, "two images, each built by the images job and by the release job");
        assertEquals(2, scopeByDockerfile.size());
        assertEquals(2, scopeByDockerfile.values().stream().distinct().count(),
                "the app and caddy images must not share a cache scope: " + scopeByDockerfile);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theImagesJobFillsTheCacheWithModeMaxAndPushesNothing() throws IOException {
        Map<String, Object> images = (Map<String, Object>) jobs().get("images");
        assertNotNull(images, "release.yml has no images job");
        assertEquals(Map.of("contents", "read"), images.get("permissions"),
                "the job that runs beside the unit suite holds no write grant");
        List<Map<String, Object>> builds = buildSteps(images);
        assertEquals(2, builds.size());
        for (Map<String, Object> with : builds) {
            assertEquals(Boolean.FALSE, with.get("push"), dockerfile(with) + " must not be pushed before the suite passes");
            assertFalse(with.containsKey("tags"), "an untested build gets no tags");
            assertTrue(String.valueOf(with.get("cache-to")).contains("mode=max"),
                    dockerfile(with) + ": mode=max keeps the builder stage (the Maven build, the "
                            + "xcaddy compile), which is what the release job reuses");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishingStillWaitsForTheUnitSuite() throws IOException {
        Map<String, Object> release = (Map<String, Object>) jobs().get("release");
        Object needs = release.get("needs");
        List<String> needed = needs instanceof List<?> l ? (List<String>) l : List.of(String.valueOf(needs));
        assertTrue(needed.contains("test"), "the publishing job must need the unit suite: " + needed);
        assertTrue(needed.contains("images"), "the publishing job must wait for the cache it reads: " + needed);
        for (Map<String, Object> with : buildSteps(release)) {
            assertEquals(Boolean.TRUE, with.get("push"), dockerfile(with));
        }
    }
}
