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

import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wikantik.pressroom.WebloggerException;

/**
 * The record / keep / list steps shared by entry and page revisions: one
 * implementation, parameterised by {@link RevisionRetention.Kind}, the owner
 * and revision classes, and the few accessors that differ.
 *
 * <p>A revision is written after the save has committed, in a transaction of
 * its own: written in the save's transaction, a row the database refused
 * failed the commit and took the save with it. Here a failure is logged and
 * costs only the revision.
 *
 * @param <O> the owner (entry or page)
 * @param <R> its revision
 */
final class RevisionLog<O, R> {

    private static final Logger log = LoggerFactory.getLogger(RevisionLog.class);

    /** What differs between the two stores: the accessors and the list query. */
    record Binding<O, R>(
            RevisionRetention.Kind kind,
            Class<O> ownerClass,
            Class<R> revisionClass,
            String listQuery,
            Function<O, String> ownerId,
            BiConsumer<R, O> attach,
            Function<R, O> ownerOf,
            Function<R, String> noteOf,
            BiConsumer<R, String> setNote,
            Function<R, Boolean> keptOf,
            BiConsumer<R, Boolean> setKept) { }

    private final JPAPersistenceStrategy strategy;
    private final Binding<O, R> b;

    RevisionLog(JPAPersistenceStrategy strategy, Binding<O, R> binding) {
        this.strategy = strategy;
        this.b = binding;
    }

    /**
     * Records what this save displaced, and prunes to the configured
     * retention, once the save has committed. -1 (the default) keeps
     * everything, 0 records nothing, a positive n keeps the n newest. A save
     * that never commits records nothing.
     *
     * @param capture the revision of what is being replaced, or null when
     *                nothing is
     * @param actor   the acting user, null for a save no person made
     */
    // The factory is the tier's own, shared and closed at shutdown by the
    // strategy; closing it here would take the whole persistence unit down.
    @SuppressWarnings("PMD.CloseResource")
    void record(O owner, Supplier<R> capture, String note, String actor, boolean restore) {
        int retention = b.kind().retention();
        if (retention == 0) {
            return;
        }
        R revision = capture.get();
        if (revision == null) {
            // nothing replaced, so nothing for the note to label
            return;
        }
        // Set every time, null included: the snapshot object is the owner's
        // own, and a note must label only the revision its save left.
        b.setNote().accept(revision, note);
        String ownerId = b.ownerId().apply(owner);
        EntityManagerFactory emf = strategy.getEntityManager(false).getEntityManagerFactory();
        strategy.runAfterCommit(() -> store(emf, ownerId, revision, retention, actor, restore));
    }

    /**
     * Stores {@code revision} for the owner and prunes its oldest beyond
     * {@code retention}, in a transaction of its own on its own
     * EntityManager: the thread's unit of work has just committed, and a
     * failure here must leave it exactly as the save left it.
     */
    private void store(EntityManagerFactory emf, String ownerId, R revision, int retention,
            String actor, boolean restore) {
        try (EntityManager em = emf.createEntityManager()) {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                O owner = em.find(b.ownerClass(), ownerId);
                if (owner == null) {
                    // deleted by the transaction that saved it: nothing to hang off
                    return;
                }
                // Which revisions exist is RevisionRetention's decision: a
                // coalesced save writes none and so pushes none out.
                if (!RevisionRetention.shouldStore(em, b.kind(), ownerId, actor,
                        b.noteOf().apply(revision), restore)) {
                    return;
                }
                b.attach().accept(revision, owner);
                RevisionRetention.pruneAtWrite(em, b.kind(), b.revisionClass(), ownerId, retention);
                em.persist(revision);
                tx.commit();
            } finally {
                if (tx.isActive()) {
                    tx.rollback();
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not record a revision for {} {}; the save itself is unaffected",
                    b.kind().name().toLowerCase(java.util.Locale.ROOT), ownerId, e);
        }
    }

    List<R> list(O owner) throws WebloggerException {
        if (owner == null || b.ownerId().apply(owner) == null) {
            return Collections.emptyList();
        }
        TypedQuery<R> query = strategy.getNamedQuery(b.listQuery(), b.revisionClass());
        query.setParameter(1, owner);
        return query.getResultList();
    }

    R get(String id) throws WebloggerException {
        if (id == null) {
            return null;
        }
        return b.revisionClass().cast(strategy.load(b.revisionClass(), id));
    }

    void setKept(R revision, boolean kept) throws WebloggerException {
        if (b.keptOf().apply(revision) == kept) {
            return;
        }
        if (kept) {
            RevisionRetention.requireRoomToKeep(strategy.getEntityManager(true), b.kind(),
                    b.ownerId().apply(b.ownerOf().apply(revision)));
        }
        b.setKept().accept(revision, kept);
        strategy.store(revision);
    }
}
