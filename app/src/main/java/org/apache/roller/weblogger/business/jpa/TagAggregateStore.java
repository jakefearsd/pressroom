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

import java.sql.Timestamp;
import java.util.Map;
import java.util.TreeMap;

import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * The one writer of {@code roller_weblogentrytagagg}: one row per (weblog,
 * tag) and one site-wide row per tag ({@code websiteid IS NULL}), each holding
 * how many published entries carry the tag.
 *
 * <p>Every change is a single atomic {@code INSERT ... ON CONFLICT DO UPDATE
 * SET total = total + delta} in the caller's transaction. The read-modify-write
 * it replaces (select the row; insert it if absent, else store
 * {@code total + delta}) let two concurrent saves both see no row and both
 * insert -- a {@code weta_weblog_tag_uq} violation, an HTTP 500 -- or both
 * read the same total and lose an increment. The conflict target is that
 * constraint, which V034 made {@code NULLS NOT DISTINCT} so that it guards the
 * site-wide rows too.
 *
 * <p>Rows are locked in tag-name order, weblog row before site-wide row, so
 * two saves touching overlapping tags cannot deadlock on them; a row whose
 * total falls to zero or below is deleted, but only the rows just changed.
 *
 * <p>The SQL bypasses EclipseLink, so {@code WeblogEntryTagAggregate} is not
 * kept in the shared cache ({@code cacheable="false"} in its mapping); every
 * read of the table is a query that reaches the database.
 */
final class TagAggregateStore {

    private static final String UPSERT_WEBLOG_ROW =
            "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
            + "VALUES (?1, ?2, ?3, ?4, ?5) "
            + "ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE "
            + "SET total = roller_weblogentrytagagg.total + EXCLUDED.total, lastused = EXCLUDED.lastused";

    private static final String UPSERT_SITE_ROW =
            "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
            + "VALUES (?1, NULL, ?2, ?3, ?4) "
            + "ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE "
            + "SET total = roller_weblogentrytagagg.total + EXCLUDED.total, lastused = EXCLUDED.lastused";

    private static final String DELETE_SPENT_ROWS =
            "DELETE FROM roller_weblogentrytagagg "
            + "WHERE name = ?1 AND (websiteid = ?2 OR websiteid IS NULL) AND total <= 0";

    private final JPAPersistenceStrategy strategy;

    TagAggregateStore(JPAPersistenceStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Adds each tag's delta to its row for {@code weblog} and to its
     * site-wide row. Zero deltas are skipped.
     *
     * @param weblog the weblog whose entries changed
     * @param deltas tag name to the change in the number of published
     *               entries carrying it
     */
    void apply(Weblog weblog, Map<String, Integer> deltas) throws WebloggerException {
        if (weblog == null) {
            throw new WebloggerException("Website cannot be NULL.");
        }
        Timestamp lastUsed = new Timestamp(System.currentTimeMillis());
        for (Map.Entry<String, Integer> change : new TreeMap<>(deltas).entrySet()) {
            int amount = change.getValue();
            if (amount == 0) {
                continue;
            }
            String name = change.getKey();
            strategy.getNativeUpdate(UPSERT_WEBLOG_ROW)
                    .setParameter(1, UUIDGenerator.generateUUID())
                    .setParameter(2, weblog.getId())
                    .setParameter(3, name)
                    .setParameter(4, amount)
                    .setParameter(5, lastUsed)
                    .executeUpdate();
            strategy.getNativeUpdate(UPSERT_SITE_ROW)
                    .setParameter(1, UUIDGenerator.generateUUID())
                    .setParameter(2, name)
                    .setParameter(3, amount)
                    .setParameter(4, lastUsed)
                    .executeUpdate();
            if (amount < 0) {
                strategy.getNativeUpdate(DELETE_SPENT_ROWS)
                        .setParameter(1, name)
                        .setParameter(2, weblog.getId())
                        .executeUpdate();
            }
        }
    }
}
