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
package org.apache.roller.weblogger.ui.controllers.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.roller.weblogger.business.DatabaseProvider;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.business.startup.MigrationCatalog;
import org.apache.roller.weblogger.business.startup.StartupException;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link InstallController}, the wizard that runs before Roller has
 * a usable database.
 *
 * <p>Its whole job is deciding what state the installation is in, and the most
 * important of those decisions is the first one: once the application has been
 * bootstrapped, every install URL must bounce to the site rather than offer to
 * create or upgrade a database that is already in use.
 */
class InstallControllerTest {

    /**
     * A Mockito mock: {@code isBootstrapped()} is false by default, which is
     * the "still installing" state every test but one wants; the one that
     * wants "already running" stubs it true. The controller takes the
     * provider by constructor; there is no JVM-wide static to install a
     * "not bootstrapped" state into any more.
     */
    private WebloggerProvider webloggerProvider;
    private InstallController controller;
    private ExtendedModelMap model;
    private StartupException previousDbException;
    private boolean previousPrepared;
    private Object previousDbProvider;

    /** Every connection the mocked database provider has handed out. */
    private final List<Connection> openedConnections = new ArrayList<>();

    @BeforeEach
    void setUp() {
        webloggerProvider = mock(WebloggerProvider.class);
        controller = ControllerTestFixture.withMessages(new InstallController(webloggerProvider));
        model = new ExtendedModelMap();
        previousDbException = WebloggerStartup.getDatabaseProviderException();
        previousPrepared = WebloggerStartup.isPrepared();
        previousDbProvider = getStartupField("dbProvider");
    }

    @AfterEach
    void tearDown() {
        setStartupField("dbProviderException", previousDbException);
        setStartupField("prepared", previousPrepared);
        setStartupField("dbProvider", previousDbProvider);
    }

    @Test
    void everyInstallUrlBouncesToTheSiteOnceRollerIsRunning() {
        when(webloggerProvider.isBootstrapped()).thenReturn(true);

        assertEquals("redirect:/", controller.execute(request(), model));
        assertEquals("redirect:/", controller.create(request(), model));
        assertEquals("redirect:/", controller.upgrade(request(), model));
        assertEquals("redirect:/", controller.bootstrap(request(), model));
    }

    /**
     * The installer is anonymous, and with installation.type=auto (production)
     * every public request lands on it while the database is unreachable. So
     * the page says only that, and where to look: the JDBC URL, the database
     * user, the exception and its trace stay in the server log, where
     * RollerLifecycle wrote them at the point of failure.
     */
    @Test
    void aDatabaseConnectionFailureShowsTheVisitorNoConnectionDetails() {
        StartupException failure = new StartupException("could not connect",
                new IllegalStateException("connection refused"),
                List.of("-- Using JDBC connection URL: jdbc:postgresql://db.internal:5432/roller",
                        "-- Using JDBC username: roller_app"));
        setStartupField("dbProviderException", failure);

        String view = controller.execute(request(), model);

        assertEquals(".DatabaseError", view);
        assertEquals("installer.error.connection.pageTitle", model.getAttribute("pageTitle"));
        assertNothingDiagnostic(model);
    }

    /**
     * The pages themselves cannot print a startup log, an exception or a trace,
     * whatever a later controller puts in the model; each shows the one
     * message that points at the server log.
     */
    @Test
    void theInstallerErrorPagesPrintNoDiagnostics() throws Exception {
        for (String page : List.of("DatabaseError.jsp", "Bootstrap.jsp")) {
            String jsp = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/webapp/WEB-INF/jsps/core", page));
            for (String forbidden : List.of("${messages}", "rootCause", "StackTrace", "['class']")) {
                assertFalse(jsp.contains(forbidden), page + " prints " + forbidden);
            }
            assertTrue(jsp.contains("installer.detailsInServerLog") || jsp.contains("installer.startFailedExplanation"),
                    page + " points at the server log");
        }
    }

    @Test
    void bootstrappingBeforeTheApplicationIsPreparedFailsOntoTheBootstrapPage() throws Exception {
        // Hitting install!bootstrap.rol out of order must produce the error page
        // rather than an exception escaping to the container.
        setStartupField("prepared", false);
        // What the real provider throws for an unprepared application (the
        // guard itself is pinned in SpringWebloggerProviderTest).
        doThrow(new IllegalStateException("Cannot bootstrap until application has been properly prepared"))
                .when(webloggerProvider).bootstrap();

        String view;
        List<String> logged;
        List<String> errors;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            view = controller.bootstrap(request(), model);
            logged = capture.everything();
            errors = capture.lines(Level.ERROR);
        }

        assertEquals(".Bootstrap", view);
        assertEquals("installer.start.pageTitle", model.getAttribute("pageTitle"));
        assertEquals(Boolean.TRUE, model.getAttribute("startFailed"),
                "the page says the start failed, not that the tables are merely ready");
        assertNothingDiagnostic(model);
        assertTrue(errors.stream().anyMatch(line -> line.contains("bootstrap")),
                "the failure is logged at ERROR: " + errors);
        assertTrue(logged.contains("java.lang.IllegalStateException: "
                        + "Cannot bootstrap until application has been properly prepared"),
                "with its exception, which the page no longer shows: " + logged);
    }

    @Test
    void anEmptyDatabaseIsOfferedTheSchemaCreationPage() throws Exception {
        // Pointing the installer at a database with no tables in it is the
        // ordinary first-run case, and this is the page that gets it going.
        setStartupField("dbProviderException", null);
        installDatabaseProvider(new String[0]);

        String view = controller.execute(request(), model);

        assertEquals(".CreateDatabase", view);
        assertEquals("installer.database.creation.pageTitle", model.getAttribute("pageTitle"));
        assertEquals("PostgreSQL", model.getAttribute("databaseProductName"),
                "the page names the database it is about to write to");
        // There is no connection pool yet at this point, so every connection the
        // installer opens is one it has to hand back itself.
        assertFalse(openedConnections.isEmpty(), "the installer did look at the database");
        for (Connection connection : openedConnections) {
            verify(connection).close();
        }
    }

    @Test
    void aFullyMigratedDatabaseIsSentStraightToBootstrapping() throws Exception {
        // Nothing to create and nothing to upgrade: the installer has no work
        // left, and the remaining step is starting the application.
        setStartupField("dbProviderException", null);
        installDatabaseProvider(new String[]{"schema_migrations", "roller_user"},
                MigrationCatalog.versions().toArray(new String[0]));

        String view = controller.execute(request(), model);

        assertEquals(".Bootstrap", view);
        assertEquals("installer.start.pageTitle", model.getAttribute("pageTitle"),
                "nothing has gone wrong: the page is not titled as an unknown error");
        assertFalse(model.containsAttribute("startFailed"));
        assertNothingDiagnostic(model);
    }

    @Test
    void aDatabaseWithSchemaButPendingMigrationsIsOfferedTheUpgradePage() throws Exception {
        // Tables exist and some migrations have been applied, but not all of
        // them: that is an upgrade, and offering to create the schema instead
        // would be offering to re-run the baseline over live data.
        setStartupField("dbProviderException", null);
        installDatabaseProvider(new String[]{"schema_migrations", "roller_user"}, "V001__baseline");

        String view = controller.execute(request(), model);

        assertEquals(".UpgradeDatabase", view);
        assertEquals("installer.database.upgrade.pageTitle", model.getAttribute("pageTitle"));
        assertEquals(Boolean.TRUE, model.getAttribute("upgradeRequired"));
        assertEquals("PostgreSQL", model.getAttribute("databaseProductName"));
    }

    @Test
    void aSchemaCreationThatFailsStaysOnTheCreationPageWithTheLog() throws Exception {
        // The startup log is the only diagnosis an administrator gets when the
        // installer cannot write to the database.
        installUnreachableDatabase();

        String view = controller.create(request(), model);

        assertEquals(".CreateDatabase", view);
        assertEquals(Boolean.TRUE, model.getAttribute("error"));
        assertNull(model.getAttribute("success"));
        assertFalse(((List<?>) model.getAttribute("messages")).isEmpty(),
                "the failure has to come back with something to read");
        assertFalse(model.getAttribute("messages").toString().contains("connection refused"),
                "but not the exception, which is in the log: " + model.getAttribute("messages"));
        assertEquals("installer.database.creation.pageTitle", model.getAttribute("pageTitle"));
    }

    @Test
    void aFailureClosingTheProbeConnectionStillReturnsTheProductNameItAlreadyRead() throws Exception {
        // getDatabaseProductName()'s finally block best-effort-closes the probe
        // connection; a driver that fails to close it must not stop the name
        // it already read from reaching the page. Called directly via
        // reflection -- it is a private helper -- so this is isolated from the
        // rest of the install flow's own database-state mocking.
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");

        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        org.mockito.Mockito.doThrow(new java.sql.SQLException("connection already closed"))
                .when(connection).close();

        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenReturn(connection);
        setStartupField("dbProvider", provider);

        Method method = InstallController.class.getDeclaredMethod("getDatabaseProductName");
        method.setAccessible(true);
        Object name = method.invoke(controller);

        assertEquals("PostgreSQL", name);
        verify(connection).close();
    }

    @Test
    void anUpgradeThatFailsStaysOnTheUpgradePageWithTheLog() throws Exception {
        installUnreachableDatabase();

        String view = controller.upgrade(request(), model);

        assertEquals(".UpgradeDatabase", view);
        assertEquals(Boolean.TRUE, model.getAttribute("error"));
        assertNull(model.getAttribute("success"));
        assertEquals("installer.database.upgrade.pageTitle", model.getAttribute("pageTitle"));
    }

    @Test
    void theInstallerIsReachableWithNoUserAndNoWeblog() {
        // There are no accounts yet when this wizard runs.
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
    }

    @Test
    void propertiesComeFromTheStaticConfigurationOnlyWhileInstalling() {
        // The database is exactly what is not available here, so this override
        // must not fall through to the runtime (database-backed) properties.
        assertEquals("A-Za-z0-9", controller.getProp("username.allowedChars"));
    }

    @Test
    void anUnknownPropertyComesBackAsItsOwnName() {
        // Which is what the installer JSPs render, rather than "null".
        assertEquals("no.such.property.exists", controller.getProp("no.such.property.exists"));
    }

    /** Nothing in the model a visitor could learn the deployment from. */
    private static void assertNothingDiagnostic(ExtendedModelMap model) {
        for (String name : List.of("rootCauseException", "rootCauseStackTrace", "messages")) {
            assertFalse(model.containsAttribute(name), name + " must not reach the page");
        }
        String everything = model.toString();
        for (String detail : List.of("jdbc:", "roller_app", "Exception", "connection refused", "\tat ")) {
            assertFalse(everything.contains(detail), "the model carries " + detail + ": " + everything);
        }
    }

    private static jakarta.servlet.http.HttpServletRequest request() {
        return ControllerTestFixture.requestFor(null);
    }

    // ---- the success and bootstrap paths

    @Test
    void aSchemaCreationThatSucceedsShowsItsLogAsSuccess() {
        try (org.mockito.MockedStatic<WebloggerStartup> startup = org.mockito.Mockito.mockStatic(WebloggerStartup.class)) {
            startup.when(WebloggerStartup::createDatabase).thenReturn(List.of("created table a"));

            String view = controller.create(request(), model);

            assertEquals(".CreateDatabase", view);
            assertEquals(Boolean.TRUE, model.getAttribute("success"));
            assertNull(model.getAttribute("error"));
            assertEquals(List.of("created table a"), model.getAttribute("messages"));
            assertEquals("unknown", model.getAttribute("databaseProductName"),
                    "no reachable provider: the probe falls back rather than failing the page");
        }
    }

    @Test
    void anUpgradeThatSucceedsShowsItsLogAsSuccess() {
        try (org.mockito.MockedStatic<WebloggerStartup> startup = org.mockito.Mockito.mockStatic(WebloggerStartup.class)) {
            startup.when(() -> WebloggerStartup.upgradeDatabase(true)).thenReturn(List.of("applied V030"));

            String view = controller.upgrade(request(), model);

            assertEquals(".UpgradeDatabase", view);
            assertEquals(Boolean.TRUE, model.getAttribute("success"));
            assertEquals(List.of("applied V030"), model.getAttribute("messages"));
        }
    }

    @Test
    void bootstrappingThatSucceedsGoesToTheSite() throws Exception {
        assertEquals("redirect:/", controller.bootstrap(request(), model));

        verify(webloggerProvider).bootstrap();
    }

    @Test
    void bootstrapOnAnAlreadyRunningSiteDoesNotBootstrapAgain() throws Exception {
        when(webloggerProvider.isBootstrapped()).thenReturn(true);

        assertEquals("redirect:/", controller.bootstrap(request(), model));

        org.mockito.Mockito.verify(webloggerProvider, org.mockito.Mockito.never()).bootstrap();
    }

    /**
     * Whatever kind of failure bootstrapping reports, the anonymous page names the problem
     * and nothing else: no exception, trace or class name in the model. The failure is
     * logged with its Throwable instead.
     */
    @Test
    void everyKindOfBootstrapFailureLandsOnTheBootstrapPageAndIsLoggedNotShown() throws Exception {
        Throwable[] failures = {
            new org.springframework.beans.FatalBeanException("bean wiring"),
            new org.apache.roller.weblogger.business.BootstrapException("tier"),
            new IllegalStateException("anything else")
        };
        for (Throwable failure : failures) {
            ExtendedModelMap each = new ExtendedModelMap();
            doThrow(failure).when(webloggerProvider).bootstrap();

            String view;
            List<String> logged;
            try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
                view = controller.bootstrap(request(), each);
                logged = capture.everything();
            }

            assertEquals(".Bootstrap", view, failure.toString());
            assertEquals("installer.start.pageTitle", each.getAttribute("pageTitle"));
            assertEquals(Boolean.TRUE, each.getAttribute("startFailed"));
            assertFalse(logged.stream().anyMatch(line -> line.contains("ENTERING") || line.contains("EXITING")),
                    "no leftover method-trace lines: " + logged);
            assertNothingDiagnostic(each);
            assertTrue(logged.contains(failure.getClass().getName() + ": " + failure.getMessage()),
                    "logged with its exception: " + logged);
        }
    }

    /**
     * Points {@code WebloggerStartup} at a database that reports the given
     * tables and applied migration versions.
     *
     * <p>The installer inspects the database through JDBC metadata, so this is
     * the smallest thing that can stand in for "a database in state X" without
     * one being running.
     */
    private void installDatabaseProvider(String[] tables, String... appliedVersions) throws Exception {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        // A fresh result set per call: the installer asks more than once and
        // each ask has to start from the beginning of the table list.
        when(metaData.getTables(isNull(), isNull(), eq("%"), isNull()))
                .thenAnswer(invocation -> tableListResultSet(tables));

        Statement statement = mock(Statement.class);
        when(statement.executeQuery(any())).thenAnswer(invocation -> versionResultSet(appliedVersions));

        // A distinct connection per call, all of them remembered, so a test can
        // insist that every one was handed back.
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenAnswer(invocation -> {
            Connection connection = mock(Connection.class);
            when(connection.getMetaData()).thenReturn(metaData);
            when(connection.createStatement()).thenReturn(statement);
            openedConnections.add(connection);
            return connection;
        });
        setStartupField("dbProvider", provider);
    }

    /** A database provider whose connections never open. */
    private void installUnreachableDatabase() throws Exception {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenThrow(new java.sql.SQLException("connection refused"));
        setStartupField("dbProvider", provider);
    }

    private static ResultSet tableListResultSet(String[] tables) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        stubRows(rs, tables.length);
        when(rs.getString("TABLE_NAME")).thenAnswer(nextValueOf(tables));
        return rs;
    }

    private static ResultSet versionResultSet(String[] versions) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        stubRows(rs, versions.length);
        when(rs.getString(1)).thenAnswer(nextValueOf(versions));
        return rs;
    }

    /** Makes {@code next()} return true the given number of times, then false. */
    private static void stubRows(ResultSet rs, int rows) throws Exception {
        AtomicInteger remaining = new AtomicInteger(rows);
        when(rs.next()).thenAnswer(invocation -> remaining.getAndDecrement() > 0);
    }

    private static Answer<String> nextValueOf(String[] values) {
        AtomicInteger index = new AtomicInteger();
        return invocation -> values[index.getAndIncrement()];
    }

    /**
     * {@code WebloggerStartup} keeps the installation state in private statics
     * with no setters; a test that wants a particular state has to write them.
     */
    private static void setStartupField(String name, Object value) {
        try {
            startupField(name).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set WebloggerStartup." + name, e);
        }
    }

    private static Object getStartupField(String name) {
        try {
            return startupField(name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read WebloggerStartup." + name, e);
        }
    }

    private static Field startupField(String name) {
        try {
            Field field = WebloggerStartup.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "WebloggerStartup no longer has a '" + name + "' field. Set the installation "
                            + "state the way it is now expressed.", e);
        }
    }
}
