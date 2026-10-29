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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import com.wikantik.pressroom.pojos.Weblog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reads {@code pressroom_weblogentrytagagg} and the truth it should hold straight
 * from the database, over a connection of its own, so what a test sees is
 * what was committed.
 */
final class TagAggregateRows {

    private TagAggregateRows() {
    }

    /**
     * Exactly one row per weblog and one site-wide row (or none, at zero), each
     * holding the number of PUBLISHED entries that carry the tag.
     */
    static void assertAggregateIsTrue(String tag, Weblog... weblogs) throws SQLException {
        for (Weblog w : weblogs) {
            int truth = trueCount(w.getId(), tag);
            assertEquals(truth == 0 ? List.of() : List.of(truth), storedTotals(w.getId(), tag),
                    "weblog " + w.getHandle() + " / " + tag);
        }
        int siteTruth = trueCount(null, tag);
        assertEquals(siteTruth == 0 ? List.of() : List.of(siteTruth), storedTotals(null, tag),
                "site-wide / " + tag);
    }

    /** The weblog's stored total for the tag; 0 when there is no row. */
    static int weblogTotal(String weblogId, String tag) throws SQLException {
        return singleOrZero(storedTotals(weblogId, tag), tag);
    }

    /** The site-wide stored total for the tag; 0 when there is no row. */
    static int siteTotal(String tag) throws SQLException {
        return singleOrZero(storedTotals(null, tag), tag);
    }

    private static int singleOrZero(List<Integer> totals, String tag) {
        if (totals.size() > 1) {
            fail("more than one aggregate row for " + tag + ": " + totals);
        }
        return totals.isEmpty() ? 0 : totals.get(0);
    }

    /** The stored totals for the tag: one per row, so a duplicate shows. */
    static List<Integer> storedTotals(String weblogId, String tag) throws SQLException {
        String sql = weblogId == null
                ? "SELECT total FROM pressroom_weblogentrytagagg WHERE websiteid IS NULL AND name = ?"
                : "SELECT total FROM pressroom_weblogentrytagagg WHERE websiteid = ? AND name = ?";
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            if (weblogId != null) {
                ps.setString(i++, weblogId);
            }
            ps.setString(i, tag);
            List<Integer> totals = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totals.add(rs.getInt(1));
                }
            }
            return totals;
        }
    }

    /** PUBLISHED entries carrying the tag, on one weblog or (null) site-wide. */
    static int trueCount(String weblogId, String tag) throws SQLException {
        String sql = "SELECT count(DISTINCT t.entryid) FROM pressroom_weblogentrytag t "
                + "JOIN weblogentry e ON e.id = t.entryid "
                + "WHERE e.status = 'PUBLISHED' AND t.name = ?"
                + (weblogId == null ? "" : " AND e.websiteid = ?");
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, tag);
            if (weblogId != null) {
                ps.setString(2, weblogId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static Connection connection() throws SQLException {
        return DriverManager.getConnection(PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }
}
