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

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.business.NewsletterEvents;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.config.ConfigSource;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.util.GenericThrottle;
import com.wikantik.pressroom.util.PressroomConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Every rule about what a signed-in reader keeps: saves, follows and reading
 * history (spec Part E). The {@code reader.rol} endpoint and the account page
 * come through here; nothing else writes the {@code pressroom_reader_*}
 * tables.
 *
 * <ul>
 *   <li>Only what {@link ReaderVisibility} allows is saved, followed or
 *       recorded as read ({@link ReaderStateRefusal#NOT_VISIBLE}), and every
 *       list is filtered by it. A row whose entry or weblog is hidden later
 *       stays, and shows again once it is visible.</li>
 *   <li>Saves and follows are idempotent; a read is an upsert of its time,
 *       and a reader's history keeps the newest {@value #HISTORY_CAP}.</li>
 *   <li>Each write commits at once. Deleting an account, entry or weblog
 *       removes its rows by the database's cascade (V056).</li>
 *   <li>Writes are rate limited per account ({@link #admitWrite}).</li>
 *   <li>A newsletter subscribe goes to listmonk with the account's own email
 *       ({@link #subscribe}); its failure is an outcome, never an error.</li>
 * </ul>
 *
 * <p>Users are identified by id only. Entries and weblogs passed in, and those
 * in a {@link ReaderLists}, belong to the current request.
 */
@Component
public class ReaderStateService {

    private static final Logger log = LoggerFactory.getLogger(ReaderStateService.class);

    /** The reads a reader's history keeps. */
    public static final int HISTORY_CAP = 500;
    /** The weblogs {@link ReaderList#PLACES} names. */
    public static final int PLACES = 5;
    /** The entries {@link ReaderList#CONTINUE} offers. */
    public static final int CONTINUE = 3;
    /** The entries {@link ReaderList#FOLLOWED_POSTS} offers. */
    public static final int FOLLOWED_POSTS = 6;

    static final String WRITE_LIMIT = "reader.throttle.window.limit";
    static final String WRITE_WINDOW_SECONDS = "reader.throttle.window.seconds";
    private static final int DEFAULT_WRITE_LIMIT = 120;
    private static final int DEFAULT_WRITE_WINDOW_SECONDS = 600;
    private static final int MAX_WINDOW_SECONDS = 86_400;
    /** As CommentService's throttles: the most recently active accounts, the rest forgotten. */
    private static final int TRACKED_ACCOUNTS = 1000;

    /** Per-account locks (by id hash) around the write count, as CommentService has. */
    private static final int LOCK_STRIPES = 64;
    /** The lists whose size the caller gives. */
    private static final Set<ReaderList> CALLER_LIMITED =
            Collections.unmodifiableSet(EnumSet.of(ReaderList.SAVED, ReaderList.FOLLOWING, ReaderList.HISTORY));
    private static final int LISTMONK_CREATED = 200;
    private static final int LISTMONK_CONFLICT = 409;

    private final Weblogger weblogger;
    private final ReaderStore store;
    private final Clock clock;
    private final GenericThrottle writes;
    private final Supplier<ListmonkClient> listmonk;
    private final Object[] stripes = new Object[LOCK_STRIPES];

    /** What a subscribe came to; each surface words it its own way. */
    public enum SubscribeOutcome {
        /** A new subscription: listmonk sends its confirmation mail. */
        CONFIRM,
        /** The address was already on the list. */
        ALREADY,
        /** Listmonk could not be reached or refused; nothing was recorded. */
        UNAVAILABLE
    }

    /**
     * With the write limit pressroom.properties configures.
     *
     * @param weblogger the lazily-resolved business tier
     * @param strategy  the persistence strategy, resolved on first use: the
     *                  business tier is built lazily, after this bean
     */
    @Autowired
    public ReaderStateService(@Lazy Weblogger weblogger, ObjectProvider<JPAPersistenceStrategy> strategy) {
        this(weblogger, strategy::getObject, Clock.systemUTC(), writeThrottle(ConfigSource.staticConfig()),
                memoized(ListmonkClient::fromConfig));
    }

    /** With every collaborator supplied, so a test controls the time, the limit and listmonk. */
    ReaderStateService(Weblogger weblogger, Supplier<JPAPersistenceStrategy> strategy, Clock clock,
            GenericThrottle writes, Supplier<ListmonkClient> listmonk) {
        this.weblogger = weblogger;
        this.store = new ReaderStore(strategy);
        this.clock = clock;
        this.writes = writes;
        this.listmonk = listmonk;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
    }

    /**
     * {@code factory}'s value, built on first use and kept: the listmonk
     * client reads configuration, which may not be loaded at wiring.
     */
    static <T> Supplier<T> memoized(Supplier<T> factory) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T v = value;
                if (v == null) {
                    synchronized (this) {
                        v = value;
                        if (v == null) {
                            v = factory.get();
                            value = v;
                        }
                    }
                }
                return v;
            }
        };
    }

    // ------------------------------------------------------------- the account

    /**
     * Whether this account may keep reader state: it exists now, is enabled,
     * and holds the global {@code comment} action, which readers, authors
     * and admins all hold. The account is re-read, so one disabled since
     * sign-in is refused.
     */
    public boolean mayUse(User user) throws WebloggerException {
        if (user == null || user.getUserName() == null) {
            return false;
        }
        User current = users().getUserByUserName(user.getUserName(), null);
        return current != null && Boolean.TRUE.equals(current.getEnabled())
                && users().checkPermission(new GlobalPermission(List.of(GlobalPermission.COMMENT)), current);
    }

    /**
     * Counts one write against the account's limit, and answers whether it
     * may go ahead: false once the account has written more than
     * {@code reader.throttle.window.limit} times in the window.
     */
    public boolean admitWrite(User user) {
        // GenericThrottle's count is not atomic; under the account's stripe concurrent writes cannot
        // overshoot the limit. 64 stripes, not a lock per account: no unbounded lock map to clean up.
        synchronized (stripes[Math.floorMod(user.getId().hashCode(), stripes.length)]) {
            return !writes.processHit(user.getId());
        }
    }

    // ------------------------------------------------------------- saves

    /** Saves the entry for the reader; saving it again changes nothing. */
    public void save(User user, WeblogEntry entry) throws WebloggerException, ReaderStateRefusal {
        Instant now = clock.instant();
        if (!ReaderVisibility.isVisible(entry, now)) {
            throw ReaderStateRefusal.NOT_VISIBLE;
        }
        store.save(user.getId(), entry.getId(), now);
        weblogger.flush();
    }

    /** Removes the save, if there is one. */
    public void unsave(User user, String entryId) throws WebloggerException {
        store.unsave(user.getId(), entryId);
        weblogger.flush();
    }

    /**
     * Whether the reader has saved this entry and readers may see it now. A
     * save of an entry hidden since stays stored but is not reported.
     */
    public boolean isSaved(User user, String entryId) throws WebloggerException {
        return StringUtils.isNotBlank(entryId) && store.isSaved(user.getId(), entryId, clock.instant());
    }

    // ------------------------------------------------------------- follows

    /** Follows the weblog for the reader; following it again changes nothing. */
    public void follow(User user, Weblog weblog) throws WebloggerException, ReaderStateRefusal {
        if (!ReaderVisibility.isVisible(weblog)) {
            throw ReaderStateRefusal.NOT_VISIBLE;
        }
        store.follow(user.getId(), weblog.getId(), clock.instant());
        weblogger.flush();
    }

    /** Stops following the weblog, if the reader follows it. */
    public void unfollow(User user, String weblogId) throws WebloggerException {
        store.unfollow(user.getId(), weblogId);
        weblogger.flush();
    }

    /**
     * Whether the reader follows this weblog and readers may see it. A follow
     * of a weblog hidden since stays stored but is not reported.
     */
    public boolean isFollowing(User user, String weblogId) throws WebloggerException {
        return StringUtils.isNotBlank(weblogId) && store.isFollowing(user.getId(), weblogId);
    }

    // ------------------------------------------------------------- history

    /**
     * Records that the reader read the entry now: a first read adds it to the
     * history, a later one moves it to the top. The history then keeps its
     * newest {@value #HISTORY_CAP}.
     */
    public void recordRead(User user, WeblogEntry entry) throws WebloggerException, ReaderStateRefusal {
        Instant now = clock.instant();
        if (!ReaderVisibility.isVisible(entry, now)) {
            throw ReaderStateRefusal.NOT_VISIBLE;
        }
        store.read(user.getId(), entry.getId(), now, HISTORY_CAP);
        weblogger.flush();
    }

    /** Empties the reader's history. */
    public void clearHistory(User user) throws WebloggerException {
        store.clearReads(user.getId());
        weblogger.flush();
        log.info("reading history cleared for {}", user.getUserName());
    }

    // ------------------------------------------------------------- lists

    /**
     * The fixed-size lists ({@link ReaderList#PLACES}, {@link ReaderList#CONTINUE},
     * {@link ReaderList#FOLLOWED_POSTS}, {@link ReaderList#READ_IDS}), for a
     * caller that has no limit to give.
     *
     * @throws IllegalArgumentException when {@code wanted} holds a list whose
     *         size the caller must give
     */
    public ReaderLists lists(User user, Set<ReaderList> wanted) throws WebloggerException {
        if (!Collections.disjoint(wanted, CALLER_LIMITED)) {
            throw new IllegalArgumentException("these lists need a limit: " + wanted);
        }
        return build(user, wanted, 0);
    }

    /**
     * The reader's lists, each holding only what readers may see now. A list
     * not in {@code wanted} is empty.
     *
     * @param limit the most {@link ReaderList#SAVED}, {@link ReaderList#FOLLOWING}
     *              and {@link ReaderList#HISTORY} hold; the other lists have
     *              their own fixed sizes ({@link #PLACES}, {@link #CONTINUE},
     *              {@link #FOLLOWED_POSTS}, {@link #HISTORY_CAP}); at least 1
     *              when one of those three is wanted
     * @throws IllegalArgumentException for a caller-limited list without a limit
     */
    public ReaderLists lists(User user, Set<ReaderList> wanted, int limit) throws WebloggerException {
        if (limit < 1 && !Collections.disjoint(wanted, CALLER_LIMITED)) {
            throw new IllegalArgumentException("a limit of at least 1 is needed for " + wanted);
        }
        return build(user, wanted, limit);
    }

    private ReaderLists build(User user, Set<ReaderList> wanted, int limit) throws WebloggerException {
        Instant now = clock.instant();
        String id = user.getId();
        return new ReaderLists(
                wanted.contains(ReaderList.SAVED) ? entries(store.saved(id, now, limit), now) : List.of(),
                wanted.contains(ReaderList.FOLLOWING) ? weblogs(store.following(id, limit)) : List.of(),
                wanted.contains(ReaderList.HISTORY) ? entries(store.reads(id, now, limit), now) : List.of(),
                wanted.contains(ReaderList.PLACES) ? weblogs(store.places(id, now, PLACES)) : List.of(),
                wanted.contains(ReaderList.CONTINUE) ? entries(store.reads(id, now, CONTINUE), now) : List.of(),
                wanted.contains(ReaderList.FOLLOWED_POSTS)
                        ? entries(store.followedPosts(id, now, FOLLOWED_POSTS), now) : List.of(),
                wanted.contains(ReaderList.READ_IDS)
                        ? Collections.unmodifiableSet(new LinkedHashSet<>(store.reads(id, now, HISTORY_CAP)))
                        : Set.of());
    }

    /** The visible entries of these ids, in order; one gone or hidden since the query is left out. */
    private List<WeblogEntry> entries(List<String> ids, Instant now) throws WebloggerException {
        List<WeblogEntry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            WeblogEntry entry = weblogger.getWeblogEntryManager().getWeblogEntry(id);
            if (ReaderVisibility.isVisible(entry, now)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** The visible weblogs of these ids, in order. */
    private List<Weblog> weblogs(List<String> ids) throws WebloggerException {
        List<Weblog> weblogs = new ArrayList<>(ids.size());
        for (String id : ids) {
            Weblog weblog = weblogger.getWeblogManager().getWeblog(id);
            if (ReaderVisibility.isVisible(weblog)) {
                weblogs.add(weblog);
            }
        }
        return weblogs;
    }

    // ------------------------------------------------------------- newsletter

    /**
     * Whether "Also email me new posts" can be offered for this weblog: it has
     * a listmonk list, the account has an email address, and listmonk is
     * configured. Pressroom keeps no subscription state.
     */
    public boolean newsletterOffered(User user, Weblog weblog) {
        return weblog != null
                && StringUtils.isNotBlank(weblog.getNewsletterListUuid())
                && StringUtils.isNotBlank(user.getEmailAddress())
                && !listmonk.get().isUnconfigured();
    }

    /**
     * Subscribes the account's own email to the weblog's list through
     * listmonk's public call, as the form does, and records
     * {@code NEWSLETTER_SUBSCRIBED} for a new subscription only. A failure is
     * an outcome, never an exception: listmonk being down must not fail the
     * reader's action (Review Focus 4).
     *
     * @throws ReaderStateRefusal {@code NOT_VISIBLE} for a weblog readers may
     *         not see, or one where {@link #newsletterOffered} is false
     */
    public SubscribeOutcome subscribe(User user, Weblog weblog) throws ReaderStateRefusal {
        if (!ReaderVisibility.isVisible(weblog) || !newsletterOffered(user, weblog)) {
            throw ReaderStateRefusal.NOT_VISIBLE;
        }
        int status;
        try {
            status = listmonk.get().subscribe(user.getEmailAddress().trim(), weblog.getNewsletterListUuid());
        } catch (IOException ex) {
            log.error("Could not forward a reader's newsletter subscription to Listmonk for weblog {}",
                    weblog.getHandle(), ex);
            return SubscribeOutcome.UNAVAILABLE;
        }
        if (status == LISTMONK_CREATED) {
            NewsletterEvents.recordSubscribed(weblogger, weblog);
            return SubscribeOutcome.CONFIRM;
        }
        if (status == LISTMONK_CONFLICT) {
            return SubscribeOutcome.ALREADY;
        }
        log.warn("Listmonk answered {} to a reader's newsletter subscription for weblog {}", status,
                weblog.getHandle());
        return SubscribeOutcome.UNAVAILABLE;
    }

    // ------------------------------------------------------------- the limit

    /**
     * The write limit {@code config} configures, keyed by account id: at most
     * {@link #writeLimit} writes in {@link #writeWindowSeconds}.
     */
    static GenericThrottle writeThrottle(ConfigSource config) {
        int allowed = writeLimit(config);
        int seconds = writeWindowSeconds(config);
        log.info("reader state write limit: {} per {}s per account", allowed, seconds);
        // admitWrite counts the hit first and GenericThrottle.processHit answers true once the count
        // EXCEEDS the threshold, so "at most allowed" is a threshold of allowed (CommentService, which
        // checks before it counts, uses one lower).
        return new GenericThrottle(allowed, seconds * PressroomConstants.SEC_IN_MS, TRACKED_ACCOUNTS);
    }

    /** {@code reader.throttle.window.limit}: writes allowed in a window, default 120. */
    static int writeLimit(ConfigSource config) {
        return config.intOr(WRITE_LIMIT, DEFAULT_WRITE_LIMIT, v -> v >= 1);
    }

    /** {@code reader.throttle.window.seconds}: the window, default 600, at most a day. */
    static int writeWindowSeconds(ConfigSource config) {
        return config.intOr(WRITE_WINDOW_SECONDS, DEFAULT_WRITE_WINDOW_SECONDS,
                v -> v >= 1 && v <= MAX_WINDOW_SECONDS);
    }

    private UserManager users() {
        return weblogger.getUserManager();
    }
}
