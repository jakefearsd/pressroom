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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.util.DiscoveryGeneration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The discovery picker over Pressroom's own database (candidates) and a
 * {@link TrafficSource} (scores).
 *
 * <p>A refresh builds an immutable {@link Snapshot} and swaps it in through a
 * volatile reference, so readers never wait on a refresh. Only the very first
 * selection, when there is no snapshot at all (after every restart, while the
 * scheduled refresh may still be an interval away), builds one on the calling
 * thread, and concurrent first requests share that one build. It reads
 * candidates only, one local query, and hands the scored refresh to the
 * background pool. It never takes the refresh lock, so a refresh already
 * waiting on Umami does not hold it up either: no page ever waits on the
 * traffic source. Selections are computed as
 * ids once per snapshot (seeded by its generation, so random picks hold until
 * the next rebuild) and resolved to entities on every call, dropping anything
 * that stopped being eligible in between.
 */
public class JPADiscoveryManagerImpl implements DiscoveryManager {

    private static final Logger log = LoggerFactory.getLogger(JPADiscoveryManagerImpl.class);

    /** Section sizes: fixed numbers, not settings (spec, "Front page"). */
    static final int POPULAR_SLOTS = 4;
    static final int BLOG_CARDS = 3;
    static final int CARD_ENTRIES = 3;
    static final int DIFFERENT_SLOTS = 3;

    /** "Something different" is all random. */
    private static final int ALL_RANDOM = 100;

    /** Random picks of content published this recently weigh double. */
    private static final Duration RECENT = Duration.ofDays(30);
    private static final double RECENT_BOOST = 2.0;

    /** Seed salts, one per section, so sections draw independently. */
    private static final int SALT_POPULAR = 1;
    private static final int SALT_BLOGS = 2;
    private static final int SALT_CARD_ENTRIES = 3;
    private static final int SALT_DIFFERENT = 4;

    private static final int SEED_MULTIPLIER = 31;

    private final Weblogger weblogger;
    private final TrafficSource trafficSource;
    private final Clock clock;
    private final Executor background;

    /** Serialises refreshes. Readers and the first build never take it. */
    private final Object refreshLock = new Object();

    /** Serialises first builds, so concurrent first requests share one. */
    private final Object firstBuildLock = new Object();

    /**
     * Guards the swap: snapshot, generation and status move together. Held
     * for no I/O, so a first build that meets a refresh's swap waits only for
     * a few assignments.
     */
    private final Object swapLock = new Object();

    private volatile Snapshot snapshot;
    private volatile DiscoveryStatus status =
            new DiscoveryStatus(DiscoveryStatus.State.NEVER, null, null, 0, 0, null);

    public JPADiscoveryManagerImpl(Weblogger weblogger, TrafficSource trafficSource) {
        this(weblogger, trafficSource, Clock.systemUTC(), inBackgroundOf(weblogger));
    }

    JPADiscoveryManagerImpl(Weblogger weblogger, TrafficSource trafficSource, Clock clock, Executor background) {
        this.weblogger = weblogger;
        this.trafficSource = trafficSource;
        this.clock = clock;
        this.background = background;
    }

    /** The tier's background pool; a scheduling failure surfaces as a rejection. */
    private static Executor inBackgroundOf(Weblogger weblogger) {
        return task -> {
            try {
                weblogger.getThreadManager().executeInBackground(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("interrupted while scheduling", e);
            }
        };
    }

    // ------------------------------------------------------------ refresh

    @Override
    public void refresh() {
        synchronized (refreshLock) {
            rebuild(true);
        }
    }

    /**
     * Builds and swaps in a snapshot. With {@code readTraffic} false (the
     * first build, on a request thread) a configured source is not read: the
     * snapshot has no scores and the status still says how the last real
     * refresh went, and it is swapped in only if no refresh got there first.
     *
     * @return whether a snapshot was swapped in
     */
    private boolean rebuild(boolean readTraffic) {
        Instant now = clock.instant();
        List<DiscoveryCandidate> candidates;
        List<Weblog> weblogs;
        try {
            candidates = weblogger.getWeblogEntryManager().getDiscoveryCandidates();
            weblogs = weblogger.getWeblogManager().getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1);
        } catch (WebloggerException | RuntimeException e) {
            // nothing new to swap in: the previous snapshot (if any) stands
            log.error("Discovery refresh could not read candidates; keeping the last snapshot", e);
            synchronized (swapLock) {
                DiscoveryStatus before = status;
                status = new DiscoveryStatus(DiscoveryStatus.State.FAILED, now, before.lastSuccess(),
                        before.scoredEntries(), before.candidates(), describe(e));
            }
            return false;
        }

        Snapshot previous = snapshot;
        Map<String, Long> scores;
        // null: carry the last real refresh's state, attempt and error forward
        DiscoveryStatus.State state;
        boolean succeeded = false;
        String error = null;
        if (trafficSource.isConfigured() && !readTraffic) {
            scores = Map.of();
            state = null;
        } else if (trafficSource.isConfigured()) {
            LocalDate firstDay = LocalDate.ofInstant(now, ZoneOffset.UTC)
                    .minusDays(DiscoverySettings.windowDays() - 1L);
            try {
                scores = score(trafficSource.viewsSince(firstDay), candidates, weblogs);
                state = DiscoveryStatus.State.OK;
                succeeded = true;
            } catch (WebloggerException | RuntimeException e) {
                log.error("Discovery refresh could not read traffic; keeping the last scores", e);
                scores = previous == null ? Map.of() : previous.scores;
                state = DiscoveryStatus.State.FAILED;
                error = describe(e);
            }
        } else {
            scores = Map.of();
            state = DiscoveryStatus.State.DISABLED;
        }

        // Always a new generation, even within one millisecond of the last:
        // a rebuild is what reshuffles the random picks.
        //
        // Stamped now, after every read, not when the rebuild started: a page
        // rendered from the old snapshot while the reads ran is dated by the
        // old generation, so its date may be later than the start, and a
        // generation older than that page would leave it cached (and 304'd)
        // for a whole extra interval.
        //
        // Ordering invariant: the snapshot is swapped in BEFORE its generation
        // is published. A rendering dated by DiscoveryGeneration.current() must
        // already read that generation's snapshot; published the other way
        // round, a page rendered in between carries the old picks under the
        // new date, and a generation-keyed cache keeps them a whole interval.
        // Both writes are volatile, so a reader that sees the new generation
        // also sees the new snapshot. Swaps are serialised by swapLock, so
        // nothing else moves the generation between these lines.
        synchronized (swapLock) {
            if (!readTraffic && snapshot != null) {
                // a refresh swapped in while the candidates were read: newer, and scored
                return false;
            }
            DiscoveryStatus before = status;
            long generation = Math.max(clock.instant().toEpochMilli(), DiscoveryGeneration.current() + 1);
            Snapshot built = new Snapshot(generation, now, candidates, weblogs, scores);
            snapshot = built;
            DiscoveryGeneration.advance(generation);
            boolean carried = state == null;
            status = new DiscoveryStatus(carried ? before.state() : state,
                    carried ? before.lastAttempt() : now,
                    succeeded ? now : before.lastSuccess(),
                    built.scoredEntries(), built.pool.size(),
                    carried ? before.lastError() : error);
            return true;
        }
    }

    /**
     * Hands the first scored refresh to the background pool. Display path: a
     * refresh that cannot be scheduled leaves the unscored snapshot to the
     * scheduled task, and the page is unaffected.
     */
    private void scoreInBackground() {
        try {
            background.execute(() -> {
                try {
                    refresh();
                } finally {
                    weblogger.release();
                }
            });
        } catch (RuntimeException e) {
            log.warn("Discovery could not schedule the first scored refresh; the scheduled one will score", e);
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Views per candidate entry id. A row maps through the weblog whose
     * analytics site id matches its website id (ignoring case) and the entry
     * with that anchor in that weblog; a row that maps to nothing is ignored.
     */
    private static Map<String, Long> score(List<TrafficRow> rows, List<DiscoveryCandidate> candidates,
            List<Weblog> weblogs) {
        Map<String, List<String>> weblogsBySite = new HashMap<>();
        for (Weblog weblog : weblogs) {
            String siteId = weblog.getAnalyticsSiteId();
            if (siteId != null && !siteId.isBlank()) {
                weblogsBySite.computeIfAbsent(siteId.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(weblog.getId());
            }
        }
        Map<String, Map<String, String>> entryByAnchor = new HashMap<>();
        for (DiscoveryCandidate c : candidates) {
            if (c.anchor() != null) {
                entryByAnchor.computeIfAbsent(c.weblogId(), k -> new HashMap<>()).put(c.anchor(), c.entryId());
            }
        }
        Map<String, Long> scores = new HashMap<>();
        for (TrafficRow row : rows) {
            if (row.websiteId() == null || row.entryAnchor() == null || row.views() <= 0) {
                continue;
            }
            for (String weblogId : weblogsBySite.getOrDefault(
                    row.websiteId().trim().toLowerCase(Locale.ROOT), List.of())) {
                String entryId = entryByAnchor.getOrDefault(weblogId, Map.of()).get(row.entryAnchor());
                if (entryId != null) {
                    scores.merge(entryId, row.views(), Long::sum);
                }
            }
        }
        return Map.copyOf(scores);
    }

    /** The generation the current snapshot was built as; 0 before the first. For tests. */
    long snapshotGeneration() {
        Snapshot s = snapshot;
        return s == null ? 0 : s.generation;
    }

    @Override
    public DiscoveryStatus getStatus() {
        return status;
    }

    /**
     * The current snapshot, building the first one if there is none; null if
     * that build failed. The first build reads candidates only; the scored
     * refresh follows in the background.
     */
    private Snapshot current() {
        Snapshot s = snapshot;
        if (s != null) {
            return s;
        }
        boolean built = false;
        synchronized (firstBuildLock) {
            if (snapshot == null) {
                built = rebuild(false);
            }
            s = snapshot;
        }
        if (built && trafficSource.isConfigured()) {
            scoreInBackground();
        }
        return s;
    }

    // ------------------------------------------------------------ front page

    @Override
    public FrontPageSelection selectFrontPage() {
        Snapshot s = current();
        if (s == null) {
            return FrontPageSelection.empty();
        }
        FrontPageIds ids = s.frontPage.get();
        if (ids == null) {
            // deterministic for the snapshot, so a racing duplicate computes the same ids
            s.frontPage.compareAndSet(null, pickFrontPage(s));
            ids = s.frontPage.get();
        }
        return resolve(s, ids);
    }

    private FrontPageIds pickFrontPage(Snapshot s) {
        int share = DiscoverySettings.sharePercent();
        Set<DiscoveryCandidate> taken = new HashSet<>();

        // promoted first: the promotion project fills slots here, before ranking

        DiscoveryCandidate headline = s.ranked.isEmpty() ? s.newest : s.ranked.get(0);
        if (headline != null) {
            taken.add(headline);
        }
        List<DiscoveryCandidate> popular = DiscoverySlots.fill(s.ranked, s.pool, POPULAR_SLOTS, share, taken,
                s.random(SALT_POPULAR), s::weight);

        List<String> cardWeblogs = DiscoverySlots.fill(
                withUntaken(s, s.rankedWeblogs, taken), withUntaken(s, s.weblogsWithEntries, taken),
                BLOG_CARDS, share, new HashSet<>(), s.random(SALT_BLOGS), s::blogWeight);
        Random cardRandom = s.random(SALT_CARD_ENTRIES);
        List<CardIds> cards = new ArrayList<>();
        for (String weblogId : cardWeblogs) {
            List<DiscoveryCandidate> entries = DiscoverySlots.fill(
                    s.rankedByWeblog.getOrDefault(weblogId, List.of()),
                    s.poolByWeblog.getOrDefault(weblogId, List.of()),
                    CARD_ENTRIES, share, taken, cardRandom, s::weight);
            cards.add(new CardIds(weblogId, entryIds(entries)));
        }

        List<DiscoveryCandidate> different = DiscoverySlots.fill(List.of(), s.pool, DIFFERENT_SLOTS, ALL_RANDOM,
                taken, s.random(SALT_DIFFERENT), s::weight);

        return new FrontPageIds(headline == null ? null : headline.entryId(), entryIds(popular), cards,
                entryIds(different), s.weblogIds);
    }

    /** The weblogs among {@code weblogIds} that still have an entry no section has taken. */
    private static List<String> withUntaken(Snapshot s, List<String> weblogIds, Set<DiscoveryCandidate> taken) {
        List<String> result = new ArrayList<>();
        for (String weblogId : weblogIds) {
            for (DiscoveryCandidate c : s.poolByWeblog.getOrDefault(weblogId, List.of())) {
                if (!taken.contains(c)) {
                    result.add(weblogId);
                    break;
                }
            }
        }
        return result;
    }

    private FrontPageSelection resolve(Snapshot s, FrontPageIds ids) {
        WeblogEntry headline = ids.headline() == null ? null : entry(ids.headline());
        List<WeblogEntry> popular = new ArrayList<>(entries(ids.popular()));
        List<WeblogEntry> different = new ArrayList<>(entries(ids.different()));
        List<Weblog> cardWeblogs = new ArrayList<>();
        List<List<WeblogEntry>> cardEntries = new ArrayList<>();
        for (CardIds card : ids.cards()) {
            Weblog weblog = weblog(card.weblogId());
            if (weblog != null) {
                cardWeblogs.add(weblog);
                cardEntries.add(new ArrayList<>(entries(card.entryIds())));
            }
        }
        if (headline == null) {
            headline = replacementHeadline(s, ids.headline(), popular, different, cardEntries);
        }
        List<BlogCard> cards = new ArrayList<>();
        for (int i = 0; i < cardWeblogs.size(); i++) {
            if (!cardEntries.get(i).isEmpty()) {
                cards.add(new BlogCard(cardWeblogs.get(i), cardEntries.get(i)));
            }
        }
        List<Weblog> allBlogs = new ArrayList<>();
        for (String weblogId : ids.allBlogs()) {
            Weblog weblog = weblog(weblogId);
            if (weblog != null) {
                allBlogs.add(weblog);
            }
        }
        allBlogs.sort(Comparator.comparing(JPADiscoveryManagerImpl::sortName, String.CASE_INSENSITIVE_ORDER));
        // ranked only when the headline has views of its own: the newest post,
        // or a replacement taken from the newest, is not "most read"
        boolean ranked = headline != null && s.scores.containsKey(headline.getId());
        return new FrontPageSelection(headline, ranked, popular, cards, different, allBlogs);
    }

    /**
     * The headline when the memoized one stopped being eligible. Never a
     * random pick: the best-ranked, then newest, survivor not already on the
     * page; if every survivor is on the page, the best-ranked, then newest,
     * of those, moved out of its section so it is not shown twice. Null only
     * when no candidate survives at all.
     */
    private WeblogEntry replacementHeadline(Snapshot s, String dropped, List<WeblogEntry> popular,
            List<WeblogEntry> different, List<List<WeblogEntry>> cardEntries) {
        List<List<WeblogEntry>> sections = new ArrayList<>();
        sections.add(popular);
        sections.addAll(cardEntries);
        sections.add(different);
        Map<String, List<WeblogEntry>> shownIn = new HashMap<>();
        for (List<WeblogEntry> section : sections) {
            for (WeblogEntry entry : section) {
                shownIn.put(entry.getId(), section);
            }
        }
        List<DiscoveryCandidate> order = new ArrayList<>(s.ranked);
        order.addAll(s.newestFirst);
        Set<String> tried = new HashSet<>();
        if (dropped != null) {
            tried.add(dropped);
        }
        for (DiscoveryCandidate c : order) {
            if (!shownIn.containsKey(c.entryId()) && tried.add(c.entryId())) {
                WeblogEntry entry = entry(c.entryId());
                if (entry != null) {
                    return entry;
                }
            }
        }
        for (DiscoveryCandidate c : order) {
            List<WeblogEntry> section = shownIn.get(c.entryId());
            if (section != null) {
                for (int i = 0; i < section.size(); i++) {
                    if (section.get(i).getId().equals(c.entryId())) {
                        return section.remove(i);
                    }
                }
            }
        }
        return null;
    }

    private static String sortName(Weblog weblog) {
        return weblog.getName() == null ? "" : weblog.getName();
    }

    // ------------------------------------------------------------ more from the site

    @Override
    public List<WeblogEntry> selectMoreFromSite(Weblog current, int count) {
        if (count <= 0) {
            return List.of();
        }
        Snapshot s = current();
        if (s == null) {
            return List.of();
        }
        String excluded = current == null ? null : current.getId();
        List<String> ids = s.moreFromSite.computeIfAbsent(excluded + "#" + count,
                key -> pickMoreFromSite(s, excluded, count));
        List<WeblogEntry> result = new ArrayList<>();
        for (WeblogEntry entry : entries(ids)) {
            if (excluded == null || !excluded.equals(entry.getWebsite().getId())) {
                result.add(entry);
            }
        }
        return result;
    }

    private static List<String> pickMoreFromSite(Snapshot s, String excluded, int count) {
        List<DiscoveryCandidate> ranked = new ArrayList<>();
        for (DiscoveryCandidate c : s.ranked) {
            if (!c.weblogId().equals(excluded)) {
                ranked.add(c);
            }
        }
        List<DiscoveryCandidate> pool = new ArrayList<>();
        for (DiscoveryCandidate c : s.pool) {
            if (!c.weblogId().equals(excluded)) {
                pool.add(c);
            }
        }
        int salt = excluded == null ? 0 : excluded.hashCode();
        return entryIds(DiscoverySlots.fill(ranked, pool, count, DiscoverySettings.sharePercent(),
                new HashSet<>(), s.random(salt), s::weight));
    }

    // ------------------------------------------------------------ resolution

    private List<WeblogEntry> entries(List<String> ids) {
        List<WeblogEntry> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            WeblogEntry entry = entry(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** The entry, or null when it is gone or no longer eligible. */
    private WeblogEntry entry(String id) {
        try {
            WeblogEntry entry = weblogger.getWeblogEntryManager().getWeblogEntry(id);
            return isEligible(entry) ? entry : null;
        } catch (WebloggerException e) {
            // display path: an entry that cannot be read is left out of the page
            log.warn("Discovery could not load entry {}", id, e);
            return null;
        }
    }

    /** The weblog, or null when it is gone or no longer eligible. */
    private Weblog weblog(String id) {
        try {
            Weblog weblog = weblogger.getWeblogManager().getWeblog(id);
            return isEligible(weblog) ? weblog : null;
        } catch (WebloggerException e) {
            // display path: a weblog that cannot be read is left out of the page
            log.warn("Discovery could not load weblog {}", id, e);
            return null;
        }
    }

    static boolean isEligible(WeblogEntry entry) {
        return entry != null
                && entry.getStatus() == PubStatus.PUBLISHED
                && !Boolean.TRUE.equals(entry.getNoindex())
                && isEligible(entry.getWebsite());
    }

    static boolean isEligible(Weblog weblog) {
        return weblog != null
                && !weblog.isSiteSpace()
                && Boolean.TRUE.equals(weblog.getVisible())
                && Boolean.TRUE.equals(weblog.getActive());
    }

    private static List<String> entryIds(List<DiscoveryCandidate> candidates) {
        return candidates.stream().map(DiscoveryCandidate::entryId).toList();
    }

    // ------------------------------------------------------------ snapshot

    private record CardIds(String weblogId, List<String> entryIds) {
    }

    private record FrontPageIds(String headline, List<String> popular, List<CardIds> cards,
            List<String> different, List<String> allBlogs) {
    }

    /**
     * One generation's candidates and scores, indexed for selection, plus the
     * selections already computed from it. Immutable apart from those memos,
     * which are filled at most once per key.
     */
    private static final class Snapshot {
        final long generation;
        final Instant recentSince;
        final List<DiscoveryCandidate> pool;
        final Map<String, Long> scores;
        /** Scored candidates, highest first. */
        final List<DiscoveryCandidate> ranked;
        final DiscoveryCandidate newest;
        /** Every candidate, newest first. */
        final List<DiscoveryCandidate> newestFirst;
        final Map<String, List<DiscoveryCandidate>> poolByWeblog;
        final Map<String, List<DiscoveryCandidate>> rankedByWeblog;
        final Map<String, Long> weblogScores;
        /** Weblogs with a score, highest first. */
        final List<String> rankedWeblogs;
        final List<String> weblogsWithEntries;
        /** Every eligible weblog, with or without entries. */
        final List<String> weblogIds;

        final AtomicReference<FrontPageIds> frontPage = new AtomicReference<>();
        final ConcurrentMap<String, List<String>> moreFromSite = new ConcurrentHashMap<>();

        Snapshot(long generation, Instant builtAt, List<DiscoveryCandidate> candidates,
                List<Weblog> weblogs, Map<String, Long> scores) {
            this.generation = generation;
            this.recentSince = builtAt.minus(RECENT);
            Set<String> eligibleWeblogs = new HashSet<>();
            List<String> weblogIdList = new ArrayList<>();
            for (Weblog weblog : weblogs) {
                if (isEligible(weblog) && eligibleWeblogs.add(weblog.getId())) {
                    weblogIdList.add(weblog.getId());
                }
            }
            this.weblogIds = List.copyOf(weblogIdList);

            List<DiscoveryCandidate> eligible = new ArrayList<>();
            for (DiscoveryCandidate c : candidates) {
                if (eligibleWeblogs.contains(c.weblogId())) {
                    eligible.add(c);
                }
            }
            eligible.sort(Comparator.comparing(DiscoveryCandidate::entryId));
            this.pool = List.copyOf(eligible);

            Map<String, Long> kept = new HashMap<>();
            for (DiscoveryCandidate c : pool) {
                Long score = scores.get(c.entryId());
                if (score != null && score > 0) {
                    kept.put(c.entryId(), score);
                }
            }
            this.scores = Map.copyOf(kept);

            Comparator<DiscoveryCandidate> byNewest = Comparator.comparing(DiscoveryCandidate::pubTime,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            this.ranked = pool.stream().filter(c -> this.scores.containsKey(c.entryId()))
                    .sorted(Comparator.comparingLong((DiscoveryCandidate c) -> score(c)).reversed()
                            .thenComparing(byNewest).thenComparing(DiscoveryCandidate::entryId))
                    .toList();
            this.newestFirst = pool.stream()
                    .sorted(byNewest.thenComparing(DiscoveryCandidate::entryId)).toList();
            this.newest = this.newestFirst.isEmpty() ? null : this.newestFirst.get(0);

            Map<String, List<DiscoveryCandidate>> byWeblog = new HashMap<>();
            for (DiscoveryCandidate c : pool) {
                byWeblog.computeIfAbsent(c.weblogId(), k -> new ArrayList<>()).add(c);
            }
            Map<String, List<DiscoveryCandidate>> rankedBy = new HashMap<>();
            for (DiscoveryCandidate c : ranked) {
                rankedBy.computeIfAbsent(c.weblogId(), k -> new ArrayList<>()).add(c);
            }
            this.poolByWeblog = copyOfLists(byWeblog);
            this.rankedByWeblog = copyOfLists(rankedBy);

            Map<String, Long> blogTotals = new HashMap<>();
            for (DiscoveryCandidate c : ranked) {
                blogTotals.merge(c.weblogId(), score(c), Long::sum);
            }
            this.weblogScores = Map.copyOf(blogTotals);
            this.rankedWeblogs = weblogIds.stream().filter(weblogScores::containsKey)
                    .sorted(Comparator.<String, Long>comparing(weblogScores::get).reversed()
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
            this.weblogsWithEntries = weblogIds.stream().filter(poolByWeblog::containsKey).toList();
        }

        private static Map<String, List<DiscoveryCandidate>> copyOfLists(Map<String, List<DiscoveryCandidate>> map) {
            Map<String, List<DiscoveryCandidate>> copy = new HashMap<>();
            map.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return Map.copyOf(copy);
        }

        long score(DiscoveryCandidate c) {
            return scores.getOrDefault(c.entryId(), 0L);
        }

        int scoredEntries() {
            return scores.size();
        }

        /** Random picks lean toward less-read and recent content. */
        double weight(DiscoveryCandidate c) {
            double weight = 1.0 / (1 + score(c));
            return c.pubTime() != null && c.pubTime().isAfter(recentSince) ? weight * RECENT_BOOST : weight;
        }

        double blogWeight(String weblogId) {
            return 1.0 / (1 + weblogScores.getOrDefault(weblogId, 0L));
        }

        @SuppressFBWarnings(
                value = "PREDICTABLE_RANDOM",
                justification = "Predictable on purpose: the seed is the snapshot generation, so a "
                        + "section's random picks are the same on every request until the next "
                        + "rebuild (spec, \"Reshuffle cadence\"). Nothing secret is drawn.")
        Random random(int salt) {
            return new Random(generation * SEED_MULTIPLIER + salt);
        }
    }
}
