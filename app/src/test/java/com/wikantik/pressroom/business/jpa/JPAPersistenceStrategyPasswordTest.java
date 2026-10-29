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
package com.wikantik.pressroom.business.jpa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import com.wikantik.pressroom.testing.PressroomDatabaseExtension;
import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import com.wikantik.pressroom.business.DatabaseProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * The database password reaches EclipseLink exactly as the operator wrote it.
 *
 * <p>EclipseLink runs {@code jakarta.persistence.jdbc.password} through its
 * login encryptor before using it, and the default one treats any value that
 * parses as hex as an EclipseLink-encrypted password: an even-length all-hex
 * plaintext (what {@code openssl rand -hex 24} prints) failed AES-GCM
 * decryption and the factory refused to deploy with EclipseLink-7360
 * ("Database password was encrypted by deprecated algorithm"). The 0.1.10
 * production deploy crash-looped on exactly that. This drives Roller's own
 * bootstrap ({@link DatabaseProvider} into {@link JPAPersistenceStrategy})
 * against a real PostgreSQL role whose password is such a string.
 */
class JPAPersistenceStrategyPasswordTest {

    private static final String ROLE = "pressroom_hex_password";

    /** 48 hex characters, the shape {@code openssl rand -hex 24} produces. */
    private static final String HEX_PASSWORD = "9f3c0b7e1a64d2c85e0f7b3a96d14c2e8b5a0f6d37c19e42";

    @BeforeEach
    void createRoleWithHexPassword() throws SQLException {
        PressroomDatabaseExtension.ensureSchema();
        asSuperuser("DROP ROLE IF EXISTS " + ROLE,
                "CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + HEX_PASSWORD + "'");
    }

    @AfterEach
    void dropRole() throws SQLException {
        asSuperuser("DROP ROLE IF EXISTS " + ROLE);
    }

    @Test
    void anEvenLengthHexPasswordIsUsedAsPlaintextAndTheFactoryConnects() throws Exception {
        DatabaseProvider provider = jdbcProviderFor(ROLE, HEX_PASSWORD);

        JPAPersistenceStrategy strategy = new JPAPersistenceStrategy(provider);
        try {
            Object user = strategy.getEntityManager(false)
                    .createNativeQuery("SELECT CAST(current_user AS varchar)")
                    .getSingleResult();
            assertEquals(ROLE, user, "the factory logged in as the hex-password role");
        } finally {
            strategy.release();
            strategy.shutdown();
        }
    }

    /**
     * A real {@link DatabaseProvider}: its constructor connects over plain
     * JDBC with the same credentials, so a pass there and a failure in the
     * factory isolates the fault to how the password is handed to JPA.
     */
    private static DatabaseProvider jdbcProviderFor(String user, String password) throws Exception {
        Map<String, String> config = Map.of(
                "database.configurationType", "jdbc",
                "database.jdbc.driverClass", "org.postgresql.Driver",
                "database.jdbc.connectionURL", PressroomPostgresContainer.getJdbcUrl(),
                "database.jdbc.username", user,
                "database.jdbc.password", password);
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getProperty(anyString()))
                    .thenAnswer(invocation -> config.get(invocation.<String>getArgument(0)));
            return new DatabaseProvider();
        }
    }

    private static void asSuperuser(String... statements) throws SQLException {
        try (Connection con = DriverManager.getConnection(PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
             Statement st = con.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }
}
