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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * The one writer of {@code roller_weblogentrytagagg}: one row per (weblog,
 * tag) and one site-wide row per tag ({@code websiteid IS NULL}), each holding
 * how many PUBLISHED entries carry the tag.
 *
 * <p>Callers say which tags a change may have touched; nothing is computed
 * from what the caller believes the entry looked like before. Just before the
 * transaction commits, each touched row is locked ({@code INSERT ... ON
 * CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE}), then set by a
 * separate statement to the count of published entries carrying the tag. In
 * READ COMMITTED that second statement takes a fresh snapshot, so it sees
 * whatever a transaction that held the lock before it committed; and the
 * transaction's own writes were flushed by the first statement. A row at zero
 * is deleted. This replaced, in turn, an unlocked select-then-insert-or-update
 * (duplicate keys, lost increments) and per-save deltas from the entry as
 * loaded (two saves of the same entry, both loaded before either committed,
 * each applied the same delta).
 *
 * <p>Lock order: a transaction takes its aggregate row locks once, at
 * commit, by tag name, each tag's weblog rows (by weblog id) before its
 * site-wide row, and only after every other write of the transaction has been
 * flushed. Two transactions therefore never wait on each other's aggregate
 * rows in a cycle, however many entries each saved; entry and weblog rows are
 * still locked in whatever order each transaction writes them.
 *
 * <p>Until the commit, a tag read inside the saving transaction still sees
 * the old counts. The SQL bypasses EclipseLink, so
 * {@code WeblogEntryTagAggregate} is not kept in the shared cache
 * ({@code cacheable="false"} in its mapping).
 */
final class TagAggregateStore {

    private static final String TAGS_OF_ENTRY =
            "SELECT DISTINCT name FROM roller_weblogentrytag WHERE entryid = ?1";

    private static final String LOCK_WEBLOG_ROW =
            "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
            + "VALUES (?1, ?2, ?3, 0, ?4) "
            + "ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE SET total = roller_weblogentrytagagg.total";

    private static final String LOCK_SITE_ROW =
            "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
            + "VALUES (?1, NULL, ?2, 0, ?3) "
            + "ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE SET total = roller_weblogentrytagagg.total";

    private static final String RECOUNT_WEBLOG_ROW =
            "UPDATE roller_weblogentrytagagg a "
            + "SET total = c.n, lastused = CASE WHEN a.total = c.n THEN a.lastused ELSE ?1 END "
            + "FROM (SELECT count(DISTINCT t.entryid) AS n FROM roller_weblogentrytag t "
            + "JOIN weblogentry e ON e.id = t.entryid "
            + "WHERE e.status = 'PUBLISHED' AND t.name = ?2 AND e.websiteid = ?3) c "
            + "WHERE a.name = ?4 AND a.websiteid = ?5";

    private static final String RECOUNT_SITE_ROW =
            "UPDATE roller_weblogentrytagagg a "
            + "SET total = c.n, lastused = CASE WHEN a.total = c.n THEN a.lastused ELSE ?1 END "
            + "FROM (SELECT count(DISTINCT t.entryid) AS n FROM roller_weblogentrytag t "
            + "JOIN weblogentry e ON e.id = t.entryid "
            + "WHERE e.status = 'PUBLISHED' AND t.name = ?2) c "
            + "WHERE a.name = ?3 AND a.websiteid IS NULL";

    private static final String DELETE_EMPTY_ROWS =
            "DELETE FROM roller_weblogentrytagagg "
            + "WHERE name = ?1 AND (websiteid = ?2 OR websiteid IS NULL) AND total <= 0";

    private final JPAPersistenceStrategy strategy;

    TagAggregateStore(JPAPersistenceStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Recount, at commit, these tags for {@code weblog} and site-wide.
     */
    void recount(Weblog weblog, Collection<String> tagNames) throws WebloggerException {
        if (weblog == null) {
            throw new WebloggerException("Website cannot be NULL.");
        }
        pending().addTags(weblog.getId(), tagNames);
    }

    /**
     * Recount, at commit, these tags and every tag the entry then carries in
     * the database -- including any a concurrent save of the same entry
     * added that this one never loaded.
     */
    void recount(WeblogEntry entry, Collection<String> tagNames) throws WebloggerException {
        recount(entry.getWebsite(), tagNames);
        pending().addEntry(entry.getId(), entry.getWebsite().getId());
    }

    private Recount pending() {
        return strategy.beforeCommit(Recount.class, () -> new Recount(strategy));
    }

    /** The tags one transaction touched, recounted just before it commits. */
    static final class Recount implements JPAPersistenceStrategy.BeforeCommit {

        private final JPAPersistenceStrategy strategy;
        /** tag name to the ids of the weblogs whose row for it to recount */
        private final SortedMap<String, SortedSet<String>> weblogsByTag = new TreeMap<>();
        /** entry id to its weblog id */
        private final Map<String, String> entries = new LinkedHashMap<>();

        Recount(JPAPersistenceStrategy strategy) {
            this.strategy = strategy;
        }

        void addTags(String weblogId, Collection<String> tagNames) {
            for (String name : tagNames) {
                weblogsByTag.computeIfAbsent(name, k -> new TreeSet<>()).add(weblogId);
            }
        }

        void addEntry(String entryId, String weblogId) {
            entries.put(entryId, weblogId);
        }

        @Override
        public void run() {
            // The first statement flushes the transaction's pending writes,
            // so the tags read here, and every count below, include them.
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                for (Object name : strategy.getNativeQuery(TAGS_OF_ENTRY)
                        .setParameter(1, entry.getKey()).getResultList()) {
                    weblogsByTag.computeIfAbsent((String) name, k -> new TreeSet<>()).add(entry.getValue());
                }
            }
            Timestamp now = new Timestamp(System.currentTimeMillis());
            for (Map.Entry<String, SortedSet<String>> tag : weblogsByTag.entrySet()) {
                String name = tag.getKey();
                for (String weblogId : tag.getValue()) {
                    strategy.getNativeQuery(LOCK_WEBLOG_ROW)
                            .setParameter(1, UUIDGenerator.generateUUID())
                            .setParameter(2, weblogId)
                            .setParameter(3, name)
                            .setParameter(4, now)
                            .executeUpdate();
                    strategy.getNativeQuery(RECOUNT_WEBLOG_ROW)
                            .setParameter(1, now)
                            .setParameter(2, name)
                            .setParameter(3, weblogId)
                            .setParameter(4, name)
                            .setParameter(5, weblogId)
                            .executeUpdate();
                }
                strategy.getNativeQuery(LOCK_SITE_ROW)
                        .setParameter(1, UUIDGenerator.generateUUID())
                        .setParameter(2, name)
                        .setParameter(3, now)
                        .executeUpdate();
                strategy.getNativeQuery(RECOUNT_SITE_ROW)
                        .setParameter(1, now)
                        .setParameter(2, name)
                        .setParameter(3, name)
                        .executeUpdate();
                for (String weblogId : tag.getValue()) {
                    strategy.getNativeQuery(DELETE_EMPTY_ROWS)
                            .setParameter(1, name)
                            .setParameter(2, weblogId)
                            .executeUpdate();
                }
            }
        }
    }
}
