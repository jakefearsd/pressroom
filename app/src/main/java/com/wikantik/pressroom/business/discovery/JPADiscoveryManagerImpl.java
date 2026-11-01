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
import java.util.concurrent.atomic.AtomicReference;

/**
 * The discovery picker over Pressroom's own database (candidates) and a
 * {@link TrafficSource} (scores).
 *
 * <p>A refresh builds an immutable {@link Snapshot} and swaps it in through a
 * volatile reference, so readers never wait on a refresh; only the very first
 * selection, when there is no snapshot at all, builds one synchronously, and
 * concurrent first requests share that one build. Selections are computed as
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

    /** Serialises refreshes, including the first build. Readers never take it. */
    private final Object refreshLock = new Object();

    private volatile Snapshot snapshot;
    private volatile DiscoveryStatus status =
            new DiscoveryStatus(DiscoveryStatus.State.NEVER, null, null, 0, 0, null);

    public JPADiscoveryManagerImpl(Weblogger weblogger, TrafficSource trafficSource) {
        this(weblogger, trafficSource, Clock.systemUTC());
    }

    JPADiscoveryManagerImpl(Weblogger weblogger, TrafficSource trafficSource, Clock clock) {
        this.weblogger = weblogger;
        this.trafficSource = trafficSource;
        this.clock = clock;
    }

    // ------------------------------------------------------------ refresh

    @Override
    public void refresh() {
        synchronized (refreshLock) {
            rebuild();
        }
    }

    private void rebuild() {
        Instant now = clock.instant();
        DiscoveryStatus before = status;
        List<DiscoveryCandidate> candidates;
        List<Weblog> weblogs;
        try {
            candidates = weblogger.getWeblogEntryManager().getDiscoveryCandidates();
            weblogs = weblogger.getWeblogManager().getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1);
        } catch (WebloggerException | RuntimeException e) {
            // nothing new to swap in: the previous snapshot (if any) stands
            log.error("Discovery refresh could not read candidates; keeping the last snapshot", e);
            status = new DiscoveryStatus(DiscoveryStatus.State.FAILED, now, before.lastSuccess(),
                    before.scoredEntries(), before.candidates(), describe(e));
            return;
        }

        Snapshot previous = snapshot;
        Map<String, Long> scores;
        DiscoveryStatus.State state;
        Instant lastSuccess = before.lastSuccess();
        String error = null;
        if (trafficSource.isConfigured()) {
            LocalDate firstDay = LocalDate.ofInstant(now, ZoneOffset.UTC)
                    .minusDays(DiscoverySettings.windowDays() - 1L);
            try {
                scores = score(trafficSource.viewsSince(firstDay), candidates, weblogs);
                state = DiscoveryStatus.State.OK;
                lastSuccess = now;
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

        // always a new generation, even within one millisecond of the last:
        // a rebuild is what reshuffles the random picks
        DiscoveryGeneration.advance(Math.max(now.toEpochMilli(), DiscoveryGeneration.current() + 1));
        Snapshot built = new Snapshot(DiscoveryGeneration.current(), now, candidates, weblogs, scores);
        snapshot = built;
        status = new DiscoveryStatus(state, now, lastSuccess, built.scoredEntries(),
                built.pool.size(), error);
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

    @Override
    public DiscoveryStatus getStatus() {
        return status;
    }

    /** The current snapshot, building the first one if there is none; null if that build failed. */
    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (refreshLock) {
                if (snapshot == null) {
                    rebuild();
                }
                s = snapshot;
            }
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
        return resolve(ids);
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

    private FrontPageSelection resolve(FrontPageIds ids) {
        WeblogEntry headline = ids.headline() == null ? null : entry(ids.headline());
        List<WeblogEntry> popular = new ArrayList<>(entries(ids.popular()));
        List<WeblogEntry> different = new ArrayList<>(entries(ids.different()));
        if (headline == null) {
            // the headline stopped being eligible since the snapshot: the
            // next-ranked survivor leads rather than leaving the top empty
            if (!popular.isEmpty()) {
                headline = popular.remove(0);
            } else if (!different.isEmpty()) {
                headline = different.remove(0);
            }
        }
        List<BlogCard> cards = new ArrayList<>();
        for (CardIds card : ids.cards()) {
            Weblog weblog = weblog(card.weblogId());
            List<WeblogEntry> entries = weblog == null ? List.of() : entries(card.entryIds());
            if (!entries.isEmpty()) {
                cards.add(new BlogCard(weblog, entries));
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
        return new FrontPageSelection(headline, popular, cards, different, allBlogs);
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

            Comparator<DiscoveryCandidate> newestFirst = Comparator.comparing(DiscoveryCandidate::pubTime,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            this.ranked = pool.stream().filter(c -> this.scores.containsKey(c.entryId()))
                    .sorted(Comparator.comparingLong((DiscoveryCandidate c) -> score(c)).reversed()
                            .thenComparing(newestFirst).thenComparing(DiscoveryCandidate::entryId))
                    .toList();
            this.newest = pool.stream()
                    .min(newestFirst.thenComparing(DiscoveryCandidate::entryId)).orElse(null);

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
