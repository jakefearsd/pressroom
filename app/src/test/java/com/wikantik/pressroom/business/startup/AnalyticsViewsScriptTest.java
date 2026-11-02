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
package com.wikantik.pressroom.business.startup;

import com.wikantik.pressroom.testing.UmamiContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped {@code deploy/analytics-views.sh} against the real Umami
 * ({@link UmamiContainer}: the compose file's own digest, its own scratch
 * PostgreSQL), the way the {@code analytics-views} one-shot does: after
 * Umami's first boot, with the compose service's environment. The views are
 * {@link UmamiViewScriptTest}'s subject; this pins the step after them, which
 * creates Pressroom's own Umami account from {@code UMAMI_PRESSROOM_PASSWORD}
 * (the SQL itself is pinned by {@code UmamiApiContractTest}).
 *
 * <p>The account name is overridable only so this class does not touch the
 * {@code pressroom} account the contract test signs in as, on the shared
 * instance.
 */
class AnalyticsViewsScriptTest {

    private static final Path SCRIPT = Paths.get("../deploy/analytics-views.sh");
    private static final Path VIEWS_SQL = Paths.get("../deploy/analytics/umami-views.sql");
    private static final Path ACCOUNT_SQL = Paths.get("../deploy/analytics/umami-pressroom-account.sql");

    private static UmamiContainer umami;

    @BeforeAll
    static void start() throws Exception {
        umami = UmamiContainer.get();
        // provision.sh creates grafana_ro in production; the view grants to it.
        try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
            st.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
                            CREATE ROLE grafana_ro NOLOGIN;
                        END IF;
                    END $$""");
        }
    }

    private static Container.ExecResult run(Map<String, String> env) {
        Map<String, String> all = new HashMap<>(env);
        all.put("ANALYTICS_SQL_DIR", ".");
        all.put("ANALYTICS_VIEWS_WAIT_SECONDS", "30");
        return umami.runDeployScript(SCRIPT, all, VIEWS_SQL, ACCOUNT_SQL);
    }

    private static String output(Container.ExecResult result) {
        return result.getStdout() + result.getStderr();
    }

    @Test
    void accountStepIsIdempotent() throws Exception {
        String username = "pressroom-views-script";
        String password = "views-script-" + UUID.randomUUID();
        Map<String, String> env = Map.of(
                "UMAMI_PRESSROOM_USERNAME", username, "UMAMI_PRESSROOM_PASSWORD", password);

        Container.ExecResult first = run(env);
        assertEquals(0, first.getExitCode(), output(first));
        assertFalse(output(first).contains(password), "the password must never be echoed: " + output(first));
        assertDoesNotThrow(() -> umami.login(username, password), "the account signs in");

        Container.ExecResult second = run(env);
        assertEquals(0, second.getExitCode(), output(second));
        assertFalse(output(second).contains(password), output(second));
        assertDoesNotThrow(() -> umami.login(username, password), "a re-run keeps the account working");

        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT count(*), min(role) FROM \"user\" WHERE username = ? AND deleted_at IS NULL")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "a re-run updates the one row, never adds a second");
                assertEquals("user", rs.getString(2), "an ordinary account, not an admin");
            }
        }
    }

    /** Compose passes an empty value when .env has none: the views still install and the stack is fine. */
    @Test
    void withoutAPasswordRegistrationIsOff() throws Exception {
        String username = "pressroom-views-off";
        Container.ExecResult result = run(Map.of(
                "UMAMI_PRESSROOM_USERNAME", username, "UMAMI_PRESSROOM_PASSWORD", ""));

        assertEquals(0, result.getExitCode(), output(result));
        assertTrue(result.getStdout().contains("analytics registration is off"), result.getStdout());
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM \"user\" WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "no password, no account");
            }
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT to_regclass('public.analytics_traffic') IS NOT NULL")) {
                assertTrue(rs.next() && rs.getBoolean(1), "the views install regardless");
            }
        }
    }

    /**
     * Umami creates {@code website_event} and {@code "user"} in one migration,
     * so the views' wait normally covers both; should they ever part, the
     * account step must say why it did nothing, not fail inside the SQL with
     * the password already sent. A scratch database with the event table and
     * no user table stands in for that Umami.
     */
    @Test
    void anUnmigratedUmamiSkipsTheAccountAndSaysToRedeploy() throws Exception {
        String scratch = "views_no_user_table";
        try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + scratch);
            st.execute("CREATE DATABASE " + scratch);
        }
        try {
            try (Connection con = umami.jdbc(scratch); Statement st = con.createStatement()) {
                st.execute("CREATE TABLE website_event (website_id uuid, session_id uuid, created_at timestamptz,"
                        + " url_path varchar(500), event_type integer)");
            }
            String password = "unmigrated-" + UUID.randomUUID();
            Container.ExecResult result = run(Map.of("UMAMI_DB", scratch,
                    "UMAMI_PRESSROOM_USERNAME", "pressroom-unmigrated", "UMAMI_PRESSROOM_PASSWORD", password));

            assertEquals(1, result.getExitCode(), "a skipped account is a step not done: " + output(result));
            assertTrue(result.getStderr().contains(
                    "Umami not migrated yet: registration account skipped, re-run deploy"), output(result));
            assertFalse(output(result).contains(password), output(result));
        } finally {
            try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + scratch + " WITH (FORCE)");
            }
        }
    }

    /**
     * A {@code -v pw=...} argument would put the password on psql's command
     * line, readable by anyone on the host through {@code ps}. The script
     * reads it from the environment inside psql instead.
     */
    @Test
    void thePasswordNeverReachesACommandLine() throws Exception {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        assertFalse(script.matches("(?s).*-v\\s+\"?pw=.*"), "psql must not take the password as -v pw=");
        assertTrue(script.contains("\\getenv pw UMAMI_PRESSROOM_PASSWORD"),
                "psql reads the password from its environment");
        assertFalse(script.matches("(?s).*\\bset\\s+-[a-z]*x.*"), "set -x would echo every expanded command");
    }
}
