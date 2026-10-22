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
package org.apache.roller.weblogger.business.jpa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.roller.weblogger.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.connection;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V036's composite indexes, with their column order: each serves one hot
 * query, and the order is what lets it (the measurements are in the
 * migration's header).
 */
class HotQueryIndexesTest {

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
    }

    /** TagAggregateStore's per-weblog recount: one weblog's rows for one tag. */
    @Test
    void theTagRecountReadsTagRowsByWeblogAndName() throws Exception {
        assertEquals("CREATE INDEX wet_weblog_name_entry_idx ON public.roller_weblogentrytag "
                + "USING btree (websiteid, name, entryid)", indexDef("wet_weblog_name_entry_idx"));
    }

    /** The category pager: newest published entries of one weblog's category. */
    @Test
    void theCategoryPagerReadsEntriesInPubtimeOrder() throws Exception {
        assertEquals("CREATE INDEX we_weblog_cat_status_pub_idx ON public.weblogentry "
                + "USING btree (websiteid, categoryid, status, pubtime)", indexDef("we_weblog_cat_status_pub_idx"));
    }

    private static String indexDef(String name) throws Exception {
        try (Connection con = connection();
             PreparedStatement ps = con.prepareStatement("SELECT indexdef FROM pg_indexes WHERE indexname = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
