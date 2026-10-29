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

import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import com.wikantik.pressroom.util.UUIDGenerator;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * The one writer of {@code pressroom_weblogentrytagagg}: one row per (weblog,
 * tag) and one site-wide row per tag ({@code websiteid IS NULL}), each holding
 * how many PUBLISHED entries carry the tag.
 *
 * <p>Callers say which tags a change may have touched; nothing is computed
 * from what the caller believes the entry looked like before. Just before the
 * transaction commits, each touched row is locked ({@code INSERT ... ON
 * CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE}), then set by a
 * separate statement: a weblog row to the count of that weblog's published
 * entries carrying the tag, the site-wide row to the sum of the tag's weblog
 * rows (each entry belongs to one weblog, so the sum is the site-wide count;
 * reading one row per weblog instead of every entry carrying the tag is what
 * keeps a save cheap on a large site). In READ COMMITTED that second
 * statement takes a fresh snapshot, so it sees whatever a transaction that
 * held the lock before it committed; and the transaction's own writes were
 * flushed by the first statement. A row at zero is deleted.
 *
 * <p>Why the sum is exact: {@code Recount.run} is the only runtime writer of
 * the weblog rows (any writer added later must re-sum the site-wide row under
 * its lock the same way), and every transaction that changes a weblog row
 * takes the tag's site-wide lock after that change and holds it to its
 * commit. So whichever transaction sums last took the lock after every
 * other one committed, and its fresh snapshot sees every weblog row as
 * finally committed; anyone summing earlier is followed by that one.
 * Summing in the locking statement itself would read from a snapshot taken
 * before the wait, and lose the entries of whoever held the lock
 * ({@code TagAggregateConcurrencyTest}). A weblog row at zero is deleted only
 * after the sum, and adds nothing to it either way. Deleting a weblog
 * recounts each of its rows the same way ({@link #recountWeblog}), so no row
 * is ever removed without the site-wide row being re-summed under its lock.
 *
 * <p>This replaced, in turn, an unlocked select-then-insert-or-update
 * (duplicate keys, lost increments), per-save deltas from the entry as
 * loaded (two saves of the same entry, both loaded before either committed,
 * each applied the same delta), and a site-wide recount that rescanned every
 * entry carrying the tag (~20 ms a save on a 44k-entry site).
 *
 * <p>Lock order: a transaction takes its aggregate row locks once, at
 * commit, by tag name, each tag's weblog rows (by weblog id) before its
 * site-wide row, and only after every other write of the transaction has been
 * flushed. Two transactions therefore never wait on each other's aggregate
 * rows in a cycle, however many entries each saved; entry and weblog rows are
 * still locked in whatever order each transaction writes them. Weblog
 * deletion follows the same order: it no longer deletes the weblog's rows
 * mid-transaction, it recounts them to zero at commit.
 *
 * <p>Relies on READ COMMITTED (a fresh snapshot per statement), which nothing
 * pins; under REPEATABLE READ the lock statement fails with serialization
 * errors instead (fails closed). {@code lastused} moves only when a row is
 * created or its count changes, not on every save touching the tag.
 *
 * <p>Until the commit, a tag read inside the saving transaction still sees
 * the old counts. The SQL bypasses EclipseLink, so
 * {@code WeblogEntryTagAggregate} is not kept in the shared cache
 * ({@code cacheable="false"} in its mapping).
 */
final class TagAggregateStore {

    private static final String TAGS_OF_ENTRY =
            "SELECT DISTINCT name FROM pressroom_weblogentrytag WHERE entryid = ?1";

    private static final String TAGS_OF_WEBLOG =
            "SELECT name FROM pressroom_weblogentrytagagg WHERE websiteid = ?1";

    private static final String LOCK_WEBLOG_ROW =
            "INSERT INTO pressroom_weblogentrytagagg (id, websiteid, name, total, lastused) "
            + "VALUES (?1, ?2, ?3, 0, ?4) "
            + "ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE SET total = pressroom_weblogentrytagagg.total";

    private static final String LOCK_SITE_ROW =
            "INSERT INTO pressroom_weblogentrytagagg (id, websiteid, name, total, lastused) "
            + "VALUES (?1, NULL, ?2, 0, ?3) "
            + "ON CONFLICT ON CONSTRAINT weta_weblog_tag_uq DO UPDATE SET total = pressroom_weblogentrytagagg.total";

    /*
     * A tag row carries its entry's weblog (WeblogEntry.addTag), and entries
     * never change weblog, so t.websiteid narrows the tag rows to the
     * weblog's through wet_weblog_name_entry_idx (V036) before the join;
     * e.websiteid keeps the count defined by the entry, as V034 computes it.
     */
    private static final String RECOUNT_WEBLOG_ROW =
            "UPDATE pressroom_weblogentrytagagg a "
            + "SET total = c.n, lastused = CASE WHEN a.total = c.n THEN a.lastused ELSE ?1 END "
            + "FROM (SELECT count(DISTINCT t.entryid) AS n FROM pressroom_weblogentrytag t "
            + "JOIN weblogentry e ON e.id = t.entryid "
            + "WHERE e.status = 'PUBLISHED' AND t.name = ?2 AND t.websiteid = ?3 AND e.websiteid = ?4) c "
            + "WHERE a.name = ?5 AND a.websiteid = ?6";

    private static final String SUM_SITE_ROW =
            "UPDATE pressroom_weblogentrytagagg a "
            + "SET total = c.n, lastused = CASE WHEN a.total = c.n THEN a.lastused ELSE ?1 END "
            + "FROM (SELECT CAST(COALESCE(SUM(w.total), 0) AS integer) AS n FROM pressroom_weblogentrytagagg w "
            + "WHERE w.name = ?2 AND w.websiteid IS NOT NULL) c "
            + "WHERE a.name = ?3 AND a.websiteid IS NULL";

    private static final String DELETE_EMPTY_ROWS =
            "DELETE FROM pressroom_weblogentrytagagg "
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

    /**
     * Recount, at commit, every tag {@code weblog} then has a row for, read
     * at commit. For weblog deletion, alongside {@link #recount(Weblog,
     * Collection)} of the tags whose rows it removed: a row no tag row backs
     * still falls to zero, goes, and comes out of the site-wide sum, where
     * a bulk DELETE of the weblog's rows would have taken their locks out of
     * order and left the site-wide rows unsummed.
     */
    void recountWeblog(Weblog weblog) {
        pending().addWeblog(weblog.getId());
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
        /** ids of the weblogs whose every row to recount */
        private final SortedSet<String> weblogs = new TreeSet<>();

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

        void addWeblog(String weblogId) {
            weblogs.add(weblogId);
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
            for (String weblogId : weblogs) {
                for (Object name : strategy.getNativeQuery(TAGS_OF_WEBLOG)
                        .setParameter(1, weblogId).getResultList()) {
                    weblogsByTag.computeIfAbsent((String) name, k -> new TreeSet<>()).add(weblogId);
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
                            .setParameter(4, weblogId)
                            .setParameter(5, name)
                            .setParameter(6, weblogId)
                            .executeUpdate();
                }
                strategy.getNativeQuery(LOCK_SITE_ROW)
                        .setParameter(1, UUIDGenerator.generateUUID())
                        .setParameter(2, name)
                        .setParameter(3, now)
                        .executeUpdate();
                // after every weblog row of the tag: the sum reads them
                strategy.getNativeQuery(SUM_SITE_ROW)
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
