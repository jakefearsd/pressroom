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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import com.wikantik.pressroom.testing.UmamiTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads entry views from a scratch Umami database carrying the real view. */
public class UmamiTrafficSourceTest {

    private static final String DB = "umami_traffic_source_test";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static Connection con;

    @BeforeAll
    static void createDatabase() throws Exception {
        con = UmamiTestDatabase.create(DB);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        con.close();
        UmamiTestDatabase.drop(DB);
    }

    private static UmamiTrafficSource source() {
        return new UmamiTrafficSource(UmamiTestDatabase.jdbcUrlFor(DB),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }

    private static void view(UUID site, LocalDate day, String path) throws Exception {
        UmamiTestDatabase.insertPageview(con, site, UUID.randomUUID(),
                day.atTime(12, 0).toInstant(ZoneOffset.UTC), path);
    }

    @Test
    public void sumsViewsPerEntryInWindow() throws Exception {
        UUID site = UUID.randomUUID();
        view(site, TODAY, "/weblog/entry/summed");
        view(site, TODAY.minusDays(1), "/weblog/entry/summed");
        view(site, TODAY.minusDays(1), "/weblog/entry/summed");

        List<TrafficRow> rows = source().viewsSince(TODAY.minusDays(5)).stream()
                .filter(r -> r.websiteId().equals(site.toString())).toList();

        assertEquals(List.of(new TrafficRow(site.toString(), "summed", 3L)), rows);
    }

    @Test
    public void excludesDaysBeforeWindow() throws Exception {
        UUID site = UUID.randomUUID();
        view(site, TODAY.minusDays(40), "/weblog/entry/old-post");

        List<TrafficRow> rows = source().viewsSince(TODAY.minusDays(30)).stream()
                .filter(r -> r.websiteId().equals(site.toString())).toList();

        assertTrue(rows.isEmpty());
    }

    @Test
    public void ignoresNonEntryPaths() throws Exception {
        UUID site = UUID.randomUUID();
        view(site, TODAY, "/weblog/");

        List<TrafficRow> rows = source().viewsSince(TODAY.minusDays(5)).stream()
                .filter(r -> r.websiteId().equals(site.toString())).toList();

        assertTrue(rows.isEmpty());
    }

    @Test
    public void unconfiguredReturnsNothing() throws Exception {
        UmamiTrafficSource blank = new UmamiTrafficSource("  ", "u", "p");
        assertFalse(blank.isConfigured());
        assertTrue(blank.viewsSince(TODAY).isEmpty());
        assertFalse(new UmamiTrafficSource(null, null, null).isConfigured());
        assertTrue(source().isConfigured());
    }

    @Test
    public void fromConfigWithNoUrlIsUnconfigured() throws Exception {
        TrafficSource fromConfig = UmamiTrafficSource.fromConfig();
        assertFalse(fromConfig.isConfigured());
        assertTrue(fromConfig.viewsSince(TODAY).isEmpty());
    }

    @Test
    public void unreachableThrowsWebloggerException() {
        UmamiTrafficSource dead = new UmamiTrafficSource(
                "jdbc:postgresql://127.0.0.1:1/umami", "u", "p");
        WebloggerException e = assertThrows(WebloggerException.class, () -> dead.viewsSince(TODAY));
        assertTrue(e.getCause() instanceof java.sql.SQLException);
    }

    @Test
    public void queryTimeoutIsSet() {
        UmamiTrafficSource s = source();
        assertEquals(15, s.queryTimeoutSeconds());
        assertEquals("5", s.connectionProperties().getProperty("connectTimeout"));
    }
}
