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

import com.wikantik.pressroom.business.DatabaseProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DatabaseInstaller#connectionUser} defends a value that gets spliced
 * straight into a GRANT statement. {@code DatabaseMetaData.getUserName()} is
 * known non-null to SpotBugs's own JDBC annotation database (a {@code == null}
 * check on it is flagged RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE, suppressed
 * at the call site) -- but that javadoc guarantees nothing about null, so the
 * null guard is kept anyway and fails safe with a diagnostic message rather
 * than a bare NPE. These tests pin all three branches of that check directly,
 * without a real database connection.
 *
 * <p>The unreachable-database tests below are characterisation tests, written
 * against the existing behaviour and expected to pass immediately: none of
 * the three entry points may read a connection failure as "nothing to do".
 */
class DatabaseInstallerTest {

    private DatabaseInstaller installer() {
        return new DatabaseInstaller(null, null);
    }

    private Connection connectionWithUser(String user) throws SQLException {
        Connection con = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(con.getMetaData()).thenReturn(metaData);
        when(metaData.getUserName()).thenReturn(user);
        return con;
    }

    @Test
    void aWellFormedUsernamePassesThrough() throws Exception {
        Connection con = connectionWithUser("pressroom_app");
        assertEquals("pressroom_app", installer().connectionUser(con));
    }

    @Test
    void anUnsafeUsernameIsRefused() throws Exception {
        Connection con = connectionWithUser("bad; DROP TABLE x; --");
        SQLException ex = assertThrows(SQLException.class, () -> installer().connectionUser(con));
        assertEquals(
                "Refusing to substitute unsafe database user name: bad; DROP TABLE x; --",
                ex.getMessage());
    }

    @Test
    void aNullUsernameIsRefusedWithADiagnosticMessage() throws Exception {
        Connection con = connectionWithUser(null);
        SQLException ex = assertThrows(SQLException.class, () -> installer().connectionUser(con));
        assertEquals(
                "Refusing to substitute unsafe database user name: null",
                ex.getMessage());
    }

    private static DatabaseProvider unreachable(SQLException refusal) throws SQLException {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenThrow(refusal);
        return provider;
    }

    @Test
    void anUnreachableDatabaseIsNotReportedAsNeedingNoCreation() throws Exception {
        SQLException refusal = new SQLException("connection refused");
        DatabaseInstaller installer = new DatabaseInstaller(unreachable(refusal), null);

        RuntimeException ex = assertThrows(RuntimeException.class, installer::isCreationRequired);

        assertEquals("Error checking database state", ex.getMessage());
        assertSame(refusal, ex.getCause());
    }

    @Test
    void anUnreachableDatabaseIsNotReportedAsUpToDate() throws Exception {
        SQLException refusal = new SQLException("connection refused");
        DatabaseInstaller installer = new DatabaseInstaller(unreachable(refusal), null);

        RuntimeException ex = assertThrows(RuntimeException.class, installer::isUpgradeRequired);

        assertEquals("Error checking for pending migrations", ex.getMessage());
        assertSame(refusal, ex.getCause());
    }

    @Test
    void anUnreachableDatabaseFailsCreationWithTheInstallersLog() throws Exception {
        SQLException refusal = new SQLException("connection refused");
        DatabaseInstaller installer = new DatabaseInstaller(unreachable(refusal), null);

        StartupException ex = assertThrows(StartupException.class, installer::createDatabase);

        assertEquals("Error applying database migrations", ex.getMessage());
        assertSame(refusal, ex.getCause());
        assertEquals(List.of("ERROR connecting to database to apply migrations"), ex.getStartupLog());
        assertEquals(ex.getStartupLog(), installer.getMessages());
    }

    /** A database with no tables at all, so the first migration (V001) is the one applied. */
    private static DatabaseProvider emptyDatabase(Connection con) throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(con.getMetaData()).thenReturn(metaData);
        when(metaData.getUserName()).thenReturn("pressroom");
        when(metaData.getTables(null, null, "%", null)).thenAnswer(i -> mock(java.sql.ResultSet.class));
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenReturn(con);
        return provider;
    }

    /**
     * Review M4: readMigration catches only IOException, so a runtime fault
     * from the script provider leaves it unwrapped. That is the right shape:
     * its one caller, applyMigration, is the boundary, and wraps any
     * RuntimeException in a StartupException naming the migration, with the
     * installer's log, after rolling the transaction back. Characterisation
     * test: passes against the code as it stands, and pins that boundary.
     */
    @Test
    void aRuntimeFaultReadingAMigrationFailsStartupNamingTheMigration() throws Exception {
        Connection con = mock(Connection.class);
        IllegalStateException fault = new IllegalStateException("classpath broken");
        DatabaseInstaller installer = new DatabaseInstaller(emptyDatabase(con), path -> {
            throw fault;
        });

        StartupException ex = assertThrows(StartupException.class, installer::createDatabase);

        assertEquals("Error applying migration V001", ex.getMessage().replaceAll("__.*", ""));
        assertSame(fault, ex.getCause());
        assertTrue(ex.getStartupLog().stream().anyMatch(line -> line.startsWith("ERROR applying migration V001")),
                "the installer's log names the migration: " + ex.getStartupLog());
        org.mockito.Mockito.verify(con).rollback();
    }

    /**
     * A migration missing from the classpath is a startup failure naming it,
     * not a silent skip. Characterisation test, passing as written.
     */
    @Test
    void aMigrationMissingFromTheClasspathFailsStartupNamingIt() throws Exception {
        DatabaseInstaller installer = new DatabaseInstaller(emptyDatabase(mock(Connection.class)), path -> null);

        StartupException ex = assertThrows(StartupException.class, installer::createDatabase);

        assertTrue(ex.getCause().getMessage().startsWith("Migration V001"), ex.getCause().getMessage());
        assertTrue(ex.getCause().getMessage().endsWith("not found on the classpath"));
    }
}
