package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Security floors for third-party libraries carrying published advisories.
 *
 * <p>This test asserts the version that actually <em>resolves onto the
 * classpath</em>, not the text of a {@code pom.xml}, and that distinction is
 * the entire reason it exists. {@code app/pom.xml} <em>imports</em>
 * {@code spring-boot-dependencies} as a BOM ({@code <scope>import</scope>})
 * rather than inheriting from {@code spring-boot-starter-parent}. Under an
 * import, a {@code <properties>} entry in the importing POM does <strong>not
 * </strong> override the BOM's managed version -- the BOM's properties are
 * resolved in the BOM's own context. So the obvious way to raise a
 * Boot-managed version here is silently a no-op: the property sits in the POM
 * looking authoritative while the vulnerable jar keeps resolving. Overriding
 * therefore has to be done with explicit {@code <dependencyManagement>}
 * entries declared <em>before</em> the BOM import (nearest/first declaration
 * wins), and only a resolved-classpath assertion can tell the two apart.
 *
 * <p>The second failure mode this guards is a future Spring Boot upgrade.
 * These overrides exist only because Boot 4.1.0's BOM pins versions that are
 * now behind their advisories; when a later Boot catches up, the natural
 * cleanup is to delete the override and let the BOM manage it again. That is
 * correct exactly when the BOM's version is at or above the floor, and a
 * silent reintroduction of the CVE when it is not. Deleting an override is
 * safe here precisely because this test will fail if it was premature.
 *
 * <p>Floors only ever move up, like the JaCoCo ratchet in the parent POM.
 * Sibling of QualityGatePomTest, ItHarnessPomTest and ProductionComposeTest.
 */
class DependencySecurityFloorTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();

    /**
     * A library whose resolved version must be at or above {@code minimum}.
     *
     * @param marker   a class that only exists in the artifact, used to find its jar
     * @param artifact the Maven artifactId, i.e. the jar filename stem
     * @param minimum  the lowest version clear of every advisory listed in {@code reason}
     * @param reason   the advisories the floor is holding, for whoever reads a failure
     */
    private record Floor(Class<?> marker, String artifact, String minimum, String reason) { }

    private static final List<Floor> FLOORS = List.of(
            new Floor(org.apache.catalina.util.ServerInfo.class, "tomcat-embed-core", "11.0.25",
                    "CVE-2026-55956 (default-servlet security constraints ignored the configured "
                    + "HTTP method / method omission), CVE-2026-59083 (RewriteValve URL decoding "
                    + "may allow a security-control bypass), plus six lower-severity issues fixed "
                    + "across 11.0.23-11.0.25. Boot 4.1.0's BOM pins 11.0.22."),
            new Floor(org.postgresql.Driver.class, "postgresql", "42.7.13",
                    "CVE-2026-54291 (HIGH): channelBinding=require silently downgrades from "
                    + "SCRAM-SHA-256-PLUS to SCRAM-SHA-256, losing MitM protection, when the "
                    + "server certificate's signature algorithm has no tls-server-end-point hash. "
                    + "Affects 42.7.4-42.7.11. Boot 4.1.0's BOM pins 42.7.11."),
            new Floor(com.fasterxml.jackson.databind.ObjectMapper.class, "jackson-databind", "2.21.6",
                    "CVE-2026-59889, CVE-2026-54515 and GHSA-mhm7-754m-9p8w: @JsonView and "
                    + "per-property @JsonIgnoreProperties are bypassed on deserialization via "
                    + "@JsonUnwrapped, case-insensitive matching, and EXTERNAL_PROPERTY creators. "
                    + "Boot 4.1.0's BOM pins 2.21.4."),
            new Floor(tools.jackson.databind.ObjectMapper.class, "jackson-databind", "3.1.6",
                    "CVE-2026-59889 on the Jackson 3 line (tools.jackson), same @JsonUnwrapped "
                    + "@JsonView bypass. Boot 4.1.0's BOM pins 3.1.4."),
            new Floor(org.apache.logging.log4j.core.LoggerContext.class, "log4j-core", "2.25.5",
                    "CVE-2026-49844: MapMessage.asJson() emits bare NaN/Infinity tokens, which "
                    + "RFC 8259 forbids, so a conformant parser rejects the log document. "
                    + "Boot 4.1.0's BOM pins 2.25.4."),
            new Floor(org.apache.logging.log4j.LogManager.class, "log4j-api", "2.25.5",
                    "CVE-2026-49844, api half of the same fix. Boot 4.1.0's BOM pins 2.25.4."));

    @Test
    @DisplayName("every library with a published advisory resolves at or above its fixed version")
    void everyVulnerableLibraryResolvesAtOrAboveItsFloor() {
        for (Floor floor : FLOORS) {
            String resolved = resolvedVersionOf(floor);
            assertTrue(compare(resolved, floor.minimum()) >= 0,
                    () -> floor.artifact() + " resolves " + resolved + ", below the security floor "
                    + floor.minimum() + ".\n  " + floor.reason()
                    + "\n  NOTE: app/pom.xml imports the Spring Boot BOM, so a <properties> entry "
                    + "does NOT raise a BOM-managed version. Use an explicit <dependencyManagement> "
                    + "entry declared before the BOM import.");
        }
    }

    /**
     * The httpclient5 floor is asserted as POM text rather than from the
     * classpath because it is a test-scoped dependency of {@code it-selenium}
     * (pulled in by selenide-core), a module that does not build in the fast
     * suite this test runs in -- so its jar is not on this classpath to
     * inspect. Selenide resolves 5.6.2, which leaks a pooled connection on an
     * unsupported Content-Encoding response header (CVE-2026-64607, pool
     * exhaustion DoS); it only ever runs against our own test server, so this
     * is hygiene rather than exposure.
     */
    @Test
    @DisplayName("it-selenium pins httpclient5 above the connection-leak advisory")
    void itSeleniumPinsHttpClientAboveTheLeakAdvisory() throws IOException {
        String pom = Files.readString(REPO.resolve("it-selenium/pom.xml"));
        Matcher m = Pattern.compile("<httpclient5\\.version>([^<]+)</httpclient5\\.version>").matcher(pom);
        assertTrue(m.find(), "it-selenium/pom.xml must pin httpclient5.version "
                + "(CVE-2026-64607: connection leak on a bad Content-Encoding header, fixed in 5.6.3)");
        String pinned = m.group(1);
        assertTrue(compare(pinned, "5.6.3") >= 0,
                "it-selenium pins httpclient5 " + pinned + ", below the fixed version 5.6.3");
        assertTrue(pom.contains("<artifactId>httpclient5</artifactId>"),
                "the httpclient5.version property is only effective if a dependencyManagement "
                + "entry actually uses it -- selenide-core's own transitive version wins otherwise");
    }

    /** Locates the jar backing {@code marker} and reads the version out of its filename. */
    private static String resolvedVersionOf(Floor floor) {
        var source = floor.marker().getProtectionDomain().getCodeSource();
        assertNotNull(source, "no code source for " + floor.marker() + "; cannot determine its version");
        String jar = Path.of(source.getLocation().getPath()).getFileName().toString();
        Matcher m = Pattern.compile(Pattern.quote(floor.artifact()) + "-([0-9][^/]*)\\.jar$").matcher(jar);
        assertTrue(m.find(), "could not read a version out of jar name '" + jar + "' for "
                + floor.artifact() + " (expected <artifact>-<version>.jar)");
        return m.group(1);
    }

    /** Numeric dotted-segment comparison; trailing non-numeric qualifiers are ignored. */
    private static int compare(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = segment(a, i);
            int y = segment(b, i);
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int segment(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
