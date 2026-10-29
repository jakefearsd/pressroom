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

import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Grafana contract shipped by {@code V017__analytics_contract.sql}
 * and widened by {@code V027__weblog_custom_domain.sql}: the {@code
 * grafana_ro} role can read exactly the two contract views and nothing else,
 * {@code analytics_events} rolls up {@code roller_event} rows correctly by
 * weblog handle, event type and day, and {@code analytics_weblog_sites}
 * carries {@code custom_domain} -- including for a weblog that has a
 * hostname but no Umami id yet, which is exactly the {@code OR} in that
 * view's {@code WHERE} clause exists to cover (see the class-level "Task 9
 * fix round 1" notes in the wave's task report for why this needed its own
 * test: every other test here inserts weblogs with {@code analytics_site_id}
 * set, so nothing previously exercised the {@code custom_domain}-only path
 * the {@code OR} adds).
 *
 * <p>These are PIN tests, not defect tests -- on a healthy HEAD both must
 * pass. A failure here means the contract itself regressed (see the "Grafana
 * analytics contract" section of CLAUDE.md), not that the test is wrong.
 *
 * <p>Follows the {@code freshDatabase} shape from {@link SchemaMigrationTest}:
 * a scratch database on the shared test container, the real migration chain
 * applied verbatim.
 */
public class AnalyticsContractTest {

    @Test
    public void grafanaRoCanReadTheContractViewsAndNothingElse() throws Exception {
        String dbName = "analyticscontracttest_grafana_privileges";
        try (Connection con = freshDatabase(dbName)) {
            assertTrue(canSelect(con, "analytics_events"),
                    "grafana_ro must be able to SELECT analytics_events");
            assertTrue(canSelect(con, "analytics_weblog_sites"),
                    "grafana_ro must be able to SELECT analytics_weblog_sites");

            assertEquals(Set.of("analytics_events", "analytics_weblog_sites"),
                    grantedTableNames(con, "grafana_ro"),
                    "grafana_ro must hold a privilege on exactly the two contract views and "
                            + "nothing else -- not roller_event, weblog, roller_form_submission, "
                            + "roller_user_token, or anything else in the schema");
        } finally {
            dropDatabase(dbName);
        }
    }

    @Test
    public void analyticsEventsRollsUpByHandleTypeAndDay() throws Exception {
        String dbName = "analyticscontracttest_events_rollup";
        try (Connection con = freshDatabase(dbName)) {
            String weblogId = UUID.randomUUID().toString();
            String handle = "handle-" + UUID.randomUUID().toString().substring(0, 8);
            insertWeblog(con, weblogId, handle);

            Instant today = Instant.now();
            Instant yesterday = today.minus(1, ChronoUnit.DAYS);

            // Two rows same type/day, one row a different day -- same type.
            insertEvent(con, weblogId, "FORM_SUBMITTED", today);
            insertEvent(con, weblogId, "FORM_SUBMITTED", today);
            insertEvent(con, weblogId, "FORM_SUBMITTED", yesterday);

            assertEquals(2, eventCount(con, handle, "FORM_SUBMITTED", today),
                    "today's rollup should count the two same-day events");
            assertEquals(1, eventCount(con, handle, "FORM_SUBMITTED", yesterday),
                    "yesterday's rollup should count only the one event on that day");
        } finally {
            dropDatabase(dbName);
        }
    }

    /**
     * A weblog with a hostname but no Umami id yet is exactly the state a
     * weblog is in the moment it is given a custom domain -- precisely when
     * the SEO tooling needs to find it via {@code analytics_weblog_sites}.
     * The view's {@code WHERE analytics_site_id IS NOT NULL OR custom_domain
     * IS NOT NULL} is what keeps this row visible; a regression back to a
     * plain {@code AND}-style single condition on {@code analytics_site_id}
     * would drop it silently.
     */
    @Test
    public void analyticsWeblogSitesIncludesAWeblogWithOnlyACustomDomain() throws Exception {
        String dbName = "analyticscontracttest_domain_only";
        try (Connection con = freshDatabase(dbName)) {
            String weblogId = UUID.randomUUID().toString();
            String handle = "handle-" + UUID.randomUUID().toString().substring(0, 8);
            String customDomain = "vhost-" + handle + ".example.com";
            insertWeblog(con, weblogId, handle, null, customDomain);

            AnalyticsSite site = analyticsSite(con, handle);
            assertNotNull(site, "a weblog with only a custom_domain (no analytics_site_id) must "
                    + "still be selectable from analytics_weblog_sites");
            assertEquals(customDomain, site.customDomain());
            assertNull(site.websiteId(), "no analytics_site_id was set for this weblog");
        } finally {
            dropDatabase(dbName);
        }
    }

    /**
     * The other half of the same coverage gap: custom_domain must round-trip
     * through the view even when analytics_site_id is ALSO set, so a future
     * change to the SELECT list (not just the WHERE clause) is caught too.
     */
    @Test
    public void analyticsWeblogSitesRoundTripsCustomDomainAlongsideTheAnalyticsSiteId() throws Exception {
        String dbName = "analyticscontracttest_domain_and_site";
        try (Connection con = freshDatabase(dbName)) {
            String weblogId = UUID.randomUUID().toString();
            String handle = "handle-" + UUID.randomUUID().toString().substring(0, 8);
            String analyticsSiteId = UUID.randomUUID().toString();
            String customDomain = "vhost-" + handle + ".example.com";
            insertWeblog(con, weblogId, handle, analyticsSiteId, customDomain);

            AnalyticsSite site = analyticsSite(con, handle);
            assertNotNull(site, "a weblog with both fields set must still be selectable");
            assertEquals(analyticsSiteId, site.websiteId());
            assertEquals(customDomain, site.customDomain());
        } finally {
            dropDatabase(dbName);
        }
    }

    private boolean canSelect(Connection con, String tableName) throws Exception {
        try (var ps = con.prepareStatement(
                "SELECT has_table_privilege('grafana_ro', 'public.' || ?, 'SELECT')")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /**
     * The complete set of tables/views {@code grantee} holds ANY privilege on,
     * read from {@code information_schema.role_table_grants} rather than
     * probing a handful of named tables -- so a future grant this test's
     * author never thought to name still fails it.
     */
    private Set<String> grantedTableNames(Connection con, String grantee) throws Exception {
        Set<String> names = new HashSet<>();
        try (var ps = con.prepareStatement(
                "SELECT DISTINCT table_name FROM information_schema.role_table_grants "
                        + "WHERE grantee = ? AND table_schema = 'public'")) {
            ps.setString(1, grantee);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private void insertWeblog(Connection con, String id, String handle) throws Exception {
        insertWeblog(con, id, handle, null, null);
    }

    private void insertWeblog(Connection con, String id, String handle,
            String analyticsSiteId, String customDomain) throws Exception {
        try (var ps = con.prepareStatement("""
                INSERT INTO weblog (id, name, handle, emailaddress, datecreated,
                                     analytics_site_id, custom_domain)
                VALUES (?, ?, ?, ?, now(), ?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, "Test Weblog " + handle);
            ps.setString(3, handle);
            ps.setString(4, "test@example.com");
            ps.setString(5, analyticsSiteId);
            ps.setString(6, customDomain);
            ps.executeUpdate();
        }
    }

    /** One row of {@code analytics_weblog_sites}, or null when the handle has none. */
    private record AnalyticsSite(String websiteId, String customDomain) {
    }

    private AnalyticsSite analyticsSite(Connection con, String handle) throws Exception {
        try (var ps = con.prepareStatement("""
                SELECT website_id, custom_domain FROM analytics_weblog_sites
                WHERE weblog_handle = ?
                """)) {
            ps.setString(1, handle);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AnalyticsSite(rs.getString("website_id"), rs.getString("custom_domain"));
            }
        }
    }

    private void insertEvent(Connection con, String weblogId, String eventType, Instant occurredAt)
            throws Exception {
        try (var ps = con.prepareStatement("""
                INSERT INTO roller_event (id, weblogid, event_type, occurred_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, weblogId);
            ps.setString(3, eventType);
            ps.setTimestamp(4, Timestamp.from(occurredAt));
            ps.executeUpdate();
        }
    }

    private long eventCount(Connection con, String handle, String eventType, Instant day) throws Exception {
        try (var ps = con.prepareStatement("""
                SELECT events FROM analytics_events
                WHERE weblog_handle = ? AND event_type = ? AND day = CAST(? AS date)
                """)) {
            ps.setString(1, handle);
            ps.setString(2, eventType);
            ps.setTimestamp(3, Timestamp.from(day));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getLong("events");
            }
        }
    }

    /** Applies the full chain to a brand-new database (dropping any leftover of the same name first) and returns a connection to it. */
    private Connection freshDatabase(String dbName) throws Exception {
        return ScratchDatabase.migrated(dbName);
    }

    /** Drops the scratch database. Callers must close their connection to it first. */
    private void dropDatabase(String dbName) throws Exception {
        ScratchDatabase.drop(dbName);
    }
}
