/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.ui.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pins that Roller cannot be configured to store a plaintext password.
 *
 * <p>Four paths could once do it: {@code passwds.encryption.enabled=false}
 * (which flipped the encoding id to {@code noop}), the unconditional
 * {@code noop} encoder registration (so a {@code {noop}} value authenticated
 * however the flag was set), {@code lazyUpgradeFrom=plaintext} (a no-op encoder
 * on the NULL prefix, so an unprefixed string authenticated), and the config
 * lines that turned the first one on in dev and in the unit-test JVM.
 *
 * <p>Deleting the capability does not prevent its return; this is the guard
 * that does. Same role as {@code ProductionComposeTest} and
 * {@code DesignTokenTest}.
 */
class PasswordEncodingTest {

    /** Tests run with the app module as the working directory. */
    private static Path repoRoot() {
        Path candidate = Paths.get("..");
        return Files.isDirectory(candidate.resolve("bin/db")) ? candidate : Paths.get(".");
    }

    @Test
    void theBuiltEncoderNeverReturnsItsInput() {
        PasswordEncoder encoder = PressroomContext.createPasswordEncoder();
        String raw = "a-plaintext-password";
        String encoded = encoder.encode(raw);
        assertFalse(encoded.contains(raw),
                "the encoder emitted its own input: " + encoded);
        assertTrue(encoder.matches(raw, encoded),
                "the encoder cannot verify what it just encoded");
    }

    /**
     * A {@code {noop}} row does not merely fail to match -- Spring refuses the
     * unknown id outright, which is the stronger outcome. Either result means
     * "does not authenticate"; this asserts the one that actually happens, so
     * the test fails loudly if the encoder is ever re-registered.
     */
    @Test
    void aNoopStoredValueDoesNotAuthenticate() {
        PasswordEncoder encoder = PressroomContext.createPasswordEncoder();
        assertThrows(IllegalArgumentException.class,
                () -> encoder.matches("secret", "{noop}secret"),
                "a {noop} stored value was accepted for matching -- the noop encoder is registered again");
    }

    @Test
    void anExplicitlySetEncryptionFlagIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> PressroomContext.rejectRemovedEncryptionFlag("false"),
                "an explicitly-set passwds.encryption.enabled must fail loudly");
        assertThrows(IllegalStateException.class,
                () -> PressroomContext.rejectRemovedEncryptionFlag("true"),
                "even =true must be rejected: the property no longer exists");
        PressroomContext.rejectRemovedEncryptionFlag(null); // absent: must not throw
    }

    @Test
    void noPropertiesFileMentionsTheRemovedFlagOrPlaintextUpgrade() throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot())) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".properties"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> {
                        String body = read(p);
                        return body.contains("passwds.encryption.enabled")
                                || body.contains("lazyUpgradeFrom=plaintext");
                    })
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "properties files still reference the removed plaintext path: " + offenders);
        }
    }

    /**
     * The dev seed carries a password, so it must never be a migration; and no
     * migration may carry one at all. Both halves matter: the first keeps the
     * dev credential out of production, the second keeps any credential out of
     * the chain that production actually applies.
     */
    @Test
    void noMigrationCarriesAPasswordAndNoDbSqlCarriesAPlaintextOne() throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot().resolve("bin/db"))) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".sql"))
                    .filter(p -> {
                        String body = read(p);
                        if (p.toString().contains("/migrations/")) {
                            // Both names: V041 renamed the table, and the
                            // migrations before it still spell the old one.
                            String lower = body.toLowerCase();
                            return body.contains("passphrase")
                                    && (lower.contains("insert into pressroom_user")
                                        || lower.contains("insert into roller_user")); // rebrand:keep
                        }
                        // Comments are stripped first: the dev seed legitimately
                        // *mentions* {noop} while explaining which rows it
                        // repairs. Only a {noop} in executable SQL is a defect.
                        String sql = body.lines()
                                .map(line -> {
                                    int c = line.indexOf("--");
                                    return c < 0 ? line : line.substring(0, c);
                                })
                                .reduce("", (x, y) -> x + "\n" + y);
                        return sql.contains("{noop}") || sql.contains("{plaintext}");
                    })
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "SQL under bin/db carries a password it should not: " + offenders);
        }
    }

    @Test
    void theDevSecretIsGitIgnoredAndNotTracked() throws IOException {
        Path root = repoRoot();
        assertTrue(read(root.resolve(".gitignore")).contains(".roller-dev-secret"),
                ".roller-dev-secret must be git-ignored before anything writes it");
        assertFalse(isTrackedByGit(root, ".roller-dev-secret"),
                ".roller-dev-secret is tracked by git -- it holds a credential");
    }

    private static boolean isTrackedByGit(Path root, String relative) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--error-unmatch", relative)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The dev credential must never become an argv entry: {@code ./roller} hands
     * it to psql as a variable and to pressroom-api on stdin.
     */
    @Test
    void theRollerScriptSeedsWithoutPuttingThePasswordInAnArgv() throws IOException {
        String body = read(repoRoot().resolve("roller"));
        assertTrue(body.contains("seed-dev-data.sql"), "./roller must apply the dev seed");
        assertTrue(body.contains("-v devpw="),
                "the seed must receive the password as a psql variable, not inlined SQL");
        assertTrue(body.contains("umask 077"),
                ".roller-dev-secret must be created with a restrictive umask");
        assertTrue(body.contains("--password-stdin"),
                "./roller token must pipe the password rather than pass it as an argument");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + p, e);
        }
    }

    /**
     * The two algorithm refusals. Both predate this change but became
     * unconditional when the enabled/noop branch around them was deleted, so
     * they are now the only thing standing between a typo and a boot with an
     * encoder nobody chose.
     */
    @Test
    void anOutdatedAlgorithmIsRefused() {
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getProperty("passwds.encryption.algorithm"))
                    .thenReturn("SHA");
            RuntimeException e = assertThrows(RuntimeException.class,
                    PressroomContext::createPasswordEncoder,
                    "SHA must be refused, not silently used");
            assertTrue(e.getMessage().contains("outdated"), e.getMessage());
        }
    }

    @Test
    void anUnsupportedAlgorithmIsRefused() {
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getProperty("passwds.encryption.algorithm"))
                    .thenReturn("rot13");
            RuntimeException e = assertThrows(RuntimeException.class,
                    PressroomContext::createPasswordEncoder,
                    "an unknown algorithm must be refused");
            assertTrue(e.getMessage().contains("not supported"), e.getMessage());
        }
    }
}
