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

import org.apache.logging.log4j.Level;
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SQLScriptRunner} is the install wizard's own SQL splitter -- a third
 * applier alongside {@code migrate.sh} (psql) and the test harness (whole-string
 * JDBC). Unlike those two, it splits accumulated lines on trailing semicolons,
 * so a {@code DO $$ ... $$;} block (V017's cluster-global {@code CREATE ROLE}
 * guard) would be corrupted into broken fragments unless the splitter tracks
 * dollar-quote state.
 *
 * <p>The comment-stripping and {@code runScript} tests (error reporting,
 * stop-on-error, the tolerated {@code drop index} failure) are characterisation
 * tests, written against the existing behaviour and expected to pass
 * immediately.
 */
class SQLScriptRunnerTest {

    @Test
    void aDollarQuotedDoBlockStaysOneStatement() throws Exception {
        String sql = """
                CREATE TABLE IF NOT EXISTS t1 (id int);
                DO $$ BEGIN
                    CREATE ROLE somerole;
                EXCEPTION WHEN duplicate_object THEN
                    NULL;
                END $$;
                CREATE TABLE IF NOT EXISTS t2 (id int);
                """;
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        List<String> commands = runner.getCommands();

        assertEquals(3, commands.size(), "got: " + commands);
        assertTrue(commands.get(1).startsWith("DO $$"), commands.get(1));
        assertTrue(commands.get(1).contains("EXCEPTION WHEN duplicate_object"),
                "the block must survive intact: " + commands.get(1));
    }

    @Test
    void aTaggedDollarQuoteAlsoStaysOneStatement() throws Exception {
        String sql = "DO $guard$ BEGIN PERFORM 1; END $guard$;\nSELECT 1;";
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, runner.getCommands().size(),
                "got: " + runner.getCommands());
    }

    /**
     * A single, unpaired "$" -- as in a price literal -- must never be mistaken
     * for a dollar-quote delimiter: the delimiter regex requires a MATCHING pair
     * of "$"s, so one lone "$" can never open a quote and strand every statement
     * after it as "still inside a block".
     */
    @Test
    void aLoneDollarInAStringLiteralDoesNotOpenAQuote() throws Exception {
        String sql = "INSERT INTO price (amount) VALUES ('$5');\nSELECT 1;";
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, runner.getCommands().size(),
                "got: " + runner.getCommands());
    }

    private static SQLScriptRunner runnerFor(String sql) throws Exception {
        return new SQLScriptRunner(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A connection whose statements record what they execute and fail any
     * command containing {@code BAD} or {@code drop index} with {@code failure}.
     */
    private static Connection recordingConnection(List<String> executed, SQLException failure)
            throws SQLException {
        Connection con = mock(Connection.class);
        when(con.getAutoCommit()).thenReturn(true);
        when(con.createStatement()).thenAnswer(invocation -> {
            Statement st = mock(Statement.class);
            when(st.executeUpdate(anyString())).thenAnswer(call -> {
                String command = call.getArgument(0);
                if (command.contains("BAD") || command.contains("drop index")) {
                    throw failure;
                }
                executed.add(command);
                return 0;
            });
            return st;
        });
        return con;
    }

    @Test
    void aTrailingCommentIsStrippedAndWhitespaceCollapsed() throws Exception {
        SQLScriptRunner runner = runnerFor("""
                -- a whole-line comment is dropped
                CREATE TABLE t (id   int,    -- the key
                    name text);  -- trailing note
                """);

        assertEquals(List.of("CREATE TABLE t (id int, name text)"), runner.getCommands());
    }

    /**
     * The messages reach the anonymous installer's pages, and a migration's
     * SQL carries the database user (its :app_user grants). So a message names
     * only which statement failed; the SQL text and the exception go to the
     * server log.
     */
    @Test
    void withoutStopOnErrorAFailureIsReportedAndTheScriptContinues() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLException failure = new SQLException("syntax error at BAD");
        SQLScriptRunner runner = runnerFor("SELECT 1;\nBAD STATEMENT;\nSELECT 2;");

        List<String> logged;
        List<String> errors;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            runner.runScript(recordingConnection(executed, failure), false);
            logged = capture.everything();
            errors = capture.lines(Level.ERROR);
        }

        assertEquals(List.of("SELECT 1", "SELECT 2"), executed);
        assertFalse(runner.getFailed());
        assertEquals(List.of("ERROR: statement 2 of 3 failed; the details are in the server log."),
                runner.getMessages());
        assertTrue(errors.stream().anyMatch(line -> line.contains("BAD STATEMENT")),
                "the failing SQL is logged at ERROR: " + errors);
        assertTrue(logged.contains(failure.toString()), "with its exception: " + logged);
    }

    @Test
    void withStopOnErrorTheFirstFailureIsRethrownAndNothingAfterItRuns() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLException failure = new SQLException("syntax error at BAD");
        SQLScriptRunner runner = runnerFor("SELECT 1;\nBAD STATEMENT;\nSELECT 2;");

        List<String> logged;
        SQLException thrown;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            thrown = assertThrows(SQLException.class,
                    () -> runner.runScript(recordingConnection(executed, failure), true));
            logged = capture.everything();
        }

        assertSame(failure, thrown);
        assertTrue(runner.getFailed());
        assertEquals(List.of("SELECT 1"), executed);
        assertEquals(List.of("ERROR: statement 2 of 3 failed; the details are in the server log."),
                runner.getMessages());
        assertTrue(logged.stream().anyMatch(line -> line.contains("BAD STATEMENT")), logged.toString());
        // The exception is rethrown, so the caller logs it with its trace
        // (DatabaseInstaller.applyMigration); logging it here too would print it twice.
        assertFalse(logged.contains(failure.toString()), logged.toString());
    }

    @Test
    void aFailedDropIndexIsToleratedEvenWhenStoppingOnError() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLScriptRunner runner = runnerFor("alter table t drop index ix_t;\nSELECT 2;");

        runner.runScript(recordingConnection(executed, new SQLException("no such index")), true);

        assertFalse(runner.getFailed());
        assertEquals(List.of("SELECT 2"), executed);
        assertEquals(List.of("INFO: statement 1 of 2 failed and was ignored."), runner.getMessages());
    }

    @Test
    void withAutocommitOnNoSavepointMachineryIsUsed() throws Exception {
        List<String> executed = new ArrayList<>();
        Connection con = recordingConnection(executed, new SQLException("unused"));
        SQLScriptRunner runner = runnerFor("SELECT 1;");

        runner.runScript(con, true);

        verify(con, never()).setSavepoint();
        verify(con, never()).commit();
    }

    /**
     * When the connection is not in autocommit mode, the caller (in
     * production, {@code DatabaseInstaller.applyMigration}) owns the
     * transaction: {@code runScript} must never commit on its behalf, or a
     * rollback after a later statement's failure only undoes that one
     * statement rather than the whole script.
     */
    @Test
    void insideATransactionEachSuccessfulStatementIsSavepointedAndReleasedButNeverCommitted()
            throws Exception {
        List<String> executed = new ArrayList<>();
        Connection con = mock(Connection.class);
        when(con.getAutoCommit()).thenReturn(false);
        Savepoint sp1 = mock(Savepoint.class);
        Savepoint sp2 = mock(Savepoint.class);
        when(con.setSavepoint()).thenReturn(sp1, sp2);
        when(con.createStatement()).thenAnswer(invocation -> {
            Statement st = mock(Statement.class);
            when(st.executeUpdate(anyString())).thenAnswer(call -> {
                executed.add(call.getArgument(0));
                return 0;
            });
            return st;
        });
        SQLScriptRunner runner = runnerFor("SELECT 1;\nSELECT 2;");

        runner.runScript(con, true);

        assertEquals(List.of("SELECT 1", "SELECT 2"), executed);
        verify(con).releaseSavepoint(sp1);
        verify(con).releaseSavepoint(sp2);
        verify(con, never()).commit();
        verify(con, never()).rollback(any(Savepoint.class));
    }

    /**
     * PostgreSQL aborts the whole transaction after any failed statement
     * unless the failure is isolated by a savepoint; without one, a tolerated
     * failure (or {@code stopOnError=false}) would poison every statement
     * after it, not just report and move on.
     */
    @Test
    void insideATransactionAToleratedFailureRollsBackToItsSavepointAndTheScriptContinues()
            throws Exception {
        List<String> executed = new ArrayList<>();
        Connection con = mock(Connection.class);
        when(con.getAutoCommit()).thenReturn(false);
        Savepoint sp1 = mock(Savepoint.class);
        Savepoint sp2 = mock(Savepoint.class);
        when(con.setSavepoint()).thenReturn(sp1, sp2);
        SQLException failure = new SQLException("no such index");
        when(con.createStatement()).thenAnswer(invocation -> {
            Statement st = mock(Statement.class);
            when(st.executeUpdate(anyString())).thenAnswer(call -> {
                String command = call.getArgument(0);
                if (command.contains("drop index")) {
                    throw failure;
                }
                executed.add(command);
                return 0;
            });
            return st;
        });
        SQLScriptRunner runner = runnerFor("alter table t drop index ix_t;\nSELECT 2;");

        runner.runScript(con, true);

        assertFalse(runner.getFailed());
        assertEquals(List.of("SELECT 2"), executed);
        verify(con).rollback(sp1);
        verify(con).releaseSavepoint(sp2);
        verify(con, never()).commit();
    }

    @Test
    void insideATransactionStopOnErrorRollsBackToTheSavepointBeforeRethrowingAndNeverCommits()
            throws Exception {
        Connection con = mock(Connection.class);
        when(con.getAutoCommit()).thenReturn(false);
        Savepoint sp1 = mock(Savepoint.class);
        when(con.setSavepoint()).thenReturn(sp1);
        SQLException failure = new SQLException("syntax error at BAD");
        when(con.createStatement()).thenAnswer(invocation -> {
            Statement st = mock(Statement.class);
            when(st.executeUpdate(anyString())).thenThrow(failure);
            return st;
        });
        SQLScriptRunner runner = runnerFor("BAD STATEMENT;");

        SQLException thrown = assertThrows(SQLException.class, () -> runner.runScript(con, true));

        assertSame(failure, thrown);
        assertTrue(runner.getFailed());
        verify(con).rollback(sp1);
        verify(con, never()).commit();
    }

    @Test
    void replacedCommandsAreWhatRuns() throws Exception {
        List<String> executed = new ArrayList<>();
        SQLScriptRunner runner = runnerFor("SELECT 1;");

        runner.setCommands(List.of("SELECT 42", "SELECT 43"));
        runner.runScript(recordingConnection(executed, new SQLException("unused")), true);

        assertEquals(2, runner.getCommandCount());
        assertEquals(List.of("SELECT 42", "SELECT 43"), executed);
    }
}
