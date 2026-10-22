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
package org.apache.roller.weblogger.business.jpa;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.roller.weblogger.pojos.Weblog;

/**
 * One remembered value per weblog, valid only at the
 * {@code weblog.lastModified} it was read at: the pattern of
 * {@code SitemapCache} and the lazy render caches, for answers the business
 * tier reads on every render (a weblog's templates by action, its published
 * pages). A stamp that no longer matches is a miss, and the slot is replaced
 * in place, so it holds one version per weblog however often it is edited.
 *
 * <p><b>Values are ids, never entities.</b> An entity belongs to the
 * EntityManager that loaded it; the caller resolves remembered ids through
 * its own, so nothing here outlives a unit of work or is merged across one.
 *
 * <p><b>Correctness rests on two rules.</b> Every change to a remembered
 * answer must move {@code weblog.lastModified} (the managers' save and
 * remove methods do, through {@code saveWeblog}). And a unit of work with
 * {@linkplain JPAPersistenceStrategy#hasUncommittedWork() uncommitted work}
 * neither reads nor fills: its weblog may carry a stamp that never commits,
 * or one that commits together with changes its own no-flush queries did
 * not see. Outside that, the stamp and the query both read committed state,
 * and a stamp is visible only once its commit is, so a slot can only ever
 * pair a stamp with an answer at least as new.
 *
 * <p>Bounded by the number of weblogs: slots are created only for weblogs
 * that were looked up, and a deleted weblog's slot is {@linkplain #forget
 * forgotten} once the delete commits. Thread safety: the map is concurrent
 * and each slot immutable; two concurrent misses both read and the later put
 * wins, and both answers are correct for the stamp they carry.
 *
 * @param <V> the remembered value, immutable
 */
final class WeblogStampedCache<V> {

    private record Slot<V>(long stamp, V value) {
    }

    private final JPAPersistenceStrategy strategy;
    private final ConcurrentMap<String, Slot<V>> slots = new ConcurrentHashMap<>();

    WeblogStampedCache(JPAPersistenceStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * The value remembered for this weblog at its current stamp, or null: on a
     * miss, and always while this unit of work has uncommitted work.
     */
    V get(Weblog weblog) {
        Long stamp = usableStamp(weblog);
        if (stamp == null) {
            return null;
        }
        Slot<V> slot = slots.get(weblog.getId());
        return slot != null && slot.stamp() == stamp ? slot.value() : null;
    }

    /** Remembers {@code value} for this weblog at its current stamp, when it may. */
    void put(Weblog weblog, V value) {
        Long stamp = usableStamp(weblog);
        if (stamp != null) {
            slots.put(weblog.getId(), new Slot<>(stamp, value));
        }
    }

    /**
     * The weblog's stamp, or null when nothing may be read or remembered for
     * it: it has no id or stamp yet (never saved), or this unit of work has
     * uncommitted work.
     */
    private Long usableStamp(Weblog weblog) {
        Date stamp = weblog.getLastModified();
        if (stamp == null || weblog.getId() == null || strategy.hasUncommittedWork()) {
            return null;
        }
        return stamp.getTime();
    }

    /** Drops the weblog's slot; for a deleted weblog, whose stamp never moves again. */
    void forget(String weblogId) {
        slots.remove(weblogId);
    }

    boolean remembers(String weblogId) {
        return slots.containsKey(weblogId);
    }
}
