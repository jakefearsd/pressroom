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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import com.wikantik.pressroom.business.startup.StartupException;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link DatabaseProvider} resolves Roller's database from either JDBC
 * properties or a JNDI name and connects once up front, so a misconfiguration
 * fails at startup with a message an installer can act on. Characterisation
 * tests: written against the existing behaviour and expected to pass
 * immediately.
 *
 * <p>Configuration is supplied through a scoped {@code mockStatic} of
 * {@link WebloggerConfig}; JNDI through a test {@link InitialContextFactory}
 * named by the {@code java.naming.factory.initial} system property, restored
 * after each test.
 */
class DatabaseProviderTest {

    private static final String FACTORY_PROPERTY = Context.INITIAL_CONTEXT_FACTORY;

    private String previousFactory;

    @BeforeEach
    void installTestNamingFactory() {
        previousFactory = System.getProperty(FACTORY_PROPERTY);
        System.setProperty(FACTORY_PROPERTY, BindingsContextFactory.class.getName());
        BindingsContextFactory.BINDINGS.clear();
    }

    @AfterEach
    void restoreNamingFactory() {
        BindingsContextFactory.BINDINGS.clear();
        if (previousFactory == null) {
            System.clearProperty(FACTORY_PROPERTY);
        } else {
            System.setProperty(FACTORY_PROPERTY, previousFactory);
        }
    }

    @Test
    void jdbcPropertiesConnectAndLogTheSettingsWithThePasswordHidden() throws Exception {
        String url = PressroomPostgresContainer.getJdbcUrl();
        DatabaseProvider provider = providerWith(Map.of(
                "database.configurationType", "jdbc",
                "database.jdbc.driverClass", "org.postgresql.Driver",
                "database.jdbc.connectionURL", url,
                "database.jdbc.username", PressroomPostgresContainer.getUsername(),
                "database.jdbc.password", PressroomPostgresContainer.getPassword()));

        assertEquals(DatabaseProvider.ConfigurationType.JDBC_PROPERTIES, provider.getType());
        assertEquals(List.of(
                "SUCCESS: Got parameters. Using configuration type JDBC_PROPERTIES",
                "-- Using JDBC driver class: org.postgresql.Driver",
                "-- Using JDBC connection URL: " + url,
                "-- Using JDBC username: " + PressroomPostgresContainer.getUsername(),
                "-- Using JDBC password: [hidden]",
                "SUCCESS: loaded JDBC driver class [org.postgresql.Driver]",
                "SUCCESS: obtained a database connection"), provider.getStartupLog());
    }

    @Test
    void aMissingJdbcDriverFailsStartupNamingTheClass() {
        StartupException failure = assertThrows(StartupException.class, () -> providerWith(Map.of(
                "database.configurationType", "jdbc",
                "database.jdbc.driverClass", "com.example.NoSuchDriver",
                "database.jdbc.connectionURL", "jdbc:nosuch://nowhere")));

        String expected = "ERROR: cannot load JDBC driver class [com.example.NoSuchDriver]. "
                + "Likely problem: JDBC driver jar missing from server classpath.";
        assertEquals(expected, failure.getMessage());
        assertInstanceOf(ClassNotFoundException.class, failure.getCause());
        assertEquals(expected, failure.getStartupLog().getLast());
        assertEquals("SUCCESS: Got parameters. Using configuration type JDBC_PROPERTIES",
                failure.getStartupLog().getFirst());
    }

    @Test
    void anUnreachableDatabaseFailsStartupWithTheConnectionError() {
        StartupException failure = assertThrows(StartupException.class, () -> providerWith(Map.of(
                "database.configurationType", "jdbc",
                "database.jdbc.driverClass", "org.postgresql.Driver",
                "database.jdbc.connectionURL", "jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=2",
                "database.jdbc.username", "nobody")));

        String expected = "ERROR: unable to obtain database connection. "
                + "Likely problem: bad connection parameters or database unavailable.";
        assertEquals(expected, failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
        List<String> log = failure.getStartupLog();
        assertEquals("SUCCESS: loaded JDBC driver class [org.postgresql.Driver]", log.get(log.size() - 2));
        assertEquals(expected, log.getLast());
    }

    @Test
    void aRelativeJndiNameIsResolvedUnderCompEnvAndServesItsDataSource() throws Exception {
        Connection connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        BindingsContextFactory.BINDINGS.put("java:comp/env/jdbc/pressroom", dataSource);

        DatabaseProvider provider = providerWith(Map.of("database.jndi.name", "jdbc/pressroom"));

        assertEquals(DatabaseProvider.ConfigurationType.JNDI_NAME, provider.getType(),
                "anything but 'jdbc' means JNDI");
        assertEquals("java:comp/env/jdbc/pressroom", provider.getFullJndiName());
        assertSame(connection, provider.getConnection());
        assertEquals(List.of(
                "SUCCESS: Got parameters. Using configuration type JNDI_NAME",
                "-- Using JNDI datasource name: java:comp/env/jdbc/pressroom",
                "SUCCESS: located JNDI DataSource [java:comp/env/jdbc/pressroom]",
                "SUCCESS: obtained a database connection"), provider.getStartupLog());
    }

    @Test
    void anAbsoluteJndiNameIsLookedUpAsGivenAndAnUnboundOneFailsStartup() {
        StartupException failure = assertThrows(StartupException.class, () -> providerWith(Map.of(
                "database.configurationType", "jndi",
                "database.jndi.name", "java:global/pressroom")));

        String expected = "ERROR: cannot locate JNDI DataSource [java:global/pressroom]. "
                + "Likely problem: no DataSource or datasource is misconfigured.";
        assertEquals(expected, failure.getMessage());
        NamingException cause = assertInstanceOf(NamingException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("java:global/pressroom"), cause.getMessage());
        assertEquals(expected, failure.getStartupLog().getLast());
    }

    private static DatabaseProvider providerWith(Map<String, String> settings) throws StartupException {
        Map<String, String> config = new HashMap<>(settings);
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getProperty(anyString()))
                    .thenAnswer(invocation -> config.get(invocation.<String>getArgument(0)));
            return new DatabaseProvider();
        }
    }

    /** A JNDI context over {@link #BINDINGS}; an unbound name is NameNotFoundException. */
    public static final class BindingsContextFactory implements InitialContextFactory {

        static final Map<String, Object> BINDINGS = new ConcurrentHashMap<>();

        @Override
        public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
            Context context = mock(Context.class);
            when(context.lookup(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                Object bound = BINDINGS.get(name);
                if (bound == null) {
                    throw new NameNotFoundException(name + " is not bound");
                }
                return bound;
            });
            return context;
        }
    }
}
