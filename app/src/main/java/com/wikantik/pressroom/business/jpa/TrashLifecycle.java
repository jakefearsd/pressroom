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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.persistence.EntityNotFoundException;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * The trash rules entries and pages share: the state guards, the
 * {@code trashedAt} stamp, the purge cutoff and the purge-under-lock. What
 * differs (the status type, how a row is saved, listed, locked and removed,
 * and what a restore must also do) is passed in.
 *
 * @param <T> the trashable item (entry or page)
 */
final class TrashLifecycle<T> {

    /** An operation on one item that may fail with a {@link WebloggerException}. */
    @FunctionalInterface
    interface Step<T> {
        void apply(T item) throws WebloggerException;
    }

    /** Lists a weblog's trashed items. */
    @FunctionalInterface
    interface Lister<T> {
        List<T> trashed(Weblog weblog) throws WebloggerException;
    }

    /** How the lifecycle reads and writes one kind of item. */
    record Binding<T>(
            String label,
            Function<T, String> id,
            Predicate<T> isTrashed,
            Function<T, Timestamp> trashedAt,
            BiConsumer<T, Timestamp> markTrashed,
            Consumer<T> markRestored,
            Step<T> save,
            Step<T> lockFresh,
            Step<T> remove,
            Lister<T> lister) { }

    private final Binding<T> b;

    TrashLifecycle(Binding<T> binding) {
        this.b = binding;
    }

    /** Stamps the item trashed and saves it; refuses one already in the trash. */
    void trash(T item) throws WebloggerException {
        if (b.isTrashed().test(item)) {
            throw new WebloggerException(b.label() + " is already in the trash: " + b.id().apply(item));
        }
        b.markTrashed().accept(item, new Timestamp(System.currentTimeMillis()));
        b.save().apply(item);
    }

    /**
     * Brings the item back as a draft and saves it; refuses one not in the
     * trash. {@code beforeRestore} runs first, while the item is still
     * trashed.
     */
    void restore(T item, Step<T> beforeRestore) throws WebloggerException {
        if (!b.isTrashed().test(item)) {
            throw new WebloggerException(b.label() + " is not in the trash: " + b.id().apply(item));
        }
        beforeRestore.apply(item);
        b.markRestored().accept(item);
        b.save().apply(item);
    }

    /**
     * Removes every item of the weblog trashed at or before the cutoff,
     * {@code retentionDays} ago; a negative retention keeps the trash forever.
     *
     * @return how many were removed
     */
    int purge(Weblog weblog, int retentionDays) throws WebloggerException {
        if (retentionDays < 0) {
            return 0;
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(retentionDays)));
        int purged = 0;
        for (T item : new ArrayList<>(b.lister().trashed(weblog))) {
            if (pastCutoff(item, cutoff) && removeIfStillTrashed(item, cutoff)) {
                purged++;
            }
        }
        return purged;
    }

    /**
     * Removes a purge candidate only if its row is still trash and still past
     * the cutoff now. The sweep holds the item as it was when listed, and a
     * restore (or a re-trash) can commit before the delete, so the row is
     * read again under a write lock: a restore either is seen here or waits
     * until this is done. An item that left the trash, or is gone, is left
     * alone.
     *
     * @return whether the item was removed
     */
    boolean removeIfStillTrashed(T item, Timestamp cutoff) throws WebloggerException {
        try {
            b.lockFresh().apply(item);
        } catch (EntityNotFoundException gone) {
            return false;
        }
        if (!b.isTrashed().test(item) || !pastCutoff(item, cutoff)) {
            return false;
        }
        b.remove().apply(item);
        return true;
    }

    private boolean pastCutoff(T item, Timestamp cutoff) {
        Timestamp trashedAt = b.trashedAt().apply(item);
        return trashedAt != null && !trashedAt.after(cutoff);
    }
}
