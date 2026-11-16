/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wikantik.pressroom.business.RevisionSweep;
import com.wikantik.pressroom.business.jpa.RevisionRetention.Kind;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogPageRevision;

/**
 * The revision sweep and the figures about the revision store (spec
 * 2026-11-15, Part 3). Removal here is the decision path, so it fails
 * closed: a limit that cannot be read is off, and a pass that throws rolls
 * back whole and removes nothing.
 *
 * <p>Two passes. Pass 1, one transaction per weblog: the per-item count,
 * then the age limit. Pass 2, once: the site-wide ceiling, oldest first
 * across posts and pages, in batches of {@link #BATCH}, each its own
 * transaction. Neither age nor the ceiling touches a kept revision or an
 * item's newest {@link RevisionRetention#FLOOR} unkept ones.
 *
 * <p>It works on its own EntityManagers, never the calling thread's unit of
 * work, and removes in bulk SQL; the two revision types are evicted from the
 * shared cache after a pass that removed anything.
 */
// Every statement here is assembled from the Kind constants and from ints
// read as ints; ids and times are bound parameters. Nothing a person typed
// reaches the SQL text.
class RevisionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RevisionSweeper.class);

    /** Removes revisions older than this many days, subject to the protections; 0 or less is off. */
    static final String MAX_AGE_PROPERTY = "revisions.maxAgeDays";

    /** The most revisions the site keeps, posts and pages together; 0 or less is off. */
    static final String TOTAL_MAX_PROPERTY = "revisions.total.max";

    /** Rows one ceiling transaction removes at most. */
    static final int BATCH = 1000;

    private final EntityManagerFactory emf;
    private volatile RevisionSweep.Report lastSweep;

    RevisionSweeper(EntityManagerFactory emf) {
        this.emf = emf;
    }

    /** The limits one sweep works to, read once at its start ({@link #sweep}). */
    record Limits(int entryRetention, int pageRetention, int maxAgeDays, long totalMax, Timestamp cutoff) {

        static Limits current() {
            int days = WebloggerRuntimeConfig.getIntProperty(MAX_AGE_PROPERTY);
            return new Limits(Kind.ENTRY.retention(), Kind.PAGE.retention(), days,
                    WebloggerRuntimeConfig.getIntProperty(TOTAL_MAX_PROPERTY),
                    new Timestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(days, 0))));
        }

        int retention(Kind kind) {
            return kind == Kind.ENTRY ? entryRetention : pageRetention;
        }
    }

    /**
     * Runs the sweep, or with {@code dryRun} counts what it would remove and
     * removes nothing. Never throws: a failure is in the report and the log.
     */
    RevisionSweep.Report sweep(boolean dryRun) {
        List<String> weblogIds;
        try {
            weblogIds = weblogIds();
        } catch (RuntimeException e) {
            log.warn("Revision sweep: the weblogs could not be listed; nothing removed", e);
            return remember(new RevisionSweep.Report(Instant.now(), dryRun, 0, 0, 0, false, 0, true));
        }
        // Read once: every weblog and the ceiling work to the same limits,
        // and a dry run predicts the sweep those limits give.
        Limits limits = Limits.current();
        long byCount = 0;
        long byAge = 0;
        int failed = 0;
        for (String weblogId : weblogIds) {
            try {
                long[] removed = inTransaction(em -> sweepWeblog(em, weblogId, dryRun, limits));
                byCount += removed[0];
                byAge += removed[1];
            } catch (RuntimeException e) {
                failed++;
                log.warn("Revision sweep: weblog {} failed; nothing of it removed", weblogId, e);
            }
        }
        // {removed, 1 when unmeetable}. Filled in as the pass goes: each
        // ceiling batch commits on its own, so when a later batch throws the
        // earlier ones are still gone and must be counted and evicted.
        long[] ceiling = {0, 0};
        boolean ceilingFailed = false;
        try {
            if (dryRun) {
                predictCeiling(limits, byCount + byAge, ceiling);
            } else {
                sweepCeiling(limits, ceiling);
            }
        } catch (RuntimeException e) {
            ceilingFailed = true;
            log.warn("Revision sweep: the site-wide ceiling pass failed after removing {}", ceiling[0], e);
        }
        long byCeiling = ceiling[0];
        boolean unmeetable = ceiling[1] > 0;
        if (!dryRun && byCount + byAge + byCeiling > 0) {
            emf.getCache().evict(WeblogEntryRevision.class);
            emf.getCache().evict(WeblogPageRevision.class);
        }
        return remember(new RevisionSweep.Report(Instant.now(), dryRun, byCount, byAge, byCeiling,
                unmeetable, failed, ceilingFailed));
    }

    private RevisionSweep.Report remember(RevisionSweep.Report report) {
        if (!report.dryRun()) {
            lastSweep = report;
            if (report.total() > 0 || report.weblogsFailed() > 0 || report.ceilingUnmeetable()) {
                log.info("Revision sweep: {}", report);
            }
        }
        return report;
    }

    /**
     * Pass 1 for one weblog, both kinds, inside the caller's transaction:
     * how many revisions went (or would go) by count and by age.
     */
    long[] sweepWeblog(EntityManager em, String weblogId, boolean dryRun, Limits limits) {
        long byCount = 0;
        long byAge = 0;
        for (Kind kind : Kind.values()) {
            String ranked = ranked(kind, limits, "o." + kind.weblogColumn + " = ?2");
            Object[] counts = (Object[]) em.createNativeQuery(
                    "SELECT coalesce(sum(CASE WHEN by_count THEN 1 ELSE 0 END), 0), "
                            + "coalesce(sum(CASE WHEN by_age THEN 1 ELSE 0 END), 0) FROM (" + ranked + ") x")
                    .setParameter(1, limits.cutoff()).setParameter(2, weblogId).getSingleResult();
            byCount += ((Number) counts[0]).longValue();
            byAge += ((Number) counts[1]).longValue();
            if (!dryRun) {
                em.createNativeQuery("DELETE FROM " + kind.table + " WHERE id IN (SELECT id FROM (" + ranked
                                + ") x WHERE by_count OR by_age)")
                        .setParameter(1, limits.cutoff()).setParameter(2, weblogId).executeUpdate();
            }
        }
        return new long[] {byCount, byAge};
    }

    /**
     * Every unkept revision of one kind, ranked newest first within its
     * item, with what pass 1 does to it. {@code by_count}: beyond the item's
     * count. {@code by_age}: not already going by count, past the newest
     * {@link RevisionRetention#FLOOR}, and older than the cutoff (?1).
     */
    private static String ranked(Kind kind, Limits limits, String where) {
        int retention = limits.retention(kind);
        String byCount = retention > 0 ? "rn > " + retention : "false";
        String byAge = limits.maxAgeDays() > 0
                ? "NOT (" + byCount + ") AND rn > " + RevisionRetention.FLOOR + " AND created < ?1"
                : "false AND created < ?1";
        return "SELECT id, created, (" + byCount + ") AS by_count, (" + byAge + ") AS by_age, rn FROM ("
                + "SELECT r.id, r.created, row_number() OVER (PARTITION BY r." + kind.ownerColumn
                + " ORDER BY r.created DESC, r.id DESC) AS rn FROM " + kind.table + " r JOIN " + kind.ownerTable
                + " o ON o.id = r." + kind.ownerColumn + " WHERE r.kept = false AND " + where + ") ranked";
    }

    /**
     * Pass 2. {@code result[0]} grows as each batch commits, so it is right
     * even if a later batch throws; {@code result[1]} becomes 1 when the
     * ceiling is still exceeded with nothing removable left.
     */
    private void sweepCeiling(Limits limits, long[] result) {
        if (limits.totalMax() <= 0) {
            return;
        }
        while (true) {
            long excess = total() - limits.totalMax();
            if (excess <= 0) {
                return;
            }
            long batch = inTransaction(em -> sweepCeilingBatch(em, excess));
            if (batch == 0) {
                log.warn("Revision ceiling of {} cannot be met: {} revisions over it are all protected "
                        + "(each item's newest {} and kept ones)", limits.totalMax(), excess, RevisionRetention.FLOOR);
                result[1] = 1;
                return;
            }
            result[0] += batch;
        }
    }

    /** One ceiling transaction: removes up to {@link #BATCH} of the oldest unprotected revisions; how many. */
    long sweepCeilingBatch(EntityManager em, long excess) {
        List<?> oldest = em.createNativeQuery("SELECT kind, id FROM (" + unprotected() + ") u "
                        + "ORDER BY created ASC, id ASC LIMIT " + Math.min(excess, batchSize()))
                .getResultList();
        long removed = 0;
        for (Kind kind : Kind.values()) {
            List<String> ids = new ArrayList<>();
            for (Object row : oldest) {
                Object[] columns = (Object[]) row;
                if (kind.name().equals(columns[0])) {
                    ids.add((String) columns[1]);
                }
            }
            removed += deleteUnkept(em, kind, ids);
        }
        return removed;
    }

    /** Rows one ceiling transaction removes at most; a method so a test can make batches small. */
    int batchSize() {
        return BATCH;
    }

    /**
     * Deletes these revisions of one kind, except any that is kept; how many
     * went. The ids were chosen as unkept a statement ago, and a revision
     * can be marked kept in between: the delete itself holds the guarantee
     * that the ceiling never takes a kept one.
     */
    long deleteUnkept(EntityManager em, Kind kind, List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return em.createQuery("DELETE FROM " + kind.entity + " r WHERE r.id IN ?1 AND r.kept = false")
                .setParameter(1, ids).executeUpdate();
    }

    /** Every revision the ceiling may remove: unkept and past its item's newest {@link RevisionRetention#FLOOR}. */
    private static String unprotected() {
        StringBuilder sql = new StringBuilder(512);
        for (Kind kind : Kind.values()) {
            if (sql.length() > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append("SELECT '").append(kind.name()).append("' AS kind, id, created FROM (SELECT id, created, "
                    + "row_number() OVER (PARTITION BY ").append(kind.ownerColumn)
                    .append(" ORDER BY created DESC, id DESC) AS rn FROM ").append(kind.table)
                    .append(" WHERE kept = false) k WHERE rn > ").append(RevisionRetention.FLOOR);
        }
        return sql.toString();
    }

    /**
     * What pass 2 would remove after pass 1 has removed {@code pass1}: the
     * ceiling's excess, capped by what would then be unprotected.
     */
    private void predictCeiling(Limits limits, long pass1, long[] result) {
        if (limits.totalMax() <= 0) {
            return;
        }
        long excess = total() - pass1 - limits.totalMax();
        if (excess <= 0) {
            return;
        }
        long removable = 0;
        for (Kind kind : Kind.values()) {
            String ranked = ranked(kind, limits, "true");
            removable += inTransaction(em -> ((Number) em.createNativeQuery("SELECT count(*) FROM (" + ranked
                            + ") x WHERE NOT by_count AND NOT by_age AND rn > " + RevisionRetention.FLOOR)
                    .setParameter(1, limits.cutoff()).getSingleResult()).longValue());
        }
        result[0] = Math.min(excess, removable);
        result[1] = excess > removable ? 1 : 0;
    }

    /** The store as it stands; throws if it cannot be read. */
    RevisionSweep.Stats stats() {
        return inTransaction(em -> {
            long[] counts = new long[2];
            long kept = 0;
            long bytes = 0;
            Instant oldest = null;
            for (Kind kind : Kind.values()) {
                String text = kind == Kind.ENTRY
                        ? "coalesce(octet_length(text), 0) + coalesce(octet_length(summary), 0)"
                        : "coalesce(octet_length(content), 0)";
                Object[] row = (Object[]) em.createNativeQuery("SELECT count(*), "
                        + "coalesce(sum(CASE WHEN kept THEN 1 ELSE 0 END), 0), "
                        + "coalesce(sum(coalesce(octet_length(title), 0) + " + text + "), 0), min(created) FROM "
                        + kind.table).getSingleResult();
                counts[kind.ordinal()] = ((Number) row[0]).longValue();
                kept += ((Number) row[1]).longValue();
                bytes += ((Number) row[2]).longValue();
                Instant first = row[3] == null ? null : ((Timestamp) row[3]).toInstant();
                if (first != null && (oldest == null || first.isBefore(oldest))) {
                    oldest = first;
                }
            }
            return new RevisionSweep.Stats(counts[Kind.ENTRY.ordinal()], counts[Kind.PAGE.ordinal()], kept, bytes,
                    oldest, largest(em), lastSweep, sweep(true));
        });
    }

    /** The ten items holding the most revisions, most first. */
    private static List<RevisionSweep.ItemCount> largest(EntityManager em) {
        StringBuilder sql = new StringBuilder(512);
        for (Kind kind : Kind.values()) {
            if (sql.length() > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append("SELECT '").append(kind.name()).append("' AS kind, o.id, o.title, count(*) AS n FROM ")
                    .append(kind.table).append(" r JOIN ").append(kind.ownerTable).append(" o ON o.id = r.")
                    .append(kind.ownerColumn).append(" GROUP BY o.id, o.title");
        }
        List<RevisionSweep.ItemCount> items = new ArrayList<>();
        for (Object row : em.createNativeQuery("SELECT kind, id, title, n FROM (" + sql
                + ") items ORDER BY n DESC, id ASC LIMIT 10").getResultList()) {
            Object[] columns = (Object[]) row;
            items.add(new RevisionSweep.ItemCount(RevisionSweep.ItemKind.valueOf((String) columns[0]),
                    (String) columns[1], (String) columns[2], ((Number) columns[3]).longValue()));
        }
        return items;
    }

    private long total() {
        return inTransaction(em -> ((Number) em.createNativeQuery("SELECT (SELECT count(*) FROM "
                + Kind.ENTRY.table + ") + (SELECT count(*) FROM " + Kind.PAGE.table + ")")
                .getSingleResult()).longValue());
    }

    private List<String> weblogIds() {
        return inTransaction(em -> {
            List<String> ids = new ArrayList<>();
            for (Object id : em.createNativeQuery("SELECT id FROM weblog ORDER BY id").getResultList()) {
                ids.add((String) id);
            }
            return ids;
        });
    }

    /** Work in a transaction of its own on its own EntityManager; rolled back whole if it throws. */
    @FunctionalInterface
    private interface Work<T> {
        T in(EntityManager em);
    }

    private <T> T inTransaction(Work<T> work) {
        try (EntityManager em = emf.createEntityManager()) {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                T result = work.in(em);
                tx.commit();
                return result;
            } finally {
                if (tx.isActive()) {
                    tx.rollback();
                }
            }
        }
    }
}
