package com.wikantik.pressroom.business.startup;

import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V043 creates the local analytics cache tables and re-points the Grafana
 * view {@code analytics_weblog_sites} at them.
 */
class AnalyticsCacheMigrationTest {

    private static final String VIEW_COLUMNS = "SELECT column_name, data_type, character_maximum_length "
            + "FROM information_schema.columns WHERE table_name = 'analytics_weblog_sites' "
            + "ORDER BY ordinal_position";

    @Test
    void migrationIsIdempotent() throws Exception {
        String db = "migrationtest_analytics_cache_idem";
        try (Connection con = ScratchDatabase.migrated(db)) {
            ScratchDatabase.apply(con, ScratchDatabase.migration(43));
            assertEquals(1, count(con, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'analytics_site'"));
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void weblogSitesViewKeepsItsColumns() throws Exception {
        String db = "migrationtest_analytics_cache_view";
        try (Connection con = ScratchDatabase.migratedThrough(db, 42)) {
            List<String> before = rows(con, VIEW_COLUMNS);
            assertEquals(3, before.size());
            ScratchDatabase.apply(con, ScratchDatabase.migration(43));
            assertEquals(before, rows(con, VIEW_COLUMNS), "names, order, types and lengths are unchanged");
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void weblogSitesViewReadsTheCacheTable() throws Exception {
        String db = "migrationtest_analytics_cache_read";
        try (Connection con = ScratchDatabase.migrated(db)) {
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES ('w1','w1','h1','e@example.com',now())");
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated, custom_domain) "
                    + "VALUES ('w2','w2','h2','e@example.com',now(),'two.example.com')");
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES ('w3','w3','h3','e@example.com',now())");
            exec(con, "INSERT INTO analytics_site (weblog_id, website_id) VALUES ('w1', 'site-1')");
            assertEquals(List.of("h1|site-1|null", "h2|null|two.example.com"),
                    rows(con, "SELECT weblog_handle, website_id, custom_domain FROM analytics_weblog_sites ORDER BY 1"));
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void deletingAWeblogCascadesItsAnalyticsRows() throws Exception {
        String db = "migrationtest_analytics_cache_cascade";
        try (Connection con = ScratchDatabase.migrated(db)) {
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES ('w1','w1','h1','e@example.com',now())");
            exec(con, "INSERT INTO analytics_site (weblog_id, website_id) VALUES ('w1', 's')");
            exec(con, "INSERT INTO analytics_summary VALUES ('w1','7d',now(),1,2,3,4,5,6,7,8,9,10)");
            exec(con, "INSERT INTO analytics_path_views VALUES ('w1','7d','/a',NULL,3,2)");
            exec(con, "INSERT INTO analytics_breakdown VALUES ('w1','7d','referrer','[]')");
            exec(con, "DELETE FROM weblog WHERE id = 'w1'");
            for (String table : List.of("analytics_site", "analytics_summary", "analytics_path_views", "analytics_breakdown")) {
                assertEquals(0, count(con, "SELECT count(*) FROM " + table), table);
            }
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    private static List<String> rows(Connection con, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= cols; i++) {
                    sb.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static int count(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static void exec(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }
}
