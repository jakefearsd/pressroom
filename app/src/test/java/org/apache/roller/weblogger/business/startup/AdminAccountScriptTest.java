/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.business.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.roller.testing.RollerPostgresContainer;
import org.apache.roller.testing.ScratchDatabase;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.ui.core.security.RollerUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the SHIPPED deploy/admin-account.sh inside the suite's PostgreSQL
 * container (which has bash and psql 18, as the app image does), then
 * proves the result through the app's real login path:
 * RollerUserDetailsService + RollerContext.createPasswordEncoder() behind
 * a DaoAuthenticationProvider, wired as SecurityConfig wires them.
 * Spec: docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md
 */
class AdminAccountScriptTest {

    private static final Path SCRIPT = Paths.get("../deploy/admin-account.sh");
    private static final String IN_CONTAINER = "/tmp/admin-account.sh";
    private static final String GOOD_PASSWORD = "correct horse battery";
    private final List<String> created = new ArrayList<>();

    record Run(int exit, String stdout, String stderr) {}

    @BeforeAll
    static void up() throws Exception {
        TestUtils.setupWeblogger();
        var pg = RollerPostgresContainer.get();
        pg.copyFileToContainer(MountableFile.forHostPath(SCRIPT, 0755), IN_CONTAINER);
        // A psql stand-in that records its argv, then runs the real psql:
        // acceptance criterion 3 (the password is in no psql argument).
        String shim = """
                #!/bin/bash
                printf '%s\\n' "$@" >> /tmp/shim/argv.log
                real=$(PATH=${PATH#/tmp/shim:} command -v psql)
                exec "$real" "$@"
                """;
        pg.copyFileToContainer(Transferable.of(shim.getBytes(StandardCharsets.UTF_8), 0755),
                "/tmp/shim/psql");
    }

    @AfterEach
    void deleteCreatedAccounts() throws Exception {
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword())) {
            for (String name : created) {
                try (PreparedStatement r = c.prepareStatement("DELETE FROM userrole WHERE username = ?");
                     PreparedStatement u = c.prepareStatement("DELETE FROM roller_user WHERE username = ?")) {
                    r.setString(1, name); r.executeUpdate();
                    u.setString(1, name); u.executeUpdate();
                }
            }
        }
    }

    static String suiteDb() {
        return RollerPostgresContainer.get().getDatabaseName();
    }

    String uniqueName() {
        String name = "tool" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        created.add(name);
        return name;
    }

    /** stdin comes from a file, so the password is on no command line here either. */
    static Run run(String db, String stdin, String... args) throws Exception {
        var pg = RollerPostgresContainer.get();
        String in = "/tmp/stdin-" + UUID.randomUUID();
        pg.copyFileToContainer(Transferable.of(stdin == null ? "" : stdin), in);
        StringBuilder cmd = new StringBuilder()
                .append("POSTGRES_USER=").append(q(pg.getUsername()))
                .append(" POSTGRES_PASSWORD=").append(q(pg.getPassword()))
                .append(" POSTGRES_DB=").append(q(db))
                .append(" PGHOST=localhost PATH=/tmp/shim:$PATH ").append(IN_CONTAINER);
        for (String a : args) {
            cmd.append(' ').append(q(a));
        }
        cmd.append(" < ").append(in);
        var r = pg.execInContainer("bash", "-c", cmd.toString());
        pg.execInContainer("rm", "-f", in);
        return new Run(r.getExitCode(), r.getStdout(), r.getStderr());
    }

    static String q(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    static Authentication login(String user, String password) {
        DaoAuthenticationProvider p = new DaoAuthenticationProvider(
                new RollerUserDetailsService(TestUtils.provider()));
        p.setPasswordEncoder(RollerContext.createPasswordEncoder());
        return p.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(user, password));
    }

    static long accountsNamed(String name) throws Exception {
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement(
                     "SELECT (SELECT count(*) FROM roller_user WHERE lower(username) = lower(?))"
                             + " + (SELECT count(*) FROM userrole WHERE lower(username) = lower(?))")) {
            s.setString(1, name);
            s.setString(2, name);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // --- acceptance criterion 1 ---
    @Test
    void createMakesAnEnabledAdminTheRealLoginPathAccepts() throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), GOOD_PASSWORD + "\n",
                "create", "--username", name, "--email", name + "@example.com", "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        Authentication a = login(name, GOOD_PASSWORD);
        // Spring Security 7 adds a FACTOR_PASSWORD authority to a password
        // login; the account's own roles are what is under test.
        assertEquals(Set.of("admin", "editor"), a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(role -> !role.startsWith("FACTOR_"))
                .collect(Collectors.toSet()));
    }

    // --- Review Focus 1 ---
    @Test
    void passwordIsStoredExactlyAsTyped() throws Exception {
        String name = uniqueName();
        String tricky = "  it's a \"p@ss\" $HOME \\ über \t ";
        Run r = run(suiteDb(), tricky + "\n",
                "create", "--username", name, "--email", name + "@example.com", "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        assertNotNull(login(name, tricky));
        assertThrows(BadCredentialsException.class, () -> login(name, tricky.strip()));
    }

    // --- prompt mode: two lines, which must match ---
    @Test
    void promptModeCreatesWhenBothEntriesMatch() throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), GOOD_PASSWORD + "\n" + GOOD_PASSWORD + "\n",
                "create", "--username", name, "--email", name + "@example.com");
        assertEquals(0, r.exit(), r.stderr());
        assertNotNull(login(name, GOOD_PASSWORD));
    }

    // --- acceptance criterion 2: every refusal writes nothing ---
    @Test
    void refusalsWriteNothing() throws Exception {
        String existing = uniqueName();
        assertEquals(0, run(suiteDb(), GOOD_PASSWORD + "\n", "create", "--username", existing,
                "--email", existing + "@example.com", "--password-stdin").exit());
        long before = accountsNamed(existing);

        String fresh = uniqueName();
        record Case(String why, String stdin, String user, String email, boolean fromStdin, String counted) {
            Case(String why, String stdin, String user, String email, boolean fromStdin) {
                this(why, stdin, user, email, fromStdin, user);
            }
        }
        // The image runs LC_ALL=en_US.UTF-8, where a bash [A-Za-z] range matches
        // letters like ä; the check must be ASCII whatever the locale.
        created.add("j\u00e4ke");
        created.add("\uff41\uff44\uff4d\uff49\uff4e");
        String longEmail = "a".repeat(250) + "@e.com";   // 256 characters: varchar(255)
        List<Case> cases = List.of(
                new Case("non-ASCII letter in username", GOOD_PASSWORD + "\n", "j\u00e4ke", "x@example.com", true),
                new Case("fullwidth letters in username", GOOD_PASSWORD + "\n",
                        "\uff41\uff44\uff4d\uff49\uff4e", "x@example.com", true),
                new Case("email over 255 characters", GOOD_PASSWORD + "\n", fresh, longEmail, true),
                new Case("existing username, other case", GOOD_PASSWORD + "\n",
                        existing.toUpperCase(Locale.ROOT), "x@example.com", true, fresh),
                new Case("bad username character", GOOD_PASSWORD + "\n", fresh + "-x", "x@example.com", true),
                new Case("malformed email", GOOD_PASSWORD + "\n", fresh, "not-an-email", true),
                new Case("7-character password", "1234567\n", fresh, "x@example.com", true),
                new Case("confirmation differs", GOOD_PASSWORD + "\nsomething else\n", fresh, "x@example.com", false),
                new Case("empty stdin", "", fresh, "x@example.com", true));
        for (Case c : cases) {
            List<String> args = new ArrayList<>(List.of("create", "--username", c.user(), "--email", c.email()));
            if (c.fromStdin()) {
                args.add("--password-stdin");
            }
            Run r = run(suiteDb(), c.stdin(), args.toArray(String[]::new));
            assertEquals(1, r.exit(), c.why() + ": " + r.stderr());
            assertFalse(r.stderr().isBlank(), c.why() + " must say what was wrong");
            assertEquals(0, accountsNamed(c.counted()), c.why() + " wrote rows");
        }
        assertEquals(before, accountsNamed(existing), "the existing account was touched");
    }

    @Test
    void anExistingDisabledAccountAlsoBlocksCreate() throws Exception {
        String name = uniqueName();
        assertEquals(0, run(suiteDb(), GOOD_PASSWORD + "\n", "create", "--username", name,
                "--email", name + "@example.com", "--password-stdin").exit());
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement("UPDATE roller_user SET isenabled = false WHERE username = ?")) {
            s.setString(1, name);
            s.executeUpdate();
        }
        Run r = run(suiteDb(), GOOD_PASSWORD + "\n", "create", "--username", name,
                "--email", name + "@example.com", "--password-stdin");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("already exists"), r.stderr());
    }

    private String createWith(String password) throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), password + "\n", "create", "--username", name,
                "--email", name + "@example.com", "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        return name;
    }

    // --- acceptance criterion 4 ---
    // The account is never logged in before the reset, so nothing has cached it.
    @Test
    void resetReplacesThePassword() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        assertNotNull(login(name, "a brand new one"));
        assertThrows(BadCredentialsException.class, () -> login(name, GOOD_PASSWORD));
    }

    // --- Review Focus 2 ---
    @Test
    void resetEndsWithTheRestartCommand() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        List<String> lines = r.stdout().strip().lines().toList();
        assertEquals("  docker compose -f docker-compose.prod.yml restart app", lines.get(lines.size() - 1));
    }

    /**
     * Why reset-password tells the operator to restart the app: a running
     * tier that has loaded an account keeps serving the password it loaded
     * (JPAUserManagerImpl.userNameToIdMap, then EclipseLink's shared cache
     * behind getUser). This pins that limitation. If it starts failing, the
     * cache went away, and the restart advice in admin-account.sh and the
     * runbook should go with it.
     */
    @Test
    void aRunningTierKeepsTheOldPasswordUntilRestart() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        assertNotNull(login(name, GOOD_PASSWORD));   // the tier now holds the account
        assertEquals(0, run(suiteDb(), "a brand new one\n", "reset-password",
                "--username", name, "--password-stdin").exit());
        assertNotNull(login(name, GOOD_PASSWORD), "expected the cached hash to still be served");
    }

    @Test
    void resetOfAnUnknownAccountChangesNothing() throws Exception {
        String name = uniqueName();
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("no account named"), r.stderr());
        assertEquals(0, accountsNamed(name));
    }

    @Test
    void resetOfADisabledAccountWarns() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement("UPDATE roller_user SET isenabled = false WHERE username = ?")) {
            s.setString(1, name);
            s.executeUpdate();
        }
        String before = passphraseOf(suiteDb(), name);
        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");
        assertEquals(0, r.exit(), r.stderr());
        assertTrue(r.stderr().contains("disabled"), r.stderr());
        assertTrue(!before.equals(passphraseOf(suiteDb(), name)), "the stored passphrase did not change");
    }

    static String passphraseOf(String db, String name) throws Exception {
        try (Connection c = DriverManager.getConnection(ScratchDatabase.jdbcUrl(db),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement("SELECT passphrase FROM roller_user WHERE username = ?")) {
            s.setString(1, name);
            try (ResultSet rs = s.executeQuery()) {
                assertTrue(rs.next(), "no account " + name);
                return rs.getString(1);
            }
        }
    }

    // --- acceptance criterion 5 and Review Focus 5 (isolated database: the
    // suite's own may hold other tests' admins) ---
    @Test
    void statusReportsEnabledAdminsOnly() throws Exception {
        String db = "admintool_status";
        try (Connection c = ScratchDatabase.migrated(db)) {
            Run none = run(db, "", "status");
            assertEquals(3, none.exit(), none.stderr());
            assertTrue(none.stdout().contains("run --rm admin create"), none.stdout());

            assertEquals(0, run(db, GOOD_PASSWORD + "\n", "create", "--username", "alice",
                    "--email", "alice@example.com", "--password-stdin").exit());
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE roller_user SET isenabled = false WHERE username = 'alice'");
            }
            assertEquals(3, run(db, "", "status").exit(), "a disabled admin is no admin");

            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE roller_user SET isenabled = true WHERE username = 'alice'");
            }
            Run some = run(db, "", "status");
            assertEquals(0, some.exit(), some.stderr());
            assertTrue(some.stdout().contains("alice"), some.stdout());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    /** Final review M2: a Site-administrator agent never signs in, so it is no admin account to count. */
    @Test
    void statusDoesNotCountAnAgentThatIsASiteAdmin() throws Exception {
        String db = "admintool_status_agent";
        try (Connection c = ScratchDatabase.migrated(db)) {
            assertEquals(0, run(db, GOOD_PASSWORD + "\n", "create", "--username", "alice",
                    "--email", "alice@example.com", "--password-stdin").exit());
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE roller_user SET kind = 'AGENT' WHERE username = 'alice'");
            }
            Run r = run(db, "", "status");
            assertEquals(3, r.exit(), "an agent holding admin is no admin account: " + r.stdout());
            assertFalse(r.stdout().contains("alice"), r.stdout());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    /** Final review M2: setting a password on an agent would only mislead; it can never sign in. */
    @Test
    void resetRefusesAnAgentAndChangesNothing() throws Exception {
        String name = createWith(GOOD_PASSWORD);
        try (Connection c = DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
             PreparedStatement s = c.prepareStatement("UPDATE roller_user SET kind = 'AGENT' WHERE username = ?")) {
            s.setString(1, name);
            s.executeUpdate();
        }
        String before = passphraseOf(suiteDb(), name);

        Run r = run(suiteDb(), "a brand new one\n", "reset-password", "--username", name, "--password-stdin");

        assertEquals(1, r.exit(), r.stdout());
        assertTrue(r.stderr().contains("agent"), r.stderr());
        assertFalse(r.stdout().contains("password reset"), r.stdout());
        assertEquals(before, passphraseOf(suiteDb(), name), "the agent's passphrase was touched");
    }

    // --- acceptance criterion 3 ---
    @Test
    void thePasswordAppearsInNoOutputAndNoPsqlArgument() throws Exception {
        RollerPostgresContainer.get().execInContainer("rm", "-f", "/tmp/shim/argv.log");
        String name = uniqueName();
        String secret = "s3cret-" + UUID.randomUUID();
        Run r = run(suiteDb(), secret + "\n" + secret + "\n",
                "create", "--username", name, "--email", name + "@example.com");
        assertEquals(0, r.exit(), r.stderr());
        String argv = RollerPostgresContainer.get().execInContainer("cat", "/tmp/shim/argv.log").getStdout();
        assertFalse(argv.isBlank(), "the shim saw no psql call: is psql resolved through PATH?");
        assertFalse(argv.contains(secret), "password passed to psql as an argument");
        assertFalse(r.stdout().contains(secret) || r.stderr().contains(secret), "password printed");

        // AC3 covers any subcommand: reset-password too.
        RollerPostgresContainer.get().execInContainer("rm", "-f", "/tmp/shim/argv.log");
        String secret2 = "s3cret-" + UUID.randomUUID();
        Run r2 = run(suiteDb(), secret2 + "\n" + secret2 + "\n", "reset-password", "--username", name);
        assertEquals(0, r2.exit(), r2.stderr());
        String argv2 = RollerPostgresContainer.get().execInContainer("cat", "/tmp/shim/argv.log").getStdout();
        assertFalse(argv2.isBlank(), "the shim saw no psql call on reset-password");
        assertFalse(argv2.contains(secret2), "password passed to psql as an argument on reset");
        assertFalse(r2.stdout().contains(secret2) || r2.stderr().contains(secret2), "password printed on reset");
    }

    // --- I2: a failed write must not leave the password in the server log ---
    private static final String FAIL_FN = "CREATE FUNCTION admintool_fail() RETURNS trigger LANGUAGE plpgsql AS"
            + " $$BEGIN RAISE EXCEPTION 'forced failure'; END$$";

    @Test
    void aFailedCreateLeavesThePasswordOutOfTheServerLog() throws Exception {
        String db = "admintool_logc";
        try (Connection c = ScratchDatabase.migrated(db); Statement st = c.createStatement()) {
            st.execute(FAIL_FN);
            st.execute("CREATE TRIGGER admintool_fail BEFORE INSERT ON roller_user"
                    + " FOR EACH ROW EXECUTE FUNCTION admintool_fail()");
            String secret = "s3cret-" + UUID.randomUUID();
            Run r = run(db, secret + "\n", "create", "--username", "carol",
                    "--email", "carol@example.com", "--password-stdin");
            assertTrue(r.exit() != 0, "the forced failure should fail the tool");
            assertFalse(r.stdout().contains(secret) || r.stderr().contains(secret), "password printed");
            assertTrue(RollerPostgresContainer.get().getLogs().contains("forced failure"),
                    "the failed statement never reached the server log: the check proves nothing");
            assertFalse(RollerPostgresContainer.get().getLogs().contains(secret),
                    "password in the PostgreSQL server log");
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void aFailedResetLeavesThePasswordOutOfTheServerLog() throws Exception {
        String db = "admintool_logr";
        try (Connection c = ScratchDatabase.migrated(db); Statement st = c.createStatement()) {
            assertEquals(0, run(db, GOOD_PASSWORD + "\n", "create", "--username", "dave",
                    "--email", "dave@example.com", "--password-stdin").exit());
            st.execute(FAIL_FN);
            st.execute("CREATE TRIGGER admintool_fail BEFORE UPDATE ON roller_user"
                    + " FOR EACH ROW EXECUTE FUNCTION admintool_fail()");
            String secret = "s3cret-" + UUID.randomUUID();
            Run r = run(db, secret + "\n", "reset-password", "--username", "dave", "--password-stdin");
            assertTrue(r.exit() != 0, "the forced failure should fail the tool");
            assertFalse(r.stdout().contains(secret) || r.stderr().contains(secret), "password printed");
            assertTrue(RollerPostgresContainer.get().getLogs().contains("forced failure"),
                    "the failed statement never reached the server log: the check proves nothing");
            assertFalse(RollerPostgresContainer.get().getLogs().contains(secret),
                    "password in the PostgreSQL server log");
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    // --- M1: a check that could not run is not "no admin" ---
    @Test
    void statusOnASqlErrorExitsOneNotThree() throws Exception {
        String db = "admintool_staterr";
        try (Connection c = ScratchDatabase.migrated(db); Statement st = c.createStatement()) {
            st.execute("DROP TABLE userrole");
            Run r = run(db, "", "status");
            assertEquals(1, r.exit(), r.stderr());
            assertTrue(r.stderr().contains("database error"), r.stderr());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    // --- Review Focus 3 ---
    @Test
    void createOnAnEmptyDatabaseExplainsTheSchemaIsMissing() throws Exception {
        String db = "admintool_empty";
        ScratchDatabase.empty(db);
        try {
            Run r = run(db, GOOD_PASSWORD + "\n", "create", "--username", "someone",
                    "--email", "someone@example.com", "--password-stdin");
            assertEquals(1, r.exit());
            assertTrue(r.stderr().contains("schema is not installed"), r.stderr());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    // --- Review Focus 4 ---
    @Test
    void aFlagWithoutItsValueIsAUsageError() throws Exception {
        for (String[] args : List.of(new String[] {"create", "--username"},
                new String[] {"create", "--bogus"}, new String[] {}, new String[] {"frobnicate"},
                new String[] {"create", "--username", "x"})) {
            Run r = run(suiteDb(), "", args);
            assertEquals(2, r.exit(), String.join(" ", args) + ": " + r.stderr());
            assertTrue(r.stderr().contains("usage:"), r.stderr());
        }
    }
}
