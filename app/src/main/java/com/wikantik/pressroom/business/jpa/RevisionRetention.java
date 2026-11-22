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
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;

import com.wikantik.pressroom.business.RevisionKeepLimitException;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;

/**
 * Every decision about which revisions exist, for posts and pages alike
 * (spec 2026-11-15, Part 3). The two managers record revisions; neither
 * decides what is kept.
 *
 * <p>At a write, in the revision's own after-commit transaction:
 * {@link #shouldStore} (coalescing) and {@link #pruneAtWrite} (the per-item
 * count). A failure there costs only the revision, never the save.
 *
 * <p>Two rules protect history from every other rule: a revision marked
 * kept is exempt from all of them, and the newest {@link #FLOOR} unkept
 * revisions of an item are never removed by age or by the site-wide ceiling.
 */
public final class RevisionRetention {

    /** The newest unkept revisions of an item that age and the ceiling never remove. */
    public static final int FLOOR = 5;

    /** How many revisions of one item may be marked kept. */
    public static final int MAX_KEPT = 10;

    /** Minutes within which one user's repeated saves share a revision; 0 or less turns coalescing off. */
    public static final String COALESCE_PROPERTY = "revisions.coalesce.minutes";

    /** The two revision stores, described so that no rule is written twice. */
    public enum Kind {
        ENTRY("WeblogEntryRevision", "weblogEntry", "weblogentry_revision", "entryid",
                "weblogentry", "websiteid", "entry.revisions.retention"),
        PAGE("WeblogPageRevision", "page", "pressroom_weblogpage_revision", "pageid",
                "pressroom_weblogpage", "weblogid", "page.revisions.retention");

        final String entity;
        final String ownerField;
        final String table;
        final String ownerColumn;
        final String ownerTable;
        final String weblogColumn;
        final String retentionProperty;

        Kind(String entity, String ownerField, String table, String ownerColumn, String ownerTable,
                String weblogColumn, String retentionProperty) {
            this.entity = entity;
            this.ownerField = ownerField;
            this.table = table;
            this.ownerColumn = ownerColumn;
            this.ownerTable = ownerTable;
            this.weblogColumn = weblogColumn;
            this.retentionProperty = retentionProperty;
        }

        /** How many unkept revisions each item of this kind keeps: -1 all, 0 records none. */
        public int retention() {
            return WebloggerRuntimeConfig.getIntProperty(retentionProperty);
        }
    }

    private RevisionRetention() {
    }

    /**
     * Refuses the keep that would take an item past {@link #MAX_KEPT} kept
     * revisions. Counted in the caller's unit of work, in which the revision
     * about to be marked is not yet kept.
     */
    static void requireRoomToKeep(EntityManager em, Kind kind, String ownerId) throws RevisionKeepLimitException {
        long kept = em.createQuery("SELECT count(r) FROM " + kind.entity + " r WHERE r." + kind.ownerField
                        + ".id = ?1 AND r.kept = true", Long.class)
                .setParameter(1, ownerId).getSingleResult();
        if (kept >= MAX_KEPT) {
            throw new RevisionKeepLimitException(MAX_KEPT);
        }
    }

    /**
     * Coalescing: whether a save's revision is to be written at all. It is
     * not when the item's newest revision was created inside the window by
     * the same person now saving: that revision already holds the content
     * from before this burst of saves. The window runs from that revision's
     * own time and is not extended, so an unbroken session leaves at most
     * one revision per window.
     *
     * <p>Always written: a save no person made ({@code actor} null), a save
     * carrying a note, and a revision restore (or a save followed by a
     * restore would lose the content the restore replaced).
     */
    static boolean shouldStore(EntityManager em, Kind kind, String ownerId, String actor, String note,
            boolean restore) {
        if (restore || actor == null || note != null) {
            return true;
        }
        int minutes = WebloggerRuntimeConfig.getIntProperty(COALESCE_PROPERTY);
        if (minutes <= 0) {
            return true;
        }
        Query newest = em.createNativeQuery("SELECT creator, created FROM " + kind.table
                + " WHERE " + kind.ownerColumn + " = ?1 ORDER BY created DESC, id DESC LIMIT 1");
        newest.setFlushMode(FlushModeType.COMMIT);
        newest.setParameter(1, ownerId);
        List<?> rows = newest.getResultList();
        if (rows.isEmpty()) {
            return true;
        }
        Object[] row = (Object[]) rows.get(0);
        long created = ((Timestamp) row[1]).getTime();
        boolean insideWindow = System.currentTimeMillis() - created < TimeUnit.MINUTES.toMillis(minutes);
        return !(insideWindow && actor.equals(row[0]));
    }

    /**
     * The per-item count at a write: removes the item's unkept revisions
     * beyond {@code retention - 1}, because the revision about to be stored
     * is not counted yet. Kept revisions are neither counted nor removed.
     * Only a positive retention prunes. Removed through the entity manager,
     * one row at a time, so the shared cache never serves a removed row.
     */
    static <R> void pruneAtWrite(EntityManager em, Kind kind, Class<R> type, String ownerId, int retention) {
        if (retention <= 0) {
            return;
        }
        List<R> unkept = em.createQuery("SELECT r FROM " + kind.entity + " r WHERE r." + kind.ownerField
                        + ".id = ?1 AND r.kept = false ORDER BY r.created DESC, r.id DESC", type)
                .setFlushMode(FlushModeType.COMMIT)
                .setParameter(1, ownerId)
                .getResultList();
        for (int i = retention - 1; i < unkept.size(); i++) {
            em.remove(unkept.get(i));
        }
    }
}
