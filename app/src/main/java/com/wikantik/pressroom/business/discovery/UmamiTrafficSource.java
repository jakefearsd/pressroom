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
import com.wikantik.pressroom.config.WebloggerConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Reads the {@code analytics_traffic} view (see
 * {@code deploy/analytics/umami-views.sql}) in Umami's database over a
 * short-lived read-only JDBC connection. Both the connect and the query are
 * time-boxed so a slow or hung Umami can delay only a background refresh.
 */
public final class UmamiTrafficSource implements TrafficSource {

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    private static final String SQL =
            "SELECT CAST(website_id AS text), entry_anchor, SUM(views) "
            + "FROM analytics_traffic WHERE day >= ? AND entry_anchor IS NOT NULL GROUP BY 1, 2";

    private final String url;
    private final String username;
    private final String password;

    public UmamiTrafficSource(String url, String username, String password) {
        this.url = url == null ? "" : url.trim();
        this.username = username;
        this.password = password;
    }

    /** A source built from the {@code analytics.jdbc.*} keys; blank URL means unconfigured. */
    public static TrafficSource fromConfig() {
        return new UmamiTrafficSource(
                WebloggerConfig.getProperty("analytics.jdbc.connectionURL"),
                WebloggerConfig.getProperty("analytics.jdbc.username"),
                WebloggerConfig.getProperty("analytics.jdbc.password"));
    }

    @Override
    public boolean isConfigured() {
        return !url.isEmpty();
    }

    @Override
    public List<TrafficRow> viewsSince(LocalDate firstDay) throws WebloggerException {
        if (!isConfigured()) {
            return List.of();
        }
        try (Connection con = DriverManager.getConnection(url, connectionProperties());
             PreparedStatement ps = con.prepareStatement(SQL)) {
            con.setReadOnly(true);
            ps.setQueryTimeout(queryTimeoutSeconds());
            ps.setDate(1, Date.valueOf(firstDay));
            List<TrafficRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TrafficRow(rs.getString(1), rs.getString(2), rs.getLong(3)));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new WebloggerException("Could not read traffic from Umami", e);
        }
    }

    int queryTimeoutSeconds() {
        return QUERY_TIMEOUT_SECONDS;
    }

    Properties connectionProperties() {
        Properties p = new Properties();
        if (username != null && !username.isEmpty()) {
            p.setProperty("user", username);
        }
        if (password != null && !password.isEmpty()) {
            p.setProperty("password", password);
        }
        p.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        return p;
    }
}
