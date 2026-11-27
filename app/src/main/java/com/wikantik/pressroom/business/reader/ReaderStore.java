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
package com.wikantik.pressroom.business.reader;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;

/**
 * The SQL of the three {@code pressroom_reader_*} tables (V056). No entity maps
 * them: every statement is native, constant, and runs in the current unit of
 * work, which the caller commits. Lists come back as ids in order; the
 * service turns them into entries and weblogs.
 *
 * <p>Every entry list states {@link ReaderVisibility}'s rule in SQL
 * ({@link #VISIBLE}, with the time as {@code ?2}), so its limit counts only
 * rows a reader may see.
 */
final class ReaderStore {

    private static final String ENTRY_JOIN =
            " JOIN weblogentry e ON e.id = x.entry_id JOIN weblog w ON w.id = e.websiteid";

    /** ReaderVisibility's rule; {@code ?2} is now. */
    private static final String VISIBLE = " AND e.status = 'PUBLISHED' AND e.pubtime <= ?2"
            + " AND e.trashed_at IS NULL AND w.isactive = true AND w.visible = true";

    private static final String SAVED = "SELECT x.entry_id FROM pressroom_reader_save x" + ENTRY_JOIN
            + " WHERE x.user_id = ?1" + VISIBLE + " ORDER BY x.created DESC, x.entry_id LIMIT ?3";

    private static final String READS = "SELECT x.entry_id FROM pressroom_reader_read x" + ENTRY_JOIN
            + " WHERE x.user_id = ?1" + VISIBLE + " ORDER BY x.read_at DESC, x.entry_id LIMIT ?3";

    private static final String PLACES = "SELECT e.websiteid FROM pressroom_reader_read x" + ENTRY_JOIN
            + " WHERE x.user_id = ?1" + VISIBLE
            + " GROUP BY e.websiteid ORDER BY max(x.read_at) DESC, e.websiteid LIMIT ?3";

    private static final String FOLLOWED_POSTS = "SELECT e.id FROM pressroom_reader_follow x"
            + " JOIN weblog w ON w.id = x.weblog_id JOIN weblogentry e ON e.websiteid = w.id"
            + " WHERE x.user_id = ?1" + VISIBLE + " ORDER BY e.pubtime DESC, e.id LIMIT ?3";

    private static final String FOLLOWING = "SELECT x.weblog_id FROM pressroom_reader_follow x"
            + " JOIN weblog w ON w.id = x.weblog_id WHERE x.user_id = ?1"
            + " AND w.isactive = true AND w.visible = true ORDER BY x.created DESC, x.weblog_id LIMIT ?2";

    private static final String SAVE = "INSERT INTO pressroom_reader_save (user_id, entry_id, created)"
            + " VALUES (?1, ?2, ?3) ON CONFLICT DO NOTHING";
    private static final String UNSAVE = "DELETE FROM pressroom_reader_save WHERE user_id = ?1 AND entry_id = ?2";
    private static final String IS_SAVED = "SELECT count(*) FROM pressroom_reader_save x" + ENTRY_JOIN
            + " WHERE x.user_id = ?1" + VISIBLE + " AND x.entry_id = ?3";

    private static final String FOLLOW = "INSERT INTO pressroom_reader_follow (user_id, weblog_id, created)"
            + " VALUES (?1, ?2, ?3) ON CONFLICT DO NOTHING";
    private static final String UNFOLLOW =
            "DELETE FROM pressroom_reader_follow WHERE user_id = ?1 AND weblog_id = ?2";
    private static final String IS_FOLLOWING = "SELECT count(*) FROM pressroom_reader_follow x"
            + " JOIN weblog w ON w.id = x.weblog_id WHERE x.user_id = ?1 AND x.weblog_id = ?2"
            + " AND w.isactive = true AND w.visible = true";

    private static final String READ = "INSERT INTO pressroom_reader_read (user_id, entry_id, read_at)"
            + " VALUES (?1, ?2, ?3) ON CONFLICT (user_id, entry_id) DO UPDATE SET read_at = EXCLUDED.read_at";
    /** Everything after the newest {@code ?3}, in the order {@link #READS} lists them. */
    private static final String TRIM = "DELETE FROM pressroom_reader_read WHERE user_id = ?1 AND entry_id IN"
            + " (SELECT entry_id FROM pressroom_reader_read WHERE user_id = ?2"
            + " ORDER BY read_at DESC, entry_id OFFSET ?3)";
    private static final String CLEAR = "DELETE FROM pressroom_reader_read WHERE user_id = ?1";

    private final Supplier<JPAPersistenceStrategy> strategy;

    ReaderStore(Supplier<JPAPersistenceStrategy> strategy) {
        this.strategy = strategy;
    }

    // ------------------------------------------------------------- lists

    List<String> saved(String userId, Instant now, int limit) throws WebloggerException {
        return visibleIds(SAVED, userId, now, limit);
    }

    List<String> reads(String userId, Instant now, int limit) throws WebloggerException {
        return visibleIds(READS, userId, now, limit);
    }

    List<String> places(String userId, Instant now, int limit) throws WebloggerException {
        return visibleIds(PLACES, userId, now, limit);
    }

    List<String> followedPosts(String userId, Instant now, int limit) throws WebloggerException {
        return visibleIds(FOLLOWED_POSTS, userId, now, limit);
    }

    List<String> following(String userId, int limit) throws WebloggerException {
        return ids(query(FOLLOWING).setParameter(1, userId).setParameter(2, limit));
    }

    // ------------------------------------------------------------- rows

    void save(String userId, String entryId, Instant at) throws WebloggerException {
        write(query(SAVE).setParameter(1, userId).setParameter(2, entryId).setParameter(3, Timestamp.from(at)));
    }

    void unsave(String userId, String entryId) throws WebloggerException {
        write(query(UNSAVE).setParameter(1, userId).setParameter(2, entryId));
    }

    /** Whether the reader saved this entry and it is visible at {@code now}. */
    boolean isSaved(String userId, String entryId, Instant now) throws WebloggerException {
        return count(query(IS_SAVED).setParameter(1, userId).setParameter(2, Timestamp.from(now))
                .setParameter(3, entryId)) > 0;
    }

    void follow(String userId, String weblogId, Instant at) throws WebloggerException {
        write(query(FOLLOW).setParameter(1, userId).setParameter(2, weblogId).setParameter(3, Timestamp.from(at)));
    }

    void unfollow(String userId, String weblogId) throws WebloggerException {
        write(query(UNFOLLOW).setParameter(1, userId).setParameter(2, weblogId));
    }

    /** Whether the reader follows this weblog and it is visible. */
    boolean isFollowing(String userId, String weblogId) throws WebloggerException {
        return count(query(IS_FOLLOWING).setParameter(1, userId).setParameter(2, weblogId)) > 0;
    }

    /** Upserts the read, then keeps only the newest {@code keep} of this reader's. */
    void read(String userId, String entryId, Instant at, int keep) throws WebloggerException {
        write(query(READ).setParameter(1, userId).setParameter(2, entryId).setParameter(3, Timestamp.from(at)));
        write(query(TRIM).setParameter(1, userId).setParameter(2, userId).setParameter(3, keep));
    }

    void clearReads(String userId) throws WebloggerException {
        write(query(CLEAR).setParameter(1, userId));
    }

    // ------------------------------------------------------------- helpers

    private List<String> visibleIds(String sql, String userId, Instant now, int limit) throws WebloggerException {
        return ids(query(sql).setParameter(1, userId).setParameter(2, Timestamp.from(now)).setParameter(3, limit));
    }

    private static long count(Query q) throws WebloggerException {
        try {
            return ((Number) q.getSingleResult()).longValue();
        } catch (PersistenceException e) {
            throw new WebloggerException("reader state could not be read", e);
        }
    }

    private static List<String> ids(Query q) throws WebloggerException {
        try {
            List<String> ids = new ArrayList<>();
            for (Object id : q.getResultList()) {
                ids.add((String) id);
            }
            return ids;
        } catch (PersistenceException e) {
            throw new WebloggerException("reader state could not be read", e);
        }
    }

    private static void write(Query q) throws WebloggerException {
        try {
            q.executeUpdate();
        } catch (PersistenceException e) {
            throw new WebloggerException("reader state could not be written", e);
        }
    }

    private Query query(String sql) {
        return strategy.get().getNativeQuery(sql);
    }
}
