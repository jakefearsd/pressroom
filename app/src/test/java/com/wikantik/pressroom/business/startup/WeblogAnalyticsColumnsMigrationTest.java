package com.wikantik.pressroom.business.startup;

import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V044 drops the two hand-entered analytics columns from {@code weblog}. Blogs
 * are registered with Umami automatically, so nothing reads them any more.
 */
class WeblogAnalyticsColumnsMigrationTest {

    private static final String COLUMNS = "SELECT count(*) FROM information_schema.columns "
            + "WHERE table_name = 'weblog' AND column_name IN ('analytics_site_id', 'analytics_share_url')";

    @Test
    void columnsAreGoneAfterMigration() throws Exception {
        String db = "migrationtest_weblog_analytics_columns";
        try (Connection con = ScratchDatabase.migratedThrough(db, 43)) {
            assertEquals(2, count(con), "V043 and earlier still have both columns");
            ScratchDatabase.apply(con, ScratchDatabase.migration(44));
            assertEquals(0, count(con));
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        String db = "migrationtest_weblog_analytics_columns_idem";
        try (Connection con = ScratchDatabase.migrated(db)) {
            ScratchDatabase.apply(con, ScratchDatabase.migration(44));
            assertEquals(0, count(con));
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void theGrafanaViewStillWorksWithoutTheColumns() throws Exception {
        String db = "migrationtest_weblog_analytics_columns_view";
        try (Connection con = ScratchDatabase.migrated(db)) {
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM analytics_weblog_sites")) {
                assertTrue(rs.next());
            }
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    private static int count(Connection con) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(COLUMNS)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
