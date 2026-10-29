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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.DatabaseProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies Roller's PostgreSQL schema migrations.
 *
 * <p>Migrations are the numbered {@code V<NNN>__<description>.sql} files under
 * {@code bin/db/migrations}, copied onto the classpath at {@code /dbmigrations}
 * by the build. Applied versions are recorded in the {@code schema_migrations}
 * table, so this class and {@code bin/db/migrate.sh} can be used
 * interchangeably: whichever runs first, the other skips what is already done.
 *
 * <p>This replaces the earlier design, which rendered vendor-specific DDL for
 * seven databases from Velocity templates and walked a hardcoded
 * {@code upgradeTo400/500/510/520/610} chain keyed off a
 * {@code roller.database.version} row in {@code roller_properties}.
 */
public class DatabaseInstaller {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInstaller.class);

    /** Tracking table; created by V001, which is therefore self-bootstrapping. */
    static final String TRACKING_TABLE = "schema_migrations";

    /**
     * psql variable used for grants inside migrations. psql expands it from
     * DB_APP_USER; over JDBC we substitute the connection's own user, which is
     * the role Roller connects as and therefore the one needing the grant.
     */
    private static final String APP_USER_VAR = ":app_user";

    private final DatabaseProvider db;
    private final DatabaseScriptProvider scripts;
    private final List<String> messages = new ArrayList<>();

    public DatabaseInstaller(DatabaseProvider dbProvider, DatabaseScriptProvider scriptProvider) {
        this.db = dbProvider;
        this.scripts = scriptProvider;
    }

    public List<String> getMessages() {
        return messages;
    }

    private void errorMessage(String msg, Throwable t) {
        messages.add(msg);
        log.error(msg, t);
    }

    private void successMessage(String msg) {
        messages.add(msg);
        log.trace(msg);
    }

    /**
     * True when the database has no Roller schema at all, i.e. not even the
     * baseline migration has run. Drives the install wizard's "create tables"
     * step.
     */
    public boolean isCreationRequired() {
        try (Connection con = db.getConnection()) {
            if (!tableExists(con, TRACKING_TABLE)) {
                // A database predating this fork's migration chain has Roller
                // tables but no tracking table. There is no upgrade path from
                // upstream, so say so plainly rather than silently re-running
                // the baseline over a populated schema.
                if (tableExists(con, "userrole") || tableExists(con, "roller_user")) {
                    throw new IllegalStateException(
                            "This database has Roller tables but no " + TRACKING_TABLE + " table, "
                            + "so it predates this fork's migration chain. There is no in-place "
                            + "upgrade path from Apache Roller 6.1.x or earlier, nor from any "
                            + "database other than PostgreSQL. Export your content and load it "
                            + "into a fresh database.");
                }
                return true;
            }
            return appliedVersions(con).isEmpty();
        } catch (SQLException e) {
            throw new RuntimeException("Error checking database state", e);
        }
    }

    /** True when migrations exist on the classpath that this database has not applied. */
    public boolean isUpgradeRequired() {
        try (Connection con = db.getConnection()) {
            return !tableExists(con, TRACKING_TABLE) || !pendingMigrations(appliedVersions(con)).isEmpty();
        } catch (SQLException e) {
            throw new RuntimeException("Error checking for pending migrations", e);
        }
    }

    /**
     * Create the schema by applying every migration. Identical in effect to
     * {@link #upgradeDatabase(boolean)} -- with migrations there is no
     * meaningful difference between creating and upgrading, both just apply
     * whatever is pending.
     */
    public void createDatabase() throws StartupException {
        log.info("Creating Roller Weblogger database schema.");
        applyPendingMigrations();
    }

    /**
     * Apply any pending migrations.
     *
     * @param runScripts retained for API compatibility with callers that
     *                   distinguish "manual" from "auto" installation. Schema
     *                   changes now live entirely in SQL migrations, so there
     *                   is no script-free upgrade path and this is ignored.
     */
    public void upgradeDatabase(boolean runScripts) throws StartupException {
        applyPendingMigrations();
    }

    /**
     * Apply each pending migration in version order, recording it in
     * {@code schema_migrations} only after it succeeds. Each migration runs in
     * its own transaction, matching migrate.sh's --single-transaction.
     */
    private void applyPendingMigrations() throws StartupException {
        try (Connection con = db.getConnection()) {

            // V001 creates the tracking table, so it must run before we can
            // query it. Its CREATE TABLE IF NOT EXISTS makes that safe.
            if (!tableExists(con, TRACKING_TABLE)) {
                successMessage("Bootstrapping " + TRACKING_TABLE + " from V001");
            }

            Set<String> applied = tableExists(con, TRACKING_TABLE)
                    ? appliedVersions(con)
                    : new LinkedHashSet<>();

            List<String> pending = pendingMigrations(applied);
            if (pending.isEmpty()) {
                successMessage("Database is up to date (" + applied.size() + " migrations applied)");
                return;
            }

            for (String version : pending) {
                applyMigration(con, version);
            }
            successMessage("Applied " + pending.size() + " migration(s)");

        } catch (SQLException e) {
            errorMessage("ERROR connecting to database to apply migrations", e);
            throw new StartupException("Error applying database migrations", e, messages);
        }
    }

    private void applyMigration(Connection con, String version) throws StartupException {
        SQLScriptRunner runner = null;
        boolean autoCommit = true;
        try {
            String sql = readMigration(version, connectionUser(con));

            autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);

            runner = new SQLScriptRunner(
                    new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
            runner.runScript(con, true);
            messages.addAll(runner.getMessages());

            recordApplied(con, version);
            con.commit();
            successMessage(version + " applied");

        } catch (Exception ex) {
            try {
                con.rollback();
            } catch (SQLException rollbackFailure) {
                log.error("Rollback of {} failed", version, rollbackFailure);
            }
            if (runner != null) {
                messages.addAll(runner.getMessages());
            }
            errorMessage("ERROR applying migration " + version, ex);
            throw new StartupException("Error applying migration " + version, ex, messages);
        } finally {
            try {
                con.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // connection is being discarded anyway
            }
        }
    }

    /** Reads a migration off the classpath and expands the :app_user psql variable. */
    private String readMigration(String version, String appUser) throws StartupException {
        try (var in = scripts.getDatabaseScript(version + ".sql")) {
            if (in == null) {
                throw new StartupException("Migration " + version + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(APP_USER_VAR, appUser);
        } catch (StartupException se) {
            throw se;
        } catch (Exception e) {
            throw new StartupException("Could not read migration " + version, e);
        }
    }

    // package-private rather than private so DatabaseInstallerTest can drive
    // all three branches directly against a mocked Connection, without needing
    // a real database connection just to reach this one regex check.
    @SuppressFBWarnings(
            value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
            justification = "SpotBugs's own JDBC nullness annotations mark "
                    + "DatabaseMetaData.getUserName() non-null, and decompiling the bundled "
                    + "pgjdbc driver (postgresql-42.7.11.jar) confirms PgDatabaseMetaData"
                    + ".getUserName() delegates to PgConnection.getUserName(), the username "
                    + "supplied at connection time -- which PostgreSQL authentication requires "
                    + "to be present. But that javadoc guarantees nothing about null: it is "
                    + "decompiled driver behaviour, not a contract, and a driver upgrade or a "
                    + "DataSource wrapper could reintroduce null. This value is spliced straight "
                    + "into a GRANT statement, so the guard is kept despite the analysis above -- "
                    + "it fails safe with a diagnostic message instead of a bare NPE.")
    String connectionUser(Connection con) throws SQLException {
        String user = con.getMetaData().getUserName();
        // Defend the substitution: an unexpected value here would be spliced
        // straight into a GRANT statement.
        if (user == null || !user.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new SQLException("Refusing to substitute unsafe database user name: " + user);
        }
        return user;
    }

    private void recordApplied(Connection con, String version) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO " + TRACKING_TABLE + " (version) VALUES (?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, version);
            ps.executeUpdate();
        }
    }

    private Set<String> appliedVersions(Connection con) throws SQLException {
        Set<String> applied = new LinkedHashSet<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version FROM " + TRACKING_TABLE + " ORDER BY version")) {
            while (rs.next()) {
                applied.add(rs.getString(1));
            }
        }
        return applied;
    }

    /** Migrations present on the classpath but not yet applied, in version order. */
    private List<String> pendingMigrations(Set<String> applied) {
        List<String> pending = new ArrayList<>();
        for (String version : MigrationCatalog.versions()) {
            if (!applied.contains(version)) {
                pending.add(version);
            }
        }
        return pending;
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares table names from the database's own JDBC metadata with a constant "
                    + "table name; no request input reaches it.")
    private boolean tableExists(Connection con, String tableName) throws SQLException {
        try (ResultSet rs = con.getMetaData().getTables(null, null, "%", null)) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
