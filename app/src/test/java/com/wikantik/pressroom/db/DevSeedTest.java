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
package com.wikantik.pressroom.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Runs the real {@code bin/db/seed-dev-data.sql} against a scratch database.
 *
 * <p>The seed's guard is the risky part: {@code crypt()} raises "invalid salt"
 * on a non-salt second argument, so a naive OR chain would abort on exactly the
 * {@code {noop}} row the seed exists to repair.
 *
 * <p>Uses its own database rather than the shared one, following
 * {@code AnalyticsContractTest}: the seed inserts a real {@code admin} row and
 * nothing in this suite truncates tables between tests.
 */
class DevSeedTest {

    private static final String PW = "dev-seed-test-password";
    private static final Path SEED = Paths.get("../bin/db/seed-dev-data.sql");
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    /** Substitutes psql's client-side :\'devpw\' with a literal this test controls. */
    private static String seedSqlWith(String password) throws Exception {
        return Files.readString(SEED, StandardCharsets.UTF_8)
                .replace(":'devpw'", "'" + password.replace("'", "''") + "'");
    }

    @Test
    void theSeedExistsAndIsNotAMigration() {
        assertTrue(Files.exists(SEED), "bin/db/seed-dev-data.sql is missing");
        assertFalse(SEED.toString().contains("migrations"),
                "the dev seed must never sit under bin/db/migrations/");
    }

    /**
     * Applies the REAL seed file over each row shape a dev database can hold.
     *
     * <p>Deliberately runs the shipped SQL rather than a copy of its guard: an
     * earlier version of this test reimplemented the CASE expression inline and
     * therefore passed happily when the shipped file was mutated to the OR form
     * the comments forbid. The truncated {@code {bcrypt}} case is the one that
     * discriminates -- with an OR chain, substring(from 9) is empty, crypt()
     * raises "invalid salt", and the seed aborts on exactly the kind of row it
     * exists to repair.
     */
    @Test
    void theSeedRepairsEveryWrongShapeAndLeavesACorrectRowAlone() throws Exception {
        assertRepaired("{noop}old-dev-password", "a {noop} row must be repaired");
        assertRepaired("{bcrypt}",         "a truncated row must be repaired, not raise invalid salt");
        assertRepaired("{noop}x",          "a short {noop} row must be repaired");
        assertRepaired("password",         "a bare plaintext row must be repaired");
        assertRepaired("$2a$10$notprefixedatall000000000000000000000000000000000000",
                       "an unprefixed bcrypt-looking row must be repaired");

        String correct = "{bcrypt}" + BCRYPT.encode(PW);
        assertEquals(correct, seedOver(correct),
                "a row already holding the right password must be left byte-identical");

        String other = "{bcrypt}" + BCRYPT.encode("a-different-password");
        assertNotEquals(other, seedOver(other),
                "a row holding a different password must be rewritten");
    }

    private void assertRepaired(String storedBefore, String why) throws Exception {
        String after = seedOver(storedBefore);
        assertTrue(after.startsWith("{bcrypt}$2"), why + " -- got: " + after);
        assertTrue(BCRYPT.matches(PW, after.substring("{bcrypt}".length())),
                why + " -- stored hash does not verify against the seeded password");
    }

    /**
     * Puts {@code storedBefore} in the admin row, applies the shipped seed, and
     * returns the resulting passphrase. A null means no pre-existing row.
     */
    private String seedOver(String storedBefore) throws Exception {
        String db = "devseed_probe";
        try (Connection con = freshDatabase(db)) {
            if (storedBefore != null) {
                try (Statement st = con.createStatement()) {
                    st.executeUpdate("""
                            INSERT INTO pressroom_user (id, username, passphrase, screenname,
                                                     fullname, emailaddress, datecreated,
                                                     locale, timezone, isenabled)
                            VALUES ('probe-0000-0000-0000-000000000001', 'admin', '%s',
                                    'Probe', 'Probe', 'probe@example.invalid', NOW(),
                                    'en_US', 'UTC', true)
                            """.formatted(storedBefore.replace("'", "''")));
                }
            }
            execute(con, seedSqlWith(PW));
            return adminPassphrase(con);
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingTheSeedTwiceLeavesThePassphraseByteIdentical() throws Exception {
        String db = "devseed_idempotent";
        try (Connection con = freshDatabase(db)) {
            execute(con, seedSqlWith(PW));
            String first = adminPassphrase(con);
            execute(con, seedSqlWith(PW));
            String second = adminPassphrase(con);
            assertEquals(first, second,
                    "a second seed run rewrote the row; the guard is not working");
            assertTrue(first.startsWith("{bcrypt}$2"), "seed stored: " + first);
            assertTrue(BCRYPT.matches(PW, first.substring("{bcrypt}".length())),
                    "the seeded hash does not verify against the seeded password");
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void changingThePasswordRewritesTheRow() throws Exception {
        String db = "devseed_rotate";
        try (Connection con = freshDatabase(db)) {
            execute(con, seedSqlWith(PW));
            String before = adminPassphrase(con);
            execute(con, seedSqlWith("a-completely-different-password"));
            String after = adminPassphrase(con);
            assertNotEquals(before, after,
                    "the guard never fires -- it suppresses real changes too");
            assertTrue(BCRYPT.matches("a-completely-different-password",
                            after.substring("{bcrypt}".length())),
                    "the new password does not verify");
        } finally {
            dropDatabase(db);
        }
    }

    private String adminPassphrase(Connection con) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT passphrase FROM pressroom_user WHERE username = 'admin'")) {
            assertTrue(rs.next(), "the seed did not create the admin user");
            return rs.getString(1);
        }
    }

    private void execute(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    // --- Isolated-database helpers: see ScratchDatabase ---

    private Connection freshDatabase(String dbName) throws Exception {
        Connection con = ScratchDatabase.migrated(dbName);
        // The seed itself does this, but guardSaysRewrite() runs the guard
        // expression alone without applying the seed.
        execute(con, "CREATE EXTENSION IF NOT EXISTS pgcrypto");
        return con;
    }

    private void dropDatabase(String dbName) throws Exception {
        ScratchDatabase.drop(dbName);
    }
}
